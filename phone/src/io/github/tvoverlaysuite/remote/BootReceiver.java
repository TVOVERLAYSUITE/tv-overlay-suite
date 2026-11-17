package io.github.tvoverlaysuite.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the TV link after the phone restarts or the app is updated (if a TV is paired). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (new Store(c).paired()) LinkService.start(c);
    }
}
