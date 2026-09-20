# RootlessZachDSP roadmap

Status date: 2026-09-20. PR #12 is the active integration, ready for review; master contains the shared signing change. Nothing here implies a release or physical validation of the integrated head.

## Current delivery state

| Workstream | Evidence-backed state |
|---|---|
| Integration | Master signing commit reconciled into PR #12; stacked draft history retained. |
| Capture, targeting and telemetry | Implemented in PR #12; September log shows idle/zero-frame behavior that still needs active-source investigation. |
| Startup and session lifecycle | Cleanup ownership/order, coalesced IO polling, late-result rejection and session fallback changes implemented; current checks tracked in the PR. |
| Convolution | Missing/empty/corrupt IR status and decoded-data validation implemented; valid-IR device exercise pending. |
| Direct Player | FLAC/WavPack, correction-file states, USB negotiation and SAF folder browsing implemented in PR #12; Samsung provider and USB hardware proof pending. |
| Signing | Shared secret contract reconciled; CI checks persistent certificate for trusted push artifacts. Installed-device certificate still needs comparison. |

## Active stabilization gate

1. Complete CI on the integrated source: tests, rootless assembly, root Kotlin compilation, lint, emulator and APK verification.
2. Retain a trusted-push artifact with package, version, full certificate, alignment and checksum evidence.
3. Follow the [Android 17 / One UI 9.0 device checklist](device-validation/S23_ULTRA_ANDROID17_20260920.md), including capture, IR, WavPack intake and USB routing.
4. Complete the authorized Copilot review remediation on PR #12, validate the updated head and obtain follow-up review. Merge and release remain on hold.
5. Reconcile distinct PR #4 startup/runtime underrun work in a focused follow-up; preserve the newer baseline/percentile implementation.

## Open proof gaps

- The September log reports `f61a292` but contains later startup text; clean build provenance is unresolved for that old installed artifact.
- API 35 emulator success cannot certify Samsung Android 17. July Android 16 evidence remains historical.
- `finalSystemMixMeasured=false` is intentional: AudioTrack input is measured, not the downstream Android/Samsung mix or DAC output.
- Direct USB needs real capability and routed-device evidence. MQA remains research only.
- Non-seekable documents use bounded staging, but cancellation/cache reuse and repeated metadata-load optimization remain follow-up work.
- Event-view memory retention remains an investigation; no owner has been proven.

## Feature roadmap

| Priority | Workstream | Status / next proof |
|---|---|---|
| P0 | Adaptive transport, deadline/underrun telemetry, crossfaded fail-open recovery | Implemented in drafts; stabilize shrink hysteresis and complete route/recovery device matrix. |
| P0 | Compatibility diagnostics and privacy-safe export | Implemented in drafts; fix optional-file noise and investigate event-view memory retention. |
| P0 | App allowlist/exclusion picker | Inclusive session-admission fix and tests present in PR #12; verify matching-UID capture and no-silence behavior on device. |
| P0 | Secure signed releases, checksums, 16 KiB support | Reconciled workflow; prove installed signer continuity and pass review before planning a prerelease. |
| P1 | Native FLAC/WavPack Direct Player and truthful USB mode | Implemented in PR #12; complete current CI, provider/lifecycle stress and USB DAC capability proof. |
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
