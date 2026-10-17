package com.mindai.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.Collections;
import java.util.List;

public final class WidgetContentRemoteViewsService extends RemoteViewsService {
    static final String EXTRA_LARGE = "large";

    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent != null && intent.getBooleanExtra(EXTRA_LARGE, false));
    }

    static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final boolean large;
        private List<WidgetSummaryState.Item> items = Collections.emptyList();

        Factory(Context context, boolean large) {
            this.context = context.getApplicationContext();
            this.large = large;
        }

        @Override public void onCreate() {}

        @Override public void onDataSetChanged() {
            WidgetSummaryState.Content content = WidgetSummaryState.content(context);
            items = content == null ? Collections.emptyList() : content.items;
        }

        @Override public void onDestroy() { items = Collections.emptyList(); }

        @Override public int getCount() { return items.size(); }

        @Override public RemoteViews getViewAt(int position) {
            if (position < 0 || position >= items.size()) return null;
            WidgetSummaryState.Item item = items.get(position);
            RemoteViews views = new RemoteViews(context.getPackageName(), large
                    ? R.layout.widget_content_item_large : R.layout.widget_content_item_small);
            views.setTextViewText(R.id.widget_item_badge, item.badge);
            String title = item.title;
            if (WidgetSummaryState.KIND_TODO.equals(item.kind) && "done".equals(item.state)) title = "✓ " + title;
            views.setTextViewText(R.id.widget_item_title, title);
            views.setTextViewText(R.id.widget_item_detail, item.detail);
            views.setTextViewText(R.id.widget_item_meta, item.meta);
            views.setViewVisibility(R.id.widget_item_detail, item.detail.isEmpty() ? View.GONE : View.VISIBLE);
            views.setViewVisibility(R.id.widget_item_meta, item.meta.isEmpty() ? View.GONE : View.VISIBLE);
            views.setViewVisibility(R.id.widget_item_latest,
                    item.latestBatch && !WidgetSummaryState.KIND_SUMMARY.equals(item.kind) ? View.VISIBLE : View.GONE);

            int accent = Color.rgb(24, 119, 242);
            int background = R.drawable.widget_panel_bg;
            int icon = R.drawable.ic_widget_summary;
            if (WidgetSummaryState.KIND_CALENDAR.equals(item.kind)) {
                accent = Color.rgb(111, 66, 193); background = R.drawable.widget_item_calendar_bg; icon = R.drawable.ic_widget_calendar;
            } else if (WidgetSummaryState.KIND_MEMORY.equals(item.kind)) {
                accent = Color.rgb(163, 93, 0); background = R.drawable.widget_item_memory_bg; icon = R.drawable.ic_widget_memory;
            } else if (WidgetSummaryState.KIND_PROFILE.equals(item.kind)) {
                accent = Color.rgb(35, 134, 54); background = R.drawable.widget_item_profile_bg; icon = R.drawable.ic_widget_profile;
            } else if (WidgetSummaryState.KIND_TODO.equals(item.kind)) {
                icon = R.drawable.ic_widget_todo;
            } else if (WidgetSummaryState.KIND_EMPTY.equals(item.kind)) {
                accent = Color.rgb(101, 103, 107); background = R.drawable.widget_item_neutral_bg; icon = R.drawable.ic_widget_empty;
            } else if (WidgetSummaryState.KIND_SECTION.equals(item.kind)) {
                accent = Color.rgb(101, 103, 107); background = R.drawable.widget_item_section_bg;
                views.setViewVisibility(R.id.widget_item_icon, View.GONE);
                views.setViewVisibility(R.id.widget_item_badge, View.GONE);
            }
            views.setInt(R.id.widget_item_card, "setBackgroundResource", background);
            views.setTextColor(R.id.widget_item_badge, accent);
            views.setTextColor(R.id.widget_item_latest, accent);
            views.setImageViewResource(R.id.widget_item_icon, icon);
            if ("done".equals(item.state) || "cancelled".equals(item.state) || "archived".equals(item.state)) {
                views.setTextColor(R.id.widget_item_title, Color.rgb(101, 103, 107));
                views.setTextColor(R.id.widget_item_detail, Color.rgb(101, 103, 107));
            }

            if (!WidgetSummaryState.KIND_SECTION.equals(item.kind)) {
                Intent fillIn = new Intent();
                fillIn.putExtra(MindAiWidgetUpdater.EXTRA_ITEM_TYPE, item.kind);
                fillIn.putExtra(MindAiWidgetUpdater.EXTRA_ITEM_ID, item.objectId);
                fillIn.putExtra(MindAiWidgetUpdater.EXTRA_SOURCE_SESSION_ID, item.sourceSessionId);
                fillIn.putExtra(MindAiWidgetUpdater.EXTRA_EVENT_START, item.eventAt);
                views.setOnClickFillInIntent(R.id.widget_item_root, fillIn);
            }
            return views;
        }

        @Override public RemoteViews getLoadingView() {
            RemoteViews views = new RemoteViews(context.getPackageName(), large
                    ? R.layout.widget_content_item_large : R.layout.widget_content_item_small);
            views.setTextViewText(R.id.widget_item_badge, "MindAI");
            views.setTextViewText(R.id.widget_item_title, "正在读取最新内容…");
            views.setViewVisibility(R.id.widget_item_detail, View.GONE);
            views.setViewVisibility(R.id.widget_item_meta, View.GONE);
            views.setViewVisibility(R.id.widget_item_latest, View.GONE);
            views.setImageViewResource(R.id.widget_item_icon, R.drawable.ic_widget_summary);
            return views;
        }

        @Override public int getViewTypeCount() { return 1; }

        @Override public long getItemId(int position) {
            return position >= 0 && position < items.size() ? items.get(position).stableId() : position;
        }

        @Override public boolean hasStableIds() { return true; }
    }
}
