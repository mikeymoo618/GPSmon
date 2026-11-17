# GPSmon

Automatic Bluetooth-triggered Android trip logging. Requires Android 9 or newer. Version 1.0.0, build 2, package com.gpsmon.app, compile/target SDK 36.

## Setup

1. Pair your car in Android Bluetooth settings.
2. Open GPSmon and grant Bluetooth and precise-location permissions.
3. On Android 10+, allow background location. On Android 11+, choose Permissions → Location → Allow all the time in GPSmon's Android app settings.
4. Select your paired car, enable monitoring notifications and allow unrestricted battery use.
5. Enable automatic monitoring. Keep Bluetooth and phone location switched on.

GPS stays off while waiting. Connecting a selected car starts a trip and requests GPS every ten seconds. A disconnection pauses sampling and allows sixty seconds to reconnect. Arrival time uses the disconnection timestamp. Trip details show endpoint addresses or coordinates, distance, duration, first/last fix accuracy, editable notes and GPX export. Address lookups can be retried after connectivity returns.

Use the monitoring notification and home-screen readiness to check operation. Android force-stop, disabled permissions, disabled Bluetooth/location and manufacturer battery restrictions can prevent automatic recording. Reopen GPSmon after force-stop. Device shutdown can leave an interrupted trip for recovery.

## Privacy and storage

Coordinates and notes stay in the app's private SQLite database. Android's address lookup provider may receive endpoint coordinates. Exported GPX goes to the destination you choose. Uninstalling removes private trips. Export important trips before uninstalling. Reitti integration is planned for Step 2.

## Build and signing

Use JDK 17+, Android SDK 36/build tools 36.0.0 and a private local.properties with sdk.dir. Run `./build.ps1` with cached dependencies or `./build.ps1 -Online` for initial downloads. The build helper creates a dedicated private `.local-signing/gpsmon.p12` and signing.properties once. Back up both securely and preserve them for upgrades. Signed APKs and checksums go to artifacts. Install signed upgrades over the existing app to retain trips and settings.

## References and licence

GPSLogger by mendhak supplies the reference design for foreground GPS collection, point filtering and GPX tracks. The inspected GPSloggerV2 source commit is f2cca9487363ab3c3b8e77d44185c11016abad7f. ScreenOhRecord supplies the Java/Gradle, signing, permission and release-workflow patterns. GPSmon uses a focused implementation of the approved automatic-trip workflow. Distributed under GPL version 2 or later. See LICENSE.md and DESIGN.md.

Repository https://github.com/mikeymoo618/GPSmon
