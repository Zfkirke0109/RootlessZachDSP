# RootlessZachDSP agent handoff

Any coding agent may continue from a normal checkout of `develop/rootless-zach-foundation`. Do not rely on chat history.

## Repository state

- Repository: `Zfkirke0109/RootlessZachDSP`
- Branch: `develop/rootless-zach-foundation`
- Pull request: #1, open, mergeable, **draft**
- Base: `master`
- Last fully CI-verified code head: `9402d249d0f2e111ce0661d30bfaa3ba71182a54`
- Do not merge without Zach's explicit approval.

## Last fully CI-verified checkpoint

Commit `9402d249d0f2e111ce0661d30bfaa3ba71182a54` completed `RootlessZachDSP Android CI` run `29476851457` (run #136) successfully.

That run completed:

- deterministic audio-transport and JVM unit tests;
- installable debug APK builds;
- APK identity, signature, 16 KiB alignment, and checksum verification;
- Android lint and report collection;
- artifact upload.

Test-only debug artifact:

- name: `RootlessZachDSP-debug-test-only-168cb8e8a5faf0785b9813947b508c3f4bb8f848`
- artifact ID: `8367032751`
- archive size: `108163599` bytes
- archive digest: `sha256:42f58ad32f510751823b9e87a70ba35e83a3a574f84a14de51bf7825810ae511`
- workflow source head: `9402d249d0f2e111ce0661d30bfaa3ba71182a54`

The signed-release job was skipped, as expected for a pull-request build. This artifact is debug/test-only and does not prove production signing.

## Work completed after the original foundation checkpoint

### Settings access

- `689c7c0` moved the existing Settings action from the bottom app bar into the top app bar.
- `f9804a6` added accessibility text and overflow fallback behavior.
- `SettingsActivity` and existing preference fragments remain the target; they were not recreated.
- `3380254`, `406071e`, and `f3de364` add a rootless-only Diagnostics entry under regular Settings with View, Copy, Preview/Export, and Clear actions.
- `04423ed` adds latest pre/post status fields to the Diagnostics summary when signal telemetry is available.
- Physical-device confirmation of visibility and navigation is still required.

### Session parser corrections

- `77d1197` recognizes Android 16 / One UI AudioFlinger v30 property metadata such as `mSystemReady=1` and deduplicates exact rows.
- `fa49690` replaces the one-to-one PID/SID fallback with `PID -> Set<SessionId>` and refuses ambiguous API 29/30 fallback.
- Sanitized fixture: `app/src/test/resources/session_dump/audio_flinger_v30_pluto_sanitized.txt`.
- Tests cover v30 parsing/deduplication, v29 compatibility, recognized metadata, multimap preservation, and ambiguous fallback refusal.

### Telemetry and persistent diagnostics

- `2a83408` timestamps recoveries and stops repeating an old recovery reason indefinitely.
- `7464126` tests fresh versus stale recovery reporting.
- `9540f88` adds an app-private rotating JSONL store with a 5 MiB active-file default and one rotated generation.
- `be526f8` adds schema-versioned transport/anomaly JSON encoding.
- `f7721f1` moves JSON serialization and file writes to a dedicated diagnostics thread.
- `87de5f1` requests an off-thread flush when recovery, underrun, deadline-miss, I/O-error, or bypass counters increase, while retaining approximately five-second periodic snapshots.
- `155c0df` adds structured-store status to the redacted compatibility report.
- `0099f34` and `b66a2f3` add rotation, clearing, normalization, escaping, schema, and event tests.

### Pre/post signal foundation

- `7eaebc4`, `30ebd96`, and `163d0ad` add a no-PCM aggregate pre/post signal accumulator for float and 16-bit PCM.
- It computes sample count, RMS, peak, DC offset, silence ratio, clipped samples, changed-sample ratio, and session-salted rolling hashes.
- Recording acquires one lock per audio buffer, not per sample.
- `357345c`, `eb3547b`, and `52cb530` add schema-versioned `SIGNAL_SNAPSHOT` encoding and persistence support.
- JVM tests cover identical bypass, changed output, clipping, short PCM, non-finite input handling, reset, and privacy-safe JSON.
- **Important:** the accumulator and persistence path are implemented and tested, but the live service has not yet been wired to call `recordFloat`/`recordShort` or `publishSignal`. Diagnostics correctly shows `prePostSignal=not-connected-yet` until that integration is made.

### Privacy gate

- `68f553e` redacts output-device names and selected raw UIDs by default in support reports.
- `5ee7a59` and `d570a8b` add a final leak scanner for content/file URIs, Android/private paths, Windows user paths, secret assignments, device serials, and ADB/network endpoints.
- `f43ca76` and `9402d24` block clipboard/export sharing when the privacy scan finds a prohibited value and display only categories/counts, not the sensitive value itself.

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

1. Confirm the current branch head and CI because this handoff documentation commit follows the verified code checkpoint.
2. Wire `AudioSignalTelemetry` into `RootlessAudioProcessorService` at the dry-input versus final-mixed-output boundary for both float and 16-bit paths.
3. Call `RootlessZachDiagnostics.publishSignal(signalTelemetry.snapshot())` only at the existing telemetry boundary, not every buffer.
4. Start a new signal accumulator/seed and call `RootlessZachDiagnostics.beginEngineEpoch()` for each service/engine lifetime.
5. Add typed session-set and route/preset/module events.
6. Add deterministic integration tests proving bypass equality and active-DSP difference.
7. Add the offline native DSP self-test in a separate measured checkpoint.
8. Add instrumentation tests for Settings and Diagnostics navigation.
9. Install the resulting APK on the Galaxy S23 Ultra and validate Settings, Amazon Music session selection, self-session exclusion, AudioFlinger parsing, speaker/Bluetooth/USB routes when available, screen lock, and recovery.

## Important code locations

- Rootless service: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/service/RootlessAudioProcessorService.kt`
- Transport telemetry: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/transport/AudioTransportTelemetry.kt`
- Signal telemetry: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/audio/transport/AudioSignalTelemetry.kt`
- Diagnostics bridge/store writer: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/RootlessZachDiagnostics.kt`
- JSON encoder: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/AudioDiagnosticJson.kt`
- Rotating store: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/RotatingJsonlStore.kt`
- Leak scanner: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/diagnostics/DiagnosticsLeakScanner.kt`
- Diagnostics Settings UI: `app/src/main/java/me/timschneeberger/rootlessjamesdsp/fragment/settings/SettingsDiagnosticsFragment.kt`
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
- unredacted package/profile identities in default support bundles;
- user-customized output-device names without explicit opt-in.

## Completion definition for the current checkpoint

The foundation remains draft until:

- Settings is visibly reachable on the physical phone;
- normal media sessions are selected correctly;
- RootlessZachDSP's own sessions are excluded;
- false duplicate-PID warnings are gone;
- AudioFlinger v30 parses cleanly;
- structured diagnostics persist safely on-device;
- the live service feeds pre/post signal telemetry and proves whether DSP changed the signal;
- route/background/recovery validation shows no prolonged silence;
- exported evidence passes privacy review.
