package io.github.tvoverlaysuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * One pop-up (text card, emoji sticker or small status badge) in its own small window.
 * The animation is driven from the clock (SystemClock), not ValueAnimator, so the TV's
 * developer-options animator scale (often lowered to 0.5 on slow TVs) does not change the timings.
 */
final class PopupView extends View {

    static final int TEXT = 0, STICKER = 1, BADGE = 2;
    private static final long IN_MS = 220, OUT_MS = 300;

    final int kind, pos;
    final int winW, winH;
    final float margin;
    private final long holdMs;
    private final String text;
    private final float k;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private StaticLayout layout;
    private float contentW, contentH, padX, padY;
    private long start = -1;
    private Runnable onDone;
    private boolean finished;

    PopupView(Context c, float k, int kind, String text, int pos, long holdMs) {
        super(c);
        this.k = k;
        this.kind = kind;
        this.text = text;
        this.pos = pos;
        this.holdMs = holdMs;
        setAlpha(0f);
        if (kind == STICKER) {
            paint.setTextSize(200 * k);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setShadowLayer(18 * k, 0, 6 * k, 0x99000000);
            Paint.FontMetrics fm = paint.getFontMetrics();
            contentW = Math.max(paint.measureText(text), 200 * k);
            contentH = fm.descent - fm.ascent;
        } else if (kind == BADGE) {
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(24 * k);
            padX = 22 * k;
            contentW = paint.measureText(text) + 2 * padX;
            contentH = 52 * k;
        } else {
            paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            paint.setTextSize(48 * k);
            paint.setColor(0xFFFFFFFF);
            padX = 46 * k;
            padY = 30 * k;
            float maxW = 1400 * k;
            float want = 0;
            for (String line : text.split("\n")) want = Math.max(want, paint.measureText(line));
            int w = (int) Math.ceil(Math.min(Math.max(want, 120 * k), maxW));
            layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, w)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setLineSpacing(0, 1.08f)
                    .setIncludePad(false)
                    .setMaxLines(3)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build();
            float lw = 0;
            for (int i = 0; i < layout.getLineCount(); i++) lw = Math.max(lw, layout.getLineWidth(i));
            contentW = Math.min(w, lw + 2) + 2 * padX;
            contentH = layout.getHeight() + 2 * padY;
            card.setColor(0xE6141820);
        }
        // room for the overshoot and the shadow
        margin = 0.06f * Math.max(contentW, contentH) + 24 * k;
        winW = (int) Math.ceil(contentW + 2 * margin);
        winH = (int) Math.ceil(contentH + 2 * margin);
    }

    /** Starts the in → hold → out sequence. onDone runs on the main thread when the pop-up has faded out. */
    void begin(Runnable done) {
        onDone = done;
        start = SystemClock.uptimeMillis();
        postOnAnimation(tick);
    }

    /** Ends early with a quick fade (used when the service shuts down). */
    void finishNow() {
        finished = true;
        removeCallbacks(tick);
    }

    private final Runnable tick = this::step;

    private void step() {
        if (finished) return;
        long t = SystemClock.uptimeMillis() - start;
        float s, a;
        if (t < IN_MS) {
            float p = t / (float) IN_MS;
            s = 0.85f + 0.15f * easeOutBack(p);
            a = Math.min(1f, p * 1.5f);
            postOnAnimation(tick);
        } else if (t < IN_MS + holdMs) {
            s = 1f;
            a = 1f;
            // nothing moves while holding: sleep until the fade instead of running every frame
            postDelayed(tick, IN_MS + holdMs - t);
        } else if (t < IN_MS + holdMs + OUT_MS) {
            float q = (t - IN_MS - holdMs) / (float) OUT_MS;
            s = 1f - 0.04f * q;
            a = 1f - q;
            postOnAnimation(tick);
        } else {
            finished = true;
            setAlpha(0f);
            if (onDone != null) onDone.run();
            return;
        }
        setScaleX(s);
        setScaleY(s);
        setAlpha(a);
    }

    private static float easeOutBack(float p) {
        final float c1 = 1.1f, c3 = c1 + 1f;
        float q = p - 1f;
        return 1f + c3 * q * q * q + c1 * q * q;
    }

    @Override
    protected void onDraw(Canvas c) {
        float l = (getWidth() - contentW) / 2f, t = (getHeight() - contentH) / 2f;
        if (kind == STICKER) {
            Paint.FontMetrics fm = paint.getFontMetrics();
            c.drawText(text, getWidth() / 2f, t - fm.ascent, paint);
        } else if (kind == BADGE) {
            rect.set(l, t, l + contentW, t + contentH);
            card.setColor(0xDC141820);
            c.drawRoundRect(rect, contentH / 2, contentH / 2, card);
            paint.setColor(0xFFFFD21F);
            c.drawText(text, l + padX, t + 35 * k, paint);
        } else {
            rect.set(l, t, l + contentW, t + contentH);
            c.drawRoundRect(rect, 28 * k, 28 * k, card);
            c.save();
            c.translate(l + padX + (contentW - 2 * padX - layout.getWidth()) / 2f, t + padY);
            layout.draw(c);
            c.restore();
        }
    }
}
