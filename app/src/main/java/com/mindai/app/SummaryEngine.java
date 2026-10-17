package com.mindai.app;

import java.util.List;
import java.util.ArrayList;

public final class SummaryEngine {
    public interface Listener {
        void onProgress(String step, int percent);
        void onSuccess(Models.SummarySession session);
        void onError(String message);
    }

    private final AppDatabase db;
    private final SecurePrefs secure;
    private final OpenRouterClient client;

    public SummaryEngine(AppDatabase db, SecurePrefs secure) {
        this(db, secure, new OpenRouterClient());
    }

    SummaryEngine(AppDatabase db, SecurePrefs secure, OpenRouterClient client) {
        this.db = db;
        this.secure = secure;
        this.client = client;
    }

    public void summarize(long start, long end, String packageFilter, Listener listener) {
        List<Models.NotificationItem> items = db.unsummarized(start, end, packageFilter);
        if (items.isEmpty()) {
            listener.onError("当前没有待总结的新通知");
            return;
        }
        if (!secure.configured()) {
            listener.onError("请先在设置中完成 OpenRouter 连接");
            return;
        }
        if (!ComplianceSettings.hasAiDataConsent(MindAiApp.get())) {
            listener.onError("请先在 OpenRouter 设置中确认 AI 数据使用说明");
            return;
        }
        if (!ProcessingCoordinator.tryAcquire()) {
            listener.onError("已有一轮整理正在进行，本次请求已合并");
            return;
        }
        Models.SummarySession session = null;
        try {
            listener.onProgress("固定本轮通知范围", 12);
            session = db.beginSession(items);
            listener.onProgress("过滤低价值推送", 22);
            NotificationValueGate.Screening screening = NotificationValueGate.screen(items);
            List<Models.NotificationItem> modelItems = screening.modelInput;
            listener.onProgress("正在分析通知", 35);
            OpenRouterClient.Config config = new OpenRouterClient.Config();
            config.apiKey = secure.apiKey(); config.baseUrl = secure.baseUrl();
            config.mode = secure.mode(); config.fixedModel = secure.fixedModel(); config.zdr = secure.zdr();
            List<Models.TodoItem> contextTodos = db.todos("open", "", 60);
            List<Models.MemoryItem> contextMemories = db.memories("active", "", 80);
            List<Models.ProfileFact> contextProfile = db.profileFacts("active", "", 80);
            long now = System.currentTimeMillis();
            List<Models.CalendarEvent> contextCalendar = new CalendarRepository(MindAiApp.get())
                    .events(now - 86_400_000L, now + 48L * 3_600_000L);
            Models.SummaryOutput output;
            if (modelItems.isEmpty()) {
                output = NotificationValueGate.emptyOutput(screening.ignoredCount);
            } else if (modelItems.size() > 60) {
                List<Models.SummaryOutput> chunks = new ArrayList<>();
                int total = (modelItems.size() + 59) / 60;
                for (int i = 0; i < modelItems.size(); i += 60) {
                    int chunk = i / 60 + 1;
                    listener.onProgress("分片分析 " + chunk + " / " + total, 25 + Math.round(38f * chunk / total));
                    Models.SummaryOutput chunkOutput = client.summarize(config,
                            new ArrayList<>(modelItems.subList(i, Math.min(i + 60, modelItems.size()))),
                            contextTodos, contextMemories, contextProfile, contextCalendar);
                    NotificationValueGate.sanitize(chunkOutput, screening);
                    chunks.add(chunkOutput);
                }
                listener.onProgress("合并分片摘要", 70);
                output = client.mergeSummaries(config, chunks);
            } else output = client.summarize(config, modelItems, contextTodos, contextMemories, contextProfile, contextCalendar);
            NotificationValueGate.sanitize(output, screening);
            listener.onProgress("自动写入待办、记忆与画像", 76);
            db.completeSession(session, output);
            listener.onProgress("核对并写入明确日程", 84);
            CalendarAutoCreator.createSafeCandidates(MindAiApp.get(), session, output.calendarCandidates);
            if (output.actualModel != null && !output.actualModel.trim().isEmpty()) {
                secure.updateActualModel(output.actualModel);
            }
            new AssistantBriefEngine(MindAiApp.get()).refresh();
            MindAiWidgetUpdater.updateAll(MindAiApp.get());
            listener.onProgress("已安全保存", 100);
            listener.onSuccess(session);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "总结失败，请稍后重试" : e.getMessage();
            if (session != null) db.failSession(session, message);
            MindAiWidgetUpdater.updateAll(MindAiApp.get());
            listener.onError(message);
        } finally {
            ProcessingCoordinator.release();
        }
    }
}
