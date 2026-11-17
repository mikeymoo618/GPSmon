package com.gpsmon.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.util.HashSet;
import java.util.Set;

public final class MonitorService extends Service implements LocationListener {
    static final String RECONCILE = "com.gpsmon.app.RECONCILE";
    private static final String CHANNEL = "trip_monitor";
    private static final int NOTIFICATION = 1;
    static volatile boolean running;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> connected = new HashSet<>();
    private TripStore store;
    private LocationManager locations;
    private boolean tracking;
    private boolean receiverRegistered;
    private int generation;
    private long lastFixElapsed;
    private String currentStatus = "Starting Bluetooth monitoring";
    private final BroadcastReceiver events = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { handle(intent); }
    };
    private final Runnable deadline = this::expireDisconnect;
    private final Runnable health = new Runnable() {
        @Override public void run() {
            if (!tracking) return;
            if (!Settings.ready(MonitorService.this)) {
                fail("Permissions changed. Open GPSmon to restore monitoring.");
                return;
            }
            if (store.active() == null) {
                stopGps();
                setStatus("Ready · waiting for your car");
                return;
            }
            if (SystemClock.elapsedRealtime() - lastFixElapsed > 45_000) {
                setStatus("Trip active · waiting for an accurate GPS fix");
            }
            handler.postDelayed(this, 15_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        store = new TripStore(this);
        locations = getSystemService(LocationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Automatic trip monitoring", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Bluetooth readiness, GPS recording and monitoring problems");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        running = true;
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            foreground(tracking);
            if (!Settings.enabled(this) || !Settings.ready(this)) {
                fail("Monitoring needs setup. Open GPSmon to check permissions and selected cars.");
                return START_NOT_STICKY;
            }
            if (!receiverRegistered) {
                IntentFilter filter = new IntentFilter();
                filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
                filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
                filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(events, filter, RECEIVER_EXPORTED);
                else registerReceiver(events, filter);
                receiverRegistered = true;
                reconcile();
            }
            if (intent != null && RECONCILE.equals(intent.getAction())) reconcile();
            else if (intent != null) handle(intent);
            return START_STICKY;
        } catch (RuntimeException error) {
            Log.e("GPSmon", "Monitoring startup failed", error);
            fail("Monitoring could not start. Check Bluetooth, permissions and Android battery settings.");
            return START_NOT_STICKY;
        }
    }
    private void handle(Intent intent) {
        String action = intent.getAction();
        try {
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = MonitorReceiver.device(intent);
                String address = device == null ? intent.getStringExtra(MonitorReceiver.DEVICE_ADDRESS) : device.getAddress();
                if (address == null || !Settings.devices(this).contains(address)) return;
                generation++;
                if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                    connected.add(address);
                    connect(Settings.preferences(this).getString("device_name_" + address, address));
                } else {
                    connected.remove(address);
                    if (connected.isEmpty()) disconnect();
                }
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    generation++;
                    connected.clear();
                    disconnect();
                    if (store.active() == null) setStatus("Bluetooth is off · trips cannot start automatically");
                } else if (state == BluetoothAdapter.STATE_ON) reconcile();
            }
        } catch (SecurityException error) {
            Log.e("GPSmon", "Bluetooth permission revoked", error);
            fail("Bluetooth permission was revoked. Open GPSmon to restore monitoring.");
        } catch (RuntimeException error) {
            Log.e("GPSmon", "Bluetooth event failed", error);
            fail("Bluetooth access failed. Open GPSmon to restore monitoring.");
        }
    }
    private void reconcile() {
        BluetoothAdapter adapter = Settings.adapter(this);
        if (adapter == null || !adapter.isEnabled()) {
            connected.clear();
            restoreWithoutConnection();
            setStatus("Bluetooth is off · trips cannot start automatically");
            return;
        }
        int request = ++generation;
        Set<String> found = new HashSet<>();
        String[] carName = {"Selected car"};
        int[] pending = {2};
        Runnable finish = () -> {
            if (request != generation) return;
            generation++;
            connected.clear();
            connected.addAll(found);
            if (connected.isEmpty()) restoreWithoutConnection(); else connect(carName[0]);
        };
        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                try {
                    if (request == generation) {
                        for (BluetoothDevice device : proxy.getConnectedDevices()) {
                            if (Settings.devices(MonitorService.this).contains(device.getAddress())) {
                                found.add(device.getAddress());
                                carName[0] = device.getName() == null ? device.getAddress() : device.getName();
                            }
                        }
                    }
                } catch (SecurityException error) {
                    Settings.failure(MonitorService.this, "Bluetooth permission was revoked");
                } finally {
                    adapter.closeProfileProxy(profile, proxy);
                    if (--pending[0] == 0) finish.run();
                }
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
        if (!adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET)) pending[0]--;
        if (!adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) pending[0]--;
        if (pending[0] == 0) finish.run();
        else handler.postDelayed(finish, 10_000);
    }
    private void restoreWithoutConnection() {
        TripStore.Trip trip = store.active();
        if (trip == null) {
            stopGps();
            setStatus("Ready · waiting for your car");
        } else if (trip.disconnected > 0) {
            scheduleDeadline(trip);
        } else {
            TripStore.Point last = store.endpoint(trip.id, false);
            finishTrip(trip, last == null ? trip.started : last.time,
                    "Monitoring interrupted; Bluetooth was disconnected when service recovered");
            Settings.failure(this, "A trip was recovered after an interruption. Review its recorded end time.");
        }
    }
    private void connect(String name) {
        TripStore.Trip trip = store.active();
        long now = System.currentTimeMillis();
        if (trip != null && trip.disconnected > 0 && now - trip.disconnected >= Settings.RECONNECT_MS) {
            finishTrip(trip, trip.disconnected, "Car Bluetooth disconnected");
            trip = null;
        }
        if (trip == null) trip = store.start(name, now);
        store.disconnected(trip.id, 0);
        handler.removeCallbacks(deadline);
        getSystemService(AlarmManager.class).cancel(deadlineIntent());
        startGps();
    }
    private void disconnect() {
        TripStore.Trip trip = store.active();
        if (trip == null) return;
        if (trip.disconnected == 0) {
            store.disconnected(trip.id, System.currentTimeMillis());
            trip = store.get(trip.id);
        }
        stopGps();
        scheduleDeadline(trip);
    }
    private PendingIntent deadlineIntent() {
        return PendingIntent.getBroadcast(this, 1, new Intent(this, DeadlineReceiver.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private void scheduleDeadline(TripStore.Trip trip) {
        long remaining = Math.max(0, Settings.RECONNECT_MS - (System.currentTimeMillis() - trip.disconnected));
        if (remaining == 0) { expireDisconnect(); return; }
        handler.removeCallbacks(deadline);
        handler.postDelayed(deadline, remaining);
        getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + remaining, deadlineIntent());
        setStatus("Car disconnected · allowing 60 seconds to reconnect");
    }
    private void expireDisconnect() {
        TripStore.Trip trip = store.active();
        if (trip == null) { stopGps(); setStatus("Ready · waiting for your car"); return; }
        if (trip.disconnected == 0) return;
        if (System.currentTimeMillis() - trip.disconnected < Settings.RECONNECT_MS) { scheduleDeadline(trip); return; }
        finishTrip(trip, trip.disconnected, "Car Bluetooth disconnected");
    }
    private void finishTrip(TripStore.Trip trip, long time, String reason) {
        store.finish(trip.id, time, reason);
        stopGps();
        Addresses.resolve(this, trip.id);
        setStatus("Ready · waiting for your car");
    }
    private void startGps() {
        if (tracking) { setStatus("Recording your trip · GPS every 10 seconds"); return; }
        try {
            foreground(true);
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, Settings.INTERVAL_MS, 0, this, Looper.getMainLooper());
            tracking = true;
            lastFixElapsed = SystemClock.elapsedRealtime();
            handler.postDelayed(health, 15_000);
            setStatus(locations.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    ? "Trip started · waiting for the first accurate GPS fix" : "Trip active · turn on phone location to record GPS");
        } catch (SecurityException error) {
            Log.e("GPSmon", "Location permission revoked", error);
            Settings.failure(this, "Trip started but location permission was revoked.");
            setStatus("Trip active · restore location permission");
        } catch (RuntimeException error) {
            Log.e("GPSmon", "GPS start failed", error);
            Settings.failure(this, "Trip started but GPS access failed. Open GPSmon and check location permissions.");
            setStatus("Trip active · GPS unavailable, check permissions");
        }
    }
    private void stopGps() {
        handler.removeCallbacks(health);
        if (locations != null) locations.removeUpdates(this);
        tracking = false;
        if (Settings.bluetoothGranted(this)) foreground(false);
    }
    @Override public void onLocationChanged(Location location) {
        try {
            TripStore.Trip trip = store.active();
            if (trip == null || trip.disconnected > 0) { stopGps(); return; }
            long age = SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos();
            if (age < 0 || age > 30_000_000_000L) return;
            if (store.add(trip.id, location)) {
                lastFixElapsed = SystemClock.elapsedRealtime();
                setStatus("Recording your trip · GPS every 10 seconds");
            }
        } catch (RuntimeException error) {
            Log.e("GPSmon", "GPS point could not be saved", error);
            Settings.failure(this, "GPS point could not be saved. Check phone storage.");
            setStatus("Recording problem · check phone storage");
        }
    }
    @Override public void onProviderDisabled(String provider) { setStatus("Trip active · phone location is off"); }
    @Override public void onProviderEnabled(String provider) { if (tracking) setStatus("Trip active · waiting for GPS"); }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    private void setStatus(String text) {
        currentStatus = text;
        Settings.status(this, text);
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_car)
                .setContentTitle("GPSmon").setContentText(currentStatus).setStyle(new Notification.BigTextStyle().bigText(currentStatus))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void foreground(boolean gps) {
        if (Build.VERSION.SDK_INT >= 29) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (gps) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTIFICATION, notification(), types);
        } else startForeground(NOTIFICATION, notification());
    }
    private void fail(String text) {
        Settings.failure(this, text);
        stopSelf();
    }
    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (locations != null) locations.removeUpdates(this);
        if (receiverRegistered) unregisterReceiver(events);
        if (!Settings.enabled(this)) {
            TripStore.Trip trip = store.active();
            if (trip != null) store.finish(trip.id, trip.disconnected > 0 ? trip.disconnected : System.currentTimeMillis(), "Monitoring disabled by user");
            getSystemService(AlarmManager.class).cancel(deadlineIntent());
        }
        store.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
