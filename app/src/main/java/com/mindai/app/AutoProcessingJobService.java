package com.mindai.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AutoProcessingJobService extends JobService {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private ExecutorService executor;

    @Override public boolean onStartJob(JobParameters params) {
        if (!ComplianceSettings.canAutoProcess(this)) return false;
        if (!RUNNING.compareAndSet(false, true)) return false;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> run(params));
        return true;
    }

    private void run(JobParameters params) {
        SharedPreferences settings = getSharedPreferences("mindai_settings", MODE_PRIVATE);
        settings.edit().putBoolean("auto_processing_running", true).putLong("auto_last_started", System.currentTimeMillis()).apply();
        final String[] error = {""};
        try {
            if (!ComplianceSettings.canAutoProcess(this)) {
                throw new IllegalStateException("自动整理已关闭或尚未完成数据使用同意");
            }
            AppDatabase db = MindAiApp.get().db();
            final Models.SummarySession[] completed = {null};
            if (db.countUnsummarized() > 0 && MindAiApp.get().secure().configured()) {
                WidgetSummaryState.markRunning(this, db.countUnsummarized(), "自动整理中…");
                MindAiWidgetUpdater.updateAll(this);
                new SummaryEngine(db, MindAiApp.get().secure()).summarize(0, Long.MAX_VALUE, "", new SummaryEngine.Listener() {
                    @Override public void onProgress(String step, int percent) {
                        settings.edit().putString("auto_processing_step", step).apply();
                    }
                    @Override public void onSuccess(Models.SummarySession session) { completed[0] = session; }
                    @Override public void onError(String message) { error[0] = message; }
                });
                if (!error[0].isEmpty()) throw new IllegalStateException(error[0]);
            } else if (db.countUnsummarized() <= 0) {
                WidgetSummaryState.markIdle(this);
            }
            java.util.List<Models.AssistantBriefItem> briefs = new AssistantBriefEngine(this).refresh();
            if (!briefs.isEmpty()) SummaryNotificationHelper.notifyTopBrief(this, briefs.get(0));
            if (completed[0] != null) {
                WidgetSummaryState.markDone(this, completed[0], WidgetSummaryService.mostImportantText(completed[0]));
            }
            MindAiWidgetUpdater.updateAll(this);
            settings.edit()
                    .putLong("auto_last_success", System.currentTimeMillis())
                    .putString("auto_last_error", "")
                    .apply();
        } catch (Exception e) {
            String message = e.getMessage() == null || e.getMessage().isEmpty() ? "自动刷新失败" : e.getMessage();
            settings.edit().putString("auto_last_error", message).putLong("auto_last_failure", System.currentTimeMillis()).apply();
            WidgetSummaryState.markError(this, message);
            MindAiWidgetUpdater.updateAll(this);
        } finally {
            settings.edit().putBoolean("auto_processing_running", false).putString("auto_processing_step", "").apply();
            RUNNING.set(false);
            jobFinished(params, false);
            if (executor != null) executor.shutdown();
        }
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (executor != null) executor.shutdownNow();
        RUNNING.set(false);
        getSharedPreferences("mindai_settings", MODE_PRIVATE).edit().putBoolean("auto_processing_running", false).apply();
        return true;
    }
}
