package io.github.tvoverlaysuite;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * The drawing, as a list of strokes in board units (1920 x 1080). Plain Java; thread-safe.
 *
 * Protocol (one command per line, a WebSocket message can carry several lines):
 *   s ID COLOR WIDTH X Y   start a stroke (COLOR = rrggbb hex, or E for eraser)
 *   m ID X Y [X Y ...]     more points
 *   e ID                   end of stroke
 *   u ID                   undo (remove) a stroke
 *   x                      clear everything
 *   t 1|0                  phone asks to show / hide the overlay (not relayed)
 * Server to phone, in addition: v 1|0 (overlay shown / hidden), n COUNT (phones connected).
 *
 * Pairing (v1.4, see InkServer): the phone's first frame must be "k CODE" (the 6-character code shown on the
 * TV; the QR carries it as #k=CODE). The TV answers "K ok" and then the snapshot, or "K bad" / "K wait" and closes.
 */
public final class Ink {
    public static final int W = 1920, H = 1080;
    static final int MAX_POINTS_PER_STROKE = 20000;
    static final int MAX_STROKES = 4000;

    public static final int CHANGED = 1, WANT_SHOW = 2, WANT_HIDE = 4;

    public interface Renderer {
        void onPoints(Stroke s, int fromPoint);
        void onEnd(Stroke s);
        void onRebuild();
    }

    public static final class Stroke {
        public final String id;
        public final String colorHex;
        public final int color;       // ARGB
        public final float width;
        public final boolean eraser;
        public final Object owner;
        public float[] pts = new float[64];
        public int count;             // number of points
        public boolean done;
        public Object tag;            // renderer data (Android Path)

        Stroke(String id, String colorHex, float width, Object owner) {
            this.id = id;
            this.colorHex = colorHex;
            this.eraser = colorHex.equals("E");
            this.color = eraser ? 0 : (0xFF000000 | Integer.parseInt(colorHex, 16));
            this.width = width;
            this.owner = owner;
        }

        void add(float x, float y) {
            if (count >= MAX_POINTS_PER_STROKE) return;
            if (count * 2 + 2 > pts.length) {
                float[] n = new float[pts.length * 2];
                System.arraycopy(pts, 0, n, 0, count * 2);
                pts = n;
            }
            pts[count * 2] = clamp(x, -50, W + 50);
            pts[count * 2 + 1] = clamp(y, -50, H + 50);
            count++;
        }
    }

    public final ArrayList<Stroke> strokes = new ArrayList<>();
    private final HashMap<String, Stroke> byId = new HashMap<>();
    private final Renderer renderer;

    public Ink(Renderer renderer) { this.renderer = renderer; }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Applies a message from a phone. Returns CHANGED / WANT_SHOW / WANT_HIDE flags. */
    public synchronized int apply(Object owner, String text) {
        int flags = 0;
        int start = 0, len = text.length();
        while (start < len) {
            int nl = text.indexOf('\n', start);
            if (nl < 0) nl = len;
            if (nl > start) {
                try {
                    flags |= applyLine(owner, text.substring(start, nl).trim());
                } catch (RuntimeException ignored) {
                    // malformed line: skip it
                }
            }
            start = nl + 1;
        }
        return flags;
    }

    private int applyLine(Object owner, String line) {
        if (line.isEmpty()) return 0;
        String[] a = line.split(" +");
        switch (a[0]) {
            case "s": {
                if (a.length < 6 || byId.containsKey(a[1]) || strokes.size() >= MAX_STROKES) return 0;
                String col = a[2].equals("E") ? "E" : a[2].toLowerCase();
                if (!col.equals("E") && !col.matches("[0-9a-f]{6}")) return 0;
                float w = clamp(Float.parseFloat(a[3]), 1, 200);
                Stroke s = new Stroke(a[1], col, w, owner);
                s.add(Float.parseFloat(a[4]), Float.parseFloat(a[5]));
                strokes.add(s);
                byId.put(s.id, s);
                if (renderer != null) renderer.onPoints(s, 0);
                return CHANGED | WANT_SHOW;
            }
            case "m": {
                Stroke s = byId.get(a[1]);
                if (s == null || s.done) return 0;
                int from = s.count;
                for (int i = 2; i + 1 < a.length; i += 2) s.add(Float.parseFloat(a[i]), Float.parseFloat(a[i + 1]));
                if (renderer != null && s.count > from) renderer.onPoints(s, from);
                return CHANGED;
            }
            case "e": {
                Stroke s = byId.get(a[1]);
                if (s == null || s.done) return 0;
                s.done = true;
                if (renderer != null) renderer.onEnd(s);
                return CHANGED;
            }
            case "u": {
                Stroke s = byId.remove(a[1]);
                if (s == null) return 0;
                strokes.remove(s);
                if (renderer != null) renderer.onRebuild();
                return CHANGED;
            }
            case "x": {
                strokes.clear();
                byId.clear();
                if (renderer != null) renderer.onRebuild();
                return CHANGED;
            }
            case "t":
                return (a.length > 1 && a[1].equals("1")) ? WANT_SHOW : WANT_HIDE;
            default:
                return 0;
        }
    }

    /** Ends strokes left open by a phone that disconnected mid-line. Returns their "e" lines (may be empty). */
    public synchronized String endOpenStrokes(Object owner) {
        StringBuilder b = new StringBuilder();
        for (Stroke s : strokes) {
            if (!s.done && s.owner == owner) {
                s.done = true;
                if (renderer != null) renderer.onEnd(s);
                b.append("e ").append(s.id).append('\n');
            }
        }
        return b.toString();
    }

    public synchronized void clear() {
        strokes.clear();
        byId.clear();
        if (renderer != null) renderer.onRebuild();
    }

    /** Whole drawing as protocol lines, for a phone that just connected. */
    public synchronized String snapshot() {
        StringBuilder b = new StringBuilder(4096);
        for (Stroke s : strokes) {
            b.append("s ").append(s.id).append(' ').append(s.colorHex).append(' ').append(num(s.width))
             .append(' ').append(num(s.pts[0])).append(' ').append(num(s.pts[1])).append('\n');
            for (int i = 1; i < s.count; ) {
                b.append("m ").append(s.id);
                int end = Math.min(s.count, i + 200);
                for (; i < end; i++) b.append(' ').append(num(s.pts[i * 2])).append(' ').append(num(s.pts[i * 2 + 1]));
                b.append('\n');
            }
            if (s.done) b.append("e ").append(s.id).append('\n');
        }
        return b.toString();
    }

    static String num(float v) {
        int r = Math.round(v * 10);
        return (r % 10 == 0) ? Integer.toString(r / 10) : Float.toString(r / 10f);
    }
}
