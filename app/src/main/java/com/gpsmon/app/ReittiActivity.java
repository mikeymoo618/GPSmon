package com.gpsmon.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class ReittiActivity extends Activity {
    private EditText endpoint, token;
    private CheckBox enabled;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 2_000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.page(this);
        Ui.button(this, root, "Back to GPSmon", view -> finish());
        Ui.heading(this, root, "Reitti uploads");
        Ui.text(this, root, "Completed trips upload over an available internet connection. Enabling uploads also queues completed trips already saved on this phone.", 15, Ui.MUTED);
        Ui.text(this, root, "JSON ingestion URL", 15, Ui.INK);
        endpoint = new EditText(this);
        endpoint.setTextColor(Ui.INK);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(ReittiSettings.preferences(this).getString("endpoint", ReittiSettings.DEFAULT_URL));
        root.addView(endpoint);
        Ui.text(this, root, "Integration token", 15, Ui.INK);
        token = new EditText(this);
        token.setTextColor(Ui.INK);
        token.setHintTextColor(Ui.MUTED);
        token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setSaveEnabled(false);
        token.setHint(ReittiSettings.preferences(this).getString("token", "").isEmpty() ? "Enter token from Reitti Integrations" : "Leave blank to keep the saved token");
        root.addView(token);
        enabled = new CheckBox(this);
        enabled.setText("Upload completed trips automatically");
        enabled.setTextColor(Ui.INK);
        enabled.setChecked(ReittiSettings.enabled(this));
        root.addView(enabled);
        Ui.text(this, root, "The token is encrypted in phone-local storage. The supplied HTTP server receives your token and coordinates without transport encryption. HTTPS is supported when your server provides it.", 14, Ui.MUTED);
        Ui.button(this, root, "Save connection", view -> save());
        Ui.button(this, root, "Retry uploads now", view -> {
            if (!ReittiSettings.enabled(this)) { Toast.makeText(this, "Save and enable the connection first.", Toast.LENGTH_LONG).show(); return; }
            UploadScheduler.enqueue(this, true);
            Toast.makeText(this, "Uploads requested. Android may wait for network access.", Toast.LENGTH_LONG).show();
        });
        status = Ui.text(this, root, "", 15, Ui.ACCENT);
        Ui.text(this, root, "Get a GPSLogger integration token in Reitti Settings → Integrations. Its device assignment identifies this phone. Each recorded point is uploaded with its original timestamp. Trips and notes stay saved locally after upload.", 14, Ui.MUTED);
    }
    private void save() {
        try {
            ReittiSettings.save(this, endpoint.getText().toString(), token.getText().toString(), enabled.isChecked());
            token.setText("");
            token.setHint("Leave blank to keep the saved token");
            UploadScheduler.enqueue(this, true);
            Toast.makeText(this, "Connection saved", Toast.LENGTH_SHORT).show();
        } catch (java.io.IOException error) { Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show(); }
        update();
    }
    private void update() {
        if (status == null) return;
        String problem = ReittiSettings.preferences(this).getString("problem", "");
        int queued = 0;
        try (TripStore store = new TripStore(this)) {
            for (TripStore.Trip trip : store.trips()) {
                TripStore.Upload upload = store.upload(trip.id, ReittiSettings.target(this));
                if (!trip.active() && upload.sent < upload.total) queued++;
            }
        }
        status.setText((ReittiSettings.enabled(this) ? "Uploads enabled" : "Uploads disabled") + "\n"
                + queued + " completed trips waiting" + (UploadJob.running() ? "\nUploading" : "")
                + (problem.isEmpty() ? "" : "\n" + problem));
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
