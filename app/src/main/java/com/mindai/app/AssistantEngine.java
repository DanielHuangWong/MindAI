package com.mindai.app;

import android.content.Context;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AssistantEngine {
    public static final class Result {
        public String message = "";
        public String action = "none";
        public String objectId = "";
        public boolean success;
        public boolean requiresConfirmation;
        public Models.AssistantPlan pendingPlan;
    }

    private static final SimpleDateFormat DATE_TIME = new SimpleDateFormat("M月d日 HH:mm", Locale.CHINA);
    private final Context context;
    private final AppDatabase db;
    private final SecurePrefs secure;
    private final CalendarRepository calendars;
    private final OpenRouterClient client;

    public AssistantEngine(Context context) {
        this(context, new OpenRouterClient());
    }

    AssistantEngine(Context context, OpenRouterClient client) {
        this.context = context.getApplicationContext();
        this.db = MindAiApp.get().db();
        this.secure = MindAiApp.get().secure();
        this.calendars = new CalendarRepository(context);
        this.client = client;
    }

    public Result process(String userText) {
        return processInternal(userText, true);
    }

    /** Used by the conversation UI after it has persisted the optimistic user bubble. */
    Result processRecorded(String userText) {
        return processInternal(userText, false);
    }

    private Result processInternal(String userText, boolean recordUserMessage) {
        String text = userText == null ? "" : userText.trim();
        if (text.isEmpty()) return failure("请输入你想查询或完成的事情");
        if (recordUserMessage) db.addConversationMessage("user", text, "", "", "");
        if (text.contains("重要") && (text.contains("今天") || text.contains("现在") || text.contains("需要知道"))) {
            return record(success("query_brief", "", briefAnswer()));
        }
        if (!secure.configured()) return record(failure("请先在“我的 → OpenRouter”完成连接，刚才的输入已经保留。"));
        if (!ComplianceSettings.hasAiDataConsent(context)) {
            return record(failure("请先在“我的 → OpenRouter”确认 AI 数据使用说明，刚才的输入已经保留。"));
        }
        try {
            OpenRouterClient.Config config = config();
            long now = System.currentTimeMillis();
            Models.AssistantPlan plan = client.assistantPlan(config, text,
                    db.todos("open", "", 80), calendars.events(now - 86_400_000L, now + 30L * 86_400_000L),
                    db.memories("active", "", 80), db.profileFacts("active", "", 80),
                    db.records("all", "", 80),
                    db.notifications(now - 30L * 86_400_000L, now + 86_400_000L, "", "all", "", 80));
            secure.updateActualModel(plan.actualModel);
            if (isDeleteAction(plan.action)) {
                plan.requiresConfirmation = true;
                plan.type = "confirmation";
            }
            if ("clarification".equals(plan.type)) {
                Result result = failure(plan.message.isEmpty() ? "我还需要一个更明确的信息。" : plan.message);
                result.success = true;
                return record(result);
            }
            if (plan.requiresConfirmation || "confirmation".equals(plan.type)) {
                Result result = new Result();
                result.action = plan.action;
                result.message = plan.message.isEmpty() ? confirmationMessage(plan) : plan.message;
                result.requiresConfirmation = true;
                result.pendingPlan = plan;
                return record(result);
            }
            return record(execute(plan));
        } catch (Exception e) {
            return record(failure(e.getMessage() == null || e.getMessage().isEmpty()
                    ? "助理暂时无法理解这条请求，请稍后重试" : e.getMessage()));
        }
    }

    public Result confirm(Models.AssistantPlan plan) {
        if (plan == null) return record(failure("确认内容已经失效，请重新发起操作"));
        return record(execute(plan));
    }

    private Result execute(Models.AssistantPlan plan) {
        try {
            String action = plan.action == null ? "none" : plan.action;
            switch (action) {
                case "query_notifications": return success(action, "", notificationAnswer(plan.query));
                case "query_todos": return success(action, "", todoAnswer(plan.query));
                case "query_calendar": return success(action, "", calendarAnswer(plan.query));
                case "query_memory": return success(action, "", memoryAnswer(plan.query));
                case "query_profile": return success(action, "", profileAnswer(plan.query));
                case "query_records": return success(action, "", recordAnswer(plan.query));
                case "create_todo": {
                    if (plan.title.isEmpty()) return failure("还需要待办标题");
                    String id = db.addManualTodo(plan.title, plan.description, plan.dueAt, plan.priority);
                    refreshBriefs();
                    return success(action, id, "已创建待办“" + plan.title + "”" + timeSuffix(plan.dueAt) + "。");
                }
                case "update_todo": {
                    Models.TodoItem todo = db.todo(plan.targetId);
                    if (todo == null) return failure("没有找到要修改的待办");
                    if (!plan.title.isEmpty()) todo.title = plan.title;
                    if (!plan.description.isEmpty()) todo.description = plan.description;
                    if (plan.dueAt > 0) todo.dueAt = plan.dueAt;
                    if (!plan.priority.isEmpty()) todo.priority = plan.priority;
                    db.updateTodo(todo); refreshBriefs();
                    return success(action, todo.id, "已更新待办“" + todo.title + "”" + timeSuffix(todo.dueAt) + "。");
                }
                case "complete_todo": {
                    Models.TodoItem todo = db.todo(plan.targetId);
                    if (todo == null) return failure("没有找到要完成的待办");
                    db.setTodoStatus(todo.id, "done"); refreshBriefs();
                    return success(action, todo.id, "已完成待办“" + todo.title + "”。");
                }
                case "delete_todo": {
                    Models.TodoItem todo = db.todo(plan.targetId);
                    if (todo == null) return failure("没有找到要删除的待办");
                    db.deleteTodo(todo.id); refreshBriefs();
                    return success(action, todo.id, "已删除待办“" + todo.title + "”。");
                }
                case "create_event": {
                    if (plan.title.isEmpty() || plan.startAt <= 0) return failure("还需要明确的日程标题和开始时间");
                    long end = plan.endAt > plan.startAt ? plan.endAt : plan.startAt + 3_600_000L;
                    long id = calendars.create(plan.title, plan.description, "", plan.startAt, end, plan.allDay, 0);
                    refreshBriefs();
                    return success(action, String.valueOf(id), "已创建日程“" + plan.title + "”，时间是 " + DATE_TIME.format(new Date(plan.startAt)) + "。");
                }
                case "update_event": {
                    long eventId = parseLong(plan.targetId);
                    Models.CalendarEvent event = findEvent(eventId);
                    if (event == null) return failure("没有找到要修改的日程");
                    long start = plan.startAt > 0 ? plan.startAt : event.startAt;
                    long end = plan.endAt > start ? plan.endAt : Math.max(start + 60_000L, event.endAt);
                    calendars.update(eventId, plan.title.isEmpty() ? event.title : plan.title,
                            plan.description.isEmpty() ? event.description : plan.description,
                            event.location, start, end, plan.allDay || event.allDay);
                    refreshBriefs();
                    return success(action, String.valueOf(eventId), "已更新日程“" + (plan.title.isEmpty() ? event.title : plan.title) + "”。");
                }
                case "delete_event": {
                    long eventId = parseLong(plan.targetId);
                    Models.CalendarEvent event = findEvent(eventId);
                    if (event == null) return failure("没有找到要删除的日程");
                    calendars.delete(eventId); refreshBriefs();
                    return success(action, String.valueOf(eventId), "已删除日程“" + event.title + "”。");
                }
                case "create_memory": {
                    String content = plan.description.isEmpty() ? plan.title : plan.description;
                    if (content.isEmpty()) return failure("还需要你希望我记住的内容");
                    String id = db.addManualMemory(plan.title.isEmpty() ? "用户明确记忆" : plan.title, content, plan.category);
                    return success(action, id, "已经记住：“" + content + "”。这条记忆会以用户提供内容优先保存。");
                }
                case "update_memory": {
                    Models.MemoryItem memory = db.memory(plan.targetId);
                    if (memory == null) return failure("没有找到要修改的记忆");
                    if (!plan.title.isEmpty()) memory.title = plan.title;
                    if (!plan.description.isEmpty()) memory.text = plan.description;
                    if (!plan.category.isEmpty() && !"other".equals(plan.category)) memory.category = plan.category;
                    db.updateMemory(memory);
                    return success(action, memory.id, "已更新记忆“" + memory.title + "”。");
                }
                case "delete_memory": {
                    Models.MemoryItem memory = db.memory(plan.targetId);
                    if (memory == null) return failure("没有找到要删除的记忆");
                    db.deleteMemory(memory.id);
                    return success(action, memory.id, "已删除记忆“" + memory.title + "”。");
                }
                case "create_profile": {
                    String content = plan.description.isEmpty() ? plan.title : plan.description;
                    if (content.isEmpty()) return failure("还需要你希望写入画像的内容");
                    String id = db.addManualProfileFact("用户提供", plan.title.isEmpty() ? "用户画像" : plan.title, content);
                    return success(action, id, "已加入个人画像：“" + content + "”。");
                }
                case "update_profile": {
                    Models.ProfileFact fact = db.profileFact(plan.targetId);
                    if (fact == null) return failure("没有找到要修改的画像信息");
                    if (!plan.title.isEmpty()) fact.title = plan.title;
                    if (!plan.description.isEmpty()) fact.content = plan.description;
                    db.updateProfileFact(fact);
                    return success(action, fact.id, "已更新画像“" + fact.title + "”。");
                }
                case "delete_profile": {
                    Models.ProfileFact fact = db.profileFact(plan.targetId);
                    if (fact == null) return failure("没有找到要删除的画像信息");
                    db.deleteProfileFact(fact.id);
                    return success(action, fact.id, "已删除画像“" + fact.title + "”。");
                }
                case "delete_record": {
                    Models.ImportantRecord record = db.record(plan.targetId);
                    if (record == null) return failure("没有找到要删除的重要信息");
                    db.deleteRecord(record.id);
                    return success(action, record.id, "已删除重要信息“" + record.title + "”。");
                }
                case "delete_notification": {
                    long notificationId = parseLong(plan.targetId);
                    Models.NotificationItem notification = db.notification(notificationId);
                    if (notification == null) return failure("没有找到要删除的通知");
                    db.deleteNotification(notification.id);
                    return success(action, String.valueOf(notification.id), "已从信息库移除通知“" + notification.title + "”。");
                }
                default:
                    return success("none", "", plan.message.isEmpty() ? "我可以帮你查询或修改日程、待办、记忆和画像。" : plan.message);
            }
        } catch (Exception e) {
            return failure(e.getMessage() == null || e.getMessage().isEmpty() ? "操作失败，请稍后重试" : e.getMessage());
        }
    }

    private String todoAnswer(String query) {
        List<Models.TodoItem> items = db.todos("open", query, 10);
        if (items.isEmpty()) return query.isEmpty() ? "当前没有未完成待办。" : "没有找到与“" + query + "”相关的未完成待办。";
        StringBuilder b = new StringBuilder("你有 ").append(items.size()).append(" 项相关待办：");
        for (Models.TodoItem item : items) b.append("\n• ").append(item.title).append(timeSuffix(item.dueAt));
        return b.toString();
    }

    private String notificationAnswer(String query) {
        long now = System.currentTimeMillis();
        List<Models.NotificationItem> items = db.notifications(now - 30L * 86_400_000L, now + 86_400_000L, query, "all", "", 10);
        if (items.isEmpty()) return query.isEmpty() ? "最近 30 天没有通知。" : "最近 30 天没有找到与“" + query + "”相关的通知。";
        StringBuilder b = new StringBuilder("最近相关通知：");
        for (Models.NotificationItem item : items) b.append("\n• ").append(DATE_TIME.format(new Date(item.postedAt))).append("  ").append(item.appName).append("：").append(item.title);
        return b.toString();
    }

    private String briefAnswer() {
        List<Models.AssistantBriefItem> items = db.activeBriefItems(7);
        if (items.isEmpty()) return "现在没有特别需要你处理的事情。";
        StringBuilder b = new StringBuilder("现在最值得关注的是：");
        for (Models.AssistantBriefItem item : items) b.append("\n• ").append(item.title);
        return b.toString();
    }

    private String calendarAnswer(String query) {
        long now = System.currentTimeMillis();
        List<Models.CalendarEvent> items = calendars.events(now - 86_400_000L, now + 30L * 86_400_000L);
        StringBuilder b = new StringBuilder();
        int count = 0;
        for (Models.CalendarEvent event : items) {
            if (!query.isEmpty() && !(event.title + " " + event.location).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) continue;
            if (count++ >= 10) break;
            b.append("\n• ").append(DATE_TIME.format(new Date(event.startAt))).append("  ").append(event.title);
        }
        return count == 0 ? "近期没有找到相关日程。" : "近期相关日程：" + b;
    }

    private String memoryAnswer(String query) {
        List<Models.MemoryItem> items = db.memories("active", query, 10);
        if (items.isEmpty()) return "没有找到相关记忆。";
        StringBuilder b = new StringBuilder("找到这些相关记忆：");
        for (Models.MemoryItem item : items) b.append("\n• ").append(item.text);
        return b.toString();
    }

    private String profileAnswer(String query) {
        List<Models.ProfileFact> items = db.profileFacts("active", query, 10);
        if (items.isEmpty()) return "没有找到相关画像信息。";
        StringBuilder b = new StringBuilder("当前画像中有这些相关信息：");
        for (Models.ProfileFact item : items) b.append("\n• ").append(item.title).append("：").append(item.content);
        return b.toString();
    }

    private String recordAnswer(String query) {
        List<Models.ImportantRecord> items = db.records("all", query, 10);
        if (items.isEmpty()) return "没有找到相关重要信息。";
        StringBuilder b = new StringBuilder("找到这些重要信息：");
        for (Models.ImportantRecord item : items) b.append("\n• ").append(item.title).append("：").append(item.content);
        return b.toString();
    }

    private Models.CalendarEvent findEvent(long id) {
        if (id <= 0) return null;
        long now = System.currentTimeMillis();
        for (Models.CalendarEvent event : calendars.events(now - 365L * 86_400_000L, now + 365L * 86_400_000L)) {
            if (event.id == id) return event;
        }
        return null;
    }

    private void refreshBriefs() { new AssistantBriefEngine(context).refresh(); }

    private OpenRouterClient.Config config() {
        OpenRouterClient.Config config = new OpenRouterClient.Config();
        config.apiKey = secure.apiKey(); config.baseUrl = secure.baseUrl(); config.mode = secure.mode();
        config.fixedModel = secure.fixedModel(); config.zdr = secure.zdr();
        return config;
    }

    private Result record(Result result) {
        db.addConversationMessage("assistant", result.message, result.action, result.objectId,
                result.success ? "success" : result.requiresConfirmation ? "confirmation" : "error");
        return result;
    }

    private static Result success(String action, String id, String message) {
        Result result = new Result(); result.success = true; result.action = action; result.objectId = id; result.message = message; return result;
    }

    private static Result failure(String message) {
        Result result = new Result(); result.success = false; result.message = message; return result;
    }

    private static String confirmationMessage(Models.AssistantPlan plan) {
        if (plan.action.startsWith("delete_")) return "确认删除“" + (plan.title.isEmpty() ? plan.targetId : plan.title) + "”？";
        return "确认执行这项修改？";
    }

    private static boolean isDeleteAction(String action) {
        return action != null && action.startsWith("delete_");
    }

    private static String timeSuffix(long time) { return time <= 0 ? "" : "（" + DATE_TIME.format(new Date(time)) + "）"; }
    private static long parseLong(String value) { try { return Long.parseLong(value); } catch (Exception e) { return 0; } }
}
