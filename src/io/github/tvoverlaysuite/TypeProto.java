package io.github.tvoverlaysuite;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * v1.5 "type from the phone" messages. Plain Java (no Android APIs): the TV app and the phone app both compile this
 * file, and the JVM tests use it too. Text travels as base64url (no padding) so it may hold spaces and line breaks.
 *
 * Phone -> TV (only on a keyboard connection, path /kb):
 *   T e SEQ FIELD START DEL CURSOR TEXT   replace DEL chars at START with TEXT, then put the cursor at CURSOR.
 *                                         SEQ counts up per phone; FIELD is the id from the last "T o".
 *   T a FIELD                             press the field's Enter / Search / Go
 *   T w                                   "I want to type": TV answers with "T o" (reason key) or "T n"
 * TV -> phone:
 *   T o REASON FIELD SEQ SELS SELE FLAGS ACTION HINT TEXT
 *                                         a text box is open. REASON a (auto, TV keyboard came up) | k (the phone
 *                                         key on the TV keyboard, or "T w"). SEQ = last phone edit already applied.
 *   T s FIELD SEQ SELS SELE TEXT          the box changed by itself (TV keyboard, the app cleared it, ...)
 *   T c FIELD                             the box closed
 *   T n WHY                               no box to type in. WHY: none (nothing selected) | ime (the suite is not
 *                                         the TV's keyboard)
 *   T i ID NAME                           sent once after connecting: the TV's install id and name (base64url)
 */
public final class TypeProto {
    private TypeProto() { }

    public static final int F_PASSWORD = 1, F_MULTILINE = 2, F_NUMBER = 4, F_EMAIL = 8, F_URI = 16;
    /** Longest field text that is mirrored to the phone; longer boxes are cut to the text around the cursor. */
    public static final int MAX_TEXT = 4000;

    public static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String unb64(String s) {
        if (s == null || s.isEmpty() || s.equals("-")) return "";
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    private static String enc(String s) { return s == null || s.isEmpty() ? "-" : b64(s); }

    // ---------------- message builders ----------------

    public static String edit(int seq, int field, int start, int del, int cursor, String text) {
        return "T e " + seq + " " + field + " " + start + " " + del + " " + cursor + " " + enc(text);
    }

    public static String action(int field) { return "T a " + field; }

    public static String want() { return "T w"; }

    public static String open(boolean auto, int field, int seq, int selS, int selE, int flags, String action,
                              String hint, String text) {
        return "T o " + (auto ? "a" : "k") + " " + field + " " + seq + " " + selS + " " + selE + " " + flags + " "
                + enc(action) + " " + enc(hint) + " " + enc(text);
    }

    public static String state(int field, int seq, int selS, int selE, String text) {
        return "T s " + field + " " + seq + " " + selS + " " + selE + " " + enc(text);
    }

    public static String closed(int field) { return "T c " + field; }

    public static String none(String why) { return "T n " + why; }

    public static String id(String id, String name) { return "T i " + id + " " + enc(name); }

    // ---------------- parsing ----------------

    /** A parsed T message. Unused fields stay 0 / "". */
    public static final class Msg {
        public char kind;
        public boolean auto;
        public int seq, field, start, del, cursor, selS, selE, flags;
        public String text = "", hint = "", action = "", why = "", id = "", name = "";
    }

    /** Parses one line ("T ..."), or returns null when it is not a well-formed T message. Never throws. */
    public static Msg parse(String line) {
        try {
            if (line == null || line.length() < 3 || line.charAt(0) != 'T' || line.charAt(1) != ' ') return null;
            String[] a = line.trim().split(" +");
            if (a.length < 2 || a[1].length() != 1) return null;
            Msg m = new Msg();
            m.kind = a[1].charAt(0);
            switch (m.kind) {
                case 'e':
                    if (a.length < 8) return null;
                    m.seq = Integer.parseInt(a[2]); m.field = Integer.parseInt(a[3]);
                    m.start = Integer.parseInt(a[4]); m.del = Integer.parseInt(a[5]); m.cursor = Integer.parseInt(a[6]);
                    m.text = unb64(a[7]);
                    if (m.start < 0 || m.del < 0 || m.cursor < 0 || m.text.length() > MAX_TEXT) return null;
                    return m;
                case 'a': m.field = Integer.parseInt(a[2]); return m;
                case 'w': return m;
                case 'o':
                    if (a.length < 11) return null;
                    m.auto = a[2].equals("a");
                    m.field = Integer.parseInt(a[3]); m.seq = Integer.parseInt(a[4]);
                    m.selS = Integer.parseInt(a[5]); m.selE = Integer.parseInt(a[6]); m.flags = Integer.parseInt(a[7]);
                    m.action = unb64(a[8]); m.hint = unb64(a[9]); m.text = unb64(a[10]);
                    return m;
                case 's':
                    if (a.length < 7) return null;
                    m.field = Integer.parseInt(a[2]); m.seq = Integer.parseInt(a[3]);
                    m.selS = Integer.parseInt(a[4]); m.selE = Integer.parseInt(a[5]); m.text = unb64(a[6]);
                    return m;
                case 'c': m.field = Integer.parseInt(a[2]); return m;
                case 'n': m.why = a.length > 2 ? a[2] : "none"; return m;
                case 'i':
                    if (a.length < 4) return null;
                    m.id = a[2]; m.name = unb64(a[3]);
                    return m;
                default: return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------- applying a phone edit to the TV's text box ----------------

    /** The three InputConnection calls an edit needs (the keyboard wraps the real connection; tests use a fake). */
    public interface Ops {
        void deleteBefore(int n);
        void select(int start, int end);
        void commit(String text);
    }

    /**
     * Plays one phone edit on the box. {@code len}, {@code selS}, {@code selE} describe the mirror the phone edited;
     * {@code winOff} is where the mirror starts inside the box. Values are clamped. Returns the cursor (mirror
     * coordinates) after the edit. Backspace at the cursor and typing at the cursor use no selection call, which is
     * the fastest path and works even in boxes that ignore setSelection.
     */
    public static int play(Ops o, int len, int selS, int selE, int winOff, int start, int del, String ins, int cursor) {
        start = Math.max(0, Math.min(start, len));
        del = Math.max(0, Math.min(del, len - start));
        cursor = Math.max(0, Math.min(cursor, len - del + ins.length()));
        boolean collapsed = selS == selE;
        if (del > 0 && collapsed && selS == start + del) {
            o.deleteBefore(del);
        } else if (!(collapsed && selS == start && del == 0)) {
            o.select(winOff + start, winOff + start + del);
            if (ins.isEmpty() && del > 0) o.commit("");
        }
        if (!ins.isEmpty()) o.commit(ins);
        if (cursor != start + ins.length()) o.select(winOff + cursor, winOff + cursor);
        return cursor;
    }

    // ---------------- text helpers ----------------

    /** The smallest single replacement that turns {@code before} into {@code after}: {start, deleteCount, insertEnd}
     *  where the inserted text is {@code after.substring(start, insertEnd)}. Surrogate pairs are never split. */
    public static int[] diff(String before, String after) {
        int n = before.length(), m = after.length();
        int p = 0;
        while (p < n && p < m && before.charAt(p) == after.charAt(p)) p++;
        int s = 0;
        while (s < n - p && s < m - p && before.charAt(n - 1 - s) == after.charAt(m - 1 - s)) s++;
        // do not cut a surrogate pair at either edge of the changed range
        if (p > 0 && p < m && Character.isLowSurrogate(after.charAt(p)) && Character.isHighSurrogate(after.charAt(p - 1))) p--;
        int endB = n - s, endA = m - s;
        if (s > 0 && endA > 0 && endA < m && Character.isLowSurrogate(after.charAt(endA)) && Character.isHighSurrogate(after.charAt(endA - 1))) {
            endB++; endA++;
        }
        return new int[]{p, endB - p, endA};
    }

    /** Applies an edit to a plain string (the TV's mirror of the field, and the tests). Clamps out-of-range values. */
    public static String apply(String text, int start, int del, String ins) {
        int st = Math.max(0, Math.min(start, text.length()));
        int end = Math.max(st, Math.min(st + del, text.length()));
        return text.substring(0, st) + ins + text.substring(end);
    }

    /** Cuts a long field to MAX_TEXT characters around the cursor. Returns {text, offset} as Object[]. */
    public static Object[] window(String text, int cursor) {
        if (text.length() <= MAX_TEXT) return new Object[]{text, 0};
        int from = Math.max(0, Math.min(cursor - MAX_TEXT / 2, text.length() - MAX_TEXT));
        if (from > 0 && Character.isLowSurrogate(text.charAt(from))) from++;
        int to = Math.min(text.length(), from + MAX_TEXT);
        if (to < text.length() && to > 0 && Character.isHighSurrogate(text.charAt(to - 1))) to--;
        return new Object[]{text.substring(from, to), from};
    }
}
