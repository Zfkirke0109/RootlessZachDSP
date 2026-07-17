# Direct Player and USB bit-perfect architecture

## Why this is separate from system-wide DSP

The existing rootless path is:

`Android playback capture -> RootlessZachDSP processing -> AudioTrack -> Samsung system policy`

That path is intentionally not bit-perfect because capture, DSP, crossfades, gain ramps, AudioTrack, and the downstream platform mix can change samples. The Direct Player owns the original source and creates a separate player route. It does not pretend to make another app's playback bit-perfect.

## Tier 1: Android 14+ public USB mixer path

Implementation components:

- `SourceAudioInspector`: reads redacted technical metadata from a selected document; it never records PCM or private paths.
- `OptionalSourceDecoder`: isolated source-decoder boundary. The FOSS build registers no proprietary MQA implementation.
- `UsbBitPerfectFormatMatcher`: requires exact sample rate, channel count, PCM encoding, and `MIXER_BEHAVIOR_BIT_PERFECT`.
- `UsbBitPerfectController`: enumerates USB output devices and their public mixer attributes, requests the selected preference, reads it back for verification, and clears it on stop, failure, or disconnect.
- `DirectPlayerActivity`: source picker and Media3 player with unity player volume and truthful status.

Android references:

- https://developer.android.com/reference/android/media/AudioManager#getSupportedMixerAttributes(android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioManager#setPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo,android.media.AudioMixerAttributes)
- https://developer.android.com/reference/android/media/AudioManager#clearPreferredMixerAttributes(android.media.AudioAttributes,android.media.AudioDeviceInfo)
- https://developer.android.com/reference/android/media/AudioMixerAttributes#MIXER_BEHAVIOR_BIT_PERFECT

Only USB devices are guaranteed by Android to expose configurable mixer attributes. The API does not replace the Samsung kernel or audio HAL.

## Current claim boundary

### Ordinary Direct Player playback

Supported through Media3/platform decoders when available:

- FLAC
- WAV / PCM
- ALAC in a supported MP4/M4A container when the device decoder supports it
- other local audio supported by Media3 and the device

A source may be described as high-resolution lossless when its format metadata establishes a known lossless codec and sample rate above 48 kHz or bit depth above 16 bits.

### Public USB mixer preference verified

The current checkpoint enables this status only when:

1. Android 14 or newer is running.
2. An external USB audio output is connected.
3. Android reports configurable mixer attributes.
4. A bit-perfect mixer is reported.
5. The source is already known linear PCM.
6. Sample rate, channel count, and PCM encoding match exactly.
7. `setPreferredMixerAttributes` succeeds.
8. `getPreferredMixerAttributes` returns the same attributes.
9. Player volume remains unity.
10. RootlessZachDSP effects, fades, limiter, gain ramps, and loudness processing are not inserted into this separate path.

The UI still states that external DAC/loopback validation is needed before claiming end-to-end bit identity. A public mixer preference verifies Android's selected mixer behavior; it does not prove every external DAC implementation.

### Compressed lossless source

FLAC and ALAC are decoded to PCM before output. The source container metadata alone does not prove the exact PCM encoding sent to AudioTrack. The current checkpoint therefore allows high-resolution lossless playback but rejects a bit-perfect claim with `SOURCE_NOT_LINEAR_PCM`. A future sink-format hook must verify the actual decoder output before enabling the exact USB preference.

## Hot unplug and rollback

The activity registers an `AudioDeviceCallback`. When the selected USB route disappears:

- preferred mixer attributes are cleared;
- the active session object is released;
- the UI disables the bit-perfect switch;
- playback is rebuilt using ordinary shared platform routing;
- no ADB or Shizuku setting remains to undo.

The same clear operation runs during activity destruction and explicit disable.

## Privacy

Default output contains only:

- generic USB output numbering;
- reported sample rate, channel count, encoding constant, and behavior;
- acceptance or rejection reason.

It excludes raw PCM, song names, document paths, USB serial numbers, package identities, notifications, and uploaded diagnostics.

## Tier 2: optional native UAC1/UAC2 backend

Tier 2 is not implemented and must not be represented as a working custom driver. The future module boundary must include:

- duplicated file-descriptor ownership;
- descriptor parsing;
- UAC1 and UAC2 clock controls;
- alternate-interface and PCM-format negotiation;
- isochronous OUT transfer pools;
- asynchronous feedback endpoint handling;
- fractional 44.1 kHz packet scheduling;
- queue-starvation telemetry;
- hot unplug and interface release;
- per-DAC quirk tables;
- mocked descriptor and packet-scheduler tests.

AAudio is not a substitute for this custom USB Audio Class sink. AAudio remains a platform/HAL output API.

## Physical acceptance matrix

Required on the Galaxy S23 Ultra:

1. No USB DAC: exact rejection reason, ordinary player remains functional.
2. USB DAC with no configurable mixers: no false bit-perfect status.
3. USB DAC with configurable default mixers only: no false bit-perfect status.
4. Exact 44.1/16 PCM match.
5. Exact 48/24 PCM match where supported.
6. Intentional sample-rate mismatch: rejection, no resampling claim.
7. FLAC/ALAC: high-resolution lossless status where appropriate, no current bit-perfect claim.
8. Start, pause, resume, route change, screen lock, background, and thermal run.
9. Hot unplug while stopped and while playing.
10. Confirm preferred attributes are cleared after every stop/failure/disconnect.
11. Compare Samsung Dolby Atmos off/on. Direct USB may bypass or alter system-effect placement; no coexistence promise is made.
12. External DAC display or digital loopback validation before end-to-end bit-perfect acceptance.
