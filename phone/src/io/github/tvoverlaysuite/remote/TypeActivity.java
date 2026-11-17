package io.github.tvoverlaysuite.remote;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.tvoverlaysuite.TypeProto;

/**
 * The typing bar as a screen of its own: opened from the quick-settings tile, the "Type on TV" notification button,
 * the app, or the heads-up notification when the floating window can't be shown.
 */
public class TypeActivity extends Activity implements TypePanel.Host {
    static final String EXTRA_WANT = "want";

    private TypePanel panel;
    private TextView waiting;
    private boolean opened;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setBackgroundDrawable(new ColorDrawable(0x99000000));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setOnClickListener(v -> closePanel());   // tap the dim area to close
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        panel = new TypePanel(this, this);
        panel.setClickable(true);
        col.addView(panel);
        waiting = new TextView(this);
        waiting.setTextColor(Color.WHITE);
        waiting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        waiting.setGravity(Gravity.CENTER);
        waiting.setPadding(0, Math.round(16 * d), 0, 0);
        col.addView(waiting);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        int m = Math.round(12 * d);
        lp.setMargins(m, Math.round(36 * d), m, m);
        root.addView(col, lp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinkService s = LinkService.instance;
        if (s == null) {
            LinkService.start(this);
            waiting.setText("Connecting to the TV…");
            panel.setVisibility(android.view.View.INVISIBLE);
            waiting.postDelayed(this::retryWant, 1500);
            return;
        }
        s.activityPanel = this;
        s.closeBar();          // one typing bar at a time
        s.cancelHeadsUp();
        boolean want = getIntent().getBooleanExtra(EXTRA_WANT, false);
        if (!opened && !want && s.fieldOpen && s.field != null) {
            open(s.field);
        } else if (!opened) {
            panel.setVisibility(android.view.View.INVISIBLE);
            waiting.setText(s.connected() ? "Asking the TV…" : "Connecting to the TV…");
            s.want();
        }
    }

    private void retryWant() {
        LinkService s = LinkService.instance;
        if (s == null || opened || isFinishing()) return;
        s.activityPanel = this;
        if (s.connected()) { waiting.setText("Asking the TV…"); s.want(); }
        else waiting.postDelayed(this::retryWant, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LinkService s = LinkService.instance;
        if (s != null && s.activityPanel == this) s.activityPanel = null;
        if (isFinishing()) panel.hideIme();
    }

    void open(TypeProto.Msg m) {
        opened = true;
        waiting.setText("");
        panel.setVisibility(android.view.View.VISIBLE);
        panel.open(m);
    }

    void state(TypeProto.Msg m) { panel.state(m); }

    void fieldClosed(int f) { if (panel.fieldId() == f) finish(); }

    void noBox(String msg) {
        if (opened) return;
        waiting.setText(msg);
        waiting.postDelayed(this::finish, 2500);
    }

    @Override
    public void sendLine(String line) {
        LinkService s = LinkService.instance;
        if (s != null) s.sendLine(line);
    }

    @Override
    public void closePanel() {
        panel.hideIme();
        finish();
    }
}
