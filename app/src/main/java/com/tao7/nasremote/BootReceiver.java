package com.tao7.nasremote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean autostart = prefs.getBoolean("boot_autostart", true);
            if (autostart) {
                prefs.edit().putBoolean("service_enabled", true).apply();
                AccessibilityHelper.enableServiceViaSettings(context);
            }
        }
    }
}
