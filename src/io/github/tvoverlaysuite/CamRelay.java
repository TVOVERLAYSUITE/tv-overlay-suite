package io.github.tvoverlaysuite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * v1.7: carries the phone-camera set-up between TV apps and phone apps. Plain Java (the JVM tests drive it with fake
 * apps and phones); {@link PadService} and {@link MarkerService} wire it to the real ones.
 *
 * Rules:
 *  - only the app in front on the TV (the trackpad's front app) may open the phone camera;
 *  - a session belongs to the app that opened it and the phone it went to: nobody else can feed it or close it;
 *  - the newest phone app that said it can be a camera gets new sessions;
 *  - when an app or a phone goes away, its sessions end and the other side is told.
 */
public final class CamRelay {

    /** A TV app. cam() gets a line without the "V " prefix; it must not block. */
    public interface App {
        void cam(String line);
        /** Package and label, shown on the phone before it lets the app use the camera. */
        String pkg();
        String label();
    }

    /** A phone app. send() gets a full "V ..." line; it must not block. */
    public interface Phone {
        void send(String line);
    }

    public interface FrontCheck {
        boolean isFront(App a);
    }

    static final int MAX_SESSIONS = 4;

    private static final class Sess {
        final App app;
        final Phone phone;
        Sess(App a, Phone p) { app = a; phone = p; }
    }

    private final FrontCheck front;
    private final List<Phone> phones = new ArrayList<>();   // ready to be a camera, newest last
    private final List<App> apps = new ArrayList<>();       // said hello: told about phones
    private final Map<String, Sess> sessions = new HashMap<>();

    public CamRelay(FrontCheck front) { this.front = front; }

    // ---------------- apps ----------------

    /** An app connected (HELLO). Any thread. */
    public void appHello(App a) {
        int n;
        synchronized (this) {
            if (!apps.contains(a)) apps.add(a);
            n = phones.size();
        }
        a.cam(CamProto.count(n));
    }

    /** An app went away. Its sessions end. Any thread. */
    public void appGone(App a) {
        List<String[]> tell = new ArrayList<>();
        List<Phone> to = new ArrayList<>();
        synchronized (this) {
            apps.remove(a);
            for (Iterator<Map.Entry<String, Sess>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Sess> e = it.next();
                if (e.getValue().app == a) { to.add(e.getValue().phone); tell.add(new String[]{e.getKey()}); it.remove(); }
            }
        }
        for (int i = 0; i < to.size(); i++) to.get(i).send("V x " + tell.get(i)[0]);
    }

    /** A line from an app. Returns false when it was refused or malformed. Any thread. */
    public boolean fromApp(App a, String line) {
        CamProto.Msg m = CamProto.parseApp(line);
        if (m == null) return false;
        Phone p;
        String err = null;
        synchronized (this) {
            if (!apps.contains(a)) apps.add(a);
            Sess s = sessions.get(m.sid);
            if (m.kind == 'o') {
                if (s != null) return false;                                   // the id is taken
                if (!front.isFront(a)) err = "Only the app on screen can use the phone camera";
                else if (phones.isEmpty()) err = "No phone ready: open TV Overlay Suite on the phone";
                else if (count(a) >= MAX_SESSIONS) err = "Too many phone camera sessions";
                if (err == null) {
                    p = phones.get(phones.size() - 1);
                    sessions.put(m.sid, new Sess(a, p));
                } else {
                    p = null;
                }
            } else {
                if (s == null || s.app != a) return false;
                p = s.phone;
                if (m.kind == 'x') sessions.remove(m.sid);
            }
        }
        if (err != null) { a.cam(CamProto.appError(m.sid, err)); return false; }
        switch (m.kind) {
            case 'o': p.send(CamProto.open(m.sid, m.media, m.facing, a.pkg(), a.label())); break;
            case 's': p.send("V s " + m.sid + " " + m.sdp); break;
            case 'f': p.send("V f " + m.sid + " " + m.facing); break;
            case 'x': p.send("V x " + m.sid); break;
            default: return false;
        }
        return true;
    }

    private int count(App a) {   // holds this
        int n = 0;
        for (Sess s : sessions.values()) if (s.app == a) n++;
        return n;
    }

    // ---------------- phones ----------------

    /** A line from a phone app ("V ..."). Any thread (the phone's network thread). */
    public boolean fromPhone(Phone p, String line) {
        CamProto.Msg m = CamProto.parsePhone(line);
        if (m == null) return false;
        if (m.kind == 'y') { phoneReady(p); return true; }
        App a;
        synchronized (this) {
            Sess s = sessions.get(m.sid);
            if (s == null || s.phone != p) a = null;
            else {
                a = s.app;
                if (m.kind == 'x') sessions.remove(m.sid);
            }
        }
        if (a == null) {
            if (m.kind != 'x') p.send("V x " + m.sid);   // a session the TV no longer knows: stop it on the phone
            return false;
        }
        switch (m.kind) {
            case 's': a.cam("s " + m.sid + " " + m.sdp); break;
            case 'h': a.cam("h " + m.sid + " " + m.state + (m.msg.isEmpty() ? "" : " " + m.msg)); break;
            case 'x': a.cam("x " + m.sid); break;
            default: return false;
        }
        return true;
    }

    private void phoneReady(Phone p) {
        List<App> tell;
        int n;
        synchronized (this) {
            phones.remove(p);
            phones.add(p);
            n = phones.size();
            tell = new ArrayList<>(apps);
        }
        for (App a : tell) a.cam(CamProto.count(n));
    }

    /** A phone app disconnected. Its sessions end. Any thread. */
    public void phoneGone(Phone p) {
        List<App> tell;
        List<App> ended = new ArrayList<>();
        List<String> sids = new ArrayList<>();
        int n;
        synchronized (this) {
            if (!phones.remove(p) && !hasPhone(p)) return;
            for (Iterator<Map.Entry<String, Sess>> it = sessions.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Sess> e = it.next();
                if (e.getValue().phone == p) { ended.add(e.getValue().app); sids.add(e.getKey()); it.remove(); }
            }
            n = phones.size();
            tell = new ArrayList<>(apps);
        }
        for (int i = 0; i < ended.size(); i++) ended.get(i).cam("x " + sids.get(i));
        for (App a : tell) a.cam(CamProto.count(n));
    }

    private boolean hasPhone(Phone p) {   // holds this
        for (Sess s : sessions.values()) if (s.phone == p) return true;
        return false;
    }

    public synchronized int phones() { return phones.size(); }

    public synchronized int sessions() { return sessions.size(); }
}
