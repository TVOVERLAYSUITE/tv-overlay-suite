package io.github.tvoverlaysuite;

import android.app.Service;
import android.content.Intent;
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
 * Suite -> app:
 *   what {@link #LINE}    data "l" = one trackpad line: "m DX DY", "s DX DY", "c", "r", "d 1", "d 0", "k KEY"
 */
public class PadService extends Service {
    public static final int HELLO = 1, FRONT = 2, LINE = 10;

    /** The front-app relay. One per process; the phone side (MarkerService) and this service share it. */
    static final PadRelay RELAY = new PadRelay(MarkerService::padToPhones);

    private static final Map<IBinder, App> APPS = new HashMap<>();
    private Messenger messenger;

    @Override
    public void onCreate() {
        super.onCreate();
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
        App a = app(reply);
        if (a == null) return true;
        if (m.what == FRONT) {
            Bundle b = m.peekData();
            String name = b == null ? "" : b.getString("name", "");
            if (name.length() > 40) name = name.substring(0, 40);
            RELAY.fromApp(a, m.arg1 == 1 ? "f 1 " + (name.isEmpty() ? "-" : TypeProto.b64(name)) : "f 0");
        }
        return true;
    }

    private static App app(Messenger reply) {
        IBinder key = reply.getBinder();
        synchronized (APPS) {
            App a = APPS.get(key);
            if (a != null) return a;
            a = new App(reply);
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
    static final class App implements PadRelay.App, IBinder.DeathRecipient {
        private final Messenger to;
        private volatile boolean gone;

        App(Messenger to) { this.to = to; }

        /** Any thread. Messenger.send is one-way: it never waits for the app. */
        @Override
        public void send(String line) {
            if (gone) return;
            Message m = Message.obtain(null, LINE);
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
        }
    }
}
