# RootlessZachDSP agent handoff

Status: 2026-09-20. Continue from `codex/integrated-roadmap-debug-20260721`, draft PR #12 against `master`. The owner approved stabilization and will personally request GitHub review. Stop before that review request and merge.

## Current source checkpoint

- Repository: `Zfkirke0109/RootlessZachDSP`.
- Pre-stabilization integrated head: `b00b3efab888b692ab14d33770cab321e876f098`.
- Master reconciliation: `3ae3593fa65109a91c6ef2fe7f6706d0093eb04d`, with master `3062969f845d843e15b8f89a7dfd6db156637fe2` as a parent.
- SDK/workflow correction: `c56025d9190c0b9e666654e668111cf32badb212`.
- Capture/file-intake implementation checkpoint: `6ae807ba199cf98034c3cf61969412a175ada57c`.
- Current CI results and remaining blockers must be recorded in the PR body. Earlier green July builds are not verification of this September checkpoint.

## Stabilization changes

- Shared signing secrets are reconciled with the feature workflow. Trusted push builds validate the persistent test certificate before and after APK signing. PR artifacts use disposable test signing. Neither establishes compatibility with an installed APK whose certificate has not been obtained.
- CI runs unit tests, rootless assembly, root Kotlin compilation, APK identity/signature/alignment checks, lint and emulator instrumentation. The emulator invokes one Bash script that preserves Gradle's exit status and captures failure evidence. The obsolete workflow that tested and wrote to a fixed development branch is now manual, read-only and tests the selected ref.
- Startup housekeeping awaits owned-cache cleanup before opening the logger. Cache no longer sweeps other owners' files, including active codec inputs.
- Session and policy collection run on IO in a lifecycle-owned, coalesced poller. Method changes discard old results, destruction cancels work, and ordinary dump pipe reads have time and size bounds. Binder transaction setup itself is subject to Android's binder behavior.
- Fallback selection requires a useful external session rather than a nonempty map containing only self/session zero/irrelevant usage. Query failure remains distinct from an observable empty snapshot. Diagnostics reports query, admission and convolution state without claiming PCM capture.
- Missing/empty/corrupt impulse responses report failures and disable convolution. Decoded dimensions and finite samples are checked before native upload.
- Direct Player supports a bounded SAF tree browser for audio and WavPack correction documents, including unknown/octet-stream MIME labels. Existing decoders validate content; filenames are discovery hints.
- Diagnostic commit identity includes a dirty suffix for tracked working-tree modifications.

## Evidence and limitations

The current device target is Android 17 / One UI 9.0, firmware `CP2A.260605.016.S918U1UEU8ZZI8`. See [physical acceptance checklist](device-validation/S23_ULTRA_ANDROID17_20260920.md). There is no connected Samsung phone or USB DAC in this coding environment.

The September log has idle/zero-frame evidence, not a successful capture run. Historical July signal/transport evidence is useful context but cannot certify the new firmware or source. Final Android/Samsung mix and DAC output are unmeasured; no MQA decoding/unfolding is implemented or claimed.

Regression coverage added: session fallback, query failure versus empty, poll coalescing/cancellation/invalidation, IR dimensions/nonfinite data, extension-based document selection, and cache cleanup ownership/order. Physical document-provider navigation/permission behavior, Samsung lifecycle and USB routing remain acceptance gates.

## Master-plan next steps

1. Complete fresh CI verification and retain the trusted-push APK, checksums and signer evidence.
2. Run the physical checklist; investigate any remaining idle capture using active playback plus simultaneous session/policy evidence.
3. Have the owner request GitHub review on PR #12. Address findings before considering merge; do not close stacked PRs just because code overlaps.
4. Audit PR #4's distinct startup/runtime underrun separation for a small follow-up that preserves current baseline/percentile telemetry. PR #5 contains superseded diagnostics work and is not blindly merged.
5. Resume the 20-feature roadmap after stabilization: capture-policy acceptance and session-scoped DynamicsProcessing design, app/device automation rules, metering and DSP graph milestones. Existing baseline implementations do not mark these expanded criteria complete.

Detailed scope: [stabilization plan](superpowers/plans/2026-09-20-stabilization.md), [delivery roadmap](ROADMAP.md), [20-feature acceptance criteria](FEATURES_20_ROADMAP.md). Historical handoff checkpoints remain in Git history.
