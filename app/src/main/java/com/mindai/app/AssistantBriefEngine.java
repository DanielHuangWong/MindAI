package com.mindai.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;

public final class AssistantBriefEngine {
    private static final long HOUR = 3_600_000L;
    private static final long DAY = 86_400_000L;

    private final Context context;
    private final AppDatabase db;
    private final CalendarRepository calendars;

    public AssistantBriefEngine(Context context) {
        this.context = context.getApplicationContext();
        this.db = MindAiApp.get().db();
        this.calendars = new CalendarRepository(context);
    }

    public List<Models.AssistantBriefItem> refresh() {
        long now = System.currentTimeMillis();
        List<Models.AssistantBriefItem> derived = new ArrayList<>();
        for (Models.TodoItem todo : db.todos("open", "", 200)) {
            Models.AssistantBriefItem item = fromTodo(todo, now);
            if (item != null) derived.add(item);
        }

        List<Models.CalendarEvent> upcoming = calendars.events(now - HOUR, now + 48 * HOUR);
        for (Models.CalendarEvent event : upcoming) {
            Models.AssistantBriefItem item = fromCalendar(event, now);
            if (item != null) derived.add(item);
        }
        for (int i = 0; i < upcoming.size(); i++) {
            Models.CalendarEvent a = upcoming.get(i);
            if (a.endAt <= now || "free".equals(a.availability)) continue;
            for (int j = i + 1; j < upcoming.size(); j++) {
                Models.CalendarEvent b = upcoming.get(j);
                if (b.startAt >= a.endAt) break;
                if (a.id == b.id || "free".equals(b.availability)) continue;
                long overlap = Math.min(a.endAt, b.endAt) - Math.max(a.startAt, b.startAt);
                if (overlap > 0) derived.add(conflict(a, b, overlap, now));
            }
        }
        db.replaceDerivedBriefs(derived);
        MindAiWidgetUpdater.updateAll(context);
        return db.activeBriefItems(7);
    }

    private Models.AssistantBriefItem fromTodo(Models.TodoItem todo, long now) {
        if (todo.dueAt <= 0) return "urgent".equals(todo.priority) || "high".equals(todo.priority)
                ? brief("todo:" + todo.id, "action", todo.title, todo.description,
                "这是一个尚未完成的" + priorityName(todo.priority) + "待办。", "查看待办",
                todo.priority, todo.dueAt, 62 + priorityBoost(todo.priority), todo.id, "") : null;
        long remaining = todo.dueAt - now;
        if (remaining > 48 * HOUR) return null;
        String urgency = remaining < 0 ? "critical" : remaining <= 2 * HOUR ? "high" : "medium";
        String reason = remaining < 0 ? "这项待办已经逾期。"
                : remaining <= 2 * HOUR ? "距离截止时间不足 2 小时。" : "这项待办将在 48 小时内到期。";
        double score = remaining < 0 ? 118 : remaining <= 2 * HOUR ? 94 : 68;
        score += priorityBoost(todo.priority);
        return brief("todo:" + todo.id, "action", todo.title, todo.description, reason,
                "查看待办", urgency, todo.dueAt, score, todo.id, "");
    }

    private Models.AssistantBriefItem fromCalendar(Models.CalendarEvent event, long now) {
        if (event.endAt <= now || event.startAt > now + 24 * HOUR || "free".equals(event.availability)) return null;
        long remaining = event.startAt - now;
        String urgency = remaining <= HOUR ? "high" : "medium";
        String reason = remaining <= 0 ? "这项日程正在进行。"
                : remaining <= 2 * HOUR ? "这项日程将在 2 小时内开始。" : "这是未来 24 小时内的日程。";
        Models.AssistantBriefItem item = brief("calendar:" + event.instanceKey, "schedule", event.title,
                event.location, reason, "查看日程", urgency, event.startAt,
                remaining <= HOUR ? 88 : 58, "", event.instanceKey);
        item.validUntil = event.endAt;
        return item;
    }

    private Models.AssistantBriefItem conflict(Models.CalendarEvent a, Models.CalendarEvent b, long overlap, long now) {
        String first = a.instanceKey.compareTo(b.instanceKey) <= 0 ? a.instanceKey : b.instanceKey;
        String second = first.equals(a.instanceKey) ? b.instanceKey : a.instanceKey;
        Models.AssistantBriefItem item = brief("conflict:" + first + ":" + second, "conflict",
                "“" + a.title + "”与“" + b.title + "”时间冲突",
                "两个日程重叠约 " + Math.max(1, overlap / 60_000L) + " 分钟。",
                "日程时间发生重叠，可能需要调整安排。", "查看冲突", "high",
                Math.min(a.startAt, b.startAt), 105, "", a.instanceKey + "," + b.instanceKey);
        item.validUntil = Math.max(a.endAt, b.endAt);
        item.validFrom = now;
        return item;
    }

    private static Models.AssistantBriefItem brief(String id, String type, String title, String detail,
                                                    String reason, String action, String urgency, long validUntil,
                                                    double score, String todoId, String calendarKeys) {
        Models.AssistantBriefItem item = new Models.AssistantBriefItem();
        item.id = id;
        item.type = type;
        item.title = title == null || title.trim().isEmpty() ? "未命名事项" : title.trim();
        item.detail = detail == null ? "" : detail.trim();
        item.reason = reason;
        item.suggestedAction = action;
        item.urgency = urgency;
        item.impact = "critical".equals(urgency) || "high".equals(urgency) ? "high" : "medium";
        item.rankScore = score;
        item.confidence = 1;
        item.validFrom = System.currentTimeMillis();
        item.validUntil = validUntil;
        item.status = "active";
        item.relatedTodoIds = todoId;
        item.relatedCalendarKeys = calendarKeys;
        item.feedback = "none";
        item.origin = "local";
        item.createdAt = item.validFrom;
        item.updatedAt = item.validFrom;
        return item;
    }

    private static int priorityBoost(String priority) {
        if ("urgent".equals(priority)) return 20;
        if ("high".equals(priority)) return 10;
        if ("low".equals(priority)) return -5;
        return 0;
    }

    private static String priorityName(String priority) {
        if ("urgent".equals(priority)) return "紧急";
        if ("high".equals(priority)) return "高优先级";
        return "重要";
    }
}
