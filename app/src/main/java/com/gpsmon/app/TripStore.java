package com.gpsmon.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
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
        Integer battery;
        Boolean charging;
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
    private final Context context;
    private final boolean applicationDatabase;
    TripStore(Context context) { this(context, "trips.db"); }
    TripStore(Context context, String database) {
        super(context, database, null, 2);
        this.context = context.getApplicationContext();
        applicationDatabase = "trips.db".equals(database);
    }
    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY, started INTEGER NOT NULL, ended INTEGER, "
                + "device TEXT NOT NULL, origin TEXT NOT NULL DEFAULT '', destination TEXT NOT NULL DEFAULT '', "
                + "notes TEXT NOT NULL DEFAULT '', distance REAL NOT NULL DEFAULT 0, reason TEXT NOT NULL DEFAULT '', "
                + "disconnected INTEGER NOT NULL DEFAULT 0, active INTEGER UNIQUE CHECK(active IS NULL OR active=1))");
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY, trip INTEGER NOT NULL REFERENCES trips(id), "
                + "time INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, accuracy REAL NOT NULL, "
                + "altitude REAL NOT NULL, speed REAL NOT NULL, battery INTEGER, charging INTEGER, UNIQUE(trip,time))");
        db.execSQL("CREATE INDEX points_by_trip ON points(trip,time)");
        createUploads(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE points ADD COLUMN battery INTEGER");
            db.execSQL("ALTER TABLE points ADD COLUMN charging INTEGER");
            createUploads(db);
        }
    }
    private static void createUploads(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE uploads (trip INTEGER NOT NULL REFERENCES trips(id), target TEXT NOT NULL, "
                + "last_time INTEGER NOT NULL DEFAULT -1, state TEXT NOT NULL DEFAULT 'Queued', "
                + "problem TEXT NOT NULL DEFAULT '', updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(trip,target))");
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
        if (applicationDatabase) UploadScheduler.enqueue(context, false);
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
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) point.put("battery", Math.min(100, Math.round(level * 100f / scale)));
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (status >= 0) point.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ? 1 : 0);
            }
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
        int battery = c.getColumnIndexOrThrow("battery");
        if (!c.isNull(battery)) point.battery = c.getInt(battery);
        int charging = c.getColumnIndexOrThrow("charging");
        if (!c.isNull(charging)) point.charging = c.getInt(charging) != 0;
        return point;
    }

    static final class Upload {
        long lastTime, updated;
        int sent, total;
        String state, problem;
    }
    synchronized Upload upload(long id, String target) {
        Upload upload = new Upload();
        upload.lastTime = -1;
        upload.state = "Queued";
        upload.problem = "";
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM uploads WHERE trip=? AND target=?", new String[]{Long.toString(id), target})) {
            if (c.moveToFirst()) {
                upload.lastTime = c.getLong(c.getColumnIndexOrThrow("last_time"));
                upload.state = c.getString(c.getColumnIndexOrThrow("state"));
                upload.problem = c.getString(c.getColumnIndexOrThrow("problem"));
                upload.updated = c.getLong(c.getColumnIndexOrThrow("updated"));
            }
        }
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(CASE WHEN time<=? THEN 1 ELSE 0 END),0) FROM points WHERE trip=?",
                new String[]{Long.toString(upload.lastTime), Long.toString(id)})) {
            c.moveToFirst();
            upload.total = c.getInt(0);
            upload.sent = c.getInt(1);
        }
        return upload;
    }
    synchronized Trip nextUpload(String target) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT t.* FROM trips t LEFT JOIN uploads u ON u.trip=t.id AND u.target=? "
                + "WHERE t.ended IS NOT NULL AND EXISTS(SELECT 1 FROM points p WHERE p.trip=t.id AND p.time>COALESCE(u.last_time,-1)) "
                + "ORDER BY t.started,t.id LIMIT 1", new String[]{target})) {
            return c.moveToFirst() ? readTrip(c) : null;
        }
    }
    synchronized List<Point> pendingPoints(long id, long after) {
        List<Point> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM points WHERE trip=? AND time>? ORDER BY time LIMIT 128",
                new String[]{Long.toString(id), Long.toString(after)})) {
            while (c.moveToNext()) result.add(readPoint(c));
        }
        return result;
    }
    synchronized void uploadProgress(long id, String target, long lastTime, String state, String problem) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues initial = new ContentValues();
            initial.put("trip", id);
            initial.put("target", target);
            db.insertWithOnConflict("uploads", null, initial, SQLiteDatabase.CONFLICT_IGNORE);
            // Never rewind an acknowledged point during cancellation or a repeated worker callback.
            db.execSQL("UPDATE uploads SET last_time=MAX(last_time,?),state=?,problem=?,updated=? WHERE trip=? AND target=?",
                    new Object[]{lastTime, state, problem, System.currentTimeMillis(), id, target});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
