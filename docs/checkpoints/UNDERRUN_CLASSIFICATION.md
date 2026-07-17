# Underrun classification checkpoint

Branch: `develop/perf-underrun-classification`
Parent: `perf/realtime-audio-diagnostics` at `301ec674b2acb8012cde72c8563c359c74f74549`
Draft PR: #4

## Purpose

Separate AudioTrack underruns observed while a newly created track is still being primed from underruns that occur after the current track has received at least two complete application buffers.

## Counters

- `underruns`: backwards-compatible service-epoch total.
- `activeTrackUnderruns`: current AudioTrack generation's monotonic platform counter.
- `startupPrimingUnderruns`: epoch total observed before two current-track buffers were written.
- `runtimeStarvationUnderruns`: epoch total observed after the priming threshold.
- `activeTrackWrittenSamples`: current-generation progress used for classification.
- `trackGeneration`: incremented whenever the AudioRecord/AudioTrack pipeline is configured.

The classification is diagnostic evidence, not proof of the exact downstream AudioFlinger cause. Physical validation on the Galaxy S23 Ultra remains required.

## Privacy

Only aggregate counters are stored. No PCM, media titles, output-device names, package identities, paths, or notification content are introduced.

## Validation

CI must run:

- `testRootlessFdroidDebugUnitTest`
- `assembleRootlessFdroidDebug`
- APK package/signature/16 KiB alignment/SHA-256 verification
- `lintRootlessFdroidDebug`

Physical validation must compare a fresh service start, expected route/policy reconfiguration, and sustained playback long enough to determine whether any `runtimeStarvationUnderruns` increase.
