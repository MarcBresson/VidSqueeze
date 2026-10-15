# FFmpeg integration

Wiring inspired by [ffshare](https://github.com/caydey/ffshare): build an argument list from user settings, run FFmpeg against content URIs (via file descriptors / `saf:` protocol or a cache copy), parse progress from stdout/statistics callbacks, cancel by session id.

Which FFmpeg library to use is an open decision — see [adr/0001-ffmpeg-library.md](adr/0001-ffmpeg-library.md).

## Parameter → CLI mapping (draft)

| Setting | FFmpeg |
| --- | --- |
| Video codec | `-c:v libx264` / `libx265` / `libvpx-vp9` / `libaom-av1`… (depends on build) |
| CRF | `-crf N` |
| Preset | `-preset faster\|fast\|medium\|slow\|slower` (x264/x265 only) |
| Max resolution | `-vf scale='min(W,iw)':'min(H,ih)':force_original_aspect_ratio=decrease` (keep even dimensions) |
| Framerate | `-r N` or `-vf fps=N` (never upscale: apply only if source fps > N) |
| Audio codec | `-c:a aac` / `libopus` / `copy` |
| Container | output extension; check codec/container compatibility (e.g. webm ⇒ vp9/av1 + opus) |
| Keep EXIF / location | `-map_metadata 0` vs `-map_metadata -1`; location is the `location` tag (`-metadata location=`) |
| Custom parameters | appended verbatim, shown in the dialog and stored in history |

## Notes

- Validate codec/container combinations in the dialog before enqueueing.
- Store the final command per item (power-user history).
- Progress = processed time / source duration (probe duration first).
- Skip output if it is not smaller than the original (configurable).
