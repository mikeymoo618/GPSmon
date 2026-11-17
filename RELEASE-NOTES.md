# GPSmon 1.1.1

Build 6 removes server-specific defaults and supports Android 9 through Android 16.

- The Reitti server URL starts blank. Enter your own JSON ingestion URL and integration token before enabling uploads.
- Completed local history is queued when the connection is enabled. Uploads use an available internet connection and Android's network-job scheduling.
- Each point uses JSON, its original UTC timestamp, coordinates, accuracy, altitude and speed. New points also capture recording-time battery and charging state.
- Durable point checkpoints resume interrupted trips. Lost-response retries retain timestamps for Reitti's duplicate detection. Connection errors and recoverable server failures use retry backoff.
- Trip cards and details show upload progress and errors. Retry upload and Retry uploads now request another attempt.
- Tokens are encrypted with Android Keystore. User-entered HTTP and HTTPS endpoints are supported. HTTPS uses normal certificate verification. Redirects are rejected.
- Existing trips, notes, car selections, privately saved connection settings and upload checkpoints are retained. Install this signed APK over your existing GPSmon installation.

HTTP connections send coordinates and token without transport encryption. Local trips and notes are retained after upload. Reitti reconstructs its journeys from the uploaded points.

Automated verification uses dedicated Android 9/API 28 and Android 16/API 36 emulators with local HTTP fixtures, synthetic Bluetooth events and injected GPS. An authenticated upload to your Reitti instance requires entering your token on the phone. Physical-car Bluetooth and phone battery measurements remain pending.
