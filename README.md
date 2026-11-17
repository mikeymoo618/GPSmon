# GPSmon

Automatic Bluetooth-triggered Android trip logging. Requires Android 9 or newer. Version 1.1.0, build 5, package com.gpsmon.app, compile/target SDK 36.

## Setup

1. Pair your car in Android Bluetooth settings.
2. Open GPSmon and grant Bluetooth and precise-location permissions.
3. On Android 10+, allow background location. On Android 11+, choose Permissions → Location → Allow all the time in GPSmon's Android app settings.
4. Select your paired car, enable monitoring notifications and allow unrestricted battery use.
5. Enable automatic monitoring. Keep Bluetooth and phone location switched on.

GPS stays off while waiting. Connecting a selected car starts a trip and requests GPS every ten seconds. A disconnection pauses sampling and allows sixty seconds to reconnect. Arrival time uses the disconnection timestamp. Trip details show endpoint addresses or coordinates, distance, duration, first/last fix accuracy, editable notes and GPX export. Address lookups can be retried after connectivity returns.

Use the monitoring notification and home-screen readiness to check operation. Android force-stop, disabled permissions, disabled Bluetooth/location and manufacturer battery restrictions can prevent automatic recording. Reopen GPSmon after force-stop. Device shutdown can leave an interrupted trip for recovery.

## Privacy and storage

Coordinates and notes are stored in the app's private SQLite database. Android's address lookup provider may receive endpoint coordinates. Enabled Reitti uploads send recorded location points to your configured server. Exported GPX goes to the destination you choose. Uninstalling removes private trips. Export important trips before uninstalling.

## Reitti uploads

Open Reitti upload settings from the Trips section. The default endpoint is http://server.example.invalid:8092/api/v1/ingest/gpslogger. Enter a GPSLogger integration token from Reitti Settings → Integrations, with its device assignment, enable automatic uploads and save. Enabling uploads queues all completed local trips. Each trip starts uploading after it ends, using an available internet connection. Android can defer background jobs.

Points are posted chronologically with their original recording timestamps. Successful points are checkpointed privately. Interrupted requests are retried with the same timestamp so Reitti can deduplicate. Reitti reconstructs its journeys from the points. GPSmon's addresses and editable notes are available locally and in GPX exports.

Trip cards and details show sent/total point counts, queued/paused/uploaded status and errors. Retry upload or Retry uploads now requests another attempt. Disabling uploads retains pending data. Changing the endpoint or token starts a separate upload destination and queues completed history for that destination. Saving the same endpoint and token preserves acknowledged points. Leave the token field blank to retain the saved secret.

The token is encrypted using Android Keystore and excluded from Android backup. The supplied HTTP URL sends coordinates and token without transport encryption. Android allows cleartext for server.example.invalid only. HTTPS endpoints use normal certificate validation. Redirects are rejected. Tokens are sent in X-API-TOKEN and are excluded from application diagnostics. New points include recording-time battery and charging state. Upgraded historical points omit unavailable battery data.

## Build and signing

Use JDK 17+, Android SDK 36/build tools 36.0.0 and a private local.properties with sdk.dir. Run `./build.ps1` with cached dependencies or `./build.ps1 -Online` for initial downloads. The build helper creates a dedicated private `.local-signing/gpsmon.p12` and signing.properties once. Back up both securely and preserve them for upgrades. Signed APKs and checksums go to artifacts. Install signed upgrades over the existing app to retain trips and settings.

## References and licence

GPSLogger by mendhak supplies the reference design for foreground GPS collection, point filtering and GPX tracks. The inspected GPSloggerV2 source commit is f2cca9487363ab3c3b8e77d44185c11016abad7f. ScreenOhRecord supplies the Java/Gradle, signing, permission and release-workflow patterns. GPSmon uses a focused implementation of the approved automatic-trip workflow. Distributed under GPL version 2 or later. See LICENSE.md and DESIGN.md.

Repository https://github.com/mikeymoo618/GPSmon
