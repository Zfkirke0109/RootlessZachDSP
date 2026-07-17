# Galaxy S23 Ultra long-run diagnostics analysis — 2026-07-17

## Build and device

- Application ID: `com.zfkirke0109.rootlesszachdsp.debug`
- Version: `2.0.0-alpha01-1041`
- Reported commit: `edde516`
- Device: Samsung Galaxy S23 Ultra (`SM-S918U1`, `dm3q`)
- Android: API 36 / Android 16
- Output format: 48,000 Hz, stereo
- HAL burst: 192 frames

## Full-run cumulative evidence

The exported compatibility summary reported:

- `readSamples=881174784`
- `writtenSamples=881171712`
- `bufferSamples=3072`
- `ioErrors=0`
- `partialRead=0`
- `partialWrite=0`
- `zeroProgress=0`
- `recoveries=3`
- `reconfigurations=1`
- `underruns=4`
- `deadlineMisses=24`
- `bypassBuffers=0`
- `maxProcessNs=72961719`

At 48 kHz stereo, the read-sample total represents approximately 9,178.9 seconds, or 2 h 32 m 59 s, of transported audio. The final read/write difference is exactly one 3,072-sample block and alternated between zero and one block in the retained window, which is consistent with a snapshot taken while one block was in flight rather than evidence of accumulating sample loss.

The most recent recovery and reconfiguration were both more than 2.5 hours old at export. They therefore occurred near startup or an early route/session transition, not during the retained steady-state window.

## Retained rotating-window evidence

The export contained 1,000 structured events from one engine epoch:

- 331 transport snapshots
- 664 signal snapshots, split evenly across both measurement boundaries
- 5 discrete deadline-miss events
- Window duration: 1,626.962 seconds (27.18 minutes)
- Audio represented by read-sample delta: 1,626.88 seconds

During this retained window:

- no new underruns;
- no recoveries;
- no reconfigurations;
- no I/O errors;
- no bypass buffers;
- five isolated deadline misses;
- buffer size remained 3,072 samples.

A 3,072-sample stereo block at 48 kHz is 32 ms. This is 5.33 times smaller than the earlier 16,384-sample block (170.67 ms), an 81.25% reduction in block duration. The retained transport snapshots showed sampled processing-time values of approximately:

- median: 11.40 ms;
- p95: 22.62 ms;
- p99: 26.03 ms;
- maximum sampled value: 33.07 ms.

These are percentiles of five-second snapshots of `lastProcessingNanos`, not true per-buffer percentiles. The follow-up implementation adds a preallocated per-buffer timing ring and computes p50/p95/p99 only on the off-thread snapshot worker.

## Signal-path proof

Both signal boundaries were populated:

1. `CAPTURED_INPUT_TO_DSP_ENGINE_OUTPUT`
2. `CAPTURED_INPUT_TO_AUDIO_TRACK_INPUT`

The retained active-audio snapshots reported `outputChanged=true` and changed-sample ratios at or extremely close to 1.0. This closes the earlier `prePostSignal=not-connected-yet` blocker for this build: the enabled DSP changed samples, and the final post-crossfade/gain buffer submitted to `AudioTrack` was also measured as changed.

This does **not** prove the final Samsung system mix or external acoustic/DAC output. The report correctly retains `finalSystemMixMeasured=false` because Samsung system effects, route processing, and hardware behavior occur after the app's `AudioTrack` boundary.

## Headroom observation

No clipped samples were recorded. However, the DSP/AudioTrack output repeatedly reached approximately `0.9772373`, which is about -0.20 dBFS. In the retained window, roughly one quarter of snapshots reached that ceiling. This is evidence that the limiter ceiling is active, not evidence of digital clipping. External true-peak or DAC loopback validation is still required before claiming inter-sample headroom.

## Decision

- Keep 3,072 samples as the current steady-state target; do not regress to 16,384 solely because of sparse isolated deadline misses.
- Add true per-buffer p50/p95/p99 timing and maximum consecutive deadline-miss streaks.
- Correct the export's `recentEventCount` so it matches the 1,000 events actually attached to an export.
- Correct the Diagnostics screen so it reports the connected AudioTrack-input signal boundary instead of a hardcoded `finalAudioTrackMixMeasured=false`.
- Keep physical speaker, Bluetooth, USB, route-change, lock-screen, thermal, and final-system-output validation open.

No raw PCM, song title, package identity, route product name, path, URI, or other private media content is included in this evidence document.
