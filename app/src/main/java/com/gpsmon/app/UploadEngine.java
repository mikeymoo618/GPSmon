package com.gpsmon.app;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Single sequential queue. Each accepted point is durably acknowledged before sending the next. */
final class UploadEngine {
    private final ReittiClient client;
    UploadEngine(ReittiClient client) { this.client = client; }
    boolean drain(TripStore store, ReittiSettings.Config config, BooleanSupplier cancelled) {
        TripStore.Trip trip = null;
        long lastTime = -1;
        try {
            while (!cancelled.getAsBoolean() && (trip = store.nextUpload(config.target)) != null) {
                lastTime = store.upload(trip.id, config.target).lastTime;
                List<TripStore.Point> points = store.pendingPoints(trip.id, lastTime);
                for (TripStore.Point point : points) {
                    if (cancelled.getAsBoolean()) return true;
                    store.uploadProgress(trip.id, config.target, lastTime, "Uploading", "");
                    client.send(config, point, cancelled);
                    lastTime = point.time;
                    store.uploadProgress(trip.id, config.target, lastTime, "Uploading", "");
                }
                TripStore.Upload upload = store.upload(trip.id, config.target);
                if (upload.sent == upload.total) store.uploadProgress(trip.id, config.target, lastTime, "Uploaded", "");
            }
            return cancelled.getAsBoolean();
        } catch (IOException error) {
            boolean retry = !(error instanceof ReittiClient.UploadFailure) || ((ReittiClient.UploadFailure) error).retry;
            String message = error instanceof ReittiClient.UploadFailure ? error.getMessage() : "Connection interrupted. Saved points will be retried.";
            if (trip != null) store.uploadProgress(trip.id, config.target, lastTime, retry ? "Waiting to retry" : "Needs attention", message);
            return retry;
        } finally {
            if (trip != null && cancelled.getAsBoolean()) store.uploadProgress(trip.id, config.target, lastTime, "Paused", "Upload was interrupted. Saved progress is retained.");
        }
    }
}
