package io.github.tvoverlaysuite;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/** Test helper in the app's package (Adb's encoder is package-private). */
public final class AdbProbe {
    public static boolean run(int port, String pemOut) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream o = new FileOutputStream(pemOut)) {
            o.write(("-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII));
        }
        System.out.println("JAVA-PUBKEY " + Adb.encodePublicKey((RSAPublicKey) kp.getPublic()));
        boolean ok = true;
        final boolean[] prompted = {false};
        try (Adb adb = Adb.connect("127.0.0.1", port, kp, "TV Overlay Suite@tv", 5000, () -> prompted[0] = true)) {
            String out = adb.shell("echo hi", 5000);
            ok &= check(prompted[0], "first connect sends the key (TV prompt)");
            ok &= check(out.equals("ran: echo hi\n"), "shell output collected across WRTE chunks: " + out.trim());
        }
        prompted[0] = false;
        try (Adb adb = Adb.connect("127.0.0.1", port, kp, "TV Overlay Suite@tv", 5000, () -> prompted[0] = true)) {
            String out = adb.shell("settings get secure enabled_accessibility_services", 5000);
            ok &= check(!prompted[0], "second connect: signature accepted, no prompt");
            ok &= check(out.startsWith("ran: settings"), "second shell works");
        }
        return ok;
    }

    private static boolean check(boolean c, String what) {
        System.out.println((c ? "PASS " : "FAIL ") + what);
        return c;
    }
}
