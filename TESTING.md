# GPSmon 1.0.0/build 2 verification

## Release identity

- Package com.gpsmon.app, minimum SDK 28, compile/target SDK 36.
- Signed APK artifacts/GPSmon-1.0.0.apk, 65,072 bytes.
- APK SHA-256 7aa7c3952aeb1281773ffd75b4de6cdd0ecc674da04dd50bbffef9266c9350fa.
- Dedicated GPSmon certificate SHA-256 3d58791e1474b86f361d248ea1598cb43bbfdbdedf93595a311fae3dc17a04d8. APK v2 signature verification passed. Private signing material remains under ignored .local-signing.
- Build and lint passed with zero errors and nine warnings in artifacts/build-1.0.0-final.log and app/build/reports/lint-results-release.txt. Warnings concern deliberate synchronous settings persistence, battery-exemption policy guidance and English-only interface strings. All warnings are retained.

## Runtime coverage

Verified on 2026-11-17 using dedicated GPSmon36/API 36 and GPSmon28/API 28 x86-64 emulators. The same signed release APK is used on both. Emulator system images and runtime are reused read-only from ScreenOhRecord. GPSmon AVD data is isolated under .testing. Physical devices are untouched.

Android 16 passed twelve groups in artifacts/api36-final1-instrumentation.log and repeated them in artifacts/api36-boot-final-instrumentation.log. Android 9 passed the same twelve groups in artifacts/api28-final3-instrumentation.log and artifacts/api28-boot-final-instrumentation.log.

The suite covers atomic trip creation, duplicate connections, ten-second sampling, stale/duplicate and inaccurate point rejection, invalid coordinates, implausible jumps, distance calculation, stationary noise, disconnect/reconnect persistence, endpoint/notes storage, duration, XML-safe GPX export and database reopening. Actual Android foreground service and location-provider callbacks record injected GPS fixes. Short reconnects preserve one trip. The real sixty-second deadline closes at the stored disconnection timestamp. Subsequent connections create new trips. Service interruption preserves data and reconciles an inactive trip. UI coverage launches the main and detail screens, saves notes through actual controls and navigates back.

Signed 0.9.0/build 1 fixtures upgrade in place to 1.0.0/build 2. Both platform runs verify retained trip notes, endpoint addresses, GPS points and selected devices. Logs use each run's -upgrade-seed.log and -upgrade-verify.log suffixes. Fixtures are local test artifacts and are excluded from publication.

Normal launcher screens are also opened outside instrumentation, their UI hierarchies checked and screenshots visually inspected. artifacts/api36-final1.png and artifacts/api28-final2.png show the readiness screen. Package, service, location, full logcat and emulator logs are retained beside each run. Test-owned emulators are shut down after each run.

Both boot runs enable monitoring through the normal launcher, reboot the actual emulator and confirm the boot receiver restores a foreground MonitorService. artifacts/api36-boot-final-boot-services.txt records the BOOT_COMPLETED start and connected-device-only foreground type 0x10. artifacts/api28-boot-final-boot-services.txt records the same boot-restored foreground service on Android 9. Both corresponding -boot-idle-location.txt snapshots contain no GPSmon location listener. Enabled preferences survive both reboots, confirmed by the -boot-verify.log files.

## Attempts retained

- Initial Gradle execution needed access to the existing user Gradle cache. First lint identified permission-call paths that were made explicit before release.
- Windows reserves TCP ports 5000–5099, including the standard ADB port. The test runner uses local ADB port 15037.
- Android 9 has no emulated Bluetooth adapter. The initial synthetic BluetoothDevice fixture failed. Production receiver events now carry the selected address into the non-exported service, which the hardware-independent fixture uses too.
- Installing a mock GPS provider after service startup allowed the Android 9 emulator's unrelated default location into the trip, causing its jump filter to reject fixture coordinates. The fixture now installs its test provider before starting the trip. Production filtering was preserved.
- Build 1 passed the initial Android 16 eleven-group run. Final build 2 includes the private receiver-routing adjustment and signed-upgrade coverage. All earlier logs remain local.

## Validation limits and phone checks

Injected Bluetooth events establish application transition behavior and Android service/location lifecycles. They do not establish actual car radio behavior. On each intended physical phone, validate permission setup, a locked-screen car connection, ten-second trip points, a brief dropout, a final disconnect, a second journey, boot while monitoring is enabled, recovery after process interruption and an in-place upgrade. Compare trip endpoints, distance and duration with the actual journey. Measure overnight idle battery use and a driving session with the phone's normal battery settings.

Automatic addresses depend on Android's geocoder provider and network conditions. Live address-provider accuracy and offline/retry behavior on physical phones remain pending. Store/export tests retain coordinates when addresses are unavailable. GPS fix delays can shift the recorded endpoints. Profile reconciliation covers standard headset/A2DP car audio connections. Android may defer alarms, but saved arrival time remains the disconnection timestamp. Force-stop requires reopening GPSmon. Manufacturer restrictions, shutdowns and permission changes can prevent collection.

## Reproduce

Build using build.ps1, then build :app:assembleReleaseAndroidTest. scripts/test-release.ps1 accepts -Api 28 or -Api 36 and a unique -RunName. It needs the private signed older-version fixture in artifacts for its upgrade checks. Its emulator data reset applies only to synthetic GPSmon data in the dedicated named AVD. Review full output before treating a run as passed.
