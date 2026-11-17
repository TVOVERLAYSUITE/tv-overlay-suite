import io.github.tvoverlaysuite.InkServer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** v1.4.1: a phone that stops reading must not stall the others, and must be dropped once far behind. */
public class SlowPhoneTest {
    static final int PORT = 18766, MSGS = 400, SIZE = 60000;

    public static void main(String[] a) throws Exception {
        AtomicInteger closed = new AtomicInteger();
        InkServer srv = new InkServer(PORT, new InkServer.Handler() {
            public void onOpen(InkServer.Client c) { }
            public void onText(InkServer.Client c, String t) { }
            public void onClose(InkServer.Client c) { closed.incrementAndGet(); }
            public byte[] page(String p) { return null; }
        });
        srv.start();
        Thread.sleep(300);
        Socket stalled = open(true);     // connects, then never reads
        Socket healthy = open(false);
        long t0 = System.currentTimeMillis();
        while (srv.clientCount() < 2 && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20);
        if (srv.clientCount() != 2) fail("both phones should be connected, got " + srv.clientCount());

        StringBuilder sb = new StringBuilder(SIZE);
        while (sb.length() < SIZE) sb.append('x');
        String body = sb.toString();
        InputStream hin = healthy.getInputStream();
        healthy.setSoTimeout(10000);
        Thread reader = new Thread(() -> {
            try {
                for (int i = 0; i < MSGS; i++) {
                    String m = readText(hin);
                    if (!m.startsWith(i + " ")) { System.out.println("FAIL out of order at " + i); System.exit(1); }
                }
                System.out.println("PASS healthy phone got all " + MSGS + " messages in order");
            } catch (IOException e) { System.out.println("FAIL healthy phone stalled: " + e); System.exit(1); }
        });
        reader.start();
        for (int i = 0; i < MSGS; i++) { srv.broadcast(i + " " + body, null); Thread.sleep(2); }   // a fast drawing pace
        reader.join(15000);
        if (reader.isAlive()) fail("healthy phone did not get everything within 15 s (stalled by the other phone?)");
        t0 = System.currentTimeMillis();
        while (closed.get() < 1 && System.currentTimeMillis() - t0 < 5000) Thread.sleep(20);
        if (closed.get() != 1 || srv.clientCount() != 1) fail("stalled phone should be dropped (closed=" + closed.get() + ", clients=" + srv.clientCount() + ")");
        System.out.println("PASS stalled phone was dropped, healthy one kept");
        srv.broadcast("after", null);
        if (!readText(hin).equals("after")) fail("healthy phone stopped receiving after the drop");
        System.out.println("PASS healthy phone still receiving");
        stalled.close(); healthy.close(); srv.stop();
        System.out.println("ALL PASS");
        System.exit(0);
    }

    static void fail(String m) { System.out.println("FAIL " + m); System.exit(1); }

    static Socket open(boolean tinyBuffer) throws IOException {
        Socket s = new Socket();
        if (tinyBuffer) s.setReceiveBufferSize(4096);
        s.connect(new InetSocketAddress("127.0.0.1", PORT));
        s.getOutputStream().write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        int st = 0;
        while (st < 4) { int c = in.read(); if (c < 0) throw new EOFException(); st = (c == (st % 2 == 0 ? '\r' : '\n')) ? st + 1 : (c == '\r' ? 1 : 0); }
        return s;
    }

    static String readText(InputStream in) throws IOException {
        int b0 = in.read(), b1 = in.read();
        if (b0 < 0 || b1 < 0) throw new EOFException();
        long len = b1 & 0x7F;
        if (len == 126) len = (in.read() << 8) | in.read();
        else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | in.read(); }
        byte[] p = new byte[(int) len];
        int off = 0;
        while (off < p.length) { int r = in.read(p, off, p.length - off); if (r < 0) throw new EOFException(); off += r; }
        return new String(p, StandardCharsets.UTF_8);
    }
}
