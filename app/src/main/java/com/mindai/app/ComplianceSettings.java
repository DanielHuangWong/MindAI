package com.mindai.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Versioned consent and safe-default policy shared by every data entry point. */
final class ComplianceSettings {
    static final String PREFS = "mindai_settings";
    static final int NOTIFICATION_DISCLOSURE_VERSION = 1;
    static final int CALENDAR_DISCLOSURE_VERSION = 1;
    static final int AI_DATA_DISCLOSURE_VERSION = 1;
    static final int AUTO_PROCESSING_DISCLOSURE_VERSION = 1;
    static final int AUTO_CALENDAR_WRITE_DISCLOSURE_VERSION = 1;

    private ComplianceSettings() {}

    static void migrate(Context context) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        if (!prefs.contains("capture_mode")) editor.putString("capture_mode", "recommended");
        if (!prefs.contains("exclude_sensitive")) editor.putBoolean("exclude_sensitive", true);
        if (!prefs.contains("redact_sensitive")) editor.putBoolean("redact_sensitive", true);
        if (!prefs.contains("auto_interval_minutes")) editor.putInt("auto_interval_minutes", 15);
        if (!prefs.contains("auto_processing_enabled")) editor.putBoolean("auto_processing_enabled", false);
        if (!prefs.contains("auto_calendar_write_enabled")) editor.putBoolean("auto_calendar_write_enabled", false);

        // Earlier versions enabled background AI without a versioned disclosure. An upgrade must
        // fail closed until the user explicitly accepts the current disclosure.
        if (!hasAutoProcessingConsent(prefs)) editor.putBoolean("auto_processing_enabled", false);
        if (!hasAutoCalendarWriteConsent(prefs)) editor.putBoolean("auto_calendar_write_enabled", false);
        editor.putBoolean("play_compliance_v1_migrated", true).apply();
    }

    static boolean hasNotificationCaptureConsent(Context context) {
        return hasVersion(prefs(context), "notification_capture_consent_version",
                NOTIFICATION_DISCLOSURE_VERSION);
    }

    static void grantNotificationCaptureConsent(Context context) {
        prefs(context).edit()
                .putInt("notification_capture_consent_version", NOTIFICATION_DISCLOSURE_VERSION)
                .putLong("notification_capture_consent_at", System.currentTimeMillis())
                .apply();
    }

    static void revokeNotificationCaptureConsent(Context context) {
        prefs(context).edit()
                .remove("notification_capture_consent_version")
                .putLong("notification_capture_consent_revoked_at", System.currentTimeMillis())
                .putBoolean("auto_processing_enabled", false)
                .putBoolean("auto_calendar_write_enabled", false)
                .apply();
        AutoProcessingScheduler.cancel(context);
    }

    static boolean hasCalendarDisclosureConsent(Context context) {
        return hasVersion(prefs(context), "calendar_disclosure_consent_version",
                CALENDAR_DISCLOSURE_VERSION);
    }

    static void grantCalendarDisclosureConsent(Context context) {
        prefs(context).edit()
                .putInt("calendar_disclosure_consent_version", CALENDAR_DISCLOSURE_VERSION)
                .putLong("calendar_disclosure_consent_at", System.currentTimeMillis())
                .apply();
    }

    static boolean hasAiDataConsent(Context context) {
        return hasVersion(prefs(context), "ai_data_consent_version",
                AI_DATA_DISCLOSURE_VERSION);
    }

    static void grantAiDataConsent(Context context) {
        prefs(context).edit()
                .putInt("ai_data_consent_version", AI_DATA_DISCLOSURE_VERSION)
                .putLong("ai_data_consent_at", System.currentTimeMillis())
                .apply();
    }

    static void revokeAiDataConsent(Context context) {
        prefs(context).edit()
                .remove("ai_data_consent_version")
                .putLong("ai_data_consent_revoked_at", System.currentTimeMillis())
                .putBoolean("auto_processing_enabled", false)
                .putBoolean("auto_calendar_write_enabled", false)
                .apply();
        AutoProcessingScheduler.cancel(context);
    }

    static boolean hasAutoProcessingConsent(Context context) {
        return hasAutoProcessingConsent(prefs(context));
    }

    static void grantAutoProcessingConsent(Context context) {
        prefs(context).edit()
                .putInt("auto_processing_consent_version", AUTO_PROCESSING_DISCLOSURE_VERSION)
                .putLong("auto_processing_consent_at", System.currentTimeMillis())
                .apply();
    }

    static boolean canAutoProcess(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean("auto_processing_enabled", false)
                && hasAutoProcessingConsent(prefs)
                && hasAiDataConsent(context);
    }

    static boolean hasAutoCalendarWriteConsent(Context context) {
        return hasAutoCalendarWriteConsent(prefs(context));
    }

    static void grantAutoCalendarWriteConsent(Context context) {
        prefs(context).edit()
                .putInt("auto_calendar_write_consent_version",
                        AUTO_CALENDAR_WRITE_DISCLOSURE_VERSION)
                .putLong("auto_calendar_write_consent_at", System.currentTimeMillis())
                .apply();
    }

    static boolean canAutoWriteCalendar(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.getBoolean("auto_calendar_write_enabled", false)
                && hasAutoCalendarWriteConsent(prefs)
                && canAutoProcess(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean hasAutoProcessingConsent(SharedPreferences prefs) {
        return hasVersion(prefs, "auto_processing_consent_version",
                AUTO_PROCESSING_DISCLOSURE_VERSION);
    }

    private static boolean hasAutoCalendarWriteConsent(SharedPreferences prefs) {
        return hasVersion(prefs, "auto_calendar_write_consent_version",
                AUTO_CALENDAR_WRITE_DISCLOSURE_VERSION);
    }

    private static boolean hasVersion(SharedPreferences prefs, String key, int required) {
        return prefs.getInt(key, 0) >= required;
    }
}
