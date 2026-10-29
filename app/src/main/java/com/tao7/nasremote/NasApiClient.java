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
     * 智能安全唤醒（幂等保护）：
     * 先发送安全探针判断当前状态，如果 NAS 处于待机状态（reason: device_standby），
     * 则发送 standby 唤醒；如果已经唤醒，则绝不重复发送，杜绝误触睡眠。
     */
    public void ensureWakeAsync(final String nasBaseUrl, final Callback callback) {
        executor.execute(() -> {
            try {
                String probeResp = sendCommandSyncInternal(nasBaseUrl, "up");
                if (probeResp != null && probeResp.contains("\"device_standby\"")) {
                    Log.i(TAG, "[AutoWake] Probe detected NAS is in standby -> sending 'standby' to wake up");
                    sendCommandAsync(nasBaseUrl, "standby", new Callback() {
                        @Override
                        public void onSuccess() {
                            Log.i(TAG, "[AutoWake] Wake-up command executed successfully!");
                            if (callback != null) callback.onSuccess();
                        }

                        @Override
                        public void onError(String msg) {
                            Log.w(TAG, "[AutoWake] Wake-up command failed: " + msg);
                            if (callback != null) callback.onError(msg);
                        }
                    });
                } else {
                    Log.i(TAG, "[AutoWake] NAS is already awake or probe returned normal -> no wake needed");
                    if (callback != null) callback.onSuccess();
                }
            } catch (Exception e) {
                Log.w(TAG, "[AutoWake] Error during wake check: " + e.getMessage());
                if (callback != null) callback.onError(e.getMessage());
            }
        });
    }

    /**
     * 联动休眠待机：直接发送 standby 使得 NAS 暂停播放并切断 HDMI 输出
     */
    public void ensureStandbyAsync(final String nasBaseUrl, final Callback callback) {
        Log.i(TAG, "[AutoStandby] Sending 'standby' command to NAS...");
        sendCommandAsync(nasBaseUrl, "standby", new Callback() {
            @Override
            public void onSuccess() {
                Log.i(TAG, "[AutoStandby] NAS entered standby successfully");
                if (callback != null) callback.onSuccess();
            }

            @Override
            public void onError(String msg) {
                Log.w(TAG, "[AutoStandby] Failed to put NAS in standby: " + msg);
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
