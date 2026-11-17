package com.gpsmon.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import java.util.ArrayList;
import java.util.List;

/** Each point and its distance update commit in one SQLite transaction. */
final class TripStore extends SQLiteOpenHelper {
    static final class Trip {
        long id, started, ended, disconnected;
        String device, origin, destination, notes, reason;
        double distance;
        boolean active() { return ended == 0; }
        long duration() { return Math.max(0, (ended == 0 ? System.currentTimeMillis() : ended) - started); }
    }
    static final class Point {
        long time;
        double latitude, longitude, altitude;
        float accuracy, speed;
        Location location() {
            Location location = new Location("gps");
            location.setTime(time);
            location.setLatitude(latitude);
            location.setLongitude(longitude);
            location.setAccuracy(accuracy);
            location.setAltitude(altitude);
            location.setSpeed(speed);
            return location;
        }
    }
    TripStore(Context context) { this(context, "trips.db"); }
    TripStore(Context context, String database) { super(context, database, null, 1); }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, "
                + "device TEXT NOT NULL, origin TEXT NOT NULL DEFAULT '', destination TEXT NOT NULL DEFAULT '', "
                + "notes TEXT NOT NULL DEFAULT '', distance REAL NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', "
                + "disconnected INTEGER NOT NULL DEFAULT 0, active INTEGER UNIQUE CHECK(active IS NULL OR active=1))");
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY, trip INTEGER NOT NULL REFERENCES trips(id), "
                + "time INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL, "
                + "altitude REAL NOT NULL, speed REAL NOT NULL, UNIQUE(trip,time))");
        db.execSQL("CREATE INDEX points_by_trip ON points(trip,time)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Missing database migration");
    }
    synchronized Trip active() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM trips WHERE active=1", null)) {
            return cursor.moveToFirst() ? readTrip(cursor) : null;
        }
    }
    synchronized Trip get(long id) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM trips WHERE id=?", new String[]{Long.toString(id)})) {
            return cursor.moveToFirst() ? readTrip(cursor) : null;
        }
    }
    synchronized List<Trip> trips() {
        List<Trip> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM trips ORDER BY started DESC,id DESC", null)) {
            while (cursor.moveToNext()) result.add(readTrip(cursor));
        }
        return result;
    }
    synchronized Trip start(String device, long time) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Trip current = active();
            if (current == null) {
                ContentValues values = new ContentValues();
                values.put("started", time);
                values.put("device", device);
                values.put("active", 1);
                long id = db.insertOrThrow("trips", null, values);
                current = get(id);
            }
            db.setTransactionSuccessful();
            return current;
        } finally { db.endTransaction(); }
    }
    synchronized void disconnected(long id, long time) {
        ContentValues values = new ContentValues();
        values.put("disconnected", time);
        getWritableDatabase().update("trips", values, "id=? AND active=1", new String[]{Long.toString(id)});
    }
    synchronized void finish(long id, long time, String reason) {
        Trip trip = get(id);
        if (trip == null || !trip.active()) return;
        ContentValues values = new ContentValues();
        values.put("ended", Math.max(trip.started, time));
        values.putNull("active");
        values.put("reason", reason);
        values.put("disconnected", 0);
        getWritableDatabase().update("trips", values, "id=? AND active=1", new String[]{Long.toString(id)});
    }
    synchronized boolean add(long id, Location location) {
        if (!valid(location)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Trip trip = get(id);
            if (trip == null || !trip.active() || trip.disconnected != 0 || location.getTime() < trip.started) return false;
            Point previous = endpoint(id, false);
            double increment = 0;
            if (previous != null) {
                long interval = location.getTime() - previous.time;
                if (interval < Settings.INTERVAL_MS) return false;
                increment = previous.location().distanceTo(location);
                // GPSLogger-inspired jump rejection, tightened for road journeys.
                if (increment / (interval / 1000d) > 100) return false;
                // Stationary GPS noise should not continuously inflate a parked trip.
                if (increment < Math.max(5, Math.min(previous.accuracy, location.getAccuracy()) / 2)) increment = 0;
            }
            ContentValues point = new ContentValues();
            point.put("trip", id);
            point.put("time", location.getTime());
            point.put("latitude", location.getLatitude());
            point.put("longitude", location.getLongitude());
            point.put("accuracy", location.getAccuracy());
            point.put("altitude", location.hasAltitude() ? location.getAltitude() : 0);
            point.put("speed", location.hasSpeed() ? location.getSpeed() : 0);
            db.insertOrThrow("points", null, point);
            ContentValues update = new ContentValues();
            update.put("distance", trip.distance + increment);
            db.update("trips", update, "id=?", new String[]{Long.toString(id)});
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }
    static boolean valid(Location location) {
        return location != null && location.hasAccuracy() && Float.isFinite(location.getAccuracy())
                && location.getAccuracy() > 0 && location.getAccuracy() <= 75
                && Double.isFinite(location.getLatitude()) && Math.abs(location.getLatitude()) <= 90
                && Double.isFinite(location.getLongitude()) && Math.abs(location.getLongitude()) <= 180;
    }
    synchronized Point endpoint(long id, boolean first) {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM points WHERE trip=? ORDER BY time "
                + (first ? "ASC" : "DESC") + " LIMIT 1", new String[]{Long.toString(id)})) {
            return cursor.moveToFirst() ? readPoint(cursor) : null;
        }
    }
    synchronized List<Point> points(long id) {
        List<Point> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT * FROM points WHERE trip=? ORDER BY time", new String[]{Long.toString(id)})) {
            while (cursor.moveToNext()) result.add(readPoint(cursor));
        }
        return result;
    }
    synchronized void notes(long id, String notes) {
        ContentValues values = new ContentValues();
        values.put("notes", notes);
        getWritableDatabase().update("trips", values, "id=?", new String[]{Long.toString(id)});
    }
    synchronized void address(long id, boolean origin, String address) {
        ContentValues values = new ContentValues();
        values.put(origin ? "origin" : "destination", address);
        getWritableDatabase().update("trips", values, "id=?", new String[]{Long.toString(id)});
    }
    private static Trip readTrip(Cursor c) {
        Trip trip = new Trip();
        trip.id = c.getLong(c.getColumnIndexOrThrow("id"));
        trip.started = c.getLong(c.getColumnIndexOrThrow("started"));
        trip.ended = c.getLong(c.getColumnIndexOrThrow("ended"));
        trip.disconnected = c.getLong(c.getColumnIndexOrThrow("disconnected"));
        trip.device = c.getString(c.getColumnIndexOrThrow("device"));
        trip.origin = c.getString(c.getColumnIndexOrThrow("origin"));
        trip.destination = c.getString(c.getColumnIndexOrThrow("destination"));
        trip.notes = c.getString(c.getColumnIndexOrThrow("notes"));
        trip.reason = c.getString(c.getColumnIndexOrThrow("reason"));
        trip.distance = c.getDouble(c.getColumnIndexOrThrow("distance"));
        return trip;
    }
    private static Point readPoint(Cursor c) {
        Point point = new Point();
        point.time = c.getLong(c.getColumnIndexOrThrow("time"));
        point.latitude = c.getDouble(c.getColumnIndexOrThrow("latitude"));
        point.longitude = c.getDouble(c.getColumnIndexOrThrow("longitude"));
        point.accuracy = c.getFloat(c.getColumnIndexOrThrow("accuracy"));
        point.altitude = c.getDouble(c.getColumnIndexOrThrow("altitude"));
        point.speed = c.getFloat(c.getColumnIndexOrThrow("speed"));
        return point;
    }
}
