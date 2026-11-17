package io.github.tvoverlaysuite.remote;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import io.github.tvoverlaysuite.CamProto;

/**
 * v1.7: this phone as the TV's camera and microphone for a video call (for example in a TV web browser).
 *
 * The TV app asks through TV Overlay Suite on the TV; this screen opens, asks once per TV app whether it may use the
 * camera, and a small page (WebRTC in this app's own WebView) sends the camera and microphone straight to the TV app
 * over the home Wi-Fi. TV Overlay Suite only carries the set-up text. Android only lets an app use the camera while it
 * is on screen, so this screen stays open (and on) during the call; "Dim" darkens it to save battery.
 */
public class CamActivity extends Activity {
    /** The page is served from this app's assets at an https address, so the WebView treats it as a secure page
     *  (cameras only work on secure pages). Nothing is fetched from the internet: every request is answered here. */
    static final String ORIGIN = "https://appassets.androidplatform.net";
    static final String PAGE = ORIGIN + "/cam/index.html";

    private static final int BG = 0xFF0F1218, FIELD = 0xFF252B38, TEXT = 0xFFE9ECF2, MUTED = 0xFF8A93A3, ACCENT = 0xFFFFE135;
    private static final int REQ_PERMS = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private float d;
    private WebView web;
    private boolean pageReady;
    private final ArrayList<String> toPage = new ArrayList<>();
    /** Open requests waiting for "Allow" on this screen, or for Android's camera permission. */
    private final ArrayList<String> waitingAsk = new ArrayList<>(), waitingPerm = new ArrayList<>();
    /** Sessions the page reported (its offers), so they can be ended if this screen closes. */
    final Set<String> live = new HashSet<>();
    private LinearLayout askCard;
    private TextView askText;
    private String askPkg = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        d = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        build();
        LinkService s = LinkService.instance;
        if (s == null) LinkService.start(this);
        attach();
    }

    private void attach() {
        LinkService s = LinkService.instance;
        if (s == null) { main.postDelayed(this::attach, 500); return; }
        if (isFinishing()) return;
        s.camActivity = this;
        s.camNoteClear();
        ArrayList<String> p = new ArrayList<>(s.camPending);
        s.camPending.clear();
        for (String line : p) fromTv(line);
    }

    @Override
    protected void onDestroy() {
        LinkService s = LinkService.instance;
        if (s != null) {
            if (s.camActivity == this) s.camActivity = null;
            for (String sid : live) s.sendLine("V x " + sid);
            for (String line : waitingAsk) deny(line, "Closed on the phone");
            for (String line : waitingPerm) deny(line, "Closed on the phone");
        }
        live.clear();
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            web.evaluateJavascript("window.cam && cam.stopAll(false)", null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        finish();   // onDestroy ends every session on the TV
    }

    // ---------------------------------------------------------------- from the TV (main thread)

    void fromTv(String line) {
        CamProto.Msg m = CamProto.parseForPhone(line);
        if (m == null) return;
        if (m.kind == 'o') {
            if (!store.camAllowed(m.app)) {
                waitingAsk.add(line);
                send(CamProto.state(m.sid, "wait", null));
                ask();
                return;
            }
            if (!permsOk()) {
                waitingPerm.add(line);
                send(CamProto.state(m.sid, "wait", null));
                requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
                return;
            }
        } else if (m.kind == 'x') {
            drop(waitingAsk, m.sid);
            drop(waitingPerm, m.sid);
            live.remove(m.sid);
            if (waitingAsk.isEmpty()) askCard.setVisibility(View.GONE);
        }
        page(line);
    }

    /** The link to the TV dropped: the page ends its sessions (the TV ends them too). */
    void linkLost() {
        waitingAsk.clear();
        waitingPerm.clear();
        live.clear();
        askCard.setVisibility(View.GONE);
        if (web != null && pageReady) web.evaluateJavascript("cam.linkLost()", null);
    }

    private static void drop(ArrayList<String> l, String sid) {
        for (Iterator<String> it = l.iterator(); it.hasNext(); ) {
            CamProto.Msg m = CamProto.parseForPhone(it.next());
            if (m != null && m.sid.equals(sid)) it.remove();
        }
    }

    private void page(String line) {
        if (web == null) return;
        if (!pageReady) { toPage.add(line); return; }
        web.evaluateJavascript("cam.fromTv(" + JSONObject.quote(line) + ")", null);
    }

    void send(String line) {
        LinkService s = LinkService.instance;
        if (s != null) s.sendLine(line);
    }

    private void deny(String openLine, String why) {
        CamProto.Msg m = CamProto.parseForPhone(openLine);
        if (m != null) send(CamProto.state(m.sid, "deny", why));
    }

    // ---------------------------------------------------------------- asking (main thread)

    private void ask() {
        if (waitingAsk.isEmpty()) { askCard.setVisibility(View.GONE); return; }
        CamProto.Msg m = CamProto.parseForPhone(waitingAsk.get(0));
        if (m == null) { waitingAsk.remove(0); ask(); return; }
        askPkg = m.app;
        String tv = store.tvName().isEmpty() ? "the TV" : store.tvName();
        askText.setText(m.name + " on " + tv + " wants to use this phone's camera"
                + (m.media.contains("a") ? " and microphone" : "") + " for a video call.");
        askCard.setVisibility(View.VISIBLE);
    }

    private void answer(boolean allow) {
        String pkg = askPkg;
        ArrayList<String> mine = new ArrayList<>();
        for (Iterator<String> it = waitingAsk.iterator(); it.hasNext(); ) {
            String line = it.next();
            CamProto.Msg m = CamProto.parseForPhone(line);
            if (m == null || m.app.equals(pkg)) { it.remove(); if (m != null) mine.add(line); }
        }
        if (allow) {
            store.setCamAllowed(pkg, true);
            for (String line : mine) fromTv(line);
        } else {
            for (String line : mine) deny(line, "Not allowed on the phone");
        }
        ask();
    }

    private boolean permsOk() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code != REQ_PERMS) return;
        ArrayList<String> l = new ArrayList<>(waitingPerm);
        waitingPerm.clear();
        if (permsOk()) {
            for (String line : l) fromTv(line);
        } else {
            for (String line : l) deny(line, "Camera permission refused on the phone");
            Toast.makeText(this, "The camera and microphone permissions are needed to be the TV's camera", Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- page bridge

    void pageReady() {
        pageReady = true;
        ArrayList<String> l = new ArrayList<>(toPage);
        toPage.clear();
        for (String line : l) page(line);
    }

    /** A line from the page: only the phone's own camera lines (offer, state, end) go to the TV. */
    void fromPage(String line) {
        CamProto.Msg m = CamProto.parsePhone(line);
        if (m == null || m.kind == 'y') return;
        if (m.kind == 's') live.add(m.sid);
        else if (m.kind == 'x') live.remove(m.sid);
        send(line);
    }

    void dim(boolean on) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = on ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    /** Named class (d8 8.2.2 bug with anonymous classes). Methods run on a WebView thread: hop to the main thread. */
    static final class Bridge {
        private final CamActivity a;
        Bridge(CamActivity a) { this.a = a; }
        @JavascriptInterface public void send(String line) { if (line != null && line.length() < CamProto.MAX_SDP + 100) a.main.post(() -> a.fromPage(line)); }
        @JavascriptInterface public void ready() { a.main.post(a::pageReady); }
        @JavascriptInterface public void dim(boolean on) { a.main.post(() -> a.dim(on)); }
        @JavascriptInterface public void close() { a.main.post(a::finish); }
        @JavascriptInterface public String tvName() { String n = a.store.tvName(); return n.isEmpty() ? "the TV" : n; }
    }

    // ---------------------------------------------------------------- WebView

    /** Answers the page's own files from the app; anything else is refused (the page never reaches the internet). */
    static final class Client extends WebViewClient {
        private final CamActivity a;
        Client(CamActivity a) { this.a = a; }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            String path = u.getPath() == null ? "" : u.getPath();
            if (ORIGIN.equals(u.getScheme() + "://" + u.getHost()) && path.startsWith("/cam/") && !path.contains("..")) {
                String file = path.substring(1);
                String mime = file.endsWith(".js") ? "text/javascript" : file.endsWith(".css") ? "text/css" : "text/html";
                try {
                    InputStream in = a.getAssets().open(file);
                    return new WebResourceResponse(mime, "utf-8", in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
            return new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null, new java.io.ByteArrayInputStream(new byte[0]));
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return true; }
    }

    static final class Chrome extends WebChromeClient {
        private final CamActivity a;
        Chrome(CamActivity a) { this.a = a; }

        @Override
        public void onPermissionRequest(PermissionRequest r) {
            String o = r.getOrigin() == null ? "" : r.getOrigin().toString();
            if (o.endsWith("/")) o = o.substring(0, o.length() - 1);
            if (!ORIGIN.equals(o) || !a.permsOk()) { r.deny(); return; }
            ArrayList<String> ok = new ArrayList<>();
            for (String res : r.getResources()) {
                if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res) || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) ok.add(res);
            }
            if (ok.isEmpty()) r.deny(); else r.grant(ok.toArray(new String[0]));
        }
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        web = new WebView(this);
        web.setBackgroundColor(BG);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setDomStorageEnabled(false);
        web.setWebViewClient(new Client(this));
        web.setWebChromeClient(new Chrome(this));
        web.addJavascriptInterface(new Bridge(this), "TvosCam");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        askCard = new LinearLayout(this);
        askCard.setOrientation(LinearLayout.VERTICAL);
        askCard.setPadding(px(22), px(22), px(22), px(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF1B202A);
        g.setCornerRadius(22 * d);
        g.setStroke(px(1), 0x55FFE135);
        askCard.setBackground(g);
        TextView t = text("Use this phone as the TV's camera?", 19, TEXT, true);
        askCard.addView(t);
        askText = text("", 15, MUTED, false);
        askText.setPadding(0, px(8), 0, px(16));
        askCard.addView(askText);
        LinearLayout row = new LinearLayout(this);
        TextView no = button("Don't allow", false);
        no.setOnClickListener(v -> answer(false));
        TextView yes = button("Allow", true);
        yes.setOnClickListener(v -> answer(true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(px(4), 0, px(4), 0);
        row.addView(no, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
        lp2.setMargins(px(4), 0, px(4), 0);
        row.addView(yes, lp2);
        askCard.addView(row);
        TextView note = text("Allowed apps can start the camera from the TV while this app is connected. "
                + "\"Forget camera permissions\" in the app takes it back.", 12, MUTED, false);
        note.setPadding(0, px(12), 0, 0);
        askCard.addView(note);
        askCard.setVisibility(View.GONE);
        askCard.setClickable(true);
        FrameLayout.LayoutParams ap = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        ap.setMargins(px(18), 0, px(18), 0);
        root.addView(askCard, ap);
        setContentView(root);
        web.loadUrl(PAGE);
    }

    private TextView button(String s, boolean primary) {
        TextView t = text(s, 16, primary ? 0xFF15181E : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(13), 0, px(13));
        GradientDrawable g = new GradientDrawable();
        g.setColor(primary ? ACCENT : FIELD);
        g.setCornerRadius(14 * d);
        t.setBackground(g);
        t.setClickable(true);
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
