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
     * 智能安全唤醒（Auto Wake）：
     * 1. 发送轻量探测 'up' 检测 NAS 当前显卡状态；
     * 2. 若返回 "reason":"device_standby"（处于 kms-off 息屏深度低功耗状态）：
     *    发送 'standby' 命令触发翻转开机（kms-on 亮屏），恢复 4K 画面；
     * 3. 若返回已处于开机亮屏（"executed":true）：
     *    保持现有状态，绝不重复发送，避免误翻转关屏！
     */
    public void ensureWakeAsync(final String nasBaseUrl, final Callback callback) {
        executor.execute(() -> {
            try {
                String probeResp = sendCommandSyncInternal(nasBaseUrl, "up");
                if (probeResp != null && probeResp.contains("device_standby")) {
                    Log.i(TAG, "[AutoWake] Probe detected NAS is in standby (kms-off) -> sending 'standby' toggle to wake up (kms-on)");
                    sendCommandAsync(nasBaseUrl, "standby", callback);
                } else {
                    Log.i(TAG, "[AutoWake] NAS screen is already active (kms-on), skipping toggle");
                    if (callback != null) callback.onSuccess();
                }
            } catch (Exception e) {
                Log.w(TAG, "[AutoWake] Error during wake check: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    /**
     * 智能深度节能待机（Deep Standby）：
     * 1. 发送 'pause' 暂停正在播放的音视频，立即卸载视频硬解解码功耗；
     * 2. 探测 NAS 当前若仍为亮屏状态（未休眠）：
     *    发送 'standby' 触发显卡物理断开 HDMI 输出（kms-off），实现 0 显卡功耗，
     *    同时触发电视检测无信号进入电视自有低功耗待机；
     * 3. 若当前已处于待机，绝不重复发送，防止误翻转！
     */
    public void ensureDeepStandbyAsync(final String nasBaseUrl, final Callback callback) {
        executor.execute(() -> {
            try {
                // 1. 先暂停播放，降低解码功耗
                sendCommandSyncInternal(nasBaseUrl, "pause");

                // 2. 探测当前显卡状态
                String probeResp = sendCommandSyncInternal(nasBaseUrl, "up");
                if (probeResp != null && !probeResp.contains("device_standby")) {
                    Log.i(TAG, "[AutoStandby] Screen active -> sending 'standby' to power off KMS (kms-off) for zero power");
                    sendCommandAsync(nasBaseUrl, "standby", callback);
                } else {
                    Log.i(TAG, "[AutoStandby] NAS is already in standby (kms-off), skipping toggle");
                    if (callback != null) callback.onSuccess();
                }
            } catch (Exception e) {
                Log.w(TAG, "[AutoStandby] Standby toggle error: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
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
