# Squeeze

A native Android app for power users to compress videos in batch (or one by one) — to share them more easily or to reclaim storage. FFmpeg-powered, Material You design.

> Status: feature complete for a first release (milestones M0–M7). Real-device checks of background work and `MANAGE_MEDIA` are still open, see the [roadmap](docs/roadmap.md).

## Highlights

- Share one or many videos from the gallery (or anywhere) into Squeeze; or pick files from inside the app
- Background compression with a progress notification, then an action notification: *reshare*, *replace originals*, *save alongside*
- Full control over FFmpeg parameters, with savable presets
- History of past batches and global stats (per period, per preset)
- **Auto compression**: scans the gallery and compresses old videos during off-peak hours, with a different preset depending on video age

Full details in [docs/product-spec.md](docs/product-spec.md).

## Documentation

| Doc | Content |
| --- | --- |
| [docs/product-spec.md](docs/product-spec.md) | Features and requirements |
| [docs/architecture.md](docs/architecture.md) | Tech stack, modules, data model, background processing |
| [docs/ffmpeg.md](docs/ffmpeg.md) | How FFmpeg is wired up, parameter → CLI mapping |
| [docs/roadmap.md](docs/roadmap.md) | Milestones |
| [docs/automation.md](docs/automation.md) | Automation API (Tasker, MacroDroid, Samsung Routines) |
| [docs/distribution.md](docs/distribution.md) | Signing, releases, F-Droid / IzzyOnDroid / Play |
| [docs/adr/](docs/adr/) | Architecture decision records |

## Build

Requirements: JDK 17, Android SDK (API 35). Android Studio's bundled JDK works.

```sh
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
./gradlew lintDebug
```

CI (GitHub Actions, [.github/workflows/ci.yml](.github/workflows/ci.yml)) runs lint, unit tests and builds the debug APK on every push and pull request; the APK is uploaded as a build artifact.

## Project layout

```
app/                 Android application module (Kotlin, Jetpack Compose, Material 3)
docs/                Specs, architecture, ADRs
gradle/libs.versions.toml   Version catalog
.github/workflows/   CI
```

Application id / package: `app.squeeze` (placeholder — change in `app/build.gradle.kts` and the source tree before publishing).

## Licence

GNU General Public License v3, see [LICENSE](LICENSE). The app bundles FFmpeg with GPL components (x264, x265), so the whole app is GPL.
