# Approved stabilization continuation

User approved the four recommendations in the September 20 review. Keep PR #12 draft and stop before merging or requesting GitHub review. Execution stays in this session. Hardware claims require physical evidence.

1. Reconcile master into the integrated branch, retaining FOSS build/test/lint gates and the shared signing certificate contract. Run emulator tests from one Bash script; persist exit codes and failure evidence. Trusted push builds use the persistent test signer; PR builds remain test-only. No release/tag is created.
2. Make startup cache cleanup synchronous on its existing background worker. Preserve active codec staging files. Test real temporary-file cleanup and log creation ordering.
3. Give session polling an owned, cancellable lifecycle with coalesced requests. Collect both session and policy dumps on IO, apply results on Main, discard results after destruction or detection-method changes. Test cancellation, late results, and burst coalescing.
4. Select fallback by useful external media sessions instead of any map entry. Preserve policy evidence and distinguish unavailable queries from no usable sessions. Test self-only/session-zero/notification-only snapshots, provider failure, and accepted media.
5. Surface missing/empty/corrupt IR failures and validate decoded frame/channel dimensions before native use. Keep convolution disabled after invalid input; do not label failure successful.
6. Add a bounded SAF folder browser for audio and WavPack correction files, independent of MIME filtering. Retain only explicitly granted tree read access; validate actual files through existing decoders. Test octet-stream WAVPACK, correction selection, directory navigation, limit, and stale async results.
7. Embed dirty-tree identity in diagnostics. Refresh roadmap/handoff with exact commit/build results and unresolved physical gates. Preserve PR #4's independent underrun work for an explicit future integration rather than replacing current telemetry.
8. Run unit tests, lint, both-flavor compilation, APK assembly, signature/package/version/alignment checks, and emulator tests on the integrated head. Review the resulting diff and artifact. Physical Samsung capture, listening, and USB DAC checks remain required when no device is connected.

Acceptance: the draft has reproducible checks and a downloadable signed test APK; source defects above have focused regression coverage; WavPack intake is implemented; no false claim of DSP output, bit-perfect USB, physical validation, or completion of the later 20-feature roadmap. Continue broader milestones after these device acceptance gates.

Review focus: cancellation while a provider is blocked; no session fallback to self/session zero; active staged files surviving housekeeping; .wv/.wvc documents labeled application/octet-stream; failure evidence retaining the actual Gradle exit status.
