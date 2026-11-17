package com.gpsmon.app;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.HashSet;
import java.util.Set;

final class Settings {
    static final long INTERVAL_MS = 10_000;
    static final long RECONNECT_MS = 60_000;
    private Settings() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("monitor", Context.MODE_PRIVATE);
    }
    static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    static void enabled(Context context, boolean value) {
        preferences(context).edit().putBoolean("enabled", value).commit();
    }
    static Set<String> devices(Context context) {
        return new HashSet<>(preferences(context).getStringSet("devices", new HashSet<>()));
    }
    static void select(Context context, String address, boolean selected) {
        Set<String> addresses = devices(context);
        if (selected) addresses.add(address); else addresses.remove(address);
        preferences(context).edit().putStringSet("devices", addresses).commit();
    }
    static boolean bluetoothGranted(Context context) {
        return Build.VERSION.SDK_INT < 31 || granted(context, Manifest.permission.BLUETOOTH_CONNECT);
    }
    static boolean granted(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
    static boolean locationGranted(Context context) {
        return granted(context, Manifest.permission.ACCESS_FINE_LOCATION);
    }
    static boolean backgroundGranted(Context context) {
        return Build.VERSION.SDK_INT < 29 || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }
    static boolean ready(Context context) {
        return bluetoothGranted(context) && locationGranted(context) && backgroundGranted(context)
                && !devices(context).isEmpty();
    }
    static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }
    static void status(Context context, String text) {
        preferences(context).edit().putString("status", text).putLong("status_time", System.currentTimeMillis()).apply();
    }
    static void failure(Context context, String text) {
        preferences(context).edit().putString("last_problem", text)
                .putLong("problem_time", System.currentTimeMillis()).commit();
        status(context, text);
    }
}
