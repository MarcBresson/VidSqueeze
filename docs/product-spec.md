# Product spec

Squeeze lets power users compress videos in batch (or one by one) to share them more easily or reduce storage. Design inspiration for the FFmpeg wiring: [ffshare](https://github.com/caydey/ffshare). Design system: Material You (inspiration: [Reef](https://github.com/aload0/Reef)).

## Entry points

1. **Share into the app** — from the gallery or any app, share one or several videos; the compression dialog opens pre-filled with default parameters.
2. **Pick in the app** — select files from the device.
3. **Suggestions** — the app proposes heavy videos worth compressing.

## Background processing & notifications

- Compression runs in the background with an ongoing notification: progress of the current file and "files done / total".
- On completion, a new notification offers actions: **Reshare**, **Replace originals**, **Save alongside**.

## Compression dialog

Presets can be loaded and saved from the dialog.

**FFmpeg parameters (common to the batch)**

- Output framerate
- CRF
- FFmpeg preset (faster, fast, medium, slow, slower)
- Video codec
- Audio codec
- Max video resolution
- Custom FFmpeg parameters
- Output format (original, mp4, mkv, webm, avi, …)

**Export options**

- When done: replace originals, or save copies
- If saving copies: file name template, default `{original_file_name} - compressed.{output_format}`
- Keep EXIF tags
- Keep location data

## In-app screens

### Queue
Current queue of items waiting to be compressed, with progress.

### History
Past batches (a single-item batch is shown as the item directly):

- Batch start and finish date/time
- Per item: name, original size, final size, size reduction, compression date/time, preset name
- Power users: the exact FFmpeg command used

### Stats
Global stats over past compressions.

- Period filter: all time, past year, past month, past week
- Grouped per preset (different presets are never merged)

### Presets
Create, edit, delete, import/export presets.

### Auto compression (killer feature)
Scans the gallery and compresses older videos during off-peak hours, using presets chosen by video age. Example rules:

| Video age | Preset |
| --- | --- |
| > 1 month | Reduce framerate to 30 fps |
| > 1 year | Framerate 24 fps, CRF 25, max resolution FHD |

Open questions: off-peak definition (charging + idle + unmetered + time window), what to do with originals (default: keep, or replace only if opted in), skipping already-compressed files.

### Settings
- Notifications
- About
