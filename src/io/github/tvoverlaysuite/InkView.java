package io.github.tvoverlaysuite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/** Full-screen, see-through layer that draws the marker ink. Also renders strokes for the Ink model. */
final class InkView extends View implements Ink.Renderer {

    private Ink ink;
    private final Bitmap layer = Bitmap.createBitmap(Ink.W, Ink.H, Bitmap.Config.ARGB_8888);
    private final Canvas layerCanvas = new Canvas(layer);
    private final Paint core = strokePaint();
    private final Paint halo = strokePaint();
    private final Paint eraser = strokePaint();
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private volatile int phones;
    private volatile String url = "";
    private Bitmap qr;
    private String qrFor = "";
    private long badgeUntil;

    InkView(Context c) {
        super(c);
        eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        eraser.setColor(Color.BLACK);
        textPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setInk(Ink ink) { this.ink = ink; }

    private static Paint strokePaint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    void setPhones(int n) { phones = n; postInvalidate(); }

    void setUrl(String u) { url = u; postInvalidate(); }

    void flashBadge() {
        badgeUntil = SystemClock.uptimeMillis() + 3200;
        invalidate();
    }

    // ---- Ink.Renderer (called with the Ink lock held, from any thread) ----

    @Override
    public void onPoints(Ink.Stroke s, int from) {
        if (s.eraser) {
            eraser.setStrokeWidth(s.width);
            for (int i = from; i < s.count; i++) {
                float x = s.pts[i * 2], y = s.pts[i * 2 + 1];
                if (i == 0) layerCanvas.drawPoint(x, y, eraser);
                else layerCanvas.drawLine(s.pts[i * 2 - 2], s.pts[i * 2 - 1], x, y, eraser);
            }
        } else {
            Path p = (Path) s.tag;
            if (p == null) { p = new Path(); s.tag = p; from = 0; }
            extend(p, s, from);
        }
        postInvalidate();
    }

    @Override
    public void onEnd(Ink.Stroke s) {
        if (!s.eraser) {
            Path p = (Path) s.tag;
            if (p == null) { p = new Path(); extend(p, s, 0); }
            finish(p, s);
            drawStroke(layerCanvas, p, s);
            s.tag = null;
        }
        postInvalidate();
    }

    @Override
    public void onRebuild() {
        layer.eraseColor(Color.TRANSPARENT);
        for (Ink.Stroke s : ink.strokes) {
            if (s.eraser) {
                onPoints(s, 0);
            } else {
                Path p = new Path();
                extend(p, s, 0);
                if (s.done) {
                    finish(p, s);
                    drawStroke(layerCanvas, p, s);
                    s.tag = null;
                } else {
                    s.tag = p;
                }
            }
        }
        postInvalidate();
    }

    /** Smooth curve through the points: quadratic segments between midpoints. */
    private static void extend(Path p, Ink.Stroke s, int from) {
        float[] a = s.pts;
        for (int i = from; i < s.count; i++) {
            float x = a[i * 2], y = a[i * 2 + 1];
            if (i == 0) {
                p.moveTo(x, y);
                p.lineTo(x + 0.1f, y);
            } else {
                float px = a[i * 2 - 2], py = a[i * 2 - 1];
                p.quadTo(px, py, (px + x) / 2f, (py + y) / 2f);
            }
        }
    }

    private static void finish(Path p, Ink.Stroke s) {
        if (s.count > 1) p.lineTo(s.pts[s.count * 2 - 2], s.pts[s.count * 2 - 1]);
    }

    /** Marker look: a soft, slightly wider rim under a nearly opaque core. */
    private void drawStroke(Canvas c, Path p, Ink.Stroke s) {
        halo.setColor(s.color);
        halo.setAlpha(64);
        halo.setStrokeWidth(s.width + 3f);
        c.drawPath(p, halo);
        core.setColor(s.color);
        core.setAlpha(240);
        core.setStrokeWidth(s.width);
        c.drawPath(p, core);
    }

    // ---- drawing on screen ----

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || ink == null) return;
        synchronized (ink) {
            c.save();
            c.scale(w / (float) Ink.W, h / (float) Ink.H);
            c.drawBitmap(layer, 0, 0, bitmapPaint);
            for (Ink.Stroke s : ink.strokes) {
                if (!s.done && !s.eraser && s.tag != null) drawStroke(c, (Path) s.tag, s);
            }
            c.restore();
        }
        float k = w / 1920f;
        if (phones == 0 && !url.isEmpty()) drawConnectCard(c, w, h, k);
        long left = badgeUntil - SystemClock.uptimeMillis();
        if (left > 0) {
            drawBadge(c, k, Math.min(1f, left / 500f));
            postInvalidateDelayed(32);
        }
    }

    private void drawConnectCard(Canvas c, int w, int h, float k) {
        if (!url.equals(qrFor)) {
            qr = Qr.bitmap(url);
            qrFor = url;
        }
        float cw = 330 * k, ch = 448 * k, m = 48 * k;
        float l = w - m - cw, t = h - m - ch;
        rect.set(l, t, l + cw, t + ch);
        cardPaint.setColor(0xE6141820);
        c.drawRoundRect(rect, 24 * k, 24 * k, cardPaint);
        textPaint.setColor(0xFFF4F5F7);
        textPaint.setTextSize(26 * k);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText("Scan to draw", l + cw / 2, t + 50 * k, textPaint);
        float qs = 250 * k;
        rect.set(l + (cw - qs) / 2, t + 72 * k, l + (cw + qs) / 2, t + 72 * k + qs);
        cardPaint.setColor(Color.WHITE);
        c.drawRoundRect(rect, 12 * k, 12 * k, cardPaint);
        if (qr != null) {
            RectF in = new RectF(rect.left + 14 * k, rect.top + 14 * k, rect.right - 14 * k, rect.bottom - 14 * k);
            c.drawBitmap(qr, null, in, null);
        }
        // url = http://IP:PORT/#k=CODE : show the address and the code separately
        int hash = url.indexOf("/#k=");
        String addr = (hash > 0 ? url.substring(0, hash) : url).replace("http://", "");
        String code = hash > 0 ? Pairing.pretty(url.substring(hash + 4)) : "";
        textPaint.setColor(0xFFB8BFCC);
        textPaint.setTextSize(21 * k);
        c.drawText(addr, l + cw / 2, t + ch - 82 * k, textPaint);
        if (!code.isEmpty()) {
            textPaint.setColor(0xFFFFD21F);
            textPaint.setTextSize(24 * k);
            c.drawText("Code  " + code, l + cw / 2, t + ch - 48 * k, textPaint);
        }
        textPaint.setTextSize(17 * k);
        textPaint.setColor(0xFF8A93A3);
        c.drawText("Same remote button hides it", l + cw / 2, t + ch - 16 * k, textPaint);
    }

    private void drawBadge(Canvas c, float k, float alpha) {
        String label = "✎  Marker on";
        textPaint.setTextSize(24 * k);
        textPaint.setTextAlign(Paint.Align.LEFT);
        float tw = textPaint.measureText(label);
        float l = 48 * k, t = 40 * k;
        rect.set(l, t, l + tw + 44 * k, t + 52 * k);
        cardPaint.setColor(Color.argb((int) (220 * alpha), 20, 24, 32));
        c.drawRoundRect(rect, 26 * k, 26 * k, cardPaint);
        textPaint.setColor(Color.argb((int) (255 * alpha), 255, 210, 31));
        c.drawText(label, l + 22 * k, t + 35 * k, textPaint);
    }
}
