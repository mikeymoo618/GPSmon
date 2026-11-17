package com.gpsmon.app;

import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

final class ReittiClient {
    interface ConnectionFactory { HttpURLConnection open(URL url) throws IOException; }
    static final class UploadFailure extends IOException {
        final boolean retry;
        UploadFailure(String message, boolean retry) { super(message); this.retry = retry; }
    }
    private final ConnectionFactory connections;
    private volatile HttpURLConnection current;
    ReittiClient() { this(url -> (HttpURLConnection) url.openConnection()); }
    ReittiClient(ConnectionFactory connections) { this.connections = connections; }
    static JSONObject payload(TripStore.Point point) throws org.json.JSONException {
        JSONObject json = new JSONObject();
        json.put("_type", "location");
        json.put("t", "u");
        json.put("acc", point.accuracy);
        json.put("alt", point.altitude);
        json.put("lat", point.latitude);
        json.put("lon", point.longitude);
        json.put("tst", point.time / 1000);
        json.put("vel", point.speed * 3.6d);
        if (point.battery != null) json.put("batt", point.battery);
        if (point.charging != null) json.put("bs", point.charging);
        return json;
    }
    void cancel() {
        HttpURLConnection connection = current;
        if (connection != null) connection.disconnect();
    }
    void send(ReittiSettings.Config config, TripStore.Point point, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean()) throw new UploadFailure("Upload paused", true);
        HttpURLConnection connection = connections.open(new URL(config.endpoint));
        current = connection;
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-API-TOKEN", config.token);
            connection.setRequestProperty("User-Agent", "GPSmon/1.1.1");
            byte[] body;
            try { body = payload(point).toString().getBytes(StandardCharsets.UTF_8); }
            catch (org.json.JSONException error) { throw new UploadFailure("This GPS point could not be encoded.", false); }
            connection.setFixedLengthStreamingMode(body.length);
            connection.setDoOutput(true);
            if (cancelled.getAsBoolean()) throw new UploadFailure("Upload paused", true);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String message = code == 401 || code == 403 ? "Reitti rejected the token. Check Settings → Integrations and its device assignment."
                        : "Reitti returned HTTP " + code + ". Check the server and ingestion URL.";
                throw new UploadFailure(message, code == 408 || code == 429 || code >= 500);
            }
            // Limit response consumption. No server body or token is copied into diagnostics.
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[1024];
                int remaining = 8192;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, Math.min(buffer.length, remaining));
                    if (count < 0) break;
                    remaining -= count;
                }
            }
            if (cancelled.getAsBoolean()) throw new UploadFailure("Upload paused", true);
        } finally {
            current = null;
            connection.disconnect();
        }
    }
}
