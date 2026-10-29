# Experimental system-audio capture

SyncSound RC1 implements Android's official playback-capture path on Android 10
(API 29) and newer. It uses a user-approved `MediaProjection`, an
`AudioPlaybackCaptureConfiguration`, and an `AudioRecord`. The capture session
runs while a foreground service is active with the `mediaProjection` service
type.

## What can be captured

Android makes the final decision. Audio is available only when all applicable
conditions are satisfied:

- The user grants `RECORD_AUDIO` and approves Android's screen-capture prompt.
- Source and capturing apps are in the same Android user profile.
- The player's usage is media, game, or unknown.
- The source application and its individual player allow capture.
- DRM and the source application's policy do not prohibit capture.

SyncSound never bypasses these restrictions. It monitors captured PCM and shows
a warning after three seconds without any audible samples. Silence can mean the
source is paused, but it commonly indicates DRM or an application that has
disabled playback capture.

The Host continues hearing the source application normally; SyncSound forwards
the captured PCM only to approved Clients, avoiding a doubled local echo.

## Test

1. Connect and approve at least one Client.
2. On the Host, tap **Share supported system audio**.
3. Grant the audio-recording permission and Android projection prompt.
4. Start playback in a browser or another application that permits capture.
5. Return to SyncSound to inspect capture status. Use **Stop** to terminate the
   stream and projection.

Netflix, Spotify, YouTube, other DRM services, and OEM applications may block
capture. Support can vary by source application even on the same phone.

Official references:

- https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- https://developer.android.com/media/grow/media-projection
