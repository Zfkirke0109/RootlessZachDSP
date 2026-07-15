# RootlessZachDSP roadmap

| Priority | Workstream | Status |
|---|---|---|
| P0 | Rebrand, upstream credit, signing, Android 16 toolchain | Foundation complete; device validation pending |
| P0 | Partial-buffer transport correctness | Foundation complete; device validation pending |
| P0 | Telemetry, adaptive buffers, fail-open recovery | Foundation complete; device validation pending |
| P0 | Deterministic transport tests | Passing in CI |
| P1 | App allowlist/exclusion UI and diagnostics center | Next milestone |
| P1 | DynamicsProcessing fallback and automation rules | Planned |
| P1 | Metering and independent channel controls | Planned |
| P1 | Ordered DSP graph and convolution overhaul | Planned |
| P2 | Preset interchange, compatibility contributions | Planned |
| P2 | Pitch/time and microphone modes | Experimental |

## Foundation validation

The foundation branch is validated by GitHub Actions against Android API 36 and NDK r28. The gate runs transport unit tests, native/debug APK assembly, Android lint, APK Signature Scheme verification, package and label inspection, 16 KiB ZIP alignment checks, and SHA-256 generation.

The next required gate is installation and route/recovery testing on the Galaxy S23 Ultra before the foundation pull request is merged.

Detailed acceptance criteria are in [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md).
