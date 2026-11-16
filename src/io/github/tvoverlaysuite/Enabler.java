package io.github.tvoverlaysuite;

import android.Manifest;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Switches the accessibility service on without a computer.
 *
 * First time ("Enable automatically"): connect to the TV's own adb daemon (USB debugging must be on; the TV asks
 * "Allow USB debugging?" once), grant this app WRITE_SECURE_SETTINGS, then switch the service on.
 * Afterwards (e.g. after an update, which drops the service from the enabled list) the app can switch it back on
 * by itself, no adb needed.
 *
 * Switching on = remove our entry, wait, add it back (other services in the list are kept), accessibility_enabled 1,
 * then wait until the service is actually running (bound), retrying up to 4 times: right after an update the TV
 * can drop a too-early re-enable while the setting still lists it.
 * Runs on a background thread; the listener is called on that thread.
 */
final class Enabler {

    interface Listener {
        void step(String message);
    }

    static final int ADB_PORT = 5555;
    private static final int TRIES = 4;

    private Enabler() { }

    static String component(Context ctx) {
        return new ComponentName(ctx, MarkerService.class).flattenToString();
    }

    static boolean canWriteSecureSettings(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean running() { return MarkerService.instance != null; }

    /** No adb: only works once WRITE_SECURE_SETTINGS was granted. Returns true when the service is running. */
    static boolean enableWithPermission(Context ctx, Listener l) {
        if (!canWriteSecureSettings(ctx)) return false;
        for (int t = 1; t <= TRIES; t++) {
            if (running()) return true;
            if (l != null) l.step(t == 1 ? "Switching the service on…" : "Not running yet, trying again (" + t + " of " + TRIES + ")…");
            try {
                toggleInSettings(ctx);
            } catch (SecurityException e) {
                return false;
            }
            if (waitRunning(5000)) return true;
        }
        return running();
    }

    /** Full path through the TV's own adb. Returns a message for the user (success or what to do next). */
    static Result enableWithAdb(Context ctx, Listener l) {
        if (running()) return new Result(true, "The service is on.");
        if (enableWithPermission(ctx, l)) return new Result(true, "The service is on.");
        KeyPair key;
        try {
            key = loadOrCreateKey(ctx);
        } catch (Exception e) {
            return new Result(false, "Could not create the adb key: " + e.getMessage());
        }
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        String ip = Qr.ip();
        if (ip != null) hosts.add(ip);
        Adb adb = null;
        Exception last = null;
        for (String h : hosts) {
            if (l != null) l.step("Connecting to the TV's debugging service…");
            try {
                adb = Adb.connect(h, ADB_PORT, key, "TV Overlay Suite@tv", 60000,
                        () -> { if (l != null) l.step("Look at the TV: select “Always allow from this computer”, then “Allow”."); });
                break;
            } catch (Adb.NotAllowedException e) {
                return new Result(false, "USB debugging was not allowed. Press “Enable automatically” again and choose Allow on the TV prompt.");
            } catch (Exception e) {
                last = e;
            }
        }
        if (adb == null) {
            String why = last == null ? "" : " (" + last.getMessage() + ")";
            return new Result(false, "Could not reach the TV's debugging service" + why
                    + ". Check step 1: Developer options → USB debugging must be On. Some TVs only offer “Wireless debugging” "
                    + "with a pairing code; on those use the computer method shown below.");
        }
        String pkg = ctx.getPackageName();
        try {
            if (l != null) l.step("Connected. Giving the app permission to switch itself on…");
            adb.shell("pm grant " + pkg + " " + Manifest.permission.WRITE_SECURE_SETTINGS, 10000);
            if (enableWithPermission(ctx, l)) return new Result(true, "Done. The service is on.");
            // Permission not granted on this TV: do the same switching from the adb shell.
            for (int t = 1; t <= TRIES; t++) {
                if (l != null) l.step("Switching the service on through adb (" + t + " of " + TRIES + ")…");
                adb.shell(ServiceList.shellToggle(component(ctx)), 15000);
                if (waitRunning(5000)) return new Result(true, "Done. The service is on.");
            }
            return new Result(false, "The setting was changed but the service did not start. Restart the TV, then open this app again.");
        } catch (IOException e) {
            return new Result(false, "The connection to the TV's debugging service dropped: " + e.getMessage());
        } finally {
            adb.close();
        }
    }

    static final class Result {
        final boolean ok; final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    // ---------------- switching ----------------

    private static void toggleInSettings(Context ctx) {
        ContentResolver cr = ctx.getContentResolver();
        String me = component(ctx);
        String cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        String without = ServiceList.remove(cur, me, ctx.getPackageName());
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without);
        SystemClock.sleep(1000);
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, without.isEmpty() ? me : without + ":" + me);
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    private static boolean waitRunning(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        while (SystemClock.uptimeMillis() < end) {
            if (running()) return true;
            SystemClock.sleep(250);
        }
        return running();
    }

    // ---------------- adb key (kept in app storage so the TV only asks once) ----------------

    private static KeyPair loadOrCreateKey(Context ctx) throws Exception {
        File priv = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            try {
                PrivateKey p = kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv)));
                PublicKey q = kf.generatePublic(new X509EncodedKeySpec(readAll(pub)));
                return new KeyPair(q, p);
            } catch (Exception ignored) {
                // corrupt: make a new one (the TV will ask again)
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        writeAll(priv, kp.getPrivate().getEncoded());
        writeAll(pub, kp.getPublic().getEncoded());
        return kp;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                int r = in.read(b, off, b.length - off);
                if (r < 0) throw new IOException("short read");
                off += r;
            }
        }
        return b;
    }

    private static void writeAll(File f, byte[] data) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(data); }
    }
}
