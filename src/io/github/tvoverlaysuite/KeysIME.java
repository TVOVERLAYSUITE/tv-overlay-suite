package io.github.tvoverlaysuite;

import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The suite's TV keyboard (new in v1.5) plus "type from the phone": while a text box is open it
 * mirrors the box to phones running the suite's phone app (through MarkerService's server, path /kb) and applies
 * their edits live. Everything here runs on the main thread; MarkerService posts phone messages to it.
 */
public class KeysIME extends InputMethodService implements KeyboardView.Listener {

    static volatile KeysIME instance;

    private static final String HINT =
            "Back = delete   ·   Hold Back = close   ·   Play/Pause = space   ·   Hold OK = á é í ó ú ñ / capital";
    private static final String[] DOMAINS = {"gmail.com", "hotmail.com", "yahoo.com", "outlook.com", "icloud.com"};
    private static final long LONG_MS = 380;
    private static final long HELD_TIMEOUT = 700;

    private KeyboardView kv;
    private Dict dict;
    private final Handler h = new Handler(Looper.getMainLooper());

    /** Mirror of the text just before the cursor (last 64 chars), kept locally so typing never waits on the app. */
    private final StringBuilder tail = new StringBuilder();
    private int lastSel = -1, pendingDelta;
    private boolean password, uriField, emailField;
    private long lastShiftTap;

    // d-pad hold
    private boolean dpadHeld;
    private int repDx, repDy, repCount;
    private long lastDpadEvent;
    private final Runnable rep = this::repTick;

    private void repTick() {
        if (!dpadHeld || SystemClock.uptimeMillis() - lastDpadEvent > HELD_TIMEOUT) { dpadHeld = false; return; }
        kv.move(repDx, repDy);
        repCount++;
        long next = repCount < 2 ? 120 : repCount < 5 ? 85 : repCount < 10 ? 60 : 45;
        h.postDelayed(rep, next);
    }

    // OK hold
    private boolean okHeld, okLongDone;
    private long lastOkEvent;
    private KeyboardView.Key okKey;
    private final Runnable okLong = this::okLongTick;

    private void okLongTick() {
        if (!okHeld) return;
        okLongDone = true;
        KeyboardView.Key k = okKey;
        if (k == null) return;
        if (k.type == KeyboardView.T_DEL) {
            delCount = 0;
            h.post(delRep);
        } else if (k.type == KeyboardView.T_CHAR && k.alt != null) {
            altReplace(k);
        }
    }
    private int delCount;
    private final Runnable delRep = this::delTick;

    private void delTick() {
        if (!okHeld || SystemClock.uptimeMillis() - lastOkEvent > HELD_TIMEOUT) { okHeld = false; return; }
        deleteOne();
        updateSuggestions();
        delCount++;
        h.postDelayed(delRep, delCount < 4 ? 90 : delCount < 12 ? 55 : 35);
    }

    // Back hold
    private boolean backHeld, backLongDone;
    private final Runnable backLong = this::backLongTick;

    private void backLongTick() {
        if (!backHeld) return;
        backLongDone = true;
        requestHideSelf(0);
    }

    private final Runnable resyncRun = this::resyncTick;

    private void resyncTick() { resync(); updateSuggestions(); }

    // ---- phone typing (v1.5), main thread only ----
    private boolean fieldOpen;          // a text box has input focus (keyboard shown or not)
    private int fieldId, fieldFlags, lastSeq;
    private String fieldAction = "Enter", fieldHint = "";
    private String mirror = "";         // the box's text as the phone sees it (a window of it, see winOff)
    private int mirS, mirE, winOff;     // selection inside the mirror; mirror[0] is the box's char at winOff
    private final Runnable stateCheck = this::checkState;
    private final Runnable restoreHint = () -> { if (kv != null) kv.setHintNow(HINT); };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        getSharedPreferences("marker", MODE_PRIVATE).edit().putBoolean("kb_used", true).apply();
        dict = new Dict(this);
        dict.loadLearned();
        new Thread(dict::load, "keys-dict").start();
        try { getWindow().getWindow().setWindowAnimations(0); } catch (Throwable ignored) { }
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        kv = new KeyboardView(this, this);
        kv.setHint(HINT);
        return kv;
    }

    @Override
    public boolean onEvaluateFullscreenMode() { return false; }

    @Override
    public void onStartInput(EditorInfo ei, boolean restarting) {
        super.onStartInput(ei, restarting);
        boolean text = ei != null && (ei.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_NULL;
        if (!text) { closeField(); return; }
        if (!restarting || !fieldOpen) { fieldId++; lastSeq = 0; }   // a new box; a restart keeps the phone's count
        fieldOpen = true;
        fieldFlags = flagsFor(ei);
        fieldAction = enterLabel(ei);
        fieldHint = ei.hintText == null ? "" : ei.hintText.toString();
        lastSel = ei.initialSelStart;
        readState();
        if (restarting) {                 // the app reset the box (e.g. cleared it): tell the phone shortly
            h.removeCallbacks(stateCheck);
            h.postDelayed(stateCheck, 120);
        }
    }

    @Override
    public void onFinishInput() {
        closeField();
        super.onFinishInput();
    }

    @Override
    public void onStartInputView(EditorInfo ei, boolean restarting) {
        super.onStartInputView(ei, restarting);
        int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
        int var = ei.inputType & InputType.TYPE_MASK_VARIATION;
        password = (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        uriField = cls == InputType.TYPE_CLASS_TEXT && var == InputType.TYPE_TEXT_VARIATION_URI;
        emailField = cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS);
        boolean numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE
                || cls == InputType.TYPE_CLASS_DATETIME;

        kv.buildLayouts(emailField ? '@' : uriField ? '/' : '\'');
        kv.shift = false;
        kv.caps = false;
        kv.setSymMode(numeric);
        kv.setEnterLabel(enterLabel(ei));
        if (!restarting) kv.resetFocus();

        lastSel = ei.initialSelStart;
        pendingDelta = 0;
        resync();
        updateSuggestions();
        kv.setHintNow(HINT);
        kv.setPhoneLinked(MarkerService.kbPhones() > 0);
        // the TV keyboard just came up for this box: let the phones pop up their typing bar
        if (fieldOpen && !restarting && MarkerService.kbPhones() > 0) { readState(); sendOpen(true); }
    }

    @Override
    public void onFinishInputView(boolean finishing) {
        stopAll();
        dict.save();
        super.onFinishInputView(finishing);
    }

    private void stopAll() {
        h.removeCallbacks(rep);
        h.removeCallbacks(okLong);
        h.removeCallbacks(delRep);
        h.removeCallbacks(backLong);
        h.removeCallbacks(resyncRun);
        dpadHeld = okHeld = backHeld = false;
    }

    private static String enterLabel(EditorInfo ei) {
        if ((ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "Enter";
        switch (ei.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO: return "Go";
            case EditorInfo.IME_ACTION_SEARCH: return "Search";
            case EditorInfo.IME_ACTION_SEND: return "Send";
            case EditorInfo.IME_ACTION_NEXT: return "Next";
            case EditorInfo.IME_ACTION_DONE: return "Done";
            default: return "Enter";
        }
    }

    // ---------------------------------------------------------------- remote keys

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (kv == null || !isInputViewShown()) return super.onKeyDown(code, ev);
        int rc = ev.getRepeatCount();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: return dpad(0, -1, rc);
            case KeyEvent.KEYCODE_DPAD_DOWN: return dpad(0, 1, rc);
            case KeyEvent.KEYCODE_DPAD_LEFT: return dpad(-1, 0, rc);
            case KeyEvent.KEYCODE_DPAD_RIGHT: return dpad(1, 0, rc);

            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                lastOkEvent = SystemClock.uptimeMillis();
                if (rc == 0) {
                    stopDpad();
                    okHeld = true;
                    okLongDone = false;
                    okKey = kv.focusedKey();
                    kv.press();
                    h.removeCallbacks(okLong);
                    h.postDelayed(okLong, LONG_MS);
                }
                return true;

            case KeyEvent.KEYCODE_BACK:
                if (rc == 0) {
                    backHeld = true;
                    backLongDone = false;
                    h.removeCallbacks(backLong);
                    h.postDelayed(backLong, 500);
                }
                return true;

            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (rc == 0) { learnCurrentWord(); commit(" "); updateSuggestions(); }
                return true;

            case KeyEvent.KEYCODE_DEL:
                deleteOne();
                updateSuggestions();
                return true;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (rc == 0) { commit(String.valueOf((char) ('0' + code - KeyEvent.KEYCODE_0))); updateSuggestions(); }
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (dpadHeld) { stopDpad(); return true; }
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (okHeld) {
                    okHeld = false;
                    h.removeCallbacks(okLong);
                    h.removeCallbacks(delRep);
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_BACK:
                if (backHeld) {
                    backHeld = false;
                    h.removeCallbacks(backLong);
                    if (!backLongDone) backTap();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DEL:
                if (kv != null && isInputViewShown()) return true;
                break;
        }
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9 && kv != null && isInputViewShown()) return true;
        return super.onKeyUp(code, ev);
    }

    private boolean dpad(int dx, int dy, int rc) {
        lastDpadEvent = SystemClock.uptimeMillis();
        if (rc == 0) {
            kv.move(dx, dy);
            dpadHeld = true;
            repDx = dx;
            repDy = dy;
            repCount = 0;
            h.removeCallbacks(rep);
            h.postDelayed(rep, 260);
        }
        return true; // system auto-repeats are ignored; our own timer drives held movement
    }

    private void stopDpad() {
        dpadHeld = false;
        h.removeCallbacks(rep);
    }

    private void backTap() {
        if (tail.length() > 0) { deleteOne(); updateSuggestions(); return; }
        InputConnection ic = getCurrentInputConnection();
        CharSequence t = ic == null ? null : ic.getTextBeforeCursor(1, 0);
        if (t != null && t.length() > 0) { deleteOne(); h.postDelayed(resyncRun, 40); }
        else requestHideSelf(0);
    }

    // ---------------------------------------------------------------- on-screen keys

    @Override
    public void onKey(KeyboardView.Key k) {
        switch (k.type) {
            case KeyboardView.T_CHAR: {
                String s = k.out;
                if ((kv.shift || kv.caps) && !kv.symMode) s = s.toUpperCase();
                char c = s.charAt(0);
                if (!Character.isLetterOrDigit(c) && c != '\'') learnCurrentWord();
                commit(s);
                if (kv.shift && !kv.caps) { kv.shift = false; kv.invalidate(); }
                updateSuggestions();
                break;
            }
            case KeyboardView.T_SHIFT: {
                long now = SystemClock.uptimeMillis();
                if (kv.caps) { kv.caps = false; kv.shift = false; }
                else if (kv.shift && now - lastShiftTap < 400) { kv.caps = true; }
                else kv.shift = !kv.shift;
                lastShiftTap = now;
                kv.invalidate();
                break;
            }
            case KeyboardView.T_SYM:
                kv.setSymMode(!kv.symMode);
                break;
            case KeyboardView.T_SPACE:
                learnCurrentWord();
                commit(" ");
                updateSuggestions();
                break;
            case KeyboardView.T_DEL:
                deleteOne();
                updateSuggestions();
                break;
            case KeyboardView.T_TEXT:
                commit(k.out);
                updateSuggestions();
                break;
            case KeyboardView.T_ENTER:
                learnCurrentWord();
                doEnter();
                break;
            case KeyboardView.T_PHONE:
                phoneKey();
                break;
        }
    }

    @Override
    public void onSuggestion(String s) {
        String dp = emailDomainPrefix();
        if (dp != null) {
            replaceTyped(dp.length(), s);
        } else if (s.equals("www.") || s.equals("https://")) {
            commit(s);
        } else {
            String w = currentWord();
            replaceTyped(w.length(), s + " ");
            if (!password) dict.learn(s);
        }
        updateSuggestions();
    }

    // ---------------------------------------------------------------- text ops

    private void commit(String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(s, 1);
        tail.append(s);
        if (tail.length() > 64) tail.delete(0, tail.length() - 64);
        pendingDelta += s.length();
    }

    private void deleteOne() {
        if (getCurrentInputConnection() == null) return;
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        if (tail.length() > 0) {
            tail.setLength(tail.length() - 1);
            pendingDelta -= 1;
        }
    }

    private void replaceTyped(int n, String s) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        n = Math.min(n, tail.length());
        ic.beginBatchEdit();
        if (n > 0) ic.deleteSurroundingText(n, 0);
        ic.commitText(s, 1);
        ic.endBatchEdit();
        tail.setLength(tail.length() - n);
        tail.append(s);
        if (tail.length() > 64) tail.delete(0, tail.length() - 64);
        pendingDelta += s.length() - n;
    }

    private void altReplace(KeyboardView.Key k) {
        if (tail.length() == 0) return;
        char typed = tail.charAt(tail.length() - 1);
        String alt;
        if (k.alt.equals(k.label.toUpperCase())) {
            // plain letter: hold flips its case
            alt = Character.isUpperCase(typed) ? k.label.toLowerCase() : k.label.toUpperCase();
        } else {
            alt = Character.isUpperCase(typed) ? k.alt.toUpperCase() : k.alt;
        }
        replaceTyped(1, alt);
        updateSuggestions();
    }

    private void doEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_UNSPECIFIED : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noAction = ei == null || (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    private void resync() {
        tail.setLength(0);
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence t = ic.getTextBeforeCursor(64, 0);
        if (t != null) tail.append(t);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd,
                                  int candStart, int candEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd);
        int moved = newSelStart - lastSel;
        boolean ok = lastSel >= 0 && newSelStart == newSelEnd
                && (moved == pendingDelta
                    || (pendingDelta > 0 && moved > 0 && moved < pendingDelta)
                    || (pendingDelta < 0 && moved < 0 && moved > pendingDelta));
        lastSel = newSelStart;
        if (fieldOpen) {
            mirS = clampMir(newSelStart - winOff);
            mirE = clampMir(newSelEnd - winOff);
            h.removeCallbacks(stateCheck);
            h.postDelayed(stateCheck, 120);
        }
        if (ok) {
            pendingDelta -= moved;
        } else {
            // the app or the user changed the text: re-read it once, off the typing path
            pendingDelta = 0;
            h.removeCallbacks(resyncRun);
            h.postDelayed(resyncRun, 40);
        }
    }

    // ---------------------------------------------------------------- suggestions

    private String currentWord() {
        int i = tail.length();
        while (i > 0) {
            char c = tail.charAt(i - 1);
            if (Character.isLetter(c) || c == '\'') i--; else break;
        }
        while (i < tail.length() && tail.charAt(i) == '\'') i++;
        return tail.substring(i);
    }

    /** Text typed after an '@' (for e-mail domains), or null if not in that spot. */
    private String emailDomainPrefix() {
        int at = tail.lastIndexOf("@");
        if (at <= 0) return null;
        char before = tail.charAt(at - 1);
        if (Character.isWhitespace(before)) return null;
        for (int i = at + 1; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-')) return null;
        }
        return tail.substring(at + 1);
    }

    private void learnCurrentWord() {
        if (password) return;
        String w = currentWord();
        if (w.length() >= 2) dict.learn(w);
    }

    private void updateSuggestions() {
        if (kv == null) return;
        List<String> out;
        if (password) {
            out = Collections.emptyList();
        } else {
            String dp = emailDomainPrefix();
            if (dp != null) {
                out = new ArrayList<>();
                String low = dp.toLowerCase();
                for (String d : DOMAINS) if (d.startsWith(low) && !d.equals(low)) out.add(d);
            } else {
                String w = currentWord();
                if (!w.isEmpty()) out = dict.suggest(w, 5);
                else if (uriField && tail.length() == 0) {
                    out = new ArrayList<>();
                    out.add("www.");
                    out.add("https://");
                } else out = Collections.emptyList();
            }
        }
        kv.setSuggestions(out);
    }

    // ---------------------------------------------------------------- phone typing (v1.5)

    static int flagsFor(EditorInfo ei) {
        int cls = ei.inputType & InputType.TYPE_MASK_CLASS;
        int var = ei.inputType & InputType.TYPE_MASK_VARIATION;
        int f = 0;
        if ((cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
                || (cls == InputType.TYPE_CLASS_NUMBER && var == InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            f |= TypeProto.F_PASSWORD;
        }
        if (cls == InputType.TYPE_CLASS_TEXT && (ei.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) {
            f |= TypeProto.F_MULTILINE;
        }
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE) f |= TypeProto.F_NUMBER;
        if (cls == InputType.TYPE_CLASS_TEXT && (var == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || var == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) f |= TypeProto.F_EMAIL;
        if (cls == InputType.TYPE_CLASS_TEXT && var == InputType.TYPE_TEXT_VARIATION_URI) f |= TypeProto.F_URI;
        return f;
    }

    private int clampMir(int v) { return Math.max(0, Math.min(v, mirror.length())); }

    private void closeField() {
        h.removeCallbacks(stateCheck);
        if (!fieldOpen) return;
        fieldOpen = false;
        MarkerService.sendKb(TypeProto.closed(fieldId));
    }

    /** Reads the box into the mirror. Password boxes are never read: the phone starts empty and types at the cursor. */
    private void readState() {
        InputConnection ic = getCurrentInputConnection();
        String full = null;
        int selS = 0, selE = 0;
        if ((fieldFlags & TypeProto.F_PASSWORD) != 0) {
            int cur = Math.max(0, lastSel >= 0 ? lastSel : 0);
            if (ic != null) {
                CharSequence before = ic.getTextBeforeCursor(TypeProto.MAX_TEXT, 0);
                if (before != null) cur = before.length();
            }
            mirror = "";
            winOff = cur;
            mirS = mirE = 0;
            return;
        }
        if (ic != null) {
            ExtractedText et = null;
            try {
                ExtractedTextRequest rq = new ExtractedTextRequest();
                rq.hintMaxChars = 0;
                et = ic.getExtractedText(rq, 0);
            } catch (RuntimeException ignored) { }
            if (et != null && et.text != null && et.partialStartOffset < 0) {
                full = et.text.toString();
                selS = et.startOffset + et.selectionStart;
                selE = et.startOffset + et.selectionEnd;
                if (et.startOffset > 0) { full = null; }   // only part of the box: fall back below
            }
            if (full == null) {
                CharSequence b = ic.getTextBeforeCursor(TypeProto.MAX_TEXT, 0);
                CharSequence sel = ic.getSelectedText(0);
                CharSequence a = ic.getTextAfterCursor(TypeProto.MAX_TEXT, 0);
                String bs = b == null ? "" : b.toString(), ss = sel == null ? "" : sel.toString();
                full = bs + ss + (a == null ? "" : a.toString());
                // positions relative to what we could read; absolute offset found from the known cursor
                int base = Math.max(0, (lastSel >= 0 ? lastSel : bs.length()) - bs.length());
                mirror = full;
                winOff = base;
                mirS = bs.length();
                mirE = bs.length() + ss.length();
                trimWindow();
                return;
            }
        }
        if (full == null) full = "";
        mirror = full;
        winOff = 0;
        mirS = Math.max(0, Math.min(selS, full.length()));
        mirE = Math.max(mirS, Math.min(selE, full.length()));
        trimWindow();
    }

    private void trimWindow() {
        if (mirror.length() <= TypeProto.MAX_TEXT) return;
        Object[] w = TypeProto.window(mirror, mirS);
        int off = (Integer) w[1];
        mirror = (String) w[0];
        winOff += off;
        mirS = clampMir(mirS - off);
        mirE = clampMir(mirE - off);
    }

    private void sendOpen(boolean auto) {
        if (!fieldOpen) return;
        MarkerService.sendKb(TypeProto.open(auto, fieldId, lastSeq, mirS, mirE, fieldFlags, fieldAction, fieldHint,
                mirror));
    }

    /** Runs 120 ms after the box last changed: if it no longer matches what the phone was told, send the new text. */
    private void checkState() {
        if (!fieldOpen || (fieldFlags & TypeProto.F_PASSWORD) != 0 || MarkerService.kbPhones() == 0) return;
        String before = mirror;
        int s0 = mirS, e0 = mirE, off0 = winOff;
        readState();
        if (!mirror.equals(before) || winOff != off0 || mirS != s0 || mirE != e0) {
            MarkerService.sendKb(TypeProto.state(fieldId, lastSeq, mirS, mirE, mirror));
        }
    }

    /** The phone key on the TV keyboard. */
    private void phoneKey() {
        if (MarkerService.kbPhones() == 0) {
            kv.setHintNow(MarkerService.instance == null
                    ? "Phone typing needs TV Overlay Suite switched on (open the app on the TV)"
                    : "No phone connected. Open the TV Overlay Suite app on your phone");
            h.removeCallbacks(restoreHint);
            h.postDelayed(restoreHint, 4000);
            return;
        }
        if (!fieldOpen) return;
        kv.setHintNow("Opening the keyboard on your phone...");
        h.removeCallbacks(restoreHint);
        h.postDelayed(restoreHint, 2500);
        sendOpen(false);
    }

    /** A phone connected or disconnected: recolour the phone key. Main thread. */
    void phonesChanged() {
        if (kv != null) kv.setPhoneLinked(MarkerService.kbPhones() > 0);
    }

    /** A T message from a phone, already parsed. Main thread. Returns the reply for that phone only, or null. */
    String fromPhone(TypeProto.Msg m) {
        switch (m.kind) {
            case 'w':
                if (!fieldOpen) return TypeProto.none("none");
                readState();
                return TypeProto.open(false, fieldId, lastSeq, mirS, mirE, fieldFlags, fieldAction, fieldHint, mirror);
            case 'e':
                if (fieldOpen && m.field == fieldId) applyEdit(m);
                return null;
            case 'a':
                if (fieldOpen && m.field == fieldId) doEnter();
                return null;
            default:
                return null;
        }
    }

    private void applyEdit(TypeProto.Msg m) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        int len = mirror.length();
        int start = Math.max(0, Math.min(m.start, len));
        int del = Math.max(0, Math.min(m.del, len - start));
        ic.beginBatchEdit();
        int cursor = TypeProto.play(new IcOps(ic), len, mirS, mirE, winOff, start, del, m.text, m.cursor);
        ic.endBatchEdit();
        mirror = TypeProto.apply(mirror, start, del, m.text);
        mirS = mirE = cursor;
        lastSeq = m.seq;
        pendingDelta = 0;                              // the keyboard's own tracking: re-read after the app reports back
        h.removeCallbacks(resyncRun);
        h.postDelayed(resyncRun, 60);
    }

    /** Named class, not anonymous (d8 8.2.2 bug). */
    private static final class IcOps implements TypeProto.Ops {
        private final InputConnection ic;
        IcOps(InputConnection ic) { this.ic = ic; }
        @Override public void deleteBefore(int n) { ic.deleteSurroundingText(n, 0); }
        @Override public void select(int a, int b) { ic.setSelection(a, b); }
        @Override public void commit(String t) { ic.commitText(t, 1); }
    }
}
