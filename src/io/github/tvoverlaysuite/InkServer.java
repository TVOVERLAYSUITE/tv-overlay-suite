package io.github.tvoverlaysuite;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP + WebSocket server. Serves the phone page and relays drawing messages. Plain Java (no Android APIs).
 *
 * Pairing (v1.4): when a {@link Gate} is set, a new socket must send {@code k <code>} as its first frame within
 * {@link #AUTH_TIMEOUT_MS}. Until then it is not a client: it gets no snapshot, no broadcasts, and its frames are
 * capped at {@link #PREAUTH_MAX_FRAME} bytes. Replies: {@code K ok}, {@code K bad} (then closed) or {@code K wait}
 * (too many wrong codes from that address; then closed).
 */
public final class InkServer {

    /** Supplies the current pairing code (upper case), or null to accept any phone. */
    public interface Gate {
        String code();
    }

    public interface Handler {
        void onOpen(Client c);
        void onText(Client c, String text);
        void onClose(Client c);
        /** Returns the file body for an HTTP path, or null for 404. */
        byte[] page(String path);
    }

    public static final class Client {
        /** Messages a phone may fall behind by before it is dropped. A dropped phone reconnects by itself and gets a
         *  fresh full picture (onOpen snapshot), so nothing is lost; dropping single messages instead would leave
         *  holes in its drawing. */
        static final int MAX_BACKLOG_MSGS = 1024;
        static final int MAX_BACKLOG_BYTES = 4 * 1024 * 1024;

        final Socket socket;
        private final OutputStream out;
        private final Executor pool;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private int queuedBytes;
        private boolean draining;
        volatile boolean open = true;

        Client(Socket s, Executor pool) throws IOException {
            socket = s;
            this.pool = pool;
            out = new BufferedOutputStream(s.getOutputStream(), 32768);
        }

        /** Queues a text message. Safe from any thread and never blocks: each phone has its own queue, written by a
         *  pool thread (Android kills the app if the main thread touches the network). A phone that stops reading
         *  (left the Wi-Fi, screen off) only stalls its own queue; once it is too far behind it is disconnected. */
        public void send(String text) {
            if (!open) return;
            final byte[] p = text.getBytes(StandardCharsets.UTF_8);
            boolean start = false, tooFar = false;
            synchronized (queue) {
                if (!queue.isEmpty()
                        && (queue.size() >= MAX_BACKLOG_MSGS || queuedBytes + (long) p.length > MAX_BACKLOG_BYTES)) {
                    tooFar = true;
                } else {
                    queue.add(p);
                    queuedBytes += p.length;
                    if (!draining) { draining = true; start = true; }
                }
            }
            if (tooFar) { close(); return; }
            if (start) {
                try {
                    pool.execute(this::drain);
                } catch (RuntimeException e) {   // pool shut down
                    synchronized (queue) { draining = false; }
                }
            }
        }

        /** Writes queued messages in order until the queue is empty. Runs on one pool thread at a time per phone. */
        private void drain() {
            while (true) {
                byte[] p;
                synchronized (queue) {
                    p = open ? queue.poll() : null;
                    if (p == null) {
                        draining = false;
                        if (!open) { queue.clear(); queuedBytes = 0; }
                        return;
                    }
                    queuedBytes -= p.length;
                }
                sendFrame(0x1, p);
            }
        }

        synchronized void sendFrame(int opcode, byte[] payload) {
            if (!open) return;
            try {
                writeFrame(out, opcode, payload);
                out.flush();
            } catch (IOException | RuntimeException e) {
                close();
            }
        }

        /** Not synchronized on purpose: closing the socket is what unblocks a write stuck on a dead phone. */
        public void close() {
            open = false;
            try { socket.close(); } catch (IOException ignored) { }
            synchronized (queue) { queue.clear(); queuedBytes = 0; }
        }
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME = 4 * 1024 * 1024;
    static final int PREAUTH_MAX_FRAME = 256;
    static final int AUTH_TIMEOUT_MS = 3000;
    /** Wrong codes allowed per address inside {@link #FAIL_WINDOW_MS} before that address is locked out. */
    static final int MAX_FAILS = 5;
    static final long FAIL_WINDOW_MS = 60_000, LOCKOUT_MS = 60_000;
    /** Wrong codes allowed from all addresses together per window (stops spreading guesses over many addresses). */
    static final int MAX_FAILS_TOTAL = 30;

    private final int port;
    private final Handler handler;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    /** Writers for the phones' queues: one busy thread per phone that has messages waiting, idle ones expire. */
    private final ExecutorService sender = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ink-send");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile ServerSocket serverSocket;
    private volatile Gate gate;
    private final Map<String, long[]> fails = new HashMap<>();   // address -> {count, windowStart, lockedUntil}
    private final long[] failsTotal = {0, 0};                    // {count, windowStart}

    public InkServer(int port, Handler handler) {
        this.port = port;
        this.handler = handler;
    }

    public void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "ink-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) { }
        for (Client c : clients) c.close();
        clients.clear();
    }

    public int clientCount() { return clients.size(); }

    public void setGate(Gate g) { gate = g; }

    /** Disconnects every phone (used after a new pairing code is made). */
    public void dropAll() {
        for (Client c : clients) c.close();
    }

    /** Constant-time compare of what the phone sent against the current code. */
    static boolean codeMatches(String expected, String got) {
        if (expected == null || got == null) return false;
        byte[] a = expected.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        byte[] b = got.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** True while this address (or everyone, after a flood of wrong codes) must wait. */
    private boolean lockedOut(String addr, long now) {
        synchronized (fails) {
            if (failsTotal[0] >= MAX_FAILS_TOTAL && now - failsTotal[1] < FAIL_WINDOW_MS) return true;
            long[] f = fails.get(addr);
            return f != null && now < f[2];
        }
    }

    private void recordFail(String addr, long now) {
        synchronized (fails) {
            if (now - failsTotal[1] >= FAIL_WINDOW_MS) { failsTotal[0] = 0; failsTotal[1] = now; }
            failsTotal[0]++;
            long[] f = fails.get(addr);
            if (f == null || now - f[1] >= FAIL_WINDOW_MS) {
                if (fails.size() > 256) fails.clear();
                f = new long[]{0, now, 0};
                fails.put(addr, f);
            }
            if (++f[0] >= MAX_FAILS) f[2] = now + LOCKOUT_MS;
        }
    }

    /** Reads the pairing frame. Returns true if the socket may become a client. */
    private boolean authenticate(Socket s, InputStream in, Client c) throws IOException {
        Gate g = gate;
        String code = g == null ? null : g.code();
        if (code == null) return true;
        String addr = s.getInetAddress() == null ? "?" : s.getInetAddress().getHostAddress();
        s.setSoTimeout(AUTH_TIMEOUT_MS);
        Frame f;
        try {
            f = readFrame(in, PREAUTH_MAX_FRAME);
        } catch (IOException e) {
            return false;   // timeout, oversized frame or garbage
        }
        long now = System.currentTimeMillis();
        if (lockedOut(addr, now)) { c.sendFrame(0x1, "K wait".getBytes(StandardCharsets.UTF_8)); return false; }
        String t = f != null && f.op == 0x1 && f.fin ? new String(f.payload, StandardCharsets.UTF_8) : "";
        if (t.startsWith("k ") && codeMatches(code, t.substring(2))) {
            synchronized (fails) { fails.remove(addr); }
            s.setSoTimeout(0);
            c.sendFrame(0x1, "K ok".getBytes(StandardCharsets.UTF_8));
            return true;
        }
        recordFail(addr, now);
        c.sendFrame(0x1, (lockedOut(addr, now) ? "K wait" : "K bad").getBytes(StandardCharsets.UTF_8));
        return false;
    }

    public void broadcast(String text, Client except) {
        for (Client c : clients) if (c != except) c.send(text);
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket ss = new ServerSocket()) {
                serverSocket = ss;
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(port));
                while (running) {
                    final Socket s = ss.accept();
                    s.setTcpNoDelay(true);
                    Thread t = new Thread(() -> serve(s), "ink-conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!running) break;
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            String path = req.length > 1 ? req[1] : "/";
            Map<String, String> h = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int k = lines[i].indexOf(':');
                if (k > 0) h.put(lines[i].substring(0, k).trim().toLowerCase(Locale.ROOT), lines[i].substring(k + 1).trim());
            }
            String key = h.get("sec-websocket-key");
            String upgrade = h.get("upgrade");
            if (key != null && upgrade != null && upgrade.equalsIgnoreCase("websocket")) {
                String accept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
                OutputStream o = s.getOutputStream();
                o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.flush();
                Client c = new Client(s, sender);
                if (!authenticate(s, in, c)) {   // the reply was already written and flushed
                    c.close();
                    return;
                }
                s.setSoTimeout(0);
                clients.add(c);
                try {
                    handler.onOpen(c);
                    frameLoop(in, c);
                } finally {
                    clients.remove(c);
                    c.close();
                    handler.onClose(c);
                }
                return;
            }
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            byte[] body = handler.page(path);
            OutputStream o = s.getOutputStream();
            if (body == null) {
                byte[] msg = "Not found".getBytes(StandardCharsets.UTF_8);
                o.write(("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: " + msg.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(msg);
            } else {
                o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + mime(path) + "\r\nContent-Length: " + body.length
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                o.write(body);
            }
            o.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    private static String mime(String path) {
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        return "text/html; charset=utf-8";
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 16384) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1").trim();
        }
        return null;
    }

    static final class Frame {
        final boolean fin; final int op; final byte[] payload;
        Frame(boolean fin, int op, byte[] payload) { this.fin = fin; this.op = op; this.payload = payload; }
    }

    /** Reads one frame; null at a clean end of stream. Throws if the frame is larger than {@code max}. */
    static Frame readFrame(InputStream in, int max) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len < 0 || len > max) throw new IOException("frame too large");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] p = new byte[(int) len];
        readFully(in, p);
        if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
        return new Frame(fin, op, p);
    }

    private void frameLoop(InputStream in, Client c) throws IOException {
        ByteArrayOutputStream frag = null;
        int fragOp = 0;
        while (c.open) {
            Frame f = readFrame(in, MAX_FRAME);
            if (f == null) return;
            byte[] p = f.payload;
            boolean fin = f.fin;
            int op = f.op;

            switch (op) {
                case 0x8: // close
                    c.sendFrame(0x8, new byte[0]);
                    return;
                case 0x9: // ping
                    c.sendFrame(0xA, p);
                    break;
                case 0xA: // pong
                    break;
                case 0x0: // continuation
                    if (frag == null) break;
                    frag.write(p);
                    if (frag.size() > MAX_FRAME) return;
                    if (fin) {
                        if (fragOp == 0x1) handler.onText(c, frag.toString("UTF-8"));
                        frag = null;
                    }
                    break;
                case 0x1:
                case 0x2:
                    if (fin) {
                        if (op == 0x1) handler.onText(c, new String(p, StandardCharsets.UTF_8));
                    } else {
                        frag = new ByteArrayOutputStream();
                        frag.write(p);
                        fragOp = op;
                    }
                    break;
                default:
                    return;
            }
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }

    static void writeFrame(OutputStream out, int opcode, byte[] p) throws IOException {
        out.write(0x80 | opcode);
        int n = p.length;
        if (n < 126) {
            out.write(n);
        } else if (n < 65536) {
            out.write(126);
            out.write((n >>> 8) & 0xFF);
            out.write(n & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) out.write((int) (((long) n >>> (8 * i)) & 0xFF));
        }
        out.write(p);
    }
}
