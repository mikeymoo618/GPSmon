package com.gpsmon.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.util.Xml;
import org.xmlpull.v1.XmlSerializer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class TripActivity extends Activity {
    private long tripId;
    private TripStore store;
    private EditText notes;
    private android.widget.TextView uploadStatus;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            TripStore.Trip trip = store.get(tripId);
            if (trip != null && uploadStatus != null) uploadStatus.setText(UploadScheduler.status(TripActivity.this, store, trip));
            handler.postDelayed(this, 2_000);
        }
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TripStore(this);
        tripId = getIntent().getLongExtra("trip", -1);
        show();
    }
    private void show() {
        TripStore.Trip trip = store.get(tripId);
        if (trip == null) { finish(); return; }
        LinearLayout root = Ui.page(this);
        Ui.button(this, root, "Back to trips", view -> finish());
        Ui.heading(this, root, trip.active() ? "Trip in progress" : "Trip details");
        Ui.text(this, root, Ui.date(trip.started), 17, Ui.ACCENT);
        Ui.text(this, root, trip.active() ? "Recording" : "Arrived " + Ui.date(trip.ended), 15, Ui.MUTED);
        Ui.text(this, root, Ui.stats(trip), 22, Ui.INK);
        uploadStatus = Ui.text(this, root, UploadScheduler.status(this, store, trip), 15, Ui.ACCENT);
        if (!trip.active()) Ui.button(this, root, "Retry upload", view -> {
            save();
            if (ReittiSettings.enabled(this)) UploadScheduler.enqueue(this, true);
            else startActivity(new Intent(this, ReittiActivity.class));
        });
        TripStore.Point first = store.endpoint(tripId, true);
        TripStore.Point last = store.endpoint(tripId, false);
        Ui.heading(this, root, "From");
        Ui.text(this, root, Addresses.label(trip.origin, first), 17, Ui.INK);
        Ui.heading(this, root, "To");
        Ui.text(this, root, trip.active() ? "Trip in progress" : Addresses.label(trip.destination, last), 17, Ui.INK);
        if (first != null) Ui.text(this, root, "First recorded fix " + Ui.date(first.time) + " · accuracy ±" + Math.round(first.accuracy) + " m", 13, Ui.MUTED);
        if (last != null) Ui.text(this, root, "Last recorded fix " + Ui.date(last.time) + " · accuracy ±" + Math.round(last.accuracy) + " m", 13, Ui.MUTED);
        Ui.text(this, root, "Car · " + trip.device + (trip.reason.isEmpty() ? "" : "\n" + trip.reason), 14, Ui.MUTED);
        Ui.button(this, root, "Retry address lookup", view -> {
            save();
            Addresses.resolve(this, tripId);
            Toast.makeText(this, "Address lookup requested. Tap Refresh after a few seconds.", Toast.LENGTH_LONG).show();
        });
        Ui.button(this, root, "Refresh details", view -> { save(); show(); });
        Ui.heading(this, root, "Notes");
        notes = new EditText(this);
        notes.setTextColor(Ui.INK);
        notes.setHintTextColor(Ui.MUTED);
        notes.setHint("Add the purpose of your journey");
        notes.setMinLines(3);
        notes.setGravity(android.view.Gravity.TOP);
        notes.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        notes.setText(trip.notes);
        root.addView(notes);
        Ui.button(this, root, "Save notes", view -> { save(); Toast.makeText(this, "Notes saved", Toast.LENGTH_SHORT).show(); });
        Ui.button(this, root, "Export GPX", view -> {
            save();
            Intent export = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/gpx+xml")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "GPSmon-trip-" + tripId + ".gpx");
            startActivityForResult(export, 1);
        });
    }
    private void save() { if (notes != null) store.notes(tripId, notes.getText().toString()); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); save(); super.onPause(); }
    @Override protected void onDestroy() { worker.shutdown(); store.close(); super.onDestroy(); }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1 || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        worker.execute(() -> {
            try (TripStore exportStore = new TripStore(getApplicationContext());
                 OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new java.io.IOException("Document could not be opened");
                export(exportStore, tripId, output);
                runOnUiThread(() -> Toast.makeText(this, "GPX saved", Toast.LENGTH_SHORT).show());
            } catch (Exception error) {
                android.util.Log.e("GPSmon", "GPX export failed", error);
                runOnUiThread(() -> Toast.makeText(this, "Export failed. Your trip remains saved in GPSmon.", Toast.LENGTH_LONG).show());
            }
        });
    }
    static void export(TripStore store, long tripId, OutputStream output) throws Exception {
        TripStore.Trip trip = store.get(tripId);
        if (trip == null) throw new IllegalArgumentException("Unknown trip");
        List<TripStore.Point> points = store.points(tripId);
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(output, StandardCharsets.UTF_8.name());
        xml.startDocument("UTF-8", true);
        xml.setPrefix("", "http://www.topografix.com/GPX/1/1");
        String ns = "http://www.topografix.com/GPX/1/1";
        xml.startTag(ns, "gpx").attribute(null, "version", "1.1").attribute(null, "creator", "GPSmon");
        xml.startTag(ns, "trk");
        xml.startTag(ns, "name").text("GPSmon trip " + tripId).endTag(ns, "name");
        String description = Addresses.label(trip.origin, points.isEmpty() ? null : points.get(0)) + " → "
                + Addresses.label(trip.destination, points.isEmpty() ? null : points.get(points.size() - 1));
        xml.startTag(ns, "desc").text(description + (trip.notes.isEmpty() ? "" : "\n" + trip.notes)).endTag(ns, "desc");
        xml.startTag(ns, "trkseg");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (TripStore.Point point : points) {
            xml.startTag(ns, "trkpt").attribute(null, "lat", Double.toString(point.latitude)).attribute(null, "lon", Double.toString(point.longitude));
            xml.startTag(ns, "ele").text(Double.toString(point.altitude)).endTag(ns, "ele");
            xml.startTag(ns, "time").text(format.format(new Date(point.time))).endTag(ns, "time");
            xml.endTag(ns, "trkpt");
        }
        xml.endTag(ns, "trkseg").endTag(ns, "trk").endTag(ns, "gpx");
        xml.endDocument();
        xml.flush();
    }
}
