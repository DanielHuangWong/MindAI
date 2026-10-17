package com.mindai.app;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Creates synthetic, non-personal demo content for Google Play screenshots.
 *
 * This test intentionally leaves its fixture data on the debug build. It is never packaged
 * into the production APK/AAB because it lives under androidTest.
 */
@RunWith(AndroidJUnit4.class)
public final class StoreListingFixtureInstrumentedTest {
    @Test public void seedCuratedStoreListingData() {
        Context context = MindAiApp.get();
        AppDatabase db = MindAiApp.get().db();
        long now = System.currentTimeMillis();
        long elapsedToday = Math.max(4_000L, now - AppDatabase.todayRange()[0]);

        db.clearEverything();
        MindAiApp.get().secure().clearOpenRouter();
        MindAiApp.get().assistantConversations().resetForTests();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit()
                .clear()
                .putBoolean("onboarding_seen", true)
                .putBoolean("auto_processing_enabled", false)
                .putString("capture_mode", "recommended")
                .commit();

        long project = db.saveNotification(
                "store-project-" + now,
                "demo.project",
                "项目沟通",
                "设计评审时间确认",
                "明天 10:00 进行设计评审，请提前准备最新原型和待确认问题。",
                now - elapsedToday * 3L / 4L,
                "normal");
        long travel = db.saveNotification(
                "store-travel-" + now,
                "demo.travel",
                "行程服务",
                "出发时间有调整",
                "明天下午的行程提前 30 分钟，请预留前往车站的时间。",
                now - elapsedToday / 2L,
                "normal");
        long delivery = db.saveNotification(
                "store-delivery-" + now,
                "demo.delivery",
                "配送提醒",
                "包裹已送达",
                "包裹已放入自提柜，请在 2 小时内取件。",
                now - elapsedToday / 4L,
                "normal");

        List<Models.NotificationItem> source =
                db.unsummarized(0, Long.MAX_VALUE, "");
        Models.SummarySession session = db.beginSession(source);
        Models.SummaryOutput output = new Models.SummaryOutput();
        output.overview = "明天的设计评审与行程调整最值得关注";
        output.actualModel = "demo/store-listing";

        Models.KeyPoint first = new Models.KeyPoint();
        first.title = "设计评审已确认";
        first.detail = "明天 10:00 开始，需要提前准备最新原型和待确认问题。";
        first.sourceIds = String.valueOf(project);
        output.keyPoints.add(first);

        Models.KeyPoint second = new Models.KeyPoint();
        second.title = "行程提前 30 分钟";
        second.detail = "建议重新确认出发时间，并预留前往车站的缓冲。";
        second.sourceIds = String.valueOf(travel);
        output.keyPoints.add(second);

        Models.TodoItem todo = new Models.TodoItem();
        todo.title = "准备设计评审材料";
        todo.description = "整理最新原型与待确认问题";
        todo.dueAt = now + 16L * 60L * 60_000L;
        todo.priority = "urgent";
        todo.confidence = 0.96;
        todo.sourceNotificationIds = String.valueOf(project);
        output.todos.add(todo);

        Models.MemoryItem memory = new Models.MemoryItem();
        memory.title = "偏好预留行程缓冲";
        memory.text = "涉及跨城行程时，倾向提前确认出发时间并预留缓冲。";
        memory.category = "preference";
        memory.importance = 0.82;
        memory.confidence = 0.88;
        memory.stability = "evolving";
        memory.sourceNotificationIds = String.valueOf(travel);
        output.memories.add(memory);

        Models.ProfileFact profile = new Models.ProfileFact();
        profile.section = "工作方式";
        profile.title = "重视会前准备";
        profile.content = "重要评审前会整理原型、问题清单和决策点。";
        profile.confidence = 0.86;
        profile.stability = "evolving";
        profile.sourceNotificationIds = String.valueOf(project);
        output.profileUpdates.add(profile);

        Models.NeedToKnow need = new Models.NeedToKnow();
        need.id = "store-need-" + now;
        need.title = "先确认明天的出发与评审安排";
        need.detail = "行程提前 30 分钟，且 10:00 有设计评审；两件事的准备时间可能重叠。";
        need.reason = "时间发生变化，并与高优先级工作事项接近。";
        need.urgency = "high";
        need.suggestedAction = "现在确认出发时间，再完成评审材料。";
        need.sourceNotificationIds = project + "," + travel;
        output.needToKnow = need;

        db.completeSession(session, output);
        db.addManualTodo(
                "半小时内取件",
                "包裹已放入自提柜",
                now + 30L * 60_000L,
                "medium");
        db.addManualTodo(
                "确认明天出发时间",
                "行程提前 30 分钟",
                now + 60L * 60_000L,
                "urgent");
        db.addConversationMessage(
                "user",
                "帮我看看明天最需要提前准备什么？",
                "",
                "",
                "");
        db.addConversationMessage(
                "assistant",
                "明天最需要先确认出发时间，并完成 10:00 设计评审的原型和问题清单。",
                "",
                "",
                "");
        new AssistantBriefEngine(context).refresh();

        assertTrue(db.countToday() >= 3);
        assertTrue(db.countOpenTodos() >= 3);
        assertTrue(db.countMemories() >= 1);
        assertTrue(db.countProfileFacts() >= 1);
    }
}
