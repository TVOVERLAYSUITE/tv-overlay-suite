package io.github.tvoverlaysuite.remote;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.tvoverlaysuite.TypeProto;

/**
 * The typing bar: a text box that mirrors the TV's text box. Every change (each letter, a swipe-typed word, an
 * autocorrection, backspace, a tap that moves the cursor) is sent to the TV at once as the smallest edit.
 * Used both in the floating window (TypingBar) and in TypeActivity.
 */
final class TypePanel extends LinearLayout {

    interface Host {
        void sendLine(String line);
        void closePanel();
    }

    static final int BG = 0xFF1B202A, FIELD = 0xFF252B38, TEXT = 0xFFE9ECF2, MUTED = 0xFF8A93A3, ACCENT = 0xFFFFE135;

    private final Host host;
    private final Store store;
    private final SelEdit edit;
    private final TextView title, actionBtn;
    private final float d;

    private int field = -1, flags, seq;
    private String mirror = "";          // what the TV's box holds, as far as this phone knows
    private int mirCur;
    private boolean suppress;

    TypePanel(Context c, Host host) {
        super(c);
        this.host = host;
        store = new Store(c);
        d = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(20 * d);
        bg.setStroke(px(1), 0x33FFFFFF);
        setBackground(bg);
        setPadding(px(14), px(10), px(10), px(12));
        setElevation(12 * d);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        title = new TextView(c);
        title.setTextColor(MUTED);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        actionBtn = pill("Search", true);
        actionBtn.setOnClickListener(v -> pressAction());
        top.addView(actionBtn);
        TextView close = pill("✕", false);
        close.setContentDescription("Close");
        close.setOnClickListener(v -> host.closePanel());
        LayoutParams clp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clp.leftMargin = px(6);
        top.addView(close, clp);
        addView(top);

        edit = new SelEdit(c, this);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(FIELD);
        fb.setCornerRadius(14 * d);
        edit.setBackground(fb);
        edit.setPadding(px(14), px(10), px(14), px(10));
        edit.setMaxLines(4);
        LayoutParams elp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        elp.topMargin = px(8);
        addView(edit, elp);
        edit.addTextChangedListener(new Watcher(this));
        edit.setOnEditorActionListener((v, id, ev) -> {
            if (ev != null && ev.getAction() != KeyEvent.ACTION_DOWN) return true;
            if ((flags & TypeProto.F_MULTILINE) != 0 && id == EditorInfo.IME_NULL) return false;   // newline
            pressAction();
            return true;
        });
    }

    private TextView pill(String s, boolean accent) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(accent ? 0xFF151820 : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), px(7), px(14), px(7));
        t.setMinWidth(px(40));
        GradientDrawable g = new GradientDrawable();
        g.setColor(accent ? ACCENT : FIELD);
        g.setCornerRadius(999);
        t.setBackground(g);
        return t;
    }

    private int px(int dp) { return Math.round(dp * d); }

    int fieldId() { return field; }

    /** A box opened on the TV (or the panel was asked for): show its text and get ready to type. */
    void open(TypeProto.Msg m) {
        field = m.field;
        flags = m.flags;
        seq = m.seq;
        String label = m.action == null || m.action.isEmpty() ? "Enter" : m.action;
        actionBtn.setText(label);
        String what = !m.hint.isEmpty() ? m.hint : (flags & TypeProto.F_PASSWORD) != 0 ? "Password" : "Text box";
        title.setText("Typing on the TV  ·  " + what);
        int type = InputType.TYPE_CLASS_TEXT;
        if ((flags & TypeProto.F_PASSWORD) != 0) type = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        else if ((flags & TypeProto.F_NUMBER) != 0) type = InputType.TYPE_CLASS_NUMBER;
        else if ((flags & TypeProto.F_EMAIL) != 0) type |= InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        else if ((flags & TypeProto.F_URI) != 0) type |= InputType.TYPE_TEXT_VARIATION_URI;
        else if ((flags & TypeProto.F_MULTILINE) != 0) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        int ime = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN | imeAction(label);
        suppress = true;
        edit.setInputType(type);
        edit.setImeOptions(ime);
        edit.setHint(m.hint.isEmpty() ? "Type here, it appears on the TV" : m.hint);
        edit.setText(m.text);
        int s = clamp(m.selS, m.text.length()), e = clamp(m.selE, m.text.length());
        edit.setSelection(s, Math.max(s, e));
        mirror = m.text;
        mirCur = s;
        suppress = false;
        focusAndShowKeyboard();
    }

    private static int imeAction(String label) {
        switch (label) {
            case "Search": return EditorInfo.IME_ACTION_SEARCH;
            case "Go": return EditorInfo.IME_ACTION_GO;
            case "Send": return EditorInfo.IME_ACTION_SEND;
            case "Next": return EditorInfo.IME_ACTION_NEXT;
            default: return EditorInfo.IME_ACTION_DONE;
        }
    }

    /** The TV's box changed by itself. Taken only when every edit this phone sent has already been applied. */
    void state(TypeProto.Msg m) {
        if (m.field != field || m.seq < seq) return;
        if (m.text.equals(edit.getText().toString())) { mirror = m.text; return; }
        suppress = true;
        edit.setText(m.text);
        int s = clamp(m.selS, m.text.length());
        edit.setSelection(s);
        mirror = m.text;
        mirCur = s;
        suppress = false;
    }

    void focusAndShowKeyboard() {
        edit.requestFocus();
        edit.post(this::showIme);
        edit.postDelayed(this::showIme, 180);   // the floating window may get focus a moment later
    }

    private void showIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
    }

    void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
    }

    private void pressAction() {
        if (field < 0) return;
        host.sendLine(TypeProto.action(field));
        if ((flags & TypeProto.F_MULTILINE) == 0 && store.closeOnEnter()) host.closePanel();
    }

    /** Text or cursor changed on the phone: send the difference right away. */
    void changed() {
        if (suppress || field < 0) return;
        String now = edit.getText().toString();
        int s = edit.getSelectionStart(), e = edit.getSelectionEnd();
        if (s < 0) return;
        if (s != e) {                      // a range is selected on the phone: nothing to send until it is replaced
            if (now.equals(mirror)) return;
            s = Math.max(s, e);
        }
        if (now.equals(mirror) && s == mirCur) return;
        int[] df = TypeProto.diff(mirror, now);
        host.sendLine(TypeProto.edit(++seq, field, df[0], df[1], s, now.substring(df[0], df[2])));
        mirror = now;
        mirCur = s;
    }

    private static int clamp(int v, int max) { return Math.max(0, Math.min(v, max)); }

    /** EditText that reports cursor moves (taps, arrow drags) as well as text changes. */
    static final class SelEdit extends EditText {
        private final TypePanel panel;

        SelEdit(Context c, TypePanel p) { super(c); panel = p; }

        @Override
        protected void onSelectionChanged(int s, int e) {
            super.onSelectionChanged(s, e);
            if (panel != null) panel.changed();
        }

        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent ev) {
            // Back with the keyboard up closes the whole bar (one press, not two)
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.getAction() == KeyEvent.ACTION_UP && panel != null) {
                panel.host.closePanel();
                return true;
            }
            return super.onKeyPreIme(keyCode, ev);
        }
    }

    static final class Watcher implements TextWatcher {
        private final TypePanel p;
        Watcher(TypePanel p) { this.p = p; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable s) { p.changed(); }
    }
}
