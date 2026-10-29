# SyncSound setup and testing

## Requirements

- Latest stable Android Studio.
- Android SDK Platform 36 and Build Tools 36 installed through SDK Manager.
- JDK 17. Android Studio's bundled JDK is recommended.
- Two physical Android phones running Android 8.0 (API 26) or newer.
- Both phones on the same Wi-Fi network, or one connected to the other's hotspot.

## Open and install dependencies

1. Open Android Studio.
2. Select **Open** and choose the `SyncSound` repository directory.
3. Allow Android Studio to use its bundled JDK 17.
4. Accept any prompt to install Android SDK Platform 36.
5. Select **File > Sync Project with Gradle Files**.

The checked-in Gradle wrapper downloads Gradle 8.13. Dependencies come only from
Google Maven and Maven Central. No secrets or `local.properties` are committed.

## Build the APK

From Android Studio, use **Build > Build APK(s)**.

Or from a terminal:

```bash
./gradlew test
./gradlew assembleDebug
```

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

For a distributable release, create a private signing key in Android Studio with
**Build > Generate Signed App Bundle or APK**. Never commit the key or passwords.

## Install on a phone

1. Enable Developer options and USB debugging on the phone.
2. Connect it by USB and approve the debugging prompt.
3. Choose the phone in Android Studio and press **Run**.

Alternatively, with Android platform tools installed:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Repeat for the second phone. Android 13+ asks for nearby-device access; allow it
for automatic session discovery.

Permissions:

- **Nearby devices**: automatic local session discovery on Android 13+.
- **Notifications**: keeps the active-session foreground notification visible.
- **Record audio**: requested only if the Host enables experimental system-audio
  capture. It is not needed for file playback or Test Sync.
- **Screen capture prompt**: requested only for experimental system-audio
  capture and must be approved for every new projection session.

## Test two devices

1. Disable Bluetooth audio on both phones.
2. Put both phones on the same Wi-Fi network or hotspot.
3. Open SyncSound on phone A and tap **Create Session**.
4. Open SyncSound on phone B and tap **Join Session**.
5. Select the discovered Host, enter its six-digit Session Code, or enter phone
   A's displayed IPv4 address under **Manual connection**.
6. Phone A displays the pending device. Tap **Approve**.
7. Tap **Test Sync**. Both phones should play the same short confirmation tone
   and phone A should show `success` for phone B.
8. On phone A, tap **Select music** and choose one or more supported files.
9. Tap **Play**. The app waits 1.5 seconds to fill Client buffers and then starts
   both speakers.
10. Exercise Pause, Play, Stop, and Skip. Rejecting a pending phone should prevent it
   from receiving media.
11. Move the Host volume slider and verify the Client volume follows.

For experimental system audio on Android 10+, follow
[`docs/SYSTEM_AUDIO_CAPTURE.md`](docs/SYSTEM_AUDIO_CAPTURE.md). Android can only
capture source applications that explicitly permit playback capture.

Keep the SyncSound session notification enabled on both phones. It allows the
network and audio session to continue when the display sleeps.

For a useful timing check, play an MP3 containing sharp clicks and record both
phones with a third device. Wi-Fi client isolation, aggressive battery modes,
Bluetooth routes, and overloaded 2.4 GHz networks can prevent good results.

## Troubleshooting

- **No sessions found:** use the manual IP shown on the Host.
- **Manual connection fails:** verify the two phones can communicate and that
  the hotspot/router does not isolate clients.
- **No audio file accepted:** use MP3, PCM WAV, AAC, FLAC, or M4A. Actual decoding
  support still depends on the codecs supplied by the phone manufacturer.
- **Audio breaks up:** move to 5 GHz Wi-Fi, reduce the number of Clients, and
  keep all phones awake with SyncSound visible.
- **System capture is silent:** the source may be paused, DRM-protected, in
  another user profile, or configured to prohibit Android playback capture.
- **Build cannot find Java:** set Gradle JDK to Android Studio's bundled JDK 17
  under Android Studio Gradle settings.

## Diagnostic logs

All important events use the `SyncSound` Logcat tag and structured
`area=... event=...` fields:

```bash
adb logcat -s SyncSound
```

Capture logs from both phones when reporting a network or synchronization issue.
