package com.tao7.nasremote;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

public class RevivalSyncService extends Service {
    private static final String TAG = "NasRemoteSync";
    private SyncAdapter adapter;

    @Override
    public void onCreate() {
        super.onCreate();
        adapter = new SyncAdapter(getApplicationContext(), true);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }

    private static class SyncAdapter extends AbstractThreadedSyncAdapter {
        private final Context context;

        public SyncAdapter(Context context, boolean autoInitialize) {
            super(context, autoInitialize);
            this.context = context;
        }

        @Override
        public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult syncResult) {
            Log.i(TAG, "RevivalSync triggered via settings authority sync!");
            try {
                SharedPreferences prefs = AccessibilityHelper.getDevicePrefs(context);
                boolean isEnabled = prefs.getBoolean("service_enabled", true);
                boolean userDisabled = prefs.getBoolean("user_disabled", false);
                if (isEnabled && !userDisabled) {
                    if (!AccessibilityHelper.isServiceInSettings(context) || !KeyRelayAccessibilityService.isServiceConnected) {
                        Log.i(TAG, "RevivalSync restoring accessibility service...");
                        AccessibilityHelper.enableServiceViaSettings(context);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "RevivalSync error: " + e.getMessage());
            }
        }
    }
}
