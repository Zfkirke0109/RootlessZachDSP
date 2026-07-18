# MQA legal and technical research

Accessed: 2026-07-17

This document records the integration gate for MQA-related code. It is an engineering record, not legal advice. RootlessZachDSP does not integrate a candidate unless copyright permission, software licensing, patent rights, trademark use, redistribution, completeness, and deterministic validation are all resolved.

## Decision

No lawful, complete, redistributable open-source MQA decoder was verified. RootlessZachDSP therefore implements **Outcome B**: a separate Direct Player, open lossless/high-resolution playback, Android 14+ public USB bit-perfect capability negotiation, and an empty optional source-decoder boundary. The application ships no MQA decoder and makes no MQA rendering claim.

## Candidate classifications

### MQA Labs / Lenbrook licensed software

- Upstream: MQA Labs / Lenbrook.
- Primary sources:
  - https://lenbrook.com/lenbrook-extends-leadership-in-hi-res-audio-with-mqa-acquisition/
  - https://mqalabs.com/eula/
- Copyright and ownership: Lenbrook states that it acquired MQA assets, including patents and the MQA and SCL6 codecs.
- Software license: the published EULA describes paid, limited, revocable, non-transferable rights and reserves deployment terms.
- Redistribution/integration: the EULA does not grant general GPL-compatible source redistribution. It restricts modification, derivative works, incorporation into other products, and reverse engineering except where a separately negotiated agreement says otherwise.
- Patent risk: MQA is expressly described as part of a patent and codec portfolio. Patent rights cannot be inferred from access to binaries, documentation, or a public repository.
- Trademark: use of the MQA name or logos would require separate authorization and accurate conformance rules.
- Technical completeness: potentially complete only through an authorized commercial integration; no SDK or test vectors have been supplied to this project.
- Classification: `LICENSE_INCOMPATIBLE` for current redistribution. A future separately negotiated GPL-compatible authorization would require a new review.

### Public GitHub searches claiming or mentioning MQA decoding

- Search scope: GitHub/web searches for `MQA decoder`, `MQA decoder C`, and related terms on 2026-07-17.
- Result: no repository was found that simultaneously provided a complete MQA decoder, an explicit redistribution license, credible ownership of the relevant implementation, patent authorization, trademark permission, and reproducible conformance vectors.
- A public repository or gist is not itself a redistribution license. GitHub's own licensing documentation notes that absent a license, default copyright restrictions apply.
- Scripts that hide data with general tools, players that call proprietary libraries, carrier detectors, metadata parsers, or upsamplers are not MQA decoders.
- Classification: `RESEARCH_ONLY` and `PATENT_RISK_UNRESOLVED`. None is integrated.

### Reverse engineering or copied proprietary binaries

- Source: any decompiled, extracted, reconstructed, copied, or binary-wrapped proprietary MQA implementation without explicit authorization.
- Software license: no verified right to create or redistribute derivatives.
- Patent/trademark: unresolved.
- Technical validation: provenance and conformance cannot be trusted.
- Classification: `REJECTED`.

### Carrier detection without decoding

- Scope: identifying source metadata that may indicate an MQA carrier, without modifying or decoding it.
- Legal status: format/container metadata detection may be technically separable, but no detector has yet passed provenance, false-positive, and trademark review.
- Product status: UI models include a truthful `CARRIER_DETECTED_DECODER_NOT_INSTALLED` state, but no detector is enabled by this checkpoint.
- Classification: `INCOMPLETE`.

### Authorized optional decoder plug-in boundary

- Source: project-authored interface only; no codec implementation.
- File: `audio/direct/OptionalSourceDecoder.kt`.
- License: RootlessZachDSP's existing GPL terms.
- Patent/trademark: the interface itself implements no codec and grants no third-party rights.
- Redistribution: approved as architecture, not as an MQA implementation.
- Classification: `APPROVED_FOR_INTEGRATION`.

## Technical boundary

MQA source decoding, when lawfully authorized, must operate on the original source before Android playback capture or system mixing. Playback-capture PCM is not guaranteed to preserve the encoded carrier bits required by a source decoder.

The terms must remain distinct:

- **Carrier passthrough:** original source carrier submitted unchanged.
- **First unfold:** decoded PCM differs from the original carrier.
- **Higher-stage rendering:** a separate capability that must not be claimed from a first unfold.
- **Post-decoder bit-perfect transmission:** exact transmission of the decoder's PCM output, not bit identity with the original encoded carrier.

Any DSP, EQ, limiter, gain ramp, fade, loudness operation, resampling, channel conversion, or non-unity software volume disables a bit-perfect claim.

## Lawful alternative selected

The Direct Player workstream will own the original source and support only formats for which the project can verify decoder and redistribution rights. Initial targets are WAV/PCM and platform-supported lossless formats, followed by independently reviewed FLAC and ALAC integrations where needed. Exact USB output uses Android 14+ configurable mixer attributes and accepts only an exact source format with `MIXER_BEHAVIOR_BIT_PERFECT`.

## Re-review requirements

A future MQA candidate must provide all of the following before code is imported:

1. Exact upstream repository or vendor package and immutable version.
2. Full license text and copyright chain.
3. Written redistribution and integration rights compatible with this GPL application.
4. Patent authorization for every distributed territory or a documented reason none is required.
5. Trademark/name usage terms.
6. Complete decoder scope: carrier detection, first unfold, rendering, or passthrough.
7. Lawfully redistributable deterministic vectors and expected outputs.
8. ARM64 Android performance, memory, and malformed-input tests.
9. Clear build-time and runtime isolation with a default-off feature flag.
10. Updated notices and user-facing wording reviewed against actual capability.
