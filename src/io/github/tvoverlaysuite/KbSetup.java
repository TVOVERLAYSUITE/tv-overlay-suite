package io.github.tvoverlaysuite;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** v1.5: makes the suite's keyboard the TV's keyboard (and back). */
final class KbSetup {
    private KbSetup() { }

    static String imeId(Context ctx) {
        return new ComponentName(ctx, KeysIME.class).flattenToShortString();
    }

    static boolean isCurrent(Context ctx) {
        String cur = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return cur != null && ComponentName.unflattenFromString(cur) != null
                && ComponentName.unflattenFromString(cur).equals(new ComponentName(ctx, KeysIME.class));
    }

    /** With WRITE_SECURE_SETTINGS (granted by "Enable automatically"): switch directly, remembering the old keyboard.
     *  Without it: open the TV's keyboard settings. Returns true when the switch was made here. */
    static boolean useSuiteKeyboard(Context ctx) { return useSuiteKeyboard(ctx, true); }

    static boolean useSuiteKeyboard(Context ctx, boolean openSettings) {
        String me = imeId(ctx);
        if (Enabler.canWriteSecureSettings(ctx)) {
            try {
                android.content.ContentResolver cr = ctx.getContentResolver();
                String old = Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD);
                if (old != null && !old.isEmpty() && !isCurrent(ctx)) {
                    ctx.getSharedPreferences("marker", Context.MODE_PRIVATE).edit().putString("prev_ime", old).apply();
                }
                String en = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS);
                if (en == null || en.isEmpty()) en = me;
                else if (!(":" + en + ":").contains(":" + me + ":") && !en.contains(me + ";")) en = en + ":" + me;
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, en);
                Settings.Secure.putString(cr, Settings.Secure.DEFAULT_INPUT_METHOD, me);
                return isCurrent(ctx);
            } catch (RuntimeException ignored) { }
        }
        if (!openSettings) return false;
        try {
            ctx.startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) { }
        return false;
    }
}
