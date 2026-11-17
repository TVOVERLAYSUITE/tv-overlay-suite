package io.github.tvoverlaysuite;

import java.util.Locale;

/**
 * v1.6 "trackpad" messages. Plain Java (no Android APIs): the TV app and the phone app both compile this file, and
 * the JVM tests use it too.
 *
 * The phone app's trackpad drives the pointer of an app on the TV that supports it (for example a TV web browser).
 * Moves travel on the phone app's existing keyboard link (path /kb); the TV app hands them to that app through
 * {@code PadService} (see docs/trackpad.md). Distances are in pixels of a 1920-pixel-wide screen; the app scales them.
 *
 * Phone -> TV (keyboard link):
 *   P m DX DY      move the pointer
 *   P s DX DY      scroll what is under the pointer (positive DY = further down the page)
 *   P c            click
 *   P r            secondary click (the app's menu for what is under the pointer)
 *   P d 1 | P d 0  press and hold (drag) | let go
 *   P k KEY        a key: back | home | menu | pp (play / pause)
 * TV -> phone:
 *   P f 1 NAME     an app that supports the trackpad is in front on the TV (NAME base64url)
 *   P f 0          none is
 *
 * TV app <-> trackpad app (PadService messages; PadService turns them into these lines for PadRelay):
 *   app -> TV:  h VERSION        hello
 *               f 1 NAME         this app came to the front (NAME base64url, shown on the phone)
 *               f 0              this app left the front
 *   TV -> app:  m DX DY | s DX DY | c | r | d 1 | d 0 | k KEY      (the phone lines without "P ")
 */
public final class PadProto {
    private PadProto() { }

    /** Largest distance accepted in one message (anything bigger is clamped). */
    public static final float MAX_STEP = 4000f;
    public static final String[] KEYS = {"back", "home", "menu", "pp"};
    static final int MAX_LINE = 200;

    /** One decimal place, '.' as the separator whatever the phone's language. */
    static String num(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) v = 0;
        v = Math.max(-MAX_STEP, Math.min(MAX_STEP, v));
        int t = Math.round(v * 10f);
        String s = Integer.toString(Math.abs(t) / 10) + (Math.abs(t) % 10 == 0 ? "" : "." + Math.abs(t) % 10);
        return t < 0 ? "-" + s : s;
    }

    // ---------------- phone side ----------------

    public static String move(float dx, float dy) { return "P m " + num(dx) + " " + num(dy); }
    public static String scroll(float dx, float dy) { return "P s " + num(dx) + " " + num(dy); }
    public static String click() { return "P c"; }
    public static String menu() { return "P r"; }
    public static String drag(boolean down) { return down ? "P d 1" : "P d 0"; }
    public static String key(String k) { return "P k " + k; }

    /** A parsed "P f" line for the phone, or null. */
    public static final class Front {
        public boolean on;
        public String name = "";
    }

    public static Front parseFront(String line) {
        try {
            if (line == null || !line.startsWith("P f ")) return null;
            String[] a = line.trim().split(" +");
            Front f = new Front();
            f.on = a.length >= 3 && a[2].equals("1");
            if (f.on && a.length >= 4) {
                try { f.name = TypeProto.unb64(a[3]); } catch (RuntimeException e) { f.name = ""; }   // still on, no name
            }
            if (f.name.length() > 60) f.name = f.name.substring(0, 60);
            return f;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------- TV side ----------------

    public static String front(boolean on, String name) {
        if (!on) return "P f 0";
        return "P f 1 " + (name == null || name.isEmpty() ? "-" : TypeProto.b64(name));
    }

    /**
     * Checks a phone line and turns it into the line for the app ("P m 3 -2" -> "m 3 -2"), or returns null when it
     * is not a well-formed trackpad line. Numbers are re-written (clamped, one decimal), so the app only ever sees
     * this exact grammar. Never throws.
     */
    public static String toApp(String line) {
        try {
            if (line == null || line.length() < 3 || line.length() > MAX_LINE || !line.startsWith("P ")) return null;
            String[] a = line.trim().split(" +");
            if (a.length < 2 || a[1].length() != 1) return null;
            switch (a[1].charAt(0)) {
                case 'm': case 's': {
                    if (a.length != 4) return null;
                    float x = Float.parseFloat(a[2]), y = Float.parseFloat(a[3]);
                    if (Float.isNaN(x) || Float.isNaN(y)) return null;
                    return a[1] + " " + num(x) + " " + num(y);
                }
                case 'c': return a.length == 2 ? "c" : null;
                case 'r': return a.length == 2 ? "r" : null;
                case 'd': return a.length == 3 && (a[2].equals("1") || a[2].equals("0")) ? "d " + a[2] : null;
                case 'k':
                    if (a.length != 3) return null;
                    String k = a[2].toLowerCase(Locale.ROOT);
                    for (String ok : KEYS) if (ok.equals(k)) return "k " + k;
                    return null;
                default: return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An app's line to the TV: kind 'h', 'f' (with {@link Front}) or 0 when malformed. */
    public static Front parseApp(String line, char[] kind) {
        kind[0] = 0;
        if (line == null || line.length() > MAX_LINE) return null;
        String t = line.trim();
        if (t.equals("h") || t.startsWith("h ")) { kind[0] = 'h'; return null; }
        Front f = parseFront("P " + t);
        if (f != null && (t.equals("f 0") || t.startsWith("f 1"))) kind[0] = 'f';
        return kind[0] == 'f' ? f : null;
    }
}
