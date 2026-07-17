# Direct Player and USB bit-perfect architecture

## Scope

Direct Player is a separate source-owned playback mode. It does not reuse Android playback capture and does not process another application's output. The existing rootless capture path remains available for system-wide DSP and remains explicitly non-bit-perfect.

## Current checkpoint

Implemented foundation:

- exact PCM format model;
- truthful playback and MQA availability states;
- optional source-decoder interface with no proprietary implementation;
- deterministic exact-format USB mixer negotiator;
- Android 14+ controller using public configurable mixer attributes;
- exact preferred-mixer verification;
- unity AudioTrack volume;
- no DSP, EQ, limiter, fade, gain-ramp, crossfade, or loudness stage in the bit-perfect session;
- automatic preferred-mixer cleanup on close, failure, or USB disconnection;
- JVM tests for rejection and acceptance rules.

This checkpoint is a transport foundation, not a complete media-library/player UI. It does not yet parse media files, schedule PCM writes, manage a MediaSession, or prove output on a physical DAC.

## Public Android path

For Android 14 and newer:

1. Discover connected USB output devices.
2. Query `AudioManager.getSupportedMixerAttributes(device)`.
3. Require an `AudioMixerAttributes` entry whose format exactly matches decoded source sample rate, channel count, encoding, and channel mask.
4. Require `MIXER_BEHAVIOR_BIT_PERFECT`.
5. Call `setPreferredMixerAttributes()` using `USAGE_MEDIA`.
6. Read back `getPreferredMixerAttributes()` and reject the session when it does not match.
7. Build an `AudioTrack` with the exact mixer format, target the same USB device, and set software track volume to 1.0.
8. Re-check the actual AudioTrack format.
9. Clear preferred attributes on stop, failure, route loss, and USB disconnect.

Primary Android references:

- https://developer.android.com/reference/android/media/AudioManager#getSupportedMixerAttributes(android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo,android.media.AudioMixerAttributes)
- https://developer.android.com/reference/android/media/AudioManager#getPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioManager#clearPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioMixerAttributes#MIXER_BEHAVIOR_BIT_PERFECT

Only USB outputs are considered by this controller. The built-in speaker and Bluetooth routes are never labeled bit-perfect.

## Fidelity state machine

- `UNSUPPORTED_ANDROID_VERSION`: public mixer API unavailable.
- `NO_USB_OUTPUT`: no USB output connected.
- `NO_CONFIGURABLE_USB_MIXER`: USB output reports no configurable mixer attributes.
- `NO_EXACT_SOURCE_FORMAT`: a conversion would be required, so playback is rejected for bit-perfect mode.
- `NO_BIT_PERFECT_MIXER`: exact format exists only with default mixer behavior.
- `PREFERENCE_REJECTED`: Android rejected configuration or AudioTrack creation/routing.
- `PREFERENCE_NOT_ACTIVE`: readback or actual track format differs.
- `READY_BIT_PERFECT`: all public-API checks passed; physical DAC validation is still required before device-specific certification.

## Direct Player source pipeline

Planned dependency order:

1. Storage Access Framework source selection.
2. Container sniffing independent of filename extension.
3. WAV/PCM decoder and deterministic fixtures.
4. Platform MediaExtractor/MediaCodec lossless capability probe.
5. FLAC and ALAC support using only reviewed decoder paths.
6. Bounded decode queue feeding the USB AudioTrack writer.
7. Audio focus and MediaSession integration.
8. Gapless transitions that are disabled in bit-perfect mode unless the source boundary can be preserved without sample modification.
9. Route-change and hot-unplug state recovery.
10. User-visible capability and rejection explanations.

## Bit-perfect policy

A session is eligible only when all are true:

- source and selected mixer formats match exactly;
- DSP bypassed;
- EQ bypassed;
- limiter bypassed;
- gain ramps bypassed;
- fades/crossfades bypassed;
- loudness processing bypassed;
- track volume is unity;
- preferred mixer attributes are verified active.

The app must revoke the label immediately if any condition changes.

## Dolby Atmos coexistence

Direct USB bit-perfect mode intentionally requests no system or app processing. Samsung Dolby Atmos eligibility is preserved for the normal shared media/rootless DSP route, not guaranteed for a bit-perfect USB route. The UI must present these as mutually distinct playback goals rather than claiming simultaneous Atmos processing and unchanged samples.

## Native UAC backend boundary

A future Tier 2 backend is not implemented by this checkpoint. It must remain separately feature-flagged and labeled experimental until it provides:

- duplicated/owned USB file descriptor;
- UAC1/UAC2 descriptor parser;
- clock and alternate-interface controls;
- exact PCM negotiation;
- asynchronous isochronous OUT transfers;
- feedback endpoint handling;
- fractional 44.1 kHz packet scheduling;
- bounded transfer queues and starvation telemetry;
- hot-unplug cleanup and interface release;
- mocked descriptor and packet-schedule tests;
- per-DAC quirks without exposing device identity in default support bundles.

AAudio is not a custom USB Audio Class driver and must not be represented as one.

## Privacy

Default diagnostics may report capability counts, redacted formats, fidelity state, and failure category. They must not include song names, private content URIs, private filesystem paths, USB serial numbers, raw device names, or PCM data.

## Required physical validation

On the Galaxy S23 Ultra with an external USB DAC:

- connect/disconnect before and during playback;
- enumerate supported formats;
- test an exact 44.1 kHz and 48/96 kHz source where supported;
- confirm a mismatched source is rejected rather than resampled;
- confirm preferred attributes are cleared after stop and unplug;
- inspect AudioFlinger/audio policy evidence without committing raw identifiers;
- verify DAC sample-rate indicator where available;
- perform a digital loopback/hash comparison where hardware permits;
- compare long-play underruns, thermal state, and battery use.

Until that evidence exists, status is `CI validated` at most and `physical-device validation blocked`.
