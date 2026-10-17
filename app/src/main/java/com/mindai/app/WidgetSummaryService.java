package com.mindai.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WidgetSummaryService extends Service {
    static final String ACTION_START = "com.mindai.app.action.START_WIDGET_SUMMARY";
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private ExecutorService executor;
    private volatile boolean cancelled;

    @Override public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        SummaryNotificationHelper.ensureChannel(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(SummaryNotificationHelper.FOREGROUND_ID,
                SummaryNotificationHelper.foreground(this, "正在准备总结…"));
        if (!RUNNING.compareAndSet(false, true)) {
            WidgetSummaryState.markRunning(this, MindAiApp.get().db().countUnsummarized(), "已有一轮总结正在进行…");
            MindAiWidgetUpdater.updateAll(this);
            return START_NOT_STICKY;
        }
        executor.execute(this::runSummary);
        return START_NOT_STICKY;
    }

    private void runSummary() {
        int pending = MindAiApp.get().db().countUnsummarized();
        WidgetSummaryState.markRunning(this, pending, "正在固定通知范围…");
        MindAiWidgetUpdater.updateAll(this);
        try {
            if (pending <= 0) {
                WidgetSummaryState.markIdle(this);
                MindAiWidgetUpdater.updateAll(this);
                return;
            }
            if (!MindAiApp.get().secure().configured()) {
                throw new IllegalStateException("请先在 App 内完成 OpenRouter 连接");
            }
            if (!ComplianceSettings.hasAiDataConsent(this)) {
                throw new IllegalStateException("请先在 App 内确认 AI 数据使用说明");
            }
            if (ProcessingCoordinator.isRunning()) {
                WidgetSummaryState.markRunning(this, pending, "已有一轮整理正在进行…");
                MindAiWidgetUpdater.updateAll(this);
                return;
            }
            SummaryEngine engine = new SummaryEngine(MindAiApp.get().db(), MindAiApp.get().secure());
            final Models.SummarySession[] success = new Models.SummarySession[1];
            final String[] error = {""};
            engine.summarize(0, Long.MAX_VALUE, "", new SummaryEngine.Listener() {
                @Override public void onProgress(String step, int percent) {
                    if (cancelled) return;
                    WidgetSummaryState.markRunning(WidgetSummaryService.this, pending, step);
                    MindAiWidgetUpdater.updateAll(WidgetSummaryService.this);
                    try {
                        startForeground(SummaryNotificationHelper.FOREGROUND_ID,
                                SummaryNotificationHelper.foreground(WidgetSummaryService.this, step));
                    } catch (Exception ignored) {}
                }

                @Override public void onSuccess(Models.SummarySession session) {
                    success[0] = session;
                }

                @Override public void onError(String message) {
                    error[0] = message;
                }
            });
            if (cancelled) return;
            if (success[0] == null) throw new IllegalStateException(error[0].isEmpty() ? "总结失败" : error[0]);
            Models.SummarySession stored = MindAiApp.get().db().session(success[0].id);
            if (stored == null) stored = success[0];
            String important = mostImportantText(stored);
            WidgetSummaryState.markDone(this, stored, important);
            MindAiWidgetUpdater.updateAll(this);
            SummaryNotificationHelper.notifyCompleted(this, stored, important);
        } catch (Exception e) {
            if (cancelled) return;
            String message = e.getMessage() == null || e.getMessage().isEmpty() ? "总结失败，请稍后重试" : e.getMessage();
            WidgetSummaryState.markError(this, message);
            MindAiWidgetUpdater.updateAll(this);
            SummaryNotificationHelper.notifyFailed(this, message);
        } finally {
            RUNNING.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    static String mostImportantText(Models.SummarySession session) {
        if (session == null) return "";
        Models.NeedToKnow need = MindAiApp.get().db().needToKnowForSession(session.id);
        if (need != null && !"none".equals(need.urgency) && (!need.title.isEmpty() || !need.detail.isEmpty())) {
            return (need.title + "：" + need.detail).trim();
        }
        List<Models.TodoItem> todos = MindAiApp.get().db().todos("all", "", 500);
        Models.TodoItem bestTodo = null;
        for (Models.TodoItem todo : todos) {
            if (!session.id.equals(todo.sourceSummaryId)) continue;
            if (bestTodo == null || rank(todo.priority) > rank(bestTodo.priority)) bestTodo = todo;
        }
        if (bestTodo != null) return (bestTodo.title + "：" + bestTodo.description).trim();
        List<Models.MemoryItem> memories = MindAiApp.get().db().memories("all", "", 500);
        Models.MemoryItem bestMemory = null;
        for (Models.MemoryItem memory : memories) {
            if (!session.id.equals(memory.sourceSummaryId)) continue;
            if (bestMemory == null || memory.importance > bestMemory.importance) bestMemory = memory;
        }
        if (bestMemory != null) return (bestMemory.title + "：" + bestMemory.text).trim();
        List<Models.ProfileFact> profile = MindAiApp.get().db().profileFactsForSession(session.id, 500);
        if (!profile.isEmpty()) {
            Models.ProfileFact fact = profile.get(0);
            return (fact.title + "：" + fact.content).trim();
        }
        List<Models.CalendarEvent> calendar = CalendarAutoCreator.committedCalendarEvents(MindAiApp.get(), session.id);
        if (!calendar.isEmpty()) {
            Models.CalendarEvent event = calendar.get(0);
            return (event.title + (event.location.isEmpty() ? "" : "：" + event.location)).trim();
        }
        Models.ImportantRecord best = null;
        List<Models.ImportantRecord> records = MindAiApp.get().db().records("all", "", 500);
        for (Models.ImportantRecord record : records) {
            if (!session.id.equals(record.sourceSummaryId)) continue;
            if (best == null || record.importance > best.importance) best = record;
        }
        if (best != null) return (best.title + "：" + best.content).trim();
        try {
            JSONArray points = new JSONArray(session.keyPointsJson);
            if (points.length() > 0) {
                JSONObject point = points.optJSONObject(0);
                if (point != null) return (point.optString("title") + "：" + point.optString("detail")).trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static int rank(String priority) {
        if ("urgent".equals(priority)) return 4;
        if ("high".equals(priority)) return 3;
        if ("medium".equals(priority)) return 2;
        return 1;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    @Override public void onDestroy() {
        cancelled = true;
        RUNNING.set(false);
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
