package com.mindai.app;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.os.Build;
import android.os.Bundle;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

@RunWith(AndroidJUnit4.class)
public final class CoreFlowInstrumentedTest {
    private AppDatabase db;

    @Before public void setUp() {
        db = MindAiApp.get().db();
        db.clearEverything();
        MindAiApp.get().secure().clearOpenRouter();
        WidgetSummaryState.prefs(MindAiApp.get()).edit().clear().apply();
        MindAiApp.get().getSharedPreferences("mindai_processing_changes", Context.MODE_PRIVATE).edit().clear().apply();
        MindAiApp.get().assistantConversations().resetForTests();
        OpenRouterClient.setTestTransportForProcess(null);
        ComplianceSettings.grantAiDataConsent(MindAiApp.get());
        ComplianceSettings.grantNotificationCaptureConsent(MindAiApp.get());
    }

    @Test public void testNotificationLifecycleAndFailedSessionRollback() {
        long now = System.currentTimeMillis();
        long id = db.saveNotification("test-key", "com.test.app", "测试应用", "项目决定", "周四完成联调", now, "normal");
        assertTrue(id > 0);
        assertEquals(1, db.countUnsummarized());
        long[] day = AppDatabase.todayRange();
        List<Models.NotificationItem> items = db.unsummarized(day[0], day[1], "");
        assertEquals(1, items.size());
        Models.SummarySession session = db.beginSession(items);
        assertEquals("processing", db.notification(id).summaryState);
        db.failSession(session, "test failure");
        assertEquals("unsummarized", db.notification(id).summaryState);
    }

    @Test public void testManualRecordCrud() {
        String id = db.addManualRecord("项目结论", "周四完成联调", "工作项目");
        Models.ImportantRecord record = db.record(id);
        assertNotNull(record);
        assertEquals("confirmed", record.status);
        record.content = "周五前完成全部测试";
        db.updateRecord(record);
        assertEquals("周五前完成全部测试", db.record(id).content);
        db.deleteRecord(id);
        assertEquals(1, db.trashedRecords().size());
        db.restoreRecord(id);
        assertEquals(0, db.trashedRecords().size());
    }

    @Test public void testOpenRouterRejectsInvalidKeyBeforeNetwork() {
        OpenRouterClient.Config config = new OpenRouterClient.Config();
        config.apiKey = "invalid";
        try {
            new OpenRouterClient().validate(config);
            fail("Expected validation error");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("API Key"));
        }
    }

    @Test public void testOpenRouterNullContentShowsFriendlyReason() throws Exception {
        JSONObject response = new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                .put("finish_reason", "error")
                .put("message", new JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
                .put("error", new JSONObject()
                        .put("message", "No endpoints found matching your data policy")
                        .put("metadata", new JSONObject().put("error_type", "provider_unavailable")))));
        try {
            OpenRouterClient.messageContent(response);
            fail("Expected friendly validation error");
        } catch (OpenRouterClient.ApiException e) {
            assertTrue(e.getMessage().contains("Zero Data Retention") || e.getMessage().contains("ZDR"));
        }
    }

    @Test public void testOpenRouterLiveValidationWhenKeyProvided() throws Exception {
        String liveKey = InstrumentationRegistry.getArguments().getString("openrouter_key", "");
        Assume.assumeTrue("Live OpenRouter key not provided", liveKey.startsWith("sk-or-"));
        OpenRouterClient.Config config = new OpenRouterClient.Config();
        config.apiKey = liveKey;
        config.mode = "auto";
        config.zdr = false;
        OpenRouterClient.ValidationResult result = new OpenRouterClient().validate(config);
        assertTrue(result.ok);
        assertFalse(result.actualModel.isEmpty());
        MindAiApp.get().secure().saveOpenRouter(config.apiKey, config.baseUrl, config.mode, config.fixedModel,
                config.zdr, result.actualModel, System.currentTimeMillis(), result.keyLabel, result.expiresAt);
        assertTrue(MindAiApp.get().secure().configured());
        assertFalse(MindAiApp.get().secure().fixedModel().isEmpty());
        assertFalse(MindAiApp.get().secure().actualModel().isEmpty());
    }

    @Test public void testOpenRouterLiveSummaryWhenKeyProvided() throws Exception {
        String liveKey = InstrumentationRegistry.getArguments().getString("openrouter_key", "");
        Assume.assumeTrue("Live OpenRouter key not provided", liveKey.startsWith("sk-or-"));
        OpenRouterClient client = new OpenRouterClient();
        OpenRouterClient.Config config = new OpenRouterClient.Config();
        config.apiKey = liveKey;
        config.mode = "auto";
        config.zdr = false;
        OpenRouterClient.ValidationResult result = client.validate(config);
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter(config.apiKey, config.baseUrl, config.mode, config.fixedModel,
                config.zdr, result.actualModel, System.currentTimeMillis(), result.keyLabel, result.expiresAt);

        long now = System.currentTimeMillis();
        db.saveNotification("live-summary-1-" + now, "com.chat", "聊天", "MindAI 测试版决定",
                "Daniel 和 Mira 确认：下周三 18:00 前完成 OpenRouter 总结回归测试。", now - 180_000, "normal");
        db.saveNotification("live-summary-2-" + now, "com.calendar", "日历", "猫疫苗提醒",
                "周六上午 10:00 去宠物医院给猫接种疫苗。", now - 120_000, "normal");
        db.saveNotification("live-summary-3-" + now, "com.sms", "短信", "验证码",
                "你的登录验证码是 123456，5 分钟内有效。", now - 60_000, "sensitive");

        long[] day = AppDatabase.todayRange();
        final Models.SummarySession[] captured = new Models.SummarySession[1];
        final String[] error = {""};
        new SummaryEngine(db, secure).summarize(day[0], day[1], "", new SummaryEngine.Listener() {
            @Override public void onProgress(String step, int percent) {}
            @Override public void onSuccess(Models.SummarySession session) { captured[0] = session; }
            @Override public void onError(String message) { error[0] = message; }
        });

        assertTrue(error[0], error[0].isEmpty());
        assertNotNull(captured[0]);
        Models.SummarySession stored = db.session(captured[0].id);
        assertNotNull(stored);
        assertEquals("success", stored.status);
        assertFalse(stored.overview.trim().isEmpty());
        assertFalse(stored.modelInfo.trim().isEmpty());
        assertEquals(0, db.countUnsummarized());
    }

    @Test public void testLocalSimulatedNotificationsProduceSummaryAfterBadJsonModelFallback() throws Exception {
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-local-summary-test", "https://local.openrouter.test/api/v1", "auto", "",
                false, "", System.currentTimeMillis(), "local", "");

        long now = System.currentTimeMillis();
        long firstId = db.saveNotification("local-summary-1-" + now, "com.chat", "聊天", "OpenRouter 总结修复",
                "Daniel 决定今晚打包新版 APK，并验证免费模型返回非 JSON 时可以自动回退。", now - 180_000, "normal");
        long secondId = db.saveNotification("local-summary-2-" + now, "com.calendar", "日历", "回归测试",
                "明天 10:00 检查通知总结结果，确认关键点和重要记录已经保存。", now - 120_000, "normal");
        long thirdId = db.saveNotification("local-summary-3-" + now, "com.sms", "短信", "验证码",
                "你的验证码是 654321，5 分钟内有效。", now - 60_000, "sensitive");

        final int[] badModelCalls = {0};
        final int[] goodModelCalls = {0};
        OpenRouterClient fakeClient = new OpenRouterClient((config, method, path, body) -> {
            if ("GET".equals(method) && "/models".equals(path)) {
                return new JSONObject().put("data", new JSONArray()
                        .put(new JSONObject()
                                .put("id", "local/bad-json:free")
                                .put("supported_parameters", new JSONArray().put("response_format")))
                        .put(new JSONObject()
                                .put("id", "local/good-summary:free")
                                .put("supported_parameters", new JSONArray().put("response_format"))));
            }
            if ("POST".equals(method) && "/chat/completions".equals(path)) {
                String model = body.optString("model");
                if ("local/bad-json:free".equals(model)) {
                    badModelCalls[0]++;
                    return chatResponse(model, "这不是 JSON，模拟免费模型总结阶段失控输出。");
                }
                if ("local/good-summary:free".equals(model)) {
                    goodModelCalls[0]++;
                    String summaryJson = new JSONObject()
                            .put("overview", "本轮 3 条模拟通知已整理：重点是 OpenRouter 总结回退修复和明天的回归测试，验证码未记录为长期信息。")
                            .put("key_points", new JSONArray()
                                    .put(new JSONObject()
                                            .put("title", "OpenRouter 总结回退")
                                            .put("detail", "新版 APK 需要验证免费模型返回非 JSON 时可自动切换并继续总结。")
                                            .put("source_ids", new JSONArray().put(firstId)))
                                    .put(new JSONObject()
                                            .put("title", "回归测试安排")
                                            .put("detail", "明天 10:00 检查通知总结结果和重要记录保存情况。")
                                            .put("source_ids", new JSONArray().put(secondId))))
                            .put("important_records", new JSONArray()
                                    .put(new JSONObject()
                                            .put("title", "通知总结回归测试")
                                            .put("content", "明天 10:00 检查通知总结结果，确认关键点和重要记录已经保存。")
                                            .put("category", "承诺与截止时间")
                                            .put("importance", 0.92)
                                            .put("event_at", 0)
                                            .put("source_ids", new JSONArray().put(secondId))))
                            .toString();
                    return chatResponse(model, "模型输出前缀\n" + summaryJson + "\n模型输出后缀");
                }
            }
            throw new OpenRouterClient.ApiException(0, "Unexpected fake OpenRouter request: " + method + " " + path);
        });

        long[] day = AppDatabase.todayRange();
        final Models.SummarySession[] captured = new Models.SummarySession[1];
        final String[] error = {""};
        new SummaryEngine(db, secure, fakeClient).summarize(day[0], day[1], "", new SummaryEngine.Listener() {
            @Override public void onProgress(String step, int percent) {}
            @Override public void onSuccess(Models.SummarySession session) { captured[0] = session; }
            @Override public void onError(String message) { error[0] = message; }
        });

        assertTrue(error[0], error[0].isEmpty());
        assertNotNull(captured[0]);
        Models.SummarySession stored = db.session(captured[0].id);
        assertNotNull(stored);
        assertEquals("success", stored.status);
        assertTrue(stored.overview, stored.overview.contains("OpenRouter 总结回退修复"));
        assertTrue(stored.keyPointsJson, stored.keyPointsJson.contains("OpenRouter 总结回退"));
        assertEquals("local/good-summary:free", stored.modelInfo);
        assertEquals("local/good-summary:free", secure.fixedModel());
        assertTrue("bad model should be tried before fallback", badModelCalls[0] >= 1);
        assertEquals(1, goodModelCalls[0]);
        assertEquals(0, db.countUnsummarized());
        List<Models.ImportantRecord> records = db.records("all", "");
        assertEquals(1, records.size());
        assertTrue(records.get(0).content, records.get(0).content.contains("明天 10:00"));
        assertEquals(thirdId, db.notification(thirdId).id);

        Bundle status = new Bundle();
        status.putString("summary_overview", stored.overview);
        status.putString("summary_key_points", stored.keyPointsJson);
        status.putString("summary_record", records.get(0).content);
        status.putString("summary_model", stored.modelInfo);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    @Test public void testWidgetProvidersAreRegistered() {
        Context context = MindAiApp.get();
        Intent update = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).setPackage(context.getPackageName());
        List<ResolveInfo> receivers = context.getPackageManager().queryBroadcastReceivers(update, PackageManager.GET_META_DATA);
        boolean small = false;
        boolean large = false;
        for (ResolveInfo receiver : receivers) {
            String name = receiver.activityInfo == null ? "" : receiver.activityInfo.name;
            if (MindAiSmallWidgetProvider.class.getName().equals(name)) small = true;
            if (MindAiLargeWidgetProvider.class.getName().equals(name)) large = true;
        }
        assertTrue("2x2 widget provider should be registered", small);
        assertTrue("4x4 widget provider should be registered", large);
    }

    @Test public void testWidgetSummaryServiceProducesSummaryStateAndCompletionNotification() throws Exception {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        runShell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancelAll();

        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-widget-summary-test", "https://local.openrouter.test/api/v1", "auto", "",
                false, "", System.currentTimeMillis(), "local", "");

        long now = System.currentTimeMillis();
        long firstId = db.saveNotification("widget-summary-1-" + now, "com.chat", "聊天", "桌面组件总结",
                "Daniel 要求 2x2 和 4x4 桌面小组件都能一键开始后台总结。", now - 180_000, "normal");
        long secondId = db.saveNotification("widget-summary-2-" + now, "com.project", "项目", "重要交付",
                "今晚 22:30 前打包新的 APK，并确认完成通知能进入对应总结结果页。", now - 120_000, "normal");
        db.saveNotification("widget-summary-3-" + now, "com.sms", "短信", "验证码",
                "你的验证码是 111222，5 分钟内有效。", now - 60_000, "sensitive");

        OpenRouterClient.setTestTransportForProcess((config, method, path, body) -> {
            if ("GET".equals(method) && "/models".equals(path)) {
                return new JSONObject().put("data", new JSONArray()
                        .put(new JSONObject()
                                .put("id", "local/widget-summary:free")
                                .put("supported_parameters", new JSONArray().put("response_format"))));
            }
            if ("POST".equals(method) && "/chat/completions".equals(path)) {
                String summaryJson = new JSONObject()
                        .put("overview", "本轮 3 条通知已整理：重点是桌面小组件后台总结和新版 APK 交付。")
                        .put("key_points", new JSONArray()
                                .put(new JSONObject()
                                        .put("title", "桌面小组件")
                                        .put("detail", "2x2 和 4x4 小组件都需要一键开始后台总结。")
                                        .put("source_ids", new JSONArray().put(firstId)))
                                .put(new JSONObject()
                                        .put("title", "新版 APK 交付")
                                        .put("detail", "今晚 22:30 前打包新的 APK，并确认通知点击进入总结结果页。")
                                        .put("source_ids", new JSONArray().put(secondId))))
                        .put("important_records", new JSONArray()
                                .put(new JSONObject()
                                        .put("title", "新版 APK 交付")
                                        .put("content", "今晚 22:30 前打包新的 APK，并确认完成通知能进入对应总结结果页。")
                                        .put("category", "承诺与截止时间")
                                        .put("importance", 0.96)
                                        .put("event_at", 0)
                                        .put("source_ids", new JSONArray().put(secondId))))
                        .toString();
                return chatResponse(body.optString("model"), summaryJson);
            }
            throw new OpenRouterClient.ApiException(0, "Unexpected fake OpenRouter request: " + method + " " + path);
        });

        Intent activity = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        InstrumentationRegistry.getInstrumentation().startActivitySync(activity);
        Intent service = new Intent(context, WidgetSummaryService.class).setAction(WidgetSummaryService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);

        SharedPreferences prefs = WidgetSummaryState.prefs(context);
        for (int i = 0; i < 80; i++) {
            if (WidgetSummaryState.STATUS_DONE.equals(prefs.getString("status", "")) && db.countUnsummarized() == 0) break;
            SystemClock.sleep(250);
        }

        assertEquals(WidgetSummaryState.STATUS_DONE, prefs.getString("status", ""));
        assertEquals(0, db.countUnsummarized());
        List<Models.SummarySession> sessions = db.sessions();
        assertFalse(sessions.isEmpty());
        Models.SummarySession session = sessions.get(0);
        assertEquals("success", session.status);
        assertTrue(session.overview, session.overview.contains("桌面小组件后台总结"));
        assertEquals(session.id, prefs.getString("last_session_id", ""));
        assertTrue(prefs.getString("last_important", ""), prefs.getString("last_important", "").contains("今晚 22:30"));

        boolean completionNotificationFound = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (StatusBarNotification notification : notificationManager.getActiveNotifications()) {
                if (notification.getId() == SummaryNotificationHelper.COMPLETED_ID) completionNotificationFound = true;
            }
        }
        assertTrue("completion notification should be posted", completionNotificationFound);

        Bundle status = new Bundle();
        status.putString("widget_status", prefs.getString("status", ""));
        status.putString("widget_important", prefs.getString("last_important", ""));
        status.putString("widget_summary_id", session.id);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    @Test public void testNotificationListenerCallbackPersistsNotification() {
        Assume.assumeTrue("Shell notification posting is only reliable on newer emulator images", Build.VERSION.SDK_INT >= 35);
        Context source = MindAiApp.get();
        source.getSharedPreferences(ComplianceSettings.PREFS, Context.MODE_PRIVATE).edit()
                .putString("capture_mode", "all")
                .apply();
        ComponentName componentName = new ComponentName(source, NotificationCaptureService.class);
        runShell("pm grant " + source.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        runShell("settings put secure enabled_notification_listeners " + componentName.flattenToString());
        runShell("cmd notification allow_listener " + componentName.flattenToString());
        android.service.notification.NotificationListenerService.requestRebind(
                componentName);
        SystemClock.sleep(1000);
        runShell("cmd notification post -t 外部通知测试 mindai_listener_test 通知监听服务应加密保存这条内容");
        for (int i = 0; i < 20 && db.countToday() == 0; i++) SystemClock.sleep(150);
        assertEquals(1, db.countToday());
        assertEquals("外部通知测试", db.notifications(AppDatabase.todayRange()[0], AppDatabase.todayRange()[1], "", "all", "").get(0).title);
    }

    @Test public void testCompletedSummaryAutomaticallyCreatesTodoMemoryProfileAndBrief() {
        long now = System.currentTimeMillis();
        long notificationId = db.saveNotification("auto-classify-" + now, "com.work", "工作", "交付安排",
                "周五前提交方案，并记住 Daniel 更喜欢先看结论。", now, "normal");
        List<Models.NotificationItem> source = db.unsummarized(0, Long.MAX_VALUE, "");
        Models.SummarySession session = db.beginSession(source);
        Models.SummaryOutput output = new Models.SummaryOutput(); output.overview = "已提取交付待办和用户偏好"; output.actualModel = "local/test:free";
        Models.TodoItem todo = new Models.TodoItem(); todo.title = "提交方案"; todo.description = "周五前提交方案"; todo.dueAt = now + 86_400_000L; todo.priority = "high"; todo.confidence = .95; todo.sourceNotificationIds = String.valueOf(notificationId); output.todos.add(todo);
        Models.MemoryItem memory = new Models.MemoryItem(); memory.title = "信息呈现偏好"; memory.text = "Daniel 更喜欢先看结论"; memory.category = "preference"; memory.importance = .9; memory.confidence = .95; memory.sourceNotificationIds = String.valueOf(notificationId); output.memories.add(memory);
        Models.ProfileFact fact = new Models.ProfileFact(); fact.section = "沟通偏好"; fact.title = "先看结论"; fact.content = "更喜欢先看结论，再看细节"; fact.confidence = .9; fact.sourceNotificationIds = String.valueOf(notificationId); output.profileUpdates.add(fact);
        Models.NeedToKnow need = new Models.NeedToKnow(); need.title = "周五前提交方案"; need.detail = "这是当前最重要的交付"; need.reason = "临近截止"; need.urgency = "high"; need.suggestedAction = "确认进度"; need.sourceNotificationIds = String.valueOf(notificationId); output.needToKnow = need;
        db.completeSession(session, output);

        assertEquals("summarized", db.notification(notificationId).summaryState);
        assertEquals(1, db.countOpenTodos()); assertEquals(1, db.countMemories()); assertEquals(1, db.countProfileFacts());
        List<Models.AssistantBriefItem> briefs = db.activeBriefItems(10); assertFalse(briefs.isEmpty()); assertEquals("周五前提交方案", briefs.get(0).title);
        WidgetSummaryState.markDone(MindAiApp.get(), session, "周五前提交方案");
        String changes = WidgetSummaryState.prefs(MindAiApp.get()).getString("last_changes", "");
        assertTrue(changes, changes.contains("新待办 1"));
        assertTrue(changes, changes.contains("新记忆 1"));
        assertTrue(changes, changes.contains("画像更新 1"));
    }

    @Test public void testSocialPromotionAndPseudoDmScreeningMatrix() {
        Models.NotificationItem weiboAd = notification("com.sina.weibo", "微博", "你收到一条私信",
                "附近有人想认识你，快来聊聊并领取新人福利");
        NotificationValueGate.Decision weiboDecision = NotificationValueGate.evaluate(weiboAd);
        assertFalse(weiboDecision.includeInModel);
        assertFalse(weiboDecision.allowActionWrite);
        assertFalse(weiboDecision.allowIdentityWrite);

        Models.NotificationItem xhsRecommendation = notification("com.xingin.xhs", "小红书", "有人提到了你",
                "这篇笔记你可能感兴趣，点击查看今日精选");
        NotificationValueGate.Decision xhsDecision = NotificationValueGate.evaluate(xhsRecommendation);
        assertFalse(xhsDecision.includeInModel);
        assertEquals("promotion", xhsDecision.classification);

        Models.NotificationItem realSocialAction = notification("com.sina.weibo", "微博", "项目负责人张明",
                "请在周五 18:00 前确认产品评审时间");
        NotificationValueGate.Decision realDecision = NotificationValueGate.evaluate(realSocialAction);
        assertTrue(realDecision.includeInModel);
        assertTrue(realDecision.allowActionWrite);
        assertFalse("One social message must not update long-term identity", realDecision.allowIdentityWrite);

        Models.NotificationItem work = notification("com.work.chat", "工作群", "项目负责人张明",
                "请在周五 18:00 前确认产品评审时间");
        NotificationValueGate.Decision workDecision = NotificationValueGate.evaluate(work);
        assertTrue(workDecision.includeInModel);
        assertTrue(workDecision.allowActionWrite);
        assertTrue(workDecision.allowIdentityWrite);
    }

    @Test public void testAllSocialEngagementNoiseCompletesWithoutCallingModel() {
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-local-value-gate", "https://local.openrouter.test/api/v1", "fixed",
                "local/summary:free", false, "local/summary:free", System.currentTimeMillis(), "local", "");
        long now = System.currentTimeMillis();
        long first = db.saveNotification("social-noise-1-" + now, "com.sina.weibo", "微博", "你收到一条私信",
                "有人想认识你，快来聊聊", now - 1_000L, "normal");
        long second = db.saveNotification("social-noise-2-" + now, "com.xingin.xhs", "小红书", "有人提到了你",
                "今日精选笔记，点击查看并领取专属福利", now, "normal");
        final int[] networkCalls = {0};
        OpenRouterClient fakeClient = new OpenRouterClient((config, method, path, body) -> {
            networkCalls[0]++;
            throw new OpenRouterClient.ApiException(0, "Filtered notifications must not reach OpenRouter");
        });
        final Models.SummarySession[] success = new Models.SummarySession[1];
        final String[] error = {""};
        new SummaryEngine(db, secure, fakeClient).summarize(0, Long.MAX_VALUE, "", new SummaryEngine.Listener() {
            @Override public void onProgress(String step, int percent) {}
            @Override public void onSuccess(Models.SummarySession session) { success[0] = session; }
            @Override public void onError(String message) { error[0] = message; }
        });

        assertTrue(error[0], error[0].isEmpty());
        assertNotNull(success[0]);
        assertEquals(0, networkCalls[0]);
        assertEquals("summarized", db.notification(first).summaryState);
        assertEquals("summarized", db.notification(second).summaryState);
        assertEquals(0, db.countOpenTodos());
        assertEquals(0, db.countMemories());
        assertEquals(0, db.countProfileFacts());
        assertTrue(db.session(success[0].id).overview.contains("低价值推送已自动忽略"));
    }

    @Test public void testStructuredWriteGateRejectsModelHallucinationsFromFilteredSocialAd() {
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-structured-gate", "https://local.openrouter.test/api/v1", "fixed",
                "local/summary:free", false, "local/summary:free", System.currentTimeMillis(), "local", "");
        long now = System.currentTimeMillis();
        long adId = db.saveNotification("social-ad-write-" + now, "com.xingin.xhs", "小红书", "你收到一条私信",
                "今晚 20:00 直播间等你，回复领取专属福利", now - 1_000L, "normal");
        long workId = db.saveNotification("work-action-" + now, "com.work.chat", "工作群", "项目负责人",
                "请在明天 18:00 前确认评审结论", now, "normal");
        final String[] requestPayload = {""};
        OpenRouterClient fakeClient = new OpenRouterClient((config, method, path, body) -> {
            requestPayload[0] = body.getJSONArray("messages").getJSONObject(1).getString("content");
            JSONObject result = new JSONObject()
                    .put("overview", "已识别项目确认事项")
                    .put("key_points", new JSONArray()
                            .put(new JSONObject().put("title", "确认评审").put("detail", "明天前确认")
                                    .put("source_ids", new JSONArray().put(workId)))
                            .put(new JSONObject().put("title", "直播福利").put("detail", "今晚参加直播")
                                    .put("source_ids", new JSONArray().put(adId))))
                    .put("todos", new JSONArray()
                            .put(new JSONObject().put("title", "确认评审结论").put("description", "明天前完成")
                                    .put("due_at", now + 86_400_000L).put("priority", "high").put("confidence", .96)
                                    .put("source_ids", new JSONArray().put(workId)))
                            .put(new JSONObject().put("title", "参加直播领福利").put("description", "营销误判")
                                    .put("due_at", now + 3_600_000L).put("priority", "high").put("confidence", .99)
                                    .put("source_ids", new JSONArray().put(adId))))
                    .put("calendar_candidates", new JSONArray()
                            .put(new JSONObject().put("title", "福利直播").put("description", "营销误判").put("location", "")
                                    .put("start_at", now + 3_600_000L).put("end_at", now + 7_200_000L)
                                    .put("all_day", false).put("user_involved", true).put("confidence", .99)
                                    .put("source_ids", new JSONArray().put(adId))))
                    .put("memory_operations", new JSONArray()
                            .put(new JSONObject().put("op", "create").put("target_id", "").put("title", "直播偏好")
                                    .put("text", "用户喜欢看直播").put("category", "preference").put("importance", .9)
                                    .put("confidence", .99).put("stability", "stable").put("sensitivity", "normal")
                                    .put("source_ids", new JSONArray().put(adId))))
                    .put("profile_updates", new JSONArray()
                            .put(new JSONObject().put("op", "add").put("target_id", "").put("section", "兴趣偏好")
                                    .put("title", "直播").put("content", "喜欢直播购物").put("confidence", .99)
                                    .put("stability", "stable").put("source_ids", new JSONArray().put(adId))))
                    .put("need_to_know", new JSONObject().put("title", "参加直播").put("detail", "今晚开始")
                            .put("reason", "即将开始").put("urgency", "high").put("suggested_action", "打开小红书")
                            .put("source_ids", new JSONArray().put(adId)));
            return chatResponse(body.optString("model"), result.toString());
        });
        final String[] error = {""};
        new SummaryEngine(db, secure, fakeClient).summarize(0, Long.MAX_VALUE, "", new SummaryEngine.Listener() {
            @Override public void onProgress(String step, int percent) {}
            @Override public void onSuccess(Models.SummarySession session) {}
            @Override public void onError(String message) { error[0] = message; }
        });

        assertTrue(error[0], error[0].isEmpty());
        assertFalse("Filtered ad must not be sent to the model", requestPayload[0].contains("直播间等你"));
        assertTrue(requestPayload[0].contains("确认评审结论"));
        assertEquals(1, db.countOpenTodos());
        assertEquals("确认评审结论", db.todos("open", "", 10).get(0).title);
        assertEquals(0, db.countMemories());
        assertEquals(0, db.countProfileFacts());
        for (Models.AssistantBriefItem item : db.activeBriefItems(10)) {
            assertFalse(item.title, item.title.contains("直播") || item.title.contains("福利"));
        }
    }

    @Test public void testWidgetKeepsLastSuccessAndAccumulatesTodayStructuredContent() throws Exception {
        Context context = MindAiApp.get();
        long now = System.currentTimeMillis();
        long firstNotification = db.saveNotification("widget-daily-first-" + now, "com.work", "工作", "合同交付",
                "周五前提交合同，并记住这是本周最高优先级。", now - 120_000L, "normal");
        Models.SummarySession first = db.beginSession(db.unsummarized(0, Long.MAX_VALUE, ""));
        Models.SummaryOutput firstOutput = new Models.SummaryOutput();
        firstOutput.overview = "已识别合同交付要求";
        Models.TodoItem todo = new Models.TodoItem();
        todo.title = "周五前提交合同"; todo.description = "完成合同检查并提交";
        todo.dueAt = now + 86_400_000L; todo.priority = "high"; todo.confidence = .96;
        todo.sourceNotificationIds = String.valueOf(firstNotification); firstOutput.todos.add(todo);
        Models.MemoryItem memory = new Models.MemoryItem();
        memory.title = "本周工作重点"; memory.text = "合同交付是本周最高优先级";
        memory.category = "project"; memory.importance = .92; memory.confidence = .95;
        memory.sourceNotificationIds = String.valueOf(firstNotification); firstOutput.memories.add(memory);
        db.completeSession(first, firstOutput);
        WidgetSummaryState.markDone(context, first, "周五前需要完成合同检查并提交");

        WidgetSummaryState.Content firstContent = WidgetSummaryState.content(context);
        assertTrue(firstContent.oneSentenceSummary, firstContent.oneSentenceSummary.contains("周五前"));
        assertEquals(2, firstContent.structuredCount);
        assertTrue(hasWidgetKind(firstContent, WidgetSummaryState.KIND_TODO));
        assertTrue(hasWidgetKind(firstContent, WidgetSummaryState.KIND_MEMORY));

        long secondNotification = db.saveNotification("widget-daily-second-" + now, "com.project", "项目", "当前重点",
                "Daniel 当前正在推进 MindAI 安卓个人助理。", now - 60_000L, "normal");
        Models.SummarySession second = db.beginSession(db.unsummarized(0, Long.MAX_VALUE, ""));
        Models.SummaryOutput secondOutput = new Models.SummaryOutput();
        secondOutput.overview = "已更新当前项目画像";
        Models.ProfileFact fact = new Models.ProfileFact();
        fact.section = "工作与项目"; fact.title = "当前产品重点";
        fact.content = "正在推进 MindAI 安卓个人助理"; fact.confidence = .96;
        fact.sourceNotificationIds = String.valueOf(secondNotification); secondOutput.profileUpdates.add(fact);
        db.completeSession(second, secondOutput);
        context.getSharedPreferences("mindai_processing_changes", Context.MODE_PRIVATE).edit()
                .putString("calendar_items:" + second.id, new JSONArray().put(new JSONObject()
                        .put("id", 987654321L).put("title", "MindAI 项目会").put("description", "讨论组件")
                        .put("location", "3F 会议室").put("start_at", now + 3_600_000L)
                        .put("end_at", now + 7_200_000L).put("all_day", false)).toString())
                .apply();
        WidgetSummaryState.markDone(context, second, "当前重点是推进 MindAI 安卓个人助理");

        WidgetSummaryState.Content secondContent = WidgetSummaryState.content(context);
        assertTrue(secondContent.oneSentenceSummary, secondContent.oneSentenceSummary.contains("MindAI"));
        assertEquals(4, secondContent.structuredCount);
        assertTrue("First-cycle todo must remain", hasWidgetKind(secondContent, WidgetSummaryState.KIND_TODO));
        assertTrue("First-cycle memory must remain", hasWidgetKind(secondContent, WidgetSummaryState.KIND_MEMORY));
        assertTrue(hasWidgetKind(secondContent, WidgetSummaryState.KIND_PROFILE));
        assertTrue(hasWidgetKind(secondContent, WidgetSummaryState.KIND_CALENDAR));

        WidgetSummaryState.markRunning(context, 8, "正在整理下一轮");
        WidgetSummaryState.Content whileRunning = WidgetSummaryState.content(context);
        assertEquals(secondContent.oneSentenceSummary, whileRunning.oneSentenceSummary);
        assertEquals(secondContent.structuredCount, whileRunning.structuredCount);
        WidgetSummaryState.markError(context, "模拟失败");
        WidgetSummaryState.Content afterFailure = WidgetSummaryState.content(context);
        assertEquals(secondContent.oneSentenceSummary, afterFailure.oneSentenceSummary);
        assertEquals(secondContent.structuredCount, afterFailure.structuredCount);

        Models.TodoItem storedTodo = db.todosForSession(first.id, 10).get(0);
        db.setTodoStatus(storedTodo.id, "done");
        WidgetSummaryState.Item completed = widgetItem(WidgetSummaryState.content(context), WidgetSummaryState.KIND_TODO);
        assertNotNull(completed); assertEquals("done", completed.state);
        Models.MemoryItem storedMemory = db.memoriesForSession(first.id, 10).get(0);
        db.deleteMemory(storedMemory.id);
        assertEquals(3, WidgetSummaryState.content(context).structuredCount);

        WidgetContentRemoteViewsService.Factory factory = new WidgetContentRemoteViewsService.Factory(context, true);
        factory.onDataSetChanged();
        assertEquals(WidgetSummaryState.content(context).items.size(), factory.getCount());
        assertNotNull(factory.getViewAt(0));
        factory.onDestroy();
        assertNotNull(MindAiWidgetUpdater.views(context, false, 77));
        assertNotNull(MindAiWidgetUpdater.views(context, true, 78));
    }

    @Test public void testWidgetDropsPreviousDayCardsButKeepsLastSummary() {
        Context context = MindAiApp.get();
        long now = System.currentTimeMillis();
        long notification = db.saveNotification("widget-old-day-" + now, "com.work", "工作", "旧日待办",
                "旧日产生的待办", now, "normal");
        Models.SummarySession session = db.beginSession(db.unsummarized(0, Long.MAX_VALUE, ""));
        Models.SummaryOutput output = new Models.SummaryOutput(); output.overview = "旧日仍需记住的总结";
        Models.TodoItem todo = new Models.TodoItem(); todo.title = "旧日待办"; todo.confidence = .95;
        todo.sourceNotificationIds = String.valueOf(notification); output.todos.add(todo);
        db.completeSession(session, output);
        WidgetSummaryState.markDone(context, session, "旧日仍需记住的总结");
        db.getWritableDatabase().execSQL("UPDATE summary_sessions SET completed_at=? WHERE id=?",
                new Object[]{now - 2L * 86_400_000L, session.id});

        WidgetSummaryState.Content content = WidgetSummaryState.content(context, now);
        assertTrue(content.oneSentenceSummary, content.oneSentenceSummary.contains("旧日"));
        assertEquals(0, content.structuredCount);
        assertEquals(1, content.items.size());
        assertEquals(WidgetSummaryState.KIND_SUMMARY, content.items.get(0).kind);
    }

    @Test public void testBriefLifecycleAndSchedulerRegistration() {
        long now = System.currentTimeMillis();
        String todoId = db.addManualTodo("一小时内完成回归", "验证助理首页优先级", now + 3_600_000L, "urgent");
        List<Models.AssistantBriefItem> items = new AssistantBriefEngine(MindAiApp.get()).refresh();
        Models.AssistantBriefItem found = null;
        for (Models.AssistantBriefItem item : items) if (("todo:" + todoId).equals(item.id)) found = item;
        assertNotNull(found); assertTrue(found.rankScore >= 90); assertEquals("high", found.urgency);
        db.setBriefStatus(found.id, "snoozed", now + 3_600_000L);
        for (Models.AssistantBriefItem item : db.activeBriefItems(20)) assertNotEquals(found.id, item.id);

        Context context = MindAiApp.get();
        ComplianceSettings.grantAutoProcessingConsent(context);
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("auto_processing_enabled", true)
                .putInt("auto_interval_minutes", 15)
                .apply();
        AutoProcessingScheduler.schedule(context);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class); JobInfo job = scheduler.getPendingJob(AutoProcessingScheduler.JOB_ID);
        assertNotNull(job); assertTrue(job.isPersisted()); assertEquals(15 * 60_000L, job.getIntervalMillis());
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putInt("auto_interval_minutes", 30).apply();
        AutoProcessingScheduler.schedule(context); job = scheduler.getPendingJob(AutoProcessingScheduler.JOB_ID);
        assertNotNull(job); assertEquals(30 * 60_000L, job.getIntervalMillis());
    }

    @Test public void testAssistantBriefRanksMultipleUpcomingTodos() {
        long now = System.currentTimeMillis();
        db.addManualTodo("今天 16:00 前确认项目交付时间", "项目进度已接近关键节点，请尽快确认交付时间。", now + 90 * 60_000L, "urgent");
        db.addManualTodo("明天 10:00 产品会，还有 2 项准备未完成", "准备产品会材料", now + 20 * 3_600_000L, "high");
        db.addManualTodo("本周完成自动整理回归", "检查桌面组件与画像更新", now + 42 * 3_600_000L, "medium");
        List<Models.AssistantBriefItem> items = new AssistantBriefEngine(MindAiApp.get()).refresh();
        assertEquals(3, items.size());
        assertEquals("今天 16:00 前确认项目交付时间", items.get(0).title);
        assertTrue(items.get(0).rankScore > items.get(1).rankScore);
    }

    @Test public void testAssistantNaturalLanguagePlanCreatesTodo() throws Exception {
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-assistant-local", "https://local.openrouter.test/api/v1", "fixed", "local/assistant:free", false, "local/assistant:free", System.currentTimeMillis(), "local", "");
        OpenRouterClient fake = new OpenRouterClient((config, method, path, body) -> {
            if ("POST".equals(method) && "/chat/completions".equals(path)) {
                JSONObject plan = new JSONObject().put("type", "action").put("message", "").put("action", "create_todo")
                        .put("target_id", "").put("title", "明天提交周报").put("description", "准备并提交周报").put("query", "")
                        .put("priority", "high").put("category", "other").put("due_at", System.currentTimeMillis() + 86_400_000L)
                        .put("start_at", JSONObject.NULL).put("end_at", JSONObject.NULL).put("all_day", false)
                        .put("requires_confirmation", false).put("confidence", .96);
                return chatResponse("local/assistant:free", plan.toString());
            }
            throw new OpenRouterClient.ApiException(0, "Unexpected assistant request");
        });
        AssistantEngine.Result result = new AssistantEngine(MindAiApp.get(), fake).process("明天提醒我提交周报");
        assertTrue(result.message, result.success); assertEquals("create_todo", result.action); assertEquals(1, db.countOpenTodos()); assertEquals("明天提交周报", db.todos("open", "", 10).get(0).title);
        assertEquals(2, db.conversationMessages(10).size());
    }

    @Test public void testAssistantForcesSecondConfirmationForDeleteEvenWhenModelDoesNot() throws Exception {
        String todoId = db.addManualTodo("不可直接删除", "验证删除防线", 0, "medium");
        SecurePrefs secure = MindAiApp.get().secure();
        secure.saveOpenRouter("sk-or-assistant-delete", "https://local.openrouter.test/api/v1", "fixed", "local/assistant:free", false,
                "local/assistant:free", System.currentTimeMillis(), "local", "");
        OpenRouterClient fake = new OpenRouterClient((config, method, path, body) -> {
            JSONObject plan = new JSONObject().put("type", "tool_call").put("message", "").put("action", "delete_todo")
                    .put("target_id", todoId).put("title", "不可直接删除").put("description", "").put("query", "")
                    .put("priority", "medium").put("category", "other").put("due_at", JSONObject.NULL)
                    .put("start_at", JSONObject.NULL).put("end_at", JSONObject.NULL).put("all_day", false)
                    .put("requires_confirmation", false).put("confidence", .99);
            return chatResponse("local/assistant:free", plan.toString());
        });
        AssistantEngine engine = new AssistantEngine(MindAiApp.get(), fake);
        AssistantEngine.Result preview = engine.process("删除不可直接删除这个待办");
        assertTrue(preview.requiresConfirmation); assertNotNull(preview.pendingPlan); assertNotNull(db.todo(todoId));
        AssistantEngine.Result confirmed = engine.confirm(preview.pendingPlan);
        assertTrue(confirmed.message, confirmed.success); assertTrue(db.todo(todoId).deletedAt > 0);
        for (Models.TodoItem item : db.todos("open", "", 20)) assertNotEquals(todoId, item.id);
    }

    @Test public void testCalendarCandidateLocalSafetyGate() {
        Models.CalendarCandidate candidate = new Models.CalendarCandidate();
        candidate.title = "产品评审"; candidate.startAt = System.currentTimeMillis() + 3_600_000L;
        candidate.endAt = candidate.startAt + 3_600_000L; candidate.userInvolved = true;
        candidate.confidence = .95; candidate.sourceNotificationIds = "101";
        assertTrue(CalendarAutoCreator.isStructurallySafe(candidate, System.currentTimeMillis()));
        candidate.confidence = .91; assertFalse(CalendarAutoCreator.isStructurallySafe(candidate, System.currentTimeMillis()));
        candidate.confidence = .95;
        Models.CalendarEvent conflict = new Models.CalendarEvent(); conflict.title = "其他会议";
        conflict.startAt = candidate.startAt + 60_000L; conflict.endAt = candidate.endAt; conflict.availability = "busy";
        assertTrue(CalendarAutoCreator.duplicatesOrConflicts(candidate, java.util.Collections.singletonList(conflict)));
    }

    @Test public void testAssistantHomeAndMonthCalendarFollowV07Structure() {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        Intent intent = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(hasText(activity.getWindow().getDecorView(), "助理"));
        assertTrue(hasText(activity.getWindow().getDecorView(), "现在需要知道"));
        assertTrue(hasTextContaining(activity.getWindow().getDecorView(), "问助理"));
        assertFalse(hasText(activity.getWindow().getDecorView(), "今天"));
        View schedule = findText(activity.getWindow().getDecorView(), "日程");
        assertNotNull(schedule); activity.runOnUiThread(() -> clickViewOrParent(schedule)); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        View month = findText(activity.getWindow().getDecorView(), "月历");
        assertNotNull(month); activity.runOnUiThread(month::performClick); InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        String selected = new SimpleDateFormat("M月d日  EEEE", Locale.CHINA).format(new Date(AppDatabase.todayRange()[0]));
        assertTrue("Month selection must show that day inline", hasText(activity.getWindow().getDecorView(), selected));
        activity.finish();
    }

    @Test public void testRealProfileMarkdownImportWhenFixtureProvided() throws Exception {
        String path = InstrumentationRegistry.getArguments().getString("profile_path", "");
        if (path.isEmpty()) path = new File(MindAiApp.get().getExternalFilesDir(null), "daniel-profile.md").getAbsolutePath();
        Assume.assumeTrue("Real profile fixture not provided", !path.isEmpty() && new File(path).isFile());
        String markdown = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        long started = SystemClock.elapsedRealtime(); AppDatabase.MdImportResult result = db.importProfileMarkdown(markdown); long elapsed = SystemClock.elapsedRealtime() - started;
        assertTrue("Expected many profile facts from Daniel fixture", result.profileFacts >= 100);
        assertTrue("Expected many memories from Daniel fixture", result.memories >= 100);
        assertTrue("Import must not block long enough to trigger ANR, elapsed=" + elapsed, elapsed < 5_000);
        AppDatabase.MdImportResult repeated = db.importProfileMarkdown(markdown); assertEquals(0, repeated.profileFacts); assertEquals(0, repeated.memories);
    }

    @Test public void testAssistantBubbleProgressAndFixedHeader() throws Exception {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        MindAiApp.get().secure().saveOpenRouter("sk-or-v1-ui-progress-test", "https://openrouter.ai/api/v1",
                "fixed", "test/model:free", false, "test/model:free", System.currentTimeMillis(), "test", "");
        String answer = "这是用于验证对话完成状态的测试回复。";
        OpenRouterClient.setTestTransportForProcess((config, method, path, body) -> {
            SystemClock.sleep(1_200L);
            String plan = new JSONObject()
                    .put("type", "answer").put("message", answer).put("action", "none")
                    .put("target_id", "").put("title", "").put("description", "").put("query", "")
                    .put("priority", "medium").put("category", "other")
                    .put("due_at", JSONObject.NULL).put("start_at", JSONObject.NULL).put("end_at", JSONObject.NULL)
                    .put("all_day", false).put("requires_confirmation", false).put("confidence", .95).toString();
            return chatResponse(body.optString("model", "test/model:free"), plan);
        });

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        android.widget.EditText homeInput = findFirstEditText(activity.getWindow().getDecorView());
        View send = findContentDescription(activity.getWindow().getDecorView(), "发送给助理");
        assertNotNull(homeInput); assertNotNull(send);
        String question = "请给我一个测试回答";
        activity.runOnUiThread(() -> { homeInput.setText(question); send.performClick(); });

        assertTrue("User bubble should appear immediately", waitForText(activity, question, 1_000));
        assertTrue("A live progress state should appear immediately",
                waitForAnyText(activity, 1_000, "思考中", "回复中"));
        View title = findText(activity.getWindow().getDecorView(), "MindAI 助理");
        assertNotNull(title);
        assertFalse("Navigation header must stay outside the scrolling content", hasAncestor(title, android.widget.ScrollView.class));
        android.widget.EditText chatInput = findFirstEditText(activity.getWindow().getDecorView());
        assertNotNull(chatInput); assertEquals("", chatInput.getText().toString());
        assertEquals("The optimistic message must only render once", 1, countText(activity.getWindow().getDecorView(), question));

        assertTrue("Replying state should be visible while the model is working", waitForText(activity, "回复中", 1_500));
        assertTrue("Assistant response should become a bubble", waitForText(activity, answer, 3_000));
        assertTrue("Completion state should be visible", waitForText(activity, "回复完成", 1_000));
        activity.finish();
    }

    @Test public void testAssistantV072CardAndDirectConversationEntry() {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        db.addManualTodo("今天确认新版交付时间", "项目已接近关键节点，需要确认团队安排。",
                System.currentTimeMillis() + 90 * 60_000L, "urgent");
        new AssistantBriefEngine(context).refresh();
        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue(waitForText(activity, "查看待办", 2_000));
        assertTrue(hasTextContaining(activity.getWindow().getDecorView(), "重要原因："));
        assertFalse("Featured card must not contain the duplicated why action",
                hasText(activity.getWindow().getDecorView(), "为什么"));
        assertTrue(hasText(activity.getWindow().getDecorView(), "稍后"));

        int messagesBefore = db.conversationMessages(20).size();
        View entry = findContentDescriptionContaining(activity.getWindow().getDecorView(), "进入助理对话");
        assertNotNull("Assistant home must expose a direct conversation entry", entry);
        activity.runOnUiThread(entry::performClick);
        assertTrue(waitForText(activity, "MindAI 助理", 1_000));
        assertEquals("Opening chat must not create an empty message", messagesBefore, db.conversationMessages(20).size());
        activity.finish();
    }

    @Test public void testAssistantReplyContinuesAfterMinimizeAndReturnsFromStatusPill() throws Exception {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        MindAiApp.get().secure().saveOpenRouter("sk-or-v15-background-test", "https://openrouter.ai/api/v1",
                "fixed", "test/model:free", false, "test/model:free", System.currentTimeMillis(), "test", "");
        String answer = "后台回复已经完成，可以从状态提醒快速回来。";
        OpenRouterClient.setTestTransportForProcess((config, method, path, body) -> {
            SystemClock.sleep(1_300L);
            String plan = new JSONObject()
                    .put("type", "answer").put("message", answer).put("action", "none")
                    .put("target_id", "").put("title", "").put("description", "").put("query", "")
                    .put("priority", "medium").put("category", "other")
                    .put("due_at", JSONObject.NULL).put("start_at", JSONObject.NULL).put("end_at", JSONObject.NULL)
                    .put("all_day", false).put("requires_confirmation", false).put("confidence", .95).toString();
            return chatResponse(body.optString("model", "test/model:free"), plan);
        });

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        View entry = findContentDescriptionContaining(activity.getWindow().getDecorView(), "进入助理对话");
        assertNotNull(entry); activity.runOnUiThread(entry::performClick);
        assertTrue(waitForText(activity, "MindAI 助理", 1_000));
        android.widget.EditText input = findFirstEditText(activity.getWindow().getDecorView());
        View send = findContentDescription(activity.getWindow().getDecorView(), "发送消息");
        assertNotNull(input); assertNotNull(send);
        activity.runOnUiThread(() -> { input.setText("测试后台回复"); send.performClick(); });
        assertTrue(waitForText(activity, "思考中", 1_000));

        View minimize = findContentDescription(activity.getWindow().getDecorView(), "最小化对话");
        assertNotNull(minimize); activity.runOnUiThread(minimize::performClick);
        assertTrue(waitForText(activity, "现在需要知道", 1_000));
        assertTrue("Minimized request must expose progress outside chat",
                waitForAnyText(activity, 1_000, "助理正在思考…", "正在生成回复…"));
        View schedule = findText(activity.getWindow().getDecorView(), "日程");
        assertNotNull(schedule); activity.runOnUiThread(() -> clickViewOrParent(schedule));
        assertTrue(waitForText(activity, "今天", 1_000));
        assertTrue("Progress pill must remain visible on other tabs",
                waitForAnyText(activity, 1_000, "助理正在思考…", "正在生成回复…", "助理已回复 · 查看"));
        assertTrue("Completed reply must become an unread return affordance",
                waitForText(activity, "助理已回复 · 查看", 3_000));
        View returnToChat = findText(activity.getWindow().getDecorView(), "助理已回复 · 查看");
        assertNotNull(returnToChat); activity.runOnUiThread(() -> clickViewOrParent(returnToChat));
        assertTrue("Status pill must return to the exact conversation", waitForText(activity, answer, 1_500));
        activity.finish();
    }

    @Test public void testAssistantReplyNotificationIsPrivateAndDeepLinksToChat() {
        Context context = MindAiApp.get();
        context.getSharedPreferences(ComplianceSettings.PREFS, Context.MODE_PRIVATE).edit()
                .remove("last_assistant_reply_notice")
                .putBoolean("assistant_reply_notifications", true)
                .apply();
        runShell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        SummaryNotificationHelper.notifyAssistantReply(context, "assistant-test-message");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        StatusBarNotification found = null;
        for (int attempt = 0; attempt < 10 && found == null; attempt++) {
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (notification.getId() == SummaryNotificationHelper.ASSISTANT_REPLY_ID) found = notification;
            }
            if (found == null) SystemClock.sleep(100);
        }
        assertNotNull("Assistant completion should post a notification", found);
        assertEquals("MindAI 已回复", found.getNotification().extras.getString(android.app.Notification.EXTRA_TITLE));
        assertEquals("点击查看助理回复", found.getNotification().extras.getString(android.app.Notification.EXTRA_TEXT));
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, found.getNotification().visibility);
    }

    @Test public void testMinimizedDeleteWaitsForExplicitConfirmation() throws Exception {
        Context context = MindAiApp.get();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("onboarding_seen", true).apply();
        String todoId = db.addManualTodo("需要二次确认的待办", "不能在后台自动删除", 0, "medium");
        MindAiApp.get().secure().saveOpenRouter("sk-or-v15-confirm-test", "https://openrouter.ai/api/v1",
                "fixed", "test/model:free", false, "test/model:free", System.currentTimeMillis(), "test", "");
        OpenRouterClient.setTestTransportForProcess((config, method, path, body) -> {
            SystemClock.sleep(250L);
            String plan = new JSONObject().put("type", "tool_call").put("message", "确认删除这条待办？")
                    .put("action", "delete_todo").put("target_id", todoId).put("title", "需要二次确认的待办")
                    .put("description", "").put("query", "").put("priority", "medium").put("category", "other")
                    .put("due_at", JSONObject.NULL).put("start_at", JSONObject.NULL).put("end_at", JSONObject.NULL)
                    .put("all_day", false).put("requires_confirmation", false).put("confidence", .99).toString();
            return chatResponse(body.optString("model", "test/model:free"), plan);
        });

        Activity activity = InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        View entry = findContentDescriptionContaining(activity.getWindow().getDecorView(), "进入助理对话");
        assertNotNull(entry); activity.runOnUiThread(entry::performClick);
        assertTrue(waitForText(activity, "MindAI 助理", 1_000));
        android.widget.EditText input = findFirstEditText(activity.getWindow().getDecorView());
        View send = findContentDescription(activity.getWindow().getDecorView(), "发送消息");
        assertNotNull(input); assertNotNull(send);
        activity.runOnUiThread(() -> { input.setText("删除需要二次确认的待办"); send.performClick(); });
        assertTrue(waitForText(activity, "需要确认", 2_000));
        View minimize = findContentDescription(activity.getWindow().getDecorView(), "最小化对话");
        assertNotNull(minimize); activity.runOnUiThread(minimize::performClick);
        assertTrue(waitForText(activity, "需要你确认一项操作", 1_000));
        assertNotNull("Minimizing must not execute deletion", db.todo(todoId));
        SystemClock.sleep(500L);
        assertEquals(0, db.todo(todoId).deletedAt);

        View returnToChat = findText(activity.getWindow().getDecorView(), "需要你确认一项操作");
        assertNotNull(returnToChat); activity.runOnUiThread(() -> clickViewOrParent(returnToChat));
        assertTrue(waitForText(activity, "确认", 1_000));
        View confirmAction = findText(activity.getWindow().getDecorView(), "确认");
        assertNotNull(confirmAction); activity.runOnUiThread(() -> clickViewOrParent(confirmAction));
        long deadline = SystemClock.elapsedRealtime() + 2_000;
        while (SystemClock.elapsedRealtime() < deadline && db.todo(todoId).deletedAt == 0) SystemClock.sleep(25);
        assertTrue("Deletion may run only after explicit confirmation", db.todo(todoId).deletedAt > 0);
        activity.finish();
    }

    @Test public void testHighPriorityBriefNotificationAndSnoozeAction() {
        Context context = MindAiApp.get(); runShell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit().putBoolean("proactive_reminders_enabled", true).putBoolean("quiet_hours_enabled", false).apply();
        String todoId = db.addManualTodo("立即确认重要交付", "这是一条主动提醒测试", System.currentTimeMillis() + 30 * 60_000L, "urgent");
        Models.AssistantBriefItem item = null; for (Models.AssistantBriefItem candidate : new AssistantBriefEngine(context).refresh()) if (("todo:" + todoId).equals(candidate.id)) item = candidate;
        assertNotNull(item); SummaryNotificationHelper.notifyTopBrief(context, item);
        NotificationManager manager = context.getSystemService(NotificationManager.class); boolean found = false;
        for (int attempt = 0; attempt < 10 && !found; attempt++) {
            for (StatusBarNotification notification : manager.getActiveNotifications()) if (notification.getId() == 7400 + Math.abs(item.id.hashCode() % 500)) found = true;
            if (!found) SystemClock.sleep(100);
        }
        assertTrue("High-priority brief should post a local notification", found);
        Intent snooze = new Intent(context, BriefActionReceiver.class).setAction(BriefActionReceiver.ACTION_SNOOZE).putExtra(SummaryNotificationHelper.EXTRA_BRIEF_ID, item.id);
        new BriefActionReceiver().onReceive(context, snooze); assertEquals("snoozed", db.briefItem(item.id).status);
    }

    private static boolean hasWidgetKind(WidgetSummaryState.Content content, String kind) {
        return widgetItem(content, kind) != null;
    }

    private static Models.NotificationItem notification(String packageName, String appName,
                                                        String title, String content) {
        Models.NotificationItem item = new Models.NotificationItem();
        item.id = Math.abs((packageName + title + content).hashCode()) + 1L;
        item.packageName = packageName;
        item.appName = appName;
        item.title = title;
        item.content = content;
        return item;
    }

    private static WidgetSummaryState.Item widgetItem(WidgetSummaryState.Content content, String kind) {
        if (content == null) return null;
        for (WidgetSummaryState.Item item : content.items) if (kind.equals(item.kind)) return item;
        return null;
    }

    private static boolean hasText(View root, String expected) { return findText(root, expected) != null; }

    private static boolean hasTextContaining(View root, String expected) {
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            if (text.getText().toString().contains(expected) || (text.getHint() != null && text.getHint().toString().contains(expected))) return true;
        }
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            if (hasTextContaining(((ViewGroup) root).getChildAt(i), expected)) return true;
        return false;
    }

    private static View findText(View root, String expected) {
        if (root instanceof TextView && expected.equals(((TextView) root).getText().toString())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findText(((ViewGroup) root).getChildAt(i), expected); if (found != null) return found;
        }
        return null;
    }

    private static android.widget.EditText findFirstEditText(View root) {
        if (root instanceof android.widget.EditText) return (android.widget.EditText) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            android.widget.EditText found = findFirstEditText(((ViewGroup) root).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static View findContentDescription(View root, String description) {
        if (root.getContentDescription() != null && description.contentEquals(root.getContentDescription())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findContentDescription(((ViewGroup) root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }

    private static View findContentDescriptionContaining(View root, String description) {
        if (root.getContentDescription() != null && root.getContentDescription().toString().contains(description)) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = findContentDescriptionContaining(((ViewGroup) root).getChildAt(i), description); if (found != null) return found;
        }
        return null;
    }

    private static boolean hasAncestor(View view, Class<?> type) {
        android.view.ViewParent parent = view.getParent();
        while (parent != null) {
            if (type.isInstance(parent)) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean waitForText(Activity activity, String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() <= deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            if (hasText(activity.getWindow().getDecorView(), expected)) return true;
            SystemClock.sleep(25);
        }
        return false;
    }

    private static boolean waitForAnyText(Activity activity, long timeoutMs, String... expected) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() <= deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            for (String value : expected) if (hasText(activity.getWindow().getDecorView(), value)) return true;
            SystemClock.sleep(25);
        }
        return false;
    }

    private static int countText(View root, String expected) {
        int count = root instanceof TextView && expected.equals(((TextView) root).getText().toString()) ? 1 : 0;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++)
            count += countText(((ViewGroup) root).getChildAt(i), expected);
        return count;
    }

    private static void clickViewOrParent(View view) {
        View current = view;
        while (current != null && !current.isClickable() && current.getParent() instanceof View) current = (View) current.getParent();
        if (current != null) current.performClick();
    }

    private static void runShell(String command) {
        try (android.os.ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             java.io.BufferedInputStream input = new java.io.BufferedInputStream(
                     new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd))) {
            while (input.read() != -1) { /* drain command output */ }
        } catch (Exception ignored) {
        }
    }

    private static JSONObject chatResponse(String model, String content) throws Exception {
        return new JSONObject()
                .put("model", model)
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", "stop")
                        .put("message", new JSONObject()
                                .put("role", "assistant")
                                .put("content", content))));
    }
}
