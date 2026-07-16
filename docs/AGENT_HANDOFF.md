# RootlessZachDSP agent handoff

Any coding agent may continue from a normal checkout of `develop/rootless-zach-foundation`. Do not rely on chat history.

## Repository state

- Repository: `Zfkirke0109/RootlessZachDSP`
- Branch: `develop/rootless-zach-foundation`
- Pull request: #1, open, mergeable, **draft**
- Base: `master`
- Do not merge without Zach's explicit approval.

## Last fully CI-verified code checkpoint

Commit `74641264a4771b72c9f500f2a9a515201beb3dcc` completed `RootlessZachDSP Android CI` run `29474570643` successfully.

That run completed:

- deterministic audio-transport/unit tests;
- installable debug APK builds;
- APK identity/signature/alignment/checksum verification;
- Android lint and report upload;
- artifact upload.

Later commits add documentation only through `a3dc502859934e988727cfd48c4b13f01093a1b7`; confirm the current branch head and its CI before continuing.

## Work completed after the original foundation checkpoint

### Settings access

- `689c7c0` moved the existing Settings action from the bottom app bar into the top app bar.
- `f9804a6` added accessibility text and overflow fallback behavior.
- `SettingsActivity` and existing preference fragments remain the target; they were not recreated.
- Physical-device confirmation of visibility and navigation is still required.

### Session parser corrections

- `77d1197` recognizes Android 16 / One UI AudioFlinger v30 property metadata such as `mSystemReady=1` and deduplicates exact rows.
- `fa49690` replaces the one-to-one PID/SID fallback with `PID -> Set<SessionId>` and refuses ambiguous API 29/30 fallback.
- Sanitized fixture: `app/src/test/resources/session_dump/audio_flinger_v30_pluto_sanitized.txt`.
- Tests cover v30 parsing/deduplication, v29 compatibility, recognized metadata, multimap preservation, and ambiguous fallback refusal.

### Telemetry correction

- `2a83408` timestamps recoveries and stops repeating an old recovery reason indefinitely.
- `7464126` tests fresh versus stale recovery reporting.

### Device evidence documentation

- `docs/device-validation/PLUTO_20260715_ANALYSIS.md`
- `docs/device-validation/AUDIO_DIAGNOSTICS_SCHEMA.md`
- `docs/FEATURES_20_ROADMAP.md`

The raw Pluto trace is not committed. Only aggregate measurements and sanitized fixture data are in the repository.

## Pluto trace result

Approximate measured result from the 2026-07-15 Galaxy S23 Ultra trace:

- 179 seconds, 172 transport snapshots;
- 48 kHz stereo;
- 16,982,016 samples read and written;
- zero partial reads/writes;
- zero zero-progress operations;
- zero I/O errors;
- zero DSP deadline misses;
- zero bypass buffers;
- one recovery caused by app-selection change;
- two underruns;
- median DSP time about 7.9 ms, p95 about 29.4 ms, maximum 49.4 ms.

The trace proves stable transport but not yet pre/post DSP signal change.

## Immediate continuation order

1. Confirm current branch head, CI, and PR state.
2. Build the first app-private rotating JSONL transport diagnostics store described in `AUDIO_DIAGNOSTICS_SCHEMA.md`.
3. Keep file I/O and JSON serialization off the urgent audio thread.
4. Emit discrete recovery/underrun/error events and approximately five-second periodic snapshots.
5. Add unit tests for JSON escaping, rotation, event deltas, and write-failure tolerance.
6. Add Settings > Diagnostics UI with View/Clear/Copy/Preview/Export only after the store is stable.
7. Add pre/post signal accumulators and offline DSP self-test in a separate measured checkpoint.
8. Install the resulting APK on the Galaxy S23 Ultra and validate Settings, Amazon Music session selection, self-session exclusion, AudioFlinger parsing, routes, screen lock, and recovery.

## Important code locations

- Rootless service: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/service/RootlessAudioProcessorService.kt`
- Transport telemetry: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/transport/AudioTransportTelemetry.kt`
- Diagnostics bridge: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/RootlessZachDiagnostics.kt`
- Compatibility report: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/CompatibilityDiagnosticsReport.kt`
- Audio service parser: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/session/dump/provider/AudioServiceDumpProvider.kt`
- AudioFlinger parser: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/session/dump/utils/AudioFlingerServiceDumpUtils.kt`
- Main UI: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/activity/MainActivity.kt`
- Main layout: `app/src/main/res/layout/activity_dsp_main.xml`

## Required checks

Run the repository's current CI-equivalent tasks and record exact results. At minimum preserve:

- unit tests;
- debug APK assembly;
- APK package/version/signature/alignment/SHA-256 verification;
- lint;
- instrumentation tests when a device is available.

Release signing secrets and keystore recovery files must never be committed. PR builds do not prove a production-signed release.

## Privacy constraints

Never commit or export:

- raw audio/PCM;
- song or notification content;
- private file paths or content URIs;
- device serials or ADB endpoints;
- signing keys, passwords, tokens, or secret values;
- unredacted package/profile identities in default support bundles.

## Completion definition for the current checkpoint

The foundation remains draft until:

- Settings is visibly reachable on the physical phone;
- normal media sessions are selected correctly;
- RootlessZachDSP's own sessions are excluded;
- false duplicate-PID warnings are gone;
- AudioFlinger v30 parses cleanly;
- structured diagnostics persist safely;
- pre/post evidence can prove whether DSP changed the signal;
- route/background/recovery validation shows no prolonged silence;
- exported evidence passes privacy review.
