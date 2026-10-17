package com.mindai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.PendingIntent;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class NotificationOpenInstrumentedTest {
    private Context context;
    private AppDatabase db;
    private int userId;
    private Activity activity;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = MindAiApp.get().db();
        db.clearEverything();
        NotificationOpenCoordinator.clearForTests();
        userId = Math.max(0, android.os.Process.myUid() / 100_000);
    }

    @After public void tearDown() {
        NotificationOpenCoordinator.clearForTests();
        if (activity != null) activity.finish();
    }

    @Test public void testNotificationClickMetadataPersistsWithoutPersistingCapability() {
        long postedAt = System.currentTimeMillis();
        long id = db.saveNotification("metadata-key", context.getPackageName(), "MindAI", "标题", "正文",
                postedAt, "normal", true, userId);
        Models.NotificationItem item = db.notification(id);
        assertNotNull(item);
        assertTrue(item.hadContentIntentAtCapture);
        assertEquals(0, item.systemRemovedAt);
        assertEquals(userId, item.systemUserId);

        db.markSystemNotificationRemoved(item.sourceKey, item.postedAt, postedAt + 1000);
        assertEquals(postedAt + 1000, db.notification(id).systemRemovedAt);

        long duplicateId = db.saveNotification("metadata-key", context.getPackageName(), "MindAI", "标题", "正文",
                postedAt, "normal", false, userId);
        assertEquals(id, duplicateId);
        Models.NotificationItem refreshed = db.notification(id);
        assertFalse(refreshed.hadContentIntentAtCapture);
        assertEquals(0, refreshed.systemRemovedAt);
    }

    @Test public void testExactTargetRequiresMatchingKeyPackageTimeUserAndCreator() {
        long postedAt = System.currentTimeMillis();
        PendingIntent pendingIntent = activityPendingIntent(4101);
        Models.NotificationItem item = new Models.NotificationItem();
        item.sourceKey = "exact-key";
        item.packageName = context.getPackageName();
        item.appName = "MindAI";
        item.postedAt = postedAt;
        item.systemUserId = userId;
        item.hadContentIntentAtCapture = true;

        NotificationOpenCoordinator.registerForTests(item.sourceKey, item.packageName, postedAt,
                userId, pendingIntent);
        NotificationOpenCoordinator.Resolution exact = NotificationOpenCoordinator.resolve(context, item);
        assertEquals(NotificationOpenCoordinator.State.EXACT, exact.state);
        assertTrue(exact.exactAvailable());

        Models.NotificationItem older = copy(item);
        older.postedAt = postedAt - 1;
        NotificationOpenCoordinator.Resolution notReused = NotificationOpenCoordinator.resolve(context, older);
        assertFalse(notReused.exactAvailable());

        Models.NotificationItem wrongUser = copy(item);
        wrongUser.systemUserId = userId + 1;
        NotificationOpenCoordinator.Resolution blockedUser = NotificationOpenCoordinator.resolve(context, wrongUser);
        assertEquals(NotificationOpenCoordinator.State.BLOCKED, blockedUser.state);
    }

    @Test public void testCreatorMismatchIsBlockedAndCanceledIntentFallsBackSafely() {
        long postedAt = System.currentTimeMillis();
        PendingIntent pendingIntent = activityPendingIntent(4102);

        Models.NotificationItem mismatch = new Models.NotificationItem();
        mismatch.sourceKey = "mismatch-key";
        mismatch.packageName = "com.example.untrusted.source";
        mismatch.appName = "不可信来源";
        mismatch.postedAt = postedAt;
        mismatch.systemUserId = userId;
        mismatch.hadContentIntentAtCapture = true;
        NotificationOpenCoordinator.registerForTests(mismatch.sourceKey, mismatch.packageName,
                postedAt, userId, pendingIntent);
        assertEquals(NotificationOpenCoordinator.State.BLOCKED,
                NotificationOpenCoordinator.resolve(context, mismatch).state);

        Models.NotificationItem valid = copy(mismatch);
        valid.sourceKey = "canceled-key";
        valid.packageName = context.getPackageName();
        valid.appName = "MindAI";
        NotificationOpenCoordinator.registerForTests(valid.sourceKey, valid.packageName,
                postedAt, userId, pendingIntent);
        NotificationOpenCoordinator.Resolution exact = NotificationOpenCoordinator.resolve(context, valid);
        assertTrue(exact.exactAvailable());
        pendingIntent.cancel();
        assertEquals(NotificationOpenCoordinator.Outcome.CANCELED,
                NotificationOpenCoordinator.sendExact(context, exact));
        NotificationOpenCoordinator.Resolution fallback = NotificationOpenCoordinator.resolve(context, valid);
        assertFalse(fallback.exactAvailable());
        assertEquals(NotificationOpenCoordinator.State.APP_HOME, fallback.state);
        assertTrue(fallback.reason.contains("失效"));
    }

    @Test public void testTodoDetailShowsDistinctMultiSourcePicker() throws Exception {
        context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE).edit()
                .putBoolean("onboarding_seen", true)
                .putBoolean("auto_processing_enabled", false)
                .apply();
        long now = System.currentTimeMillis();
        long firstId = db.saveNotification("ui-source-1", context.getPackageName(), "MindAI",
                "项目群消息", "交付日期改到周五，请确认。", now - 2_000, "normal", true, userId);
        long secondId = db.saveNotification("ui-source-2", context.getPackageName(), "MindAI",
                "审批提醒", "请处理同一项目的审批。", now - 1_000, "normal", true, userId);
        Models.NotificationItem first = db.notification(firstId);
        Models.NotificationItem second = db.notification(secondId);
        NotificationOpenCoordinator.registerForTests(first.sourceKey, first.packageName, first.postedAt,
                userId, activityPendingIntent(4201));
        NotificationOpenCoordinator.registerForTests(second.sourceKey, second.packageName, second.postedAt,
                userId, activityPendingIntent(4202));

        Models.SummarySession session = db.beginSession(java.util.Arrays.asList(first, second));
        Models.SummaryOutput output = new Models.SummaryOutput();
        output.overview = "项目交付需要确认";
        output.actualModel = "local/test";
        Models.TodoItem todo = new Models.TodoItem();
        todo.title = "确认项目交付日期";
        todo.description = "查看来源消息并完成确认。";
        todo.priority = "high";
        todo.confidence = 0.95;
        todo.sourceNotificationIds = firstId + "," + secondId;
        output.todos.add(todo);
        db.completeSession(session, output);
        assertFalse(todo.id.isEmpty());

        Intent launch = new Intent(context, MainActivity.class)
                .setAction(MindAiWidgetUpdater.ACTION_OPEN_ITEM)
                .putExtra(MindAiWidgetUpdater.EXTRA_ITEM_TYPE, WidgetSummaryState.KIND_TODO)
                .putExtra(MindAiWidgetUpdater.EXTRA_ITEM_ID, todo.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = InstrumentationRegistry.getInstrumentation().startActivitySync(launch);
        UiDevice device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        assertTrue(device.wait(Until.hasObject(By.text("待办详情")), 4_000));
        UiScrollable page = new UiScrollable(new UiSelector().scrollable(true));
        assertTrue(page.scrollTextIntoView("打开来源消息"));
        View chooseSource = findTextView(activity.getWindow().getDecorView(), "打开来源消息");
        assertNotNull(chooseSource);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(chooseSource::performClick);
        assertTrue(device.wait(Until.hasObject(By.text("选择来源")), 4_000));
        assertTrue(device.hasObject(By.textContains("可打开原消息")));
        assertTrue(device.hasObject(By.text("项目群消息")));
        assertTrue(device.hasObject(By.text("审批提醒")));
    }

    private PendingIntent activityPendingIntent(int requestCode) {
        Intent intent = new Intent(context, MainActivity.class).setAction("test.notification.open." + requestCode);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static View findTextView(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTextView(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Models.NotificationItem copy(Models.NotificationItem source) {
        Models.NotificationItem copy = new Models.NotificationItem();
        copy.sourceKey = source.sourceKey;
        copy.packageName = source.packageName;
        copy.appName = source.appName;
        copy.postedAt = source.postedAt;
        copy.systemUserId = source.systemUserId;
        copy.systemRemovedAt = source.systemRemovedAt;
        copy.hadContentIntentAtCapture = source.hadContentIntentAtCapture;
        return copy;
    }
}
