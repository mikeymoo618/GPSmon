package com.gpsmon.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView problems;
    private TextView readiness;
    private LinearLayout trips;
    private TripStore store;
    private String tripFingerprint = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 2_000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new TripStore(this);
    }
    @Override protected void onResume() {
        super.onResume();
        draw();
        if (com.gpsmon.app.Settings.enabled(this) && com.gpsmon.app.Settings.ready(this)) startService();
        UploadScheduler.enqueue(this, false);
        handler.post(refresh);
    }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { store.close(); super.onDestroy(); }
    private void draw() {
        LinearLayout root = Ui.page(this);
        Ui.heading(this, root, "GPSmon");
        Ui.text(this, root, "Your journeys, recorded automatically", 16, Ui.MUTED);
        LinearLayout monitor = Ui.card(this, root);
        status = Ui.text(this, monitor, "", 20, Ui.ACCENT);
        readiness = Ui.text(this, monitor, "", 14, Ui.MUTED);
        problems = Ui.text(this, monitor, "", 14, Ui.MUTED);
        boolean enabled = com.gpsmon.app.Settings.enabled(this);
        Ui.button(this, monitor, enabled ? "Disable monitoring" : "Enable automatic monitoring", view -> toggle());
        Ui.heading(this, root, "Your cars");
        Ui.text(this, root, "Select paired car devices. Connecting starts a trip. Disconnecting allows 60 seconds to reconnect, then finishes the trip at the disconnection time.", 14, Ui.MUTED);
        devices(root);
        Ui.button(this, root, "Pair a Bluetooth device", view -> launch(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        Ui.heading(this, root, "Monitoring setup");
        Ui.button(this, root, "Grant Bluetooth and precise location", view -> permissions());
        Ui.button(this, root, "Allow background location", view -> backgroundPermission());
        Ui.button(this, root, "Allow monitoring notifications", view -> {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3);
            else launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
        Ui.button(this, root, "Allow unrestricted battery use", view -> launch(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));
        Ui.button(this, root, "Phone location settings", view -> launch(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        Ui.text(this, root, "Waiting mode uses Bluetooth events with GPS off. During trips, GPS is requested every 10 seconds. Android and the car can delay fixes or connection events. Force-stopping GPSmon prevents automatic starts until you reopen it.", 14, Ui.MUTED);
        Ui.text(this, root, "Trip coordinates stay in private phone storage. Android's address lookup provider may receive start/end coordinates to resolve addresses. Unresolved addresses can be retried from trip details.", 14, Ui.MUTED);
        Ui.heading(this, root, "Trips");
        Ui.button(this, root, "Reitti upload settings", view -> startActivity(new Intent(this, ReittiActivity.class)));
        trips = new LinearLayout(this);
        trips.setOrientation(LinearLayout.VERTICAL);
        root.addView(trips);
        Ui.text(this, root, Ui.version(this), 12, Ui.MUTED);
        tripFingerprint = "";
        update();
    }
    private void devices(LinearLayout root) {
        if (!com.gpsmon.app.Settings.bluetoothGranted(this)) {
            Ui.text(this, root, "Grant Bluetooth access to choose your car.", 15, Ui.MUTED);
            return;
        }
        BluetoothAdapter adapter = com.gpsmon.app.Settings.adapter(this);
        if (adapter == null) { Ui.text(this, root, "This phone has no Bluetooth adapter.", 15, Ui.MUTED); return; }
        try {
            List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
            devices.sort(Comparator.comparing(device -> device.getName() == null ? device.getAddress() : device.getName()));
            if (devices.isEmpty()) Ui.text(this, root, "Pair your car in Android Bluetooth settings first.", 15, Ui.MUTED);
            Set<String> selected = com.gpsmon.app.Settings.devices(this);
            for (BluetoothDevice device : devices) {
                CheckBox box = new CheckBox(this);
                box.setText((device.getName() == null ? "Bluetooth device" : device.getName()) + "\n" + device.getAddress());
                box.setTextColor(Ui.INK);
                box.setMinHeight(Ui.dp(this, 60));
                box.setChecked(selected.contains(device.getAddress()));
                box.setEnabled(!com.gpsmon.app.Settings.enabled(this));
                box.setOnCheckedChangeListener((button, checked) -> {
                    com.gpsmon.app.Settings.select(this, device.getAddress(), checked);
                    com.gpsmon.app.Settings.preferences(this).edit().putString("device_name_" + device.getAddress(), box.getText().toString().split("\n")[0]).apply();
                });
                root.addView(box);
            }
            if (com.gpsmon.app.Settings.enabled(this)) Ui.text(this, root, "Disable monitoring to change selected cars.", 13, Ui.MUTED);
        } catch (SecurityException error) { Ui.text(this, root, "Bluetooth permission needs to be restored.", 15, Ui.MUTED); }
    }
    private void permissions() {
        List<String> required = new ArrayList<>();
        required.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        required.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 31) required.add(Manifest.permission.BLUETOOTH_CONNECT);
        requestPermissions(required.toArray(new String[0]), 1);
    }
    private void backgroundPermission() {
        if (!com.gpsmon.app.Settings.locationGranted(this)) { permissions(); return; }
        if (Build.VERSION.SDK_INT == 29) requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 2);
        else if (Build.VERSION.SDK_INT >= 30) {
            new AlertDialog.Builder(this).setTitle("Allow location all the time")
                    .setMessage("Open Permissions → Location and choose Allow all the time. Keep precise location enabled so GPSmon can start a trip when your car connects while your phone is locked.")
                    .setPositiveButton("Open app settings", (dialog, which) -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())))).setNegativeButton("Cancel", null).show();
        } else Toast.makeText(this, "Android 9 uses the location permission already granted.", Toast.LENGTH_LONG).show();
    }
    private void toggle() {
        if (com.gpsmon.app.Settings.enabled(this)) {
            com.gpsmon.app.Settings.enabled(this, false);
            // Finish durably even if Android already stopped the service.
            TripStore.Trip trip = store.active();
            if (trip != null) {
                store.finish(trip.id, trip.disconnected > 0 ? trip.disconnected : System.currentTimeMillis(), "Monitoring disabled by user");
                Addresses.resolve(this, trip.id);
            }
            stopService(new Intent(this, MonitorService.class));
            com.gpsmon.app.Settings.status(this, "Monitoring disabled");
        } else {
            if (!com.gpsmon.app.Settings.ready(this)) {
                Toast.makeText(this, "Select a car and grant Bluetooth, precise location and background location first.", Toast.LENGTH_LONG).show();
                return;
            }
            com.gpsmon.app.Settings.enabled(this, true);
            startService();
        }
        draw();
    }
    private void startService() {
        try { startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.RECONCILE)); }
        catch (RuntimeException error) { com.gpsmon.app.Settings.failure(this, "Android prevented monitoring from starting. Check app permissions."); }
    }
    private void update() {
        if (status == null) return;
        boolean enabled = com.gpsmon.app.Settings.enabled(this);
        String state = com.gpsmon.app.Settings.preferences(this).getString("status", "Monitoring disabled");
        if (enabled && !MonitorService.running) state = "Monitoring interrupted · reopen GPSmon to restore";
        status.setText(enabled ? state : "Monitoring disabled");
        PowerManager power = getSystemService(PowerManager.class);
        boolean locationOn = getSystemService(LocationManager.class).isLocationEnabled();
        readiness.setText("Bluetooth permission " + yes(com.gpsmon.app.Settings.bluetoothGranted(this))
                + "\nPrecise location " + yes(com.gpsmon.app.Settings.locationGranted(this))
                + " · background " + yes(com.gpsmon.app.Settings.backgroundGranted(this))
                + "\nPhone location " + (locationOn ? "on" : "OFF")
                + " · battery unrestricted " + yes(power.isIgnoringBatteryOptimizations(getPackageName()))
                + "\nNotifications " + (getSystemService(android.app.NotificationManager.class).areNotificationsEnabled() ? "on" : "OFF"));
        long problemTime = com.gpsmon.app.Settings.preferences(this).getLong("problem_time", 0);
        problems.setText(problemTime == 0 ? "" : "Last interruption · " + Ui.date(problemTime) + "\n"
                + com.gpsmon.app.Settings.preferences(this).getString("last_problem", ""));
        List<TripStore.Trip> all = store.trips();
        StringBuilder fingerprint = new StringBuilder();
        for (TripStore.Trip trip : all) fingerprint.append(trip.id).append(trip.ended).append(trip.distance).append(trip.origin).append(trip.destination)
                .append(UploadScheduler.status(this, store, trip));
        if (fingerprint.toString().equals(tripFingerprint) && trips.getChildCount() > 0) return;
        tripFingerprint = fingerprint.toString();
        trips.removeAllViews();
        if (all.isEmpty()) Ui.text(this, trips, "Your first trip will appear when your selected car connects.", 15, Ui.MUTED);
        for (TripStore.Trip trip : all) {
            LinearLayout card = Ui.card(this, trips);
            Ui.text(this, card, trip.active() ? "Recording · " + trip.device : Ui.date(trip.started), 16, Ui.ACCENT);
            Ui.text(this, card, Addresses.label(trip.origin, store.endpoint(trip.id, true)) + "\n→ "
                    + (trip.active() ? "Trip in progress" : Addresses.label(trip.destination, store.endpoint(trip.id, false))), 16, Ui.INK);
            Ui.text(this, card, Ui.stats(trip), 15, Ui.MUTED);
            Ui.text(this, card, UploadScheduler.status(this, store, trip), 13, Ui.MUTED);
            Ui.button(this, card, "Trip details and notes", view -> startActivity(new Intent(this, TripActivity.class).putExtra("trip", trip.id)));
        }
    }
    private static String yes(boolean value) { return value ? "granted" : "needed"; }
    private void launch(Intent intent) {
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException error) { Toast.makeText(this, "Open the relevant setting in Android Settings.", Toast.LENGTH_LONG).show(); }
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        draw();
    }
}
