package com.tao7.nasremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KeyRelayAccessibilityService extends AccessibilityService {

    private static final String TAG = "NasRemote";
    public static volatile boolean isServiceConnected = false;

    private NasApiClient apiClient;
    private SharedPreferences prefs;
    private volatile String currentPackageName = "";
    private volatile String currentClassName = "";
    private volatile int cachedStreamingPort = 0;
    private ScheduledExecutorService backgroundExecutor;
    private ContentObserver settingsObserver;

    private long backPressTime = 0;
    private int backRepeats = 0;
    private int menuRepeats = 0;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        isServiceConnected = true;
        apiClient = new NasApiClient();
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 50;
        setServiceInfo(info);

        Log.i(TAG, "KeyRelayAccessibilityService connected successfully with FLAG_REQUEST_FILTER_KEY_EVENTS!");

        // 注册系统无障碍服务变更监听（防止果园TV或第三方桌面覆盖）
        registerSettingsObserver();

        // 后台守护与端口检测线程：定期检测自愈守护与 HDMI 活跃流
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor();
        backgroundExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureServicePersistence();

                    int p = HdmiPortDetector.detectActiveStreamingPort(KeyRelayAccessibilityService.this);
                    if (p != cachedStreamingPort) {
                        cachedStreamingPort = p;
                    }
                } catch (Exception ignored) {}
            }
        }, 500, 1000, TimeUnit.MILLISECONDS);
    }

    private void registerSettingsObserver() {
        try {
            settingsObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
                @Override
                public void onChange(boolean selfChange) {
                    super.onChange(selfChange);
                    ensureServicePersistence();
                }
            };
            getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                settingsObserver
            );
        } catch (Exception e) {
            Log.w(TAG, "Failed to register settings observer: " + e.getMessage());
        }
    }

    /**
     * 自愈守护：若第三方应用覆盖了 ENABLED_ACCESSIBILITY_SERVICES，自动恢复追加
     */
    private void ensureServicePersistence() {
        try {
            if (prefs == null) return;
            boolean isEnabled = prefs.getBoolean("service_enabled", true);
            boolean userDisabled = prefs.getBoolean("user_disabled", false);
            if (isEnabled && !userDisabled) {
                if (!AccessibilityHelper.isServiceInSettings(this)) {
                    Log.w(TAG, "Watchdog: KeyRelayAccessibilityService missing from settings, restoring...");
                    AccessibilityHelper.enableServiceViaSettings(this);
                }
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceConnected = false;
        if (settingsObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(settingsObserver);
            } catch (Exception ignored) {}
        }
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }

        // 若非用户主动关闭，触发自愈重连
        ensureServicePersistence();
        Log.i(TAG, "KeyRelayAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) currentPackageName = event.getPackageName().toString();
            if (event.getClassName() != null) currentClassName = event.getClassName().toString();
            Log.d(TAG, "Window changed: pkg=" + currentPackageName + ", cls=" + currentClassName);
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();

        boolean isServiceEnabled = prefs.getBoolean("service_enabled", true);
        if (!isServiceEnabled) {
            return false;
        }

        if (currentPackageName == null || currentPackageName.isEmpty()) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    currentPackageName = root.getPackageName().toString();
                    if (root.getClassName() != null) {
                        currentClassName = root.getClassName().toString();
                    }
                }
            } catch (Exception ignored) {}
        }

        boolean hdmiActive = isHdmiActive();
        if (!hdmiActive) {
            return false;
        }

        // 端口过滤：如果用户指定了监听端口，且检测到当前端口不在列表中，则放行
        int activePort = cachedStreamingPort;
        Set<String> selectedPorts = prefs.getStringSet("selected_hdmi_ports", null);
        if (selectedPorts != null && !selectedPorts.isEmpty()) {
            if (activePort > 0) {
                String portKey = "HDMI " + activePort;
                if (!selectedPorts.contains(portKey)) {
                    return false;
                }
            }
        }

        String nasIp = prefs.getString("nas_ip", "192.168.31.2");
        String nasPort = prefs.getString("nas_port", "9999");
        String nasBaseUrl = "http://" + nasIp + ":" + nasPort;

        // 音量键与电源键绝不拦截，保留系统全局功能
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || 
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || 
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_POWER) {
            return false;
        }

        // 主页键（HOME）保留系统原生行为，方便随时退出 HDMI
        if (keyCode == KeyEvent.KEYCODE_HOME) {
            return false;
        }

        // 返回键：短按发送给 NAS 桌面；长按 1.2 秒退出信号源返回电视主页
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    backPressTime = System.currentTimeMillis();
                    backRepeats = 0;
                    Log.i(TAG, "Intercepted BACK -> sending 'back' to NAS");
                    apiClient.sendCommandAsync(nasBaseUrl, "back", null);
                } else {
                    backRepeats++;
                    if (backRepeats >= 4 && System.currentTimeMillis() - backPressTime > 1200) {
                        Log.i(TAG, "Long press BACK -> returning to TV Home");
                        performGlobalAction(GLOBAL_ACTION_HOME);
                    }
                }
            }
            return true;
        }

        // 确定 / OK 键
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                Log.i(TAG, "Intercepted DPAD_CENTER -> sending 'confirm' to NAS");
                apiClient.sendCommandAsync(nasBaseUrl, "confirm", null);
            }
            return true;
        }

        // 方向键：上下左右
        String dirCmd = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: dirCmd = "up"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dirCmd = "down"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: dirCmd = "left"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dirCmd = "right"; break;
        }
        if (dirCmd != null) {
            if (action == KeyEvent.ACTION_DOWN) {
                Log.i(TAG, "Intercepted DPAD_" + dirCmd.toUpperCase() + " -> sending '" + dirCmd + "' to NAS");
                apiClient.sendCommandAsync(nasBaseUrl, dirCmd, null);
            }
            return true;
        }

        // 菜单键：短按中继给 NAS，长按放行呼出电视图像画质菜单
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (action == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    menuRepeats = 0;
                    Log.i(TAG, "Intercepted MENU -> sending 'menu' to NAS");
                    apiClient.sendCommandAsync(nasBaseUrl, "menu", null);
                    return true;
                } else {
                    menuRepeats++;
                    if (menuRepeats >= 3) {
                        return false;
                    }
                    return true;
                }
            }
            return true;
        }

        return false;
    }

    private boolean isHdmiActive() {
        // 1. 如果在自身设置界面，放行
        if (getPackageName().equals(currentPackageName)) {
            return false;
        }

        // 2. 如果在电视主界面（小米桌面、果园桌面或第三方桌面），放行（绝不拦截画中画小卡片或桌面浏览）
        if (isLauncherPackage(currentPackageName) || 
            "com.mitv.tvhome".equals(currentPackageName) ||
            "app.orchard.tv".equals(currentPackageName)) {
            return false;
        }

        // 3. 通用模式（若用户在非小米电视或投影仪上开启了“通用模式”）
        boolean genericMode = prefs.getBoolean("generic_hdmi_mode", false);
        if (genericMode) {
            return true;
        }

        // 4. 小米电视全屏 HDMI 专属判定：
        // 前台包名为小米电视官方播放器 com.xiaomi.mitv.tvplayer，或类名包含 ExternalSourceActivity
        if ("com.xiaomi.mitv.tvplayer".equals(currentPackageName) ||
            (currentClassName != null && currentClassName.contains("ExternalSourceActivity"))) {
            return true;
        }

        // 5. 交互窗口快速探针（官方 Accessibility API，无 SELinux 风险）
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                String pkg = root.getPackageName().toString();
                if ("com.xiaomi.mitv.tvplayer".equals(pkg)) {
                    return true;
                }
            }
        } catch (Exception ignored) {}

        // 6. 其他品牌电视全屏 HDMI 播放器
        if (currentPackageName != null) {
            if (currentPackageName.contains("com.sony.dtv.tvinput") || 
                currentPackageName.contains("com.sony.dtv.intent.action.ACTION_HDMI") ||
                currentPackageName.contains("com.tcl.tvinput") || 
                currentPackageName.contains("com.tcl.sourcemanager") ||
                currentPackageName.contains("com.hisense.tv.videoinput") || 
                currentPackageName.contains("com.google.android.tv") || 
                currentPackageName.contains("com.android.tv")) {
                return true;
            }
        }

        return false;
    }

    private boolean isLauncherPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        if ("com.mitv.tvhome".equals(pkg) || 
            "app.orchard.tv".equals(pkg) ||
            "com.google.android.tvlauncher".equals(pkg) || 
            "com.android.tv.launcher".equals(pkg)) {
            return true;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo res = getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (res != null && res.activityInfo != null && pkg.equals(res.activityInfo.packageName)) {
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }
}
