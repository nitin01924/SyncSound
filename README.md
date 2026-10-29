<div align="center">
  <img src="docs/assets/syncsound-hero.svg" alt="SyncSound — nearby Android phones playing together over a local network" width="100%" />

  <h1>SyncSound</h1>

  <img src="https://img.shields.io/badge/version-1.0.0--rc1-6042D0?style=for-the-badge" alt="Version 1.0.0 RC1" />
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0 and newer" />
  <img src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin 2.2" />
  <img src="https://img.shields.io/badge/Jetpack-Compose-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />

  <p><strong>Turn nearby Android phones into one shared listening experience.</strong></p>
  <p>
    <a href="#quick-start">Quick start</a>
    ·
    <a href="SETUP.md">Setup guide</a>
    ·
    <a href="docs/ARCHITECTURE.md">Architecture</a>
    ·
    <a href="docs/RC1_TEST_PLAN.md">Test plan</a>
  </p>
</div>

> [!IMPORTANT]
> SyncSound is currently a **Version 1 release candidate** for local testing. Traffic stays on the local network, but it is not encrypted. Use it only on networks and with devices you trust.

## Sound, together

SyncSound streams audio from one **Host** phone to approved **Client** phones on the same Wi-Fi network or mobile hotspot. The Host decodes audio once, distributes timestamped PCM packets, and schedules playback against a shared monotonic clock so every device starts on the same timeline.

No cloud account or internet connection is required for a session.

<table>
  <tr>
    <td width="33.33%" align="center" valign="top">
      <br />
      <strong>① Create</strong><br />
      <sub>Start a private listening session from one phone.</sub>
      <br /><br />
    </td>
    <td width="33.33%" align="center" valign="top">
      <br />
      <strong>② Connect</strong><br />
      <sub>Approve nearby phones over Wi-Fi or a hotspot.</sub>
      <br /><br />
    </td>
    <td width="33.33%" align="center" valign="top">
      <br />
      <strong>③ Listen</strong><br />
      <sub>Press play once and hear the same moment together.</sub>
      <br /><br />
    </td>
  </tr>
</table>

| | Capability | What it gives you |
|:--:|---|---|
| ◉ | **Nearby discovery** | Find Hosts automatically with Android DNS-SD, or connect by session code/IP address. |
| ✓ | **Host approval** | Review, approve, reject, and remove every phone that requests access. |
| ≋ | **Synchronized playback** | Use clock-offset sampling, future start times, and timestamped audio to keep phones aligned. |
| ▶ | **Shared controls** | Play a queue, pause, resume, stop, skip, and change volume from the Host. |
| ⏱ | **Sync test** | Play a short confirmation tone and inspect the result for each connected device. |
| ◌ | **Session resilience** | Continue in a foreground service and reconnect approved devices after brief network loss. |

## How it works

```mermaid
flowchart LR
    subgraph H[Host phone]
        direction TB
        A[Music files] --> B[MediaExtractor<br/>+ MediaCodec]
        B --> C[16-bit PCM]
        C --> D[Local AudioTrack]
        C --> E[Timestamped<br/>UDP packets]
        F[Session controls] --> G[TCP control channel]
    end

    subgraph N[Local Wi-Fi / hotspot]
        E --> U[UDP :45122]
        G --> T[TCP :45121]
    end

    subgraph C1[Approved Client phones]
        direction TB
        T --> J[Clock sync + commands]
        U --> K[Packet reorder buffer]
        J --> L[Shared presentation time]
        K --> L
        L --> M[AudioTrack playback]
    end

    classDef source fill:#E7DEFF,stroke:#6042D0,color:#1C0060,stroke-width:2px;
    classDef transport fill:#DDF8F2,stroke:#006B5D,color:#00201B,stroke-width:2px;
    classDef output fill:#171A22,stroke:#B9A6FF,color:#F7F7FC,stroke-width:2px;
    class A,B,C,F source;
    class E,G,U,T,J,K transport;
    class D,L,M output;
```

The control plane and media plane are intentionally separate: reliable TCP carries identity, approval, clock probes, formats, and playback commands; bounded UDP datagrams carry low-latency PCM audio. If a media packet is missing, the Client inserts timestamp-derived silence instead of pulling later audio forward and breaking the group timeline.

## Quick start

You will need:

- Android Studio with Android SDK Platform 36
- JDK 17 (Android Studio's bundled JDK is recommended)
- Two physical Android phones running Android 8.0 / API 26 or newer
- A shared Wi-Fi network or mobile hotspot

Build and test the debug app:

```bash
./gradlew test assembleDebug
```

The APK will be available at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on both phones with Android Studio, or use ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

See the [complete setup guide](SETUP.md) for SDK installation, permissions, release builds, and troubleshooting.

## Start a listening session

```mermaid
sequenceDiagram
    autonumber
    actor H as Host
    participant N as Local network
    actor C as Client

    H->>N: Create and advertise session
    C->>N: Discover Host or enter code/IP
    C->>H: Request to join
    H-->>C: Approve device
    C->>H: Sample clock offset
    H-->>C: Format + Play at T + 1.5 s
    H-)C: Timestamped PCM packets
    par Host playback
        H->>H: Play at scheduled time
    and Client playback
        C->>C: Buffer, translate clock, and play
    end
```

1. Put both phones on the same Wi-Fi network and disable Bluetooth audio.
2. On phone A, tap **Create a session**.
3. On phone B, tap **Join a session** and select the discovered Host. The six-digit code and manual IP address are available as fallback connection methods.
4. Approve phone B on the Host, then run **Test Sync**.
5. Choose one or more supported audio files on the Host and press **Play**.

## Technical profile

| Area | Implementation |
|---|---|
| Platform | Native Android, single activity, minimum API 26 |
| UI | Kotlin, Jetpack Compose, Material 3, MVVM |
| Audio input | MP3, PCM WAV, AAC/M4A, and FLAC through Android platform codecs |
| Audio output | 16-bit PCM through `AudioTrack` |
| Discovery | Android NSD / DNS-SD service `_syncsound._tcp` |
| Control transport | Versioned binary protocol over TCP `45121` |
| Media transport | PCM over UDP `45122`, maximum 1,200-byte datagrams |
| Synchronization | Smoothed clock offsets and a 1.5-second scheduled start lead |
| Background operation | Visible Android foreground service |
| Dependency injection | Small manual composition root |

## Project structure

```text
SyncSound/
├── app/src/main/java/com/syncsound/app/
│   ├── audio/       # Decode, capture, packetize, buffer, and play PCM
│   ├── data/        # Session state and orchestration
│   ├── di/          # Manual application composition root
│   ├── logging/     # Structured application and crash logging
│   ├── model/       # Immutable UI and domain models
│   ├── network/     # Discovery, TCP control, UDP protocol, monitoring
│   ├── service/     # Active-session foreground service
│   ├── ui/          # Compose screens, components, and ViewModels
│   └── util/        # Network helpers
├── app/src/test/    # Packet, buffer, tone, and control-server tests
├── docs/            # Architecture, test plan, and capture notes
├── SETUP.md         # Detailed build and two-phone test guide
└── README.md
```

## Experimental system audio

On Android 10 and newer, a Host can try Android's official playback-capture API to share audio from another application. This requires `RECORD_AUDIO` permission and explicit approval of Android's screen-capture prompt for each session.

Capture availability is controlled by Android and the source app. DRM services and apps that opt out may produce silence; SyncSound does not bypass those restrictions. Read [System Audio Capture](docs/SYSTEM_AUDIO_CAPTURE.md) before testing this feature.

## Current limitations

- Best suited to small groups: stereo PCM uses roughly 1.4–1.5 Mbit/s **per Client** before Wi-Fi overhead.
- Bluetooth introduces device-specific latency; use phone speakers or wired outputs for synchronization tests.
- Android hardware and speaker DSP latency are not acoustically calibrated, so different phone models can still have an audible offset.
- Routers or hotspots with wireless client isolation will prevent devices from connecting.
- Local traffic is cleartext in Version 1. Approval limits who receives playback, but it is not cryptographic authentication.
- Codec availability can vary by phone manufacturer even when a file type is supported by the app.

## Development

Run the main local checks:

```bash
./gradlew test
./gradlew lint
./gradlew assembleDebug
./gradlew assembleRelease
```

Important events use the `SyncSound` Logcat tag with structured `area=... event=...` fields:

```bash
adb logcat -s SyncSound
```

For physical-device acceptance criteria, follow the [RC1 test plan](docs/RC1_TEST_PLAN.md).

## Documentation

- [Setup and two-device testing](SETUP.md)
- [Version 1 architecture](docs/ARCHITECTURE.md)
- [RC1 acceptance test plan](docs/RC1_TEST_PLAN.md)
- [Experimental system-audio capture](docs/SYSTEM_AUDIO_CAPTURE.md)

<div align="center">
  <sub>Built for nearby listening — no cloud required.</sub>
</div>
