package com.gpsmon.app;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Addresses {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private Addresses() {}
    static void resolve(Context context, long tripId) {
        Context app = context.getApplicationContext();
        WORKER.execute(() -> {
            if (!Geocoder.isPresent()) return;
            try (TripStore store = new TripStore(app)) {
                TripStore.Trip trip = store.get(tripId);
                if (trip == null) return;
                Geocoder geocoder = new Geocoder(app, Locale.getDefault());
                if (trip.origin.isEmpty()) resolveEndpoint(store, geocoder, tripId, true);
                if (!trip.active() && trip.destination.isEmpty()) resolveEndpoint(store, geocoder, tripId, false);
            } catch (Exception error) {
                android.util.Log.w("GPSmon", "Address lookup deferred. Coordinates retained.", error);
            }
        });
    }
    @SuppressWarnings("deprecation")
    private static void resolveEndpoint(TripStore store, Geocoder geocoder, long tripId, boolean first) throws Exception {
        TripStore.Point point = store.endpoint(tripId, first);
        if (point == null) return;
        List<Address> results = geocoder.getFromLocation(point.latitude, point.longitude, 1);
        if (results != null && !results.isEmpty()) {
            String line = results.get(0).getAddressLine(0);
            if (line != null && !line.isBlank()) store.address(tripId, first, line);
        }
    }
    static String label(String address, TripStore.Point point) {
        if (!address.isEmpty()) return address;
        if (point == null) return "Location unavailable";
        return String.format(Locale.getDefault(), "%.5f, %.5f", point.latitude, point.longitude);
    }
}
