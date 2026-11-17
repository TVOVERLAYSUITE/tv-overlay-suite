package io.github.tvoverlaysuite;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

/**
 * v1.6: the TV end of the phone trackpad, for apps on this TV. An app binds to this service and talks to it with
 * {@link Messenger} messages (Android's own app-to-app messaging: no network, and each side knows which app the
 * other is). See docs/trackpad.md.
 *
 * App -> suite (every message carries replyTo = the app's Messenger):
 *   what {@link #HELLO}   register
 *   what {@link #FRONT}   arg1 1 = this app is now in front (data "name": shown on the phone), 0 = it left the front
 *   what {@link #CAM}     v1.7, data "l" = a phone-camera line (see {@link CamProto}, docs/phone-camera.md)
 * Suite -> app:
 *   what {@link #LINE}    data "l" = one trackpad line: "m DX DY", "s DX DY", "c", "r", "d 1", "d 0", "k KEY"
 *   what {@link #CAM_LINE} v1.7, data "l" = a phone-camera line: "y N", "s SID SDP", "h SID STATE [MSG]", "x SID"
 */
public class PadService extends Service {
    public static final int HELLO = 1, FRONT = 2, CAM = 3, LINE = 10, CAM_LINE = 11;

    /** The front-app relay. One per process; the phone side (MarkerService) and this service share it. */
    static final PadRelay RELAY = new PadRelay(MarkerService::padToPhones);
    /** v1.7: the phone-camera relay. Only the app in front may open the camera. */
    static final CamRelay CAMS = new CamRelay(a -> a != null && RELAY.front() == a);

    private static final Map<IBinder, App> APPS = new HashMap<>();
    private static Context ctx;
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = getApplicationContext();
        messenger = new Messenger(new Handler(Looper.getMainLooper(), PadService::handle));
    }

    @Override
    public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    static int apps() {
        synchronized (APPS) { return APPS.size(); }
    }

    /** Main thread. */
    private static boolean handle(Message m) {
        Messenger reply = m.replyTo;
        if (reply == null) return true;
        App a = app(reply, m.sendingUid);
        if (a == null) return true;
        if (m.what == HELLO) {
            CAMS.appHello(a);
        } else if (m.what == FRONT) {
            Bundle b = m.peekData();
            String name = b == null ? "" : b.getString("name", "");
            if (name.length() > 40) name = name.substring(0, 40);
            RELAY.fromApp(a, m.arg1 == 1 ? "f 1 " + (name.isEmpty() ? "-" : TypeProto.b64(name)) : "f 0");
        } else if (m.what == CAM) {
            Bundle b = m.peekData();
            String line = b == null ? null : b.getString("l");
            if (line != null) CAMS.fromApp(a, line);
        }
        return true;
    }

    private static App app(Messenger reply, int uid) {
        IBinder key = reply.getBinder();
        synchronized (APPS) {
            App a = APPS.get(key);
            if (a != null) return a;
            a = new App(reply, uid);
            try {
                key.linkToDeath(a, 0);
            } catch (RemoteException e) {
                return null;   // already gone
            }
            APPS.put(key, a);
            return a;
        }
    }

    /** One app on the TV. Named static class (d8 8.2.2 bug with inner / anonymous classes). */
    static final class App implements PadRelay.App, CamRelay.App, IBinder.DeathRecipient {
        private final Messenger to;
        private final String pkg, label;
        private volatile boolean gone;

        App(Messenger to, int uid) {
            this.to = to;
            String p = "?", l = "A TV app";
            try {
                PackageManager pm = ctx.getPackageManager();
                String[] pk = pm.getPackagesForUid(uid);
                if (pk != null && pk.length > 0) {
                    p = pk[0];
                    ApplicationInfo ai = pm.getApplicationInfo(p, 0);
                    CharSequence cs = pm.getApplicationLabel(ai);
                    if (cs != null && cs.length() > 0) l = cs.toString();
                }
            } catch (RuntimeException | PackageManager.NameNotFoundException ignored) { }
            pkg = p;
            label = l.length() > 40 ? l.substring(0, 40) : l;
        }

        /** Any thread. Messenger.send is one-way: it never waits for the app. */
        @Override
        public void send(String line) { deliver(LINE, line); }

        @Override
        public void cam(String line) { deliver(CAM_LINE, line); }

        @Override public String pkg() { return pkg; }

        @Override public String label() { return label; }

        private void deliver(int what, String line) {
            if (gone) return;
            Message m = Message.obtain(null, what);
            Bundle b = new Bundle();
            b.putString("l", line);
            m.setData(b);
            try {
                to.send(m);
            } catch (RemoteException e) {
                binderDied();
            }
        }

        @Override
        public void binderDied() {
            if (gone) return;
            gone = true;
            synchronized (APPS) { APPS.remove(to.getBinder()); }
            try { to.getBinder().unlinkToDeath(this, 0); } catch (RuntimeException ignored) { }
            RELAY.closed(this);
            CAMS.appGone(this);
        }
    }
}
