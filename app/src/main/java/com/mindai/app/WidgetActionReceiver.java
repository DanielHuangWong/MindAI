package com.mindai.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Non-exported trampoline for the explicit widget action PendingIntent. */
public final class WidgetActionReceiver extends BroadcastReceiver {
    static final String ACTION_SUMMARIZE = "com.mindai.app.action.WIDGET_SUMMARIZE_INTERNAL";
    static final String ACTION_CANCEL = "com.mindai.app.action.CANCEL_WIDGET_SUMMARY_INTERNAL";
    private static final long MIN_REQUEST_INTERVAL_MS = 3_000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            context.stopService(new Intent(context, WidgetSummaryService.class));
            WidgetSummaryState.markIdle(context);
            MindAiWidgetUpdater.updateAll(context);
            return;
        }
        if (!ACTION_SUMMARIZE.equals(intent.getAction())) return;
        long now = System.currentTimeMillis();
        android.content.SharedPreferences settings =
                context.getSharedPreferences(ComplianceSettings.PREFS, Context.MODE_PRIVATE);
        long last = settings.getLong("widget_summary_requested_at", 0);
        if (now - last < MIN_REQUEST_INTERVAL_MS) return;
        settings.edit().putLong("widget_summary_requested_at", now).apply();

        Intent service = new Intent(context, WidgetSummaryService.class)
                .setAction(WidgetSummaryService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);
    }
}
