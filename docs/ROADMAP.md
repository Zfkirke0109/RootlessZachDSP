# RootlessZachDSP roadmap

Status date: 2026-07-22. This file distinguishes merged, draft-branch, local-only,
and planned work so that an implementation is never mistaken for a released feature.

## Current delivery state

| Workstream | Evidence-backed state |
|---|---|
| Production `master` | Only the shared signing-workflow change is merged. There is no GitHub Release yet. |
| Rebrand, credit, startup identity fix | Implemented in draft PR #1 and verified on the S23 Ultra; not merged to `master`. |
| Partial reads/writes, telemetry, fail-open recovery | Implemented across draft PRs and exercised on the S23 Ultra at 48 kHz stereo. Current build has no startup crash; route/recovery stress coverage is still incomplete. |
| Structured diagnostics and source/headroom evidence | Implemented in green draft PRs #8, #10, and #11. Measurement ends at AudioTrack input; final system mix is explicitly not measured. |
| App capture allowlist UI | Integrated local work now uses the same inclusive UID policy for playback capture and muting-session admission, including an empty allowlist. Unit/build validation passes; S23 Ultra behavior is still unverified. |
| Direct Player and USB negotiation | Integrated local work adds FLAC, WavPack, correction-file truth states, and DSP staging. Lifecycle, fallback, early-EOF, metadata-budget, and diagnostics-isolation fixes compile and pass unit tests; USB hardware validation remains pending. |
| Release signing | Shared secret contract is merged, but the stacked feature branches still need reconciliation and signer-continuity validation. |

## Active stabilization gate

1. Preserve the integrated FLAC/WavPack/allowlist work on `codex/integrated-roadmap-debug-20260721` and open one draft PR to `master`.
2. Re-run lint after the latest safety fixes; unit tests and four-ABI native assembly currently pass locally.
3. Reconcile `.github/workflows/build.yml` with the merged `KEYSTORE_*` plus `EXPECTED_SIGNER_SHA256` contract and retain persistent debug signing.
4. Obtain a CI APK whose package, version, full signing certificate, 16 KiB alignment, and checksum are verified before updating the installed app.
5. Repeat speaker, Bluetooth, screen-lock, app-switch, allowlist, Direct Player, USB-route, and recovery tests on the Galaxy S23 Ultra.
6. Audit Diagnostics memory after the 200-event view; current device evidence shows high retained PSS but does not yet prove the owner.

## Open proof gaps

- The installed S23 Ultra build remains `b97bd21`; the new local debug APK has a different signer and must not replace it.
- `finalSystemMixMeasured=false` is intentional: AudioTrack input is measured, not the downstream Android/Samsung mix or DAC output.
- Direct USB mode still needs a real DAC capability matrix and routed-device evidence; no MQA decoding or unfolding is claimed.
- Non-seekable lossless documents are staged safely, but cache reuse/cancellation and repeated metadata loading remain optimization work.

## Feature roadmap

| Priority | Workstream | Status / next proof |
|---|---|---|
| P0 | Adaptive transport, deadline/underrun telemetry, crossfaded fail-open recovery | Implemented in drafts; stabilize shrink hysteresis and complete route/recovery device matrix. |
| P0 | Compatibility diagnostics and privacy-safe export | Implemented in drafts; fix optional-file noise and investigate event-view memory retention. |
| P0 | App allowlist/exclusion picker | Inclusive session-admission fix and tests implemented locally; verify matching-UID capture and no-silence behavior on device. |
| P0 | Secure signed releases, checksums, 16 KiB support | Workflow pieces exist; consolidate branches, prove signer continuity, then publish the first prerelease. |
| P1 | Native FLAC/WavPack Direct Player and truthful USB mode | Local build/tests pass; complete lifecycle/failure stress, memory profiling, and USB DAC capability proof. |
| P1 | DynamicsProcessing fallback for capture-blocked apps | Planned; must be session-scoped and mutually exclusive with full capture DSP. |
| P1 | App-plus-device rules and Tasker/MacroDroid intents | Planned; document actions, permissions, priority, and state broadcasts. |
| P1 | Pre/post spectrum, peak/true-peak, LUFS, and gain-reduction meters | Planned; bound refresh rate and memory/CPU cost. |
| P1 | Relative equal-loudness and independent L/R EQ, gain, delay, balance | Planned; profile-scoped with conservative gain ramps. |
| P1 | Ordered DSP graph, multiple LiveProg blocks, convolution overhaul | Planned; requires watchdogs, timing budgets, partitioning, caching, and crossfades. |
| P2 | Equalizer APO/AutoEQ interchange and local compatibility database | Planned; preview unsupported filters and keep contributions opt-in/anonymized. |
| P2 | Pitch/time and separate microphone mode | Experimental; microphone mode requires a distinct permission, routing, and privacy design. |
| Research | Legal MQA handling | Research/capability-discovery only. Do not claim decoding, unfolding, or licensed support without an authorized implementation and hardware evidence. |
| Tooling | PowerShell/ADB device-information and repeatable validation scripts | Planned; produce privacy-scoped captures with package/build/route/telemetry identity. |

Detailed acceptance criteria remain in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md),
with the expanded feature design in [FEATURES_20_ROADMAP.md](FEATURES_20_ROADMAP.md).
