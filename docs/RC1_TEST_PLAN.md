# SyncSound 1.0 RC1 acceptance test

RC1 is ready for physical-device testing after the automated build, unit tests,
R8 release build, and Android Lint complete successfully.

## Required matrix

Run the core scenario with:

- Two different phone manufacturers when available.
- Android 8–12 and Android 13–16 when available.
- A normal 5 GHz Wi-Fi router.
- One Android phone hotspot.
- Screen on and screen off.

## Release-blocking scenario

1. Host creates a session.
2. Client joins and remains on the waiting screen.
3. Host approves the Client.
4. Leave both apps connected for two minutes.
5. Verify neither process exits and both show the approved connection.
6. Repeat while disconnecting Wi-Fi immediately before approval.
7. Verify the Host reports the failed/disconnected Client without crashing.

## Functional scenario

1. Join through discovery.
2. Remove the Client and verify it returns to an error/disconnected state.
3. Rejoin with the six-digit Session Code.
4. Run Test Sync and verify a per-device success result.
5. Play one file of every available supported type: MP3, PCM WAV, AAC, FLAC,
   and M4A.
6. Verify Play, Pause, resume, Stop, and Skip.
7. Turn the Client screen off for one minute and verify the foreground
   notification keeps the session alive.
8. Disable and restore Wi-Fi. Verify reconnecting state appears and the device
   recovers without process termination.
9. End the Host session. Verify Clients disconnect instead of retrying forever.
10. Change Host volume from 100% to 25% and verify every Client follows.
11. On Android 10+, grant experimental system-audio capture and test one source
    that permits capture. Also test a blocked/DRM source and verify SyncSound
    reports silence instead of crashing or claiming capture succeeded.

## Synchronization check

Place both phones together, select a file with sharp beats, and record them with
a third phone. Also run Test Sync five times. Report phone models, Android
versions, network type, audible offset, and both `SyncSound` Logcat streams.

## Pass criteria

- No crash or ANR.
- Approval never ends either process.
- Test Sync succeeds on every connected device.
- Music remains stable without repeated underruns on a healthy network.
- Controls affect all devices on the same scheduled timeline.
- Network and media errors appear as friendly UI state and structured logs.
