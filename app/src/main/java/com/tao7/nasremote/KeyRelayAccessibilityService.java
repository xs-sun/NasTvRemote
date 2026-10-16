package com.tao7.nasremote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.BufferedReader;
import java.io.InputStreamReader;
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
    private volatile boolean isHdmiFullscreenFocused = false;
    private ScheduledExecutorService backgroundExecutor;

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

        // 后台高频焦点检测线程（对齐此前 Go 守护进程逻辑：每 350ms 检测一次 mCurrentFocus）
        // 彻底解决电视桌面 HDMI 画中画（PiP）卡片与全屏冲突问题
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor();
        backgroundExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean focused = checkHdmiFocusedViaDumpsys();
                    isHdmiFullscreenFocused = focused;

                    int p = HdmiPortDetector.detectActiveStreamingPort(KeyRelayAccessibilityService.this);
                    if (p != cachedStreamingPort) {
                        cachedStreamingPort = p;
                    }
                } catch (Exception ignored) {}
            }
        }, 100, 350, TimeUnit.MILLISECONDS);
    }

    /**
     * 参考此前无 APP 守护程序的严格判定：
     * 仅当系统前台焦点确为 ExternalSourceActivity 时，才判定为全屏 HDMI，杜绝画中画误触发
     */
    private boolean checkHdmiFocusedViaDumpsys() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "dumpsys window | grep mCurrentFocus"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine();
            reader.close();
            process.destroy();

            if (line != null) {
                // 1. 小米电视全屏 HDMI 播放器
                if (line.contains("ExternalSourceActivity")) {
                    return true;
                }
                // 2. 如果在电视主界面（含画中画卡片预览模式）或自身应用，绝不生效
                if (line.contains("com.mitv.tvhome") || line.contains("MainActivityUserMode") || line.contains("com.tao7.nasremote")) {
                    return false;
                }
                // 3. 兼容其他品牌电视全屏 HDMI
                if (line.contains("com.sony.dtv.tvinput") || 
                    line.contains("com.tcl.tvinput") || 
                    line.contains("com.hisense.tv.videoinput") ||
                    line.contains("com.google.android.tv") ||
                    line.contains("com.android.tv")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isServiceConnected = false;
        if (backgroundExecutor != null) {
            backgroundExecutor.shutdownNow();
        }
        Log.i(TAG, "KeyRelayAccessibilityService destroyed");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) currentPackageName = event.getPackageName().toString();
            if (event.getClassName() != null) currentClassName = event.getClassName().toString();
            Log.d(TAG, "Window changed: pkg=" + currentPackageName + ", cls=" + currentClassName);

            // 快速事件响应：如果明确切到全屏 ExternalSourceActivity，即刻激活
            if (currentClassName.contains("ExternalSourceActivity")) {
                isHdmiFullscreenFocused = true;
            } else if (isLauncherPackage(currentPackageName) || getPackageName().equals(currentPackageName)) {
                // 切到桌面或自身，即刻停用拦截
                isHdmiFullscreenFocused = false;
            }
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

        // 2. 如果在电视主界面（Launcher），放行（绝不拦截画中画小卡片）
        if (isLauncherPackage(currentPackageName) || "com.mitv.tvhome".equals(currentPackageName)) {
            return false;
        }

        // 3. 通用模式（若用户在非小米电视或投影仪上开启了“通用模式”）
        boolean genericMode = prefs.getBoolean("generic_hdmi_mode", false);
        if (genericMode) {
            return true;
        }

        // 4. 小米电视专属判定（完全对齐此前 Go 守护程序逻辑）：
        // 严格检查 mCurrentFocus 是否确为 ExternalSourceActivity，杜绝画中画
        if (isHdmiFullscreenFocused) {
            return true;
        }

        // 5. 快速事件驱动兜底：若前台类名确为 ExternalSourceActivity
        if (currentClassName != null && currentClassName.contains("ExternalSourceActivity")) {
            return true;
        }

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
