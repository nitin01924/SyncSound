# SyncSound

SyncSound is a native Android Version 1 application that streams an MP3 selected by a Host to
approved Android Clients on the same Wi-Fi network or mobile hotspot. Audio is
decoded to PCM by the Host, transported over UDP, buffered, and started against
a shared monotonic presentation timestamp.

See [SETUP.md](SETUP.md) for build and two-device testing instructions and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for design details and Version 1 limits.
