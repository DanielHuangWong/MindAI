package com.mindai.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MindAiApp extends Application {
    private static MindAiApp instance;
    private static final ExecutorService MAINTENANCE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "mindai-db-maintenance");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private AppDatabase database;
    private CryptoManager crypto;
    private SecurePrefs securePrefs;
    private AssistantConversationManager assistantConversationManager;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        ComplianceSettings.migrate(this);
        crypto = new CryptoManager(this);
        database = new AppDatabase(this, crypto);
        securePrefs = new SecurePrefs(this, crypto);
        assistantConversationManager = new AssistantConversationManager(this, database, crypto);
        final int retentionDays = getSharedPreferences("mindai_settings", MODE_PRIVATE)
                .getInt("retention_days", 30);
        MAINTENANCE_EXECUTOR.execute(() -> {
            try {
                database.recoverInterruptedSessions();
                database.applyRetentionPolicy(retentionDays);
                new AiContentReportManager(this).flushPending();
            } catch (RuntimeException e) {
                // Maintenance must never prevent the process from reaching its first frame.
                android.util.Log.e("MindAiMaintenance", "Database maintenance failed", e);
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> MAINTENANCE_EXECUTOR.execute(() -> {
            try { database.migrateLegacyEncryption(2_500); }
            catch (RuntimeException e) { android.util.Log.e("MindAiMaintenance", "Encryption migration failed", e); }
        }), 5_000L);
        AutoProcessingScheduler.schedule(this);
    }

    public static MindAiApp get() { return instance; }
    public AppDatabase db() { return database; }
    public SecurePrefs secure() { return securePrefs; }
    public AssistantConversationManager assistantConversations() { return assistantConversationManager; }
}
