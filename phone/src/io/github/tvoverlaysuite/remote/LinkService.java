package io.github.tvoverlaysuite.remote;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.tvoverlaysuite.PadProto;
import io.github.tvoverlaysuite.TypeProto;

/**
 * Keeps one small connection to the TV (path /kb) while the phone screen is on, and pops up the typing bar when the
 * TV says a text box opened. Runs as a foreground service ("connected device") so Android does not cut it off.
 * The link is dropped a minute after the screen goes off and comes back the moment it turns on.
 */
public class LinkService extends Service {

    static final String ACTION_WANT = "io.github.tvoverlaysuite.remote.WANT";
    static final String ACTION_KICK = "io.github.tvoverlaysuite.remote.KICK";
    static final String ACTION_STOP = "io.github.tvoverlaysuite.remote.STOP";

    static final int S_UNPAIRED = 0, S_LOOKING = 1, S_CONNECTED = 2, S_REFUSED = 3, S_NOT_FOUND = 4, S_ASLEEP = 5;

    static volatile LinkService instance;
    static volatile int status = S_UNPAIRED;
    /** MainActivity's refresh hook (main thread). */
    static volatile Runnable onStatus;

    private static final int NOTE_LINK = 1, NOTE_TYPE = 2;
    private static final long SCREEN_OFF_DROP_MS = 60_000, PING_MS = 20_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService tx = Executors.newSingleThreadExecutor(r -> new Thread(r, "kb-send"));
    private Store store;
    private TypingBar bar;
    private volatile boolean running;
    private volatile WsClient ws;
    private final Object wake = new Object();
    private volatile boolean screenOn = true;
    private volatile long screenOffAt;
    private Thread loop;
    private ScreenReceiver screenRx;
    private NetCallback net;

    /** Last box the TV reported, for TypeActivity. Main thread. */
    TypeProto.Msg field;
    boolean fieldOpen;
    /** Set when the user asked to type (tile, notification, app): the next "T o" / "T n" answers that. */
    private boolean wanted;
    /** TypeActivity, when it is the one showing the panel. Main thread. */
    TypeActivity activityPanel;

    /** v1.6 trackpad: is a trackpad app in front on the TV, and which. Main thread. */
    boolean padOn;
    String padName = "";
    private long padOffAt;
    /** The first "P f" after (re)connecting is the TV's current state, not a change: it never pops the trackpad. */
    private boolean padFresh;
    /** PadActivity while it is on screen. Main thread. */
    PadActivity padActivity;

    static void start(Context c) {
        Intent i = new Intent(c, LinkService.class).setAction(ACTION_KICK);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (RuntimeException ignored) { }   // not allowed from the background right now: next app open
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        store = new Store(this);
        bar = new TypingBar(this, this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel link = new NotificationChannel("link", "Connection to the TV", NotificationManager.IMPORTANCE_MIN);
            link.setShowBadge(false);
            nm.createNotificationChannel(link);
            NotificationChannel type = new NotificationChannel("type", "Text box opened on the TV", NotificationManager.IMPORTANCE_HIGH);
            type.setShowBadge(false);
            nm.createNotificationChannel(type);
        }
        goForeground();
        instance = this;
        PowerManager pm = getSystemService(PowerManager.class);
        screenOn = pm == null || pm.isInteractive();
        screenRx = new ScreenReceiver(this);
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenRx, f);
        try {
            net = new NetCallback(this);
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(net);
        } catch (RuntimeException e) { net = null; }
        running = true;
        loop = new Thread(this::linkLoop, "kb-link");
        loop.start();
        main.postDelayed(this::pingTick, PING_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        String a = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(a)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_WANT.equals(a)) want();
        kick();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenRx); } catch (RuntimeException ignored) { }
        try { if (net != null) getSystemService(ConnectivityManager.class).unregisterNetworkCallback(net); } catch (RuntimeException ignored) { }
        WsClient c = ws;
        if (c != null) c.close();
        kick();
        bar.hide();
        tx.shutdownNow();
        setStatus(S_UNPAIRED);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- notification

    private void goForeground() {
        Notification n = linkNote();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_LINK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NOTE_LINK, n);
        } catch (RuntimeException e) {
            stopSelf();
        }
    }

    private Notification linkNote() {
        String name = store.tvName().isEmpty() ? "your TV" : store.tvName();
        String text;
        switch (status) {
            case S_CONNECTED: text = "Connected to " + name; break;
            case S_REFUSED: text = "The TV has a new pairing code. Open the app to pair again."; break;
            case S_NOT_FOUND: text = "Can't find " + name + " on this Wi-Fi"; break;
            case S_ASLEEP: text = "Paused while the screen is off"; break;
            case S_UNPAIRED: text = "Not paired with a TV"; break;
            default: text = "Looking for " + name + "…";
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent type = PendingIntent.getActivity(this, 1,
                new Intent(this, TypeActivity.class).putExtra(TypeActivity.EXTRA_WANT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "link") : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle("TV Overlay Suite")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false);
        if (status == S_CONNECTED) {
            b.addAction(new Notification.Action.Builder(null, "Type on TV", type).build());
            PendingIntent pad = PendingIntent.getActivity(this, 4,
                    new Intent(this, PadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "Trackpad", pad).build());
        }
        return b.build();
    }

    private void setStatus(int s) {
        if (status == s) return;
        status = s;
        main.post(() -> {
            if (running) {
                try { getSystemService(NotificationManager.class).notify(NOTE_LINK, linkNote()); } catch (RuntimeException ignored) { }
            }
            Runnable r = onStatus;
            if (r != null) r.run();
            if (s != S_CONNECTED) { fieldOpen = false; bar.hide(); padState(false, padName); }
        });
    }

    // ---------------------------------------------------------------- link loop (own thread)

    void kick() {
        synchronized (wake) { wake.notifyAll(); }
    }

    private void sleep(long ms) {
        synchronized (wake) {
            try { wake.wait(ms); } catch (InterruptedException ignored) { }
        }
    }

    private boolean linkWanted() {
        return screenOn || System.currentTimeMillis() - screenOffAt < SCREEN_OFF_DROP_MS;
    }

    private void linkLoop() {
        long backoff = 1000;
        while (running) {
            if (!store.paired()) { setStatus(S_UNPAIRED); sleep(60_000); continue; }
            if (store.refused()) { setStatus(S_REFUSED); sleep(60_000); continue; }
            if (!linkWanted()) { setStatus(S_ASLEEP); sleep(60_000); continue; }
            if (status != S_CONNECTED && status != S_NOT_FOUND) setStatus(S_LOOKING);

            WsClient c = open(store.host());
            if (c == null && !store.tvId().isEmpty()) {
                // the TV's Wi-Fi address may have changed: find it by its id
                String h = new Finder(this).hostOf(store.tvId(), 4000);
                if (h != null && !h.equals(store.host())) {
                    store.setHost(h);
                    c = open(h);
                }
            }
            if (c == null) {
                setStatus(S_NOT_FOUND);
                sleep(backoff);
                backoff = Math.min(backoff * 2, 15_000);
                continue;
            }
            String reply;
            try {
                c.send("k " + store.code());
                reply = c.readText();
            } catch (IOException e) {
                c.close();
                sleep(backoff);
                continue;
            }
            if ("K bad".equals(reply)) { c.close(); store.setRefused(true); continue; }
            if (!"K ok".equals(reply)) { c.close(); sleep(60_000); continue; }   // "K wait": locked out a minute
            backoff = 1000;
            main.post(() -> padFresh = true);
            c.setReadTimeout((int) (PING_MS * 2 + 5000));
            ws = c;
            setStatus(S_CONNECTED);
            try {
                while (running && c.isOpen()) {
                    String t = c.readText();
                    for (String line : t.split("\n")) {
                        if (line.startsWith("P ")) {
                            PadProto.Front f = PadProto.parseFront(line.trim());
                            if (f != null) main.post(() -> padFront(f));
                            continue;
                        }
                        TypeProto.Msg m = TypeProto.parse(line.trim());
                        if (m != null) main.post(() -> fromTv(m));
                    }
                }
            } catch (IOException ignored) {
                // dropped: TV off, Wi-Fi change, screen-off timeout
            }
            ws = null;
            c.close();
            if (running) setStatus(S_LOOKING);
            sleep(300);
        }
    }

    private static WsClient open(String host) {
        if (host == null || host.isEmpty()) return null;
        try {
            return WsClient.connect(host, Store.PORT, "/kb", 2500);
        } catch (IOException e) {
            return null;
        }
    }

    private void pingTick() {
        WsClient c = ws;
        if (c != null) tx.execute(c::ping);
        if (!screenOn && c != null && System.currentTimeMillis() - screenOffAt >= SCREEN_OFF_DROP_MS) {
            c.close();   // save battery while the phone sleeps
        }
        if (running) main.postDelayed(this::pingTick, PING_MS);
    }

    void screen(boolean on) {
        screenOn = on;
        if (!on) screenOffAt = System.currentTimeMillis();
        else kick();
    }

    // ---------------------------------------------------------------- typing (main thread)

    /** Sends a line to the TV off the main thread. */
    void sendLine(String line) {
        WsClient c = ws;
        if (c == null) return;
        try { tx.execute(() -> c.send(line)); } catch (RuntimeException ignored) { }
    }

    boolean connected() { return ws != null; }

    /** The user wants to type now: the TV answers with its open box, or says there is none. */
    void want() {
        if (ws == null) {
            Toast.makeText(this, status == S_REFUSED ? "Pair with the TV again (the code changed)"
                    : "Not connected to the TV yet", Toast.LENGTH_SHORT).show();
            kick();
            return;
        }
        wanted = true;
        sendLine(TypeProto.want());
    }

    private void fromTv(TypeProto.Msg m) {
        switch (m.kind) {
            case 'i':
                store.setTv(m.id, m.name);
                try { getSystemService(NotificationManager.class).notify(NOTE_LINK, linkNote()); } catch (RuntimeException ignored) { }
                break;
            case 'o': {
                field = m;
                fieldOpen = true;
                boolean asked = wanted || !m.auto;
                wanted = false;
                if (!asked && !store.autoPop()) break;
                if (activityPanel != null) { activityPanel.open(m); break; }
                if (screenOn && !locked() && bar.show(m)) break;
                headsUp();
                break;
            }
            case 's':
                if (field != null && field.field == m.field) { field.text = m.text; field.selS = m.selS; field.selE = m.selE; field.seq = m.seq; }
                bar.state(m);
                if (activityPanel != null) activityPanel.state(m);
                break;
            case 'c':
                if (field != null && field.field == m.field) fieldOpen = false;
                bar.fieldClosed(m.field);
                if (activityPanel != null) activityPanel.fieldClosed(m.field);
                try { getSystemService(NotificationManager.class).cancel(NOTE_TYPE); } catch (RuntimeException ignored) { }
                break;
            case 'n':
                if (!wanted) break;
                wanted = false;
                String msg = "ime".equals(m.why)
                        ? "Set TV Overlay Suite as the TV's keyboard first (TV app → Use as TV keyboard)"
                        : "Select a text box on the TV first";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                if (activityPanel != null) activityPanel.noBox(msg);
                break;
            default:
        }
    }

    private boolean locked() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    /** No floating window possible (locked phone, or the permission is off): a notification to tap instead. */
    private void headsUp() {
        PendingIntent pi = PendingIntent.getActivity(this, 2,
                new Intent(this, TypeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, "type") : new Notification.Builder(this);
        String what = field != null && !field.hint.isEmpty() ? field.hint : "A text box";
        b.setSmallIcon(R.drawable.ic_note)
                .setContentTitle("Type on the TV")
                .setContentText(what + " is open on the TV. Tap to type.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setTimeoutAfter(120_000);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_HIGH);
        try { getSystemService(NotificationManager.class).notify(NOTE_TYPE, b.build()); } catch (RuntimeException ignored) { }
    }

    void cancelHeadsUp() {
        try { getSystemService(NotificationManager.class).cancel(NOTE_TYPE); } catch (RuntimeException ignored) { }
    }

    void closeBar() { bar.hide(); }

    // ---------------------------------------------------------------- trackpad (v1.6)

    private final Object padLock = new Object();
    private float padMx, padMy, padSx, padSy;
    private final ArrayList<String> padQueue = new ArrayList<>();
    private boolean padSending;

    /** Pointer movement from the trackpad. Any thread; never blocks. Moves that pile up while the network is busy are
     *  added together into one message, so the pointer never lags behind a backlog. */
    void padMove(float dx, float dy) {
        if (ws == null) return;
        synchronized (padLock) { padMx += dx; padMy += dy; padSchedule(); }
    }

    void padScroll(float dx, float dy) {
        if (ws == null) return;
        synchronized (padLock) { padSx += dx; padSy += dy; padSchedule(); }
    }

    /** A click, menu, drag or key: sent in order after the movement before it. */
    void padEvent(String line) {
        if (ws == null) return;
        synchronized (padLock) { padFlushSums(); padQueue.add(line); padSchedule(); }
    }

    private void padFlushSums() {   // holds padLock
        if (padMx != 0 || padMy != 0) { padQueue.add(PadProto.move(padMx, padMy)); padMx = padMy = 0; }
        if (padSx != 0 || padSy != 0) { padQueue.add(PadProto.scroll(padSx, padSy)); padSx = padSy = 0; }
    }

    private void padSchedule() {   // holds padLock
        if (padSending) return;
        padSending = true;
        try { tx.execute(this::padDrain); } catch (RuntimeException e) { padSending = false; }
    }

    /** kb-send thread: sends everything waiting as one frame, until nothing is left. */
    private void padDrain() {
        while (true) {
            String out;
            synchronized (padLock) {
                padFlushSums();
                if (padQueue.isEmpty()) { padSending = false; return; }
                out = String.join("\n", padQueue);
                padQueue.clear();
            }
            WsClient c = ws;
            if (c != null) c.send(out);
        }
    }

    private void padFront(PadProto.Front f) {
        boolean live = !padFresh;
        padFresh = false;
        boolean was = padOn;
        padState(f.on, f.on ? f.name : padName);
        if (!f.on || was || !live) return;
        // a trackpad app just came to the front on the TV
        if (padActivity != null || !store.padAutoPop() || !screenOn || locked() || bar.isShown() || activityPanel != null) return;
        if (System.currentTimeMillis() - padOffAt < 1500) return;   // the app only blinked (its own video player)
        if (!TypingBar.allowed(this)) return;   // starting a screen from the background needs "Display over other apps"
        try {
            startActivity(new Intent(this, PadActivity.class).putExtra(PadActivity.EXTRA_AUTO, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
    }

    private void padState(boolean on, String name) {
        if (padOn && !on) padOffAt = System.currentTimeMillis();
        padOn = on;
        padName = name == null ? "" : name;
        if (on) store.setPadApp(padName);
        if (padActivity != null) padActivity.front(on, padName);
    }

    // ---------------------------------------------------------------- receivers (named classes: d8 bug)

    static final class ScreenReceiver extends BroadcastReceiver {
        private final LinkService s;
        ScreenReceiver(LinkService s) { this.s = s; }
        @Override public void onReceive(Context c, Intent i) { s.screen(Intent.ACTION_SCREEN_ON.equals(i.getAction())); }
    }

    static final class NetCallback extends ConnectivityManager.NetworkCallback {
        private final LinkService s;
        NetCallback(LinkService s) { this.s = s; }
        @Override public void onAvailable(Network n) { s.kick(); }
        @Override public void onLost(Network n) { WsClient c = s.ws; if (c != null) c.close(); }
    }
}
