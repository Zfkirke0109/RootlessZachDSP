# MQA integration gate — 2026-07-17

This engineering record is not legal advice. It defines what RootlessZachDSP may and may not ship until a written authorization is supplied and reviewed.

## Current decision

RootlessZachDSP ships no MQA decoder, renderer, authentication logic, carrier detector, passthrough implementation, proprietary binary, logo, or conformance claim.

The only approved current behavior is truthful compatibility reporting:

- a separately licensed source application may decode before Android playback capture;
- playback-capture PCM cannot prove that an upstream MQA carrier was authentic, decoded, or rendered;
- RootlessZachDSP's DSP and AudioTrack path is not bit-perfect;
- the final Samsung system mix and an external DAC remain outside the measured boundary.

## Primary-source review

### Ownership and patent/licensing context

Lenbrook states that it acquired MQA assets in 2023 and that the acquisition added patents and the MQA and SCL6 codecs to its portfolio:

- https://lenbrook.com/lenbrook-extends-leadership-in-hi-res-audio-with-mqa-acquisition/
- https://lenbrook.com/lenbrook-spins-out-its-content-delivery-business/

This means access to public descriptions, files, metadata, or binaries does not itself grant patent, copyright, redistribution, trademark, or conformance rights.

### Published end-user license

The MQA Labs/Lenbrook published plugin EULA grants limited end-user rights. It prohibits, among other things, creating derivatives, combining or incorporating the software into another product, reverse engineering, and distributing or sublicensing it:

- https://mqalabs.com/eula/

That EULA is not an Android SDK integration license and is not sufficient to redistribute MQA software inside this GPL application.

### Authorized route

MQA Labs provides a partner contact route for product and service integration:

- https://mqalabs.com/contact-us/

A future integration requires a separate written agreement that expressly covers the exact Android architecture, GPL interaction, territories, patents, redistribution, updates, security response, trademarks, logos, test vectors, and permitted product claims.

## Open-source search result

No complete decoder was verified that simultaneously provides:

1. credible implementation ownership;
2. an explicit redistribution license compatible with this project;
3. patent authorization or a documented conclusion that none is required;
4. trademark and naming permission;
5. deterministic conformance vectors;
6. complete carrier detection, decode, rendering, and authentication scope;
7. ARM64 Android safety and malformed-input validation.

A public repository, gist, decompiled library, metadata parser, upsampler, or wrapper around a proprietary binary is not enough. Reverse-engineered and copied proprietary implementations are rejected.

## Required authorization package

Before any MQA-related implementation is enabled, the project must receive and archive:

- immutable SDK/module identity and provenance;
- full license and copyright chain;
- written GPL-compatible redistribution and integration permission;
- patent authorization for intended distribution territories;
- trademark/logo and user-interface wording rules;
- permitted claims for carrier detection, first unfold, rendering, authentication, and passthrough;
- lawfully redistributable conformance vectors;
- Android ARM64 ABI and lifecycle requirements;
- offline/online licensing behavior;
- security-update and vulnerability-disclosure terms;
- a default-off build/runtime isolation plan.

Until every item is resolved, the application must report `AUTHORIZED_MODULE_NOT_INSTALLED` and remain functionally MQA-free.

## Lawful feature path now implemented

The Source Fidelity & Headroom inspector adds value without decoding proprietary formats. It reports the measured AudioTrack-input peak, dBFS headroom, conservative preamp recommendation, measurement boundary, and the exact absence of MQA capabilities. It does not inspect or retain raw PCM.
