package com.tao7.nasremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "NasRemote";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "null";
        Log.i(TAG, "BootReceiver received action: " + action);

        SharedPreferences prefs = AccessibilityHelper.getDevicePrefs(context);
        boolean autostart = prefs.getBoolean("boot_autostart", true);
        boolean userDisabled = prefs.getBoolean("user_disabled", false);

        if (autostart && !userDisabled) {
            prefs.edit().putBoolean("service_enabled", true).apply();
            AccessibilityHelper.enableServiceViaSettings(context);
        }
    }
}
