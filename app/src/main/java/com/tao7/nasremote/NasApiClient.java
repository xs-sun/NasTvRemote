package com.tao7.nasremote;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NasApiClient {
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public void sendCommandAsync(final String nasBaseUrl, final String commandName, final Callback callback) {
        executor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(nasBaseUrl + "/ugreen/v1/hdmi/desktop/command");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Connection", "Keep-Alive");
                conn.setConnectTimeout(300);
                conn.setReadTimeout(300);
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

    public interface Callback {
        void onSuccess();
        void onError(String msg);
    }
}
