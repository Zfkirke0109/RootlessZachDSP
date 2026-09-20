# RootlessZachDSP lossless, USB, and app-capture audit

Date: 2026-07-18

This is an engineering and licensing audit, not legal advice. It describes the public Android API
contract and the exact RootlessZachDSP implementation. It does not treat a high sample rate, a DAC
display, or an app-observable route as proof that physical USB samples are unchanged.

## Current architecture

The existing rootless service is a playback-capture processor:

`other app -> Android playback capture -> AudioRecord -> JamesDSP -> AudioTrack -> Android mixer`

That path is intentionally not called bit-perfect. Bypass still performs capture and replay through
Android PCM endpoints, and the current transport is constrained to its negotiated 44.1/48 kHz
stereo format.

The source-owning Direct Player is a separate path:

`local file -> lossless decoder -> exact PCM -> selected USB AudioTrack`

On Android 14 or newer, it queries `AudioManager.getSupportedMixerAttributes()` and accepts only an
exact-format USB candidate whose behavior is `MIXER_BEHAVIOR_BIT_PERFECT`. It then sets and reads
back the preferred mixer attributes, selects that USB device on the same `AudioTrack`, keeps app
volume at unity, and bypasses ReplayGain, JamesDSP, fades, loudness, and other transforms. Android
documents that mixer behavior as disabling framework mixing, volume adjustment, effects, and
sample-rate conversion for that stream. Support is optional for a device/vendor audio HAL.

The app does not ship a replacement USB Audio Class driver. Standard UAC routing is supplied by the
Android USB audio stack. A custom USB-host isochronous driver would be a different, much larger
subsystem and would still depend on the DAC, USB host controller, and device policy.

## Requested-feature classification

| Feature | Classification | Exact boundary |
|---|---|---|
| Detect standard USB DACs and route the app's own playback | Fully implementable | Public `AudioDeviceInfo` and `AudioTrack.setPreferredDevice`; Android supplies UAC. |
| Query USB PCM formats and native rates | Fully implementable on Android 14+ when the vendor exposes mixer attributes | The app rejects absent, non-exact, and non-bit-perfect mixer candidates. |
| Framework bit-perfect mixer path | Implementable with device limitations | Requires API 34+, a connected USB output, an exact advertised format, and vendor/HAL support for `MIXER_BEHAVIOR_BIT_PERFECT`. |
| Exclusive output before API 34 | Not verifiable with public APIs | AAudio exclusive mode can reduce sharing but is not proof of no mixing/resampling and may be denied. |
| Physically verified bit-perfect USB samples | Requires external validation | Only a matching digital-loopback or USB-payload capture for the exact format earns `EXTERNALLY_VERIFIED`. A DAC rate display is insufficient. |
| FLAC decode and metadata | Fully implementable | Native decoder preserves source rate, significant bit depth, 1-8 channel layout, Vorbis comments, ReplayGain metadata, artwork, and STREAMINFO MD5. ReplayGain is not applied in direct mode. |
| WavPack lossless decode and metadata | Fully implementable | Official BSD-3-Clause decoder can decode `.wv`, APEv2 tags, ReplayGain, and artwork. |
| WavPack Hybrid without `.wvc` | Fully implementable, lossy by definition | Must display `HYBRID_LOSSY`; it cannot be labeled lossless. |
| WavPack Hybrid with matching `.wvc` | Fully implementable | Must open both streams and confirm correction use before displaying `HYBRID_CORRECTED_LOSSLESS`. |
| Preserve arbitrary multichannel playback | Implementable with limitations | Decoding preserves 1-8 channels. Direct output also requires the DAC/HAL to advertise the exact Android channel mask. JamesDSP enhanced playback currently refuses non-stereo instead of silently downmixing. |
| Choose installed apps for capture | Implementable with package-visibility limitations | The F-Droid flavor can enumerate installed apps; saved package choices compile to UIDs. Removed/hidden packages are handled without retaining a false active UID. |
| Capture another app's PCM | Implementable only when Android and the source app allow it | Requires `RECORD_AUDIO`, a user-approved foreground MediaProjection, same user profile, and an effective `ALLOW_CAPTURE_BY_ALL` policy. |
| Transparently insert JamesDSP into every other app's output | Requires root, privileged/vendor integration, or cooperation from that app | An ordinary APK cannot install a system AudioFlinger effect or reroute another UID's protected output. Playback capture copies audio; it is not transparent in-process insertion. |
| Shizuku override of DRM/no-capture policy | Blocked | Shizuku runs with shell authority, not root/signature authority, and cannot lawfully defeat app-private storage, DRM, secure surfaces, or an effective no-capture policy. |
| Amazon Music streaming/download processing | Implementable only if a specific Amazon stream voluntarily allows playback capture | The supplied device evidence contains no Amazon session and no Amazon capture result. Downloads are private/DRM-controlled. No support claim is made until legal device tests show capturable PCM without breaking playback. |
| Force Amazon Music's USB rate, exclusive route, or gapless behavior | Blocked for a rootless third-party app | RootlessZachDSP cannot configure mixer attributes for Amazon's `AudioTrack` or take ownership of its DRM pipeline. |
| Direct / Enhanced / Automatic modes for app-owned files | Fully implementable | Direct refuses rather than falls back; Enhanced runs actual JamesDSP; Automatic prefers an eligible USB contract, then stereo JamesDSP, then honestly labeled ordinary Android playback. |

## Playback truth model

Every status surface must keep these facts separate:

1. Source: codec, lossless/hybrid state, native rate, significant bit depth, channel layout, tags,
   ReplayGain metadata, and artwork.
2. Decoded PCM: actual container encoding and whether downmix, ReplayGain, dither, conversion, or
   app resampling occurred.
3. Requested output: selected device and exact `AudioFormat` requested from Android.
4. Observed output: `AudioTrack.format` and `AudioTrack.routedDevice` after data is written.
5. Android mixer contract: unsupported, eligible, accepted/read back, or no longer active.
6. DSP: disabled, or the concrete preference-backed JamesDSP stages that were synchronized before
   the first sample.
7. Resampling: `none by Android bit-perfect contract`, `performed by app`, or `unknown`. Unknown is
   never silently rendered as none.
8. Verification tier: `UNAVAILABLE`, `ELIGIBLE`, `ANDROID_BIT_PERFECT_MIXER_CONTRACT_ACTIVE`,
   `ROUTED_DEVICE_CONFIRMED`, or `EXTERNALLY_VERIFIED`.

Only the last tier may be rendered as "verified bit-perfect." The external evidence record must
identify the exact PCM format and a digital-loopback PCM hash match or USB-protocol payload hash
match. No such evidence ships with the app today.

## Supplied Galaxy S23 Ultra evidence

The 2026-07-17/18 exports identify an SM-S918U1 on Android 16 / API 36. They show a healthy long-run
48 kHz stereo rootless capture/replay transport with zero partial-write, zero-progress, or I/O-error
events, plus five recoveries, nine reconfigurations, seventeen underruns, sixty-six deadline misses,
and two current `AudioTrack` underruns. The final snapshot was silent, while earlier active snapshots
showed JamesDSP changing samples without clipping.

Those exports also show:

- no connected USB output;
- no supported mixer-attribute sample from a connected DAC;
- `bitPerfect=false` for the rootless capture/replay pipeline, correctly;
- YouTube and RootlessZachDSP sessions, but no Amazon Music package/session;
- capture policy `EXCLUDE_SELECTED` with no user-selected packages (the compiled self UID is still
  excluded);
- no fatal exception, ANR, or permission failure in the captured interval.

Therefore the logs validate transport stability and truthful negative capability reporting. They do
not validate USB routing, FLAC/WavPack playback, Amazon capture, or physical bit-perfect output.

## Device-validation gate

An implementation is releasable only after the build/unit/static checks pass. Feature-specific
claims additionally require the following device evidence:

### FLAC and WavPack

1. Decode official/reference mono, stereo, high-rate, 16/20/24-bit, and multichannel vectors.
2. Compare decoded interleaved PCM hashes against `flac -d` / `wvunpack` reference output.
3. Test corrupt/truncated files, non-seekable document-provider streams, repeated tags, ReplayGain,
   large artwork, and storage exhaustion during seekable staging.
4. Test WavPack lossless, hybrid core-only, matching correction, missing correction, and mismatched
   correction. The displayed integrity state must follow the decoder result.

### USB

1. Capture the connected `AudioDeviceInfo`, all supported mixer attributes, the chosen exact format,
   `setPreferredMixerAttributes` result/readback, `AudioTrack.format`, and post-write routed device.
2. Exercise 44.1/48/88.2/96/176.4/192 kHz and supported 16/24/32-bit containers without changing
   source metadata or silently widening a claim.
3. Disconnect/reconnect during playback and confirm the preference, track, and device callback are
   cleaned up.
4. For literal bit-perfect verification, record digital loopback or USB payload and compare PCM
   hashes for each exact format. Store the evidence artifact reference; otherwise stop at the
   Android-contract or routed-device tier.

### Other apps and Amazon Music

1. Select one app, approve MediaProjection, and capture its effective policy and actual PCM/silence
   result. Repeat with the app deselected.
2. Test streaming and downloaded Amazon playback independently, without attempting to access app
   private files or bypass DRM.
3. Record whether playback capture is allowed, whether duplicate playback/latency occurs, and
   whether Amazon retains its reported resolution, gapless transitions, and route.
4. A blocked or silent result is a supported negative outcome, not a reason to weaken Android
   policy or display a success state.

## Primary platform and codec references

- Android USB audio: <https://source.android.com/docs/core/audio/usb>
- Android preferred mixer attributes: <https://source.android.com/docs/core/audio/preferred-mixer-attr>
- `AudioManager`: <https://developer.android.com/reference/android/media/AudioManager>
- `AudioMixerAttributes`: <https://developer.android.com/reference/android/media/AudioMixerAttributes>
- Audio playback capture: <https://developer.android.com/media/platform/av-capture>
- Package visibility: <https://developer.android.com/training/package-visibility/declaring>
- Android audio effects architecture: <https://source.android.com/docs/core/audio/audio-effects>
- FLAC format and metadata: <https://xiph.org/flac/format.html>
- WavPack library and license: <https://github.com/dbry/WavPack>
- Amazon Music playback API requirements: <https://www.developer.amazon.com/docs/music/requ_AM-Program-Requirements.html>
