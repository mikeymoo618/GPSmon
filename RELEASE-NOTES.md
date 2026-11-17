# GPSmon 1.0.0

Initial automatic-trip release, build 2, for Android 9 and newer including Android 16.

- Select paired car Bluetooth devices and enable monitoring once.
- Car connection starts a trip. GPS is requested every ten seconds while connected.
- A sixty-second reconnection window preserves brief Bluetooth dropouts. Completed trips use the disconnection time as arrival time.
- Waiting mode keeps GPS off. A visible foreground notification reports monitoring and trip status.
- Local trip history retains GPS points, distance, duration, endpoint addresses or coordinates, and editable notes.
- GPX export uses Android's document picker. Failed address lookups retain coordinates and can be retried.
- Stored points, trip state and enabled settings survive process interruption and signed app upgrades.
- Setup includes precise/background location, Bluetooth, notifications and battery controls.

Reitti integration is reserved for Step 2.

Validation uses dedicated Android 9/API 28 and Android 16/API 36 emulators with synthetic Bluetooth events and injected GPS fixes. Actual car Bluetooth behavior, locked-phone trips, reboot recovery on physical hardware and idle battery consumption require phone validation. Android force-stop and manufacturer restrictions can prevent automatic recording. The first GPS fix can arrive after movement begins, and endpoints reflect recorded fixes. See TESTING.md for evidence and limitations.
