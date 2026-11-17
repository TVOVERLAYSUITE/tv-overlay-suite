import io.github.tvoverlaysuite.CamProto;
import io.github.tvoverlaysuite.CamRelay;
import io.github.tvoverlaysuite.TypeProto;

import java.util.ArrayList;
import java.util.List;

/** v1.7: phone-camera messages (checked before they are passed on) and the relay's rules. */
public class CamTest {
    static int fails;

    static void check(boolean ok, String m) {
        if (!ok) fails++;
        System.out.println((ok ? "PASS " : "FAIL ") + m);
    }

    static String last(List<String> l) { return l.isEmpty() ? null : l.get(l.size() - 1); }

    static final class FakeApp implements CamRelay.App {
        final List<String> got = new ArrayList<>();
        final String pkg;
        FakeApp(String p) { pkg = p; }
        public void cam(String line) { got.add(line); }
        public String pkg() { return pkg; }
        public String label() { return "TV Browser"; }
    }

    static final class FakePhone implements CamRelay.Phone {
        final List<String> got = new ArrayList<>();
        public void send(String line) { got.add(line); }
    }

    public static void main(String[] x) {
        String sdp = TypeProto.b64("v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\n");
        // ---- parsers
        check(CamProto.parseApp("o AbCd1234 va u") != null, "open parses");
        check(CamProto.parseApp("o AbCd1234 av e").media.equals("va"), "media normalised");
        check(CamProto.parseApp("o AbCd123 va u") == null, "short id refused");
        check(CamProto.parseApp("o AbCd12!4 va u") == null, "bad id refused");
        check(CamProto.parseApp("o AbCd1234 vv u") == null, "bad media refused");
        check(CamProto.parseApp("o AbCd1234 va x") == null, "bad facing refused");
        check(CamProto.parseApp("s AbCd1234 " + sdp) != null, "sdp parses");
        check(CamProto.parseApp("s AbCd1234 abc+def") == null, "non-base64url sdp refused");
        check(CamProto.parseApp("s AbCd1234 a\nV x AbCd1234") == null, "embedded line break refused");
        StringBuilder big = new StringBuilder();
        while (big.length() < CamProto.MAX_SDP + 5) big.append('A');
        check(CamProto.parseApp("s AbCd1234 " + big) == null, "huge sdp refused");
        check(CamProto.parsePhone("V y 1") != null, "ready parses");
        check(CamProto.parsePhone("V h AbCd1234 live") != null, "state parses");
        check(CamProto.parsePhone("V h AbCd1234 hack") == null, "unknown state refused");
        check(CamProto.parsePhone("V h AbCd1234 err " + TypeProto.b64("oops")).msg.length() > 0, "state message parses");
        String open = CamProto.open("AbCd1234", "va", "u", "com.example.tvbrowser", "TV Browser");
        CamProto.Msg po = CamProto.parseForPhone(open);
        check(po != null && po.app.equals("com.example.tvbrowser") && po.name.equals("TV Browser") && po.media.equals("va"), "open round-trips to the phone");
        check(CamProto.parseForApp("y 2").count == 2, "count parses for the app");
        check(CamProto.parseForApp("h AbCd1234 err " + TypeProto.b64("x")) != null, "error parses for the app");

        // ---- relay
        final CamRelay.App[] front = {null};
        CamRelay r = new CamRelay(a -> a == front[0]);
        FakeApp app = new FakeApp("com.example.tvbrowser"), other = new FakeApp("com.evil");
        FakePhone ph = new FakePhone(), ph2 = new FakePhone();
        r.appHello(app);
        check("y 0".equals(last(app.got)), "app told no phone is ready");
        front[0] = app;
        r.fromApp(app, "o AbCd1234 va u");
        check(last(app.got).startsWith("h AbCd1234 err"), "open with no phone fails with a reason");
        r.fromPhone(ph, "V y 1");
        check("y 1".equals(last(app.got)), "app told a phone is ready");
        r.fromApp(other, "o Zzzz9999 va u");
        check(ph.got.isEmpty() && last(other.got).startsWith("h Zzzz9999 err"), "an app not in front can't open the camera");
        check(r.fromApp(app, "o AbCd1234 va u"), "front app opens");
        CamProto.Msg sent = CamProto.parseForPhone(last(ph.got));
        check(sent != null && sent.kind == 'o' && sent.app.equals("com.example.tvbrowser"), "phone gets the open with the app's package");
        r.fromPhone(ph, "V s AbCd1234 " + sdp);
        check(("s AbCd1234 " + sdp).equals(last(app.got)), "phone offer reaches the app");
        r.fromApp(app, "s AbCd1234 " + sdp);
        check(("V s AbCd1234 " + sdp).equals(last(ph.got)), "app answer reaches the phone");
        int n = ph.got.size();
        check(!r.fromApp(other, "s AbCd1234 " + sdp) && ph.got.size() == n, "another app can't feed the session");
        check(!r.fromApp(other, "x AbCd1234") && r.sessions() == 1, "another app can't close the session");
        r.fromPhone(ph2, "V y 1");
        check(!r.fromPhone(ph2, "V s AbCd1234 " + sdp) && "V x AbCd1234".equals(last(ph2.got)), "another phone can't feed the session (told to stop)");
        r.fromPhone(ph, "V h AbCd1234 live");
        check("h AbCd1234 live".equals(last(app.got)), "state reaches the app");
        r.fromApp(app, "f AbCd1234 e");
        check("V f AbCd1234 e".equals(last(ph.got)), "camera switch reaches the phone");
        r.fromApp(app, "o Bbbb2222 v u");
        check(CamProto.parseForPhone(last(ph2.got)) != null && CamProto.parseForPhone(last(ph2.got)).kind == 'o', "new sessions go to the newest phone");
        r.phoneGone(ph);
        check(app.got.contains("x AbCd1234") && "y 1".equals(last(app.got)), "phone leaving ends its session and updates the count");
        r.appGone(app);
        check("V x Bbbb2222".equals(last(ph2.got)) && r.sessions() == 0, "app leaving ends its sessions on the phone");
        front[0] = app;
        for (int i = 0; i < 6; i++) r.fromApp(app, "o Cccc000" + i + " a u");
        check(r.sessions() == 4, "at most 4 sessions per app");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
