import io.github.tvoverlaysuite.InkServer;
import io.github.tvoverlaysuite.TypeProto;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

/** v1.5 typing from the phone: protocol, edit playback on a fake text box, and keyboard connections on the server. */
public class TypeTest {
    static int fails;

    public static void main(String[] args) throws Exception {
        protocol();
        fuzzPlayback();
        typingSession();
        server();
        System.out.println(fails == 0 ? "ALL PASS" : "FAILURES: " + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void check(boolean ok, String what) {
        if (!ok) { fails++; System.out.println("FAIL " + what); }
    }

    // ---------------------------------------------------------------- protocol

    static void protocol() {
        String t = "hola mundo\nsegunda línea ñ 😀 \"q\" ";
        TypeProto.Msg m = TypeProto.parse(TypeProto.edit(7, 3, 2, 1, 9, t));
        check(m != null && m.kind == 'e' && m.seq == 7 && m.field == 3 && m.start == 2 && m.del == 1 && m.cursor == 9
                && m.text.equals(t), "edit round trip");
        m = TypeProto.parse(TypeProto.edit(1, 1, 0, 0, 0, ""));
        check(m != null && m.text.isEmpty(), "empty edit text");
        m = TypeProto.parse(TypeProto.open(true, 4, 2, 1, 3, TypeProto.F_URI, "Search", "Search YouTube", t));
        check(m != null && m.kind == 'o' && m.auto && m.field == 4 && m.seq == 2 && m.selS == 1 && m.selE == 3
                && m.flags == TypeProto.F_URI && m.action.equals("Search") && m.hint.equals("Search YouTube")
                && m.text.equals(t), "open round trip");
        m = TypeProto.parse(TypeProto.open(false, 4, 0, 0, 0, 0, "", "", ""));
        check(m != null && !m.auto && m.hint.isEmpty() && m.text.isEmpty() && m.action.isEmpty(), "open with empties");
        m = TypeProto.parse(TypeProto.state(5, 9, 0, 0, "abc"));
        check(m != null && m.kind == 's' && m.field == 5 && m.seq == 9 && m.text.equals("abc"), "state round trip");
        m = TypeProto.parse(TypeProto.id("ABCDEFGH2345", "Sala TCL"));
        check(m != null && m.kind == 'i' && m.id.equals("ABCDEFGH2345") && m.name.equals("Sala TCL"), "id round trip");
        check(TypeProto.parse(TypeProto.closed(5)).field == 5, "closed");
        check(TypeProto.parse(TypeProto.none("ime")).why.equals("ime"), "none");
        check(TypeProto.parse(TypeProto.want()).kind == 'w', "want");
        check(TypeProto.parse(TypeProto.action(6)).field == 6, "action");
        // garbage never throws and never parses
        String[] bad = {null, "", "T", "T ", "T e", "T e 1 2 3", "T e x y z w v q", "T e 1 1 -1 0 0 -", "T z 1",
                "T o a 1 2 3 4 5 !!! - -", "T e 1 1 0 0 0 %%%", "x e 1", "T ee 1 1 1 1 1 -"};
        for (String b : bad) check(TypeProto.parse(b) == null, "rejects " + b);
    }

    // ---------------------------------------------------------------- playback on a fake box

    /** Behaves like an Android EditText's InputConnection for the three calls the keyboard makes. */
    static final class FakeBox implements TypeProto.Ops {
        StringBuilder text = new StringBuilder();
        int selS, selE;
        int selectCalls;

        FakeBox(String t, int s, int e) { text.append(t); selS = s; selE = e; }

        public void deleteBefore(int n) {
            int a = Math.max(0, selS - n);
            text.delete(a, selS);
            selE -= selS - a;
            selS = a;
            if (selE < selS) selE = selS;
        }

        public void select(int a, int b) {
            selectCalls++;
            selS = Math.max(0, Math.min(a, text.length()));
            selE = Math.max(selS, Math.min(b, text.length()));
        }

        public void commit(String t) {
            text.replace(selS, selE, t);
            selS = selE = selS + t.length();
        }
    }

    static final String ALPHA = "abcdeñáé 😀\n.ABZ";

    static String rnd(Random r, int max) {
        StringBuilder b = new StringBuilder();
        int n = r.nextInt(max + 1);
        while (b.length() < n) {
            int i = r.nextInt(ALPHA.length());
            char c = ALPHA.charAt(i);
            if (Character.isHighSurrogate(c)) b.append(c).append(ALPHA.charAt(i + 1));
            else if (Character.isLowSurrogate(c)) b.append(ALPHA.charAt(i - 1)).append(c);
            else b.append(c);
        }
        return b.toString();
    }

    static void fuzzPlayback() {
        Random r = new Random(42);
        int ok = 0, fast = 0;
        for (int i = 0; i < 20000; i++) {
            String before = rnd(r, 30), after;
            switch (r.nextInt(4)) {   // edits that look like real typing, plus random rewrites
                case 0: { int p = r.nextInt(before.length() + 1); after = before.substring(0, p) + rnd(r, 3) + before.substring(p); break; }
                case 1: { int p = r.nextInt(before.length() + 1), q = p + r.nextInt(before.length() - p + 1); after = before.substring(0, p) + before.substring(q); break; }
                case 2: after = rnd(r, 30); break;
                default: after = before;
            }
            String prefix = r.nextBoolean() ? "" : rnd(r, 8);          // part of the box outside the phone's window
            String suffix = r.nextBoolean() ? "" : rnd(r, 8);
            int[] d = TypeProto.diff(before, after);
            String ins = after.substring(d[0], d[2]);
            check(TypeProto.apply(before, d[0], d[1], ins).equals(after), "diff+apply #" + i);
            check(!splitsPair(after, d[0]) && !splitsPair(after, d[2]), "diff keeps surrogate pairs #" + i);
            int cursor = r.nextInt(after.length() + 1);
            if (cursor > 0 && cursor < after.length() && Character.isLowSurrogate(after.charAt(cursor))) cursor--;
            // the TV cursor is where the phone last left it (collapsed), or a selection made on the TV
            int s = r.nextInt(before.length() + 1), e = r.nextBoolean() ? s : s + r.nextInt(before.length() - s + 1);
            FakeBox box = new FakeBox(prefix + before + suffix, prefix.length() + s, prefix.length() + e);
            int got = TypeProto.play(box, before.length(), s, e, prefix.length(), d[0], d[1], ins, cursor);
            boolean good = box.text.toString().equals(prefix + after + suffix) && got == cursor
                    && box.selS == prefix.length() + cursor && box.selE == box.selS;
            check(good, "playback #" + i + " before='" + before + "' after='" + after + "' sel=" + s + "-" + e);
            if (good) ok++;
            if (box.selectCalls == 0) fast++;
        }
        System.out.println((fails == 0 ? "PASS" : "FAIL") + " fuzz: " + ok + "/20000 edits played back exactly ("
                + fast + " without any selection call)");
    }

    static boolean splitsPair(String s, int at) {
        return at > 0 && at < s.length() && Character.isHighSurrogate(s.charAt(at - 1)) && Character.isLowSurrogate(s.charAt(at));
    }

    /** A phone typing session: keystrokes, Gboard swipe words, autocorrect and backspace, each sent as it happens. */
    static void typingSession() {
        FakeBox tv = new FakeBox("", 0, 0);
        String mirror = "";
        int mirS = 0;
        StringBuilder phone = new StringBuilder();
        int cur = 0, seq = 0, selectCalls = 0;
        String[] steps = {"+h", "+o", "+l", "+a", "+ ", "W mundo ", "<", "<", "<", "+ ", "+q", "+u", "+e",
                "R que→qué", "+ ", "+😀", "<", "<", "M3", "+X", "M99", "+!"};
        for (String st : steps) {
            String before = phone.toString();
            switch (st.charAt(0)) {
                case '+': phone.insert(cur, st.substring(1)); cur += st.length() - 1; break;
                case 'W': phone.insert(cur, st.substring(2)); cur += st.length() - 2; break;   // swipe word
                case '<': {                                                                    // backspace
                    if (cur == 0) break;
                    int n = cur > 1 && Character.isLowSurrogate(phone.charAt(cur - 1)) ? 2 : 1;
                    phone.delete(cur - n, cur); cur -= n; break;
                }
                case 'R': {                                                                    // autocorrect
                    String[] a = st.substring(2).split("→");
                    int p = phone.lastIndexOf(a[0]);
                    phone.replace(p, p + a[0].length(), a[1]);
                    break;
                }
                case 'M': cur = Math.min(Integer.parseInt(st.substring(1)), phone.length()); break;   // tap to move
            }
            String after = phone.toString();
            int[] d = TypeProto.diff(before, after);
            String line = TypeProto.edit(++seq, 1, d[0], d[1], cur, after.substring(d[0], d[2]));   // over the wire
            TypeProto.Msg m = TypeProto.parse(line);
            int before0 = tv.selectCalls;
            mirS = TypeProto.play(tv, mirror.length(), mirS, mirS, 0, m.start, m.del, m.text, m.cursor);
            mirror = TypeProto.apply(mirror, m.start, m.del, m.text);
            selectCalls += tv.selectCalls - before0;
        }
        check(tv.text.toString().equals(phone.toString()) && mirror.equals(phone.toString()) && tv.selS == cur,
                "typing session: TV box '" + tv.text + "' vs phone '" + phone + "'");
        System.out.println("PASS typing session: TV shows '" + tv.text.toString().replace("\n", "\\n") + "' ("
                + steps.length + " edits, " + selectCalls + " needed a selection call)");
    }

    // ---------------------------------------------------------------- server: keyboard connections

    static void server() throws Exception {
        final int port = 18767;
        final StringBuilder kbGot = new StringBuilder();
        InkServer srv = new InkServer(port, new InkServer.Handler() {
            public void onOpen(InkServer.Client c) { if (c.kb) c.send("T i ID0123456789 -"); }
            public void onText(InkServer.Client c, String t) { if (c.kb) synchronized (kbGot) { kbGot.append(t); } }
            public void onClose(InkServer.Client c) { }
            public byte[] page(String p) { return null; }
        });
        srv.setGate(() -> "ABC234");
        srv.start();
        Thread.sleep(300);
        Socket draw = open(port, "/ws", "ABC234");
        Socket kb = open(port, "/kb", "ABC234");
        Socket bad = null;
        try { bad = open(port, "/kb", "ZZZ999"); } catch (IOException expected) { }
        long t0 = System.currentTimeMillis();
        while (srv.clientCount() + srv.kbCount() < 2 && System.currentTimeMillis() - t0 < 3000) Thread.sleep(20);
        check(srv.clientCount() == 1 && srv.kbCount() == 1, "counts: drawing=" + srv.clientCount() + " kb=" + srv.kbCount()
                + " (a keyboard link must not count as a drawing phone, or the TV would never sleep)");
        check(bad == null, "keyboard link with a wrong pairing code is refused");
        kb.setSoTimeout(3000);
        draw.setSoTimeout(3000);
        InputStream kin = kb.getInputStream(), din = draw.getInputStream();
        check(SlowRead.text(kin).startsWith("T i "), "keyboard link gets the TV id first");
        srv.broadcast("s 1 2 3", null);          // drawing traffic
        srv.broadcastKb("T c 1");                // typing traffic
        check(SlowRead.text(din).equals("s 1 2 3"), "drawing phone gets drawing");
        check(SlowRead.text(kin).equals("T c 1"), "keyboard link gets typing and nothing else");
        String e = TypeProto.edit(1, 1, 0, 0, 1, "h");
        writeMasked(kb.getOutputStream(), e);
        t0 = System.currentTimeMillis();
        while (kbGot.length() == 0 && System.currentTimeMillis() - t0 < 3000) Thread.sleep(10);
        check(kbGot.toString().equals(e), "typing from the phone reaches the TV");
        draw.close(); kb.close(); srv.stop();
        System.out.println(fails == 0 ? "PASS keyboard connections" : "FAIL keyboard connections");
    }

    static Socket open(int port, String path, String code) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", port));
        s.setSoTimeout(3000);
        s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        int st = 0;
        while (st < 4) { int c = in.read(); if (c < 0) throw new EOFException(); st = (c == (st % 2 == 0 ? '\r' : '\n')) ? st + 1 : (c == '\r' ? 1 : 0); }
        writeMasked(s.getOutputStream(), "k " + code);
        String reply = SlowRead.text(in);
        if (!reply.equals("K ok")) { s.close(); throw new IOException("pairing refused: " + reply); }
        return s;
    }

    static void writeMasked(OutputStream o, String text) throws IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        byte[] mask = {1, 2, 3, 4};
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(0x81);
        if (p.length < 126) b.write(0x80 | p.length);
        else { b.write(0x80 | 126); b.write(p.length >> 8); b.write(p.length & 0xFF); }
        b.write(mask);
        for (int i = 0; i < p.length; i++) b.write(p[i] ^ mask[i & 3]);
        o.write(b.toByteArray());
        o.flush();
    }

    static final class SlowRead {
        static String text(InputStream in) throws IOException {
            int b0 = in.read(), b1 = in.read();
            if (b0 < 0 || b1 < 0) throw new EOFException();
            long len = b1 & 0x7F;
            if (len == 126) len = (in.read() << 8) | in.read();
            byte[] p = new byte[(int) len];
            int off = 0;
            while (off < p.length) { int r = in.read(p, off, p.length - off); if (r < 0) throw new EOFException(); off += r; }
            return new String(p, StandardCharsets.UTF_8);
        }
    }
}
