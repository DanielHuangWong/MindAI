package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class WidgetSummaryState {
    static final String STATUS_IDLE = "idle";
    static final String STATUS_RUNNING = "running";
    static final String STATUS_DONE = "done";
    static final String STATUS_ERROR = "error";

    static final String KIND_SUMMARY = "summary";
    static final String KIND_SECTION = "section";
    static final String KIND_TODO = "todo";
    static final String KIND_CALENDAR = "calendar";
    static final String KIND_MEMORY = "memory";
    static final String KIND_PROFILE = "profile";
    static final String KIND_EMPTY = "empty";

    private static final String PREFS = "mindai_widget_summary";
    private static final String EMPTY_SUMMARY = "上一周期没有发现需要特别关注的新信息。";

    static final class Item {
        String kind = KIND_EMPTY;
        String objectId = "";
        String sourceSessionId = "";
        String badge = "";
        String title = "";
        String detail = "";
        String meta = "";
        String state = "";
        long eventAt;
        long changedAt;
        boolean latestBatch;

        long stableId() {
            String key = kind + ':' + objectId + ':' + sourceSessionId;
            return key.hashCode() & 0x7fffffffL;
        }
    }

    static final class Content {
        String latestSessionId = "";
        String oneSentenceSummary = "";
        long lastCompletedAt;
        int structuredCount;
        final List<Item> items = new ArrayList<>();
    }

    private WidgetSummaryState() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void markRunning(Context context, int count, String progressText) {
        prefs(context).edit()
                .putString("status", STATUS_RUNNING)
                .putInt("started_count", Math.max(0, count))
                .putString("progress", progressText == null ? "正在总结…" : progressText)
                .apply();
    }

    static void markDone(Context context, Models.SummarySession session, String importantText) {
        String changes = committedChangeLabels(context, session);
        String summary = clean(importantText).isEmpty() ? EMPTY_SUMMARY : oneSentence(importantText, "");
        long completedAt = session != null && session.completedAt > 0
                ? session.completedAt : System.currentTimeMillis();
        prefs(context).edit()
                .putString("status", STATUS_DONE)
                .putInt("started_count", session == null ? 0 : session.notificationCount)
                .putString("last_session_id", session == null ? "" : session.id)
                .putString("last_summary", summary)
                // Keep these two keys for seamless upgrades from the old widget and older tests.
                .putString("last_important", summary)
                .putString("last_changes", changes)
                .putLong("completed_at", completedAt)
                .putString("progress", "总结完成")
                .apply();
    }

    static void markIdle(Context context) {
        prefs(context).edit()
                .putString("status", STATUS_IDLE)
                .putInt("started_count", 0)
                .putString("progress", "暂无待整理")
                .apply();
    }

    static void markError(Context context, String message) {
        prefs(context).edit()
                .putString("status", STATUS_ERROR)
                .putString("progress", message == null || message.isEmpty() ? "总结失败" : message)
                .apply();
    }

    static boolean isRunning(Context context) {
        return STATUS_RUNNING.equals(prefs(context).getString("status", STATUS_IDLE));
    }

    static Content content(Context context) {
        return content(context, System.currentTimeMillis());
    }

    static Content content(Context context, long now) {
        Content content = new Content();
        MindAiApp app = MindAiApp.get();
        if (app == null) {
            content.items.add(emptyItem());
            return content;
        }
        AppDatabase db = app.db();
        Models.SummarySession latest = db.latestSuccessfulSession();
        SharedPreferences state = prefs(context);
        if (latest == null) {
            content.items.add(emptyItem());
            return content;
        }

        content.latestSessionId = latest.id;
        content.lastCompletedAt = latest.completedAt;
        String storedSession = state.getString("last_session_id", "");
        String candidate = latest.id.equals(storedSession) ? state.getString("last_summary", "") : "";
        if (candidate == null || candidate.trim().isEmpty()) {
            candidate = latest.id.equals(storedSession) ? state.getString("last_important", "") : "";
        }
        if (candidate == null || candidate.trim().isEmpty()) candidate = latest.overview;
        content.oneSentenceSummary = oneSentence(candidate, latest.overview);
        content.items.add(summaryItem(latest.id, content.oneSentenceSummary));

        long[] range = dayRange(now);
        List<Models.SummarySession> todaySessions = db.successfulSessionsBetween(range[0], range[1]);
        Set<String> sessionIds = new HashSet<>();
        for (Models.SummarySession session : todaySessions) {
            sessionIds.add(session.id);
        }
        if (sessionIds.isEmpty()) return content;

        Map<String, Item> structured = new LinkedHashMap<>();
        for (Models.TodoItem todo : db.todosForSuccessfulSessions(sessionIds)) {
            Item item = new Item();
            item.kind = KIND_TODO; item.objectId = todo.id; item.sourceSessionId = todo.sourceSummaryId;
            item.badge = "TODO"; item.title = fallback(todo.title, "未命名待办"); item.detail = clean(todo.description);
            item.meta = todoMeta(todo, now); item.state = todo.status; item.eventAt = todo.dueAt; item.changedAt = todo.updatedAt;
            item.latestBatch = latest.id.equals(item.sourceSessionId);
            structured.put(item.kind + ':' + item.objectId, item);
        }
        for (Models.MemoryItem memory : db.memoriesForSuccessfulSessions(sessionIds)) {
            Item item = new Item();
            item.kind = KIND_MEMORY; item.objectId = memory.id; item.sourceSessionId = memory.sourceSummaryId;
            item.badge = "记忆"; item.title = fallback(memory.title, "新记忆"); item.detail = clean(memory.text);
            item.meta = changeLabel(memory.createdAt, memory.updatedAt) + " · " + time(memory.updatedAt);
            item.state = memory.status; item.changedAt = memory.updatedAt; item.latestBatch = latest.id.equals(item.sourceSessionId);
            structured.put(item.kind + ':' + item.objectId, item);
        }
        for (Models.ProfileFact fact : db.profileFactsForSuccessfulSessions(sessionIds)) {
            Item item = new Item();
            item.kind = KIND_PROFILE; item.objectId = fact.id; item.sourceSessionId = fact.sourceSummaryId;
            item.badge = "画像更新"; item.title = fallback(fact.title, "画像事实"); item.detail = clean(fact.content);
            item.meta = fallback(fact.section, "其他") + " · " + changeLabel(fact.createdAt, fact.updatedAt) + " · " + time(fact.updatedAt);
            item.state = fact.status; item.changedAt = fact.updatedAt; item.latestBatch = latest.id.equals(item.sourceSessionId);
            structured.put(item.kind + ':' + item.objectId, item);
        }

        CalendarRepository calendar = new CalendarRepository(context);
        boolean canReadCalendar = calendar.canRead();
        for (Models.SummarySession session : todaySessions) {
            for (Models.CalendarEvent committed : CalendarAutoCreator.committedCalendarEvents(context, session.id)) {
                Models.CalendarEvent current = canReadCalendar ? calendar.event(committed.id, committed.startAt) : committed;
                boolean cancelled = canReadCalendar && current == null;
                Models.CalendarEvent shown = current == null ? committed : current;
                Item item = new Item();
                item.kind = KIND_CALENDAR; item.objectId = String.valueOf(committed.id); item.sourceSessionId = session.id;
                item.badge = "日程"; item.title = fallback(shown.title, "未命名日程"); item.detail = clean(shown.location);
                item.meta = (cancelled ? "已取消 · " : "") + calendarTime(shown);
                item.state = cancelled ? "cancelled" : "active"; item.eventAt = shown.startAt;
                item.changedAt = session.completedAt; item.latestBatch = latest.id.equals(session.id);
                structured.put(item.kind + ':' + item.objectId, item);
            }
        }

        List<Item> ordered = new ArrayList<>(structured.values());
        ordered.sort(Comparator.comparingInt((Item item) -> sortRank(item, now))
                .thenComparing((Item a, Item b) -> Long.compare(b.changedAt, a.changedAt))
                .thenComparing(item -> item.title));
        content.structuredCount = ordered.size();
        if (!ordered.isEmpty()) {
            Item section = new Item();
            section.kind = KIND_SECTION; section.objectId = "today"; section.badge = "今日沉淀";
            section.title = "今日沉淀 · " + ordered.size();
            content.items.add(section);
            content.items.addAll(ordered);
        }
        return content;
    }

    static String committedChangeLabels(Context context, Models.SummarySession session) {
        if (session == null || session.id == null || session.id.isEmpty()) return "";
        AppDatabase db = MindAiApp.get().db();
        int calendar = CalendarAutoCreator.committedCalendarCount(context, session.id);
        int todos = db.todosForSession(session.id, 500).size();
        int memories = db.memoriesForSession(session.id, 500).size();
        int profile = db.profileFactsForSession(session.id, 500).size();
        StringBuilder labels = new StringBuilder();
        append(labels, "新日程", calendar); append(labels, "新待办", todos);
        append(labels, "新记忆", memories); append(labels, "画像更新", profile);
        return labels.toString();
    }

    static String oneSentence(String primary, String fallback) {
        String value = clean(primary);
        if (value.isEmpty()) value = clean(fallback);
        if (value.isEmpty()) return EMPTY_SUMMARY;
        int sentenceEnd = firstSentenceEnd(value);
        if (sentenceEnd > 0 && sentenceEnd < value.length()) value = value.substring(0, sentenceEnd);
        if (value.length() > 84) value = value.substring(0, 83).trim() + "…";
        if (!value.endsWith("。") && !value.endsWith("！") && !value.endsWith("？")
                && !value.endsWith("!") && !value.endsWith("?") && !value.endsWith("…")) value += "。";
        return value;
    }

    private static Item summaryItem(String sessionId, String summary) {
        Item item = new Item();
        item.kind = KIND_SUMMARY; item.objectId = sessionId; item.sourceSessionId = sessionId;
        item.badge = "上一周期"; item.title = summary; item.latestBatch = true;
        return item;
    }

    private static Item emptyItem() {
        Item item = new Item();
        item.kind = KIND_EMPTY; item.objectId = "empty"; item.badge = "尚未整理";
        item.title = "完成首次整理后，这里会显示有价值的信息。";
        return item;
    }

    private static int sortRank(Item item, long now) {
        if (KIND_TODO.equals(item.kind)) {
            if ("open".equals(item.state) && item.eventAt > 0 && item.eventAt < now) return 0;
            if (item.meta.contains("紧急") || item.meta.contains("高优先级")) return 0;
            return 2;
        }
        if (KIND_CALENDAR.equals(item.kind)) {
            if (!"cancelled".equals(item.state) && item.eventAt >= now) return 1;
            return 2;
        }
        if (KIND_MEMORY.equals(item.kind)) return 3;
        if (KIND_PROFILE.equals(item.kind)) return 4;
        return 5;
    }

    private static String todoMeta(Models.TodoItem todo, long now) {
        if ("done".equals(todo.status)) return "已完成 · " + priority(todo.priority);
        if ("archived".equals(todo.status)) return "已归档 · " + priority(todo.priority);
        String due = todo.dueAt <= 0 ? "无截止时间" : dateTime(todo.dueAt);
        if (todo.dueAt > 0 && todo.dueAt < now) due = "已逾期 · " + due;
        return due + " · " + priority(todo.priority);
    }

    private static String priority(String value) {
        if ("urgent".equals(value)) return "紧急";
        if ("high".equals(value)) return "高优先级";
        if ("low".equals(value)) return "低优先级";
        return "中优先级";
    }

    private static String changeLabel(long createdAt, long updatedAt) {
        return createdAt > 0 && Math.abs(updatedAt - createdAt) < 1_500L ? "新增" : "更新";
    }

    private static String calendarTime(Models.CalendarEvent event) {
        if (event.allDay) return new SimpleDateFormat("M月d日 · 全天", Locale.CHINA).format(new Date(event.startAt));
        SimpleDateFormat day = new SimpleDateFormat("M月d日", Locale.CHINA);
        SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
        return day.format(new Date(event.startAt)) + " " + clock.format(new Date(event.startAt))
                + "–" + clock.format(new Date(event.endAt));
    }

    private static String dateTime(long value) {
        return new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(new Date(value));
    }

    private static String time(long value) {
        return value <= 0 ? "" : new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(value));
    }

    private static long[] dayRange(long now) {
        Calendar start = Calendar.getInstance();
        start.setTimeInMillis(now); start.set(Calendar.HOUR_OF_DAY, 0); start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0); start.set(Calendar.MILLISECOND, 0);
        Calendar end = (Calendar) start.clone(); end.add(Calendar.DAY_OF_MONTH, 1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    private static int firstSentenceEnd(String value) {
        int result = -1;
        for (char marker : new char[]{'。', '！', '？', '!', '?'}) {
            int index = value.indexOf(marker, Math.min(12, value.length()));
            if (index >= 0 && (result < 0 || index < result)) result = index + 1;
        }
        return result;
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String fallback(String value, String replacement) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? replacement : cleaned;
    }

    private static void append(StringBuilder labels, String name, int count) {
        if (count <= 0) return;
        if (labels.length() > 0) labels.append("  ·  ");
        labels.append(name).append(' ').append(count);
    }
}
