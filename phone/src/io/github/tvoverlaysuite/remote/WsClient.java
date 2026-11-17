package io.github.tvoverlaysuite.remote;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Minimal WebSocket client for the TV's server (text frames only). Plain Java. One thread reads
 * ({@link #readText}); writes are synchronized and must not run on Android's main thread.
 */
final class WsClient {
    private static final SecureRandom RNG = new SecureRandom();
    private static final int MAX_FRAME = 1 << 20;

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean open = true;

    private WsClient(Socket s, InputStream in) throws IOException {
        sock = s;
        this.in = in;
        out = s.getOutputStream();
    }

    /** Connects and upgrades. Throws on any failure (the socket is closed). */
    static WsClient connect(String host, int port, String path, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            s.setTcpNoDelay(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            byte[] nonce = new byte[16];
            RNG.nextBytes(nonce);
            String key = Base64.getEncoder().encodeToString(nonce);
            String req = "GET " + path + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(req.getBytes(StandardCharsets.US_ASCII));
            o.flush();
            InputStream in = new BufferedInputStream(s.getInputStream(), 8192);
            String head = readHead(in);
            if (head == null || !head.startsWith("HTTP/1.1 101")) throw new IOException("not a TV Overlay Suite server");
            return new WsClient(s, in);
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    void setReadTimeout(int ms) {
        try { sock.setSoTimeout(ms); } catch (IOException ignored) { }
    }

    boolean isOpen() { return open; }

    /** Sends a text frame (client frames are masked, as the protocol requires). Returns false once closed. */
    synchronized boolean send(String text) {
        if (!open) return false;
        try {
            writeFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    private void writeFrame(int op, byte[] p) throws IOException {
        byte[] mask = new byte[4];
        RNG.nextBytes(mask);
        int n = p.length;
        ByteArrayOutputStream b = new ByteArrayOutputStream(n + 14);
        b.write(0x80 | op);
        if (n < 126) {
            b.write(0x80 | n);
        } else if (n < 65536) {
            b.write(0x80 | 126);
            b.write(n >>> 8);
            b.write(n & 0xFF);
        } else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) n >>> (8 * i)) & 0xFF));
        }
        b.write(mask, 0, 4);
        for (int i = 0; i < n; i++) b.write(p[i] ^ mask[i & 3]);
        out.write(b.toByteArray());
        out.flush();
    }

    /** Next text message; answers pings itself. Throws at the end of the connection. */
    String readText() throws IOException {
        ByteArrayOutputStream frag = null;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) throw new EOFException();
            int b1 = byteOf(in);
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            long len = b1 & 0x7F;
            if (len == 126) len = (byteOf(in) << 8) | byteOf(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | byteOf(in); }
            if (len < 0 || len > MAX_FRAME) throw new IOException("frame too large");
            byte[] mask = null;
            if ((b1 & 0x80) != 0) { mask = new byte[4]; readFully(in, mask); }
            byte[] p = new byte[(int) len];
            readFully(in, p);
            if (mask != null) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
            switch (op) {
                case 0x8: close(); throw new EOFException("closed by TV");
                case 0x9: synchronized (this) { if (open) writeFrame(0xA, p); } break;
                case 0xA: break;
                case 0x0:
                    if (frag == null) break;
                    frag.write(p, 0, p.length);
                    if (frag.size() > MAX_FRAME) throw new IOException("message too large");
                    if (fin) return frag.toString("UTF-8");
                    break;
                case 0x1:
                    if (fin) return new String(p, StandardCharsets.UTF_8);
                    frag = new ByteArrayOutputStream();
                    frag.write(p, 0, p.length);
                    break;
                default:   // binary: not used by the TV
            }
        }
    }

    /** Sends a ping (keeps Wi-Fi power-save from parking an idle link for long). */
    synchronized void ping() {
        if (!open) return;
        try { writeFrame(0x9, new byte[0]); } catch (IOException e) { close(); }
    }

    void close() {
        open = false;
        try { sock.close(); } catch (IOException ignored) { }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int st = 0;
        while (b.size() < 8192) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            if ((st == 0 || st == 2) && c == '\r') st++;
            else if ((st == 1 || st == 3) && c == '\n') st++;
            else st = c == '\r' ? 1 : 0;
            if (st == 4) return b.toString("ISO-8859-1");
        }
        return null;
    }

    private static int byteOf(InputStream in) throws IOException {
        int v = in.read();
        if (v < 0) throw new EOFException();
        return v;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }
}
