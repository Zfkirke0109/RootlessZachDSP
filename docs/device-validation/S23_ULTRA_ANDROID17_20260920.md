# Galaxy S23 Ultra acceptance checkpoint — 2026-09-20

## Target supplied by the owner

The supplied Software information screenshot shows:

| Field | Value |
|---|---|
| Device family / firmware model | Galaxy S23 Ultra / SM-S918U1 |
| OS | Android 17, One UI 9.0 |
| Build | CP2A.260605.016.S918U1UEU8ZZI8 |
| Kernel | 5.15.197-android13-8 |
| Android security patch | 2026-08-05 |
| Google Play system update | 2026-09-01 |
| SE for Android | Enforcing |

This is a user-supplied target, not a device connected to CI. API 35 emulator results are not Samsung Android 17 results. July Android 16 evidence is historical and must not be reused as acceptance for this firmware.

## Why the September log is not a capture pass

The supplied September 19 log reports `f61a292`; the service interval around 19:51–20:02 has zero read/write/process counters and idle processing. Media notifications alone do not prove active playback or capturable PCM. The stopped diagnostic snapshot contains session zero; an AudioFlinger native permission denial applies to that subsection, not every dump provider. Convolution reports no frames. None of this establishes whether the original source was playing, blocked capture, or hit a Samsung policy/parser issue.

The startup logging text also differs from the nominal commit identity, so clean-source provenance must be checked on the replacement build. New dirty-tree labeling helps future diagnosis; it cannot establish the provenance of the old APK.

## Physical checks still required

1. Record installed package, version code, commit, and certificate SHA-256. Compare the full signer fingerprint with the trusted-push CI artifact before updating. Do not uninstall to bypass a mismatch without first protecting user settings; no signer continuity with the currently installed APK is presumed.
2. Open Settings → Diagnostics after a cold start, a headless bind, and service restart. Export a fresh redacted report. Confirm a startup marker survives and that session query/admission status matches the test phase.
3. Play a known local, capture-allowed audio source continuously. Verify accepted sessions, increasing read/write/process counts, nonzero input RMS, and a repeatable bypass versus effect difference. An accepted session by itself is not a PCM pass.
4. Test Amazon Music and Audible separately while actively playing. Record selected dump method and source capture policy. Classify unavailable query, no eligible sessions, silence, and processing independently. Do not label an app capture-blocked solely from a notification or zero counters.
5. Repeat speaker, Bluetooth, screen lock, app switch, allowlist changes, stop/start and route disconnect. Confirm audio stays playable, session rebuilds settle, and no stale post-destroy results alter the service. Capture a 10-minute run with telemetry before attempting a longer soak.
6. Enable convolution with no selection, an empty/invalid IR, then a known valid IR. Invalid input must leave convolution disabled with a visible error; valid input must load and process audio. Do not confuse an enabled preference with a successfully loaded IR.
7. In Direct Player use **Browse audio folder** for `.wv` files labeled `application/octet-stream`; enter/leave nested folders, cancel, revoke access, and choose another source. Use **Browse correction folder (.wvc)** for a matching hybrid correction. Check core-only lossy versus corrected-lossless status and reject mismatched/corrupt inputs. Test a folder over 1,000 entries: a truncation notice must appear.
8. With a real USB DAC, record supported and requested rate/encoding/channel count and the routed device. Test FLAC/WAV/WavPack in Automatic, Direct and Enhanced modes, then disconnect/reconnect. Strict Direct must not silently fall back to processed playback. Android route/contract evidence does not prove DAC output bits.
9. Repeat Diagnostics open/close and file selection while observing memory, staged-file cleanup and UI responsiveness. A growing measurement is a lead, not a proven leak without retained-object evidence.

## Evidence to attach to PR #12

- Exact CI source SHA/run URL and artifact SHA-256; package/version/signer verification.
- A fresh redacted compatibility export and transport/signal snapshots for each capture phase.
- Direct Player source type, correction state, requested/resolved mode and USB contract/route evidence.
- Pass/fail/pending per step, with firmware and output route. Listening results should be identified as subjective.

The owner will personally request GitHub review. Keep the PR draft; no merge, review request, release or hardware acceptance is implied by this checkpoint.
