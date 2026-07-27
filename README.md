<div align="center">

# 🎵 SyncSound RC1

**Stream music across multiple Android devices in perfect synchronization over Wi-Fi**

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-8.0+-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://www.android.com/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](#)

---

</div>

## ✨ Features

- 🎧 **Multi-Device Synchronization** - Stream music to multiple Android devices with precise timing
- 📡 **Wi-Fi Direct** - Works on the same Wi-Fi network or mobile hotspot
- 🔊 **PCM Audio Transport** - High-quality UDP streaming with intelligent buffering
- 🎚️ **Remote Volume Control** - Host controls volume for all connected clients
- 🔐 **Session Codes** - 6-digit session codes for easy device pairing
- 📱 **Automatic Discovery** - Android 13+ automatic local session discovery
- 🧪 **Test Sync** - Built-in synchronization verification
- 🎵 **Multiple Formats** - MP3, PCM WAV, AAC, FLAC, M4A support

---

## 🚀 Quick Start

### Prerequisites

- **Android Studio** (Latest stable)
- **Android SDK Platform 36** & **Build Tools 36**
- **JDK 17** (Android Studio's bundled JDK recommended)
- **Two Android phones** running Android 8.0+ (API 26 or newer)
- **Same Wi-Fi network** or one connected to the other's hotspot

### Installation Steps

#### 1️⃣ Clone & Setup

```bash
git clone https://github.com/nitin01924/SyncSound.git
cd SyncSound
```

#### 2️⃣ Open in Android Studio

1. Launch **Android Studio**
2. Select **File > Open** and choose the `SyncSound` directory
3. Let Android Studio use its bundled **JDK 17**
4. Accept prompts to install **Android SDK Platform 36**
5. Select **File > Sync Project with Gradle Files**

#### 3️⃣ Build the Application

**From Android Studio:**
- Use **Build > Build APK(s)**

**Or from Terminal:**

```bash
# Run tests
./gradlew test

# Build debug APK
./gradlew assembleDebug
```

The debug APK will be available at:
```
app/build/outputs/apk/debug/app-debug.apk
```

#### 4️⃣ Install on Devices

**Via Android Studio:**
1. Enable **Developer Options** and **USB Debugging** on your phone
2. Connect via USB and approve the debugging prompt
3. Choose the device in Android Studio and press **Run**

**Via Command Line (ADB):**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Repeat for the second device.

---

## 📖 Usage Guide

### First Time Setup

1. **Host Phone (Phone A):**
   - Open SyncSound
   - Tap **Create Session**
   - Share the displayed 6-digit **Session Code** or **IPv4 address**

2. **Client Phone (Phone B):**
   - Open SyncSound
   - Tap **Join Session**
   - Select discovered Host, or enter the code/IP address manually

3. **Approve Connection:**
   - Phone A displays the pending device
   - Tap **Approve** to connect

### Test Synchronization

1. Tap **Test Sync** to verify audio alignment
2. Both phones should play a confirmation tone simultaneously
3. Phone A will show `success` for synchronized clients

### Play Music

1. On Phone A, tap **Select Music**
2. Choose one or more supported audio files
3. Tap **Play** - audio will start on all connected devices
4. Use **Pause**, **Play**, **Stop**, and **Skip** controls
5. Adjust volume on the Host - clients follow automatically

### Permissions

- **Nearby Devices** - Automatic discovery on Android 13+
- **Notifications** - Keeps active session visible
- **Record Audio** - Only needed for experimental system-audio capture
- **Screen Capture** - Required for system-audio capture feature

---

## 📁 Project Structure

```
SyncSound/
├── app/                          # Main Android application
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/            # Kotlin source code
│   │   │   ├── res/             # Resources (layouts, strings, colors)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                # Unit tests
│   └── build.gradle
├── SETUP.md                      # Detailed setup instructions
├── docs/
│   ├── ARCHITECTURE.md           # System design details
│   ├── RC1_TEST_PLAN.md         # Acceptance testing guide
│   └── SYSTEM_AUDIO_CAPTURE.md  # Optional audio capture setup
├── gradle/                       # Gradle wrapper configuration
└── build.gradle                  # Project-level build config
```

---

## 🔧 How It Works

### Architecture Overview

```
┌─────────────────────────────────────────────────┐
│            SyncSound System Flow                │
├─────────────────────────────────────────────────┤
│                                                 │
│  HOST PHONE              NETWORK      CLIENT    │
│  ┌──────────────┐                    PHONES    │
│  │   Select     │        Wi-Fi        ┌─────┐ │
│  │   Music      │  ──────────────→   │ PCM │ │
│  │              │   UDP Stream       │Buff│ │
│  │   Decode to  │                    │ er │ │
│  │   PCM        │  ←──────────────   │     │ │
│  │              │   Sync Signal      └─────┘ │
│  │   Send Sync  │                            │
│  │   Signal     │                    ┌─────┐ │
│  │              │  ──────────────→   │PCM  │ │
│  └──────────────┘                    │Play │ │
│                                      └─────┘ │
│                                              │
│  Shared Monotonic Presentation Timestamp    │
│  Ensures audio playback is synchronized     │
│                                              │
└─────────────────────────────────────────────────┘
```

**Key Features:**
- Audio is decoded to **PCM by the Host**
- Transported over **UDP for low latency**
- Clients **buffer** for consistent playback
- All devices use a **shared timestamp** for synchronization

---

## 🐛 Troubleshooting

| Issue | Solution |
|-------|----------|
| ❌ No sessions found | Use the manual IP address shown on the Host |
| ❌ Manual connection fails | Verify both phones can communicate; check hotspot/router settings for client isolation |
| ❌ Audio file not accepted | Use MP3, PCM WAV, AAC, FLAC, or M4A formats |
| ❌ Audio breaks up | Switch to 5 GHz Wi-Fi, reduce number of clients, keep phones awake |
| ❌ System capture is silent | Source may be paused, DRM-protected, or configured to prohibit capture |
| ❌ Build cannot find Java | Set Gradle JDK to Android Studio's bundled JDK 17 in settings |

### Debug Logs

Monitor application events with:

```bash
adb logcat -s SyncSound
```

---

## 📚 Documentation

- 📋 **[SETUP.md](SETUP.md)** - Detailed build and testing instructions
- 🏗️ **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** - System design and architecture
- ✅ **[docs/RC1_TEST_PLAN.md](docs/RC1_TEST_PLAN.md)** - Acceptance testing guide
- 🎤 **[docs/SYSTEM_AUDIO_CAPTURE.md](docs/SYSTEM_AUDIO_CAPTURE.md)** - Optional system audio capture setup

---

## 🛠️ Building for Release

To create a distributable release build:

1. In Android Studio, go to **Build > Generate Signed App Bundle or APK**
2. Create a private signing key (keep it secure!)
3. Never commit the key or passwords to version control

---

## 📋 System Requirements

| Component | Requirement |
|-----------|-------------|
| **Android Version** | 8.0 (API 26) or newer |
| **SDK Platform** | 36 |
| **Build Tools** | 36 |
| **JDK** | 17 |
| **Gradle** | 8.13 (auto-downloaded) |
| **Dependencies** | Google Maven, Maven Central |

---

## 🎯 Supported Audio Formats

- 🎵 **MP3**
- 🎵 **PCM WAV**
- 🎵 **AAC**
- 🎵 **FLAC**
- 🎵 **M4A**

*Actual codec support depends on your device manufacturer's supplied codecs.*

---

## 💡 Tips for Best Results

✅ Disable Bluetooth audio on both phones  
✅ Use 5 GHz Wi-Fi for optimal performance  
✅ Keep both phones awake while streaming  
✅ Use MP3 files with sharp clicks for timing verification  
✅ Keep the SyncSound foreground notification visible  
✅ Test with files containing clear audio markers  

---

## 📄 License

This project is provided as-is for development and testing purposes.

---

## 📞 Support

For issues, questions, or detailed information:

- 📖 Check the [SETUP.md](SETUP.md) file
- 🏗️ Review the [Architecture Documentation](docs/ARCHITECTURE.md)
- 🐛 Check [Troubleshooting Section](#-troubleshooting)
- 📝 View diagnostic logs with `adb logcat -s SyncSound`

---

<div align="center">

### ⭐ If you find this project useful, please star it! ⭐

Made with ❤️ for synchronized music streaming

[GitHub Repository](https://github.com/nitin01924/SyncSound) • [Releases](https://github.com/nitin01924/SyncSound/releases)

</div>
