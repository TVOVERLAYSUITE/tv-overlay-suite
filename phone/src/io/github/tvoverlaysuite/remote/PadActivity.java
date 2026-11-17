package io.github.tvoverlaysuite.remote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.tvoverlaysuite.PadProto;

/**
 * v1.6: the phone as a trackpad for the TV app in front (one that supports it, such as a TV web browser).
 * Opens from the app, the notification, or by itself when such an app comes to the front on the TV.
 */
public class PadActivity extends Activity implements PadView.Host {
    static final String EXTRA_AUTO = "auto";

    private static final int BG = 0xFF0F1218, FIELD = 0xFF252B38, TEXT = 0xFFE9ECF2, MUTED = 0xFF8A93A3,
            ACCENT = 0xFFFFE135, GOOD = 0xFF45D483, WARN = 0xFFFF8A5F;
    private static final long AUTO_CLOSE_MS = 3000;

    private Store store;
    private float d;
    private PadView pad;
    private TextView dot, title, sub;
    private boolean auto;
    private WifiManager.WifiLock wifi;
    private final Runnable autoClose = this::finish;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        d = getResources().getDisplayMetrics().density;
        auto = getIntent().getBooleanExtra(EXTRA_AUTO, false);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        build();
        try {
            WifiManager wm = getApplicationContext().getSystemService(WifiManager.class);
            int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifi = wm.createWifiLock(mode, "tvos:trackpad");
            wifi.setReferenceCounted(false);
        } catch (RuntimeException e) {
            wifi = null;
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        // opened again by hand: it stays open even if the app on the TV goes away
        if (!i.getBooleanExtra(EXTRA_AUTO, false)) auto = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.setSpeed(store.padSpeed());
        // Wi-Fi power saving can add 50-200 ms per packet; low-latency mode turns it off while this screen is open.
        try { if (wifi != null) wifi.acquire(); } catch (RuntimeException ignored) { }
        LinkService s = LinkService.instance;
        if (s == null) {
            LinkService.start(this);
            front(false, "");
            pad.postDelayed(this::attach, 800);
        } else {
            attach();
        }
        LinkService.onStatus = this::refresh;
    }

    private void attach() {
        LinkService s = LinkService.instance;
        if (s == null || isFinishing()) return;
        s.padActivity = this;
        front(s.padOn, s.padName);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pad.removeCallbacks(autoClose);
        pad.stopFling();
        LinkService s = LinkService.instance;
        if (s != null && s.padActivity == this) s.padActivity = null;
        if (LinkService.onStatus != null) LinkService.onStatus = null;
        try { if (wifi != null) wifi.release(); } catch (RuntimeException ignored) { }
    }

    private void refresh() {
        LinkService s = LinkService.instance;
        front(s != null && s.padOn, s == null ? "" : s.padName);
    }

    /** The TV says whether a trackpad app is in front. Main thread. */
    void front(boolean on, String name) {
        LinkService s = LinkService.instance;
        boolean linked = s != null && s.connected();
        String app = name == null || name.isEmpty() ? store.padApp() : name;
        if (!linked) {
            dot.setTextColor(WARN);
            title.setText("Not connected to the TV");
            sub.setText("Is the TV on, and is this phone on the same Wi-Fi?");
        } else if (on) {
            dot.setTextColor(GOOD);
            title.setText(app.isEmpty() ? "Trackpad" : app);
            sub.setText("The trackpad is driving the TV");
        } else {
            dot.setTextColor(ACCENT);
            title.setText(app.isEmpty() ? "Open a trackpad app on the TV" : "Open " + app + " on the TV");
            sub.setText("The trackpad works in TV apps that support it");
        }
        pad.setAlpha(linked && on ? 1f : 0.55f);
        pad.removeCallbacks(autoClose);
        if (auto && linked && !on) pad.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    // ---------------------------------------------------------------- PadView.Host

    @Override public void padMove(float dx, float dy) { LinkService s = LinkService.instance; if (s != null) s.padMove(dx, dy); }

    @Override public void padScroll(float dx, float dy) { LinkService s = LinkService.instance; if (s != null) s.padScroll(dx, dy); }

    @Override public void padEvent(String line) { LinkService s = LinkService.instance; if (s != null) s.padEvent(line); }

    // ---------------------------------------------------------------- layout

    private void build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);
        col.setPadding(px(12), px(10), px(12), px(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(px(6), 0, 0, px(8));
        dot = text("●", 16, MUTED, true);
        dot.setPadding(0, 0, px(10), 0);
        top.addView(dot);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        title = text("", 18, TEXT, true);
        title.setSingleLine(true);
        names.addView(title);
        sub = text("", 13, MUTED, false);
        sub.setSingleLine(true);
        names.addView(sub);
        top.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = text("✕", 22, MUTED, false);
        close.setPadding(px(16), px(6), px(8), px(6));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        col.addView(top);

        pad = new PadView(this, this);
        col.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout keys = new LinearLayout(this);
        keys.setPadding(0, px(10), 0, 0);
        keys.addView(key("◀", "Back", v -> padEvent(PadProto.key("back"))));
        keys.addView(key("⌂", "Home", v -> padEvent(PadProto.key("home"))));
        keys.addView(key("☰", "Menu", v -> padEvent(PadProto.key("menu"))));
        keys.addView(key("⏯", "Play", v -> padEvent(PadProto.key("pp"))));
        keys.addView(key("⌨", "Type", v -> startActivity(new Intent(this, TypeActivity.class)
                .putExtra(TypeActivity.EXTRA_WANT, true))));
        col.addView(keys);
        setContentView(col);
    }

    private TextView key(String icon, String label, View.OnClickListener l) {
        TextView t = text(icon + "\n" + label, 13, TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(0, 1.05f);
        t.setPadding(0, px(9), 0, px(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(FIELD);
        g.setCornerRadius(14 * d);
        t.setBackground(g);
        t.setClickable(true);
        t.setHapticFeedbackEnabled(true);
        t.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); l.onClick(v); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(px(3), 0, px(3), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private int px(int dp) { return Math.round(dp * d); }
}
