package com.mindai.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BriefActionReceiver extends BroadcastReceiver {
    static final String ACTION_SNOOZE = "com.mindai.app.action.SNOOZE_BRIEF";
    static final String ACTION_COMPLETE = "com.mindai.app.action.COMPLETE_BRIEF";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(SummaryNotificationHelper.EXTRA_BRIEF_ID);
        if (id == null || id.isEmpty()) return;
        AppDatabase db = MindAiApp.get().db();
        Models.AssistantBriefItem item = db.briefItem(id);
        if (item == null) return;
        if (ACTION_SNOOZE.equals(intent.getAction())) {
            db.setBriefStatus(id, "snoozed", System.currentTimeMillis() + 3_600_000L);
        } else if (ACTION_COMPLETE.equals(intent.getAction())) {
            if (!item.relatedTodoIds.isEmpty()) db.setTodoStatus(item.relatedTodoIds.split(",")[0], "done");
            db.setBriefStatus(id, "resolved", 0);
        }
        new AssistantBriefEngine(context).refresh();
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                .cancel(7400 + Math.abs(id.hashCode() % 500));
    }
}
