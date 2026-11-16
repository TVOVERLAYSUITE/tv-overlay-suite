package io.github.tvoverlaysuite;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * Minimal ADB client for the TV's own adb daemon (classic TCP transport, usually port 5555), used once to switch
 * the accessibility service on. Plain Java (JVM-testable), no dependencies.
 *
 * Protocol: 24-byte little-endian header {command, arg0, arg1, length, checksum, magic = ~command} + payload.
 *   -> CNXN version maxdata "host::"
 *   <- AUTH TOKEN(1) 20-byte token
 *   -> AUTH SIGNATURE(2) RSA-PKCS#1 signature of the token (treated as a SHA-1 digest)
 *   <- CNXN (key already trusted)  or  AUTH TOKEN again
 *   -> AUTH RSAPUBLICKEY(3) base64(android_pubkey) + " name\0"   -> the TV shows "Allow USB debugging?"
 *   <- CNXN once the user allows it
 *   -> OPEN local "shell:cmd\0"  <- OKAY remote local  <- WRTE ... (-> OKAY each)  <- CLSE (-> CLSE)
 * Wireless debugging with pairing codes (TLS, Android 11+ "Wireless debugging") is not supported.
 */
public final class Adb implements AutoCloseable {

    static final int A_CNXN = 0x4e584e43, A_AUTH = 0x48545541, A_OPEN = 0x4e45504f,
            A_OKAY = 0x59414b4f, A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_STLS = 0x534c5453;
    static final int VERSION = 0x01000000, MAX_DATA = 256 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    /** Thrown when the TV is waiting for the user to answer "Allow USB debugging?" and the wait ran out. */
    public static final class NotAllowedException extends IOException {
        NotAllowedException() { super("USB debugging was not allowed on the TV"); }
    }

    static final class Msg {
        final int cmd, arg0, arg1; final byte[] data;
        Msg(int cmd, int arg0, int arg1, byte[] data) { this.cmd = cmd; this.arg0 = arg0; this.arg1 = arg1; this.data = data; }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /**
     * Connects and authenticates. {@code allowWaitMs} is how long to wait for the user to accept the key on the TV.
     * The listener (may be null) is told when the TV is showing its prompt.
     */
    public static Adb connect(String host, int port, KeyPair key, String keyName, int allowWaitMs, Runnable onPrompt)
            throws IOException, GeneralSecurityException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 3000);
            s.setSoTimeout(5000);
            Adb a = new Adb(s);
            a.handshake(key, keyName, allowWaitMs, onPrompt);
            return a;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    private void handshake(KeyPair key, String keyName, int allowWaitMs, Runnable onPrompt)
            throws IOException, GeneralSecurityException {
        write(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
        boolean sentSignature = false, sentKey = false;
        while (true) {
            Msg m = read();
            if (m.cmd == A_CNXN) return;
            if (m.cmd == A_STLS) throw new IOException("this TV wants a TLS (wireless debugging) connection");
            if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb reply " + name(m.cmd));
            if (!sentSignature) {
                write(A_AUTH, AUTH_SIGNATURE, 0, sign((RSAPrivateKey) key.getPrivate(), m.data));
                sentSignature = true;
            } else if (!sentKey) {
                String pub = encodePublicKey((RSAPublicKey) key.getPublic()) + " " + keyName + "\0";
                write(A_AUTH, AUTH_RSAPUBLICKEY, 0, pub.getBytes(StandardCharsets.US_ASCII));
                sentKey = true;
                if (onPrompt != null) onPrompt.run();
                socket.setSoTimeout(allowWaitMs);
                try {
                    Msg r = read();
                    if (r.cmd == A_CNXN) { socket.setSoTimeout(5000); return; }
                    throw new NotAllowedException();
                } catch (SocketTimeoutException e) {
                    throw new NotAllowedException();
                }
            } else {
                throw new NotAllowedException();
            }
        }
    }

    /** Runs a shell command and returns its output (stdout + stderr as the shell service merges them). */
    public String shell(String command, int timeoutMs) throws IOException {
        final int local = 1;
        write(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        socket.setSoTimeout(timeoutMs);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (java.io.EOFException e) {
                break;
            }
            if (m.cmd == A_OKAY) {
                remote = m.arg0;
            } else if (m.cmd == A_WRTE) {
                remote = m.arg0;
                result.write(m.data);
                write(A_OKAY, local, remote, new byte[0]);
            } else if (m.cmd == A_CLSE) {
                if (m.arg0 != 0) write(A_CLSE, local, m.arg0, new byte[0]);
                break;
            }
            if (result.size() > 1024 * 1024) break;
        }
        return result.toString("UTF-8");
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }

    // ---------------- framing ----------------

    private void write(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private Msg read() throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = h.getInt(), a0 = h.getInt(), a1 = h.getInt(), len = h.getInt();
        h.getInt();             // checksum (newer daemons send 0)
        int magic = h.getInt();
        if (magic != ~cmd) throw new IOException("bad adb header");
        if (len < 0 || len > MAX_DATA * 4) throw new IOException("adb message too large");
        byte[] d = new byte[len];
        in.readFully(d);
        return new Msg(cmd, a0, a1, d);
    }

    static int checksum(byte[] d) {
        int sum = 0;
        for (byte b : d) sum += b & 0xFF;
        return sum;
    }

    private static String name(int cmd) {
        byte[] b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array();
        return new String(b, StandardCharsets.US_ASCII);
    }

    // ---------------- crypto ----------------

    /** ASN.1 DigestInfo prefix for SHA-1: adbd verifies with RSA_verify(NID_sha1, token, ...). */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    /** RSA PKCS#1 v1.5 signature of a pre-hashed 20-byte token, built by hand so it behaves the same on every JCE. */
    static byte[] sign(RSAPrivateKey key, byte[] token) throws GeneralSecurityException {
        int k = (key.getModulus().bitLength() + 7) / 8;
        int tLen = SHA1_DIGEST_INFO.length + token.length;
        if (k < tLen + 11) throw new GeneralSecurityException("key too small");
        byte[] em = new byte[k];
        em[0] = 0;
        em[1] = 1;
        for (int i = 2; i < k - tLen - 1; i++) em[i] = (byte) 0xFF;
        em[k - tLen - 1] = 0;
        System.arraycopy(SHA1_DIGEST_INFO, 0, em, k - tLen, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, em, k - token.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        return c.doFinal(em);
    }

    /** android_pubkey format (2048-bit only): len words, n0inv, modulus LE, R^2 mod n LE, exponent; base64. */
    static String encodePublicKey(RSAPublicKey pub) {
        final int words = 64;
        BigInteger n = pub.getModulus();
        if (n.bitLength() > 2048) throw new IllegalArgumentException("adb keys are 2048-bit");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(littleEndian(n, 256));
        b.put(littleEndian(rr, 256));
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.getEncoder().encodeToString(b.array());
    }

    private static byte[] littleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) le[i] = be[be.length - 1 - i];
        return le;
    }
}
