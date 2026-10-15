# Architecture (proposal)

## Stack

- Kotlin, Jetpack Compose, Material 3 with dynamic color (Material You)
- Single Gradle module `:app` to start; split into feature modules only when it hurts
- Room for queue, history, presets, auto-compression rules
- DataStore for app settings
- WorkManager for scheduling; a foreground service for long-running encoding
- DI: Hilt (with `hilt-work` for workers)
- Min SDK 33, target/compile SDK 35 (plan: bump target to latest)

## Layers

```
ui/            Compose screens + ViewModels (queue, history, stats, presets, auto, settings)
domain/        Preset, CompressionJob, Batch, command builder (pure Kotlin, unit-tested)
data/          Room DB, DataStore, MediaStore access, repositories
engine/        FFmpeg wrapper (see docs/ffmpeg.md) behind an interface
work/          Foreground service, WorkManager workers, notifications
```

The **command builder** (preset + source metadata → FFmpeg argument list) is pure Kotlin so it can be fully unit-tested without a device.

## Data model (sketch)

- `Preset(id, name, fps?, crf, ffPreset, videoCodec, audioCodec, maxResolution?, customArgs, container, keepExif, keepLocation, nameTemplate, onDone)`
- `Batch(id, startedAt, finishedAt?, presetId, presetName)` — snapshot the preset name so history survives preset edits/deletion
- `Item(id, batchId, sourceUri, name, originalSize, finalSize?, compressedAt?, status, ffmpegCommand)`
- `AutoRule(id, minAgeDays, presetId)` + auto-compression settings (constraints, window)

Stats are computed from `Item` joined with `Batch`, filtered by period, grouped by preset.

## Background processing

- Interactive batches: foreground service with type `mediaProcessing` (Android 15+; falls back to `dataSync` on 14) and an ongoing progress notification. Handle the system timeout callback by re-queuing the remaining items.
- Auto compression: periodic WorkManager job with constraints (charging, device idle, unmetered optional, battery not low) plus a user-defined time window; it enqueues items and hands off to the same engine.
- Completion notification with actions (reshare / replace originals / save alongside) handled by broadcast receivers or a trampoline activity.

## Storage

- Input via Storage Access Framework / MediaStore URIs (including `ACTION_SEND` and `ACTION_SEND_MULTIPLE` with `video/*`).
- Output: write to app cache first, then publish to MediaStore (`Movies/Squeeze` or alongside the original).
- Replace originals: `MediaStore.createWriteRequest` / `createDeleteRequest` (API 30+) requires a user confirmation; plan the UX for that (notification action opens a transparent activity).
- Preserve `date_taken`/modified time on outputs; handle EXIF / location stripping per preset.

## Queue (as built in M2)

- `QueueManager` is the facade (enqueue, pause/resume, cancel). It resolves sources with `SourceImporter` (a real MediaStore video we may read is used in place, anything else is copied to `files/imports` and flagged `isImported`), writes `batch`/`batch_item` rows and starts the unique `compression-queue` work (`KEEP`, expedited with a non-expedited fallback).
- `CompressionWorker` loops on `nextQueued()`: `ItemProcessor` probes, builds the command from the batch's preset snapshot and encodes into `files/staging/<batch>/<item>.<ext>`. Room is the single source of truth for the queue UI and the notification.
- Stop, pause, system timeout and process death all end the same way: a `RUNNING` row is put back to `QUEUED` (`recoverInterrupted`) and the item restarts from scratch.
- "Skip this one" and "Cancel all" go through `CurrentJob`, which cancels ffmpeg even while the file is still being probed. Cancelled items are `CANCELLED`, not failed.
- Output not smaller than the input becomes `SKIPPED_NO_GAIN` and the staged file is deleted.
- Staged results live in `filesDir`, not `cacheDir`, so the system cannot clear results that still await a user decision.
- Known limitation: the stored ffmpeg command shows ffmpegKit's `saf:N.ext` input token for content URIs.

## Presets and the compression sheet (as built in M3)

- Built-in presets live in `BuiltInPresets` (code); only user presets are rows in `preset`. `PresetRepository` serves both, keeps names unique (case-insensitive, " (2)" suffix) and refuses to modify built-ins ("Duplicate" copies one into an editable preset).
- `CompressionSheetViewModel` holds a *draft* copy of the selected preset plus the files being compressed. The draft never writes to the saved preset until "Update" or "Save as". Starting with unsaved edits snapshots the batch as "<preset> (edited)".
- Everything shown under the controls is pure Kotlin and unit tested: `CommandPreview` (exact command via `FfmpegCommandBuilder` against the first probed file, or a sample clip until then), `SizeEstimator` (bits-per-pixel model, always labelled "≈"), `ParamOptions` (CRF scale per codec, quality hints, container availability with reasons, codec switches that keep equivalent CRF).
- DataStore (`SettingsRepository`) stores the default share preset and the last used one; the sheet opens with last used, else default.
- Preset files are `{"format":"squeeze-presets","version":1,"presets":[...]}`; import assigns fresh ids, drops the built-in flag and uniquifies names. Export goes through `FileProvider` (`cache/presets`) and the system share sheet.
- Android's regex engine rejects an unescaped `}` that the JVM accepts, so regex-heavy code needs a device test (`NameTemplateDeviceTest`).

## Completion actions (as built in M4)

- A finished batch with at least one result is `AWAITING_ACTION`. Its compressed files stay in `files/staging/<batch>/<item>/<final name>` (the name comes from the preset's name template, so resharing shows a proper file name) until someone saves, replaces or discards them. `ResultsRepository` owns that bookkeeping; a batch becomes `DONE` when nothing is left to decide. Results nobody touched are discarded at app start after the retention set in Settings (3, 7, 14 (default), 30 days or never).
- `Finalizer` does the work, `MediaStoreWriter` is the only class that writes to MediaStore. A staged file is deleted only after its result is safely in place, so every operation can simply be run again after an interruption or a missing approval.
  - **Save alongside**: a new row in the original's folder (or `Movies/Squeeze/` for imported sources or folders MediaStore refuses), created with `IS_PENDING=1`, name from the template made unique. The gallery date comes from the file's own `creation_time` (MediaStore re-reads it when a file is published and refuses later updates), which "keep metadata" preserves; with metadata stripped the new file gets today's date (known gap).
  - **Replace originals** (`FinalizePlanner` decides per item): same container → overwrite the bytes in place (`"wt"`), which keeps the URI, albums and favourites; different container → add the new file and delete the original; imported or unknown sources → saved as copies. Each staged file is verified with ffprobe first (video stream present, duration within 1 s). A result that fails verification never touches the original and stays staged.
  - **Reshare**: `ACTION_SEND` / `SEND_MULTIPLE` chooser over `FileProvider` URIs of the staged files.
  - **Discard** deletes staged files; originals are untouched.
- Approval: replacing an original we do not own needs the user's consent. `FinalizeActivity` (invisible) asks once for the whole batch with `MediaStore.createWriteRequest`, runs the replace in `FinalizeWorker` (expedited, foreground notification) and, when originals have to be deleted because the container changed, asks once more with `createDeleteRequest`. With the optional `MANAGE_MEDIA` special access (`MediaStore.canManageMedia`) none of the dialogs appear.
- `BatchCompletion` runs when the queue worker ends a batch: export mode "Save copies" is applied right away, "Replace originals" only when `MANAGE_MEDIA` is granted (otherwise the notification asks), "Ask when done" leaves everything to the notification. The results notification has Reshare / Replace originals / Save alongside; the Queue screen shows the same actions plus Discard in a "Ready to use" list, so nothing depends on the notification.
- Keep location: with `ACCESS_MEDIA_LOCATION` granted the source is opened with `MediaStore.setRequireOriginal` so the GPS tag is not redacted.
- MediaStore facts learned the hard way (all verified on an API 35 emulator): published rows refuse `DATE_TAKEN` / title updates (they return 0 rows); the scan after a write refreshes size, dimensions and date within about a second; a `createWriteRequest` grant is tied to the *plain* URI (not its `requireOriginal` variant), and with `MANAGE_MEDIA` on, the consent request is auto-approved without showing a dialog; the `MANAGE_MEDIA` direct write was refused for a file owned by the shell user (`adb push`), so `FinalizeActivity` falls back to the consent request whenever a replace reports missing access. `MANAGE_MEDIA` on a real gallery file is therefore still to be verified on a real device.

## History and stats (as built in M5)

- No new tables: history and stats are read from `batch` and `batch_item`, which already keep the preset name/id, sizes, times, outcome and ffmpeg command per item. `HistoryRepository` exposes `observeHistory()` (finished batches, newest first; batches whose items were all cancelled are left out) and `observeStats(period)`.
- Stats come from one SQL join (`BatchItemDao.observeStatRows`) and a pure `StatsCalculator`. A video counts when it was compressed (`DONE`) and not discarded, by the time its compression finished. Periods are rolling windows (7 / 30 / 365 days). Groups are keyed by preset id *and* the name the batch ran under, so "Share" and "Share (edited)" or a renamed preset are never merged. "Freed" is the part of the saving from results that replaced their original; saved copies and unused results still count as saved, not freed.
- The History screen (Queue top bar) has two tabs: History and Stats. Tapping an item shows its state, the error if any, and the exact command.

## Auto compression (as built in M6)

- **Config** (`AutoConfig`, DataStore as JSON through `AutoConfigJson`): master switch, schedule mode (`WINDOWS`, `WHENEVER_CHARGING`, `EXTERNAL_ONLY`), local wall-clock windows (`AutoSchedule`, DST safe), age tiers (`AutoRuleResolver`, oldest match wins), charger and battery gates, minimum size and bitrate, skipped folders, output (save copies by default; replace needs `MANAGE_MEDIA` to run unattended, otherwise results wait in the Ready list) and order.
- **Scan and select**: `GalleryScanner` lists MediaStore videos, `CandidateSelector` (pure) picks them: old enough for a tier (capture date, else date added), big and heavy enough, outside skipped folders and `Movies/Squeeze/` (our own fallback folder), and not already handled. "Handled" comes from `batch_item`: compressed, no-gain, queued or running, failed twice, or the `finalUri` of an earlier result (`QueueRepository.skipIds`). Replacing in place keeps the media id, so the original is skipped as well.
- **Run**: `AutoScanWorker` (hourly, constrained to charging when that gate is on) calls `AutoRunner.run`, which checks the mode/window and the gates, queues one `AUTO` batch per preset and starts the normal queue worker. There is no separate engine for auto mode. `AutoRunner.plan` is also the dry run.
- **Stopping**: the run's limits (deadline = end of the window or the external `duration_minutes` / `until`, plus the gates) are stored in DataStore (`RunLimits`). `CompressionWorker` checks them before every `AUTO` item and, while encoding one, every 15 s. When they fail, the current item is cancelled and waiting `AUTO` items are cancelled too (`cancelQueuedAuto`); the next scan picks them up again. Items queued by the user are never affected. A cancelled-only batch is hidden from History.
- **Background start**: the scan runs as a periodic job, queues the queue worker as expedited work (foreground notification) and that worked from the background on an API 35 emulator. Not yet verified on real devices (battery optimisation, OEM restrictions); the open M0 spike remains.
- **Suggestions**: `HeavyVideoSelector` (100 MB and up, biggest first, not handled) feeds a card on the Queue screen that opens the normal compression sheet; without library access the card asks for it once.
- **Automation API**: see [automation.md](automation.md). `AutomationParser` / `AutomationAuth` / `RateLimiter` are pure and unit tested; `AutomationService` checks (switch, rate limit, constant-time token, arguments), executes and logs; the receiver and the trampoline activity are thin.

## Settings, permissions, about (as built in M7)

- **Settings** (`SettingsRepository`, DataStore): theme (system / light / dark) and wallpaper colours, default share preset, result retention, first-run flag. `AppViewModel` loads them before the first frame so the theme and start screen are right; the activity also sets the system bar style from the app's own choice, not the device's.
- **Permissions**: `AppPermission` lists what can be granted (videos, location in videos, notifications, `MANAGE_MEDIA`, battery optimisation); `PermissionsScreen` is both the first-run screen (`OnboardingScreen`, always skippable, never shown again) and the page under Settings. Nothing is required to start: sharing and the photo picker work without video access. `MANAGE_MEDIA` opens the system's own toggle for Squeeze; battery uses the system list (no `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which stores restrict). The Queue and Auto screens still ask in context.
- **Storage**: "Clear" discards every pending result (`Finalizer.discardAllPending`, originals untouched) and sweeps empty staging folders (`pruneEmptyStagingDirs`, which also runs at app start and ignores folders touched in the last minute).
- **Notifications**: sound, vibration and visibility are per channel in the system settings; Settings links to each channel (`progress`, `results`). There is no separate "auto summary" channel yet because notifications are per batch.
- **About**: version, privacy (no `INTERNET` permission, checked in the release manifest), GPL-3 text, the FFmpegKit source offer (a GPL obligation) and the notices of every library bundled in the FFmpegKit AAR (`res/raw/license_*.txt`). `res/raw/keep.xml` stops the resource shrinker from dropping them, and `LicensesTest` fails if a library upgrade removes one. Android library licences are linked (Apache 2.0).
- **Backup**: `allowBackup` is off. The DataStore holds the automation token and the database holds paths of staged files; neither belongs in a cloud backup.
- **Release**: see [distribution.md](distribution.md).
