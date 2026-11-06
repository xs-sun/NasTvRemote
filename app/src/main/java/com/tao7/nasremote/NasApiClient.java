package com.tao7.nasremote;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Log;

public class NasApiClient {
    private static final String TAG = "NasApiClient";
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    public void sendCommandAsync(final String nasBaseUrl, final String commandName, final Callback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(nasBaseUrl + "/ugreen/v1/hdmi/desktop/command");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setConnectTimeout(400);
                conn.setReadTimeout(400);
                conn.setDoOutput(true);

                String jsonBody = "{\"command\":\"" + commandName + "\"}";
                byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bodyBytes.length);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyBytes);
                    os.flush();
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    if (callback != null) callback.onSuccess();
                } else {
                    if (callback != null) callback.onError("HTTP " + code);
                }
            } catch (Exception e) {
                if (callback != null) callback.onError(e.getMessage());
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /**
     * 智能安全唤醒：如果处于待机/主页，发送主页或恢复指令
     */
    public void ensureWakeAsync(final String nasBaseUrl, final Callback callback) {
        executor.execute(() -> {
            try {
                String probeResp = sendCommandSyncInternal(nasBaseUrl, "up");
                if (probeResp != null && probeResp.contains("\"device_standby\"")) {
                    Log.i(TAG, "[AutoWake] Probe detected NAS is in standby -> sending 'home' to resume");
                    sendCommandAsync(nasBaseUrl, "home", callback);
                } else {
                    Log.i(TAG, "[AutoWake] NAS is already awake or probe returned normal");
                    if (callback != null) callback.onSuccess();
                }
            } catch (Exception e) {
                Log.w(TAG, "[AutoWake] Error during wake check: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    /**
     * 安全休眠/退出控制（安全模式）：
     * 绿联 11月3日新版固件将 standby 升级为了物理断电级深度待机（杀进程+写磁盘标记导致 404 死锁）。
     * 因此退出 HDMI 或电视关机时，改为发送 'pause' 暂停播放 + 'home' 返回桌面，
     * 既达成离开自动暂停播放、节省 NAS 解码功耗的目的，又绝对不会让 HDMI 桌面进程崩溃或死锁！
     */
    public void ensurePauseAndHomeAsync(final String nasBaseUrl, final Callback callback) {
        Log.i(TAG, "[AutoPause] Sending 'pause' to stop video playback safely...");
        sendCommandAsync(nasBaseUrl, "pause", new Callback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "[AutoPause] Video paused successfully");
                if (callback != null) callback.onSuccess();
            }

            @Override
            public void onError(String msg) {
                Log.w(TAG, "[AutoPause] Pause attempt error (may already be paused/on home): " + msg);
                if (callback != null) callback.onError(msg);
            }
        });
    }

    private String sendCommandSyncInternal(String nasBaseUrl, String commandName) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(nasBaseUrl + "/ugreen/v1/hdmi/desktop/command");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(500);
            conn.setReadTimeout(500);
            conn.setDoOutput(true);

            String jsonBody = "{\"command\":\"" + commandName + "\"}";
            byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bodyBytes.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }

            int code = conn.getResponseCode();
            try (InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream()) {
                if (is != null) {
                    byte[] buf = new byte[1024];
                    int n = is.read(buf);
                    if (n > 0) {
                        return new String(buf, 0, n, StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "sendCommandSyncInternal error: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public interface Callback {
        void onSuccess();
        void onError(String msg);
    }
}
