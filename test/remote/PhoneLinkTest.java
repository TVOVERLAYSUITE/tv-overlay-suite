package io.github.tvoverlaysuite.remote;

import io.github.tvoverlaysuite.InkServer;
import io.github.tvoverlaysuite.TypeProto;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** v1.5: the phone app's WebSocket client against the TV's real server: pairing, typing both ways, big messages, ping. */
public class PhoneLinkTest {
    static int fails;
    static void check(boolean ok, String m) { if (!ok) { fails++; System.out.println("FAIL " + m); } else System.out.println("PASS " + m); }

    public static void main(String[] a) throws Exception {
        final int port = 18768;
        LinkedBlockingQueue<String> tvGot = new LinkedBlockingQueue<>();
        InkServer srv = new InkServer(port, new InkServer.Handler() {
            public void onOpen(InkServer.Client c) { if (c.kb) c.send(TypeProto.id("ABCDEFGHJKMN", "Sala")); }
            public void onText(InkServer.Client c, String t) {
                tvGot.add(t);
                if (t.equals("T w")) c.send(TypeProto.open(false, 1, 0, 0, 0, 0, "Search", "Search YouTube", ""));
            }
            public void onClose(InkServer.Client c) { }
            public byte[] page(String p) { return null; }
        });
        srv.setGate(() -> "ABC234");
        srv.start();
        Thread.sleep(300);

        WsClient bad = WsClient.connect("127.0.0.1", port, "/kb", 2500);
        bad.send("k ZZZ999");
        check("K bad".equals(bad.readText()), "wrong code is refused with K bad");
        bad.close();

        WsClient c = WsClient.connect("127.0.0.1", port, "/kb", 2500);
        c.send("k ABC234");
        check("K ok".equals(c.readText()), "right code pairs (K ok)");
        TypeProto.Msg id = TypeProto.parse(c.readText());
        check(id != null && id.kind == 'i' && id.id.equals("ABCDEFGHJKMN") && id.name.equals("Sala"), "TV sends its id and name");
        check(srv.kbCount() == 1 && srv.clientCount() == 0, "counted as a keyboard link, not a drawing phone");

        c.send(TypeProto.want());
        TypeProto.Msg o = TypeProto.parse(c.readText());
        check(o != null && o.kind == 'o' && o.hint.equals("Search YouTube") && o.action.equals("Search"), "T w answered with the open box");

        tvGot.clear();                              // drop the "T w" line seen above
        long t0 = System.nanoTime();
        String e = TypeProto.edit(1, 1, 0, 0, 5, "hello");
        c.send(e);
        String got = tvGot.poll(3, TimeUnit.SECONDS);
        long us = (System.nanoTime() - t0) / 1000;
        check(e.equals(got), "an edit reaches the TV (" + us + " microseconds here)");

        StringBuilder big = new StringBuilder();
        while (big.length() < 70000) big.append("ñandú 😀 ");
        String bigEdit = TypeProto.edit(2, 1, 0, 0, 0, big.substring(0, 3000));
        c.send(bigEdit);
        check(bigEdit.equals(tvGot.poll(3, TimeUnit.SECONDS)), "a 3000-character paste arrives intact");
        String huge = "T s 1 2 0 0 " + TypeProto.b64(big.toString());
        srv.broadcastKb(huge);
        check(huge.equals(c.readText()), "a 70 000-character message from the TV arrives intact (64-bit length)");

        c.ping();
        srv.broadcastKb("T c 1");
        check("T c 1".equals(c.readText()), "ping answered by the TV and skipped by the reader");

        srv.broadcast("s 1 2 3 4", null);           // drawing traffic must never reach the keyboard link
        srv.broadcastKb("T n none");
        check("T n none".equals(c.readText()), "no drawing traffic on the keyboard link");

        srv.stop();
        boolean ended = false;
        try { c.setReadTimeout(2000); c.readText(); } catch (java.io.IOException x) { ended = true; }
        check(ended, "reader ends when the TV goes away (phone then reconnects)");
        System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
