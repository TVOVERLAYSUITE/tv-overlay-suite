package io.github.tvoverlaysuite;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Setup screen. Three states:
 *  1. first run: plain-words disclosure of what the accessibility service does (Agree / Not now);
 *  2. service off: how to turn on USB debugging + "Enable automatically" (local adb, see Enabler);
 *  3. service on: status, remote button, phones, dimmer, pairing code + QR for the phone.
 */
public class SetupActivity extends Activity {

    static final String REPO = "github.com/TVOVERLAYSUITE/tv-overlay-suite";

    private SharedPreferences prefs;
    private LinearLayout panelAgree, panelOff, panelOn;
    private TextView status, keyLine, phones, dimLine, codeLine, hint, progress;
    private TextView btnAgree, btnNotNow, btnEnable, btnToggle, btnLearn, btnClear, btnDimOff, btnNewCode;
    private TextView cardTitle, urlText, cardCode;
    private ImageView qrView;
    private boolean learning;
    private volatile boolean busy;
    private boolean autoTried;
    private int lastState = -1;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable poll = this::pollTick;   // method ref, not an anonymous class (d8 8.2.2 bug)
    private MarkerService attachedTo;
    private String lastQr = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("marker", MODE_PRIVATE);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(0xFF12151C);
        root.setPadding(px(64, d), px(44, d), px(64, d), px(36, d));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(left);
        root.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.BOTTOM);
        titleRow.addView(text("TV Overlay Suite", 36, 0xFFFFFFFF, true));
        TextView ver = text("  v" + versionName(), 16, 0xFF8A93A3, false);
        ver.setPadding(px(10, d), 0, 0, px(8, d));
        titleRow.addView(ver);
        left.addView(titleRow);
        TextView sub = text("Draw, point, dim the screen and send messages or photos to the TV from your phone.", 16, 0xFF9AA3B2, false);
        sub.setPadding(0, px(2, d), px(24, d), px(16, d));
        left.addView(sub);

        // ---- 1. disclosure ----
        panelAgree = vbox();
        panelAgree.addView(text("Before you switch it on", 20, 0xFFFFFFFF, true));
        TextView disc = text("This app uses Android's accessibility service for two things only: to catch the remote "
                + "button you choose, and to draw its layers (marker, dimmer, messages, pointer, photo) over other apps.\n\n"
                + "It does not read what is on the screen, does not collect or send any data, and does not use the internet. "
                + "It only talks to phones on your home network that you pair with the code shown on this TV.\n\n"
                + "Photos sent from a phone stay in the TV's memory only while they are shown, and are never saved or uploaded.",
                16, 0xFFD5DAE3, false);
        disc.setPadding(0, px(8, d), px(24, d), px(14, d));
        panelAgree.addView(disc);
        LinearLayout ra = new LinearLayout(this);
        btnAgree = button("Agree", d);
        btnNotNow = button("Not now", d);
        ra.addView(btnAgree);
        ra.addView(btnNotNow);
        panelAgree.addView(ra);
        left.addView(panelAgree);

        // ---- 2. service off ----
        panelOff = vbox();
        TextView offStatus = text("●  Overlay service is OFF", 19, 0xFFFF6B6B, true);
        panelOff.addView(offStatus);
        TextView step1 = text("Step 1 — turn on USB debugging (one time):\n"
                + "Settings → System → About → select “Android TV OS build” 7 times (“You are now a developer”). "
                + "Then Settings → System → Developer options → USB debugging → On.", 16, 0xFFD5DAE3, false);
        step1.setPadding(0, px(10, d), px(24, d), px(6, d));
        panelOff.addView(step1);
        TextView step2 = text("Step 2 — press the button. The TV asks “Allow USB debugging?”: tick “Always allow” and choose Allow.",
                16, 0xFFD5DAE3, false);
        step2.setPadding(0, px(6, d), px(24, d), px(12, d));
        panelOff.addView(step2);
        LinearLayout ro = new LinearLayout(this);
        btnEnable = button("Enable automatically", d);
        ro.addView(btnEnable);
        panelOff.addView(ro);
        progress = text("", 16, 0xFFFFD21F, false);
        progress.setPadding(0, px(12, d), px(24, d), 0);
        panelOff.addView(progress);
        TextView manual = text("No luck? The help page (QR on the right) shows how to switch it on from a computer or a phone with adb.",
                14, 0xFF8A93A3, false);
        manual.setPadding(0, px(12, d), px(24, d), 0);
        panelOff.addView(manual);
        left.addView(panelOff);

        // ---- 3. service on ----
        panelOn = vbox();
        status = text("●  Overlay service is ON", 19, 0xFF45D483, true);
        keyLine = text("", 17, 0xFFD5DAE3, false);
        phones = text("", 17, 0xFFD5DAE3, false);
        dimLine = text("", 17, 0xFFD5DAE3, false);
        codeLine = text("", 17, 0xFFD5DAE3, false);
        panelOn.addView(status);
        panelOn.addView(keyLine);
        panelOn.addView(phones);
        panelOn.addView(dimLine);
        panelOn.addView(codeLine);
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, px(18, d), 0, px(12, d));
        btnToggle = button("Show overlay", d);
        btnLearn = button("Set a different button", d);
        btnClear = button("Clear drawing", d);
        row.addView(btnToggle);
        row.addView(btnLearn);
        row.addView(btnClear);
        panelOn.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        btnDimOff = button("Dimmer off", d);
        btnNewCode = button("New pairing code", d);
        row2.addView(btnDimOff);
        row2.addView(btnNewCode);
        panelOn.addView(row2);
        hint = text("", 14, 0xFF8A93A3, false);
        hint.setPadding(0, px(14, d), px(24, d), 0);
        panelOn.addView(hint);
        left.addView(panelOn);

        // ---- right card: phone QR (service on) or help QR (otherwise) ----
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFF1C212B);
        card.setCornerRadius(20 * d);
        right.setBackground(card);
        right.setPadding(px(26, d), px(22, d), px(26, d), px(22, d));
        cardTitle = text("", 18, 0xFFFFFFFF, true);
        right.addView(cardTitle);
        qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(px(10, d), px(10, d), px(10, d), px(10, d));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(px(220, d), px(220, d));
        qlp.topMargin = px(14, d);
        qlp.bottomMargin = px(12, d);
        right.addView(qrView, qlp);
        urlText = text("", 15, 0xFFB8BFCC, false);
        urlText.setGravity(Gravity.CENTER);
        right.addView(urlText);
        cardCode = text("", 20, 0xFFFFD21F, true);
        right.addView(cardCode);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(px(290, d), -2);
        rlp.gravity = Gravity.CENTER_VERTICAL;
        root.addView(right, rlp);

        setContentView(root);

        btnAgree.setOnClickListener(v -> {
            prefs.edit().putBoolean("agreed", true).apply();
            refresh();
        });
        btnNotNow.setOnClickListener(v -> finish());
        btnEnable.setOnClickListener(v -> startEnable(true));
        btnToggle.setOnClickListener(v -> {
            MarkerService s = MarkerService.instance;
            if (s != null) s.setShown(!s.isShown());
        });
        btnLearn.setOnClickListener(v -> {
            MarkerService s = MarkerService.instance;
            if (s == null) return;
            learning = true;
            s.startLearning(() -> { learning = false; refresh(); });
            refresh();
        });
        btnClear.setOnClickListener(v -> {
            MarkerService s = MarkerService.instance;
            if (s != null) s.clearDrawing();
        });
        btnDimOff.setOnClickListener(v -> {
            MarkerService s = MarkerService.instance;
            if (s != null) s.setDim(0);
        });
        btnNewCode.setOnClickListener(v -> {
            MarkerService.newPairingCode(this);
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        autoTried = false;
        attachService();
        refresh();
        ui.postDelayed(poll, 1000);
    }

    private void pollTick() {
        attachService();
        refresh();
        ui.postDelayed(poll, 1000);
    }

    /** The service can start (or restart) while this screen is open: re-attach the change listener. */
    private void attachService() {
        MarkerService s = MarkerService.instance;
        if (s != attachedTo) {
            attachedTo = s;
            if (s != null) s.setOnChange(this::refresh);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
        attachedTo = null;
        MarkerService s = MarkerService.instance;
        if (s != null) {
            s.setOnChange(null);
            if (learning) { s.cancelLearning(); learning = false; }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && learning) {
            MarkerService s = MarkerService.instance;
            if (s != null) s.cancelLearning();
            learning = false;
            refresh();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** withAdb = the button; otherwise a silent attempt that only works if the permission was granted before. */
    private void startEnable(boolean withAdb) {
        if (busy) return;
        busy = true;
        btnEnable.setEnabled(false);
        btnEnable.setAlpha(0.45f);
        progress.setText(withAdb ? "Starting…" : "Switching the service back on…");
        Enabler.Listener l = m -> ui.post(() -> progress.setText(m));
        new Thread(() -> {
            String msg;
            if (withAdb) {
                msg = Enabler.enableWithAdb(this, l).message;
            } else {
                msg = Enabler.enableWithPermission(this, l) ? "The service is on." : "";
            }
            final String m = msg;
            ui.post(() -> {
                busy = false;
                btnEnable.setEnabled(true);
                btnEnable.setAlpha(1f);
                progress.setText(m);
                refresh();
            });
        }, "enable").start();
    }

    private void refresh() {
        MarkerService s = MarkerService.instance;
        boolean on = s != null;
        boolean agreed = prefs.getBoolean("agreed", false);
        int state = on ? 3 : (agreed ? 2 : 1);
        panelAgree.setVisibility(state == 1 ? View.VISIBLE : View.GONE);
        panelOff.setVisibility(state == 2 ? View.VISIBLE : View.GONE);
        panelOn.setVisibility(state == 3 ? View.VISIBLE : View.GONE);
        if (state != lastState) {
            lastState = state;
            (state == 1 ? btnAgree : state == 2 ? btnEnable : btnToggle).requestFocus();
        }
        if (state == 2 && !autoTried && !busy && Enabler.canWriteSecureSettings(this)) {
            autoTried = true;   // e.g. after an update: switch back on without adb
            startEnable(false);
        }

        String qr;
        if (on) {
            keyLine.setText(learning ? "Press the remote button you want to use now…  (Back cancels)"
                    : "Show / hide button:  " + keyName(s.getKeyCode()));
            keyLine.setTextColor(learning ? 0xFFFFD21F : 0xFFD5DAE3);
            int n = s.phoneCount();
            phones.setText(n == 0 ? "No phone connected" : (n == 1 ? "1 phone connected" : n + " phones connected"));
            btnToggle.setText(s.isShown() ? "Hide overlay" : "Show overlay");
            int dim = s.getDimLevel();
            dimLine.setText(dim == 0 ? "Night dimmer: off" : "Night dimmer: " + dim + " %");
            btnDimOff.setEnabled(dim > 0);
            btnDimOff.setAlpha(dim > 0 ? 1f : 0.45f);
            String code = MarkerService.pairingCode(this);
            codeLine.setText("Pairing code:  " + Pairing.pretty(code));
            hint.setText("Short press: show / hide the marker layer, then draw on your phone (the drawing stays until you clear it). "
                    + "Long press (about 1 second): night dimmer off and photo removed. "
                    + "“New pairing code” disconnects every phone; they scan the new QR to come back.\nHelp: " + REPO);
            String url = MarkerService.phoneUrl(this);
            cardTitle.setText("Scan with your phone");
            if (url.isEmpty()) {
                urlText.setText("TV is not on the network");
                cardCode.setText("");
            } else {
                urlText.setText(url.substring(7, url.indexOf("/#k=")));
                cardCode.setText("Code  " + Pairing.pretty(code));
            }
            qr = url;
        } else {
            cardTitle.setText("Help & manual setup");
            urlText.setText(REPO.replace("/", "/​"));
            cardCode.setText("");
            qr = "https://" + REPO + "#setup";
        }
        if (!qr.equals(lastQr)) {
            lastQr = qr;
            qrView.setImageBitmap(qr.isEmpty() ? null : Qr.bitmap(qr));
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static String keyName(int kc) {
        if (kc == KeyEvent.KEYCODE_INFO) return "Info";
        String n = KeyEvent.keyCodeToString(kc);
        return n.startsWith("KEYCODE_") ? n.substring(8).replace('_', ' ') : n;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, 4, 0, 4);
        return t;
    }

    private TextView button(String s, float d) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(17);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setFocusable(true);
        t.setClickable(true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(24, d), px(13, d), px(24, d), px(13, d));
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(0xFF232937);
        normal.setCornerRadius(40 * d);
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFFFFD21F);
        focused.setCornerRadius(40 * d);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, normal);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, f) -> ((TextView) v).setTextColor(f ? 0xFF12151C : 0xFFE9ECF2));
        t.setTextColor(0xFFE9ECF2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(14, d);
        t.setLayoutParams(lp);
        return t;
    }

    private static int px(int dp, float d) { return Math.round(dp * d); }
}
