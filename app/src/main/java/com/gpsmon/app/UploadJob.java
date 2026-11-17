package com.gpsmon.app;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UploadJob extends JobService {
    private static volatile Run active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final class Run {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ReittiClient client = new ReittiClient();
    }
    static boolean running() { return active != null; }
    private Run own;
    @Override public boolean onStartJob(JobParameters parameters) {
        if (!ReittiSettings.enabled(this)) return false;
        if (active != null) return false;
        Run run = new Run();
        active = run;
        own = run;
        new Thread(() -> {
            boolean retry = false;
            boolean configured = true;
            try (TripStore store = new TripStore(this)) {
                ReittiSettings.Config config = ReittiSettings.load(this);
                retry = new UploadEngine(run.client).drain(store, config, () -> run.cancelled.get()
                        || !ReittiSettings.enabled(this) || !config.target.equals(ReittiSettings.target(this)));
                ReittiSettings.preferences(this).edit().putString("problem", "").apply();
            } catch (IOException error) {
                configured = false;
                ReittiSettings.preferences(this).edit().putString("problem", "Re-enter your Reitti connection settings and token.").apply();
            } catch (RuntimeException error) {
                retry = true;
                ReittiSettings.preferences(this).edit().putString("problem", "Upload processing was interrupted. Saved progress will be retried.").apply();
                android.util.Log.e("GPSmon", "Upload processing failed", error);
            }
            boolean retryJob = retry && ReittiSettings.enabled(this);
            boolean canContinue = configured;
            main.post(() -> {
                if (active != run) return;
                active = null;
                own = null;
                if (!run.cancelled.get()) {
                    jobFinished(parameters, retryJob);
                    if (!retryJob && canContinue) {
                        main.postDelayed(() -> {
                            if (!ReittiSettings.enabled(this)) return;
                            try (TripStore check = new TripStore(this)) {
                                TripStore.Trip next = check.nextUpload(ReittiSettings.target(this));
                                if (next != null && !"Needs attention".equals(check.upload(next.id, ReittiSettings.target(this)).state))
                                    UploadScheduler.enqueue(this, true);
                            }
                        }, 1_000);
                    }
                } else if (ReittiSettings.enabled(this)) {
                    main.postDelayed(() -> UploadScheduler.enqueue(this, false), 1_000);
                }
            });
        }, "GPSmon-Reitti").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters parameters) {
        Run run = own;
        if (run != null) {
            run.cancelled.set(true);
            run.client.cancel();
        }
        return ReittiSettings.enabled(this);
    }
    @Override public void onDestroy() {
        Run run = own;
        if (run != null) { run.cancelled.set(true); run.client.cancel(); }
        super.onDestroy();
    }
}
