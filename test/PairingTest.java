import io.github.tvoverlaysuite.InkServer;
import io.github.tvoverlaysuite.Pairing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Pairing gate of InkServer (v1.4): code check, 3 s limit, pre-auth frame cap, lockout, no data before auth.
 *  Usage: java -cp build/test PairingTest */
public class PairingTest {
    static int fails = 0;
    static final int PORT = 18765;
    static final AtomicInteger opens = new AtomicInteger();

    static void check(boolean c, String what) {
        System.out.println((c ? "PASS " : "FAIL ") + what);
        if (!c) fails++;
    }

    public static void main(String[] a) throws Exception {
        final String[] code = {"TEST23"};
        final InkServer[] srv = new InkServer[1];
        srv[0] = new InkServer(PORT, new InkServer.Handler() {
            public void onOpen(InkServer.Client c) { opens.incrementAndGet(); c.send("SNAPSHOT"); }
            public void onText(InkServer.Client c, String t) { srv[0].broadcast("echo " + t, null); }
            public void onClose(InkServer.Client c) { }
            public byte[] page(String p) { return "page".getBytes(); }
        });
        srv[0].setGate(() -> code[0]);
        srv[0].start();
        Thread.sleep(300);

        // codes
        for (int i = 0; i < 200; i++) if (!Pairing.valid(Pairing.newCode())) { check(false, "newCode valid"); break; }
        check(!Pairing.valid("TEST2O") && !Pairing.valid("abc") && Pairing.valid("TEST23"), "Pairing.valid rejects look-alikes / wrong length");
        check(Pairing.pretty("ABC234").equals("ABC 234"), "pretty() groups 3+3");

        // 1. right code
        Ws ok = new Ws();
        ok.text("k TEST23");
        check("K ok".equals(ok.read(2000)), "right code -> K ok");
        check("SNAPSHOT".equals(ok.read(2000)), "snapshot only after K ok");
        check(srv[0].clientCount() == 1, "authenticated socket counts as a phone");

        // 2. lower case + spaces accepted
        Ws lc = new Ws();
        lc.text("k  test23 ");
        check("K ok".equals(lc.read(2000)), "code is case-insensitive, spaces ignored");
        lc.close();

        // 3. a socket that has not paired yet gets no broadcasts and no snapshot
        Ws quiet = new Ws();
        ok.text("hello");
        check("echo hello".equals(ok.read(2000)), "paired phone gets broadcast");
        check(quiet.read(800) == null, "unpaired socket receives nothing (no snapshot, no broadcast)");

        // 4. silence -> closed after ~3 s, never counted
        long t0 = System.currentTimeMillis();
        String r = quiet.read(5000);
        long took = System.currentTimeMillis() - t0;
        check(r == null && quiet.closed && took < 4000, "no code within 3 s -> closed (" + (took + 800) + " ms)");

        // 5. big frame before pairing -> closed, no reply
        Ws big = new Ws();
        byte[] blob = new byte[10000];
        big.frame(0x1, blob);
        check(big.read(2000) == null && big.closed, "10 KB frame before pairing -> closed without reply");

        // 6. wrong code
        int before = opens.get();
        Ws bad = new Ws();
        bad.text("k WRONG2");
        check("K bad".equals(bad.read(2000)), "wrong code -> K bad");
        check(bad.read(1000) == null && bad.closed, "wrong code -> closed");
        check(opens.get() == before, "wrong code never reaches onOpen");

        // 7. other first message
        Ws other = new Ws();
        other.text("s a1 ff0000 8 1 1");
        check("K bad".equals(other.read(2000)), "drawing before pairing -> K bad");

        // 8. lockout after 5 wrong codes from one address (2 used above)
        for (int i = 0; i < 3; i++) { Ws w = new Ws(); w.text("k NOPE23"); w.read(2000); w.close(); }
        Ws locked = new Ws();
        locked.text("k TEST23");
        check("K wait".equals(locked.read(2000)), "after 5 wrong codes even the right code gets K wait");

        // 9. new code drops paired phones
        code[0] = "NEWC23";
        srv[0].dropAll();
        check(ok.read(2000) == null && ok.closed, "dropAll() disconnects paired phones");

        // 10. plain HTTP still served (the page carries no secrets)
        try (Socket s = new Socket("127.0.0.1", PORT)) {
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            String resp = new String(readAll(s.getInputStream()), StandardCharsets.UTF_8);
            check(resp.startsWith("HTTP/1.1 200") && resp.endsWith("page"), "HTTP page served without a code");
        }

        srv[0].stop();
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        return b.toByteArray();
    }

    /** Minimal masked WebSocket client. */
    static final class Ws {
        final Socket s;
        final InputStream in;
        final OutputStream out;
        boolean closed;

        Ws() throws IOException {
            s = new Socket("127.0.0.1", PORT);
            in = s.getInputStream();
            out = s.getOutputStream();
            out.write(("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes());
            int state = 0;
            while (state < 4) {
                int c = in.read();
                if (c < 0) throw new IOException("no handshake");
                state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            }
        }

        void text(String t) throws IOException { frame(0x1, t.getBytes(StandardCharsets.UTF_8)); }

        void frame(int op, byte[] p) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(0x80 | op);
            if (p.length < 126) b.write(0x80 | p.length);
            else { b.write(0x80 | 126); b.write(p.length >> 8); b.write(p.length & 0xFF); }
            byte[] m = {1, 2, 3, 4};
            b.write(m);
            for (int i = 0; i < p.length; i++) b.write(p[i] ^ m[i & 3]);
            try { out.write(b.toByteArray()); out.flush(); } catch (IOException e) { closed = true; }
        }

        /** Next text frame, or null on timeout / close (closed is set on close). */
        String read(int ms) throws IOException {
            if (closed) return null;
            s.setSoTimeout(ms);
            try {
                int b0 = in.read();
                if (b0 < 0) { closed = true; return null; }
                int len = in.read() & 0x7F;
                if (len == 126) len = (in.read() << 8) | in.read();
                byte[] p = new byte[len];
                int off = 0;
                while (off < len) { int r = in.read(p, off, len - off); if (r < 0) { closed = true; return null; } off += r; }
                if ((b0 & 0x0F) == 0x8) { closed = true; return null; }
                return new String(p, StandardCharsets.UTF_8);
            } catch (SocketTimeoutException e) {
                return null;
            } catch (IOException e) {
                closed = true;
                return null;
            }
        }

        void close() { try { s.close(); } catch (IOException ignored) { } }
    }
}
