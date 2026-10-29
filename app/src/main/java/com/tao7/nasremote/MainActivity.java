package com.tao7.nasremote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private Switch switchService;
    private Switch switchBoot;
    private Switch switchGenericMode;
    private View layoutPermissionWarning;
    private Button btnGrantAccessibility;
    private EditText editNasIp;
    private EditText editNasPort;
    private CheckBox cbHdmi1, cbHdmi2, cbHdmi3;
    private TextView tvStatus;
    private TextView tvActivePort;
    private Button btnTestConnection;
    private TextView tvLog;
    private SharedPreferences prefs;
    private Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = AccessibilityHelper.getDevicePrefs(this);

        initViews();
        loadSettings();
        startStatusMonitor();
    }

    private void initViews() {
        switchService = findViewById(R.id.switch_service);
        switchBoot = findViewById(R.id.switch_boot);
        switchGenericMode = findViewById(R.id.switch_generic_mode);
        layoutPermissionWarning = findViewById(R.id.layout_permission_warning);
        btnGrantAccessibility = findViewById(R.id.btn_grant_accessibility);
        editNasIp = findViewById(R.id.edit_nas_ip);
        editNasPort = findViewById(R.id.edit_nas_port);
        cbHdmi1 = findViewById(R.id.cb_hdmi_1);
        cbHdmi2 = findViewById(R.id.cb_hdmi_2);
        cbHdmi3 = findViewById(R.id.cb_hdmi_3);
        tvStatus = findViewById(R.id.tv_status);
        tvActivePort = findViewById(R.id.tv_active_port);
        btnTestConnection = findViewById(R.id.btn_test_connection);
        tvLog = findViewById(R.id.tv_log);

        // 权限引导按钮
        btnGrantAccessibility.setOnClickListener(v -> {
            appendLog("正在打开系统设置，请开启【绿联NAS遥控助手】无障碍服务...");
            AccessibilityHelper.openAccessibilitySettings(MainActivity.this);
        });

        // 服务开关监听
        switchService.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("service_enabled", isChecked).putBoolean("user_disabled", !isChecked).apply();
            if (isChecked) {
                boolean isRunning = AccessibilityHelper.isServiceRunning(MainActivity.this);
                if (!isRunning) {
                    boolean success = AccessibilityHelper.enableServiceViaSettings(MainActivity.this);
                    if (!success && !AccessibilityHelper.isServiceRunning(MainActivity.this)) {
                        appendLog("提示：正在打开系统设置，请开启【绿联NAS遥控助手】");
                        AccessibilityHelper.openAccessibilitySettings(MainActivity.this);
                    } else {
                        appendLog(">>> 监听服务已启动");
                    }
                } else {
                    appendLog(">>> 监听服务已启动");
                }
            } else {
                appendLog(">>> 监听服务已暂停 (遥控按键放行中)");
            }
            updateStatusText();
        });

        // 开机自启开关监听
        switchBoot.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("boot_autostart", isChecked).apply();
            appendLog(isChecked ? "开机自启已启用" : "开机自启已禁用");
        });

        // 通用模式开关监听
        switchGenericMode.setOnCheckedChangeListener((btn, isChecked) -> {
            prefs.edit().putBoolean("generic_hdmi_mode", isChecked).apply();
            appendLog(isChecked ? "已启用通用模式（除电视主页外全局生效）" : "已恢复标准模式（仅 HDMI 信号源生效）");
        });

        // HDMI 端口多选变更监听
        View.OnClickListener hdmiClickListener = v -> saveHdmiPortSelection();
        cbHdmi1.setOnClickListener(hdmiClickListener);
        cbHdmi2.setOnClickListener(hdmiClickListener);
        cbHdmi3.setOnClickListener(hdmiClickListener);

        // 测试连通性按钮
        btnTestConnection.setOnClickListener(v -> testNasConnection());
    }

    private void loadSettings() {
        switchService.setChecked(prefs.getBoolean("service_enabled", true));
        switchBoot.setChecked(prefs.getBoolean("boot_autostart", true));
        switchGenericMode.setChecked(prefs.getBoolean("generic_hdmi_mode", false));
        editNasIp.setText(prefs.getString("nas_ip", "192.168.31.2"));
        editNasPort.setText(prefs.getString("nas_port", "9999"));

        Set<String> ports = prefs.getStringSet("selected_hdmi_ports", null);
        Map<Integer, HdmiPortDetector.PortInfo> detected = HdmiPortDetector.detectAllPorts(this);

        // 如果未配置过，或者保存的端口全都未插线但检测到有实际插线端口，自动对齐到真实插线端口
        boolean hasPluggedSelection = false;
        if (ports != null && !ports.isEmpty()) {
            for (int p = 1; p <= 3; p++) {
                if (ports.contains("HDMI " + p) && detected.get(p) != null && detected.get(p).isPlugged) {
                    hasPluggedSelection = true;
                    break;
                }
            }
        }

        if (ports == null || ports.isEmpty() || (!hasPluggedSelection && (detected.get(1).isPlugged || detected.get(2).isPlugged || detected.get(3).isPlugged))) {
            int detectedPort = HdmiPortDetector.getFirstPluggedPort(this);
            cbHdmi1.setChecked(true);
            if (detectedPort == 2) cbHdmi2.setChecked(true);
            if (detectedPort == 3) cbHdmi3.setChecked(true);
            saveHdmiPortSelection();
        } else {
            cbHdmi1.setChecked(ports.contains("HDMI 1"));
            cbHdmi2.setChecked(ports.contains("HDMI 2"));
            cbHdmi3.setChecked(ports.contains("HDMI 3"));
        }
        updateStatusText();
    }

    private void saveHdmiPortSelection() {
        Set<String> selected = new HashSet<>();
        if (cbHdmi1.isChecked()) selected.add("HDMI 1");
        if (cbHdmi2.isChecked()) selected.add("HDMI 2");
        if (cbHdmi3.isChecked()) selected.add("HDMI 3");
        prefs.edit().putStringSet("selected_hdmi_ports", selected).apply();
        appendLog("更新生效端口列表: " + selected.toString());
    }

    private void testNasConnection() {
        String ip = editNasIp.getText().toString().trim();
        String port = editNasPort.getText().toString().trim();
        prefs.edit().putString("nas_ip", ip).putString("nas_port", port).apply();

        appendLog("正在连接 " + ip + ":" + port + " ...");
        long start = System.currentTimeMillis();
        new NasApiClient().sendCommandAsync("http://" + ip + ":" + port, "up", new NasApiClient.Callback() {
            @Override
            public void onSuccess() {
                long cost = System.currentTimeMillis() - start;
                runOnUiThread(() -> appendLog("✓ 连接成功！延迟: " + cost + " ms"));
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> appendLog("✗ 连接失败: " + msg));
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isConfigEnabled = prefs.getBoolean("service_enabled", true);
        if (isConfigEnabled && !AccessibilityHelper.isServiceRunning(this)) {
            AccessibilityHelper.enableServiceViaSettings(this);
        }
        updateStatusText();
    }

    private void updateStatusText() {
        boolean isRunning = AccessibilityHelper.isServiceRunning(this);
        boolean isConfigEnabled = prefs.getBoolean("service_enabled", true);

        if (!isRunning) {
            layoutPermissionWarning.setVisibility(View.VISIBLE);
            tvStatus.setText("🔴 未开启无障碍服务 (请点击下方按钮开启)");
            tvStatus.setTextColor(0xFFFF5252);
        } else {
            layoutPermissionWarning.setVisibility(View.GONE);
            if (isConfigEnabled) {
                tvStatus.setText("🟢 运行中 (Listening)");
                tvStatus.setTextColor(0xFF4CAF50);
            } else {
                tvStatus.setText("⚪ 已暂停 (Paused)");
                tvStatus.setTextColor(0xFF9E9E9E);
            }
        }
    }

    private void startStatusMonitor() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateStatusText();
                updatePortStatus();
                handler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    private void updatePortStatus() {
        Map<Integer, HdmiPortDetector.PortInfo> allPorts = HdmiPortDetector.detectAllPorts(MainActivity.this);
        int streamingPort = 0;
        int pluggedCount = 0;
        int firstPlugged = 0;

        for (int i = 1; i <= 3; i++) {
            HdmiPortDetector.PortInfo info = allPorts.get(i);
            CheckBox cb = (i == 1) ? cbHdmi1 : (i == 2 ? cbHdmi2 : cbHdmi3);
            if (info != null && cb != null) {
                if (info.isStreaming) {
                    streamingPort = i;
                    cb.setText("HDMI " + i + "  [● 已插线 / 全屏活跃中]");
                    cb.setTextColor(0xFF00E676); // 荧光绿
                } else if (info.isPlugged) {
                    cb.setText("HDMI " + i + "  [● 已插线 / 待机备用]");
                    cb.setTextColor(0xFF40C4FF); // 科技亮蓝
                    if (firstPlugged == 0) firstPlugged = i;
                    pluggedCount++;
                } else {
                    cb.setText("HDMI " + i + "  [○ 未插线]");
                    cb.setTextColor(0xFF90A4AE); // 暗灰
                }
            }
        }

        if (streamingPort > 0) {
            tvActivePort.setText("HDMI " + streamingPort + " (全屏活跃输出中)");
            tvActivePort.setTextColor(0xFF00E676);
        } else if (pluggedCount > 0) {
            tvActivePort.setText("电视桌面/待机 (检测到 HDMI " + firstPlugged + " 物理插线)");
            tvActivePort.setTextColor(0xFF40C4FF);
        } else {
            tvActivePort.setText("未检测到 HDMI 插线/信号 (待机放行中)");
            tvActivePort.setTextColor(0xFF90A4AE);
        }
    }

    private void appendLog(String line) {
        tvLog.append("\n" + line);
    }
}
