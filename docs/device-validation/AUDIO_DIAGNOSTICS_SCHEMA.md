# RootlessZachDSP audio diagnostics schema

## Goals

The diagnostics system must answer two separate questions without storing private audio:

1. Did the rootless transport move audio reliably?
2. Did the enabled DSP actually change the signal as expected?

It must remain local by default, avoid raw PCM persistence, stay bounded in size, and keep all disk I/O off the urgent audio thread.

## Storage model

- Format: UTF-8 JSON Lines (`.jsonl`).
- Location: app-private internal storage.
- Default active-file limit: 5 MiB.
- Rotation: one previous generation, maximum approximately 10 MiB total.
- No network upload.
- No raw PCM, song names, notification content, private paths, URIs, location, or creation metadata.
- Exported application identities are salted hashes unless the user explicitly elects to include package names.
- Periodic snapshots are persisted approximately every five seconds; anomalies are emitted immediately by a non-real-time writer.

## Common envelope

Every record uses a stable envelope:

```json
{
  "schemaVersion": 1,
  "eventType": "TRANSPORT_SNAPSHOT",
  "capturedAtNanos": 1234567890,
  "wallClockUtc": "2026-07-16T00:00:00Z",
  "applicationId": "com.zfkirke0109.rootlesszachdsp.debug",
  "versionName": "2.0.0-alpha01",
  "versionCode": 100,
  "commit": "abcdef0",
  "engineEpoch": "random-per-service-start",
  "sessionEpoch": "random-per-selected-session-set"
}
```

`engineEpoch` and `sessionEpoch` must be random non-identifying IDs. They are correlation handles, not persistent device identifiers.

## Event types

### `ENGINE_START`

Fields:

- sample rate;
- channel count/layout;
- encoding;
- buffer samples and duration;
- output route class;
- capture-policy mode;
- selected application count and optional hashes;
- active preset hash;
- active module mask;
- wet/dry target;
- build identity.

### `ENGINE_STOP`

Fields:

- reason enum;
- service uptime;
- final counters;
- whether shutdown was clean.

### `SESSION_SET_CHANGED`

Fields:

- session epoch;
- added/removed salted session identities;
- package hash or explicit local-only label;
- UID hash, usage, content type;
- recordability;
- include/exclude decision;
- typed decision reason;
- discovery source.

Decision reasons include:

- `ACCEPTED_MEDIA`;
- `SELF_UID`;
- `SELF_PACKAGE`;
- `SESSION_ZERO`;
- `NON_RECORDABLE_USAGE`;
- `USER_EXCLUDED`;
- `NOT_IN_ALLOWLIST`;
- `RESTRICTED_CAPTURE_POLICY`;
- `AMBIGUOUS_PID_FALLBACK`;
- `CONTRADICTORY_OWNERSHIP`;
- `MALFORMED_RECORD`.

### `TRANSPORT_SNAPSHOT`

Fields:

- sample rate;
- channels;
- buffer samples;
- total read/written samples;
- partial read/write operations;
- zero-progress operations;
- I/O errors;
- recoveries;
- underruns;
- deadline misses;
- bypass buffers;
- last/max processing nanoseconds;
- load EWMA;
- last recovery age;
- last platform error code.

### `RECOVERY`

A discrete event emitted once per recovery rather than repeating a stale reason forever.

Fields:

- typed reason;
- requested and resulting buffer size;
- attempt number;
- downtime estimate;
- route and selected-session-set hash;
- success/failure.

### `UNDERRUN`, `DEADLINE_MISS`, `IO_ERROR`, `BYPASS_ENTER`, `BYPASS_EXIT`

Each anomaly record includes the counter delta, current buffer configuration, load, route class, engine/session epochs, and a typed reason.

### `ROUTE_CHANGED`

Fields:

- previous/new route class;
- device type;
- supported sample rates/channel counts;
- whether the pipeline was rebuilt;
- rebuild result.

Product names are local-only UI data and are redacted from default exports.

### `PRESET_CHANGED`, `MODULE_STATE_CHANGED`, `PARAMETER_CHANGED`

Fields:

- old/new preset hash or module identifier;
- sanitized parameter identifiers;
- no user filenames or external paths;
- whether the engine required soft reload or hard rebuild.

### `SIGNAL_SNAPSHOT`

This is the evidence that DSP changed the signal. It is computed incrementally with reusable buffers and serialized off-thread.

Fields:

- input/output RMS;
- sample peak;
- estimated true peak;
- short-term/integrated LUFS when affordable;
- silence ratio;
- clipped-sample counts;
- DC offset;
- input/output energy delta;
- rolling non-reversible input/output hashes;
- `outputChanged`;
- compressor and limiter gain reduction;
- total and per-module processing time;
- bypass percentage.

Frame/sample content is never persisted.

### `SESSION_SUMMARY`

Fields:

- duration;
- samples read/written;
- transfer abnormalities;
- recoveries, underruns, deadline misses, bypass buffers;
- processing median/p95/max;
- load mean/p95/max;
- input/output loudness and peak deltas;
- `outputChanged`;
- effective preset/module snapshot hash;
- terminal reason.

## Threading requirements

The urgent audio thread may only:

- update primitive counters;
- update preallocated signal accumulators;
- atomically publish an immutable snapshot or sequence number.

It must not:

- open or write files;
- serialize JSON;
- allocate per sample or per frame;
- query package manager, routes, preferences, or storage;
- compute expensive LUFS/true-peak work synchronously.

A bounded single-writer executor performs serialization, rotation, export preparation, and event coalescing.

## Rotation and failure behavior

- Append records atomically as complete lines.
- Rotate before exceeding the configured active-file limit.
- Keep at most one previous file by default.
- A diagnostics write failure must never stop audio processing.
- Track dropped/coalesced event counts in memory and report them in the next successful record.
- Clear history must close the writer, delete both generations, and restart cleanly.

## Export model

The diagnostics UI provides:

- View recent events;
- Copy session summary;
- Preview sanitized bundle;
- Export sanitized bundle;
- Clear history;
- Optional inclusion of package names with explicit confirmation.

A leak test rejects exported data containing:

- filesystem paths;
- content/file URIs;
- Android serials or ADB endpoints;
- raw package names when redaction is selected;
- song/media titles;
- notification content;
- signing secrets or tokens.

## Schema evolution

- Every record has `schemaVersion`.
- Parsers ignore unknown fields.
- Breaking changes increment the version.
- Test fixtures preserve parsing of earlier schemas.
- Export bundles include a schema manifest and application commit identity.

## Delivery stages

1. Transport JSONL persistence and rotation.
2. Discrete recovery/underrun/error events.
3. Session-selection records and typed decisions.
4. Route, preset, and module events.
5. Pre/post signal accumulators and session summaries.
6. Settings diagnostics UI, preview/export/clear actions.
7. Offline deterministic DSP self-test.
