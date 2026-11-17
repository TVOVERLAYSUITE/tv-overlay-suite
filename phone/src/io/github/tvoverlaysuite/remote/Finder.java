package io.github.tvoverlaysuite.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds TVs running TV Overlay Suite 1.5+ on the home Wi-Fi (they announce themselves over mDNS as
 * {@code _tvoverlay._tcp}). Blocking: call from a background thread.
 */
final class Finder {
    static final String TYPE = "_tvoverlay._tcp";

    static final class Tv {
        final String name, id, host;
        Tv(String name, String id, String host) { this.name = name; this.id = id; this.host = host; }
    }

    private final NsdManager nsd;
    private final ArrayList<Tv> found = new ArrayList<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private boolean resolving;
    private String wantId;
    private final Object lock = new Object();

    Finder(Context c) { nsd = (NsdManager) c.getApplicationContext().getSystemService(Context.NSD_SERVICE); }

    /** Every TV seen within {@code ms}. */
    List<Tv> scan(int ms) { return run(null, ms); }

    /** The address of the TV with this install id, or null if it did not answer within {@code ms}. */
    String hostOf(String id, int ms) {
        List<Tv> l = run(id, ms);
        for (Tv t : l) if (t.id.equals(id)) return t.host;
        return null;
    }

    private List<Tv> run(String id, int ms) {
        synchronized (lock) { found.clear(); toResolve.clear(); resolving = false; wantId = id; }
        Discovery d = new Discovery(this);
        try {
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, d);
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
        long end = System.currentTimeMillis() + ms;
        synchronized (lock) {
            while (System.currentTimeMillis() < end) {
                if (id != null && hasId(id)) break;
                try { lock.wait(Math.max(1, end - System.currentTimeMillis())); } catch (InterruptedException e) { break; }
            }
        }
        try { nsd.stopServiceDiscovery(d); } catch (RuntimeException ignored) { }
        synchronized (lock) { return new ArrayList<>(found); }
    }

    private boolean hasId(String id) {
        for (Tv t : found) if (t.id.equals(id)) return true;
        return false;
    }

    /** Resolves one service at a time (older Android allows only one resolve in flight). */
    private void nextResolve() {
        NsdServiceInfo si;
        synchronized (lock) {
            if (resolving || toResolve.isEmpty()) return;
            si = toResolve.poll();
            resolving = true;
        }
        try {
            nsd.resolveService(si, new Resolve(this));
        } catch (RuntimeException e) {
            synchronized (lock) { resolving = false; }
            nextResolve();
        }
    }

    private void resolved(NsdServiceInfo si) {
        InetAddress a = si.getHost();
        String id = "";
        try {
            Map<String, byte[]> attrs = si.getAttributes();
            byte[] v = attrs == null ? null : attrs.get("id");
            if (v != null) id = new String(v, StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) { }
        if (a != null && a.getAddress().length == 4) {   // the TV's IPv4 address
            String name = si.getServiceName();
            synchronized (lock) {
                boolean dup = false;
                for (Tv t : found) if (t.host.equals(a.getHostAddress())) dup = true;
                if (!dup) found.add(new Tv(name, id, a.getHostAddress()));
                lock.notifyAll();
            }
        }
        synchronized (lock) { resolving = false; }
        nextResolve();
    }

    // Static named classes: the no-Gradle build's d8 (8.2.2) crashes on anonymous and on inner (non-static) classes.
    static final class Discovery implements NsdManager.DiscoveryListener {
        private final Finder f;
        Discovery(Finder f) { this.f = f; }
        @Override public void onStartDiscoveryFailed(String t, int e) { synchronized (f.lock) { f.lock.notifyAll(); } }
        @Override public void onStopDiscoveryFailed(String t, int e) { }
        @Override public void onDiscoveryStarted(String t) { }
        @Override public void onDiscoveryStopped(String t) { }
        @Override public void onServiceLost(NsdServiceInfo si) { }
        @Override public void onServiceFound(NsdServiceInfo si) {
            synchronized (f.lock) { f.toResolve.add(si); }
            f.nextResolve();
        }
    }

    static final class Resolve implements NsdManager.ResolveListener {
        private final Finder f;
        Resolve(Finder f) { this.f = f; }
        @Override public void onResolveFailed(NsdServiceInfo si, int e) {
            synchronized (f.lock) { f.resolving = false; }
            f.nextResolve();
        }
        @Override public void onServiceResolved(NsdServiceInfo si) { f.resolved(si); }
    }
}
