package io.github.tvoverlaysuite;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * After an update the TV drops the service from the enabled list. If the app already holds WRITE_SECURE_SETTINGS
 * (granted by "Enable automatically"), switch it back on by itself: wait for the TV to finish processing the update
 * first (a too-early re-enable gets dropped), then switch and verify, with retries (see Enabler).
 */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        if (!Enabler.canWriteSecureSettings(app)) return;
        if (!app.getSharedPreferences("marker", Context.MODE_PRIVATE).getBoolean("enabled_once", false)) return;
        final PendingResult pr = goAsync();
        Thread t = new Thread(() -> {
            try {
                SystemClock.sleep(6000);
                Enabler.enableWithPermission(app, null);
            } finally {
                pr.finish();
            }
        }, "re-enable");
        t.start();
    }
}
