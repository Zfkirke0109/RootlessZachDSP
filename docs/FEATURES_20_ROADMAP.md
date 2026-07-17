# RootlessZachDSP — 20-feature implementation roadmap

This roadmap begins after the foundation transport work in PR #1. Each milestone is independently reviewable, measurable, and reversible. No feature is considered device-validated until it runs on physical Android hardware.

## Global rules

- Preserve GPL attribution and upstream history.
- Keep the rootless transport fail-open and never trade playable audio for prolonged silence.
- No cloud requirement, advertising SDK, or automatic upload.
- No raw PCM, media titles, private paths, or notification content in diagnostics.
- All external automation endpoints are permission-protected and disabled unless explicitly enabled.
- DSP changes require deterministic native golden vectors and a bypass comparison.
- Every milestone ships behind feature flags until its acceptance criteria pass.
- Each PR updates `docs/AGENT_HANDOFF.md` with branch, SHA, tests, APK checksum, risks, and next action.

---

# Milestone A — application targeting, compatibility, and privacy

## 1. Full app allowlist/exclusion picker

**Purpose**  
Let the user choose exactly which applications are captured or ignored.

**User experience**  
Searchable installed-app list with icons, labels, package names, current playback state, and two explicit modes: `Allow selected only` and `Exclude selected`.

**Android/API limitations**  
Package visibility, work profiles, shared UIDs, hidden/system packages, and applications that disallow playback capture.

**Architecture**  
Extend `CapturePolicyStore` with a versioned policy model. Use a repository/ViewModel over package discovery and recent session evidence. The audio service consumes an immutable compiled UID rule set.

**Likely files**  
`audio/capture/CapturePolicyStore.kt`, `BlocklistActivity`, Room models/DAO, `RootlessAudioProcessorService.kt`, new picker fragments/adapters.

**Data model**  
Mode, package name, user/profile handle, optional resolved UID, source (`picker`, `manual`, `recent`), enabled state, last resolution time.

**Privacy/performance**  
Stored locally. Package labels are never included in redacted exports. UID compilation happens off the audio thread.

**Feature flag**  
`capture_policy_ui_v2`.

**Tests**  
Policy migration, allow/exclude precedence, package removal/reinstall, shared UID, empty allowlist, large app list, configuration change.

**Device validation**  
Amazon Music selected/unselected while other apps play; recorder rebuild count exactly once per effective policy change.

**Acceptance**  
Correct capture policy, no self-capture, no stale UID after reinstall, deterministic behavior after restart.

**Rollback**  
Read existing legacy blocklist and disable v2 UI without losing entries.

**Dependencies**  
Foundation transport and session diagnostics.

**Branch / PR**  
`feat/capture-policy-picker` — `A1: application capture-policy picker`.

## 2. Manual package and UID entry

**Purpose**  
Support packages hidden by package visibility, unusual system components, and advanced troubleshooting.

**User experience**  
Validated package/UID input with a preview of resolved identity and a warning when the UID is ephemeral or shared.

**Android/API limitations**  
UIDs change after reinstall; package-to-UID may differ by user/profile; raw system UIDs can be dangerous.

**Architecture**  
Manual rules are separate from resolved picker entries and are revalidated before each recorder configuration.

**Likely files**  
Capture-policy models, picker ViewModel, validation utilities, Settings/compatibility UI.

**Data model**  
Input type, normalized value, profile, validation status, last successful resolution, warning state.

**Privacy/performance**  
Local only. Redacted exports include counts and hashes, not raw values by default.

**Feature flag**  
Shares `capture_policy_ui_v2`.

**Tests**  
Malformed package, nonexistent package, shared UID, UID rollover, profile mismatch, duplicate rule.

**Device validation**  
Add/remove a manual rule and confirm the effective capture configuration and diagnostics reason.

**Acceptance**  
No invalid rule reaches `AudioPlaybackCaptureConfiguration`.

**Rollback**  
Manual rules can be disabled independently.

**Dependencies**  
Feature 1.

**Branch / PR**  
`feat/manual-capture-rules` — `A2: manual package and UID rules`.

## 3. Recently detected sessions interface

**Purpose**  
Expose what Android reports so users can select or troubleshoot active audio sessions.

**User experience**  
Live/recent list showing app, usage, content type, active state, recordability, capture decision, and exact reason.

**Android/API limitations**  
Session IDs are short-lived and reused; several sessions may share a PID; some dumps omit session IDs.

**Architecture**  
Create a typed `SessionRecord` and bounded recent-session repository. Preserve `PID -> Set<SessionId>` and `SessionId -> SessionRecord`.

**Likely files**  
Session dump providers, `BaseSessionDatabase`, new recent-session repository/UI, diagnostics schema.

**Data model**  
Session ID, PID, UID, package, usage, content, direction, active, recordable, decision, reason, source, epoch, first/last seen.

**Privacy/performance**  
Bounded local history; hashes in exported bundles.

**Feature flag**  
`recent_sessions_ui`.

**Tests**  
Multiple SIDs per PID, exact duplicates, SID reuse, app restart, malformed/future dump formats, own-session exclusion.

**Device validation**  
Amazon Music appears as media/music; RootlessZachDSP sessions appear as self-excluded; no duplicate-PID warning.

**Acceptance**  
Every rejected session has a typed reason and no arbitrary ambiguous fallback.

**Rollback**  
Disable UI while retaining current session-selection behavior.

**Dependencies**  
Pluto parser fixes and diagnostics foundation.

**Branch / PR**  
`feat/recent-audio-sessions` — `A3: recent session inspection`.

## 4. Secure Folder and work-profile-aware rules

**Purpose**  
Correctly target duplicate package names installed in separate users/profiles.

**User experience**  
Profile badges and separate rules for personal, work, and Samsung Secure Folder contexts when Android exposes them.

**Android/API limitations**  
Cross-profile package discovery and UID access are restricted. Secure Folder may not expose all identities to the personal-profile app.

**Architecture**  
Key rules by package plus user/profile identifier. Fall back to recent-session UID evidence when package enumeration is unavailable.

**Likely files**  
Capture-policy models, package repository, recent sessions, compatibility explanations.

**Data model**  
Package, user serial/profile token, resolved UID, availability, source, confidence.

**Privacy/performance**  
Do not export profile names or user serials; use random local aliases/hashes.

**Feature flag**  
`profile_scoped_capture_rules`.

**Tests**  
Duplicate package across profiles, unavailable profile, profile removal, stale UID, rule migration.

**Device validation**  
Separate personal and Secure Folder playback tests when the platform allows capture.

**Acceptance**  
No personal-profile rule silently targets the wrong profile.

**Rollback**  
Collapse to package-only behavior with a clear warning.

**Dependencies**  
Features 1–3.

**Branch / PR**  
`feat/profile-scoped-capture` — `A4: work-profile and Secure Folder capture rules`.

## 5. Compatibility and diagnostics center

**Purpose**  
Give a single screen for engine state, selected session, route, format, transport health, and actionable problems.

**User experience**  
Settings > Diagnostics with current engine state, session history, route, active preset/modules, transport counters, warnings, and self-test.

**Android/API limitations**  
Some route/session details are OEM-dependent; rootless capture policy is controlled by the source app.

**Architecture**  
Read-only ViewModel backed by `RootlessZachDiagnostics`, session repository, route observer, and compatibility report builder.

**Likely files**  
Diagnostics package, Settings preferences/fragments, route observer, service event bridge.

**Data model**  
Versioned diagnostic events and latest-state projections.

**Privacy/performance**  
No raw audio. UI refresh throttled and detached when backgrounded.

**Feature flag**  
`diagnostics_center`.

**Tests**  
State projection, empty state, process recreation, event parser compatibility, clear/export.

**Device validation**  
Speaker/Bluetooth/USB route changes, lock screen, app selection, engine restart, recovery.

**Acceptance**  
A support user can identify why an app is not captured without raw logcat.

**Rollback**  
Keep text compatibility report while hiding live UI.

**Dependencies**  
Features 1–4 and structured diagnostics.

**Branch / PR**  
`feat/diagnostics-center` — `A5: compatibility and diagnostics center`.

## 6. Privacy dashboard and redacted support bundle

**Purpose**  
Make diagnostic retention and export understandable and controllable.

**User experience**  
View retention size, clear history, preview bundle, choose inclusion categories, and explicitly opt into package names.

**Android/API limitations**  
Document providers may revoke access; large reports must stream rather than load entirely into memory.

**Architecture**  
Sanitizing exporter over versioned JSONL and compatibility snapshots. Leak scanner runs before sharing.

**Likely files**  
Diagnostics store/exporter, Settings privacy fragment, FileProvider, report tests.

**Data model**  
Export manifest, schema versions, redaction settings, event counts, hashes.

**Privacy/performance**  
Default maximum approximately 10 MiB across active/rotated files. No automatic upload.

**Feature flag**  
`diagnostics_export_v1`.

**Tests**  
Path/URI/package-name leak detection, rotation, interrupted export, revoked destination, corrupted line tolerance.

**Device validation**  
Preview and export a bundle, then inspect it for prohibited data.

**Acceptance**  
Leak scanner passes and the bundle remains technically useful.

**Rollback**  
Disable export while retaining local clear/view controls.

**Dependencies**  
Feature 5.

**Branch / PR**  
`feat/private-support-bundle` — `A6: privacy dashboard and sanitized export`.

---

# Milestone B — fallback and automation

## 7. `DynamicsProcessing` fallback

**Purpose**  
Offer limited processing when playback capture is unavailable but an attachable audio session exists.

**User experience**  
Clearly labeled compatibility fallback with a supported-effects list and no claim of full JamesDSP parity.

**Android/API limitations**  
Effect availability, session attachment, OEM behavior, and parameter ranges vary; it cannot process every app globally.

**Architecture**  
Separate fallback engine implementing a restricted common interface. Never silently switch without user-visible reason.

**Likely files**  
New fallback engine/service, compatibility evaluator, Settings, effect-state adapter.

**Data model**  
Capability matrix, active session, supported bands/effects, failure reason.

**Privacy/performance**  
No extra persisted media data. Platform effect processing is low overhead.

**Feature flag**  
`dynamics_processing_fallback` default off.

**Tests**  
Capability mapping, unsupported effect rejection, attach/detach, parameter bounds, lifecycle.

**Device validation**  
Known compatible and incompatible apps/routes.

**Acceptance**  
No crash or false claim of processing; fallback is audibly and measurably applied where supported.

**Rollback**  
Disable the fallback engine independently.

**Dependencies**  
Compatibility center.

**Branch / PR**  
`feat/dynamics-processing-fallback` — `B1: limited platform fallback`.

## 8. App-plus-device profile automation

**Purpose**  
Automatically load a preset based on the active app and output route.

**User experience**  
Rules such as `Amazon Music + Bluetooth headset -> Preset X`, with priority preview and manual override.

**Android/API limitations**  
Route names can change; several apps/sessions may be active; rapid route churn must be debounced.

**Architecture**  
Deterministic rule evaluator with explicit priority, specificity, cooldown, and last-decision diagnostics.

**Likely files**  
`ProfileManager`, route observer, session repository, Room tables, Settings UI.

**Data model**  
App/profile predicate, route predicate, preset ID, priority, enabled, override timeout.

**Privacy/performance**  
Local only. Rules evaluate off the audio thread.

**Feature flag**  
`automatic_profiles_v2`.

**Tests**  
Priority ties, route churn, multiple sessions, manual override, missing preset, process restart.

**Device validation**  
Speaker/Bluetooth switching with at least two source apps.

**Acceptance**  
Exactly one deterministic rule wins and no preset loop occurs.

**Rollback**  
Disable automation while preserving rules.

**Dependencies**  
Features 1–5.

**Branch / PR**  
`feat/app-route-automation` — `B2: deterministic app and route profiles`.

## 9. Tasker/MacroDroid protected intents

**Purpose**  
Allow local automation of power, preset, profile, effect state, and diagnostics capture.

**User experience**  
Documented actions with an enable switch, generated authorization token or signature permission, and test buttons.

**Android/API limitations**  
Background activity/FGS restrictions and MediaProjection consent cannot be bypassed.

**Architecture**  
Non-exported by default; when enabled, signature/custom-permission receiver validates caller, action, nonce, and allowed operation.

**Likely files**  
Manifest, automation receiver/service, permission resources, docs, Settings.

**Data model**  
Allowed operations, caller allowlist, last request result, rate limit.

**Privacy/performance**  
No arbitrary file/path parameters; no secret values in logs.

**Feature flag**  
`external_automation` default off.

**Tests**  
Unauthorized caller, malformed extras, replay/rate limit, allowed action, Android background restrictions.

**Device validation**  
Tasker/MacroDroid start/stop and preset selection with app foreground/background.

**Acceptance**  
Unauthorized requests are rejected and audited without exposing tokens.

**Rollback**  
Disable receiver component and clear authorization.

**Dependencies**  
Feature 8.

**Branch / PR**  
`feat/protected-automation-api` — `B3: permission-protected automation intents`.

---

# Milestone C — measurement and channel processing

## 10. Spectrum, peak, true-peak, LUFS, and gain-reduction meters

**Purpose**  
Show verifiable pre/post signal behavior and headroom.

**User experience**  
Selectable pre/post spectrum, sample peak, true-peak estimate, short-term/integrated LUFS, clipping, and compressor/limiter reduction.

**Android/API limitations**  
Metering must not starve the real-time thread; true-peak/LUFS require bounded decimation and background work.

**Architecture**  
Preallocated accumulators in the audio loop, lock-free snapshots, throttled UI rendering.

**Likely files**  
Audio service/buffers, native/JNI meters where appropriate, diagnostics, new meter views.

**Data model**  
Windowed statistics, channel values, reset epoch, dropped-frame count.

**Privacy/performance**  
No PCM persistence. Target <3% additional CPU in normal mode.

**Feature flag**  
`signal_meters_v1`.

**Tests**  
Sine/noise/silence vectors, clipping, stereo asymmetry, LUFS tolerance, no allocation regression.

**Device validation**  
Enable/disable EQ/compressor and confirm pre/post change without underrun growth.

**Acceptance**  
Measurements match reference tolerances and audio stability is unchanged.

**Rollback**  
Disable expensive meters independently; retain transport diagnostics.

**Dependencies**  
Diagnostics schema.

**Branch / PR**  
`feat/pre-post-metering` — `C1: pre/post signal meters`.

## 11. Independent channel controls

**Purpose**  
Provide left/right gain, balance, delay, swap, mono, polarity, and independent EQ.

**User experience**  
Channel matrix page with link/unlink controls and a safe reset.

**Android/API limitations**  
Delay adds latency and memory; channel layouts beyond stereo require explicit support.

**Architecture**  
Native channel-matrix stage with bounded delay lines and atomic parameter snapshots.

**Likely files**  
Native DSP graph, JNI wrapper, preferences, channel UI, preset schema.

**Data model**  
Per-channel gain/polarity/delay/EQ, matrix, link state.

**Privacy/performance**  
No privacy impact. Memory scales with maximum delay.

**Feature flag**  
`channel_tools_v1`.

**Tests**  
Impulse routing, delay samples, polarity, mono, swap, independent EQ, denormal/clipping safety.

**Device validation**  
Stereo test signals through speaker/headphones/USB.

**Acceptance**  
Golden vectors pass and controls do not alter untouched channels unexpectedly.

**Rollback**  
Bypass entire channel stage and migrate presets safely.

**Dependencies**  
Feature 10 test infrastructure.

**Branch / PR**  
`feat/channel-matrix` — `C2: independent stereo channel tools`.

## 12. Relative equal-loudness compensation

**Purpose**  
Adjust tonal balance relative to listening volume without uncontrolled loudness boost.

**User experience**  
Per-device calibration point, strength, curve preview, and headroom indicator.

**Android/API limitations**  
Android volume index is not calibrated SPL; route/device changes require separate profiles.

**Architecture**  
Relative curve generator driven by normalized route volume and calibration, feeding the ordered EQ stage.

**Likely files**  
Profile manager, route observer, EQ parameter generator, Settings/graph UI.

**Data model**  
Calibration volume, reference curve, strength, route class, headroom policy.

**Privacy/performance**  
Local route profile only; negligible runtime cost after curve generation.

**Feature flag**  
`relative_equal_loudness` default off.

**Tests**  
Curve interpolation, endpoints, route switch, headroom limiter interaction, preset migration.

**Device validation**  
Low/medium/high volume sweeps on at least speaker and headphones.

**Acceptance**  
No clipping, deterministic curve, and disabling returns bit-equivalent baseline within tolerance.

**Rollback**  
Remove generated curve and retain calibration data for future versions.

**Dependencies**  
Features 8, 10, and ordered graph planning.

**Branch / PR**  
`feat/equal-loudness` — `C3: route-relative equal-loudness compensation`.

---

# Milestone D — DSP graph and convolution

## 13. Ordered DSP graph

**Purpose**  
Make processing order explicit, inspectable, and configurable.

**User experience**  
Drag/reorder supported blocks with warnings for invalid combinations and one-tap default order.

**Android/API limitations**  
Some native modules assume fixed order or sample format and must declare constraints.

**Architecture**  
Versioned graph model compiled to an immutable native execution plan and swapped atomically.

**Likely files**  
Native engine/JNI, preference/preset models, graph editor UI, diagnostics.

**Data model**  
Node ID/type/version, enabled, parameters reference, ordering, compatibility constraints.

**Privacy/performance**  
No privacy impact. Compilation off-thread; no per-buffer graph allocation.

**Feature flag**  
`ordered_dsp_graph`.

**Tests**  
Graph validation, migration, invalid cycle/order, atomic swap, bypass equivalence, golden vectors.

**Device validation**  
Reorder EQ/compressor/convolver during playback with crossfade and no discontinuity.

**Acceptance**  
No crash/pop, deterministic execution order, safe fallback to last valid graph.

**Rollback**  
Restore fixed legacy order from the same preset data.

**Dependencies**  
Feature 17 test matrix begins here.

**Branch / PR**  
`feat/ordered-dsp-graph` — `D1: versioned ordered processing graph`.

## 14. Multiple independent LiveProg blocks

**Purpose**  
Run several independent EEL/LiveProg processors with separate controls.

**User experience**  
Add/remove/reorder blocks; per-block bypass, wet/dry, gain, parameters, CPU meter, and fault status.

**Android/API limitations**  
Untrusted scripts can be expensive or unstable; execution must be bounded.

**Architecture**  
One isolated state/context per block, watchdog, budget accounting, fail-open block bypass, atomic program swap.

**Likely files**  
LiveProg native wrapper/editor, ordered graph, diagnostics, preset format.

**Data model**  
Block UUID, script asset hash, parameters, wet/dry, gain, budget, last error.

**Privacy/performance**  
Script filenames/paths redacted in exports; CPU budget per block.

**Feature flag**  
`multi_liveprog`.

**Tests**  
Two+ blocks, state isolation, compile failure, runtime timeout, bypass, reorder, preset migration.

**Device validation**  
Known scripts under sustained playback; watchdog injection.

**Acceptance**  
A failing block bypasses itself without stopping other audio.

**Rollback**  
Collapse to first enabled legacy block.

**Dependencies**  
Feature 13.

**Branch / PR**  
`feat/multi-liveprog` — `D2: independent LiveProg graph blocks`.

## 15. Partitioned convolution and IR-cache overhaul

**Purpose**  
Reduce convolution latency/CPU spikes and make IR changes reliable.

**User experience**  
Fast IR load, normalization options, channel mapping, cache status, and click-free switching.

**Android/API limitations**  
Large IRs consume memory; resampling and FFT plans vary by ABI/device.

**Architecture**  
Uniform/non-uniform partitioned convolution, resampled-IR cache keyed by content hash/sample rate/channel map, background preparation, crossfaded swap.

**Likely files**  
Native convolver, JNI, storage/cache utilities, convolver preferences/UI, diagnostics.

**Data model**  
IR content hash, source format, normalized/resampled format, partitions, cache version, channel map.

**Privacy/performance**  
No raw path in exports; cache remains app-private. Memory budget and eviction required.

**Feature flag**  
`partitioned_convolver_v2`.

**Tests**  
Impulse response golden vectors, mono/stereo mapping, resampling, cache hit/miss/corruption, crossfade, memory budget.

**Device validation**  
Short/long IRs at 44.1/48 kHz, route change, screen lock, repeated IR switching.

**Acceptance**  
No audible discontinuity, bounded memory, lower worst-case processing time than legacy path.

**Rollback**  
Fallback to legacy convolver or bypass on cache/plan failure.

**Dependencies**  
Features 10, 13, and 17.

**Branch / PR**  
`feat/partitioned-convolution` — `D3: partitioned convolver and IR cache`.

---

# Milestone E — interchange, testing, and local learning

## 16. Equalizer APO, AutoEQ, Wavelet, and versioned preset interchange

**Purpose**  
Import/export common EQ data without silently changing meaning.

**User experience**  
File preview, detected format, supported/ignored fields, conversion warnings, and rollback.

**Android/API limitations**  
Formats differ in filter types, channel semantics, preamp, sample-rate assumptions, and unsupported effects.

**Architecture**  
Parser -> normalized intermediate model -> validated RootlessZachDSP preset. Never apply directly from untrusted text.

**Likely files**  
New interchange parsers, preset models, storage/import UI, fuzz corpus.

**Data model**  
Versioned bundle manifest, normalized filters, preamp, channels, provenance, warnings.

**Privacy/performance**  
Local files only; paths excluded from exports.

**Feature flag**  
`preset_interchange_v2`.

**Tests**  
Known fixtures, malformed/fuzzed input, unsupported filters, extreme values, round-trip, archive traversal prevention.

**Device validation**  
Import representative APO/AutoEQ/Wavelet files and compare generated response.

**Acceptance**  
No crash, explicit loss warnings, response within tolerance.

**Rollback**  
Reject import and preserve current preset unchanged.

**Dependencies**  
Ordered graph/preset versioning.

**Branch / PR**  
`feat/preset-interchange` — `E1: validated cross-format preset interchange`.

## 17. Golden-vector, fuzz, native, and instrumentation test matrix

**Purpose**  
Make DSP and rootless transport changes safe to evolve.

**User experience**  
No direct UI; produces confidence and reproducible diagnostics.

**Android/API limitations**  
Emulators do not reproduce OEM audio routing; native floating-point results need tolerances.

**Architecture**  
Layered tests: pure Kotlin transport/parser, native golden vectors, property/fuzz tests, Robolectric where useful, Android instrumentation, physical-device scripts.

**Likely files**  
`app/src/test`, `app/src/androidTest`, native test targets, CI workflow, device-validation scripts/docs.

**Data model**  
Test vector manifest with sample rate, channels, expected hashes/metrics, tolerance, engine version.

**Privacy/performance**  
Synthetic/generated fixtures only.

**Feature flag**  
Not applicable; CI gate.

**Tests**  
This feature is the test system itself, including parser backward compatibility and leak scanning.

**Device validation**  
Automated route/recovery checklist with captured artifacts.

**Acceptance**  
Every DSP stage has bypass and active golden vectors; CI artifacts identify commit and checksums.

**Rollback**  
Never remove a gate without documented replacement.

**Dependencies**  
Foundation; expands continuously.

**Branch / PR**  
`test/dsp-validation-matrix` — `E2: deterministic DSP and device test matrix`.

## 18. Local compatibility-learning database

**Purpose**  
Use verified local outcomes to improve troubleshooting and conservative defaults per app/route/device class.

**User experience**  
“Known working”, “capture restricted”, “route unstable”, and suggested safe actions with confidence and reset controls.

**Android/API limitations**  
Evidence is device-specific and can become stale after app/OS updates.

**Architecture**  
Bounded Room database of anonymized class keys and outcome counters with confidence decay. It advises; it never overrides safety or user policy.

**Likely files**  
Room entities/DAO/repository, diagnostics, compatibility UI, migration/export.

**Data model**  
Salted app identity, app version class, OS/device/route class, capture result, recovery/underrun stats, confidence, timestamps.

**Privacy/performance**  
Local only, bounded/decaying, resettable. No network sync.

**Feature flag**  
`local_compatibility_learning` default shadow mode.

**Tests**  
Decay, poisoning resistance, app update, route split, reset/export, migration, low-confidence fallback.

**Device validation**  
Repeated known-good and known-restricted sessions across routes.

**Acceptance**  
Advice is explainable, never blocks a user-requested safe attempt, and improves repeated troubleshooting.

**Rollback**  
Ignore/delete learned data and use deterministic compatibility rules.

**Dependencies**  
Features 3, 5, 6, and 17.

**Branch / PR**  
`feat/local-compat-learning` — `E3: private compatibility outcome learning`.

---

# Milestone F — experimental processing modes

## 19. Pitch shifting and time stretching

**Purpose**  
Provide independent pitch and tempo controls for compatible playback.

**User experience**  
Pitch semitones/cents, tempo ratio, quality/latency mode, reset, and overload warning.

**Android/API limitations**  
Adds latency and CPU load; quality varies with content and extreme settings.

**Architecture**  
Dedicated graph node with preallocated state, bounded parameter range, quality modes, watchdog, and fail-open bypass.

**Likely files**  
Native DSP library/node, JNI, graph UI, preset schema, diagnostics.

**Data model**  
Pitch ratio, tempo ratio, algorithm/quality mode, latency, enabled.

**Privacy/performance**  
No privacy impact. Explicit CPU/latency budget and device qualification.

**Feature flag**  
`pitch_time_experimental` default off.

**Tests**  
Sine/chirp/speech/music vectors, duration/pitch tolerance, discontinuity, parameter sweep, overload fallback.

**Device validation**  
Sustained playback on speaker/Bluetooth/USB with thermal and underrun tracking.

**Acceptance**  
No prolonged silence, acceptable quality in documented range, safe bypass on overload.

**Rollback**  
Bypass node and remove from active graph while preserving preset warning.

**Dependencies**  
Features 10, 13, and 17.

**Branch / PR**  
`feat/pitch-time-experimental` — `F1: bounded pitch and tempo processing`.

## 20. Separate microphone-processing mode

**Purpose**  
Process microphone input as an explicit mode independent from playback capture.

**User experience**  
Clear mode switch, persistent microphone indicator/notification, monitoring/output selection, feedback warning, and dedicated presets.

**Android/API limitations**  
Microphone permission, foreground-service rules, echo/feedback risk, Bluetooth SCO limitations, and no hidden background recording.

**Architecture**  
Separate service/session pipeline and permission flow. Never reuse playback-capture authorization or silently activate the microphone.

**Likely files**  
New microphone service, manifest/permissions, onboarding, route/feedback protection, graph/preset integration, diagnostics.

**Data model**  
Mode, input device, output/monitoring route, gain, feedback guard, active preset, consent epoch.

**Privacy/performance**  
No audio recording to disk by default. Always visible foreground indication. Diagnostics contain metrics only.

**Feature flag**  
`microphone_mode_experimental` default off.

**Tests**  
Permission denial/revocation, lifecycle, route changes, feedback guard, background restrictions, no cross-mode state leak.

**Device validation**  
Built-in mic, wired/USB input when available, Bluetooth limitations, screen lock, call interruption.

**Acceptance**  
Microphone never starts without explicit action and visible indication; safe stop on revocation; no feedback runaway.

**Rollback**  
Disable component and clear authorization; playback mode remains unaffected.

**Dependencies**  
Features 10, 13, 17, and privacy dashboard.

**Branch / PR**  
`feat/microphone-mode` — `F2: explicit isolated microphone processing`.

---

# Cross-cutting fidelity and device-profile requirements

These requirements apply across the 20 feature milestones and must not be marketed beyond what the
stock Android platform can prove.

## Bit-perfect compatibility mode

- A rootless capture -> DSP -> AudioTrack pipeline is not bit-perfect while processing is active.
- Add a clearly labeled bypass/relinquish mode for users who prefer a source player or USB DAC's own
  direct path. The UI must say "bit-perfect eligibility", never guarantee bit-perfect output.
- Report the exact blockers: capture conversion, sample-rate conversion, DSP, fades, Android mixer,
  route effects, and volume processing.
- Acceptance requires byte/hash comparison only on a direct path where Android exposes one.

## High-resolution output capability probe

- Do not bundle or claim a replacement Samsung audio driver. A normal unrooted APK cannot replace
  the kernel driver, vendor audio HAL, or privileged system policy.
- Probe supported route sample rates and encodings, attempt high-rate AudioTrack creation behind an
  experimental flag, and fall back safely to 48 kHz.
- Keep 96/192 kHz disabled until physical S23 Ultra tests prove stable capture, processing, routing,
  thermal behavior, and no underrun regression.

## MQA compatibility

- Do not claim an MQA decoder or renderer without licensed, auditable decoder technology.
- RootlessZachDSP receives PCM from Android playback capture; any source-side decode must occur
  before capture. The compatibility screen must state that MQA passthrough is unavailable while DSP
  is active.

## Dolby Atmos coexistence

- Rootless output uses media/music AudioAttributes so Samsung's normal media and Dolby policy can
  remain eligible.
- Never silently disable Dolby Atmos. Detect competing effects where possible and warn about
  double-processing or headroom risk.
- Device validation must cover Atmos off/on/auto on speakers, Bluetooth, USB, screen lock, and route
  changes, with captured-input, DSP-output, and final-output measurements.

## Local equipment calibration

- Built-in S23 Ultra speaker and Wrangler 4xe premium-audio entries are conservative unmeasured
  starting templates, not AutoEQ laboratory corrections.
- Preserve headroom by default, show provenance, and allow replacement with measured curves.
- Future calibration should support per-route microphone measurements, target curves, confidence,
  rollback, and automatic app-plus-route profile selection.

---

# Delivery order and merge gates

1. Complete and physically validate the foundation Settings/session/diagnostics fixes.
2. Milestone A PRs in order 1–6.
3. Milestone B PRs 7–9.
4. Milestone C PRs 10–12.
5. Milestone D PRs 13–15.
6. Milestone E PRs 16–18, with feature 17 expanding throughout.
7. Milestone F PRs 19–20 only after CPU/latency safety gates are mature.

Every PR must include:

- exact starting and ending SHA;
- build/test/lint commands and results;
- APK package/version/signature/checksum;
- diagnostics schema changes and parser compatibility;
- privacy impact and leak-scan result;
- physical-device validation status stated honestly;
- rollback instructions;
- updated agent handoff.
