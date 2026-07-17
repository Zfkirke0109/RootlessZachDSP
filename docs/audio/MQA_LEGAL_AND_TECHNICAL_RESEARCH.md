# MQA legal and technical research

Accessed: 2026-07-17

This document records the source and licensing gate used by RootlessZachDSP. It is an engineering compliance record, not legal advice.

## Decision

No complete MQA decoder was found that is both technically validated and explicitly licensed for redistribution inside this Android application. RootlessZachDSP therefore implements **Outcome B**:

- no MQA decoder is shipped;
- no reverse-engineered MQA code is copied, reconstructed, decompiled, or derived;
- an empty optional source-decoder interface is present for a future separately authorized plug-in;
- the Direct Player supports lawful open lossless playback;
- UI wording distinguishes carrier passthrough, first unfold, higher-stage rendering, and post-decoder PCM;
- sample-rate conversion, oversampling, EQ, or filtering is never called MQA.

## Classification rules

- `APPROVED_FOR_INTEGRATION`: explicit compatible software licence, redistribution permitted, known ownership, no unresolved codec/patent gate for the intended use, and enough technical evidence to validate the implementation.
- `RESEARCH_ONLY`: useful source information but not redistributable in this application.
- `INCOMPLETE`: not a complete or validated MQA decoder.
- `LICENSE_INCOMPATIBLE`: public terms do not permit bundling or redistribution in RootlessZachDSP.
- `PATENT_RISK_UNRESOLVED`: a software licence alone would not resolve the relevant codec/patent rights.
- `REJECTED`: wrong technology, no licence, misleading claim, or unsafe provenance.

## Candidate audit

### Lenbrook / MQA Labs commercial software and SDK path

- Upstream/owner: Lenbrook Industries Limited / MQA Labs.
- Sources:
  - https://mqalabs.com/eula/
  - https://mqalabs.com/terms-and-conditions/
- Public licence result: the published EULA is a limited, non-transferable end-user licence. It prohibits incorporating the software into other products, modifying it, reverse engineering it, sublicensing it, and distributing it to third parties without separately granted rights.
- Patent result: active bandwidth-extension patent records include US9548055B2, assigned to Lenbrook Industries Limited in 2024. Patent status must be evaluated in every distribution territory by qualified counsel and the rights holder.
  - https://patents.google.com/patent/US9548055B2/en
- Trademark result: MQA Labs states that its names and logos are proprietary marks and does not grant general permission to use them.
- Technical completeness: authorized implementations may be complete, but no Android redistribution SDK, executable agreement, test vectors, or redistribution grant was provided to this project.
- Commercial/noncommercial distinction: the public terms do not create a free noncommercial redistribution exception.
- Classification: `LICENSE_INCOMPATIBLE` for direct integration under the public terms; `RESEARCH_ONLY` as a potential future commercial licensing route.
- Required change to classification: a signed agreement explicitly granting Android application integration, redistribution, patent rights, trademark wording, update rights, test-vector rights, and distribution terms.

### Public repositories or snippets described as an “open-source MQA decoder”

- Search scope: GitHub/web searches for MQA decoder, first unfold, C/C++ decoder, licence files, and source provenance.
- Exact approved repository: none.
- Results were unrelated decoders, ambiguous uses of the acronym “MQ,” unlicensed snippets, or projects without a complete validated MQA decode path and patent authorization.
- A public GitHub repository is not sufficient permission by itself. GitHub documents that an unlicensed repository remains under default copyright restrictions:
  - https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository
- Technical completeness: no candidate supplied deterministic lawful vectors proving carrier detection, first unfold, metadata authentication, or higher-stage rendering.
- Classification: `REJECTED`, `INCOMPLETE`, and `PATENT_RISK_UNRESOLVED` depending on the individual item. No code was imported.

### FFmpeg `mqc` / `mqcdec`

- Upstream: FFmpeg.
- Source examples:
  - https://ffmpeg.org/doxygen/7.0/mqcdec_8c_source.html
  - https://ffmpeg.org/doxygen/7.0/mqc_8c_source.html
- Licence: LGPL-2.1-or-later for the displayed files.
- Technical finding: these implement an arithmetic MQ-coder used by image codecs. “MQ” here is not Master Quality Authenticated audio and is not an MQA unfold or renderer.
- Classification: `REJECTED` as an MQA candidate.

### AndroidX Media3 / ExoPlayer

- Upstream: Android Open Source Project / AndroidX Media.
- Repository: https://github.com/androidx/media
- Licence: Apache-2.0.
- Redistribution: permitted under Apache-2.0 with required notices.
- Technical scope: source-owning local playback using platform and bundled Media3 extractors/decoders. It does not provide MQA decoding.
- Classification: `APPROVED_FOR_INTEGRATION` for the lawful Direct Player and open lossless alternative; not an MQA implementation.

### FLAC reference implementation and format

- Upstream: Xiph.Org Foundation / FLAC contributors.
- Repository: https://github.com/xiph/flac
- Library licence: Xiph BSD-like licence for libFLAC/libFLAC++ according to the upstream repository.
- Patent/trademark finding: no MQA dependency; this is a separate open lossless format.
- Classification: `APPROVED_FOR_INTEGRATION` as a lawful lossless format through Android/Media3. RootlessZachDSP does not copy the GPL command-line tools.

### Optional source-decoder interface in RootlessZachDSP

- Upstream: this repository.
- Licence: repository licence.
- Technical scope: an empty interface and registry that cannot claim a first unfold unless an authorized decoder is explicitly registered.
- Classification: `APPROVED_FOR_INTEGRATION`.

## Truthful state definitions

- **Original carrier passthrough**: encoded carrier bytes are delivered unchanged to a compatible downstream decoder. No unfold is claimed.
- **First unfold**: an authorized source decoder has converted the carrier to a higher-rate PCM representation. The resulting PCM is not bit-identical to the original encoded carrier.
- **Higher-stage rendering**: an authorized renderer performs additional MQA-specific processing after decode. It must never be claimed by a first-unfold-only implementation.
- **Post-decoder PCM transmission**: PCM may be transported exactly after decoding when format, volume, mixer, routing, and DAC validation all pass. This is not identity with the original encoded carrier.

## Remaining legal blockers

1. No signed Lenbrook/MQA licence.
2. No authorized Android decoder binary or source package.
3. No redistribution terms.
4. No patent grant or covenant for this application.
5. No approved trademark wording.
6. No legally redistributable deterministic MQA test vectors.

Until all six are resolved, the production feature state remains **MQA decoder unavailable**.
