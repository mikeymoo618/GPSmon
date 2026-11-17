package com.gpsmon.app;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

final class UploadScheduler {
    static final int JOB_ID = 450;
    private UploadScheduler() {}
    static synchronized void enqueue(Context context, boolean manual) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (!ReittiSettings.enabled(context)) {
            scheduler.cancel(JOB_ID);
            return;
        }
        if (UploadJob.running()) return;
        if (!manual && scheduler.getPendingJob(JOB_ID) != null) return;
        if (manual) scheduler.cancel(JOB_ID);
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, UploadJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build();
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) {
            ReittiSettings.preferences(context).edit().putString("problem", "Android could not schedule uploads. Open Reitti settings and retry.").apply();
        }
    }
    static String status(Context context, TripStore store, TripStore.Trip trip) {
        if (trip.active()) return "Reitti · uploads after this trip ends";
        if (ReittiSettings.target(context).isEmpty()) return "Reitti · set up connection to upload";
        TripStore.Upload upload = store.upload(trip.id, ReittiSettings.target(context));
        if (upload.total == 0) return "Reitti · no GPS points to upload";
        if (upload.sent == upload.total) return "Reitti · uploaded " + upload.sent + " points";
        return "Reitti · " + (ReittiSettings.enabled(context) ? upload.state : "Paused")
                + " · " + upload.sent + "/" + upload.total + " points"
                + (upload.problem.isEmpty() ? "" : "\n" + Ui.date(upload.updated) + " · " + upload.problem);
    }
}
