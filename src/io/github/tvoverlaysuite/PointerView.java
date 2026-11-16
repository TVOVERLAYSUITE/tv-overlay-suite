package io.github.tvoverlaysuite;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/** Red laser dot with a short fading trail and an optional "ping" ring. Lives in the top-most overlay window. */
final class PointerView extends View {

    private static final int TRAIL = 10;
    private static final long TRAIL_MS = 200, PING_MS = 650;

    private final float k;
    private final float[] tx = new float[TRAIL], ty = new float[TRAIL];
    private final long[] tt = new long[TRAIL];
    private int head = -1;            // newest trail slot
    private float x = -1, y = -1;     // screen pixels
    private long pingAt = -1;
    private float pingX, pingY;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float rCore, rGlow;

    PointerView(Context c, float k) {
        super(c);
        this.k = k;
        rCore = 9 * k;     // 18 px core
        rGlow = 22 * k;    // ~44 px soft glow
        glow.setShader(new RadialGradient(0, 0, rGlow,
                new int[]{0xB0FF2A2A, 0x55FF1E1E, 0x00FF1010}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        core.setColor(0xFFFF2B2B);
        hot.setColor(0xFFFFD6D6);
        trail.setColor(0xFFFF2B2B);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0xFFFF3B3B);
    }

    /** Board coordinates (1920 x 1080). Main thread. */
    void moveTo(float bx, float by) {
        float nx = bx * k, ny = by * k;
        if (nx == x && ny == y) return;
        long now = SystemClock.uptimeMillis();
        if (x >= 0) {
            head = (head + 1) % TRAIL;
            tx[head] = x; ty[head] = y; tt[head] = now;
        }
        invalidateAround(x, y);
        x = nx; y = ny;
        invalidateAround(x, y);
    }

    void ping(float bx, float by) {
        moveTo(bx, by);
        pingX = bx * k;
        pingY = by * k;
        pingAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** Dirty-region invalidation (honoured in software rendering; with GPU rendering Android redraws the view, which is a few circles). */
    @SuppressWarnings("deprecation")
    private void invalidateAround(float cx, float cy) {
        if (cx < 0) return;
        int r = (int) (rGlow + 4);
        invalidate((int) cx - r, (int) cy - r, (int) cx + r, (int) cy + r);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        boolean again = false;
        // trail: oldest first
        for (int n = TRAIL - 1; n >= 0 && head >= 0; n--) {
            int i = (head - n + TRAIL) % TRAIL;
            long age = now - tt[i];
            if (tt[i] == 0 || age >= TRAIL_MS) continue;
            float f = 1f - age / (float) TRAIL_MS;
            trail.setAlpha((int) (150 * f));
            c.drawCircle(tx[i], ty[i], rCore * (0.45f + 0.5f * f), trail);
            again = true;
        }
        if (pingAt >= 0) {
            long age = now - pingAt;
            if (age < PING_MS) {
                float f = age / (float) PING_MS;
                ring.setStrokeWidth((6 - 3 * f) * k);
                ring.setAlpha((int) (230 * (1 - f)));
                c.drawCircle(pingX, pingY, (20 + 120 * (1 - (1 - f) * (1 - f))) * k, ring);
                again = true;
            } else {
                pingAt = -1;
            }
        }
        if (x >= 0) {
            c.save();
            c.translate(x, y);
            c.drawCircle(0, 0, rGlow, glow);
            c.drawCircle(0, 0, rCore, core);
            c.drawCircle(0, 0, rCore * 0.42f, hot);
            c.restore();
        }
        if (again) postInvalidateOnAnimation();
    }
}
