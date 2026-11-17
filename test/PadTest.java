import io.github.tvoverlaysuite.PadProto;
import io.github.tvoverlaysuite.PadRelay;

import java.util.ArrayList;
import java.util.List;

/** v1.6: trackpad messages (checked and re-written before an app sees them) and the front-app relay. */
public class PadTest {
    static int fails;

    static void check(boolean ok, String m) {
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + m);
    }

    static void eq(String got, String want, String m) { check(want == null ? got == null : want.equals(got), m + " (got " + got + ")"); }

    static final class FakeApp implements PadRelay.App {
        final List<String> got = new ArrayList<>();
        public void send(String line) { got.add(line); }
    }

    public static void main(String[] a) {
        // ---- phone lines become app lines
        eq(PadProto.move(3.04f, -12.36f), "P m 3 -12.4", "move rounds to one decimal");
        eq(PadProto.move(-0.04f, 0.05f), "P m 0 0.1", "tiny moves round, no minus zero");
        eq(PadProto.toApp("P m 3 -12.4"), "m 3 -12.4", "move passes");
        eq(PadProto.toApp("P s 0 250.5"), "s 0 250.5", "scroll passes");
        eq(PadProto.toApp("P m 1e9 2"), "m 4000 2", "huge move is clamped");
        eq(PadProto.toApp("P m NaN 2"), null, "NaN refused");
        eq(PadProto.toApp("P m 1 2 3"), null, "extra field refused");
        eq(PadProto.toApp("P m 1"), null, "missing field refused");
        eq(PadProto.toApp("P c"), "c", "click");
        eq(PadProto.toApp("P c x"), null, "click with junk refused");
        eq(PadProto.toApp("P r"), "r", "menu");
        eq(PadProto.toApp("P d 1"), "d 1", "drag down");
        eq(PadProto.toApp("P d 2"), null, "bad drag refused");
        eq(PadProto.toApp("P k BACK"), "k back", "key normalised");
        eq(PadProto.toApp("P k rm"), null, "unknown key refused");
        eq(PadProto.toApp("P k back\nc"), null, "embedded line break refused");
        eq(PadProto.toApp("T w"), null, "typing line is not a trackpad line");
        StringBuilder big = new StringBuilder("P m 1 ");
        while (big.length() < 400) big.append('9');
        eq(PadProto.toApp(big.toString()), null, "over-long line refused");

        PadProto.Front f = PadProto.parseFront(PadProto.front(true, "TV Browser"));
        check(f != null && f.on && f.name.equals("TV Browser"), "front name round-trips");
        f = PadProto.parseFront("P f 0");
        check(f != null && !f.on, "front off parses");
        f = PadProto.parseFront("P f 1 !!!");
        check(f != null && f.on && f.name.isEmpty(), "bad name: still on, no name");

        // ---- relay
        List<String> phones = new ArrayList<>();
        PadRelay r = new PadRelay(phones::add);
        FakeApp a1 = new FakeApp(), a2 = new FakeApp();
        check(!r.fromPhone("P m 1 1"), "nothing in front: move goes nowhere");
        eq(r.frontLine(), "P f 0", "initial state is off");
        r.fromApp(a1, "h 1");
        check(phones.isEmpty(), "hello alone changes nothing");
        r.fromApp(a1, "f 1 " + io.github.tvoverlaysuite.TypeProto.b64("TV Browser"));
        eq(phones.get(phones.size() - 1), PadProto.front(true, "TV Browser"), "phones told the app came to the front");
        check(r.fromPhone("P m 5 -2") && a1.got.get(0).equals("m 5 -2"), "move reaches the front app");
        r.fromPhone("P x 1");
        check(a1.got.size() == 1, "junk never reaches the app");
        r.fromApp(a2, "f 1 " + io.github.tvoverlaysuite.TypeProto.b64("Other"));
        r.fromPhone("P c");
        check(a2.got.size() == 1 && a1.got.size() == 1, "only the newest front app gets input");
        int n = phones.size();
        r.fromApp(a1, "f 0");
        check(phones.size() == n, "a background app leaving changes nothing");
        r.closed(a2);
        eq(phones.get(phones.size() - 1), "P f 0", "front app disconnecting turns the trackpad off");
        check(!r.fromPhone("P c"), "after that, input goes nowhere");
        r.fromApp(a1, "f 1 -");
        eq(r.frontLine(), "P f 1 -", "app without a name");
        n = phones.size();
        r.fromApp(a1, "f 1 -");
        check(phones.size() == n, "repeating f 1 does not spam the phones");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
