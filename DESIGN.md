# GPSmon 1.0.0 design

## Approved scope

GPSmon automatically records road trips when selected paired Bluetooth devices connect. Precise GPS is requested every ten seconds during connected trips. Disconnection suspends GPS immediately and allows sixty seconds for reconnection. The trip ends at the original disconnection timestamp if the window expires. Trips retain coordinates, distance, timestamps, endpoint addresses and editable notes. Reitti upload is Step 2.

## Implementation decisions

- Use a focused Java Android application with framework APIs and the pinned ScreenOhRecord Gradle/SDK and private-signing patterns. GPSLogger provides the foreground location, filtering and GPX design reference. Retain its GPLv2 licence and attribution. The simplified application implements the approved trip workflow directly.
- An ongoing connected-device foreground service receives Bluetooth ACL broadcasts. An additional location foreground-service type is active during recording. Idle monitoring registers no location listener and has no periodic polling loop. Opening the app, system boot, package replacement and sticky service restart restore enabled monitoring when Android permits it.
- Background precise-location permission allows location service activation while the phone is locked. Android 12+ Bluetooth-connect permission and Android 13+ notification permission are exposed in setup. Battery-optimization exemption is user-controlled.
- Paired devices are selected explicitly. Active selection changes require disabling monitoring, preventing races against an ongoing trip. Multiple selected cars share one trip while at least one remains connected. Startup reconciliation checks Android headset and A2DP audio profiles. Other device types can trigger through ACL events, but their already-connected state cannot be recovered through these audio profiles.
- SQLite stores every accepted point and cumulative distance in one transaction. A unique active-trip column prevents duplicate trip creation. A stored disconnect timestamp survives process loss. A private alarm provides deadline recovery when the process sleeps. Android can delay the alarm, while the stored arrival timestamp remains the original disconnect time.
- Ignore stale fixes, invalid coordinates, accuracy worse than 75 metres, samples closer than ten seconds, and apparent speeds above 100 metres per second. Suppress small stationary drift in the distance total. GPS sampling and chord distance are approximate, with uncertainty at turns and poor reception.
- The first fresh GPS fix supplies the origin. The final accepted connected fix supplies the destination. Exact Bluetooth connection/disconnection coordinates may be unavailable. Point times and accuracy appear in trip details. A trip without fixes remains visible with unavailable endpoints.
- Resolve endpoint addresses after completion through Android Geocoder on a background worker. Preserve coordinates on failure and expose an explicit retry. Android's configured geocoder provider may receive coordinates. Trip descriptions derive from endpoint addresses while notes remain separately editable.
- GPX export uses Android's document picker and XML escaping. The private database remains the primary local record. Android app backup and device transfer are disabled. Uninstalling clears private trip data.

## Reliability boundaries

Force-stop prevents background receivers until the user reopens the app. Permission revocation, manufacturer process management, disabled Bluetooth/location and phone shutdown can interrupt collection. The app retains the last problem and displays readiness. Recovery closes an interrupted trip at its last known recorded point if the car is no longer connected, explicitly identifying the interruption. A surviving connection resumes the same stored trip.

## Sources

- https://github.com/mikeymoo618/GPSloggerV2/tree/f2cca9487363ab3c3b8e77d44185c11016abad7f
- ScreenOhRecord 1.3.4/build 20 under ../AZScreen/ScreenOhRecord
