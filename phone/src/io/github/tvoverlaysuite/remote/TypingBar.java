package io.github.tvoverlaysuite.remote;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;

import io.github.tvoverlaysuite.TypeProto;

/**
 * The typing bar as a floating window at the top of the phone screen, over whatever app is open (needs "Display over
 * other apps"). It sits at the top so the phone keyboard never covers it; taps outside it go to the app underneath.
 */
final class TypingBar implements TypePanel.Host {
    private final Context ctx;
    private final LinkService link;
    private final WindowManager wm;
    private Root root;
    private TypePanel panel;
    private boolean shown;

    TypingBar(Context c, LinkService link) {
        ctx = c;
        this.link = link;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean allowed(Context c) { return Settings.canDrawOverlays(c); }

    boolean isShown() { return shown; }

    int fieldId() { return panel == null ? -1 : panel.fieldId(); }

    /** Returns false when the window could not be shown (no permission). */
    boolean show(TypeProto.Msg m) {
        if (!allowed(ctx)) return false;
        if (!shown) {
            float d = ctx.getResources().getDisplayMetrics().density;
            root = new Root(ctx, this);
            panel = new TypePanel(ctx, this);
            FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            int m8 = Math.round(10 * d);
            plp.setMargins(m8, m8, m8, m8);
            root.addView(panel, plp);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            lp.y = statusBarHeight();
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
            lp.setTitle("Type on TV");
            lp.windowAnimations = android.R.style.Animation_Toast;
            try {
                wm.addView(root, lp);
            } catch (RuntimeException e) {
                root = null;
                panel = null;
                return false;
            }
            shown = true;
        }
        panel.open(m);
        return true;
    }

    void state(TypeProto.Msg m) { if (shown) panel.state(m); }

    /** The TV box closed: close the bar if it was typing into that box. */
    void fieldClosed(int field) { if (shown && panel.fieldId() == field) hide(); }

    void hide() {
        if (!shown) return;
        shown = false;
        try { panel.hideIme(); } catch (RuntimeException ignored) { }
        try { wm.removeView(root); } catch (RuntimeException ignored) { }
        root = null;
        panel = null;
    }

    @Override public void sendLine(String line) { link.sendLine(line); }

    @Override public void closePanel() { hide(); }

    private int statusBarHeight() {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    /** Catches Back while the bar has focus. */
    static final class Root extends FrameLayout {
        private final TypingBar bar;
        Root(Context c, TypingBar b) { super(c); bar = b; }

        @Override
        public boolean dispatchKeyEvent(KeyEvent e) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) bar.hide();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
    }
}
