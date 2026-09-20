# Pluto device trace analysis — 2026-07-15

## Evidence and privacy

Source evidence: `pluto_share.txt`, exported from the debug Pluto logger after exercising RootlessZachDSP on a Galaxy S23 Ultra. The raw trace remains outside the repository. Only aggregate measurements and a sanitized AudioFlinger parser fixture are committed.

No private file paths, song titles, notification text, device serials, ADB endpoints, or signing material are included here.

## Measured transport result

The trace covers approximately 179 seconds and contains 172 `RZDSP_TELEMETRY` snapshots.

| Metric | Result |
|---|---:|
| Sample rate | 48,000 Hz |
| Channels | 2 |
| Final samples read | 16,982,016 |
| Final samples written | 16,982,016 |
| Partial read operations | 0 |
| Partial write operations | 0 |
| Zero-progress operations | 0 |
| I/O errors | 0 |
| Recoveries | 1 |
| Underruns | 2 |
| DSP deadline misses | 0 |
| Fail-open bypass buffers | 0 |
| Approx. median DSP time | 7.9 ms |
| Approx. p95 DSP time | 29.4 ms |
| Maximum observed DSP time | 49.4 ms |
| Approx. mean load EWMA | 0.12 |
| Maximum observed load EWMA | 0.32 |

The read and write totals stayed equal. The trace does not show transport loss, repeated zero progress, an I/O failure, a DSP deadline miss, or fail-open bypass activation.

The only recovery was caused by a capture-app selection change. The two underruns require route/startup correlation in a follow-up capture, but they did not coincide with a transport failure in this trace.

## Session-discovery findings

The trace correctly discovered a normal media/music session for the test music player. It also discovered RootlessZachDSP-created sessions with unknown usage.

Two parser defects were confirmed:

1. `AudioServiceDumpProvider` treated PID-to-session ownership as one-to-one. Android may legitimately expose several session IDs for a single process, so repeated PIDs generated false `Duplicated PID` warnings and the previous map overwrote earlier sessions.
2. Android 16 / One UI 8.5 AudioFlinger table version 30 was followed by `mSystemReady=1`. The parser treated this recognized service-property metadata as an unmatched table row.

Implemented corrections:

- PID lookup now preserves `PID -> Set<SessionId>`.
- API 29/30 fallback resolves only a PID with one unambiguous session candidate.
- Exact AudioFlinger rows are deduplicated.
- AudioFlinger property lines such as `mSystemReady=1` end the table without a false warning.
- A sanitized v30 fixture and unit tests cover the observed structure.

## Settings finding

`SettingsActivity` and its preference fragments were present. The access icon was hosted in a custom `ActionMenuView` inside the bottom app bar, which was not reliably visible on the test Samsung layout. The first correction moves that existing action into the top app bar while retaining the existing Settings activity.

Physical-device validation is still required before calling the UI correction complete.

## What this trace proves

- The rootless capture/processing/output loop moved audio continuously.
- Transport counters remained internally consistent.
- The adaptive transport did not enter repeated error recovery or fail-open bypass.
- The app-selection change triggered one controlled rebuild.
- The measured DSP workload had substantial headroom during this run.

## What this trace does not prove

The present transport telemetry cannot prove:

- which app was effectively captured for every buffer;
- which output route was active;
- which DSP modules and parameters were active;
- whether processed samples differed from dry input;
- input/output RMS, peak, true-peak, LUFS, clipping, or gain reduction;
- per-module CPU time;
- whether an underrun was audible;
- behavior during Bluetooth, USB, screen lock, route switching, or process recreation.

These gaps are addressed by `AUDIO_DIAGNOSTICS_SCHEMA.md` and the staged implementation roadmap.

## Validation gate before merging PR #1

PR #1 must remain unmerged until a fresh APK is installed on the physical Galaxy S23 Ultra and verifies:

- a reliable visible Settings entry;
- correct media-session selection and self-session exclusion;
- no false duplicate-PID warnings;
- clean AudioFlinger v30 parsing;
- speaker and Bluetooth route behavior;
- screen-lock/background continuity;
- selected-app changes and recovery;
- no prolonged silence after a recoverable event;
- privacy-safe diagnostics export.
