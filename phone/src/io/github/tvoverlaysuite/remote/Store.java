package io.github.tvoverlaysuite.remote;

import android.content.Context;
import android.content.SharedPreferences;

/** The paired TV and the app's settings. */
final class Store {
    static final int PORT = 8765;
    private final SharedPreferences p;

    Store(Context c) { p = c.getSharedPreferences("remote", Context.MODE_PRIVATE); }

    boolean paired() { return !code().isEmpty() && (!host().isEmpty() || !tvId().isEmpty()); }

    String code() { return p.getString("code", ""); }
    String host() { return p.getString("host", ""); }
    String tvId() { return p.getString("id", ""); }
    String tvName() { return p.getString("name", ""); }

    void pair(String host, String code) {
        p.edit().putString("host", host).putString("code", code.toUpperCase(java.util.Locale.ROOT))
                .putString("id", "").putString("name", "").putBoolean("refused", false).apply();
    }

    void setHost(String host) { p.edit().putString("host", host).apply(); }

    void setTv(String id, String name) { p.edit().putString("id", id).putString("name", name).apply(); }

    /** The TV refused the code (it was changed on the TV): stop retrying until the phone is paired again. */
    void setRefused(boolean r) { p.edit().putBoolean("refused", r).apply(); }
    boolean refused() { return p.getBoolean("refused", false); }

    void forget() { p.edit().remove("host").remove("code").remove("id").remove("name").remove("refused").apply(); }

    /** Pop the typing bar up by itself when the TV keyboard comes up. */
    boolean autoPop() { return p.getBoolean("auto", true); }
    void setAutoPop(boolean on) { p.edit().putBoolean("auto", on).apply(); }

    /** v1.6: open the trackpad by itself when a trackpad app comes to the front on the TV. */
    boolean padAutoPop() { return p.getBoolean("padAuto", true); }
    void setPadAutoPop(boolean on) { p.edit().putBoolean("padAuto", on).apply(); }

    /** Trackpad speed, 1 (slow) to 5 (fast). */
    int padSpeed() { return Math.max(1, Math.min(5, p.getInt("padSpeed", 3))); }
    void setPadSpeed(int v) { p.edit().putInt("padSpeed", Math.max(1, Math.min(5, v))).apply(); }

    /** Name of the last app the trackpad drove, for "Open … on the TV". */
    String padApp() { return p.getString("padApp", ""); }
    void setPadApp(String n) { if (n != null && !n.isEmpty() && !n.equals(padApp())) p.edit().putString("padApp", n).apply(); }

    /** Close the bar after Search / Go / Enter in one-line boxes. */
    boolean closeOnEnter() { return p.getBoolean("closeEnter", true); }
    void setCloseOnEnter(boolean on) { p.edit().putBoolean("closeEnter", on).apply(); }
}
