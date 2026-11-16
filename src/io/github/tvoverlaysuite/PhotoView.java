package io.github.tvoverlaysuite;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Picture-in-picture photo sent from the phone: rounded corners, thin outline, short clock-driven fade-in. */
final class PhotoView extends View {

    private static final long FADE_MS = 220;

    private final Bitmap bitmap;
    private final Paint img = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix m = new Matrix();
    private final RectF rect = new RectF();
    private final float radius;
    private long start = -1;
    private final Runnable fade = this::step;

    PhotoView(Context c, Bitmap b, float k) {
        super(c);
        bitmap = b;
        radius = 18 * k;
        img.setShader(new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, 2 * k));
        edge.setColor(0x59FFFFFF);
        setAlpha(0f);
    }

    Bitmap bitmap() { return bitmap; }

    /** Photo size on screen for a size index (0 small, 1 medium, 2 large), keeping its proportions. */
    static int[] boxFor(Bitmap b, int size, int screenW, int screenH) {
        float frac = size == 0 ? 0.28f : size == 2 ? 0.55f : 0.40f;
        float w = screenW * frac, h = w * b.getHeight() / (float) b.getWidth();
        float maxH = screenH * (size == 2 ? 0.80f : size == 0 ? 0.40f : 0.58f);
        if (h > maxH) { h = maxH; w = h * b.getWidth() / (float) b.getHeight(); }
        return new int[]{Math.round(w), Math.round(h)};
    }

    void fadeIn() {
        start = SystemClock.uptimeMillis();
        postOnAnimation(fade);
    }

    private void step() {
        float p = (SystemClock.uptimeMillis() - start) / (float) FADE_MS;
        if (p >= 1f) { setAlpha(1f); setScaleX(1f); setScaleY(1f); return; }
        setAlpha(p);
        float s = 0.94f + 0.06f * (1f - (1f - p) * (1f - p));
        setScaleX(s);
        setScaleY(s);
        postOnAnimation(fade);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || bitmap.isRecycled()) return;
        m.setScale(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        img.getShader().setLocalMatrix(m);
        rect.set(0, 0, w, h);
        c.drawRoundRect(rect, radius, radius, img);
        float i = edge.getStrokeWidth() / 2f;
        rect.inset(i, i);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
