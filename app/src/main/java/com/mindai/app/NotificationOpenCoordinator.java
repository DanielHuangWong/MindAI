package com.mindai.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps notification click capabilities process-local. A PendingIntent is a live system token,
 * not durable user data, so it is never written to SQLite, exported, logged, or sent to AI.
 */
final class NotificationOpenCoordinator {
    enum State { EXACT, APP_HOME, APP_MISSING, BLOCKED, UNAVAILABLE }
    enum Outcome { EXACT_SENT, HOME_SENT, CANCELED, SECURITY_REJECTED, SYSTEM_BLOCKED, UNAVAILABLE }

    static final class Resolution {
        final State state;
        final String packageName;
        final String appName;
        final String sourceKey;
        final long postedAt;
        final PendingIntent exactIntent;
        final Intent launchIntent;
        final IntentSender launchIntentSender;
        final String reason;

        private Resolution(State state, Models.NotificationItem item, PendingIntent exactIntent,
                           Intent launchIntent, IntentSender launchIntentSender, String reason) {
            this.state = state;
            this.packageName = item == null || item.packageName == null ? "" : item.packageName;
            this.appName = displayAppName(item);
            this.sourceKey = item == null || item.sourceKey == null ? "" : item.sourceKey;
            this.postedAt = item == null ? 0 : item.postedAt;
            this.exactIntent = exactIntent;
            this.launchIntent = launchIntent;
            this.launchIntentSender = launchIntentSender;
            this.reason = reason == null ? "" : reason;
        }

        boolean exactAvailable() { return state == State.EXACT && exactIntent != null; }
        boolean appHomeAvailable() {
            return state == State.APP_HOME && (launchIntent != null || launchIntentSender != null);
        }
    }

    private static final class Entry {
        final String sourceKey;
        final String packageName;
        final long postedAt;
        final int userId;
        final PendingIntent clickIntent;

        Entry(String sourceKey, String packageName, long postedAt, int userId, PendingIntent clickIntent) {
            this.sourceKey = sourceKey;
            this.packageName = packageName;
            this.postedAt = postedAt;
            this.userId = userId;
            this.clickIntent = clickIntent;
        }
    }

    private static final ConcurrentHashMap<String, Entry> LIVE = new ConcurrentHashMap<>();
    private static final Set<String> INVALIDATED = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static volatile WeakReference<NotificationCaptureService> listener = new WeakReference<>(null);

    private NotificationOpenCoordinator() {}

    static void listenerConnected(NotificationCaptureService service) {
        listener = new WeakReference<>(service);
    }

    static void listenerDisconnected(NotificationCaptureService service) {
        NotificationCaptureService current = listener.get();
        if (current == null || current == service) listener = new WeakReference<>(null);
        LIVE.clear();
        INVALIDATED.clear();
    }

    static void notificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Notification notification = sbn.getNotification();
        String instance = instanceKey(sbn.getKey(), sbn.getPostTime());
        INVALIDATED.remove(instance);
        if (notification.contentIntent == null) {
            LIVE.remove(instance);
            return;
        }
        LIVE.put(instance, new Entry(sbn.getKey(), sbn.getPackageName(), sbn.getPostTime(),
                sbn.getUserId(), notification.contentIntent));
    }

    static void notificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String sourceKey = sbn.getKey();
        LIVE.entrySet().removeIf(entry -> sourceKey.equals(entry.getValue().sourceKey));
        INVALIDATED.add(instanceKey(sourceKey, sbn.getPostTime()));
    }

    static Resolution resolve(Context context, Models.NotificationItem item) {
        if (context == null || item == null || item.packageName == null || item.packageName.isEmpty()) {
            return new Resolution(State.UNAVAILABLE, item, null, null, null, "缺少来源应用信息");
        }
        String instance = instanceKey(item.sourceKey, item.postedAt);
        if (item.systemRemovedAt <= 0 && !INVALIDATED.contains(instance)) {
            Entry entry = LIVE.get(instance);
            if (entry == null) entry = restoreFromActiveNotification(item);
            if (entry != null) {
                Resolution exact = validateExact(item, entry);
                if (exact != null) return exact;
            }
        }
        return resolveAppHome(context, item);
    }

    private static Entry restoreFromActiveNotification(Models.NotificationItem item) {
        NotificationCaptureService service = listener.get();
        if (service == null || item.sourceKey == null || item.sourceKey.isEmpty()) return null;
        try {
            StatusBarNotification[] active = service.getActiveNotifications(new String[]{item.sourceKey});
            if (active == null) return null;
            for (StatusBarNotification sbn : active) {
                if (sbn == null || !item.sourceKey.equals(sbn.getKey()) ||
                        !item.packageName.equals(sbn.getPackageName()) || sbn.getPostTime() != item.postedAt ||
                        sbn.getNotification() == null || sbn.getNotification().contentIntent == null) continue;
                Entry entry = new Entry(sbn.getKey(), sbn.getPackageName(), sbn.getPostTime(),
                        sbn.getUserId(), sbn.getNotification().contentIntent);
                LIVE.put(instanceKey(item.sourceKey, item.postedAt), entry);
                return entry;
            }
        } catch (RuntimeException ignored) {
            // Listener may disconnect between the state check and Binder call. App-home fallback remains safe.
        }
        return null;
    }

    private static Resolution validateExact(Models.NotificationItem item, Entry entry) {
        if (!item.sourceKey.equals(entry.sourceKey) || item.postedAt != entry.postedAt ||
                !item.packageName.equals(entry.packageName)) return null;
        if (entry.userId != item.systemUserId ||
                entry.userId != Math.max(0, android.os.Process.myUid() / 100_000)) {
            return new Resolution(State.BLOCKED, item, null, null, null,
                    "系统或工作资料限制了打开操作");
        }
        String creator;
        try { creator = entry.clickIntent.getCreatorPackage(); }
        catch (RuntimeException e) { creator = null; }
        if (creator == null || !item.packageName.equals(creator)) {
            return new Resolution(State.BLOCKED, item, null, null, null,
                    "无法确认这是来源 App 创建的入口，已阻止打开");
        }
        return new Resolution(State.EXACT, item, entry.clickIntent, null, null, "");
    }

    private static Resolution resolveAppHome(Context context, Models.NotificationItem item) {
        PackageManager packageManager = context.getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                IntentSender sender = packageManager.getLaunchIntentSenderForPackage(item.packageName);
                if (sender != null) return new Resolution(State.APP_HOME, item, null, null, sender,
                        originalTargetUnavailableReason(item));
            } else {
                Intent launch = packageManager.getLaunchIntentForPackage(item.packageName);
                if (launch != null) return new Resolution(State.APP_HOME, item, null, launch, null,
                        originalTargetUnavailableReason(item));
            }
        } catch (RuntimeException ignored) {}

        try {
            ApplicationInfo info = packageManager.getApplicationInfo(item.packageName, 0);
            if (!info.enabled) return new Resolution(State.APP_MISSING, item, null, null, null,
                    "来源 App 已停用，无法打开");
            return new Resolution(State.UNAVAILABLE, item, null, null, null,
                    "该 App 没有可打开的主页");
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return new Resolution(State.APP_MISSING, item, null, null, null,
                    "来源 App 可能已卸载、停用或归档");
        }
    }

    static Outcome sendExact(Context context, Resolution resolution) {
        if (resolution == null || !resolution.exactAvailable()) {
            recordOutcome(context, Outcome.UNAVAILABLE);
            return Outcome.UNAVAILABLE;
        }
        try {
            resolution.exactIntent.send();
            recordOutcome(context, Outcome.EXACT_SENT);
            return Outcome.EXACT_SENT;
        } catch (PendingIntent.CanceledException e) {
            invalidate(resolution);
            recordOutcome(context, Outcome.CANCELED);
            return Outcome.CANCELED;
        } catch (SecurityException e) {
            invalidate(resolution);
            recordOutcome(context, Outcome.SECURITY_REJECTED);
            return Outcome.SECURITY_REJECTED;
        } catch (RuntimeException e) {
            recordOutcome(context, Outcome.SYSTEM_BLOCKED);
            return Outcome.SYSTEM_BLOCKED;
        }
    }

    static Outcome openAppHome(Context context, Resolution resolution) {
        if (context == null || resolution == null || !resolution.appHomeAvailable()) {
            recordOutcome(context, Outcome.UNAVAILABLE);
            return Outcome.UNAVAILABLE;
        }
        try {
            if (resolution.launchIntentSender != null) {
                context.startIntentSender(resolution.launchIntentSender, null, 0, 0, 0);
            } else {
                context.startActivity(resolution.launchIntent);
            }
            recordOutcome(context, Outcome.HOME_SENT);
            return Outcome.HOME_SENT;
        } catch (IntentSender.SendIntentException | SecurityException e) {
            recordOutcome(context, Outcome.SYSTEM_BLOCKED);
            return Outcome.SYSTEM_BLOCKED;
        } catch (RuntimeException e) {
            recordOutcome(context, Outcome.UNAVAILABLE);
            return Outcome.UNAVAILABLE;
        }
    }

    private static void invalidate(Resolution resolution) {
        String instance = instanceKey(resolution.sourceKey, resolution.postedAt);
        LIVE.remove(instance);
        INVALIDATED.add(instance);
    }

    private static String originalTargetUnavailableReason(Models.NotificationItem item) {
        if (item.systemRemovedAt > 0) return "原通知已被移除，无法定位到对应页面";
        if (item.hadContentIntentAtCapture) return "原消息入口已失效，无法定位到对应页面";
        return "原通知没有可用的页面入口，只能打开来源 App 首页";
    }

    private static String displayAppName(Models.NotificationItem item) {
        if (item == null) return "来源 App";
        if (item.appName != null && !item.appName.trim().isEmpty()) return item.appName.trim();
        if (item.packageName != null && !item.packageName.trim().isEmpty()) return item.packageName.trim();
        return "来源 App";
    }

    private static String instanceKey(String sourceKey, long postedAt) {
        return (sourceKey == null ? "" : sourceKey) + '\u0000' + postedAt;
    }

    private static void recordOutcome(Context context, Outcome outcome) {
        if (context == null || outcome == null) return;
        context.getSharedPreferences("mindai_notification_open_audit", Context.MODE_PRIVATE).edit()
                .putString("last_outcome", outcome.name().toLowerCase(Locale.ROOT))
                .putLong("last_outcome_at", System.currentTimeMillis())
                .apply();
    }

    static void clearForTests() {
        LIVE.clear();
        INVALIDATED.clear();
        listener = new WeakReference<>(null);
    }

    static void registerForTests(String sourceKey, String packageName, long postedAt,
                                 int userId, PendingIntent clickIntent) {
        String instance = instanceKey(sourceKey, postedAt);
        INVALIDATED.remove(instance);
        if (clickIntent == null) LIVE.remove(instance);
        else LIVE.put(instance, new Entry(sourceKey, packageName, postedAt, userId, clickIntent));
    }
}
