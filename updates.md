# Updates

## 2026-11-17 — 1.0.0/build 1 initial Step 1 implementation

- Added GPSmon with its own package and private release-signing identity.
- Added selected-car Bluetooth monitoring, ten-second GPS sampling, sixty-second reconnect handling, persisted trip recovery and reboot/package-update receivers.
- Added transactional local trips/points, distance, duration, endpoint address resolution, notes and GPX export.
- Added readiness diagnostics, location/Bluetooth permission setup, notification and battery controls.
- Added release-APK instrumentation for point filtering, persistence, export, Bluetooth transition logic and Android service/location lifecycles.
- Reitti integration remains Step 2. Verification is in progress. Physical-car Bluetooth and battery validation are pending.

## 2026-11-17 — 1.0.0/build 2 release candidate

- Route trusted Bluetooth receiver events into the private service using the selected device address. This also permits service/lifecycle regression coverage on Android 9 emulators without Bluetooth hardware.
- Added signed upgrade fixtures and trip/settings preservation checks. Build 1 passed the Android 16 eleven-group suite. Final build 2 verification is in progress.

## 2026-11-17 — 1.0.0/build 2 verification completed

- Final signed APK passed build/lint with zero errors and nine retained warnings, plus twelve-group release suites on Android 9 and Android 16.
- Verified actual foreground service/location callbacks with injected GPS, sixty-second reconnect behavior, trip recovery, GPX export and edits through actual notes controls.
- Verified signed 0.9.0/build 1 to 1.0.0/build 2 upgrades preserve trip points, notes, addresses and selected cars on both platforms.
- Real emulator reboots restored enabled monitoring through BOOT_COMPLETED on both versions. Idle diagnostics show no GPSmon location listener.
- Verified package/SDK declarations, dedicated signing certificate and APK checksum. Retained all failed-attempt and successful logs. Physical-car Bluetooth, address-provider accuracy and phone battery validation remain pending.
