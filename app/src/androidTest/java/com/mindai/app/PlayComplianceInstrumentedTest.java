package com.mindai.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.app.Notification;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PlayComplianceInstrumentedTest {
    private Context context;
    private SharedPreferences settings;

    @Before public void setUp() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        settings = context.getSharedPreferences(ComplianceSettings.PREFS, Context.MODE_PRIVATE);
        settings.edit().clear().commit();
        new AiContentReportManager(context).clearLocalReports();
    }

    @After public void tearDown() {
        settings.edit().clear().commit();
        ComplianceSettings.migrate(context);
        new AiContentReportManager(context).clearLocalReports();
    }

    @Test public void safeDefaultsFailClosedBeforeConsent() {
        ComplianceSettings.migrate(context);
        assertEquals("recommended", settings.getString("capture_mode", ""));
        assertTrue(settings.getBoolean("exclude_sensitive", false));
        assertTrue(settings.getBoolean("redact_sensitive", false));
        assertFalse(settings.getBoolean("auto_processing_enabled", true));
        assertFalse(settings.getBoolean("auto_calendar_write_enabled", true));
        assertFalse(ComplianceSettings.canAutoProcess(context));
        assertFalse(ComplianceSettings.canAutoWriteCalendar(context));
    }

    @Test public void revokingAiConsentStopsAutomaticProcessing() {
        ComplianceSettings.migrate(context);
        ComplianceSettings.grantAiDataConsent(context);
        ComplianceSettings.grantAutoProcessingConsent(context);
        settings.edit().putBoolean("auto_processing_enabled", true).commit();
        assertTrue(ComplianceSettings.canAutoProcess(context));

        ComplianceSettings.revokeAiDataConsent(context);
        assertFalse(settings.getBoolean("auto_processing_enabled", true));
        assertFalse(ComplianceSettings.canAutoProcess(context));
    }

    @Test public void customEndpointIsRejectedBeforeAnyNetworkRequest() {
        OpenRouterClient.Config config = new OpenRouterClient.Config();
        config.apiKey = "sk-or-v1-compliance-local-test-key";
        config.baseUrl = "https://example.invalid/collect";
        try {
            new OpenRouterClient().validate(config);
            fail("A non-OpenRouter endpoint must not be accepted");
        } catch (Exception expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("OpenRouter 官方 API"));
        }
        assertEquals("https://openrouter.ai/api/v1", OpenRouterClient.API_BASE_URL);
    }

    @Test public void internalWidgetActionReceiverIsNotExported() throws Exception {
        ActivityInfo info = context.getPackageManager().getReceiverInfo(
                new ComponentName(context, WidgetActionReceiver.class),
                0);
        assertFalse(info.exported);
    }

    @Test public void selectedLauncherArtworkIsPackaged() {
        Bitmap icon = BitmapFactory.decodeResource(context.getResources(),
                R.drawable.ic_launcher_selected);
        assertNotNull(icon);
        assertTrue(icon.getWidth() >= 512);
        assertEquals(icon.getWidth(), icon.getHeight());
    }

    @Test public void foregroundSummaryCanBeStoppedByTheUser() {
        Notification notification =
                SummaryNotificationHelper.foreground(context, "合规测试");
        assertNotNull(notification.actions);
        boolean found = false;
        for (Notification.Action action : notification.actions) {
            if (action != null && "停止".contentEquals(action.title)) found = true;
        }
        assertTrue("Foreground data sync must expose a stop action", found);
    }

    @Test public void undeliveredAiReportIsEncryptedAtRest() {
        if (AiContentReportManager.hasEndpoint()) return;
        String unique = "private-report-body-" + System.currentTimeMillis();
        AiContentReportManager manager = new AiContentReportManager(context);
        AiContentReportManager.Result result =
                manager.submit("assistant", "test", unique, "其他违规内容");
        assertFalse(result.delivered);
        assertEquals(1, manager.pendingCount());
        String raw = context.getSharedPreferences(
                "mindai_ai_reports", Context.MODE_PRIVATE).getString("encrypted_queue", "");
        assertFalse(raw.isEmpty());
        assertFalse(raw.contains(unique));
    }
}
