package io.github.tvoverlaysuite;

import android.graphics.Bitmap;
import io.nayuki.qrcodegen.QrCode;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** QR code bitmap + the TV's own Wi-Fi / Ethernet address. */
final class Qr {
    private Qr() { }

    static Bitmap bitmap(String text) {
        try {
            QrCode q = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
            int n = q.size;
            int[] px = new int[n * n];
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++)
                    px[y * n + x] = q.getModule(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap small = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888);
            // Enlarge with hard edges so the modules stay crisp when drawn.
            Bitmap big = Bitmap.createScaledBitmap(small, n * 10, n * 10, false);
            small.recycle();
            return big;
        } catch (Exception e) {
            return null;
        }
    }

    /** Best guess at the TV's LAN IPv4 address, or null. */
    static String ip() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String name = ni.getName();
                    if (a.isSiteLocalAddress() && (name.startsWith("wlan") || name.startsWith("eth"))) return ip;
                    if (a.isSiteLocalAddress() && fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }
}
