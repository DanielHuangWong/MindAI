package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Queues AI-content reports locally and delivers them to the configured developer endpoint. */
final class AiContentReportManager {
    static final String[] REASON_LABELS = {
            "仇恨、骚扰或霸凌",
            "色情或不适宜内容",
            "危险行为或自伤内容",
            "欺诈、冒充或误导",
            "隐私或敏感信息泄露",
            "其他违规内容"
    };
    private static final String PREFS = "mindai_ai_reports";
    private static final String QUEUE = "encrypted_queue";
    private static final int MAX_QUEUE = 50;
    private static final int MAX_CONTENT_CHARS = 4_000;

    static final class Result {
        final String reportId;
        final boolean delivered;

        Result(String reportId, boolean delivered) {
            this.reportId = reportId;
            this.delivered = delivered;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final CryptoManager crypto;

    AiContentReportManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.crypto = new CryptoManager(this.context);
    }

    Result submit(String contentType, String contentId, String content, String reason) {
        JSONObject report = new JSONObject();
        String reportId = UUID.randomUUID().toString();
        try {
            report.put("report_id", reportId);
            report.put("created_at", System.currentTimeMillis());
            report.put("app_version", BuildConfig.VERSION_NAME);
            report.put("content_type", safe(contentType, 80));
            report.put("content_id", safe(contentId, 160));
            report.put("reason", safe(reason, 200));
            report.put("reported_output", safe(content, MAX_CONTENT_CHARS));
            report.put("original_notifications_included", false);
        } catch (Exception ignored) {}

        boolean delivered = deliver(report);
        if (!delivered) enqueue(report);
        return new Result(reportId, delivered);
    }

    void flushPending() {
        if (!hasEndpoint()) return;
        JSONArray queue = readQueue();
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject report = queue.optJSONObject(i);
            if (report == null || deliver(report)) continue;
            remaining.put(report);
        }
        writeQueue(remaining);
    }

    int pendingCount() {
        return readQueue().length();
    }

    void clearLocalReports() {
        prefs.edit().clear().apply();
    }

    static boolean hasEndpoint() {
        String endpoint = BuildConfig.AI_REPORT_ENDPOINT == null
                ? "" : BuildConfig.AI_REPORT_ENDPOINT.trim();
        return endpoint.startsWith("https://");
    }

    private boolean deliver(JSONObject report) {
        if (!hasEndpoint()) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BuildConfig.AI_REPORT_ENDPOINT.trim())
                    .openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(report.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            drain(status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            return status >= 200 && status < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void enqueue(JSONObject report) {
        JSONArray current = readQueue();
        JSONArray next = new JSONArray();
        int first = Math.max(0, current.length() - (MAX_QUEUE - 1));
        for (int i = first; i < current.length(); i++) {
            JSONObject item = current.optJSONObject(i);
            if (item != null) next.put(item);
        }
        next.put(report);
        writeQueue(next);
    }

    private JSONArray readQueue() {
        String encrypted = prefs.getString(QUEUE, "");
        if (encrypted == null || encrypted.isEmpty()) return new JSONArray();
        try {
            String raw = crypto.decrypt(encrypted);
            return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
        } catch (Exception ignored) {
            prefs.edit().remove(QUEUE).apply();
            return new JSONArray();
        }
    }

    private void writeQueue(JSONArray queue) {
        if (queue == null || queue.length() == 0) {
            prefs.edit().remove(QUEUE).apply();
            return;
        }
        prefs.edit().putString(QUEUE, crypto.encrypt(queue.toString())).apply();
    }

    private static String safe(String value, int max) {
        String safe = value == null ? "" : value.trim();
        return safe.length() <= max ? safe : safe.substring(0, max);
    }

    private static void drain(InputStream stream) {
        if (stream == null) return;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            while (reader.readLine() != null) {}
        } catch (Exception ignored) {}
    }
}
