package com.mindai.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class SummaryNotificationHelper {
    static final String ACTION_OPEN_SUMMARY = "com.mindai.app.action.OPEN_SUMMARY";
    static final String ACTION_OPEN_BRIEF = "com.mindai.app.action.OPEN_BRIEF";
    static final String ACTION_OPEN_ASSISTANT = "com.mindai.app.action.OPEN_ASSISTANT";
    static final String EXTRA_SUMMARY_ID = "summary_id";
    static final String EXTRA_BRIEF_ID = "brief_id";
    static final String CHANNEL_ID = "mindai_summary";
    static final String BRIEF_CHANNEL_ID = "mindai_important";
    static final String ASSISTANT_CHANNEL_ID = "mindai_assistant_reply";
    static final int FOREGROUND_ID = 7001;
    static final int COMPLETED_ID = 7002;
    static final int ASSISTANT_REPLY_ID = 7003;

    private SummaryNotificationHelper() {}

    static Intent summaryIntent(Context context, String sessionId) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(ACTION_OPEN_SUMMARY)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (sessionId != null && !sessionId.isEmpty()) intent.putExtra(EXTRA_SUMMARY_ID, sessionId);
        return intent;
    }

    static Intent briefIntent(Context context, String briefId) {
        return new Intent(context, MainActivity.class).setAction(ACTION_OPEN_BRIEF)
                .putExtra(EXTRA_BRIEF_ID, briefId == null ? "" : briefId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent assistantIntent(Context context, String messageId) {
        return new Intent(context, MainActivity.class).setAction(ACTION_OPEN_ASSISTANT)
                .putExtra("assistant_message_id", messageId == null ? "" : messageId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void notifyAssistantReply(Context context, String messageId) {
        android.content.SharedPreferences settings = context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean("assistant_reply_notifications", true)) return;
        String dedupe = messageId == null ? "" : messageId;
        if (!dedupe.isEmpty() && dedupe.equals(settings.getString("last_assistant_reply_notice", ""))) return;
        ensureAssistantChannel(context);
        Notification notification = builder(context, ASSISTANT_CHANNEL_ID, assistantIntent(context, messageId),
                "MindAI 已回复", "点击查看助理回复")
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(ASSISTANT_REPLY_ID, notification);
        if (!dedupe.isEmpty()) settings.edit().putString("last_assistant_reply_notice", dedupe).apply();
    }

    static void notifyTopBrief(Context context, Models.AssistantBriefItem item) {
        if (item == null || !("critical".equals(item.urgency) || "high".equals(item.urgency))) return;
        android.content.SharedPreferences settings = context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE);
        if (!settings.getBoolean("proactive_reminders_enabled", true)) return;
        java.util.Calendar now = java.util.Calendar.getInstance(); int hour = now.get(java.util.Calendar.HOUR_OF_DAY);
        boolean quiet = settings.getBoolean("quiet_hours_enabled", true) && (hour >= 22 || hour < 8);
        if (quiet && !("critical".equals(item.urgency) && settings.getBoolean("critical_break_quiet", false))) return;
        String fingerprint = item.id + ":" + item.urgency + ":" + item.validUntil + ":" + item.title + ":" + item.reason;
        String noticeKey = "brief_notice_" + Integer.toHexString(item.id.hashCode());
        if (fingerprint.equals(settings.getString(noticeKey, ""))) return;
        ensureBriefChannel(context);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        int requestBase = 7500 + Math.abs(item.id.hashCode() % 1000) * 2;
        PendingIntent snooze = PendingIntent.getBroadcast(context, requestBase,
                new Intent(context, BriefActionReceiver.class).setAction(BriefActionReceiver.ACTION_SNOOZE).putExtra(EXTRA_BRIEF_ID, item.id), flags);
        Notification.Builder builder = builder(context, BRIEF_CHANNEL_ID, briefIntent(context, item.id), item.title,
                item.reason.isEmpty() ? item.detail : item.reason)
                .setAutoCancel(true).addAction(new Notification.Action.Builder(R.drawable.ic_stat_mindai, "稍后", snooze).build());
        if (!item.relatedTodoIds.isEmpty()) {
            PendingIntent done = PendingIntent.getBroadcast(context, requestBase + 1,
                    new Intent(context, BriefActionReceiver.class).setAction(BriefActionReceiver.ACTION_COMPLETE).putExtra(EXTRA_BRIEF_ID, item.id), flags);
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_stat_mindai, "完成", done).build());
        }
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(7400 + Math.abs(item.id.hashCode() % 500), builder.build());
        settings.edit().putString(noticeKey, fingerprint).apply();
    }

    static Notification foreground(Context context, String text) {
        ensureChannel(context);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent cancel = PendingIntent.getBroadcast(context, 7299,
                new Intent(context, WidgetActionReceiver.class)
                        .setAction(WidgetActionReceiver.ACTION_CANCEL), flags);
        return builder(context, CHANNEL_ID, summaryIntent(context, ""), "MindAI 正在总结通知",
                text == null || text.isEmpty() ? "正在后台整理新通知…" : text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(
                        R.drawable.ic_stat_mindai, "停止", cancel).build())
                .build();
    }

    static void notifyCompleted(Context context, Models.SummarySession session, String importantText) {
        ensureChannel(context);
        String content = importantText == null || importantText.trim().isEmpty()
                ? "已完成 " + session.notificationCount + " 条通知总结"
                : importantText;
        Notification notification = builder(context, CHANNEL_ID, summaryIntent(context, session.id),
                "MindAI 总结完成", content)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(COMPLETED_ID, notification);
    }

    static void notifyFailed(Context context, String message) {
        ensureChannel(context);
        Notification notification = builder(context, CHANNEL_ID, summaryIntent(context, ""),
                "MindAI 总结未完成", message == null || message.isEmpty() ? "请打开 App 检查 OpenRouter 连接。" : message)
                .setAutoCancel(true)
                .build();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).notify(COMPLETED_ID, notification);
    }

    private static Notification.Builder builder(Context context, String channelId, Intent intent, String title, String text) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String objectId = intent.getStringExtra(EXTRA_BRIEF_ID);
        if (objectId == null || objectId.isEmpty()) objectId = intent.getStringExtra(EXTRA_SUMMARY_ID);
        if (objectId == null || objectId.isEmpty()) objectId = intent.getStringExtra("assistant_message_id");
        int requestCode = 7200 + (objectId == null ? 0 : Math.abs(objectId.hashCode() % 1000));
        PendingIntent pendingIntent = PendingIntent.getActivity(context, requestCode, intent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, channelId)
                : new Notification.Builder(context);
        return builder
                .setSmallIcon(R.drawable.ic_stat_mindai)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setColor(UiKit.ACCENT)
                .setShowWhen(true);
    }

    static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "MindAI 总结", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("通知总结进度与完成提醒");
        manager.createNotificationChannel(channel);
    }

    private static void ensureBriefChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(BRIEF_CHANNEL_ID, "MindAI 重要提醒", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("临近截止、时间冲突和高优先级事项"); manager.createNotificationChannel(channel);
    }

    private static void ensureAssistantChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(ASSISTANT_CHANNEL_ID, "MindAI 助理回复", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("助理在 App 后台完成回复时提醒，不展示回复正文");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }
}
