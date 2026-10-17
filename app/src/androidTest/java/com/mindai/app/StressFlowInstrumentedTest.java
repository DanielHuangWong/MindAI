package com.mindai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.Configurator;
import androidx.test.uiautomator.StaleObjectException;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@LargeTest
@RunWith(AndroidJUnit4.class)
public final class StressFlowInstrumentedTest {
    private static final int NOTIFICATION_COUNT = 3_000;
    private static final int TODO_COUNT = 1_000;
    private static final int MEMORY_COUNT = 1_000;
    private static final int PROFILE_COUNT = 1_000;
    private static final int CONVERSATION_COUNT = 100;
    private static final long RESPONSE_BUDGET_MS = 1_000;
    private static final long COLD_START_BUDGET_MS = 1_500;
    private static final long DATA_BUDGET_MS = 5_000;
    private static final long DATA_HARD_TIMEOUT_MS = 30_000;

    private Context context;
    private AppDatabase db;
    private UiDevice device;
    private Activity activity;
    private long testStartedAt;
    private long seedDurationMs;
    private long uiStartedAt;
    private long startPssKb;
    private long lastClickAt;
    private boolean measurementsReported;
    private final List<String> timings = new ArrayList<>();
    private final List<String> responseViolations = new ArrayList<>();

    @Before public void setUp() throws Exception {
        context = MindAiApp.get();
        db = MindAiApp.get().db();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // The default UIAutomator idle wait adds roughly 500 ms to every hierarchy read.
        // A short timeout measures MindAI's rendered feedback instead of automation latency.
        Configurator.getInstance().setWaitForIdleTimeout(25);
        db.clearEverything();
        MindAiApp.get().assistantConversations().resetForTests();
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit()
                .clear()
                .putBoolean("onboarding_seen", true)
                .putBoolean("auto_processing_enabled", false)
                .apply();
        ComplianceSettings.migrate(context);
        ComplianceSettings.grantAiDataConsent(context);
        long seedStarted = SystemClock.elapsedRealtime();
        seedStressData();
        seedDurationMs = SystemClock.elapsedRealtime() - seedStarted;
        assertEquals(NOTIFICATION_COUNT, db.countToday());
        assertEquals(TODO_COUNT, db.countOpenTodos());
        assertEquals(MEMORY_COUNT, db.countMemories());
        assertEquals(PROFILE_COUNT, db.countProfileFacts());
        assertEquals(CONVERSATION_COUNT, db.conversationMessages(200).size());
        runShell("logcat -c -b all");
        testStartedAt = System.currentTimeMillis();
    }

    @After public void tearDown() {
        if (!measurementsReported && uiStartedAt > 0) reportMeasurements();
        if (activity != null) activity.finish();
    }

    @Test public void testLargeDataAllPagesRespondWithinOneSecondWithoutCrash() throws Exception {
        uiStartedAt = SystemClock.elapsedRealtime();
        launchAssistantWithinBudget();
        startPssKb = Debug.getPss();
        exerciseAssistant();
        exerciseScheduleAndTodos();
        exerciseNotificationAndKnowledgeLibrary();
        exerciseSettingsPages();
        repeatPrimaryNavigation();
        reportMeasurements();
        assertNoCrashOrAnr();
        assertTrue("Responses over 1 second: " + responseViolations, responseViolations.isEmpty());
    }

    private void launchAssistantWithinBudget() {
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        long started = SystemClock.elapsedRealtime();
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        assertAppearsWithin(started, COLD_START_BUDGET_MS, "现在需要知道");
    }

    private void exerciseAssistant() {
        clickDescriptionAndWait("发送给助理", "MindAI 助理");
        assertAppearsWithin(SystemClock.elapsedRealtime(), DATA_BUDGET_MS, "压力对话");
        pressBackAndWait("现在需要知道");
    }

    private void exerciseScheduleAndTodos() {
        clickTextAndWait("日程", "今天");
        clickTextAndWait("月历", currentMonthLabel());
        clickTextAndWait("待办", "待办事项");

        rapidSearch("压力待办 0001", "压力待办 0001");
        clickContainsAndWait("压力待办", "待办详情");
        pressBackAndWait("待办事项");
    }

    private void exerciseNotificationAndKnowledgeLibrary() {
        clickTextAndWait("信息库", "通知", "LOADING", "日期");
        assertAppearsWithin(SystemClock.elapsedRealtime(), DATA_BUDGET_MS, "压力通知");
        clickTextAndWait("全部应用⌄", "按应用筛选");
        clickTextAndWait("全部应用", "日期", "正在加载");
        rapidSearch("压力通知 2999", "压力通知 2999", "LOADING");
        clickContainsAndWait("压力通知", "通知详情");
        pressBackAndWait("日期", "LOADING");

        clickTextAndWait("整理", "整理历史");
        assertAppearsWithin(SystemClock.elapsedRealtime(), DATA_BUDGET_MS, "条通知等待整理");
        clickTextAndWait("立即整理全部未总结通知", "总结全部未总结");
        pressBackAndWait("信息库", "整理历史");
        clickContainsAndWait("压力整理", "总结结果");
        pressBackAndWait("现在需要知道");

        clickTextAndWait("信息库", "通知", "LOADING", "日期");
        clickTextAndWait("记忆", "长期记忆", "LOADING");
        rapidSearch("压力记忆 0999", "压力记忆 0999", "LOADING");
        clickContainsAndWait("压力记忆", "记忆详情");
        pressBackAndWait("长期记忆", "LOADING");

        clickTextAndWait("画像", "个人画像", "LOADING");
        rapidSearch("压力画像 0999", "压力画像 0999", "LOADING");
        clickContainsAndWait("压力画像", "画像详情");
        pressBackAndWait("个人画像", "LOADING");
    }

    private void exerciseSettingsPages() {
        clickTextAndWait("我的", "自动整理");
        scrollClickTextAndWait("自动整理", "让重要信息主动出现");
        pressBackAndWait("我的");

        scrollClickTextAndWait("本地日历", "连接系统日历", "已连接的日历");
        pressBackAndWait("我的");

        scrollClickTextAndWait("OpenRouter", "OpenRouter 连接");
        pressBackAndWait("我的");

        scrollClickTextAndWait("应用范围", "采集范围");
        pressBackAndWait("我的");

        scrollClickTextAndWait("导出 / 删除", "数据与隐私");
        pressBackAndWait("我的");

        scrollClickTextAndWait("权限与连接状态", "待处理通知");
        pressBackAndWait("我的");
    }

    private void repeatPrimaryNavigation() {
        for (int i = 0; i < 5; i++) {
            clickTextAndWait("助理", "现在需要知道");
            clickTextAndWait("日程", "今天");
            clickTextAndWait("信息库", "个人画像", "LOADING");
            clickTextAndWait("我的", "自动整理");
        }
    }

    private void rapidSearch(String query, String... feedback) {
        UiObject2 input = firstEditText();
        assertNotNull("Expected a search field on the current page", input);
        input.click();
        // Simulate a burst of text changes, but measure the app only after UIAutomator has
        // delivered the final value. Cross-process text injection is not app response time.
        input.setText(query.substring(0, Math.min(2, query.length())));
        input.setText(query.substring(0, Math.min(5, query.length())));
        input.setText(query);
        long started = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, query, "LOADING", "正在加载", "正在读取");
        assertNonInputAppearsWithin(started, DATA_BUDGET_MS, query);
        hideKeyboardWithoutLeavingPage();
    }

    private UiObject2 firstEditText() {
        List<UiObject2> inputs = device.findObjects(By.clazz("android.widget.EditText"));
        return inputs.isEmpty() ? null : inputs.get(0);
    }

    private void clickTextAndWait(String text, String... feedback) {
        settleClickGuard();
        UiObject2 object = findClickableExact(text, false);
        assertNotNull("Could not find clickable text: " + text, object);
        long started = SystemClock.elapsedRealtime();
        object.click();
        lastClickAt = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, feedback);
    }

    private void scrollClickTextAndWait(String text, String... feedback) {
        settleClickGuard();
        UiObject2 object = findClickableExact(text, true);
        assertNotNull("Could not find clickable text after scrolling: " + text, object);
        long started = SystemClock.elapsedRealtime();
        object.click();
        lastClickAt = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, feedback);
    }

    private void clickContainsAndWait(String text, String... feedback) {
        settleClickGuard();
        UiObject2 object = findClickableContains(text, true);
        assertNotNull("Could not find clickable text containing: " + text, object);
        long started = SystemClock.elapsedRealtime();
        object.click();
        lastClickAt = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, feedback);
    }

    private void clickDescriptionAndWait(String description, String... feedback) {
        settleClickGuard();
        UiObject2 object = device.findObject(By.desc(description));
        assertNotNull("Could not find content description: " + description, object);
        long started = SystemClock.elapsedRealtime();
        object.click();
        lastClickAt = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, feedback);
    }

    private void pressBackAndWait(String... feedback) {
        settleClickGuard();
        long started = SystemClock.elapsedRealtime();
        device.pressBack();
        lastClickAt = SystemClock.elapsedRealtime();
        assertAppearsWithin(started, RESPONSE_BUDGET_MS, feedback);
    }

    private UiObject2 findClickableExact(String text, boolean scroll) {
        for (int attempt = 0; attempt < (scroll ? 8 : 1); attempt++) {
            for (UiObject2 object : device.findObjects(By.text(text))) {
                UiObject2 clickable = clickableAncestor(object);
                if (clickable != null) return clickable;
            }
            if (attempt < 7) swipeUp();
        }
        return null;
    }

    private UiObject2 findClickableContains(String text, boolean scroll) {
        for (int attempt = 0; attempt < (scroll ? 10 : 1); attempt++) {
            for (UiObject2 object : device.findObjects(By.textContains(text))) {
                if ("android.widget.EditText".equals(object.getClassName())) continue;
                UiObject2 clickable = clickableAncestor(object);
                if (clickable != null) return clickable;
            }
            if (attempt < 9) swipeUp();
        }
        return null;
    }

    private UiObject2 clickableAncestor(UiObject2 object) {
        UiObject2 current = object;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (current.isClickable()) return current;
            current = current.getParent();
        }
        return null;
    }

    private void swipeUp() {
        int width = device.getDisplayWidth();
        int height = device.getDisplayHeight();
        device.swipe(width / 2, height * 4 / 5, width / 2, height / 4, 20);
        SystemClock.sleep(80);
    }

    private void assertAppearsWithin(long started, long budgetMs, String... alternatives) {
        long hardTimeout = budgetMs >= DATA_BUDGET_MS ? DATA_HARD_TIMEOUT_MS : DATA_BUDGET_MS;
        long deadline = started + Math.max(hardTimeout, budgetMs);
        while (SystemClock.elapsedRealtime() <= deadline) {
            for (String text : alternatives) {
                if (text != null && !text.isEmpty()
                        && (device.hasObject(By.textContains(text)) || hasDirectNonInputText(text))) {
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    String timing = join(alternatives) + "=" + elapsed + "ms";
                    timings.add(timing);
                    if (elapsed > budgetMs) responseViolations.add(timing + " (budget " + budgetMs + "ms)");
                    return;
                }
            }
            SystemClock.sleep(20);
        }
        throw new AssertionError("No UI feedback within " + Math.max(hardTimeout, budgetMs)
                + " ms; expected " + join(alternatives));
    }

    private void assertNonInputAppearsWithin(long started, long budgetMs, String... alternatives) {
        long deadline = started + DATA_HARD_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() <= deadline) {
            for (String text : alternatives) {
                if (text == null || text.isEmpty()) continue;
                for (UiObject2 object : device.findObjects(By.textContains(text))) {
                    try {
                        if ("android.widget.EditText".equals(object.getClassName())) continue;
                    } catch (StaleObjectException stale) {
                        // Search results replace the loading row asynchronously; retry the
                        // fresh accessibility snapshot instead of failing the performance run.
                        continue;
                    }
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    String timing = "data " + join(alternatives) + "=" + elapsed + "ms";
                    timings.add(timing);
                    if (elapsed > budgetMs) responseViolations.add(timing + " (budget " + budgetMs + "ms)");
                    return;
                }
                if (hasDirectNonInputText(text)) {
                    long elapsed = SystemClock.elapsedRealtime() - started;
                    String timing = "data " + join(alternatives) + "=" + elapsed + "ms";
                    timings.add(timing);
                    if (elapsed > budgetMs) responseViolations.add(timing + " (budget " + budgetMs + "ms)");
                    return;
                }
            }
            SystemClock.sleep(20);
        }
        throw new AssertionError("No non-input data within " + DATA_HARD_TIMEOUT_MS
                + " ms; expected " + join(alternatives));
    }

    private boolean hasDirectNonInputText(String expected) {
        if (activity == null) return false;
        final boolean[] found = {false};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                found[0] = containsNonInputText(activity.getWindow().getDecorView(), expected));
        return found[0];
    }

    private static boolean containsNonInputText(View root, String expected) {
        if (root instanceof TextView && !(root instanceof EditText)
                && ((TextView) root).getText().toString().contains(expected)) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++)
                if (containsNonInputText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    private void hideKeyboardWithoutLeavingPage() {
        if (activity == null) return;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View focus = activity.getCurrentFocus();
            if (focus == null) return;
            InputMethodManager manager = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            focus.clearFocus();
        });
    }

    private void settleClickGuard() {
        long wait = 240 - (SystemClock.elapsedRealtime() - lastClickAt);
        if (wait > 0) SystemClock.sleep(wait);
    }

    private String currentMonthLabel() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy年 M月", Locale.CHINA);
        return format.format(new java.util.Date());
    }

    private void assertNoCrashOrAnr() throws Exception {
        assertTrue("MindAI window unexpectedly disappeared", device.hasObject(By.pkg(context.getPackageName())));
        String crash = runShell("logcat -b crash -d");
        assertFalse("Unhandled app crash found in logcat:\n" + crash, crash.contains(context.getPackageName()));
        String events = runShell("logcat -b events -d -s am_anr");
        assertFalse("ANR found in event log:\n" + events,
                events.contains(context.getPackageName()) || events.contains("com.mindai.app"));
        if (Build.VERSION.SDK_INT >= 30) {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 10)) {
                if (exit.getTimestamp() < testStartedAt) continue;
                int reason = exit.getReason();
                assertTrue("App exited because of crash/ANR: " + exit,
                        reason != ApplicationExitInfo.REASON_CRASH
                                && reason != ApplicationExitInfo.REASON_CRASH_NATIVE
                                && reason != ApplicationExitInfo.REASON_ANR);
            }
        }
    }

    private void reportMeasurements() {
        measurementsReported = true;
        long endPssKb = Debug.getPss();
        long uiDurationMs = SystemClock.elapsedRealtime() - uiStartedAt;
        String summary = "seed=" + seedDurationMs + "ms, ui=" + uiDurationMs + "ms, pss="
                + startPssKb + "KB->" + endPssKb + "KB, delta=" + (endPssKb - startPssKb)
                + "KB, timings=" + timings + ", violations=" + responseViolations;
        Log.i("MindAIStress", summary);
        Bundle status = new Bundle();
        status.putString("stress_summary", summary);
        status.putLong("stress_seed_ms", seedDurationMs);
        status.putLong("stress_ui_ms", uiDurationMs);
        status.putLong("stress_pss_start_kb", startPssKb);
        status.putLong("stress_pss_end_kb", endPssKb);
        status.putStringArrayList("stress_timings", new ArrayList<>(timings));
        status.putStringArrayList("stress_violations", new ArrayList<>(responseViolations));
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }

    private String runShell(String command) throws Exception {
        try (ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command);
             BufferedInputStream input = new BufferedInputStream(
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private void seedStressData() {
        long now = System.currentTimeMillis();
        long today = AppDatabase.todayRange()[0];
        // AppDatabase methods synchronize independently. Keep the database monitor for the
        // complete synthetic transaction so startup maintenance cannot acquire the monitor,
        // wait for this connection, and deadlock the fixture on slower API 26 devices.
        synchronized (db) {
            android.database.sqlite.SQLiteDatabase sql = db.getWritableDatabase();
            sql.beginTransaction();
            try {
                for (int i = 0; i < NOTIFICATION_COUNT; i++) {
                    db.saveNotification("stress-notification-" + i,
                            "com.mindai.stress." + (i % 12),
                            "压力应用 " + String.format(Locale.ROOT, "%02d", i % 12),
                            "压力通知 " + String.format(Locale.ROOT, "%04d", i),
                            "这是用于大数据加载和页面响应测试的通知正文 " + i,
                            today + (86_399_000L * i / NOTIFICATION_COUNT),
                            i % 17 == 0 ? "sensitive" : "normal");
                }

                for (int i = 0; i < TODO_COUNT; i++) {
                    db.addManualTodo("压力待办 " + String.format(Locale.ROOT, "%04d", i),
                            "待办说明与上下文 " + i,
                            now + (i + 1L) * 60_000L,
                            i % 10 == 0 ? "urgent" : "medium");
                }

                for (int i = 0; i < MEMORY_COUNT; i++) {
                    db.addManualMemory("压力记忆 " + String.format(Locale.ROOT, "%04d", i),
                            "长期记忆正文 " + i, "fact");
                }

                for (int i = 0; i < PROFILE_COUNT; i++) {
                    db.addManualProfileFact("压力画像",
                            "压力画像 " + String.format(Locale.ROOT, "%04d", i),
                            "用户画像事实 " + i);
                }

                for (int i = 0; i < CONVERSATION_COUNT; i++) {
                    db.addConversationMessage(i % 2 == 0 ? "user" : "assistant",
                            "压力对话 " + String.format(Locale.ROOT, "%04d", i), "", "", "");
                }
                sql.setTransactionSuccessful();
            } finally {
                sql.endTransaction();
            }
        }

        List<Models.NotificationItem> source = db.notifications(today, today + 86_400_000L,
                "", "unsummarized", "", 25);
        Models.SummarySession session = db.beginSession(source);
        Models.SummaryOutput output = new Models.SummaryOutput();
        output.overview = "压力整理：大量通知已形成测试结果";
        output.actualModel = "local/stress:free";
        db.completeSession(session, output);
        new AssistantBriefEngine(context).refresh();
    }

    private static String join(String[] values) {
        List<String> nonEmpty = new ArrayList<>();
        for (String value : values) if (value != null && !value.isEmpty()) nonEmpty.add(value);
        return nonEmpty.toString();
    }
}
