package io.github.tvoverlaysuite.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;

import java.util.Collections;

import io.github.tvoverlaysuite.PadProto;

/**
 * The trackpad surface. Turns touches into pointer moves, clicks, scrolls and drags, like a laptop trackpad:
 *   one finger slides the pointer (faster swipes go further: pointer acceleration), a tap clicks,
 *   two fingers scroll (with momentum after a flick), a two-finger tap or holding still opens the menu,
 *   tap and then touch again and slide to drag (seek bars, sliders).
 * Every touch sample (the phone reports up to 240 a second) goes out at once; LinkService adds together whatever
 * piles up while the network is busy, so nothing ever queues behind.
 */
final class PadView extends View {

    interface Host {
        void padMove(float dx, float dy);
        void padScroll(float dx, float dy);
        void padEvent(String line);
    }

    private static final int NONE = 0, MOVE = 1, SCROLL = 2, SCROLL_END = 3;
    private static final long TAP_MS = 230, TWO_TAP_MS = 320, HOLD_MS = 650, DRAG_GAP_MS = 300;
    /** Scroll distance on the TV per dp of finger travel. */
    private static final float SCROLL_GAIN = 2.6f;
    private static final float[] SPEEDS = {0.65f, 0.82f, 1f, 1.25f, 1.55f};

    private final Host host;
    private final float d;          // px per dp
    private final float slop;       // px
    private float speed = 1f;

    private int mode = NONE;
    private int activeId = -1;
    private float lastX, lastY, downX, downY, travel;
    private long lastT, downT;
    private float vSmooth;          // finger speed, dp per ms
    private int maxPointers;
    private boolean holdFired, dragArmed, dragging;
    private long lastTapUp;
    private float lastTapX, lastTapY;

    private float cLastX, cLastY, scrollTravel;
    private long twoDownT;
    private boolean twoTapDone;
    private VelocityTracker vt;

    private float flingVx, flingVy;   // TV px per second
    private long flingLastNs;
    private boolean flinging;

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private StaticLayout hint;
    private String hintText = "";
    private final float[] fx = new float[3], fy = new float[3];
    private int fingers;

    PadView(Context c, Host host) {
        super(c);
        this.host = host;
        d = c.getResources().getDisplayMetrics().density;
        slop = 9 * d;
        bg.setColor(0xFF1B202A);
        dot.setColor(0x40FFE135);
        hintPaint.setColor(0xFF6E7787);
        hintPaint.setTextSize(15 * c.getResources().getDisplayMetrics().scaledDensity);
        setHapticFeedbackEnabled(true);
        setHint("Slide to move  ·  tap to click\n\nTwo fingers to scroll\nTwo-finger tap or hold still for the menu\nTap, then touch again and slide to drag");
    }

    void setSpeed(int level) { speed = SPEEDS[Math.max(1, Math.min(5, level)) - 1]; }

    void setHint(String s) {
        if (s.equals(hintText)) return;
        hintText = s;
        hint = null;
        invalidate();
    }

    /** Pointer acceleration: slow, careful moves stay precise; quick flicks cross the TV screen. */
    static float gain(float dpPerMs) {
        float t = Math.max(0f, Math.min(1f, (dpPerMs - 0.04f) / 1.36f));
        return 1.6f + 4.6f * t * t * (3 - 2 * t);
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: down(e); break;
            case MotionEvent.ACTION_POINTER_DOWN: pointerDown(e); break;
            case MotionEvent.ACTION_MOVE: move(e); break;
            case MotionEvent.ACTION_POINTER_UP: pointerUp(e); break;
            case MotionEvent.ACTION_UP: up(e); break;
            case MotionEvent.ACTION_CANCEL: cancel(); break;
            default:
        }
        track(e);
        return true;
    }

    private void down(MotionEvent e) {
        flinging = false;
        mode = MOVE;
        activeId = e.getPointerId(0);
        lastX = downX = e.getX();
        lastY = downY = e.getY();
        lastT = downT = e.getEventTime();
        travel = 0;
        vSmooth = 0;
        maxPointers = 1;
        holdFired = false;
        dragArmed = downT - lastTapUp < DRAG_GAP_MS && Math.hypot(downX - lastTapX, downY - lastTapY) < 60 * d;
        removeCallbacks(holdRun);
        postDelayed(holdRun, HOLD_MS);
    }

    private void pointerDown(MotionEvent e) {
        maxPointers = Math.max(maxPointers, e.getPointerCount());
        removeCallbacks(holdRun);
        if (mode == MOVE && e.getPointerCount() == 2) {
            if (dragging) { dragging = false; host.padEvent(PadProto.drag(false)); }
            mode = SCROLL;
            cLastX = (e.getX(0) + e.getX(1)) / 2;
            cLastY = (e.getY(0) + e.getY(1)) / 2;
            scrollTravel = 0;
            twoDownT = e.getEventTime();
            twoTapDone = false;
        }
    }

    private void move(MotionEvent e) {
        if (mode == MOVE) {
            int i = e.findPointerIndex(activeId);
            if (i < 0) return;
            float sx = 0, sy = 0;
            int n = e.getHistorySize();
            for (int h = 0; h <= n; h++) {
                float x = h < n ? e.getHistoricalX(i, h) : e.getX(i);
                float y = h < n ? e.getHistoricalY(i, h) : e.getY(i);
                long t = h < n ? e.getHistoricalEventTime(h) : e.getEventTime();
                float dx = x - lastX, dy = y - lastY;
                float dt = Math.max(1, t - lastT);
                float distDp = (float) Math.hypot(dx, dy) / d;
                float v = distDp / dt;
                vSmooth = vSmooth == 0 ? v : vSmooth * 0.5f + v * 0.5f;
                float g = gain(vSmooth) * speed / d;
                sx += dx * g;
                sy += dy * g;
                travel += distDp * d;
                lastX = x; lastY = y; lastT = t;
            }
            if (travel > slop) {
                removeCallbacks(holdRun);
                if (dragArmed && !dragging && !holdFired) {
                    dragging = true;
                    dragArmed = false;
                    host.padEvent(PadProto.drag(true));
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
            }
            if (sx != 0 || sy != 0) host.padMove(sx, sy);
        } else if (mode == SCROLL && e.getPointerCount() >= 2) {
            float sx = 0, sy = 0;
            int n = e.getHistorySize();
            for (int h = 0; h <= n; h++) {
                float x = h < n ? (e.getHistoricalX(0, h) + e.getHistoricalX(1, h)) / 2 : (e.getX(0) + e.getX(1)) / 2;
                float y = h < n ? (e.getHistoricalY(0, h) + e.getHistoricalY(1, h)) / 2 : (e.getY(0) + e.getY(1)) / 2;
                float dx = x - cLastX, dy = y - cLastY;
                scrollTravel += (float) Math.hypot(dx, dy);
                // content follows the fingers, as on a phone or a laptop trackpad
                sx -= dx / d * SCROLL_GAIN;
                sy -= dy / d * SCROLL_GAIN;
                cLastX = x; cLastY = y;
            }
            if (sx != 0 || sy != 0) host.padScroll(sx, sy);
        }
    }

    private void pointerUp(MotionEvent e) {
        if (mode == SCROLL && e.getPointerCount() == 2) {
            if (scrollTravel < slop && e.getEventTime() - twoDownT < TWO_TAP_MS && !twoTapDone) {
                twoTapDone = true;
                host.padEvent(PadProto.menu());
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } else {
                startFling(e);
            }
            mode = SCROLL_END;   // the finger left behind does nothing until it lifts too (no jump)
        }
    }

    private void up(MotionEvent e) {
        removeCallbacks(holdRun);
        long t = e.getEventTime();
        if (mode == MOVE) {
            if (dragging) {
                dragging = false;
                host.padEvent(PadProto.drag(false));
            } else if (!holdFired && maxPointers == 1 && travel < slop && t - downT < TAP_MS) {
                host.padEvent(PadProto.click());
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                lastTapUp = t;
                lastTapX = e.getX();
                lastTapY = e.getY();
            }
        } else if (mode == SCROLL) {
            startFling(e);
        }
        mode = NONE;
        dragArmed = false;
        if (vt != null) { vt.recycle(); vt = null; }
    }

    private void cancel() {
        removeCallbacks(holdRun);
        if (dragging) { dragging = false; host.padEvent(PadProto.drag(false)); }
        mode = NONE;
        if (vt != null) { vt.recycle(); vt = null; }
    }

    private final Runnable holdRun = this::onHold;

    /** Held still: the menu, like a long press on the remote's OK. */
    private void onHold() {
        if (mode == MOVE && travel < slop && !dragging) {
            holdFired = true;
            dragArmed = false;
            host.padEvent(PadProto.menu());
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    // ---------------------------------------------------------------- momentum scrolling

    private void startFling(MotionEvent e) {
        if (vt == null || scrollTravel < slop) return;
        vt.computeCurrentVelocity(1000);
        float vx = 0, vy = 0;
        int n = Math.min(2, e.getPointerCount());
        for (int i = 0; i < n; i++) { vx += vt.getXVelocity(e.getPointerId(i)); vy += vt.getYVelocity(e.getPointerId(i)); }
        vx /= n; vy /= n;
        flingVx = -vx / d * SCROLL_GAIN;
        flingVy = -vy / d * SCROLL_GAIN;
        if (Math.hypot(flingVx, flingVy) < 400) return;
        flinging = true;
        flingLastNs = System.nanoTime();
        postOnAnimation(flingStep);
    }

    /** A method reference, not an anonymous class (d8 8.2.2 bug). */
    private final Runnable flingStep = this::flingTick;

    private void flingTick() {
        if (!flinging) return;
        long now = System.nanoTime();
        float dt = Math.min(0.05f, (now - flingLastNs) / 1e9f);
        flingLastNs = now;
        host.padScroll(flingVx * dt, flingVy * dt);
        float k = (float) Math.pow(0.93, dt * 60);   // friction, the same at 60 or 120 frames a second
        flingVx *= k;
        flingVy *= k;
        if (Math.hypot(flingVx, flingVy) < 60) { flinging = false; return; }
        postOnAnimation(flingStep);
    }

    void stopFling() { flinging = false; }

    /** Swipes that start at the screen's side edges would be taken as Android's Back gesture: not on the pad. */
    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (changed && Build.VERSION.SDK_INT >= 29) {
            setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, b - t)));
        }
    }

    // ---------------------------------------------------------------- drawing

    private void track(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) fingers = 0;
        else {
            fingers = Math.min(3, e.getPointerCount());
            int skip = a == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
            int j = 0;
            for (int i = 0; i < e.getPointerCount() && j < 3; i++) {
                if (i == skip) continue;
                fx[j] = e.getX(i);
                fy[j] = e.getY(i);
                j++;
            }
            fingers = j;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        box.set(0, 0, getWidth(), getHeight());
        c.drawRoundRect(box, 26 * d, 26 * d, bg);
        if (fingers == 0) {
            if (hint == null || hint.getWidth() != getWidth() - Math.round(48 * d)) {
                int w = Math.max(1, getWidth() - Math.round(48 * d));
                hint = new StaticLayout(hintText, hintPaint, w, Layout.Alignment.ALIGN_CENTER, 1.15f, 0, false);
            }
            c.save();
            c.translate(24 * d, (getHeight() - hint.getHeight()) / 2f);
            hint.draw(c);
            c.restore();
        } else {
            for (int i = 0; i < fingers; i++) c.drawCircle(fx[i], fy[i], 34 * d, dot);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        flinging = false;
        removeCallbacks(holdRun);
        if (vt != null) { vt.recycle(); vt = null; }
    }
}
