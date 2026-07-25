# SyncSound RC1

SyncSound is a native Android Version 1 Release Candidate that streams music selected by a Host
to approved Android Clients on the same Wi-Fi network or mobile hotspot. Audio is
decoded to PCM by the Host, transported over UDP, buffered, and started against
a shared monotonic presentation timestamp.

See [SETUP.md](SETUP.md) for build and two-device testing instructions and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for design details,
[docs/RC1_TEST_PLAN.md](docs/RC1_TEST_PLAN.md) for acceptance testing, and
[docs/SYSTEM_AUDIO_CAPTURE.md](docs/SYSTEM_AUDIO_CAPTURE.md) for the optional
Android playback-capture investigation.
