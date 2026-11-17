package io.github.tvoverlaysuite;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs while enabled in Accessibility: listens for the chosen remote button, shows/hides the
 * see-through ink layer over whatever is playing, and runs the phone server.
 *
 * v1.2 layers, each its own overlay window, bottom to top:
 *   night dimmer  <  photo (v1.3)  <  marker ink  <  pop-ups  <  laser pointer
 * Windows of the same type stack in the order they are added, so attach() re-adds the layers that
 * must stay above a newly added one. All window work happens on the main thread; all socket writes
 * go through InkServer.Client.send() (sender thread).
 */
public class MarkerService extends AccessibilityService implements InkServer.Handler, Extras.Sink {

    static final int PORT = 8765;
    static volatile MarkerService instance;

    private static final int RANK_DIM = 0, RANK_PHOTO = 1, RANK_INK = 2, RANK_POP = 3, RANK_PTR = 4;
    private static final int PHOTO_MAX_SIDE = 1600;
    private static final long SHORT_PRESS_MS = 600, LONG_PRESS_MS = 800, POINTER_IDLE_MS = 2000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private WindowManager wm;
    private InkView view;
    private Ink ink;
    private InkServer server;
    private boolean shown;
    private float k = 1f;                 // screen pixels per board unit
    private int screenW = 1920, screenH = 1080;

    private volatile int keyCode = KeyEvent.KEYCODE_INFO;
    private volatile int scanCode = -1;
    private volatile boolean learning;
    private Runnable onLearned;
    private Runnable onChange;
    private long keyDownAt = -1;
    private boolean longFired;

    // ---- window stack (main thread only) ----
    private static final class Layer {
        final View v; final WindowManager.LayoutParams lp; final int rank;
        Layer(View v, WindowManager.LayoutParams lp, int rank) { this.v = v; this.lp = lp; this.rank = rank; }
    }
    private final ArrayList<Layer> layers = new ArrayList<>();

    // ---- dimmer ----
    private volatile int dimLevel, dimWarmth;       // 0-90 %, 0-100
    private View dimView;
    private WindowManager.LayoutParams dimLp;
    private final AtomicBoolean dimPosted = new AtomicBoolean();

    // ---- pop-ups (main thread) ----
    private final ArrayDeque<PopupView>[] popQueue = newQueues();
    private final PopupView[] popShowing = new PopupView[3];
    private PopupView badge;

    // ---- laser pointer ----
    private PointerView pointer;
    private WindowManager.LayoutParams pointerLp;
    private volatile float laserX = -1, laserY, pingX, pingY;
    private volatile boolean laserHide, laserPing;
    private final AtomicBoolean laserPosted = new AtomicBoolean();
    private long laserLast;

    private boolean keepOn;
    private volatile String pairCode;

    // ---- photo (picture-in-picture) ----
    private PhotoView photoView;
    private WindowManager.LayoutParams photoLp;
    private int photoPos = 3, photoSize = 1;
    private volatile String photoState = "I 0";

    @SuppressWarnings("unchecked")
    private static ArrayDeque<PopupView>[] newQueues() {
        return new ArrayDeque[]{new ArrayDeque<PopupView>(), new ArrayDeque<PopupView>(), new ArrayDeque<PopupView>()};
    }

    @Override
    protected void onServiceConnected() {
        prefs = getSharedPreferences("marker", MODE_PRIVATE);
        prefs.edit().putBoolean("enabled_once", true).apply();   // lets UpdateReceiver switch it back on after updates
        keyCode = prefs.getInt("key", KeyEvent.KEYCODE_INFO);
        scanCode = prefs.getInt("scan", -1);
        dimLevel = Extras.clamp(prefs.getInt("dim", 0), 0, Extras.MAX_DIM);
        dimWarmth = Extras.clamp(prefs.getInt("warmth", 0), 0, 100);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        if (dm.widthPixels > 0) { k = dm.widthPixels / (float) Ink.W; screenW = dm.widthPixels; screenH = dm.heightPixels; }
        view = new InkView(this);
        ink = new Ink(view);
        view.setInk(ink);
        pairCode = pairingCode(this);
        server = new InkServer(PORT, this);
        server.setGate(() -> pairCode);
        server.start();
        instance = this;
        advertise();
        applyDim();          // restore the dimmer after a reboot
        notifyChange();
    }

    @Override
    public void onDestroy() {
        instance = null;
        main.removeCallbacksAndMessages(null);
        for (PopupView p : popShowing) if (p != null) p.finishNow();
        if (badge != null) badge.finishNow();
        if (wm != null) for (Layer l : new ArrayList<>(layers)) {
            try { wm.removeViewImmediate(l.v); } catch (RuntimeException ignored) { }
        }
        layers.clear();
        if (server != null) server.stop();
        stopAdvertising();
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    // ---------------- remote button ----------------

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int kc = e.getKeyCode();
        if (learning) {
            if (isReservedKey(kc)) return false;
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                keyCode = kc;
                scanCode = e.getScanCode();
                prefs.edit().putInt("key", keyCode).putInt("scan", scanCode).apply();
                learning = false;
                Runnable r = onLearned;
                if (r != null) main.post(r);
            }
            return true;
        }
        if (matches(e)) {
            // Short press (< 600 ms) toggles the marker on key-up; long press (>= 800 ms) turns the dimmer off.
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) {
                    keyDownAt = e.getEventTime();
                    longFired = false;
                } else if (!longFired && keyDownAt >= 0 && e.getEventTime() - keyDownAt >= LONG_PRESS_MS) {
                    longFired = true;   // remotes that auto-repeat: react while still held
                    main.post(this::dimmerOffByRemote);
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && keyDownAt >= 0) {
                long held = e.getEventTime() - keyDownAt;
                keyDownAt = -1;
                if (!longFired) {
                    if (held >= LONG_PRESS_MS) main.post(this::dimmerOffByRemote);
                    else if (held < SHORT_PRESS_MS) main.post(this::toggle);
                }
            }
            return true; // swallow both down and up so the button does nothing else
        }
        return false;
    }

    private boolean matches(KeyEvent e) {
        if (e.getKeyCode() != keyCode) return false;
        return keyCode != KeyEvent.KEYCODE_UNKNOWN || e.getScanCode() == scanCode;
    }

    /** Keys the setup screen needs for navigation; never learned. */
    static boolean isReservedKey(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE: case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return false;
        }
    }

    // ---------------- window stack ----------------

    private WindowManager.LayoutParams overlayLp(int w, int h, int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    /** Adds a window and re-adds every layer that must stay above it. Main thread. */
    private boolean attach(View v, WindowManager.LayoutParams lp, int rank) {
        ArrayList<Layer> above = new ArrayList<>();
        for (Layer l : layers) if (l.rank > rank) above.add(l);
        for (Layer l : above) {
            try { wm.removeViewImmediate(l.v); } catch (RuntimeException ignored) { }
            layers.remove(l);
        }
        boolean ok;
        try {
            wm.addView(v, lp);
            layers.add(new Layer(v, lp, rank));
            ok = true;
        } catch (RuntimeException e) {
            ok = false;
        }
        for (Layer l : above) {
            try { wm.addView(l.v, l.lp); layers.add(l); } catch (RuntimeException ignored) { }
        }
        return ok;
    }

    private void detach(View v) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i).v == v) {
                layers.remove(i);
                try { wm.removeView(v); } catch (RuntimeException ignored) { }
                return;
            }
        }
    }

    private boolean attached(View v) {
        for (Layer l : layers) if (l.v == v) return true;
        return false;
    }

    // ---------------- marker overlay ----------------

    void toggle() { setShown(!shown); }

    void setShown(boolean on) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> setShown(on)); return; }
        if (on == shown || wm == null || view == null) return;
        if (on) {
            view.setUrl(phoneUrl(this));
            view.setPhones(server.clientCount());
            WindowManager.LayoutParams lp = overlayLp(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP | Gravity.START);
            lp.setTitle("Marker overlay");
            if (keepOn) lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            if (!attach(view, lp, RANK_INK)) return;
            view.flashBadge();
        } else {
            detach(view);
        }
        shown = on;
        if (server != null) server.broadcast("v " + (shown ? 1 : 0), null);
        notifyChange();
    }

    boolean isShown() { return shown; }

    void clearDrawing() {
        ink.clear();
        server.broadcast("x", null);
    }

    // ---------------- keep the screen awake while a phone is connected ----------------

    /** FLAG_KEEP_SCREEN_ON on the marker and pointer windows while at least one phone is connected. Main thread. */
    private void updateKeepOn() {
        boolean want = server != null && server.clientCount() > 0;
        if (want == keepOn) return;
        keepOn = want;
        for (Layer l : layers) {
            if (l.rank != RANK_INK && l.rank != RANK_PTR && l.rank != RANK_PHOTO) continue;
            if (want) l.lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            else l.lp.flags &= ~WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            try { wm.updateViewLayout(l.v, l.lp); } catch (RuntimeException ignored) { }
        }
    }

    // ---------------- night dimmer ----------------

    int getDimLevel() { return dimLevel; }

    /** From the setup screen or the remote. Any thread. */
    void setDim(int level) { dim(level, -1); }

    @Override
    public void dim(int level, int warmth) {
        dimLevel = Extras.clamp(level, 0, Extras.MAX_DIM);
        if (warmth >= 0) dimWarmth = Extras.clamp(warmth, 0, 100);
        // a slider sends many values: apply only the latest one on the main thread
        if (dimPosted.compareAndSet(false, true)) main.post(() -> { dimPosted.set(false); applyDim(); saveAndEchoDim(); });
    }

    private int lastSavedDim = -1, lastSavedWarmth = -1;

    private void saveAndEchoDim() {
        int l = dimLevel, w = dimWarmth;
        if (server != null) server.broadcast("d " + l + " " + w, null);
        if (l != lastSavedDim || w != lastSavedWarmth) {
            prefs.edit().putInt("dim", l).putInt("warmth", w).apply();
            lastSavedDim = l;
            lastSavedWarmth = w;
            notifyChange();
        }
    }

    /** Main thread. Level 0 removes the window entirely. */
    private void applyDim() {
        int level = dimLevel;
        if (level <= 0) {
            if (dimView != null) { detach(dimView); dimView = null; }
            return;
        }
        float w = dimWarmth / 100f;
        // warmth folds an amber tint into the same black layer
        int color = Color.argb(Math.round(level * 2.55f), Math.round(110 * w), Math.round(52 * w), Math.round(6 * w));
        if (dimView == null) {
            dimView = new View(this);
            dimLp = overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.START);
            dimLp.setTitle("Marker dimmer");
            dimView.setBackgroundColor(color);
            if (!attach(dimView, dimLp, RANK_DIM)) { dimView = null; return; }
        } else {
            dimView.setBackgroundColor(color);
        }
    }

    /** Long press on the remote button: "clear the screen" — dimmer off and photo removed. */
    private void dimmerOffByRemote() {
        boolean hadDim = dimLevel > 0, hadPhoto = photoView != null;
        dimLevel = 0;
        applyDim();
        saveAndEchoDim();
        removePhoto();
        showBadge(hadPhoto && !hadDim ? "Photo removed" : hadPhoto ? "☾  Dimmer off · photo removed" : "☾  Dimmer off");
    }

    // ---------------- photo (picture-in-picture) ----------------

    /** Network thread: decode here (CPU work, never on the main thread), then show on the main thread. */
    @Override
    public void photo(int pos, int size, byte[] image) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > PHOTO_MAX_SIDE) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        final Bitmap b;
        try {
            b = BitmapFactory.decodeByteArray(image, 0, image.length, o);
        } catch (OutOfMemoryError e) {
            return;
        }
        if (b == null) return;
        main.post(() -> showPhoto(b, pos, size));
    }

    @Override public void photoMove(int pos, int size) { main.post(() -> movePhoto(pos, size)); }

    @Override public void photoHide() { main.post(this::removePhoto); }

    private void showPhoto(Bitmap b, int pos, int size) {
        if (wm == null) return;
        removePhoto();
        photoPos = pos;
        photoSize = size;
        PhotoView v = new PhotoView(this, b, k);
        WindowManager.LayoutParams lp = overlayLp(1, 1, Gravity.TOP | Gravity.START);
        lp.setTitle("Marker photo");
        if (keepOn) lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        placePhoto(lp, b, pos, size);
        if (!attach(v, lp, RANK_PHOTO)) return;
        photoView = v;
        photoLp = lp;
        v.fadeIn();
        photoChanged();
    }

    private void movePhoto(int pos, int size) {
        if (photoView == null) return;
        photoPos = pos;
        photoSize = size;
        placePhoto(photoLp, photoView.bitmap(), pos, size);
        try { wm.updateViewLayout(photoView, photoLp); } catch (RuntimeException ignored) { }
        photoChanged();
    }

    private void removePhoto() {
        if (photoView == null) return;
        detach(photoView);
        photoView.release();
        photoView = null;
        photoLp = null;
        photoChanged();
    }

    /** Corner placement; top-right sits a little lower so it clears a corner clock overlay, if one is shown. */
    private void placePhoto(WindowManager.LayoutParams lp, Bitmap b, int pos, int size) {
        int[] box = PhotoView.boxFor(b, size, screenW, screenH);
        lp.width = box[0];
        lp.height = box[1];
        int m = Math.round(48 * k);
        lp.x = m;
        lp.y = m;
        switch (pos) {
            case 0: lp.gravity = Gravity.TOP | Gravity.LEFT; break;
            case 1: lp.gravity = Gravity.TOP | Gravity.RIGHT; lp.y = Math.round(110 * k); break;
            case 2: lp.gravity = Gravity.BOTTOM | Gravity.LEFT; break;
            case 4: lp.gravity = Gravity.CENTER; lp.x = 0; lp.y = 0; break;
            default: lp.gravity = Gravity.BOTTOM | Gravity.RIGHT; break;
        }
    }

    private void photoChanged() {
        photoState = photoView == null ? "I 0"
                : "I 1 " + Extras.PHOTO_POS[photoPos] + " " + Extras.PHOTO_SIZE[photoSize];
        if (server != null) server.broadcast(photoState, null);
    }

    // ---------------- pop-ups ----------------

    @Override
    public void popup(int seconds, int pos, boolean sticker, String text) {
        main.post(() -> {
            if (wm == null) return;
            PopupView p = new PopupView(this, k, sticker ? PopupView.STICKER : PopupView.TEXT, text, pos, seconds * 1000L);
            ArrayDeque<PopupView> q = popQueue[pos];
            if (popShowing[pos] == null) startPopup(p);
            else if (q.size() < 5) q.add(p);
        });
    }

    private void startPopup(PopupView p) {
        int g;
        int y;
        if (p.pos == Extras.TOP) { g = Gravity.TOP | Gravity.CENTER_HORIZONTAL; y = Math.round(70 * k); }
        else if (p.pos == Extras.BOTTOM) { g = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL; y = Math.round(90 * k); }
        else { g = Gravity.CENTER; y = 0; }
        WindowManager.LayoutParams lp = overlayLp(p.winW, p.winH, g);
        lp.y = y;
        lp.setTitle("Marker pop-up");
        if (!attach(p, lp, RANK_POP)) { nextPopup(p.pos); return; }
        popShowing[p.pos] = p;
        p.begin(() -> {
            detach(p);
            popShowing[p.pos] = null;
            nextPopup(p.pos);
        });
    }

    private void nextPopup(int pos) {
        PopupView n = popQueue[pos].poll();
        if (n != null) startPopup(n);
    }

    private void showBadge(String label) {
        if (badge != null) { badge.finishNow(); detach(badge); badge = null; }
        PopupView b = new PopupView(this, k, PopupView.BADGE, label, Extras.TOP, 2000);
        WindowManager.LayoutParams lp = overlayLp(b.winW, b.winH, Gravity.TOP | Gravity.START);
        lp.x = Math.max(0, Math.round(48 * k - b.margin));   // pill's left edge at 48 px, like "Marker on"
        lp.y = Math.max(0, Math.round(40 * k - b.margin));
        lp.setTitle("Marker badge");
        if (!attach(b, lp, RANK_POP)) return;
        badge = b;
        b.begin(() -> { detach(b); if (badge == b) badge = null; });
    }

    // ---------------- laser pointer ----------------

    @Override public void laser(float x, float y) { laserX = x; laserY = y; postLaser(); }
    @Override public void laserHide() { laserHide = true; postLaser(); }
    @Override public void laserPing(float x, float y) { pingX = x; pingY = y; laserPing = true; postLaser(); }

    /** Network thread → at most one pending main-thread update; only the newest position matters. */
    private void postLaser() {
        if (laserPosted.compareAndSet(false, true)) main.post(applyLaser);
    }

    private final Runnable pointerIdle = () -> {
        if (SystemClock.uptimeMillis() - laserLast >= POINTER_IDLE_MS - 20) removePointer();
    };

    private final Runnable applyLaser = () -> {
        laserPosted.set(false);
        if (wm == null) return;
        if (laserHide) {
            laserHide = false;
            laserPing = false;
            laserX = -1;
            removePointer();
            return;
        }
        if (pointer == null) {
            pointer = new PointerView(this, k);
            pointerLp = overlayLp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Gravity.TOP | Gravity.START);
            pointerLp.setTitle("Marker pointer");
            if (keepOn) pointerLp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            if (!attach(pointer, pointerLp, RANK_PTR)) { pointer = null; return; }
        }
        if (laserX >= 0) pointer.moveTo(laserX, laserY);   // same position twice is a no-op
        if (laserPing) { laserPing = false; pointer.ping(pingX, pingY); }
        laserLast = SystemClock.uptimeMillis();
        main.removeCallbacks(pointerIdle);
        main.postDelayed(pointerIdle, POINTER_IDLE_MS);
    };

    private void removePointer() {
        main.removeCallbacks(pointerIdle);
        if (pointer != null) { detach(pointer); pointer = null; }
    }

    // ---------------- pairing ----------------

    /** The TV's pairing code, made once and kept until "New code". */
    static synchronized String pairingCode(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("marker", MODE_PRIVATE);
        String c = p.getString("pair", null);
        if (!Pairing.valid(c)) {
            c = Pairing.newCode();
            p.edit().putString("pair", c).apply();
        }
        return c;
    }

    /** Phone page address with the pairing code in the fragment (the fragment never leaves the phone). */
    static String phoneUrl(Context ctx) {
        String ip = Qr.ip();
        return ip == null ? "" : "http://" + ip + ":" + PORT + "/#k=" + pairingCode(ctx);
    }

    /** "New code" on the setup screen: every connected phone is dropped and must scan again. */
    static void newPairingCode(Context ctx) {
        String c = Pairing.newCode();
        synchronized (MarkerService.class) {
            ctx.getSharedPreferences("marker", MODE_PRIVATE).edit().putString("pair", c).commit();
        }
        MarkerService s = instance;
        if (s != null) {
            s.pairCode = c;
            if (s.server != null) s.server.dropAll();
            if (s.shown && s.view != null) s.view.setUrl(phoneUrl(s));
        }
    }

    // ---------------- settings used by the setup screen ----------------

    int getKeyCode() { return keyCode; }

    void startLearning(Runnable done) { onLearned = done; learning = true; }

    void cancelLearning() { learning = false; }

    void setOnChange(Runnable r) { onChange = r; }

    private void notifyChange() {
        Runnable r = onChange;
        if (r != null) main.post(r);
    }

    int phoneCount() { return server == null ? 0 : server.clientCount(); }

    // ---------------- phone app: keyboard connections and discovery (v1.5) ----------------

    static final String NSD_TYPE = "_tvoverlay._tcp";
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    /** Sends a typing message to every phone app. Any thread. */
    static void sendKb(String msg) {
        MarkerService s = instance;
        if (s != null && s.server != null) s.server.broadcastKb(msg);
    }

    /** v1.6: a trackpad app came to the front or left it: tell every phone app (and the setup screen). */
    static void padToPhones(String line) {
        sendKb(line);
        MarkerService s = instance;
        if (s != null) s.notifyChange();
    }

    /** The trackpad app in front on the TV, or null. */
    static String padFront() { return PadService.RELAY.frontName(); }

    /** Apps on the TV connected for the trackpad. */
    static int padApps() { return PadService.apps(); }

    /** Phone apps connected for typing. */
    static int kbPhones() {
        MarkerService s = instance;
        return s == null || s.server == null ? 0 : s.server.kbCount();
    }

    /** This install's id: lets the phone app find this TV again after its Wi-Fi address changes. */
    static synchronized String tvId(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("marker", MODE_PRIVATE);
        String id = p.getString("tvid", null);
        if (id == null || !id.matches("[A-Za-z0-9]{12}")) {
            id = Pairing.newCode() + Pairing.newCode();
            p.edit().putString("tvid", id).apply();
        }
        return id;
    }

    static String tvName(Context ctx) {
        String n = null;
        try { n = Settings.Global.getString(ctx.getContentResolver(), "device_name"); } catch (RuntimeException ignored) { }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n == null ? "TV" : n.trim();
    }

    /** Announces the server on the home network (mDNS) so the phone app finds the TV without its address. */
    private void advertise() {
        try {
            nsd = (NsdManager) getSystemService(NSD_SERVICE);
            NsdServiceInfo si = new NsdServiceInfo();
            si.setServiceName("TV Overlay Suite " + tvId(this).substring(0, 4));
            si.setServiceType(NSD_TYPE);
            si.setPort(PORT);
            si.setAttribute("id", tvId(this));
            si.setAttribute("v", "1.7");
            nsdListener = new NsdListener();
            nsd.registerService(si, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            nsdListener = null;
        }
    }

    private void stopAdvertising() {
        try { if (nsd != null && nsdListener != null) nsd.unregisterService(nsdListener); } catch (RuntimeException ignored) { }
        nsdListener = null;
    }

    /** Named class, not anonymous (d8 8.2.2 bug). Registration failures are harmless: the phone app also keeps the
     *  last address it used. */
    private static final class NsdListener implements NsdManager.RegistrationListener {
        @Override public void onRegistrationFailed(NsdServiceInfo si, int err) { }
        @Override public void onUnregistrationFailed(NsdServiceInfo si, int err) { }
        @Override public void onServiceRegistered(NsdServiceInfo si) { }
        @Override public void onServiceUnregistered(NsdServiceInfo si) { }
    }

    /** v1.7: each phone app's link as a camera relay endpoint (one object per connection). */
    private final java.util.concurrent.ConcurrentHashMap<InkServer.Client, CamRelay.Phone> camPhones = new java.util.concurrent.ConcurrentHashMap<>();

    private CamRelay.Phone camPhone(InkServer.Client c) {
        CamRelay.Phone p = camPhones.get(c);
        if (p == null) {
            p = c::send;
            CamRelay.Phone was = camPhones.putIfAbsent(c, p);
            if (was != null) p = was;
        }
        return p;
    }

    /** Phone apps ready to be the TV's camera (v1.7). */
    static int camPhones() { return PadService.CAMS.phones(); }

    private void kbOpened(InkServer.Client c) {
        c.send(TypeProto.id(tvId(this), tvName(this)));
        c.send(PadService.RELAY.frontLine());
        main.post(() -> { KeysIME k = KeysIME.instance; if (k != null) k.phonesChanged(); notifyChange(); });
    }

    /** Network thread: typing lines from a phone app go to the keyboard on the main thread; trackpad lines go
     *  straight to the trackpad app from this thread (no main-thread hop, for the lowest delay). */
    private void kbText(InkServer.Client c, String text) {
        for (String line : text.split("\n")) {
            if (line.startsWith("P ")) {
                PadService.RELAY.fromPhone(line.trim());
                continue;
            }
            if (line.startsWith("V ")) {   // v1.7 phone camera set-up
                PadService.CAMS.fromPhone(camPhone(c), line.trim());
                if (line.startsWith("V y")) notifyChange();
                continue;
            }
            TypeProto.Msg m = TypeProto.parse(line.trim());
            if (m == null) continue;
            main.post(() -> {
                KeysIME k = KeysIME.instance;
                if (k == null) {
                    if (m.kind == 'w') c.send(TypeProto.none("ime"));
                    return;
                }
                String reply = k.fromPhone(m);
                if (reply != null) c.send(reply);
            });
        }
    }

    // ---------------- InkServer.Handler (network threads) ----------------

    @Override
    public void onOpen(InkServer.Client c) {
        if (c.kb) { kbOpened(c); return; }
        String snap = ink.snapshot();
        c.send("x\n" + snap + "v " + (shown ? 1 : 0) + "\nd " + dimLevel + " " + dimWarmth + "\n" + photoState);
        phonesChanged();
    }

    @Override
    public void onText(InkServer.Client c, String text) {
        if (c.kb) { kbText(c, text); return; }
        String rest = Extras.route(text, this);   // dimmer / pop-up / laser lines
        if (rest.isEmpty()) return;
        int flags = ink.apply(c, rest);
        if (rest.startsWith("t ")) {
            // show/hide request: handled below, not relayed
        } else if ((flags & Ink.CHANGED) != 0) {
            server.broadcast(rest, c);
        }
        if ((flags & Ink.WANT_HIDE) != 0) setShown(false);
        else if ((flags & Ink.WANT_SHOW) != 0 && !shown) setShown(true);
    }

    @Override
    public void onClose(InkServer.Client c) {
        if (c.kb) {
            CamRelay.Phone p = camPhones.remove(c);
            if (p != null) PadService.CAMS.phoneGone(p);
            main.post(() -> { KeysIME k = KeysIME.instance; if (k != null) k.phonesChanged(); notifyChange(); });
            return;
        }
        String ended = ink.endOpenStrokes(c); // strokes cut off mid-line
        if (!ended.isEmpty()) server.broadcast(ended, c);
        phonesChanged();
    }

    private void phonesChanged() {
        main.post(() -> {
            int n = server.clientCount();
            view.setPhones(n);
            server.broadcast("n " + n, null);
            updateKeepOn();
            notifyChange();
        });
    }

    /** Phone page files, read from the APK once (they cannot change while the app runs). */
    private final java.util.concurrent.ConcurrentHashMap<String, byte[]> pageCache = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public byte[] page(String path) {
        String asset;
        switch (path) {
            case "/": case "/index.html": asset = "index.html"; break;
            case "/icon.png": asset = "icon.png"; break;
            case "/manifest.json": asset = "manifest.json"; break;
            default: return null;
        }
        byte[] cached = pageCache.get(asset);
        if (cached != null) return cached;
        try (InputStream in = getAssets().open(asset)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            byte[] data = b.toByteArray();
            pageCache.put(asset, data);
            return data;
        } catch (IOException e) {
            return null;
        }
    }
}
