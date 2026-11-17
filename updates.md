# Updates

## 2026-11-17 — 1.1.1/build 6 replacement verified

- Passed twenty-group Android 9 and Android 16 suites with a blank initial server URL and user-entered HTTP/HTTPS configuration.
- Verified 1.1.0 encrypted connection settings and upload checkpoints survive signed upgrades. Earlier 1.0.0 trip/schema migration and real-emulator reboot checks also passed on both platforms.
- Build/lint passed with zero errors and thirteen retained warnings. Confirmed the preserved signing identity, package/SDK metadata and absence of the removed server reference in tracked files and the decompressed APK.
- Public history and release cleanup is being completed under the user's explicit approval.

## 2026-11-17 — 1.1.1/build 6 server-reference cleanup

- Removed the server-specific default and documentation references. New installations start with a blank ingestion URL and require explicit connection setup before enabling uploads.
- Support user-entered HTTP/HTTPS endpoints. Privately saved phone settings and upload checkpoints are retained across upgrades.
- Use a reserved example hostname exclusively in protocol tests. Added blank-default, URL-required and upgrade-preservation regression checks.
- The user approved sanitizing affected published Git history and withdrawing affected release assets. Replacement build verification is in progress.

## 2026-11-17 — 1.1.0/build 5 verification completed

- Passed twenty-group release suites on Android 9 and Android 16, including real HTTP local fixtures, partial progress, server/authentication failures, lost responses, cancellation, the persisted Android network job and actual settings controls.
- Verified in-place migration from the published 1.0.0/build 2 APK preserves trip points, notes, addresses and car selections. Legacy battery information remains unknown.
- Actual emulator reboots restored monitoring on both platforms. Idle diagnostics show no GPSmon location listener. Both emulators were shut down after testing.
- Build/lint passed with zero errors and twelve retained warnings. Package metadata, SHA-256 and preserved signing identity were verified. Production-server authentication and physical-phone validation require the user's locally entered token and phone checks.

## 2026-11-17 — 1.1.0/build 5 release candidate

- Show the timestamp of an upload problem alongside the retained error message and progress.
- Removed a redundant in-memory trip-ID field from upload status. Build 4 passed twenty groups on Android 9 and Android 16. Final candidate verification is in progress.

## 2026-11-17 — 1.1.0/build 4 release candidate

- Validate integration tokens as visible ASCII before passing them to HTTP headers. Accept case-insensitive HTTP/HTTPS schemes.
- Added checks for recording-time battery capture, invalid header values and actual Reitti settings-screen token entry/save behavior.
- Build 3 passed nineteen Android 16 groups, including the persisted network job and local HTTP fixtures. Final build 4 verification is in progress.

## 2026-11-17 — 1.1.0/build 3 Reitti uploads

- Added enabled-by-user Reitti JSON settings, Android Keystore-encrypted token storage and configurable server transport.
- Added chronological post-trip uploads with original Unix timestamps, accuracy, altitude, speed, and battery/charging state captured with new points.
- Migrated existing trips and points without rebuilding the database. Legacy battery values remain unknown.
- Added durable destination-scoped point checkpoints, retry/backoff, cancellation, status and manual retry. Local trips and notes are retained.
- Added local HTTP proxy fixtures for authentication errors, transient failure, lost responses, replay, redirects, database reopen and actual Android network jobs. Verification is in progress.

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
