package com.gathin.tvbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Settings over adb. The receiver requires android.permission.DUMP, which the adb shell holds and
 * ordinary apps can't get.
 * <pre>
 *   am broadcast -n com.gathin.tvbridge/.ConfigReceiver -a com.gathin.tvbridge.CONFIGURE \
 *       --es jellyfin_url http://192.168.1.10:8096 --es jellyfin_key &lt;api key&gt; [--es jellyfin_user &lt;user id&gt;]
 *   am broadcast -n com.gathin.tvbridge/.ConfigReceiver -a com.gathin.tvbridge.SYNC
 * </pre>
 */
public class ConfigReceiver extends BroadcastReceiver {

    static final String ACTION_CONFIGURE = "com.gathin.tvbridge.CONFIGURE";
    static final String ACTION_SYNC = "com.gathin.tvbridge.SYNC";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_CONFIGURE.equals(intent.getAction())) {
            JellyfinSync.configure(context, intent.getStringExtra("jellyfin_url"), intent.getStringExtra("jellyfin_key"),
                    intent.getStringExtra("jellyfin_user"));
        } else if (ACTION_SYNC.equals(intent.getAction())) {
            JellyfinSync.get(context).syncSoon(0);
        }
    }
}
