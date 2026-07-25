# Experimental system-audio capture decision

Android officially provides `AudioPlaybackCapture` from Android 10 (API 29),
using a user-approved `MediaProjection` token and an `AudioRecord` configured
with `AudioPlaybackCaptureConfiguration`.

It is not enabled in SyncSound 1.0 RC1. The feature is optional, significantly
expands the foreground-service and permission lifecycle, and cannot guarantee
that a source app will provide audio. Adding it before physical validation of
the file-streaming pipeline would increase crash risk in the release candidate.

Android permits capture only when all applicable conditions are satisfied:

- The user approves the system MediaProjection prompt.
- SyncSound has `RECORD_AUDIO`.
- Source and capturing apps are in the same user profile.
- Source usage is media, game, or unknown.
- The source app and individual player allow capture.
- DRM or a restrictive source capture policy does not block it.

Applications can explicitly forbid capture. SyncSound cannot and will not
bypass that policy; unsupported sources would produce silence and must be
reported as unavailable in any future implementation.

Official references:

- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration

Post-RC implementation requires a separate foreground service with the
`mediaProjection` type, revocation callback handling, silence/source-policy
detection, and the same timestamped PCM distributor used by file playback.
