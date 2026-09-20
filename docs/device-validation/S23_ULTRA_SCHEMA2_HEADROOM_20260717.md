# Galaxy S23 Ultra schema-v2 headroom checkpoint — 2026-07-17

Source: user-exported redacted RootlessZachDSP compatibility report and structured diagnostics. No raw PCM was collected or retained.

## Build and device

- Application ID: `com.zfkirke0109.rootlesszachdsp.debug`
- Version: `2.0.0-alpha01-1072`
- Version code: `100012`
- Device: Samsung SM-S918U1
- Android: 16 / API 36
- Output sample rate: 48 kHz
- HAL frames per buffer: 192

## Transport result

The main engine epoch ran for approximately 435 seconds and adapted through:

1. 16,128 interleaved samples;
2. 8,064 samples;
3. 3,840 samples;
4. 3,072 samples.

The final 3,072-sample generation remained active for approximately 211 seconds without a new underrun. At the final snapshot:

- reads: 41,556,864 interleaved samples;
- writes: 41,553,792 interleaved samples;
- partial reads/writes: zero;
- zero-progress operations: zero;
- I/O errors: zero;
- recoveries: zero;
- reconfigurations: five;
- bypass buffers: zero;
- deadline misses: one;
- maximum consecutive deadline misses: one;
- processing p50: 3.484 ms;
- processing p95: 16.251 ms;
- processing p99: 23.008 ms;
- load EWMA: 0.068.

The one-buffer read/write difference is consistent with an in-flight report boundary and is not evidence of a sustained write failure. The six cumulative underruns occurred before the stable final generation. This run does not justify increasing the final buffer.

## Signal result

Both measured boundaries reported changed output and no recorded sample clipping:

- captured input to DSP-engine output;
- captured input to RootlessZachDSP AudioTrack input.

The final AudioTrack-input telemetry window reached approximately `0.977237` peak, or about `-0.20 dBFS`, leaving approximately `0.20 dB` of measured headroom. Multiple stable-generation windows reached the same limiter ceiling.

This is a valid reason to expose a conservative headroom warning and approximately `-1.80 dB` preamp recommendation for a `-2.0 dBFS` target. It is not proof that Samsung's final speaker mix clipped, because system processing after AudioTrack input remains unmeasured.

## Codec and fidelity boundary

- Rootless playback capture and DSP are not bit-perfect.
- No MQA decoder, renderer, carrier detector, authentication implementation, or passthrough claim is present.
- A separately licensed source application may decode before capture, but post-capture PCM cannot prove upstream authentication or rendering.
- No USB output or external DAC was present in this run.

## Next physical validation

- confirm the unique galaxy launcher resource on One UI Home and the splash screen;
- verify Source Fidelity & Headroom shows `CRITICAL` near the observed limiter ceiling;
- test a quieter preset and confirm the recommendation moves toward zero without ever suggesting positive gain;
- repeat speaker/Bluetooth/USB route tests only when those milestones are ready.
