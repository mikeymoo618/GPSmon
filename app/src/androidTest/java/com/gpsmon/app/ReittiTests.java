package com.gpsmon.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ReittiTests {
    private final Instrumentation instrumentation;
    private final Context app;
    private final Consumer<String> passed;
    ReittiTests(Instrumentation instrumentation, Consumer<String> passed) {
        this.instrumentation = instrumentation;
        this.app = instrumentation.getTargetContext();
        this.passed = passed;
    }
    void run() throws Exception {
        settings();
        queue();
        job();
    }
    private void settings() throws Exception {
        check(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("server.example.invalid"), "Approved HTTP host allowed");
        check(!android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("example.com"), "Other HTTP hosts blocked");
        ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "synthetic-private-token", false);
        String stored = ReittiSettings.preferences(app).getString("token", "");
        check(!stored.contains("synthetic-private-token"), "Token encrypted at rest");
        check(ReittiSettings.load(app).token.equals("synthetic-private-token"), "Keystore decrypt round-trip");
        String target = ReittiSettings.target(app);
        ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "", false);
        check(ReittiSettings.target(app).equals(target), "Unchanged destination preserves checkpoints");
        ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "replacement-synthetic-token", false);
        check(!ReittiSettings.target(app).equals(target), "Changed token scopes new checkpoints");
        boolean rejected = false;
        try { new ReittiSettings.Config("http://example.com/api", "token", "x", true); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Unapproved HTTP host rejected");
        rejected = false;
        try { new ReittiSettings.Config(ReittiSettings.DEFAULT_URL, "token\r\nInjected: x", "x", true); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Header injection rejected");
        rejected = false;
        try { new ReittiSettings.Config(ReittiSettings.DEFAULT_URL, "token\u0100", "x", true); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "Non-ASCII header value rejected before HTTP library diagnostics");
        ReittiSettings.preferences(app).edit().putString("token", "unreadable").commit();
        ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "recovered-synthetic-token", false);
        check(ReittiSettings.load(app).token.equals("recovered-synthetic-token"), "Invalid secret can be replaced");
        passed.accept("Reitti token encryption, destination scope and host restrictions");
    }
    private void queue() throws Exception {
        String database = "reitti-queue-" + System.currentTimeMillis() + ".db";
        ReittiSettings.Config config = new ReittiSettings.Config(ReittiSettings.DEFAULT_URL, "fixture-token", "fixture-target", true);
        long start = System.currentTimeMillis() - 100_000;
        long id;
        try (TripStore store = new TripStore(app, database)) {
            id = store.start("Upload car", start).id;
            store.add(id, point(start, -36.85));
            store.add(id, point(start + 10_000, -36.849));
            store.add(id, point(start + 20_000, -36.848));
            check(store.nextUpload(config.target) == null, "Active trip excluded from uploads");
            store.finish(id, start + 25_000, "Fixture complete");
            List<TripStore.Point> points = store.points(id);
            points.get(0).battery = 72;
            points.get(0).charging = true;
            JSONObject payload = ReittiClient.payload(points.get(0));
            check(payload.getLong("tst") == start / 1000, "Original epoch seconds sent");
            check(Math.abs(payload.getDouble("vel") - 36) < 0.01, "Speed converted from m/s to km/h");
            check(payload.getInt("batt") == 72 && payload.getBoolean("bs"), "Battery and charging typed correctly");
            check(store.points(id).get(0).battery != null, "New GPS point captures battery level");
            try (Fixture fixture = new Fixture(200, 503)) {
                ReittiClient client = new ReittiClient(url -> (HttpURLConnection) url.openConnection(fixture.proxy()));
                check(new UploadEngine(client).drain(store, config, () -> false), "Transient error requests retry");
                check(store.upload(id, config.target).sent == 1, "First accepted point checkpointed");
                check(fixture.payloads.size() == 2, "Stopped on failed point");
                check(fixture.headers.get(0).contains("x-api-token: fixture-token"), "Token sent only in header");
                check(fixture.payloads.get(0).getLong("tst") == start / 1000, "HTTP body carries original time");
            }
            passed.accept("JSON fields, real HTTP POST and partial upload checkpoint");
        }
        try (TripStore store = new TripStore(app, database); Fixture fixture = new Fixture(200, 200)) {
            ReittiClient client = new ReittiClient(url -> (HttpURLConnection) url.openConnection(fixture.proxy()));
            check(!new UploadEngine(client).drain(store, config, () -> false), "Reopened queue drains successfully");
            check(store.upload(id, config.target).sent == 3, "All points acknowledged");
            check(fixture.payloads.size() == 2 && fixture.payloads.get(0).getLong("tst") == (start + 10_000) / 1000, "Resume begins at failed point");
            check(store.points(id).size() == 3, "Local GPS points retained");
            passed.accept("interrupted upload resumes after database reopen and retains local trip");
            ReittiSettings.Config auth = new ReittiSettings.Config(config.endpoint, config.token, "auth-target", true);
            try (Fixture denied = new Fixture(401)) {
                ReittiClient rejected = new ReittiClient(url -> (HttpURLConnection) url.openConnection(denied.proxy()));
                check(!new UploadEngine(rejected).drain(store, auth, () -> false), "Invalid token stops automatic retry loop");
                check(store.upload(id, auth.target).sent == 0 && store.upload(id, auth.target).state.equals("Needs attention"), "Authentication failure visible and unacknowledged");
                check(!store.upload(id, auth.target).problem.contains(config.token), "Token absent from error status");
            }
            try (Fixture redirected = new Fixture(302)) {
                ReittiClient clientRedirect = new ReittiClient(url -> (HttpURLConnection) url.openConnection(redirected.proxy()));
                new UploadEngine(clientRedirect).drain(store, auth, () -> false);
                check(redirected.payloads.size() == 1 && store.upload(id, auth.target).sent == 0, "Redirect not followed with credentials");
            }
            passed.accept("authentication failure, safe diagnostics and redirect rejection");
            ReittiSettings.Config lost = new ReittiSettings.Config(config.endpoint, config.token, "lost-reply", true);
            try (Fixture dropped = new Fixture(-1)) {
                ReittiClient clientLost = new ReittiClient(url -> (HttpURLConnection) url.openConnection(dropped.proxy()));
                check(new UploadEngine(clientLost).drain(store, lost, () -> false), "Lost response remains retryable");
                check(store.upload(id, lost.target).sent == 0, "Unconfirmed response is not acknowledged");
            }
            try (Fixture replay = new Fixture(200, 200, 200)) {
                ReittiClient clientReplay = new ReittiClient(url -> (HttpURLConnection) url.openConnection(replay.proxy()));
                new UploadEngine(clientReplay).drain(store, lost, () -> false);
                check(replay.payloads.get(0).getLong("tst") == start / 1000, "Replay timestamp remains stable for Reitti deduplication");
            }
            passed.accept("lost-response replay preserves timestamps for duplicate detection");
            AtomicBoolean cancelled = new AtomicBoolean(true);
            try (Fixture paused = new Fixture(200)) {
                ReittiSettings.Config stop = new ReittiSettings.Config(config.endpoint, config.token, "cancelled", true);
                ReittiClient pausedClient = new ReittiClient(url -> (HttpURLConnection) url.openConnection(paused.proxy()));
                check(new UploadEngine(pausedClient).drain(store, stop, cancelled::get), "Cancelled queue remains retryable");
                check(paused.payloads.isEmpty(), "No network call after cancellation");
            }
            passed.accept("cancellation prevents further upload requests");
        }
    }
    private void job() throws Exception {
        ProxySelector original = ProxySelector.getDefault();
        try (Fixture fixture = new Fixture()) {
            ProxySelector.setDefault(new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    return "server.example.invalid".equals(uri.getHost()) ? Collections.singletonList(fixture.proxy())
                            : original == null ? Collections.singletonList(Proxy.NO_PROXY) : original.select(uri);
                }
                @Override public void connectFailed(URI uri, java.net.SocketAddress address, IOException error) { }
            });
            ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "scheduled-fixture-token", true);
            String target = ReittiSettings.target(app);
            UploadScheduler.enqueue(app, true);
            try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand("cmd jobscheduler run -f com.gpsmon.app 450");
                 InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                byte[] buffer = new byte[256];
                while (input.read(buffer) != -1) { }
            }
            long until = SystemClock.elapsedRealtime() + 40_000;
            try (TripStore store = new TripStore(app)) {
                while (store.nextUpload(target) != null) {
                    if (SystemClock.elapsedRealtime() > until) throw new AssertionError("Android upload job did not drain its persisted queue");
                    SystemClock.sleep(100);
                }
            }
            check(!fixture.payloads.isEmpty(), "Real Android JobService sent HTTP requests");
            passed.accept("persisted Android network job drains completed trips through real HTTP");
        } finally {
            ReittiSettings.save(app, ReittiSettings.DEFAULT_URL, "", false);
            UploadScheduler.enqueue(app, false);
            long until = SystemClock.elapsedRealtime() + 25_000;
            while (UploadJob.running() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
            ProxySelector.setDefault(original);
        }
    }
    private static android.location.Location point(long time, double latitude) {
        android.location.Location point = new android.location.Location("gps");
        point.setTime(time);
        point.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        point.setLatitude(latitude);
        point.setLongitude(174.76);
        point.setAccuracy(5);
        point.setSpeed(10);
        return point;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    /** Loopback HTTP proxy ensures fixture data never reaches the public Reitti server. */
    private static final class Fixture implements AutoCloseable {
        final List<JSONObject> payloads = new CopyOnWriteArrayList<>();
        final List<String> headers = new CopyOnWriteArrayList<>();
        private final ServerSocket server = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        private final Thread thread;
        private volatile boolean stopped;
        private final int[] responses;
        Fixture(int... responses) throws IOException {
            this.responses = responses;
            server.setSoTimeout(1000);
            thread = new Thread(this::serve, "Reitti-fixture");
            thread.start();
        }
        Proxy proxy() { return new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", server.getLocalPort())); }
        private void serve() {
            while (!stopped) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    InputStream input = new BufferedInputStream(socket.getInputStream());
                    StringBuilder head = new StringBuilder();
                    int length = 0;
                    String line;
                    while (!(line = line(input)).isEmpty()) {
                        head.append(line.toLowerCase(Locale.ROOT)).append('\n');
                        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    }
                    if (length <= 0 || length > 8192) throw new IOException("Unexpected request size");
                    byte[] body = new byte[length];
                    int offset = 0;
                    while (offset < length) {
                        int read = input.read(body, offset, length - offset);
                        if (read < 0) throw new IOException("Incomplete request");
                        offset += read;
                    }
                    headers.add(head.toString());
                    payloads.add(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                    int index = payloads.size() - 1;
                    int response = responses.length == 0 ? 200 : responses[Math.min(index, responses.length - 1)];
                    if (response < 0) continue;
                    socket.getOutputStream().write(("HTTP/1.1 " + response + " Fixture\r\nContent-Length: 0\r\nConnection: close\r\nLocation: https://example.com/redirect\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (java.net.SocketTimeoutException expected) {
                    // Check cancellation between accepts.
                } catch (Exception error) {
                    if (!stopped) android.util.Log.e("GPSmonTest", "Loopback fixture failed", error);
                }
            }
        }
        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) >= 0 && next != '\n') {
                if (next != '\r') bytes.write(next);
                if (bytes.size() > 8192) throw new IOException("Header too long");
            }
            if (next < 0) throw new IOException("Missing request headers");
            return bytes.toString(StandardCharsets.US_ASCII.name());
        }
        @Override public void close() throws Exception { stopped = true; server.close(); thread.join(6000); }
    }
}
