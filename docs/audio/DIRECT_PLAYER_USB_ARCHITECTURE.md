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
- exact preferred-mixer request and readback confirmation;
- unity AudioTrack volume with a rejected `setVolume(1.0f)` result treated as session failure;
- no DSP, EQ, limiter, fade, gain-ramp, crossfade, or loudness stage in the bit-perfect session;
- PCM/WAV writes through the configured AudioTrack;
- post-write `AudioTrack.getRoutedDevice()` observation, reported separately from the Android mixer contract;
- automatic preferred-mixer cleanup on close, failure, or USB disconnection;
- JVM tests for rejection and acceptance rules.

This checkpoint includes a small source-owning player, but it is not a complete media library and
does not prove unchanged output at a physical DAC. Compressed FLAC/ALAC ordinary playback and exact
PCM/WAV direct playback remain distinct paths. No physical bit-perfect claim is made from Android
API state alone.

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
9. After writes begin, compare `AudioTrack.getRoutedDevice()` with the selected USB device. A non-null different route fails the direct session; a null route remains pending before first confirmation and fails the session if a previously confirmed route disappears.
10. Continue observing the route during writes and clear preferred attributes on stop, failure, route loss, and USB disconnect.

Primary Android references:

- https://developer.android.com/reference/android/media/AudioManager#getSupportedMixerAttributes(android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo,android.media.AudioMixerAttributes)
- https://developer.android.com/reference/android/media/AudioManager#getPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioManager#clearPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioMixerAttributes#MIXER_BEHAVIOR_BIT_PERFECT
- https://developer.android.com/reference/android/media/AudioTrack#getRoutedDevice()

Only USB outputs are considered by this controller. The built-in speaker and Bluetooth routes are
never eligible for this direct mode. A selected USB route is still not called externally verified
without independent DAC or digital-loopback evidence.

## Fidelity state machine

- `UNSUPPORTED_ANDROID_VERSION`: public mixer API unavailable.
- `NO_USB_OUTPUT`: no USB output connected.
- `NO_CONFIGURABLE_USB_MIXER`: USB output reports no configurable mixer attributes.
- `NO_EXACT_SOURCE_FORMAT`: a conversion would be required, so playback is rejected for bit-perfect mode.
- `NO_BIT_PERFECT_MIXER`: exact format exists only with default mixer behavior.
- `PREFERENCE_REJECTED`: Android rejected configuration or AudioTrack creation/routing.
- `PREFERENCE_NOT_ACTIVE`: readback or actual track format differs.
- `ELIGIBLE`: source and advertised USB mixer format match exactly and the candidate advertises `MIXER_BEHAVIOR_BIT_PERFECT`; nothing has been configured yet.
- `ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE`: Android accepted the preference, read it back, created an exact-format AudioTrack, and accepted the preferred USB device. Playback/routed-device evidence is still pending.
- `ROUTED_DEVICE_CONFIRMED`: after a successful write, the playing AudioTrack reported the selected USB output from `getRoutedDevice()`. This is Android routing evidence, not physical-output proof.
- `EXTERNALLY_VERIFIED`: a digital-loopback PCM hash or independently captured USB payload hash matched for the tested device/format/build. A DAC rate indicator alone is insufficient. Production code never advances to this tier without a structured evidence reference.

These tiers are cumulative. The pure evidence model refuses to skip a lower tier, so an external
evidence reference cannot yield `EXTERNALLY_VERIFIED` without eligibility, the Android contract,
and routed-device confirmation.

## Direct Player source pipeline

Current data flow:

1. Storage Access Framework source selection.
2. `MediaExtractor` inspection of the selected source.
3. Ordinary Media3 playback for supported compressed lossless sources; this path makes no direct or bit-perfect claim.
4. Exact mode only when inspection exposes an `audio/raw` PCM track with a known mono/stereo output mask and PCM encoding.
5. Android 14+ USB mixer negotiation and exact-format AudioTrack creation.
6. Bounded PCM reads and blocking writes with continuous non-null route-mismatch rejection.
7. Preferred-mixer cleanup on completion, failure, user stop, or USB removal.

Still required for a complete player: reviewed direct FLAC/ALAC decoder output, broader channel-layout
support, AudioFocus/MediaSession integration, gapless transition rules, and device-backed validation.
None of those capabilities are inferred from ordinary Media3 playback.

## Bit-perfect policy

The source path is eligible only when all are true:

- source and selected mixer formats match exactly;
- DSP bypassed;
- EQ bypassed;
- limiter bypassed;
- gain ramps bypassed;
- fades/crossfades bypassed;
- loudness processing bypassed;
- track volume is unity;
- the selected mixer advertises an exact format and bit-perfect behavior.

Android mixer-contract activation, AudioTrack routed-device confirmation, and external verification
are higher and separately displayed evidence tiers. The app must revoke or fail the direct path
when an observable condition changes. Route confirmation never substitutes for external proof.

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
- record the DAC sample-rate indicator where available as corroboration only, never as bit-perfect proof;
- perform a digital loopback/hash comparison where hardware permits;
- compare long-play underruns, thermal state, and battery use.

Until that evidence exists, the highest app-observable status is
`ROUTED_DEVICE_CONFIRMED`; `EXTERNALLY_VERIFIED` remains unavailable.
