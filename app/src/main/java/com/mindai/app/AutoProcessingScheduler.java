package com.mindai.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

public final class AutoProcessingScheduler {
    static final int JOB_ID = 6206;

    private AutoProcessingScheduler() {}

    public static void schedule(Context context) {
        SharedPreferences settings = context.getSharedPreferences("mindai_settings", Context.MODE_PRIVATE);
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        boolean enabled = ComplianceSettings.canAutoProcess(context);
        int minutes = settings.getInt("auto_interval_minutes", 15);
        if (!enabled || minutes <= 0) {
            scheduler.cancel(JOB_ID);
            return;
        }
        long interval = Math.max(15, minutes) * 60_000L;
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, AutoProcessingJobService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(interval)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }
}
