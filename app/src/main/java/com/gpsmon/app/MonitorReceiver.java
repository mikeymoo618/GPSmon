package com.gpsmon.app;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Bluetooth ACL and boot broadcasts are Android background-launch exemptions. */
public final class MonitorReceiver extends BroadcastReceiver {
    static final String DEVICE_ADDRESS = "com.gpsmon.app.DEVICE_ADDRESS";
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))
            UploadScheduler.enqueue(context, false);
        if (!Settings.enabled(context)) return;
        String action = intent.getAction();
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                && !BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        Intent service = new Intent(context, MonitorService.class).setAction(action);
        BluetoothDevice device = device(intent);
        try {
            if (device != null) {
                if (!Settings.bluetoothGranted(context) || !Settings.devices(context).contains(device.getAddress())) return;
                service.putExtra(DEVICE_ADDRESS, device.getAddress());
            }
            service.putExtra(BluetoothAdapter.EXTRA_STATE,
                    intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
            context.startForegroundService(service);
        } catch (RuntimeException error) {
            Settings.failure(context, "Android blocked automatic monitoring. Open GPSmon to restore it.");
            Log.e("GPSmon", "Automatic service start failed", error);
        }
    }
    @SuppressWarnings("deprecation")
    static BluetoothDevice device(Intent intent) {
        return android.os.Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }
}
