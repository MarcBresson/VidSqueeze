# 0001. FFmpeg library

- Status: accepted
- Date: 2026-10-14

## Context

The app needs CRF-based encoding, arbitrary codecs/containers and user-supplied custom arguments, i.e. a real FFmpeg, not just a hardware transcoder. ffshare wires FFmpeg through ffmpeg-kit; the upstream ffmpeg-kit project has been retired (binaries pulled from Maven Central on 2025-04-01), so maintained forks were checked.

## Options

1. **A maintained ffmpeg-kit fork** (Maven artifact).
   - `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl` (chosen, see below).
   - `io.github.jamaismagic.ffmpeg:ffmpeg-kit-*-16kb` (`full-gpl` variant, one maintainer, version/libs less clearly documented).
   - Others found (moizhassan, wekor, LucQuebec) are Android-only or LGPL-only or not on Maven.
2. **Build FFmpeg ourselves via NDK** with a minimal JNI wrapper: full control, high maintenance. Kept as the fallback if the fork goes stale.
3. **Media3 Transformer**: hardware accelerated, but no CRF or custom args. A possible complement for a fast path, not a replacement.

## Decision

Use **`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`** (Maven Central, GPL-3.0 variant). The 7.1.7 and 6.0.4 lines are published too, as a fallback if 8.1 misbehaves.

Inspected the AAR (2026-10-14):
- Free: all `-gpl` variants are published openly; the paid "Pro" tier only adds Whisper/OCR/fingerprinting.
- ABIs: `arm64-v8a` and `x86_64` only (no 32-bit ARM, fine at minSdk 33). All `.so` files have 16 KB LOAD alignment.
- Encoders present: libx264, libx265, libvpx-vp9, libaom-av1, `h264_mediacodec`, `hevc_mediacodec`, libopus, libmp3lame, aac.
- Filters present: `zscale` (libzimg), `tonemap`, `colorspace`, so the HDR to SDR chain is available.
- **No SVT-AV1.** AV1 encoding uses libaom, which is slow on a phone, so AV1 is offered as an opt-in.
- Size: about 35 MB per ABI of native libraries.
- Release cadence: last published 2026-09-26. Single-vendor project, so mirror the AAR if it is ever pulled.

The engine stays behind `engine/FfmpegKitEngine`; nothing else imports `com.arthenica`.

## Consequences

The app must be GPL-3 compatible (x264/x265 are GPL). Licence notices from the AAR (`res/raw/license_*.txt`) must be shown in About. AV1 speed is poor until an SVT-AV1 build exists.
