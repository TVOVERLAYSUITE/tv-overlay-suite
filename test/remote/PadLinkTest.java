package io.github.tvoverlaysuite.remote;

import io.github.tvoverlaysuite.InkServer;
import io.github.tvoverlaysuite.PadProto;
import io.github.tvoverlaysuite.PadRelay;
import io.github.tvoverlaysuite.TypeProto;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * v1.6: the phone app's trackpad lines over the real keyboard link, through the relay, to a trackpad app; front
 * changes back to the phone; and the time from the phone's send to the app having it (on this machine, no Wi-Fi).
 */
public class PadLinkTest {
    static int fails;
    static void check(boolean ok, String m) { if (!ok) fails++; System.out.println((ok ? "PASS " : "FAIL ") + m); }

    public static void main(String[] a) throws Exception {
        final int port = 18769;
        final InkServer[] srv = new InkServer[1];
        PadRelay relay = new PadRelay(line -> srv[0].broadcastKb(line));
        LinkedBlockingQueue<String> app = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<Long> appAt = new LinkedBlockingQueue<>();
        PadRelay.App fake = line -> { appAt.add(System.nanoTime()); app.add(line); };
        srv[0] = new InkServer(port, new InkServer.Handler() {
            public void onOpen(InkServer.Client c) { if (c.kb) { c.send(TypeProto.id("ABCDEFGHJKMN", "Sala")); c.send(relay.frontLine()); } }
            public void onText(InkServer.Client c, String t) {
                // same routing as MarkerService.kbText
                for (String line : t.split("\n")) if (line.startsWith("P ")) relay.fromPhone(line.trim());
            }
            public void onClose(InkServer.Client c) { }
            public byte[] page(String p) { return null; }
        });
        srv[0].setGate(() -> "ABC234");
        srv[0].start();
        Thread.sleep(300);

        WsClient c = WsClient.connect("127.0.0.1", port, "/kb", 2500);
        c.send("k ABC234");
        check("K ok".equals(c.readText()), "phone pairs");
        c.readText();   // T i
        check("P f 0".equals(c.readText()), "phone told nothing is in front yet");

        relay.fromApp(fake, "f 1 " + TypeProto.b64("TV Browser"));
        PadProto.Front f = PadProto.parseFront(c.readText());
        check(f != null && f.on && f.name.equals("TV Browser"), "phone told the browser came to the front");

        c.send("P m 12.5 -3\nP c\nP s 0 40");
        check("m 12.5 -3".equals(app.poll(2, TimeUnit.SECONDS)), "move arrives");
        check("c".equals(app.poll(2, TimeUnit.SECONDS)), "click arrives, in order");
        check("s 0 40".equals(app.poll(2, TimeUnit.SECONDS)), "scroll arrives");
        c.send("T w");
        check(app.poll(300, TimeUnit.MILLISECONDS) == null, "typing lines never reach the trackpad app");

        // latency: 300 moves, one per frame of a 120 Hz phone
        appAt.clear();
        long worst = 0, sum = 0;
        for (int i = 0; i < 300; i++) {
            long t0 = System.nanoTime();
            c.send(PadProto.move(1, 1));
            Long t1 = appAt.poll(2, TimeUnit.SECONDS);
            app.poll();
            if (t1 == null) { check(false, "move " + i + " lost"); break; }
            long us = (t1 - t0) / 1000;
            worst = Math.max(worst, us);
            sum += us;
            Thread.sleep(8);
        }
        System.out.println("INFO phone send -> app: average " + (sum / 300) + " us, worst " + worst + " us (loopback)");
        check(sum / 300 < 3000, "relay adds under 3 ms on average");

        relay.closed(fake);
        f = PadProto.parseFront(c.readText());
        check(f != null && !f.on, "phone told the app left");
        c.close();
        srv[0].stop();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
