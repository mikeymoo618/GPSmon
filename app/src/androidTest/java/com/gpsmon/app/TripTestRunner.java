package com.gpsmon.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/** Release-APK tests with actual Android service/location lifecycles and synthetic Bluetooth events. */
public final class TripTestRunner extends Instrumentation {
    private Context app;
    private int groups;
    private String mode = "full";
    private volatile Activity resumed;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) mode = arguments.getString("mode", "full");
        start();
    }
    @Override public void onStart() {
        app = getTargetContext();
        ((android.app.Application) app.getApplicationContext()).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) { resumed = activity; }
            @Override public void onActivityCreated(Activity activity, Bundle state) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
        Bundle result = new Bundle();
        try {
            if (!"full".equals(mode)) {
                upgrade();
                result.putString("stream", "PASS: signed upgrade " + mode + "\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            storage();
            service();
            Intent launch = new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(launch);
            await(() -> resumed instanceof MainActivity, 10_000, "Main screen resumed");
            check(!Settings.enabled(app), "Test teardown disabled monitoring");
            pass("launcher screen and disabled monitoring");
            runOnMainSync(() -> button(resumed.getWindow().getDecorView(), "Trip details and notes").performClick());
            await(() -> resumed instanceof TripActivity, 10_000, "Trip detail screen resumed");
            long editedTrip = resumed.getIntent().getLongExtra("trip", -1);
            runOnMainSync(() -> {
                edit(resumed.getWindow().getDecorView()).setText("Notes edited through trip screen");
                button(resumed.getWindow().getDecorView(), "Save notes").performClick();
            });
            try (TripStore store = new TripStore(app)) {
                check(store.get(editedTrip).notes.equals("Notes edited through trip screen"), "Actual notes control persists edit");
            }
            runOnMainSync(() -> button(resumed.getWindow().getDecorView(), "Back to trips").performClick());
            await(() -> resumed instanceof MainActivity, 10_000, "Back control returns to trips");
            pass("trip details, editable notes and back navigation through actual controls");
            result.putString("stream", "PASS: GPSmon suite complete, " + groups + " groups\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            android.util.Log.e("GPSmonTest", "FAIL", error);
            result.putString("stream", "FAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (!"bootseed".equals(mode)) {
                Settings.enabled(app, false);
                app.stopService(new Intent(app, MonitorService.class));
            }
        }
    }
    private static android.widget.Button button(android.view.View root, String label) {
        if (root instanceof android.widget.Button && label.contentEquals(((android.widget.Button) root).getText())) return (android.widget.Button) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.Button result = button(group.getChildAt(index), label);
                if (result != null) return result;
            }
        }
        return null;
    }
    private static android.widget.EditText edit(android.view.View root) {
        if (root instanceof android.widget.EditText) return (android.widget.EditText) root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                android.widget.EditText result = edit(group.getChildAt(index));
                if (result != null) return result;
            }
        }
        return null;
    }
    private void upgrade() {
        try (TripStore store = new TripStore(app)) {
            if ("seed".equals(mode)) {
                long now = System.currentTimeMillis();
                TripStore.Trip trip = store.start("Upgrade fixture", now);
                store.add(trip.id, point(now, -36.85, 174.76, 5));
                store.notes(trip.id, "Preserved through signed upgrade");
                store.address(trip.id, true, "Upgrade origin");
                store.finish(trip.id, now + 10_000, "Upgrade fixture");
                Settings.select(app, "02:00:00:00:56:78", true);
                Settings.preferences(app).edit().putLong("upgrade_trip", trip.id).commit();
            } else if ("verify".equals(mode)) {
                long id = Settings.preferences(app).getLong("upgrade_trip", -1);
                TripStore.Trip trip = store.get(id);
                check(trip != null && trip.notes.equals("Preserved through signed upgrade"), "Trip notes survived upgrade");
                check(trip.origin.equals("Upgrade origin") && store.points(id).size() == 1, "Address and GPS point survived upgrade");
                check(Settings.devices(app).contains("02:00:00:00:56:78"), "Car selection survived upgrade");
            } else if ("bootseed".equals(mode)) {
                Settings.select(app, "02:00:00:00:56:78", true);
                Settings.enabled(app, true);
            } else if ("bootcleanup".equals(mode)) {
                check(Settings.enabled(app), "Enabled monitoring survived real emulator reboot");
                Settings.enabled(app, false);
            } else throw new IllegalArgumentException("Unknown verification mode");
        }
    }
    private void storage() throws Exception {
        String database = "verification-" + System.currentTimeMillis() + ".db";
        long start = System.currentTimeMillis() - 60_000;
        long id;
        try (TripStore store = new TripStore(app, database)) {
            TripStore.Trip trip = store.start("Test car", start);
            id = trip.id;
            check(store.start("Duplicate connect", start).id == id, "Duplicate start coalesced");
            check(store.trips().size() == 1, "One active trip");
            pass("atomic trip start and duplicate connection");
            check(store.add(id, point(start, -36.85, 174.76, 5)), "First point accepted");
            check(!store.add(id, point(start, -36.85, 174.76, 5)), "Duplicate timestamp rejected");
            check(!store.add(id, point(start + 1_000, -36.85, 174.76, 5)), "Sampling interval enforced");
            check(!store.add(id, point(start + 10_000, -36.85, 174.76, 100)), "Inaccurate point rejected");
            check(!store.add(id, point(start + 10_000, Double.NaN, 174.76, 5)), "Invalid coordinate rejected");
            check(!store.add(id, point(start + 10_000, -35, 174.76, 5)), "Impossible jump rejected");
            check(store.add(id, point(start + 10_000, -36.849, 174.76, 5)), "Second road point accepted");
            check(store.get(id).distance > 100 && store.get(id).distance < 120, "Measured distance around 111 metres");
            pass("10-second sampling, accuracy, coordinate and jump filters");
            double distance = store.get(id).distance;
            check(store.add(id, point(start + 20_000, -36.849, 174.76, 5)), "Stationary point retained");
            check(store.get(id).distance == distance, "Stationary noise adds no distance");
            store.disconnected(id, start + 25_000);
            check(!store.add(id, point(start + 30_000, -36.848, 174.76, 5)), "No points during reconnect window");
            store.disconnected(id, 0);
            check(store.add(id, point(start + 30_000, -36.848, 174.76, 5)), "Reconnected trip resumes");
            pass("stationary noise and disconnect/reconnect persistence");
            store.notes(id, "Meeting <client> & return");
            store.address(id, true, "1 Start Street");
            store.address(id, false, "2 Finish Road");
            store.finish(id, start + 35_000, "Test complete");
            check(!store.add(id, point(start + 40_000, -36.847, 174.76, 5)), "Closed trip rejects points");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            TripActivity.export(store, id, output);
            String xml = output.toString(StandardCharsets.UTF_8.name());
            check(xml.contains("&lt;client&gt;") && xml.contains("&amp; return"), "Notes escaped for GPX");
            org.xmlpull.v1.XmlPullParser parser = android.util.Xml.newPullParser();
            parser.setInput(new java.io.StringReader(xml));
            int points = 0;
            while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.getEventType() == org.xmlpull.v1.XmlPullParser.START_TAG && "trkpt".equals(parser.getName())) points++;
            }
            check(points == 4, "All four accepted points exported");
            check(store.get(id).ended - store.get(id).started == 35_000, "Trip duration retained");
            pass("trip notes, endpoints, duration and valid GPX export");
        }
        try (TripStore reopened = new TripStore(app, database)) {
            check(reopened.get(id).notes.equals("Meeting <client> & return"), "Notes survive reopening");
            check(reopened.points(id).size() == 4, "All points survive reopening");
            check(reopened.get(id).origin.equals("1 Start Street"), "Address survives reopening");
            long second = reopened.start("Next car", System.currentTimeMillis()).id;
            reopened.disconnected(second, System.currentTimeMillis() - 10_000);
        }
        try (TripStore reopened = new TripStore(app, database)) {
            check(reopened.active().disconnected > 0, "Reconnect deadline survives reopening");
            pass("database reopen and interrupted-trip recovery state");
        }
    }
    @SuppressWarnings("deprecation")
    private void service() throws Exception {
        String address = "02:00:00:00:12:34";
        Settings.select(app, address, true);
        Settings.enabled(app, true);
        check(Settings.ready(app), "Required permissions granted by emulator runner");
        Intent connected = new Intent(app, MonitorService.class).setAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                .putExtra(MonitorReceiver.DEVICE_ADDRESS, address);
        Intent disconnected = new Intent(app, MonitorService.class).setAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                .putExtra(MonitorReceiver.DEVICE_ADDRESS, address);
        LocationManager manager = app.getSystemService(LocationManager.class);
        boolean provider = false;
        try (TripStore store = new TripStore(app)) {
            manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, 1, 1);
            provider = true;
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true);
            app.startForegroundService(connected);
            await(() -> store.active() != null && MonitorService.running, 15_000, "Bluetooth event starts foreground trip service");
            long id = store.active().id;
            app.startForegroundService(connected);
            SystemClock.sleep(1_000);
            check(store.active().id == id, "Duplicate Bluetooth event keeps trip");
            pass("automatic foreground service and duplicate Bluetooth start");
            Location first = point(System.currentTimeMillis(), -36.85, 174.76, 5);
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, first);
            await(() -> store.points(id).size() >= 1, 15_000, "Real location listener saves injected GPS fix");
            SystemClock.sleep(10_500);
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, point(System.currentTimeMillis(), -36.849, 174.76, 5));
            await(() -> store.points(id).size() >= 2, 15_000, "10-second location listener saves next point");
            check(store.get(id).distance > 100, "Service distance updated");
            pass("Android location provider, 10-second sampling and trip distance");
            app.startForegroundService(disconnected);
            await(() -> store.active() != null && store.active().disconnected > 0, 5_000, "Disconnect persisted");
            SystemClock.sleep(1_500);
            app.startForegroundService(connected);
            await(() -> store.active() != null && store.active().disconnected == 0, 5_000, "Reconnection clears deadline");
            check(store.active().id == id, "Brief dropout preserves trip");
            pass("brief Bluetooth dropout coalesces into original trip");
            app.startForegroundService(disconnected);
            await(() -> store.active() != null && store.active().disconnected > 0, 5_000, "Second disconnect persisted");
            long disconnectedAt = store.active().disconnected;
            await(() -> store.get(id).ended > 0, 75_000, "Trip closes after 60-second grace");
            check(store.get(id).ended == disconnectedAt, "Arrival uses disconnect timestamp");
            check(store.active() == null, "No active trip after disconnect");
            pass("60-second reconnection deadline and correct arrival time");
            app.startForegroundService(connected);
            await(() -> store.active() != null, 5_000, "Next connection starts next trip");
            check(store.active().id != id, "Next trip has distinct identity");
            long next = store.active().id;
            app.stopService(new Intent(app, MonitorService.class));
            await(() -> !MonitorService.running, 5_000, "Service stopped for interruption fixture");
            check(store.get(next).active(), "Interrupted trip remains recoverable");
            app.startForegroundService(new Intent(app, MonitorService.class).setAction(MonitorService.RECONCILE));
            await(() -> !store.get(next).active(), 15_000, "Disconnected interrupted trip recovered");
            pass("subsequent trip and service-interruption recovery");
        } finally {
            Settings.enabled(app, false);
            app.stopService(new Intent(app, MonitorService.class));
            if (provider) manager.removeTestProvider(LocationManager.GPS_PROVIDER);
        }
    }
    private static Location point(long time, double latitude, double longitude, float accuracy) {
        Location point = new Location(LocationManager.GPS_PROVIDER);
        point.setTime(time);
        point.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        point.setLatitude(latitude);
        point.setLongitude(longitude);
        point.setAccuracy(accuracy);
        return point;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(Callable<Boolean> condition, long timeout, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (!condition.call()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(message);
            SystemClock.sleep(100);
        }
    }
    private void pass(String name) {
        groups++;
        Bundle status = new Bundle();
        status.putString("stream", "PASS: " + name + "\n");
        sendStatus(0, status);
    }
}
