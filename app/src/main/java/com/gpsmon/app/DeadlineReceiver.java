package com.gpsmon.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Private persisted deadline can finish a disconnected trip after process loss. */
public final class DeadlineReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        try (TripStore store = new TripStore(context)) {
            TripStore.Trip trip = store.active();
            if (trip != null && trip.disconnected > 0
                    && System.currentTimeMillis() - trip.disconnected >= Settings.RECONNECT_MS) {
                store.finish(trip.id, trip.disconnected, "Car Bluetooth disconnected");
                Addresses.resolve(context, trip.id);
            }
        }
    }
}
