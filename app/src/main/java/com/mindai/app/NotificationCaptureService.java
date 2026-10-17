package com.mindai.app;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class NotificationCaptureService extends NotificationListenerService {
    private static final String TAG = "MindAiCapture";
    private static final long WIDGET_DEBOUNCE_MS = 750L;
    private static final ConcurrentHashMap<String, String> APP_NAMES = new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor CAPTURE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256), r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "mindai-notification-writer");
        return thread;
    }, new ThreadPoolExecutor.DiscardOldestPolicy());
    private static final ScheduledThreadPoolExecutor WIDGET_EXECUTOR = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "mindai-widget-debounce");
        return thread;
    });
    private static final Object WIDGET_LOCK = new Object();
    private static ScheduledFuture<?> pendingWidgetUpdate;
    private static long widgetUpdateGeneration;

    static {
        WIDGET_EXECUTOR.setRemoveOnCancelPolicy(true);
    }

    private static final Pattern SENSITIVE_CODE = Pattern.compile("(?<!\\d)\\d{4,8}(?!\\d)");
    private static final Set<String> RECOMMENDED_HINTS = new HashSet<>(Arrays.asList(
            "wechat", "weixin", "tencent.mm", "lark", "feishu", "dingtalk", "mail", "gmail",
            "outlook", "calendar", "calendar2", "slack", "teams", "telegram", "whatsapp"
    ));

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        NotificationOpenCoordinator.listenerConnected(this);
        getSharedPreferences("mindai_settings", MODE_PRIVATE).edit()
                .putLong("listener_connected_at", System.currentTimeMillis()).apply();
    }

    @Override public void onListenerDisconnected() {
        super.onListenerDisconnected();
        NotificationOpenCoordinator.listenerDisconnected(this);
        getSharedPreferences("mindai_settings", MODE_PRIVATE).edit()
                .putLong("listener_disconnected_at", System.currentTimeMillis()).apply();
        try { requestRebind(new android.content.ComponentName(this, NotificationCaptureService.class)); }
        catch (Exception ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if (!ComplianceSettings.hasNotificationCaptureConsent(this)) return;
        SharedPreferences settings = getSharedPreferences("mindai_settings", MODE_PRIVATE);
        String pkg = sbn.getPackageName();
        if (!shouldCapture(settings, pkg)) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        boolean groupSummary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
        if (ongoing && !settings.getBoolean("capture_ongoing", false)) return;
        if (groupSummary && !settings.getBoolean("capture_group_summaries", false)) return;

        Bundle extras = n.extras == null ? new Bundle() : n.extras;
        if (sbn.getPackageName().equals(getPackageName()) && !extras.getBoolean("mindai_test_capture", false)) return;
        String title = first(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                extras.getCharSequence(Notification.EXTRA_TITLE), extras.getCharSequence(Notification.EXTRA_TITLE_BIG));
        String content = first(extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
                extras.getCharSequence(Notification.EXTRA_TEXT), extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT));
        if (title.isEmpty() && content.isEmpty()) content = "此通知未提供可读取的正文";

        String sensitivity = detectSensitivity(pkg, title, content);
        boolean redact = settings.getBoolean("redact_sensitive", true);
        if (!"normal".equals(sensitivity) && settings.getBoolean("exclude_sensitive", false)) return;
        if (!"normal".equals(sensitivity) && redact) content = redact(content);

        NotificationOpenCoordinator.notificationPosted(sbn);

        final Context appContext = getApplicationContext();
        final String sourceKey = sbn.getKey();
        final String capturedPackage = pkg;
        final String capturedTitle = title;
        final String capturedContent = content;
        final long postedAt = sbn.getPostTime() > 0 ? sbn.getPostTime() : System.currentTimeMillis();
        final String capturedSensitivity = sensitivity;
        final boolean hadContentIntent = n.contentIntent != null;
        final int systemUserId = sbn.getUserId();
        CAPTURE_EXECUTOR.execute(() -> {
            try {
                String appName = APP_NAMES.computeIfAbsent(capturedPackage,
                        key -> appName(appContext, key));
                MindAiApp.get().db().saveNotification(sourceKey, capturedPackage, appName,
                        capturedTitle, capturedContent, postedAt, capturedSensitivity,
                        hadContentIntent, systemUserId);
                appContext.getSharedPreferences("mindai_settings", MODE_PRIVATE).edit()
                        .putLong("last_captured_at", System.currentTimeMillis()).apply();
                debounceWidgetUpdate(appContext);
            } catch (RuntimeException e) {
                android.util.Log.e(TAG, "Unable to persist captured notification", e);
            }
        });
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (sbn == null) return;
        NotificationOpenCoordinator.notificationRemoved(sbn);
        final String sourceKey = sbn.getKey();
        final long postedAt = sbn.getPostTime();
        CAPTURE_EXECUTOR.execute(() -> {
            try {
                MindAiApp.get().db().markSystemNotificationRemoved(sourceKey, postedAt,
                        System.currentTimeMillis());
            } catch (RuntimeException e) {
                android.util.Log.e(TAG, "Unable to update removed notification metadata", e);
            }
        });
    }

    private boolean shouldCapture(SharedPreferences settings, String pkg) {
        Set<String> blocked = settings.getStringSet("blocked_packages", new HashSet<>());
        if (blocked.contains(pkg)) return false;
        String mode = settings.getString("capture_mode", "recommended");
        if ("custom".equals(mode)) {
            return settings.getStringSet("allowed_packages", new HashSet<>()).contains(pkg);
        }
        if ("recommended".equals(mode)) {
            String lower = pkg.toLowerCase(Locale.ROOT);
            for (String hint : RECOMMENDED_HINTS) if (lower.contains(hint)) return true;
            return false;
        }
        return true;
    }

    private static String detectSensitivity(String pkg, String title, String content) {
        String all = (pkg + " " + title + " " + content).toLowerCase(Locale.ROOT);
        if (all.contains("验证码") || all.contains("verification code") || all.contains("otp") ||
                all.contains("bank") || all.contains("银行") || all.contains("支付") ||
                all.contains("health") || all.contains("健康")) return "sensitive";
        return "normal";
    }

    private static String redact(String content) {
        if (content == null || content.isEmpty()) return "";
        return SENSITIVE_CODE.matcher(content).replaceAll("••••••");
    }

    private static String first(CharSequence... values) {
        for (CharSequence value : values) if (value != null && value.length() > 0) return value.toString();
        return "";
    }

    private static String appName(Context context, String pkg) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
            return context.getPackageManager().getApplicationLabel(info).toString();
        } catch (Exception e) { return pkg; }
    }

    private static void debounceWidgetUpdate(Context context) {
        Context appContext = context.getApplicationContext();
        synchronized (WIDGET_LOCK) {
            final long generation = ++widgetUpdateGeneration;
            if (pendingWidgetUpdate != null) pendingWidgetUpdate.cancel(false);
            pendingWidgetUpdate = WIDGET_EXECUTOR.schedule(() -> {
                synchronized (WIDGET_LOCK) {
                    if (generation != widgetUpdateGeneration) return;
                    pendingWidgetUpdate = null;
                }
                try {
                    MindAiWidgetUpdater.updateAll(appContext);
                } catch (RuntimeException e) {
                    android.util.Log.e(TAG, "Unable to update widgets after notification capture", e);
                }
            }, WIDGET_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }
}
