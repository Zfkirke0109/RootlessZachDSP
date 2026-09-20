# Android 16/17 and Samsung One UI compatibility

RootlessZachDSP compiles and targets Android 16 (API 36) and is tested forward on Android 17. The native toolchain is pinned to NDK r28 and release APKs are checked with 16 KiB ZIP alignment. Every packaged ELF library must also be inspected before stable release.

Primary Samsung validation covers Galaxy S23 Ultra on One UI 8.5 and later One UI 9 builds: speaker, wired, A2DP, BLE Audio, USB DAC, HDMI, DeX, SoundAssistant, Separate App Sound, Multi Sound, SoundAlive, screen-off behavior, power saving, route changes, and system-component updates.

Android 17 background-audio hardening can reject audio operations that do not originate from a visible activity or eligible foreground service. MediaProjection authorization must begin in a visible activity, followed by the declared media-projection foreground service. Tests capture `AudioHardening` logs and treat silent platform rejection as a diagnostic state.

Full DSP is impossible when the source disallows capture, uses an uncapturable native/exclusive path, or runs under another Android user/profile. Such cases must remain audible through clean bypass where technically possible and receive a clear explanation rather than a false universal compatibility claim.
