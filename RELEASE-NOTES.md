# GPSmon 1.1.0

Step 2 adds optional Reitti uploads after each completed trip. Build 5 supports Android 9 through Android 16.

- Open Reitti upload settings, enter your integration token, enable uploads and save. The default URL is http://server.example.invalid:8092/api/v1/ingest/gpslogger.
- Completed local history is queued when the connection is enabled. Uploads use an available internet connection and Android's network-job scheduling.
- Each point uses JSON, its original UTC timestamp, coordinates, accuracy, altitude and speed. New points also capture recording-time battery and charging state.
- Durable point checkpoints resume interrupted trips. Lost-response retries retain timestamps for Reitti's duplicate detection. Connection errors and recoverable server failures use retry backoff.
- Trip cards and details show upload progress and errors. Retry upload and Retry uploads now request another attempt.
- Tokens are encrypted with Android Keystore. HTTP is scoped to server.example.invalid, and HTTPS endpoints use normal certificate verification. Redirects are rejected.
- Existing trips, notes, car selections and GPS points migrate in place. Install this signed APK over GPSmon 1.0.0 to preserve data.

The supplied HTTP endpoint sends coordinates and token without transport encryption. Enable HTTPS when your server provides it. Local trips and notes are retained after upload. Reitti reconstructs its journeys from the uploaded points.

Automated verification uses dedicated Android 9/API 28 and Android 16/API 36 emulators with local HTTP fixtures, synthetic Bluetooth events and injected GPS. An authenticated upload to your Reitti instance requires entering your token on the phone. Physical-car Bluetooth and phone battery measurements remain pending.
