package com.tao7.nasremote;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HdmiPortDetector {

    public static class PortInfo {
        public int port;
        public boolean isPlugged;    // 物理插入线缆
        public boolean isStreaming;  // 当前全屏输出画面中

        public PortInfo(int port) {
            this.port = port;
        }
    }

    /**
     * 完整检测所有 HDMI 端口的物理插线状态与活跃输出状态
     */
    public static Map<Integer, PortInfo> detectAllPorts(Context context) {
        Map<Integer, PortInfo> ports = new HashMap<>();
        ports.put(1, new PortInfo(1));
        ports.put(2, new PortInfo(2));
        ports.put(3, new PortInfo(3));

        // 1. Android 标准系统 TvInputManager API（免 root、免权限、全品牌通用）
        try {
            TvInputManager tvInputManager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (tvInputManager != null) {
                List<TvInputInfo> inputList = tvInputManager.getTvInputList();
                for (TvInputInfo info : inputList) {
                    if (info.getType() == TvInputInfo.TYPE_HDMI) {
                        String id = info.getId().toLowerCase();
                        int state = tvInputManager.getInputState(info.getId());
                        // state: 0=CONNECTED (已插线), 1=STANDBY (空闲/未插线), 2=DISCONNECTED (未插线)
                        boolean plugged = (state == TvInputManager.INPUT_STATE_CONNECTED);
                        
                        int portNum = parsePortFromId(id);
                        if (portNum > 0 && ports.containsKey(portNum)) {
                            ports.get(portNum).isPlugged = plugged;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 2. 底层 dumpsys tv_input 流状态检测
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"dumpsys", "tv_input"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("type=9")) {
                    for (int p = 1; p <= 3; p++) {
                        if (line.contains("hdmi_port=" + p)) {
                            // 小米电视 Novatek 平台物理端口与芯片驱动对应关系：
                            // 芯片 hdmi_port=3 (HW6) 对应机身外壳上的 HDMI 1
                            // 芯片 hdmi_port=1 (HW4) 对应机身外壳上的 HDMI 3
                            int physicalPort = (p == 1) ? 3 : (p == 3 ? 1 : 2);
                            if (line.contains("cable_connection_status=1") || line.contains("mConfigs: [TvStreamConfig")) {
                                ports.get(physicalPort).isPlugged = true;
                            }
                            if (line.contains("mConfigs: [TvStreamConfig")) {
                                ports.get(physicalPort).isStreaming = true;
                                ports.get(physicalPort).isPlugged = true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return ports;
    }

    /**
     * 检测当前正在全屏输出视频流的 HDMI 端口号（1/2/3），没有则返回 0
     */
    public static int detectActiveStreamingPort(Context context) {
        Map<Integer, PortInfo> all = detectAllPorts(context);
        for (PortInfo pi : all.values()) {
            if (pi.isStreaming) {
                return pi.port;
            }
        }
        return 0;
    }

    /**
     * 获取第一个检测到插线的端口（若无则默认 1）
     */
    public static int getFirstPluggedPort(Context context) {
        Map<Integer, PortInfo> all = detectAllPorts(context);
        for (PortInfo pi : all.values()) {
            if (pi.isStreaming) return pi.port;
        }
        for (int p = 1; p <= 3; p++) {
            if (all.get(p).isPlugged) return p;
        }
        return 1;
    }

    private static int parsePortFromId(String id) {
        // 小米电视 Novatek 平台物理映射：HW6/hdmi3 对应电视外壳上的 HDMI 1
        if (id.contains("novatek") || id.contains("mitv")) {
            if (id.contains("hdmi3") || id.contains("/hw6") || id.contains("hw3")) return 1;
            if (id.contains("hdmi2") || id.contains("/hw5") || id.contains("hw2")) return 2;
            if (id.contains("hdmi1") || id.contains("/hw4") || id.contains("hw1")) return 3;
        }
        if (id.contains("hdmi1") || id.contains("/hw4") || id.contains("hw1")) return 1;
        if (id.contains("hdmi2") || id.contains("/hw5") || id.contains("hw2")) return 2;
        if (id.contains("hdmi3") || id.contains("/hw6") || id.contains("hw3")) return 3;
        for (int i = 1; i <= 3; i++) {
            if (id.contains("port=" + i) || id.contains("hdmi_" + i) || id.contains("hdmi" + i)) {
                return i;
            }
        }
        return 0;
    }
}
