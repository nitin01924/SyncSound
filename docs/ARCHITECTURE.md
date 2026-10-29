# SyncSound Version 1 architecture

## Scope

Version 1 is a native, single-activity Android app. Kotlin and Compose own the UI
and lifecycle. The Host decodes selected music with `MediaExtractor` and
`MediaCodec`, plays the resulting PCM locally with `AudioTrack`, and fans the
same PCM data out to approved Clients over UDP.

This repository intentionally does not contain Version 2 features: Opus,
acoustic calibration, stereo device roles, multicast media, host migration,
cloud accounts, or unsupported/root-based system-audio capture. Android's
official, policy-limited playback-capture API is available experimentally.

## Layers

- `ui`: Compose screens and MVVM ViewModels.
- `data`: the session repository, which is the single source of truth.
- `network`: DNS-SD discovery, TCP control protocol, approval, and clock probes.
- `audio`: platform decoding, optional official playback capture, bounded UDP
  PCM packets, buffering, and `AudioTrack`.
- `model`: immutable screen and protocol-domain models.
- `util`: platform-independent helpers where appropriate.
- `di`: a deliberately small manual composition root.

The project is one Gradle module because Version 1 has one deployment unit and a
small team-facing API surface. Package boundaries and interfaces prevent Android
UI code from depending directly on sockets or codecs, and can become Gradle
modules later without changing product behavior.

## Networking

Android NSD advertises `_syncsound._tcp`. The Host also shows its private IPv4
address because routers and hotspots sometimes suppress mDNS.

TCP port `45121` carries the versioned control protocol:

- Client identity and pending/approved/rejected state.
- Host identity and six-digit session code validation.
- Clock pings.
- Audio format.
- Future-timestamped play, pause, and stop commands.
- Sync-test commands and per-device acknowledgements.
- Session-wide playback volume.

UDP port `45122` carries PCM packets no larger than 1,200 bytes. Every packet has
a protocol version, stream ID, sequence number, Host monotonic presentation
time, sample rate, channel count, and payload. Approved address filtering occurs
at the Host immediately before every fan-out.

Version 1 uses clear local-network transport. The Host approval gate prevents
uninvited playback but is not cryptographic authentication. TLS and authenticated
media encryption belong in a security-hardening release before public release.

Control writes use one serialized outbound queue so FORMAT always precedes PLAY.
Socket exceptions are contained on the IO supervisor, converted to explicit
connection state, and logged. Approved device identities can reconnect during
the same Host session with bounded exponential backoff. Ending the Host session
sends a terminal removal message so Clients do not retry forever.

## Synchronization

Clients periodically ping the Host, reject clock samples distorted by network
queueing, and continuously smooth accepted offsets so oscillator drift is
followed over time. Play is scheduled 1.5 seconds in the future so all devices
can buffer. Clients translate both the control command and every PCM packet from
the Host monotonic domain into their own `elapsedRealtimeNanos` domain.

This is synchronization by presentation time, not simultaneous command arrival.
Version 1 targets perceptually close playback on a healthy local network. It
cannot compensate for unknown speaker/DSP output latency on different phone
models; acoustic measurement is specifically deferred.

An active Host or Client runs a visible foreground service. This keeps the
session eligible to run when the UI is backgrounded or the display sleeps.
Clients accept UDP media only from the Host address used by their approved
control connection.

When one or more UDP packets are missing, the Client writes timestamp-derived
silence rather than moving later audio earlier. This preserves the group
timeline. Pause and resume are also scheduled against translated Host monotonic
time instead of command arrival time.

## Version 1 constraints

- Small groups only. PCM uses roughly 1.4–1.5 Mbit/s per stereo 44.1/48 kHz
  Client before Wi-Fi overhead.
- MP3, common PCM WAV, AAC/M4A, and FLAC are accepted. Actual codec support can
  still vary with the Android device.
- Phone speakers or wired output; Bluetooth adds uncontrolled latency.
- Android may still stop a session under exceptional resource pressure; the
  foreground notification must remain visible.
- A router with wireless client isolation will prevent operation.
- Clock drift is followed for new scheduling boundaries. Android hardware/DSP
  output latency remains device-specific and is not acoustically calibrated.
