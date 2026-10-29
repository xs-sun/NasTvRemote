package com.tao7.nasremote;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 电视待机（STR）唤醒复活守卫：
 * 小米等电视待机（关机）时会 forceStop 第三方应用，导致无障碍服务被系统强制移出列表。
 * Android 系统对 NotificationListenerService 采用独立于 forceStop 的系统级保活机制（系统保留权限并在唤醒后自动重新绑定）。
 * 借由 NotificationListenerService 的重新绑定，第一时间自愈并重新将无障碍服务注回系统。
 */
public class RevivalListener extends NotificationListenerService {
    private static final String TAG = "NasRemoteRevival";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            recover();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "RevivalListener onCreate -> check and revive accessibility");
        recover();
        scheduleChecks();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "RevivalListener onListenerConnected -> check and revive accessibility");
        recover();
        scheduleChecks();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        recover();
    }

    private void scheduleChecks() {
        handler.removeCallbacks(checkRunnable);
        // 分别在 1秒、3秒、8秒 进行恢复检查，确保系统就绪且无障碍服务成功绑定
        handler.postDelayed(checkRunnable, 1000);
        handler.postDelayed(checkRunnable, 3000);
        handler.postDelayed(checkRunnable, 8000);
    }

    private void recover() {
        try {
            SharedPreferences prefs = AccessibilityHelper.getDevicePrefs(this);
            boolean isEnabled = prefs.getBoolean("service_enabled", true);
            boolean userDisabled = prefs.getBoolean("user_disabled", false);
            if (isEnabled && !userDisabled) {
                if (!AccessibilityHelper.isServiceInSettings(this) || !KeyRelayAccessibilityService.isServiceConnected) {
                    Log.i(TAG, "RevivalListener restoring accessibility service...");
                    AccessibilityHelper.enableServiceViaSettings(this);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error in recover: " + e.getMessage());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkRunnable);
    }
}
