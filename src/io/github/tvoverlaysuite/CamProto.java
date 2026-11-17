package io.github.tvoverlaysuite;

/**
 * v1.7 "phone camera" messages. Plain Java (no Android APIs): the TV app and the phone app both compile this file, and
 * the JVM tests use it too.
 *
 * A TV app (for example a TV web browser in a video call) asks for the phone's camera and/or microphone. The suite
 * only carries the WebRTC set-up text (SDP) between the TV app and the phone app; the video and sound then go
 * straight from the phone to the TV app over the home Wi-Fi.
 *
 * Phone app <-> suite, on the phone app's keyboard link (path /kb):
 *   phone -> TV:  V y 1                 this phone app can be a camera (sent after every connect)
 *                 V s SID SDP           the phone's offer (SDP base64url)
 *                 V h SID STATE [MSG]   wait (asking on the phone) | live | deny | err   (MSG base64url, optional)
 *                 V x SID               the phone ended this session
 *   TV -> phone:  V o SID KIND FACING APP NAME   open: KIND v | a | va; FACING u (front) | e (back);
 *                                                APP = asking app's package, NAME = its label (both base64url)
 *                 V s SID SDP           the TV app's answer
 *                 V f SID FACING        switch to the front / back camera
 *                 V x SID               the TV app ended this session
 *
 * TV app <-> suite (PadService messages: app -> suite CAM, suite -> app CAM_LINE, data "l"):
 *   app -> suite: o SID KIND FACING | s SID SDP | f SID FACING | x SID
 *   suite -> app: y N  (phone apps ready to be a camera) | s SID SDP | h SID STATE [MSG] | x SID
 *
 * SID: 8 letters or digits, chosen by the TV app.
 */
public final class CamProto {
    private CamProto() { }

    /** Longest SDP accepted, base64url characters (an SDP with a few candidates is about 4-8 KB). */
    public static final int MAX_SDP = 48000;
    static final int MAX_MSG = 400;
    public static final String[] STATES = {"wait", "live", "deny", "err"};

    /** A parsed line. kind: 'o' open, 's' sdp, 'f' facing, 'x' close, 'h' state, 'y' ready. */
    public static final class Msg {
        public char kind;
        public String sid = "", media = "", facing = "", sdp = "", state = "", msg = "", app = "", name = "";
        public int count;
    }

    public static boolean validSid(String s) {
        if (s == null || s.length() != 8) return false;
        for (int i = 0; i < 8; i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    static boolean b64ok(String s, int max) {
        if (s == null || s.isEmpty() || s.length() > max) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /** "v", "a" or "va" (any order, no repeats), else null. */
    static String media(String s) {
        if ("v".equals(s) || "a".equals(s)) return s;
        if ("va".equals(s) || "av".equals(s)) return "va";
        return null;
    }

    static boolean facingOk(String s) { return "u".equals(s) || "e".equals(s); }

    static boolean stateOk(String s) {
        for (String x : STATES) if (x.equals(s)) return true;
        return false;
    }

    /** A line from a TV app (no "V " prefix): o, s, f or x. Null when malformed. Never throws. */
    public static Msg parseApp(String line) {
        try {
            if (line == null || line.length() > MAX_SDP + 20) return null;
            String[] a = line.trim().split(" ");
            if (a.length < 2 || a[0].length() != 1 || !validSid(a[1])) return null;
            Msg m = new Msg();
            m.kind = a[0].charAt(0);
            m.sid = a[1];
            switch (m.kind) {
                case 'o':
                    if (a.length != 4 || media(a[2]) == null || !facingOk(a[3])) return null;
                    m.media = media(a[2]);
                    m.facing = a[3];
                    return m;
                case 's':
                    if (a.length != 3 || !b64ok(a[2], MAX_SDP)) return null;
                    m.sdp = a[2];
                    return m;
                case 'f':
                    if (a.length != 3 || !facingOk(a[2])) return null;
                    m.facing = a[2];
                    return m;
                case 'x':
                    return a.length == 2 ? m : null;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A line from a phone app ("V ..."): y, s, h or x. Null when malformed. Never throws. */
    public static Msg parsePhone(String line) {
        try {
            if (line == null || line.length() > MAX_SDP + 20 || !line.startsWith("V ")) return null;
            String[] a = line.trim().split(" ");
            if (a.length < 3 || a[1].length() != 1) return null;
            Msg m = new Msg();
            m.kind = a[1].charAt(0);
            if (m.kind == 'y') return a.length == 3 && a[2].equals("1") ? m : null;
            if (!validSid(a[2])) return null;
            m.sid = a[2];
            switch (m.kind) {
                case 's':
                    if (a.length != 4 || !b64ok(a[3], MAX_SDP)) return null;
                    m.sdp = a[3];
                    return m;
                case 'h':
                    if (a.length < 4 || a.length > 5 || !stateOk(a[3])) return null;
                    m.state = a[3];
                    if (a.length == 5) { if (!b64ok(a[4], MAX_MSG)) return null; m.msg = a[4]; }
                    return m;
                case 'x':
                    return a.length == 3 ? m : null;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A line from the suite for the phone ("V ..."): o, s, f or x. The phone app's parser. Never throws. */
    public static Msg parseForPhone(String line) {
        try {
            if (line == null || line.length() > MAX_SDP + 400 || !line.startsWith("V ")) return null;
            String[] a = line.trim().split(" ");
            if (a.length < 3 || a[1].length() != 1 || !validSid(a[2])) return null;
            Msg m = new Msg();
            m.kind = a[1].charAt(0);
            m.sid = a[2];
            switch (m.kind) {
                case 'o':
                    if (a.length != 7 || media(a[3]) == null || !facingOk(a[4]) || !b64ok(a[5], 400) || !b64ok(a[6], 400)) return null;
                    m.media = media(a[3]);
                    m.facing = a[4];
                    m.app = TypeProto.unb64(a[5]);
                    m.name = TypeProto.unb64(a[6]);
                    return m;
                case 's':
                    if (a.length != 4 || !b64ok(a[3], MAX_SDP)) return null;
                    m.sdp = a[3];
                    return m;
                case 'f':
                    if (a.length != 4 || !facingOk(a[3])) return null;
                    m.facing = a[3];
                    return m;
                case 'x':
                    return a.length == 3 ? m : null;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A line from the suite for a TV app: y, s, h or x. The TV app's parser. Never throws. */
    public static Msg parseForApp(String line) {
        try {
            if (line == null || line.length() > MAX_SDP + 600) return null;
            String[] a = line.trim().split(" ");
            if (a.length < 2 || a[0].length() != 1) return null;
            Msg m = new Msg();
            m.kind = a[0].charAt(0);
            if (m.kind == 'y') {
                if (a.length != 2) return null;
                m.count = Integer.parseInt(a[1]);
                return m.count >= 0 && m.count < 100 ? m : null;
            }
            if (!validSid(a[1])) return null;
            m.sid = a[1];
            switch (m.kind) {
                case 's':
                    if (a.length != 3 || !b64ok(a[2], MAX_SDP)) return null;
                    m.sdp = a[2];
                    return m;
                case 'h':
                    if (a.length < 3 || a.length > 4 || !stateOk(a[2])) return null;
                    m.state = a[2];
                    if (a.length == 4) { if (!b64ok(a[3], MAX_MSG)) return null; m.msg = a[3]; }
                    return m;
                case 'x':
                    return a.length == 2 ? m : null;
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------- builders ----------------

    public static String ready() { return "V y 1"; }

    public static String open(String sid, String media, String facing, String app, String name) {
        return "V o " + sid + " " + media + " " + facing + " " + enc(app) + " " + enc(name);
    }

    private static String enc(String s) {
        if (s == null || s.isEmpty()) s = "?";
        if (s.length() > 100) s = s.substring(0, 100);
        return TypeProto.b64(s);
    }

    public static String state(String sid, String state, String msg) {
        String m = msg == null || msg.isEmpty() ? "" : " " + TypeProto.b64(msg.length() > 200 ? msg.substring(0, 200) : msg);
        return "V h " + sid + " " + state + m;
    }

    /** To a TV app: the number of phone apps ready to be a camera. */
    public static String count(int n) { return "y " + n; }

    /** To a TV app: a session failed before it started (no phone, not in front...). */
    public static String appError(String sid, String msg) {
        return "h " + sid + " err " + TypeProto.b64(msg);
    }
}
