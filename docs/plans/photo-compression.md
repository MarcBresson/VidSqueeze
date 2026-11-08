# Photo compression (JPEG, WebP, PNG, AVIF, JPEG XL)

Implementation plan. The implementer has none of the conversation this came from; everything
needed is in this file. Companion context: `docs/architecture.md` (how the app works today).

## Goal

Photos compress through the same queue/batch/finalize pipeline as videos, with:

- Output formats **JPEG, PNG, AVIF** (encoded by the bundled FFmpeg, `ffmpeg-kit-full-gpl`
  8.1.9 — encoders verified present in the AAR), **WebP** (platform `Bitmap` API — the AAR has
  a WebP *decoder* but **no** libwebp encoder) and **JPEG XL** (bundled `libjxl` built as a new
  Gradle module; the AAR has a native `jpegxl` *decoder* only).
- Any image FFmpeg can decode is a valid input, notably **HEIC** (input-only, never an output).
  JXL inputs decode via FFmpeg's native decoder; libjxl is not used for decode.
- A post-encode metadata step that copies EXIF when the preset asks for it.
- Presets are video-only, photo-only, or dual. The preset editor shows **Video** and **Photos**
  tabs, each with its own enable switch; at least one must be on.
- A mixed selection of videos + photos goes into **one batch** with one dual preset; each item
  runs with the params of its own kind.

## Decisions (agreed — do not re-litigate)

1. Preset model: two optional params blocks, `params` (video) and `imageParams` (photos);
   validity requires at least one. Editor = two toggleable tabs.
2. Built-ins: only **Share**, **Balanced archive**, **Max savings** gain image params.
   **Lossless-ish** stays video-only. No new preset rows.
3. Explicit output format only — no "keep original format" for photos.
4. Mixed batch, no splitting. Per-item routing by kind.
5. Mismatched presets in the sheet: grayed out + a caption line explaining why.
6. HEIC supported as input (decode via FFmpeg), never as output. Any input may be encoded to
   any output format (decode is always FFmpeg's job).
7. Animated image inputs (animated WebP/GIF, animated JXL) are **rejected** with an error item.
   Motion photos: compress the still frame only (the appended video is ignored — see
   "Motion photos" below).
8. JPEG via FFmpeg (`mjpeg` encoder): baseline only, no progressive. Documented, accepted.
9. Metadata: JPEG/WebP via `androidx.exifinterface`; JXL via the Exif box written through our
   libjxl JNI; AVIF/PNG get no metadata and a visible warning.
10. `keepExif` off ⇒ copy nothing. `keepLocation` off ⇒ GPS tags dropped from the copied set.
    EXIF orientation is always baked into the pixels at encode and the copied orientation tag
    is reset. (Hermes decision, user-approved.)
11. Out of scope: auto compression of photos, photo compare screen, photo suggestions on Home.
    `GalleryScanner`/`CandidateSelector`/`CompareScreen`/`HeavyVideoSelector` stay video-only.
12. Home picker becomes `VideoAndImages`; share intents accept `image/*`.
13. libjxl: git submodule + `externalNativeBuild` (CMake/NDK) in a new `:libjxl` Gradle module,
    with a CI cache (Gradle build cache + `.cxx`/ccache artefact cache). Nothing binary in the
    repo.
14. FFmpeg does all image decoding; the JNI decode endpoint is implemented but unused for now.
15. `kind` column on `batch` and `batch_item` (Room 2→3 migration).
16. `ImageParams.maxShortSide` with the same never-upscale semantics as video.

## Codec reality (verified against the actual AAR)

Encoder availability in `dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9` (checked with
`strings` over `jni/*/libavcodec.so` in the Gradle cache — do not re-derive from docs, the
maintainer's table claims libwebp and it is **not** in the binary):

| Format | Decode | Encode | Path |
|---|---|---|---|
| JPEG  | mjpeg          | `mjpeg` (built-in)     | ffmpeg |
| PNG   | png/apng       | `png` (built-in)       | ffmpeg |
| AVIF  | libaom / dav1d | `libaom-av1` + `-still-picture 1` | ffmpeg |
| WebP  | native decoder | none in AAR            | platform `Bitmap.compress` |
| JPEG XL | native `jpegxl` decoder | none in AAR | bundled libjxl (new module) |
| HEIC  | `heif` demuxer + hevc | — (input only) | ffmpeg |

FFmpeg has no EXIF writer for still images — hence the dedicated post-encode step.

## Data model

### `engine/FfmpegParams.kt` — additions

```kotlin
@Serializable
enum class MediaKind { VIDEO, IMAGE }

@Serializable
enum class ImageFormat(val extension: String, val mimeType: String) {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    AVIF("avif", "image/avif"),
    JPEG_XL("jxl", "image/jxl"),
}

/** JPEG XL only. libjxl parameters, exposed 1:1. */
@Serializable
data class JxlParams(
    /** 1..9, libjxl default 7 (eagle). Higher = smaller + slower. */
    val effort: Int = 7,
    /** 0.0 = lossless, 1.0 ≈ visually lossless (default), up to 7.0 very lossy. */
    val distance: Double = 1.0,
    /** Modular mode (good for UI shots / true lossless); false = VarDCT (photos). */
    val modular: Boolean = false,
    /** VarDCT colourspace: XYB (libjxl default, best compression) or SRGB (bit-exact roundtrip). */
    val colorspace: JxlColorspace = JxlColorspace.XYB,
    /** 8, 16 or 32 (float). 0 = keep source depth. */
    val bitDepth: Int = 0,
)
@Serializable
enum class JxlColorspace { XYB, SRGB }

@Serializable
data class ImageParams(
    val format: ImageFormat = ImageFormat.JPEG,
    /** JPEG quality 1..100 and WebP quality 1..100 (both map internally). */
    val quality: Int = 85,
    /** JPEG: 4:4:4 instead of default 4:2:0 (`-pix_fmt yuvj444p`). */
    val jpegChroma444: Boolean = false,
    /** PNG: 8 or 16 bit (`rgb24/rgba` vs `rgb48be/rgba64be`, chosen by source alpha/depth). */
    val pngBitDepth: Int = 8,
    /** PNG zlib compression level 0..9 (ffmpeg `-compression_level`). */
    val pngCompression: Int = 6,
    /** AVIF: libaom-av1 CRF 0..63 (30 ≈ high quality). */
    val avifCrf: Int = 30,
    /** AVIF: `-cpu-used` 0..8 (6 ≈ libaom's sweet spot for stills). */
    val avifEffort: Int = 6,
    /** WebP: lossless (`Bitmap.CompressFormat.WEBP_LOSSLESS`, quality ignored). */
    val webpLossless: Boolean = false,
    val jxl: JxlParams = JxlParams(),
    /** Limit applied to the short side, never upscaling. null = keep. */
    val maxShortSide: Int? = null,
)
```

### `Preset` — shape change (breaking, touch every consumer)

```kotlin
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val builtIn: Boolean = false,
    val params: FfmpegParams? = null,          // was non-null; null = photos-only
    val imageParams: ImageParams? = null,      // null = video-only
    val export: ExportOptions = ExportOptions(),
) {
    val kinds: Set<MediaKind>
        get() = buildSet {
            if (params != null) add(MediaKind.VIDEO)
            if (imageParams != null) add(MediaKind.IMAGE)
        }
    fun isValid(): Boolean = params != null || imageParams != null
}
```

Back-compat: old preset JSON has `params` present → decodes as video-only (`EngineJson` already
sets `ignoreUnknownKeys`, so old app versions reading new exports just drop `imageParams`).
Preset export format version stays 1.

**Consumers of `preset.params` that must go nullable-aware** (grep `\.params` to confirm the
list is complete before starting):

- `feature/compress/CompressionSheetViewModel.kt` — `editParams`, preview creation, `start`
  validation (a photos-only preset with a video-only selection is now a *UI gating* matter,
  not a crash).
- `feature/compress/PresetForm.kt`, `feature/presets/*` — see UI phase.
- `engine/CommandPreview.kt`, `engine/SizeEstimator.kt`, `engine/ParamOptions.kt` — take
  nullable and early-return an empty preview/"—" for the missing kind.
- `engine/CodecContainerMatrix.resolveContainer(params: FfmpegParams, ...)` keeps its
  non-null signature; call sites pass `preset.params!!` only on the video path after a null
  check (see `ItemProcessor` below).
- `work/ItemProcessor.kt` — dispatches by kind before touching either field.
- `auto/AutoRuleResolver.kt`, `auto/AutoRunner.kt`, `feature/home/HomeViewModel.kt`
  (estimate) — video-only paths; require `params != null` and skip otherwise (auto stays
  video-only per decision 11, so a photos-only preset is never eligible there — enforce it in
  `AutoRuleResolver` by filtering `preset.params != null`).
- `work/QueueManager.kt` / `data/QueueRepository.kt` — validation `require(preset.isValid())`.
- `engine/BuiltInPresets.kt` — rebuilt (decision 2), see Built-ins section.

### Room (schema 2 → 3)

`data/Entities.kt`: `BatchEntity` and `BatchItemEntity` gain

```kotlin
val kind: MediaKind = MediaKind.VIDEO,
```

(Room auto-converts enums to TEXT.) `SqueezeDatabase` version = 3; migration:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `batch` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'VIDEO'")
        db.execSQL("ALTER TABLE `batch_item` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'VIDEO'")
    }
}
```

Batch kind = the preset's kinds when it's a single kind; `VIDEO` for a dual preset's batch row
(the row is informational; routing uses the *item's* kind). Exported schema 3.json lands in
`app/schemas`; `MigrationTest` gains a 2→3 case asserting the column + default.

`data/QueueRepository.enqueue(source, preset, sources)`: `QueuedSource` gains `kind: MediaKind`
(filled by `SourceImporter`), items insert with it.

### `SourceImporter` (the gate — change both places or buttons open dead screens)

- `mediaStoreVideoId(uri)` becomes `mediaStoreId(uri): Pair<Long, MediaKind>?` parsing
  `…/video/media/<id>` **and** `…/image/media/<id>` (path segments `[volume, "video"|"image",
  "media", id]`). Callers: `SourceImporter.resolveOne`, anything in `finalize/` resolving
  originals.
- `canReadMedia()` splits: a video row needs `READ_MEDIA_VIDEO`, an image row
  `READ_MEDIA_IMAGES`; only a matching readable row avoids the import copy.
- `resolve(uris)` returns `QueuedSource` with the kind detected from the URI, falling back to
  MIME (`contentResolver.getType`) / extension when neither permission matches.
- **Animated-input rejection** (decision 7): ffprobe already runs in `ItemProcessor`; an image
  item whose probe reports > 1 frame (`nb_frames > 1` — add it to `ProbeParser`/`SourceInfo`
  as `frameCount: Int`) fails with message "Animated images are not supported yet."
  Motion photos are HEIC/JPEG with an appended MP4 track — the heif/image2 demuxer yields the
  still frame as the only video stream, so they decode as single-frame images naturally: no
  special code, just a note in the log.

## Engine

### `engine/ImageCommandBuilder.kt` (new, pure Kotlin, JVM-testable)

Same role as `FfmpegCommandBuilder` but for the three FFmpeg-encoded formats. Shape:

```kotlin
data class ImageBuildOptions(val hasZscale: Boolean = true) // unused for now, kept for symmetry
sealed interface ImageBuildResult {
    data class Ok(val args: List<String>, val format: ImageFormat, val issues: List<Issue>) {
        fun display(program: String = "ffmpeg"): String = CommandFormatter.format(args, program)
    }
    data class Invalid(val issues: List<Issue>)
}
object ImageCommandBuilder {
    fun build(params: ImageParams, source: SourceInfo, orientationDegrees: Int,
              input: String, output: String, options: ImageBuildOptions = ImageBuildOptions()): ImageBuildResult
}
```

Rules (unit-test each):

- Header: `-y -i <input> -frames:v 1 -map 0:v:0 -dn` (never map audio, never `-map_metadata` —
  metadata is the post-step's job; `-map_metadata -1` so ffmpeg doesn't write junk).
- JPEG: `-c:v mjpeg -q:v <q>`, `q = (31 - round(quality * 30.0 / 100)).coerceIn(1, 31)`
  (ffmpeg's inverse scale, 2 ≈ ~95%); `-pix_fmt yuvj444p` iff `jpegChroma444`, else
  `yuvj420p`. `-compression_level` is meaningless for mjpeg — do not emit.
- PNG: `-c:v png -compression_level <pngCompression>`; pixel format from `pngBitDepth` +
  source alpha: 8-bit → `rgb24`/`rgba`, 16-bit → `rgb48be`/`rgba64be`. Warn (not error) when
  a 16-bit setting meets an 8-bit source ("output is upconverted — no quality gain").
- AVIF: `-c:v libaom-av1 -still-picture 1 -crf <avifCrf> -cpu-used <avifEffort> -b:v 0`.
- Filters: `orientationDegrees` from the pre-read EXIF (see Post-encode step) emits
  `transpose=…`/`hflip`/`vflip` (90/180/270/mirrored variants) **before** scale — FFmpeg's
  image demuxers do not honour EXIF orientation. Then `maxShortSide` scale with the same
  `if(lt(iw,ih),…)` short-side expression as `FfmpegCommandBuilder.videoFilters`, `setsar=1`.
  Never upscale (skip the filter when the source is already ≤ the limit and dimensions known).
- Output path extension comes from `ImageFormat.extension` — the caller (`ItemProcessor`)
  already derives the staged name from it, mirroring the video container flow.
- HDR stills (PQ/HLG PNGs): out of scope; if `source.isHdr` and target is JPEG, emit a
  *warning* ("HDR photo, output will be clipped to SDR") — a `zscale` chain can be added later
  without touching the interface.

WebP and JXL do **not** go through this builder (no ffmpeg args exist for them). For history's
"exact command" column they still need a display string: each engine returns a one-line
pseudo-command (`webp quality=85 lossless=true src → out.webp`,
`jxl effort=7 distance=1.0 modular=false …`), which `ItemProcessor` stores in
`ffmpegCommand` like any other.

### `engine/ImageEngine.kt` (new seam — one interface, three encoders)

```kotlin
data class ImageEncodeRequest(
    val input: String,          // opaque, like FfmpegCommandBuilder's input
    val output: File,
    val params: ImageParams,
    val source: SourceInfo,
    val orientationDegrees: Int, // 0/90/180/270 + mirrored, from the pre-read EXIF
    val onLog: (String) -> Unit,
    val onProgress: (Float) -> Unit, // 0..1; single-shot for webp/jxl (they have no partial progress)
)
sealed interface ImageEncodeResult {
    data object Success : ImageEncodeResult
    data object Cancelled : ImageEncodeResult
    data class Failed(val message: String) : ImageEncodeResult
}
interface ImageEngine { suspend fun encode(request: ImageEncodeRequest): ImageEncodeResult }
```

Implementations (each `@Inject`-able, wired in `di/AppModule.kt` as a set or a dispatcher):

1. `FfmpegImageEncoder` — wraps the existing `FfmpegKitEngine.run` with
   `ImageCommandBuilder.build(...)`, `durationMs = 0` (progress is indeterminate; `ItemProcessor`
   maps `fraction == null` to the indeterminate bar the queue UI already supports). Returns
   `Cancelled` on `RunResult.Cancelled`. Covers JPEG, PNG, AVIF.
2. `PlatformWebpEncoder` — `ImageDecoder` (honours EXIF orientation + gives us the bitmap)
   from the source URI, then `Bitmap.compress(WEBP_LOSSY, quality, …)` /
   `WEBP_LOSSLESS` into `output`. Limitation to surface as a warning: platform path is 8-bit
   RGBA; a >8-bit source is downconverted (no error). Runs on `Dispatchers.IO`.
3. `JxlEncoder` — the JNI wrapper (see `:libjxl` module). Pixels come from FFmpeg: run a
   minimal raw-video pipe (`-pix_fmt rgba/rgb48be -f rawvideo` to a pipe/temp file) then hand
   the buffer + `JxlParams` to native. Honours `orientationDegrees` via `transpose` in that
   same raw-extraction command. `still-picture` single-frame; writes the Exif box when the
   post-encode step asks (see below).

`engine/ImageEngineDispatcher.kt`: `fun engineFor(format: ImageFormat): ImageEngine`.

### `:libjxl` Gradle module (new)

- `settings.gradle.kts`: `include(":libjxl")`; submodule `third_party/libjxl`
  (`git submodule add https://github.com/libjxl/libjxl.git third_party/libjxl`, pinned tag —
  pick the latest stable at implementation time and record it here).
- `:libjxl/build.gradle.kts`: `com.android.library` + `externalNativeBuild.cmake.path`,
  `ndkVersion` = the NDK AGP already uses, `prefab` publishing so `:app` sees headers. ABIs
  `arm64-v8a`, `x86_64` (matches the app's ABI splits; universal APK gets both).
- CMake: builds libjxl's `jxl_enc-static`/`jxl_dec-static` (+ vendored brotli/highway, all in
  the submodule) into `libjxl.so` exporting the C API (`jxl/encode.h`, `jxl/decode.h`,
  `jxl/types.h`, `jxl/codestream_header.h` only — do not leak C++ headers).
- Kotlin API (inside the module, so the JNI surface is invisible to `:app`):
  `object Jxl { fun encode(input: JxlInput, output: File): Unit; fun decode(file: File): Bitmap? }`
  with `JxlInput(raw pixels, width, height, bitDepth, hasAlpha, effort, distance, modular,
  colorspace, exifBytes: ByteArray?)`. Encode is synchronous (called on Dispatchers.IO);
  cancellation = close the output stream and return a `CancelledException`-style sentinel.
- License: BSD-3 + Apache (brotli/highway). Add `app/src/main/res/raw/license_libjxl.txt`
  (+brotli/highway if not already among the ffmpeg notices) and extend `LicensesTest` —
  it *fails the build* when a bundled library's notice is missing, so this is not optional.
- CI cache: Gradle build cache for the CXX tasks + a `~/.cache/ccache` (or `.cxx`) artefact
  cache keyed on the submodule SHA, so libjxl compiles once per pinned revision, not per run.

## Post-encode metadata step

New `finalize/MetadataCopier.kt` (and a pure testable core `finalize/MetadataFilter.kt`):

- **Pre-encode** (`ItemProcessor`, right after probe): read source EXIF with
  `androidx.exifinterface` (`contentResolver.openInputStream`), keep (a) `orientationDegrees`,
  (b) the full tag map for the copy step. HEIC sources: ExifInterface reads HEIC; if the
  source is neither JPEG/WebP/HEIC (PNG/AVIF/JXL in), there is nothing to copy — note in log.
- **Post-encode** (only when `export.keepExif`):
  - JPEG / WebP out: `ExifInterface(output)` + `setAttribute` from the filtered map, `saveAttributes`.
  - JXL out: pass the filtered bytes into `Jxl.encode(...)` as the Exif box (libjxl
    `JxlEncoderAddExifBox`; lossless JPEG→JXL byte reconstruction is out of scope for v1).
  - AVIF / PNG out: skip, add warning `Issue`-style note to the item log ("This format cannot
    carry the photo's metadata here").
  - **Filter** (`MetadataFilter`, pure, JVM-tested): drop GPS tags (`TAG_GPS_LATITUDE`,
    `…_LONGITUDE`, `…_LATITUDE_REF`, `…_LONGITUDE_REF`, `…_ALTITUDE*`, `…_TIMESTAMP`,
    `…_DATESTAMP`, `…_PROCESSING_METHOD`, `…_SATELLITES`, `…_STATUS`, `…_VERSION_ID`,
    `…_MAPDatum`…) when `!keepLocation`; always reset `TAG_ORIENTATION` to 1 (pixels are
    already rotated); always drop `TAG_THUMBNAIL_*`/embedded thumbnail (it would lie about
    the new pixels).
- Failure of the copy step is a **warning**, never a failed item: the compressed image is
  valid; log it and continue.

## Intake, picker, permissions

- `AndroidManifest.xml:45,50`: add `<data android:mimeType="image/*" />` to **both** existing
  intent-filters in `ShareReceiverActivity` (multiple `data` mimeTypes in one filter is fine).
- `feature/home/HomeScreen.kt:112`: `PickVisualMedia.VideoAndImages`.
- `feature/settings/AppPermission.kt`: add `IMAGES(Manifest.permission.READ_MEDIA_IMAGES,
  recommended = true)` next to `VIDEOS`; `PermissionsScreen`/onboarding pick it up from the
  enum automatically. In-context asks stay `READ_MEDIA_VIDEO` where the screen is video-only.

## Output & finalize

- `finalize/MediaStoreWriter.kt`: parameterise the collection —
  `fun collectionFor(kind: MediaKind): Uri` → `MediaStore.Video.Media…` / `MediaStore.Images.Media…`;
  fallback folder `Pictures/Squeeze` for images (`Movies/Squeeze` stays for videos). MIME type
  from `ImageFormat.mimeType`. Every `MediaStore.Video.Media.*` column reference in the file
  must go through that parameter (RELATIVE_PATH/DISPLAY_NAME/MIME/IS_PENDING/DATE_TAKEN queries
  included — there are several; grep `MediaStore.Video` inside the file and change all).
  All the verified MediaStore behaviours (IS_PENDING, published-row immutability, consent
  grants, `MANAGE_MEDIA`) are collection-agnostic and carry over.
- `finalize/OutputVerifier` (used by `Finalizer.kt:209`): image variant — probe the staged
  image, check *a* video stream exists (that is how ffprobe reports image streams), duration
  ≤ 1 s, and dimensions match the plan (source vs output, allowing the maxShortSide scale);
  do not apply the ±1 s duration tolerance against a 0-duration source.
- `NameTemplate`: `{output_format}` already renders the extension; image items pass
  `ImageFormat.extension`. No change.
- History rows: small Video/Photo pill next to the preset name (reuse `ui/Pill.kt`), from
  `BatchItemEntity.kind`.

## UI (presets, sheet)

### PresetForm tabs (`feature/compress/PresetForm.kt`)

Top of the form: two `SegmentedButton`s or tabs — **Video** and **Photos** — each with an
enable `Switch` in its row. Enabled tab shows the existing video sections (`EncodingSection`
onwards, untouched) or a new `ImageEncodingSection`. Both off ⇒ the Start/Save actions are
disabled and a `Note(error = true)` says at least one kind must be enabled.

`ImageEncodingSection` (mirrors `EncodingSection`'s building blocks — `SectionTitle`,
`Label`, `Note`, `Chips`, `Choice`, `NumberField`, `Slider` all already exist in the file):

- Format chips: JPEG · PNG · WebP · AVIF · JPEG XL (`Choice` rows).
- Quality slider 1..100 with value text (JPEG, WebP) — reuse the CRF slider pattern at
  `PresetForm.kt:168`.
- JPEG: 4:4:4 chip. PNG: bit-depth chips (8/16) + compression `NumberField` 0..9.
  AVIF: CRF `NumberField` 0..63 + effort 0..8. WebP: lossless chip.
  JXL: effort 1..9, distance (decimal field, 0–7, "0 = lossless"), modular chip, colourspace
  chips (XYB / sRGB), bit-depth chips (Auto/8/16/float32).
- `maxShortSide` `NumberField`, shared wording with the video one.
- Under it, the same `ExportSection` (single export block serves both kinds — name template,
  keepExif/keepLocation already apply).

### CompressionSheetViewModel

- `SheetUi` gains `selectionKinds: Set<MediaKind>` (from `loadFiles`: per-URI
  `SourceImporter`-style kind detection; empty until files are loaded → all presets enabled).
- `editParams(change: (FfmpegParams) -> FfmpegParams)` becomes
  `editVideoParams(change: (FfmpegParams?) -> FfmpegParams?)` plus
  `editImageParams(change: (ImageParams?) -> ImageParams?)`,
  `setVideoEnabled(Boolean)` / `setImageEnabled(Boolean)` (toggle writes null / reinstates a
  default `FfmpegParams()` / `ImageParams()`; if both would be off, refuse with a message).
- Preset list rows in the sheet dropdown (`CompressionSheet.kt:101`) and in
  `PresetsScreen`: disabled (`enabled = false`, greyed) when
  `selectionKinds.isNotEmpty() && !selectionKinds.containsAll(preset.kinds)`; each row shows
  Pill("Video") / Pill("Photos") / both pills; when any preset is greyed, one caption line
  under the selector: "Photos and videos are selected — presets that handle only one kind
  are disabled."
- `start()` (VM line 211): validation additionally rejects an empty
  `selectionKinds ∩ draft.kinds` ("This preset cannot compress the selected files") — same
  message the loader path uses; the *loader* check matters because the share receiver can
  enqueue without the sheet.

### Built-ins (`engine/BuiltInPresets.kt`)

- `share` += `imageParams = ImageParams(format = JPEG, quality = 85)`.
- `balanced` += `imageParams = ImageParams(format = AVIF, avifCrf = 30, avifEffort = 6)`.
- `maxSavings` += `imageParams = ImageParams(format = WEBP, quality = 75)`.
- `losslessIsh`: unchanged, video-only.

### ItemProcessor dispatch

`work/ItemProcessor.kt.process(...)`:

- probe (ffprobe handles images; a `null` probe result keeps the current "Could not read a
  video stream" failure — it is genuinely the same failure).
- `if (item.kind == IMAGE)`: read EXIF (orientation + tag map) → build staged name from
  `ImageFormat.extension` (existing `FinalizePlanner.copyName` flow) → `dispatcher
  .engineFor(format).encode(...)` → animate-progress only via the engine's `onProgress` →
  same size checks (`NoGain` semantics unchanged: never replace with a bigger file) →
  metadata copy → `ItemOutcome.Done`. Store the pseudo-command/display string in
  `ffmpegCommand` so History keeps working.
- `else`: the existing video path, with `preset.params` now guaranteed non-null by an early
  `?: return Failed("This preset cannot compress videos")`.
- `CompressionWorker` needs **no changes**: it loops `nextQueued()` and calls `process`.

## Strings (`app/src/main/res/values/strings.xml`)

New keys (naming follows the existing `sec_*`, `enc_*`, `sheet_*` prefixes; text lives here,
never in a composable):

```
sec_image_encoding, img_format, img_quality, img_quality_value, img_jpeg_chroma,
img_png_depth, img_png_compression, img_avif_crf, img_avif_effort, img_webp_lossless,
img_jxl_effort, img_jxl_distance, img_jxl_distance_hint, img_jxl_modular, img_jxl_colorspace,
img_jxl_depth, img_preset_tab_video, img_preset_tab_photos, img_tab_one_required,
pill_video, pill_photos, sheet_preset_kind_mismatch, sheet_preset_cannot_compress,
err_animated_image, img_metadata_not_supported, img_max_side
```

## Deliberately NOT touched

- `auto/` (scanner, selector, rules, runner) — video-only by decision 11.
- `feature/compare/` — `CanCompare` stays video; extend in a follow-up.
- Notifications, `FinalizeActivity` consent flow, `ResultsRepository`, `BatchCompletion`,
  share/reshare plumbing (only the `image/*` intent-filter data lines change).
- `FfmpegCommandBuilder` / `CodecContainerMatrix` / video tests — no signature changes.

## Phases (each ends `assembleDebug` + `testDebugUnitTest` green)

**Phase 0 — libjxl module.** Submodule, CMake, empty Kotlin `Jxl.encode`/`decode` (JNI stubs
can throw `NotImplementedError` at this point), license notices + `LicensesTest` update.
Checkpoint: APK contains `libjxl.so` for both ABIs; build passes.
`export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home && ./gradlew assembleDebug testDebugUnitTest`

**Phase 1 — model + DB.** `ImageParams`/`JxlParams`/`MediaKind`/`ImageFormat`; `Preset` nullable
rework across every consumer (list under "Preset — shape change"); Room v3 migration + schema +
`MigrationTest`. Image encoding not reachable yet. Checkpoint: full build + all existing tests
green (`PresetSerializationTest` extended: old JSON → video-only; round-trip dual).

**Phase 2 — encoders.** `ImageCommandBuilder` + `ImageEngine` + `FfmpegImageEncoder` +
`PlatformWebpEncoder` + real JNI `Jxl.encode` (raw-pixel → codestream, Exif box optional).
JVM tests: `ImageCommandBuilderTest` (each format's args, orientation filters, scale
never-upscale, quality mapping edge cases 1 and 100, warnings vs errors), dispatcher test.
Device/androidTest: `ImageEnginesDeviceTest` (encode a small PNG fixture per format into
staging, probe the result, assert format + dimensions). No emulator on this machine — run in
CI; flag that locally.

**Phase 3 — pipeline.** `SourceImporter` (kind detection, image MediaStore ids, permission
split), manifest filters, picker, `AppPermission.IMAGES`, `QueuedSource.kind` →
`QueueRepository`, `ItemProcessor` image path + animated rejection + EXIF pre-read,
`MetadataCopier`/`MetadataFilter`, `MediaStoreWriter` parameterisation, `OutputVerifier`
image variant. JVM tests: `MetadataFilterTest`, kind-detection tests; device:
`SourceImporterTest` extension, `FinalizerTest` image replace case.

**Phase 4 — UI.** PresetForm tabs + `ImageEncodingSection`, VM edit methods, preset pills +
graying + captions, history pill, strings. `HomeModelsTest`/sheet tests extended for
`selectionKinds` gating (pure logic in top-level functions per project convention).

**Phase 5 — built-ins + docs.** Dual built-ins, `docs/architecture.md` new section ("Photos
(as built in M8)" — formats, encoder split, metadata step, preset kinds), `docs/ffmpeg.md`
encoder-availability table update.

## Pitfalls

- Kotlin files use specific-name imports, no wildcards: any new type referenced in a patched
  file needs its import added in the same pass, or the build fails with `Unresolved reference`.
- The preset-kind gate lives in **two** places (sheet UI graying + `ItemProcessor`/VM
  validation). Widening only the UI yields buttons that enqueue failing items.
- `preset.params` nullability is the biggest blast radius in this plan — do it in Phase 1 in
  one pass, don't sprinkle `!!`; the compiler lists them one at a time and each costs a build.
- Don't pipe gradle output through `tail` (masks exit codes); run bare.
- ExifInterface needs a device for IO tests — keep tag-selection logic pure (`MetadataFilter`)
  so it is JVM-testable.
- `androidx.exifinterface` dependency must be added (`androidx.exifinterface:exifinterface`)
  — check `libs.versions.toml` and add the alias rather than an inline coordinate.
- `-map_metadata -1` on image encode: without it ffmpeg copies container-level junk into
  e.g. PNG tEXt chunks.
- WebP via `Bitmap`: `Bitmap.compress(WEBP_LOSSLESS)` ignores quality; `WEBP_LOSSY` honours
  it. `ImageDecoder` (not `BitmapFactory`) so orientation is applied and 16-bit isn't
  silently half-failed.
- ABI splits: `:libjxl` must ship both `arm64-v8a` and `x86_64` or the universal/x86_64 APKs
  crash on load.
- After Phase 5 update `docs/architecture.md` in the same task — the skill's doc-rot rule.
