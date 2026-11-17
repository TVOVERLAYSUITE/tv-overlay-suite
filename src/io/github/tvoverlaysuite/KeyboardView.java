package io.github.tvoverlaysuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * One custom-drawn view: no child views, no layout passes, no animations.
 * Moving focus just changes two ints and redraws.
 */
final class KeyboardView extends View {

    static final int T_CHAR = 0, T_SHIFT = 1, T_SYM = 2, T_SPACE = 3, T_DEL = 4, T_ENTER = 5, T_TEXT = 6, T_PHONE = 7;

    static final class Key {
        final String label;
        final String out;
        final String alt;
        final int type;
        final float units;
        final RectF r = new RectF();
        float cx;

        Key(String label, String out, String alt, int type, float units) {
            this.label = label; this.out = out; this.alt = alt; this.type = type; this.units = units;
        }
    }

    interface Listener {
        void onKey(Key k);
        void onSuggestion(String s);
    }

    private static final int BG = 0xFF12161D, KEY = 0xFF232A35, FN = 0xFF2E3644, CHIP = 0xFF1C222C;
    private static final int TEXT = 0xFFE8ECF2, DIM = 0xFF8D97A6, FOCUS_TEXT = 0xFFFFFFFF;
    private static final int ACCENT = 0xFF4C8DFF, SHIFT_ON = 0xFF3A5A94;

    private final Listener listener;
    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path icon = new Path();

    private List<List<Key>> letters, symbols, rows;
    private final ArrayList<String> sugg = new ArrayList<>();
    private final ArrayList<RectF> suggRects = new ArrayList<>();
    private String hint = "";

    // focus: row -1 = suggestion bar, 0..3 = key rows
    private int fr = 1, fc = 4;
    private float stickyX = -1;

    boolean shift, caps, symMode;
    private String enterLabel = "Enter";
    private int w, h;

    // geometry (px)
    private final float padTop, padBottom, padH, gap, keyH, suggH, radius, maxW;

    KeyboardView(Context c, Listener l) {
        super(c);
        listener = l;
        d = c.getResources().getDisplayMetrics().density;
        padTop = 8 * d; padBottom = 14 * d; padH = 40 * d; gap = 6 * d;
        keyH = 44 * d; suggH = 36 * d; radius = 8 * d; maxW = 860 * d;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * d);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        buildLayouts('\'');
        rows = letters;
    }

    /** lastKey: the key at the end of the middle letter row ("'" normally, "@" for email, "/" for URLs). */
    void buildLayouts(char lastKey) {
        letters = new ArrayList<>();
        letters.add(charRow("qwertyuiop"));
        List<Key> r1 = charRow("asdfghjkl");
        r1.add(ch(String.valueOf(lastKey)));
        letters.add(r1);
        letters.add(charRow("zxcvbnm,.-"));
        letters.add(bottom("?123"));

        symbols = new ArrayList<>();
        symbols.add(charRow("1234567890"));
        symbols.add(charRow("@#$%&*-+()"));
        symbols.add(charRow("!?/:;\"_=¿¡"));
        symbols.add(bottom("ABC"));
        if (rows != null) rows = symMode ? symbols : letters;
        layoutKeys();
    }

    private List<Key> bottom(String symLabel) {
        List<Key> r = new ArrayList<>();
        r.add(new Key("shift", null, null, T_SHIFT, 1f));
        r.add(new Key(symLabel, null, null, T_SYM, 1f));
        r.add(new Key("phone", null, null, T_PHONE, 1f));   // v1.5: type from the phone
        r.add(new Key("space", " ", null, T_SPACE, 3f));
        r.add(new Key("del", null, null, T_DEL, 1f));
        r.add(new Key(".com", ".com", null, T_TEXT, 1.5f));
        r.add(new Key("enter", null, null, T_ENTER, 1.5f));
        return r;
    }

    private List<Key> charRow(String chars) {
        List<Key> r = new ArrayList<>();
        for (int i = 0; i < chars.length(); i++) r.add(ch(String.valueOf(chars.charAt(i))));
        return r;
    }

    private static Key ch(String s) {
        String alt;
        switch (s) {
            case "a": alt = "á"; break;
            case "e": alt = "é"; break;
            case "i": alt = "í"; break;
            case "o": alt = "ó"; break;
            case "u": alt = "ú"; break;
            case "n": alt = "ñ"; break;
            case ",": alt = ";"; break;
            case ".": alt = ":"; break;
            case "-": alt = "_"; break;
            case "'": alt = "\""; break;
            case "?": alt = "¿"; break;
            case "!": alt = "¡"; break;
            default:
                alt = Character.isLetter(s.charAt(0)) ? s.toUpperCase() : null;
        }
        return new Key(s, s, alt, T_CHAR, 1f);
    }

    void setEnterLabel(String l) { enterLabel = l; invalidate(); }

    void setSymMode(boolean on) {
        symMode = on;
        rows = on ? symbols : letters;
        layoutKeys();
        clampFocus();
        invalidate();
    }

    void setHint(String s) { hint = s; }

    void setSuggestions(List<String> s) {
        sugg.clear();
        sugg.addAll(s);
        layoutSugg();
        clampFocus();
        invalidate();
    }

    void resetFocus() { fr = 1; fc = 4; stickyX = -1; invalidate(); }

    Key focusedKey() { return fr >= 0 ? rows.get(fr).get(fc) : null; }

    void press() {
        if (fr == -1) { if (fc < sugg.size()) listener.onSuggestion(sugg.get(fc)); }
        else listener.onKey(rows.get(fr).get(fc));
    }

    /** dx/dy in {-1,0,1}. Wraps on every edge. */
    void move(int dx, int dy) {
        boolean hasS = !sugg.isEmpty();
        if (dx != 0) {
            int n = fr == -1 ? sugg.size() : rows.get(fr).size();
            fc = ((fc + dx) % n + n) % n;
            stickyX = centerX(fr, fc);
        } else if (dy != 0) {
            if (stickyX < 0) stickyX = centerX(fr, fc);
            int nr = fr + dy;
            int top = hasS ? -1 : 0;
            if (nr < top) nr = 3;
            else if (nr > 3) nr = top;
            fr = nr;
            fc = nearest(fr, stickyX);
        }
        invalidate();
    }

    private float centerX(int r, int c) {
        if (r == -1) return suggRects.isEmpty() ? w / 2f : suggRects.get(c).centerX();
        return rows.get(r).get(c).cx;
    }

    private int nearest(int r, float x) {
        if (r == -1) {
            int best = 0; float bd = Float.MAX_VALUE;
            for (int i = 0; i < suggRects.size(); i++) {
                float dd = Math.abs(suggRects.get(i).centerX() - x);
                if (dd < bd) { bd = dd; best = i; }
            }
            return best;
        }
        List<Key> row = rows.get(r);
        for (int i = 0; i < row.size(); i++) {
            RectF k = row.get(i).r;
            if (x >= k.left - gap / 2 && x <= k.right + gap / 2) return i;
        }
        int best = 0; float bd = Float.MAX_VALUE;
        for (int i = 0; i < row.size(); i++) {
            float dd = Math.abs(row.get(i).cx - x);
            if (dd < bd) { bd = dd; best = i; }
        }
        return best;
    }

    private void clampFocus() {
        if (fr == -1) {
            if (sugg.isEmpty()) { fr = 0; fc = nearest(0, stickyX >= 0 ? stickyX : centerX(0, 4)); }
            else if (fc >= sugg.size()) fc = sugg.size() - 1;
        } else {
            int n = rows.get(fr).size();
            if (fc >= n) fc = n - 1;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int width = MeasureSpec.getSize(wSpec);
        int height = (int) (padTop + suggH + gap + 4 * keyH + 3 * gap + padBottom);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int nw, int nh, int ow, int oh) {
        w = nw; h = nh;
        layoutKeys();
        layoutSugg();
    }

    private float contentLeft() { return (w - contentW()) / 2f; }
    private float contentW() { return Math.min(w - 2 * padH, maxW); }

    private void layoutKeys() {
        if (w == 0) return;
        float left = contentLeft(), cw = contentW(), unit = cw / 10f;
        layoutSet(letters, left, unit);
        layoutSet(symbols, left, unit);
    }

    private void layoutSet(List<List<Key>> set, float left, float unit) {
        float y = padTop + suggH + gap;
        for (List<Key> row : set) {
            float x = left;
            for (Key k : row) {
                float kw = k.units * unit;
                k.r.set(x + gap / 2, y, x + kw - gap / 2, y + keyH);
                k.cx = k.r.centerX();
                x += kw;
            }
            y += keyH + gap;
        }
    }

    private void layoutSugg() {
        suggRects.clear();
        if (w == 0 || sugg.isEmpty()) return;
        float left = contentLeft(), cw = contentW();
        float sw = cw / Math.max(sugg.size(), 3);
        for (int i = 0; i < sugg.size(); i++) {
            float x = left + i * sw;
            suggRects.add(new RectF(x + gap / 2, padTop, x + sw - gap / 2, padTop + suggH));
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(BG);

        // suggestion bar
        if (sugg.isEmpty()) {
            text.setTextSize(13 * d);
            text.setColor(DIM);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText(hint, contentLeft() + gap, padTop + suggH / 2 + 4.5f * d, text);
            text.setTextAlign(Paint.Align.CENTER);
        } else {
            text.setTextSize(17 * d);
            for (int i = 0; i < sugg.size(); i++) {
                RectF r = suggRects.get(i);
                boolean f = fr == -1 && fc == i;
                fill.setColor(f ? ACCENT : CHIP);
                c.drawRoundRect(r, suggH / 2, suggH / 2, fill);
                text.setColor(f ? FOCUS_TEXT : TEXT);
                c.drawText(sugg.get(i), r.centerX(), r.centerY() + 6 * d, text);
            }
        }

        boolean upper = (shift || caps) && !symMode;
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Key> row = rows.get(ri);
            for (int ci = 0; ci < row.size(); ci++) {
                Key k = row.get(ci);
                boolean f = fr == ri && fc == ci;
                int bg;
                if (f) bg = ACCENT;
                else if (k.type == T_CHAR) bg = KEY;
                else if (k.type == T_SHIFT && (shift || caps)) bg = SHIFT_ON;
                else bg = FN;
                fill.setColor(bg);
                c.drawRoundRect(k.r, radius, radius, fill);
                int fg = f ? FOCUS_TEXT : TEXT;
                if (k.type == T_ENTER && !f) {
                    stroke.setColor(ACCENT);
                    c.drawRoundRect(k.r, radius, radius, stroke);
                }
                switch (k.type) {
                    case T_CHAR:
                        text.setTextSize(22 * d);
                        text.setColor(fg);
                        c.drawText(upper ? k.label.toUpperCase() : k.label, k.cx, k.r.centerY() + 8 * d, text);
                        break;
                    case T_SHIFT:
                        drawShift(c, k.r, fg, caps);
                        break;
                    case T_DEL:
                        drawDel(c, k.r, fg);
                        break;
                    case T_PHONE:
                        drawPhone(c, k.r, f ? FOCUS_TEXT : (phoneLinked ? ACCENT : fg));
                        break;
                    case T_SPACE:
                        stroke.setColor(f ? FOCUS_TEXT : DIM);
                        float sy = k.r.centerY() + 5 * d, sx = 22 * d;
                        icon.reset();
                        icon.moveTo(k.cx - sx, sy - 5 * d);
                        icon.lineTo(k.cx - sx, sy);
                        icon.lineTo(k.cx + sx, sy);
                        icon.lineTo(k.cx + sx, sy - 5 * d);
                        c.drawPath(icon, stroke);
                        break;
                    case T_ENTER:
                        text.setTextSize(16 * d);
                        text.setColor(fg);
                        c.drawText(enterLabel, k.cx, k.r.centerY() + 6 * d, text);
                        break;
                    default:
                        text.setTextSize(16 * d);
                        text.setColor(fg);
                        c.drawText(k.label, k.cx, k.r.centerY() + 6 * d, text);
                }
            }
        }
    }

    private void drawShift(Canvas c, RectF r, int color, boolean locked) {
        float cx = r.centerX(), cy = r.centerY();
        float s = 9 * d;
        icon.reset();
        icon.moveTo(cx, cy - s);
        icon.lineTo(cx + s, cy);
        icon.lineTo(cx + s * 0.45f, cy);
        icon.lineTo(cx + s * 0.45f, cy + s * 0.8f);
        icon.lineTo(cx - s * 0.45f, cy + s * 0.8f);
        icon.lineTo(cx - s * 0.45f, cy);
        icon.lineTo(cx - s, cy);
        icon.close();
        stroke.setColor(color);
        if (locked) {
            fill.setColor(color);
            c.drawPath(icon, fill);
            c.drawLine(cx - s * 0.6f, cy + s * 1.25f, cx + s * 0.6f, cy + s * 1.25f, stroke);
        } else {
            c.drawPath(icon, stroke);
        }
    }

    /** True while a phone with the app is connected: the phone key is drawn in the accent colour. */
    boolean phoneLinked;

    void setPhoneLinked(boolean on) {
        if (on != phoneLinked) { phoneLinked = on; invalidate(); }
    }

    /** Flashes a line in the suggestion bar's hint spot (cleared by the next suggestions or by restoring the hint). */
    void setHintNow(String s) { hint = s; if (sugg.isEmpty()) invalidate(); }

    private void drawPhone(Canvas c, RectF r, int color) {
        float cx = r.centerX(), cy = r.centerY();
        float hw = 7 * d, hh = 12 * d;
        stroke.setColor(color);
        c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, 3 * d, 3 * d, stroke);
        c.drawLine(cx - 2.5f * d, cy + hh - 4 * d, cx + 2.5f * d, cy + hh - 4 * d, stroke);
    }

    private void drawDel(Canvas c, RectF r, int color) {
        float cx = r.centerX(), cy = r.centerY();
        float hw = 12 * d, hh = 8 * d;
        icon.reset();
        icon.moveTo(cx - hw, cy);
        icon.lineTo(cx - hw + 7 * d, cy - hh);
        icon.lineTo(cx + hw, cy - hh);
        icon.lineTo(cx + hw, cy + hh);
        icon.lineTo(cx - hw + 7 * d, cy + hh);
        icon.close();
        stroke.setColor(color);
        c.drawPath(icon, stroke);
        float xc = cx + 3 * d, x = 3.5f * d;
        c.drawLine(xc - x, cy - x, xc + x, cy + x, stroke);
        c.drawLine(xc - x, cy + x, xc + x, cy - x, stroke);
    }
}
