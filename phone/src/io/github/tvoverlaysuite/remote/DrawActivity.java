package io.github.tvoverlaysuite.remote;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * The TV's own phone page (draw, laser, messages, photo, dimmer), opened already paired: no QR to scan. Motion
 * pointing needs Chrome's flag and is not available here; Trackpad mode works.
 */
public class DrawActivity extends Activity {
    private static final int PICK = 7;
    private WebView web;
    private ValueCallback<Uri[]> pending;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store s = new Store(this);
        if (s.host().isEmpty() || s.code().isEmpty()) {
            Toast.makeText(this, "Pair with the TV first", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        getWindow().setStatusBarColor(0xFF0F1218);
        getWindow().setNavigationBarColor(0xFF0F1218);
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(15, 18, 24));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUserAgentString(ws.getUserAgentString() + " TVOverlaySuiteApp");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome(this));
        setContentView(web);
        web.loadUrl("http://" + s.host() + ":" + Store.PORT + "/#k=" + s.code());
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) { web.goBack(); return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.loadUrl("about:blank"); web.destroy(); }
        super.onDestroy();
    }

    boolean pickFile(ValueCallback<Uri[]> cb, WebChromeClient.FileChooserParams p) {
        if (pending != null) pending.onReceiveValue(null);
        pending = cb;
        try {
            startActivityForResult(p.createIntent(), PICK);
            return true;
        } catch (RuntimeException e) {
            pending = null;
            return false;
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent data) {
        if (rc == PICK && pending != null) {
            pending.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            pending = null;
        }
    }

    /** Keeps the TV page inside the app; other links open in the browser. Named classes (d8 bug). */
    static final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            Uri u = Uri.parse(url);
            if (u.getPort() == Store.PORT) return false;
            try { v.getContext().startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (RuntimeException ignored) { }
            return true;
        }
    }

    static final class Chrome extends WebChromeClient {
        private final DrawActivity a;
        Chrome(DrawActivity a) { this.a = a; }
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) { return a.pickFile(cb, p); }
    }
}
