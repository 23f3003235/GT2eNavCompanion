# GT2e Navigation Companion — experimental Android MVP

## What it does
- Sends a test Android notification to check whether Huawei Health forwards custom-app alerts to the GT 2e.
- Fetches an experimental walking route using the public OSRM demonstration server from a recent Android location to manually entered destination coordinates.
- While the app is open, checks the latest phone location every 5 seconds and issues an alert near a route maneuver.

## Critical limitations
- **Not tested on the user's watch or phone. No APK included.** This is source code, not a proven solution.
- The public OSRM demo service is not a production routing service; its `foot` URL does not guarantee pedestrian-specific routing. Do not rely on it for walking safety or road legality.
- Nearest-maneuver matching is a crude proof of concept, not robust map matching. It can produce incorrect turns on loops, intersections and overlapping roads.
- Uses last known locations, requires an update from another app, and does not request active location updates. Location must be less than 2 minutes old.
- Navigation runs only while this activity is open. No background navigation, rerouting, voice or offline routing.
- Huawei Health must allow this app in its notification list; watch display is not guaranteed.
- Routing sends coordinates to a third-party OSRM server. Do not use if you require fully offline or private routing.
- Avoid using this as your sole navigation source or interacting with the phone while driving.

## Build
Open the folder in Android Studio (JDK 17, Android SDK 35). Sync Gradle and choose Build > Build APK(s). The APK will be at app/build/outputs/apk/debug/app-debug.apk. Install it on the phone and grant notification permission.

## Test sequence
1. Pair the GT 2e with Huawei Health; enable Android notification access for Huawei Health.
2. Install the app, request notification permission, and enable **GT2e Nav Companion** in Huawei Health > Devices > GT 2e > Notifications (if listed).
3. Press **Send test notification**. Check Android notification shade and the watch.
4. If the watch does not receive the test, stop: routing code cannot fix notification transport.
5. If it does, optionally test experimental route instructions using valid coordinates and a fresh GPS fix.

## Next production milestones
Use a supported routing service with legal turn restrictions; active fused location updates; proper maneuver progression, route deviation and rerouting; foreground service with Android background-location and notification handling; on-device routing if offline support is desired; notification throttling and watch text-length testing.
