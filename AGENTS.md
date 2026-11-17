# GPSmon project rules

- Follow parent instructions and communicate briefly in English.
- Approved Step 1 is automatic selected-car Bluetooth trip logging, local history, distance, duration, endpoint addresses and editable notes. GPS requests use a ten-second interval. A sixty-second reconnection window preserves brief dropouts. Reitti integration belongs to Step 2.
- Approved Step 2 uploads completed trips point-by-point as JSON to the configurable Reitti GPSLogger ingestion endpoint. Store tokens with Android Keystore encryption, retain local trips and durable per-destination checkpoints, retry recoverable failures and expose upload status/manual retry. Start with a blank server URL and support user-entered HTTP/HTTPS endpoints. Preserve original point timestamps for server deduplication.
- Use Android 9/API 28 through Android 16/API 36. Preserve the GPSmon package and private signing identity across upgrades.
- Read the source before edits, obtain approval before substantive new scope, increment release versions and maintain updates.md.
- Keep signing material, credentials, local SDK settings, test runtimes, recordings and generated artifacts out of Git.
- Verify build/lint, storage recovery, Bluetooth transition logic, real Android service/location lifecycles and signed upgrades before publishing. Clearly distinguish injected emulator events from physical-car Bluetooth and battery validation.
- Publish completed verified releases and matching source to https://github.com/mikeymoo618/GPSmon. Preserve repository history and existing releases.
- The approved server-reference privacy cleanup authorizes rewriting affected published history and withdrawing affected release assets. Keep any recovery backup in ignored private local storage.
