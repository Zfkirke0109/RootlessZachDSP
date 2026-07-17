# RootlessZachDSP implementation plan

This plan separates transport safety from experimental DSP additions so each milestone can be measured, tested, and reverted independently.

## Milestone 1 — foundation and transport correctness

- Rebrand the installed application and release artifacts.
- Preserve upstream attribution and GPL notices.
- Upgrade to Android SDK 36, AGP 8.13.2, Gradle 8.13, and NDK r28.
- Add partial read/write loops, error handling, zero-tail behavior, and deterministic tests.
- Add underrun/deadline/partial-operation telemetry.
- Add adaptive buffer decisions with conservative hysteresis.
- Add fail-open bypass, wet/dry ramps, recovery fades, and pipeline restart retries.
- Add persistent allow/exclude capture-policy storage and local diagnostics data.
- Produce signed FOSS APKs, checksums, and 16 KiB alignment verification.

Exit criteria: tests and lint pass; debug APK builds; no transport path assumes a full read or write; exceptions do not intentionally convert playable audio into prolonged silence.

## Milestone 2 — allowlist UI and compatibility center

- App picker with allow-selected and exclude-selected modes.
- Manual package/UID entry and recently detected sessions.
- Compatibility report screen with copy/export actions.
- Route, WebView, OS, playback-policy, permission, and transport-state explanations.
- Redacted support bundle with explicit privacy choices.
- Source Fidelity & Headroom inspector for the current AudioTrack-input telemetry window.
- Truthful proprietary-codec integration state that never infers source authentication from post-capture PCM.

Current checkpoint: the source-fidelity inspector reports peak, dBFS headroom, a conservative preamp recommendation, and the unmeasured final-system boundary. It stores no raw PCM.

## Milestone 3 — automation and fallback

- Documented, permission-protected intents for start/stop/toggle/profile/effect state.
- App-plus-device profile rules with deterministic priority.
- Experimental `DynamicsProcessing` fallback for compatible audio sessions.
- Enforce mutual exclusion between playback-capture transport and session-effect fallback to prevent double processing.

## Milestone 4 — metering and channel tools

- Pre/post spectrum, sample peak, true-peak estimate, LUFS, clipping, and gain reduction.
- Left/right gain, balance, delay, channel swap, mono, and independent EQ.
- Relative equal-loudness compensation with per-device calibration controls.

## Milestone 5 — DSP graph and convolution

- Ordered processing graph and multiple LiveProg blocks.
- Per-block bypass, wet/dry mix, gain, measured CPU cost, and watchdog.
- Partitioned convolution, resampled-IR cache, normalization, and crossfaded IR switching.
- Golden-vector native DSP tests.

## Milestone 6 — interchange and experimental modes

- Equalizer APO and expanded AutoEQ interchange.
- Versioned RootlessZachDSP preset bundles.
- Experimental pitch shifting/time stretching.
- Separate microphone-processing mode.
- Optional source-decoder modules only after copyright, redistribution, patent, trademark, conformance, and GPL-compatibility review.

MQA remains behind an authorization gate. No decoder, renderer, carrier detector, authentication implementation, proprietary binary, or trademark claim may be enabled without a written MQA Labs agreement covering this exact Android distribution model.
