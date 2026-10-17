package com.mindai.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class MindAiWidgetUpdater {
    static final String ACTION_OPEN_ITEM = "com.mindai.app.action.OPEN_WIDGET_ITEM";
    static final String EXTRA_ITEM_TYPE = "widget_item_type";
    static final String EXTRA_ITEM_ID = "widget_item_id";
    static final String EXTRA_SOURCE_SESSION_ID = "widget_source_session_id";
    static final String EXTRA_EVENT_START = "widget_event_start";

    private MindAiWidgetUpdater() {}

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        update(context, manager, new ComponentName(context, MindAiSmallWidgetProvider.class), false);
        update(context, manager, new ComponentName(context, MindAiLargeWidgetProvider.class), true);
    }

    static void update(Context context, AppWidgetManager manager, ComponentName component, boolean large) {
        int[] ids = manager.getAppWidgetIds(component);
        if (ids == null || ids.length == 0) return;
        for (int id : ids) {
            manager.updateAppWidget(id, views(context, large, id));
            manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list);
        }
    }

    static RemoteViews views(Context context, boolean large, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                large ? R.layout.widget_summary_4x4 : R.layout.widget_summary_2x2);
        SharedPreferences state = WidgetSummaryState.prefs(context);
        String status = state.getString("status", WidgetSummaryState.STATUS_IDLE);
        int pending = MindAiApp.get() == null ? 0 : MindAiApp.get().db().countUnsummarized();
        int displayCount = WidgetSummaryState.STATUS_RUNNING.equals(status)
                ? Math.max(pending, state.getInt("started_count", pending)) : pending;
        Models.SummarySession latest = MindAiApp.get() == null ? null : MindAiApp.get().db().latestSuccessfulSession();
        String latestSessionId = latest == null ? "" : latest.id;
        long lastCompletedAt = latest == null ? 0 : latest.completedAt;

        String statusText;
        int statusColor = Color.rgb(101, 103, 107);
        if (WidgetSummaryState.STATUS_RUNNING.equals(status)) {
            statusText = displayCount > 0 ? "正在整理 " + displayCount + " 条" : "正在整理";
            statusColor = Color.rgb(24, 119, 242);
        } else if (WidgetSummaryState.STATUS_ERROR.equals(status)) {
            String source = lastCompletedAt > 0 ? " · 内容 " + time(lastCompletedAt) : "";
            statusText = "整理失败" + source;
            statusColor = Color.rgb(228, 30, 63);
        } else if (pending > 0) {
            statusText = pending + " 条待整理";
            statusColor = Color.rgb(24, 119, 242);
        } else if (lastCompletedAt > 0) {
            statusText = "上次完成 " + time(lastCompletedAt);
        } else {
            statusText = "暂无待整理";
        }
        views.setTextViewText(R.id.widget_status, statusText);
        views.setTextColor(R.id.widget_status, statusColor);
        views.setOnClickPendingIntent(R.id.widget_title, openPendingIntent(context, latestSessionId));
        views.setOnClickPendingIntent(R.id.widget_status,
                WidgetSummaryState.STATUS_ERROR.equals(status) ? summarizePendingIntent(context) : openPendingIntent(context, latestSessionId));
        views.setOnClickPendingIntent(R.id.widget_button,
                WidgetSummaryState.STATUS_RUNNING.equals(status) ? openPendingIntent(context, latestSessionId) : summarizePendingIntent(context));

        Intent serviceIntent = new Intent(context, WidgetContentRemoteViewsService.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .putExtra(WidgetContentRemoteViewsService.EXTRA_LARGE, large)
                .setData(Uri.parse("mindai://widget/content/" + appWidgetId + "/" + (large ? "large" : "small")));
        views.setRemoteAdapter(R.id.widget_list, serviceIntent);
        views.setPendingIntentTemplate(R.id.widget_list, itemTemplate(context, appWidgetId));
        return views;
    }

    private static PendingIntent summarizePendingIntent(Context context) {
        Intent intent = new Intent(context, WidgetActionReceiver.class);
        intent.setAction(WidgetActionReceiver.ACTION_SUMMARIZE);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, 4101, intent, flags);
    }

    private static PendingIntent openPendingIntent(Context context, String sessionId) {
        Intent intent = SummaryNotificationHelper.summaryIntent(context, sessionId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, 4102, intent, flags);
    }

    private static PendingIntent itemTemplate(Context context, int appWidgetId) {
        Intent intent = new Intent(context, MainActivity.class)
                .setAction(ACTION_OPEN_ITEM)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getActivity(context, 4300 + Math.abs(appWidgetId % 1000), intent, flags);
    }

    private static String time(long value) {
        return new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(value));
    }
}
