package io.github.tvoverlaysuite;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * v1.2 commands that are not part of the drawing: night dimmer, pop-up messages, laser pointer.
 * Plain Java (testable on a JVM). Network threads call {@link #route}; the Sink decides which thread does the work.
 *
 *   d LEVEL [WARMTH]                  dimmer 0-90 %, warmth 0-100 (TV echoes "d LEVEL WARMTH" to every phone)
 *   p ID SECONDS POS KIND PAYLOAD     pop-up. POS = top|center|bottom, KIND = t (text) | s (sticker),
 *                                     PAYLOAD = base64url of the UTF-8 text. Not stored, not relayed.
 *   l X Y                             laser dot at board coordinates (1920 x 1080)
 *   l -                               hide the laser now
 *   l * X Y                           "ping" ring at X Y
 *   i ID POS SIZE BASE64JPEG          picture-in-picture photo (v1.3). POS = tl|tr|bl|br|c, SIZE = s|m|l,
 *                                     standard base64 of a JPEG/PNG the phone already shrank. Replaces any photo shown.
 *   i ~ POS SIZE                      move / resize the photo on screen without resending it
 *   i -                               remove the photo
 * TV -> phone: "I 1 POS SIZE" (photo shown) / "I 0" (no photo), also in the on-connect snapshot.
 */
public final class Extras {
    private Extras() { }

    public static final int TOP = 0, CENTER = 1, BOTTOM = 2;
    public static final int MAX_DIM = 90;
    public static final int MAX_TEXT = 160;      // code points
    public static final int MAX_STICKER = 16;    // UTF-16 units (ZWJ emoji sequences)
    public static final int MAX_PHOTO_BYTES = 3 * 1024 * 1024;
    public static final String[] PHOTO_POS = {"tl", "tr", "bl", "br", "c"};
    public static final String[] PHOTO_SIZE = {"s", "m", "l"};

    public interface Sink {
        void dim(int level, int warmth);          // warmth = -1 when the phone did not send one
        void popup(int seconds, int pos, boolean sticker, String text);
        void laser(float x, float y);
        void laserHide();
        void laserPing(float x, float y);
        /** Called on the network thread (decode the image there, never on the main thread). */
        void photo(int pos, int size, byte[] image);
        void photoMove(int pos, int size);
        void photoHide();
    }

    /** Handles the d / p / l lines of a message and returns the remaining lines (for Ink), or "" if none. */
    public static String route(String text, Sink sink) {
        StringBuilder rest = null;
        int start = 0, len = text.length();
        boolean any = false;
        while (start < len) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) nl = len;
            String line = text.substring(start, nl).trim();
            start = nl + 1;
            if (line.isEmpty()) continue;
            char c = line.charAt(0);
            if ((c == 'd' || c == 'p' || c == 'l' || c == 'i') && (line.length() == 1 || line.charAt(1) == ' ')) {
                any = true;
                try { apply(line, sink); } catch (RuntimeException ignored) { /* malformed: skip */ }
            } else {
                if (rest == null) rest = new StringBuilder(text.length());
                if (rest.length() > 0) rest.append('\n');
                rest.append(line);
            }
        }
        if (!any) return text;          // nothing for us: hand the message on unchanged
        return rest == null ? "" : rest.toString();
    }

    private static void apply(String line, Sink sink) {
        String[] a = line.split(" +");
        switch (a[0]) {
            case "d": {
                int level = clamp(Math.round(Float.parseFloat(a[1])), 0, MAX_DIM);
                int warmth = a.length > 2 ? clamp(Math.round(Float.parseFloat(a[2])), 0, 100) : -1;
                sink.dim(level, warmth);
                break;
            }
            case "p": {
                if (a.length < 6) return;
                int secs = clamp(Integer.parseInt(a[2]), 3, 15);
                int pos = a[3].equals("top") ? TOP : a[3].equals("bottom") ? BOTTOM : CENTER;
                boolean sticker = a[4].equals("s");
                String t = decode(a[5]);
                t = sticker ? cleanSticker(t) : cleanText(t);
                if (t.isEmpty()) return;
                sink.popup(secs, pos, sticker, t);
                break;
            }
            case "l": {
                if (a.length >= 2 && a[1].equals("-")) { sink.laserHide(); return; }
                if (a.length >= 4 && a[1].equals("*")) {
                    sink.laserPing(fx(a[2]), fy(a[3]));
                    return;
                }
                if (a.length >= 3) sink.laser(fx(a[1]), fy(a[2]));
                break;
            }
            case "i": {
                if (a.length >= 2 && a[1].equals("-")) { sink.photoHide(); return; }
                if (a.length < 4) return;
                int pos = indexOf(PHOTO_POS, a[2], 3), size = indexOf(PHOTO_SIZE, a[3], 1);
                if (a[1].equals("~")) { sink.photoMove(pos, size); return; }
                if (a.length < 5 || a[4].length() > MAX_PHOTO_BYTES / 3 * 4 + 8) return;
                byte[] img = Base64.getDecoder().decode(a[4]);
                if (!isImage(img)) return;
                sink.photo(pos, size, img);
                break;
            }
            default:
        }
    }

    static int indexOf(String[] list, String v, int fallback) {
        for (int i = 0; i < list.length; i++) if (list[i].equals(v)) return i;
        return fallback;
    }

    /** JPEG (FF D8 FF), PNG or WebP signature: anything else is rejected before decoding. */
    static boolean isImage(byte[] b) {
        if (b.length < 12) return false;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    static String decode(String b64url) {
        if (b64url.length() > 2048) throw new IllegalArgumentException("too long");
        byte[] b = Base64.getUrlDecoder().decode(b64url); // padding optional
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Plain text only: no control characters except line breaks, at most 3 lines, at most 160 characters. */
    static String cleanText(String s) {
        StringBuilder b = new StringBuilder();
        int lines = 1, count = 0;
        for (int i = 0; i < s.length() && count < MAX_TEXT; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r') continue;
            if (cp == '\n') {
                if (lines >= 3 || b.length() == 0 || b.charAt(b.length() - 1) == '\n') continue;
                lines++;
            } else if (cp == '\t') {
                cp = ' ';
            } else if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069)) {
                continue;   // also drops bidi override characters
            }
            b.appendCodePoint(cp);
            count++;
        }
        return b.toString().trim();
    }

    static String cleanSticker(String s) {
        s = s.trim();
        if (s.length() > MAX_STICKER || s.indexOf('\n') >= 0) return "";
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp)) return "";
        }
        return s;
    }

    private static float fx(String v) { return clampF(Float.parseFloat(v), 0, Ink.W); }
    private static float fy(String v) { return clampF(Float.parseFloat(v), 0, Ink.H); }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    static float clampF(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
