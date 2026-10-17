package com.mindai.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;

public final class MindAiSmallWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        MindAiWidgetUpdater.update(context, manager, new ComponentName(context, MindAiSmallWidgetProvider.class), false);
    }
}
