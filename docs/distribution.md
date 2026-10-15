# Distribution

## Signing

The release build reads its keystore from `keystore.properties` in the repository root (gitignored) or from environment variables. Without either it still builds, unsigned.

```properties
# keystore.properties
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Environment variables (used by CI): `SQUEEZE_KEYSTORE_FILE`, `SQUEEZE_KEYSTORE_PASSWORD`, `SQUEEZE_KEY_ALIAS`, `SQUEEZE_KEY_PASSWORD`.

Create the key once and keep it safe; losing it means users cannot update:

```sh
keytool -genkeypair -v -keystore release.jks -alias squeeze -keyalg RSA -keysize 4096 -validity 10000
```

## Releasing

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (max 500 characters, used as the release notes).
3. Tag and push: `git tag v0.1.0 && git push origin v0.1.0`.

[`release.yml`](../.github/workflows/release.yml) then runs the unit tests, builds signed APKs (arm64-v8a, x86_64, universal) and an AAB, verifies the signatures and creates a GitHub release with `SHA256SUMS.txt`. Repository secrets: `SQUEEZE_KEYSTORE_BASE64` (`base64 -w0 release.jks`), `SQUEEZE_KEYSTORE_PASSWORD`, `SQUEEZE_KEY_ALIAS`, `SQUEEZE_KEY_PASSWORD`. The workflow refuses a tag that does not match `versionName`.

Install the arm64 APK on almost every phone; the universal APK is about twice the size.

## Stores

- **GitHub releases / Obtainium / IzzyOnDroid**: work as they are. IzzyOnDroid reads the fastlane metadata and takes the signed APK.
- **F-Droid (official)**: F-Droid builds from source and refuses prebuilt binaries. FFmpegKit's native libraries come from a Maven artifact (`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl`), so inclusion needs either F-Droid's acceptance of that artifact or building FFmpegKit from source in the recipe. The project is GPL-3, has no network permission and no tracking, and `dependenciesInfo` is disabled so the APK has no opaque blob. Not attempted yet.
- **Google Play**: the AAB is produced. Open items before submitting: a Play-compatible signing set-up (upload key), the data-safety form (no data collected), a justification for `MANAGE_MEDIA` (it is optional but declared) and for the `mediaProcessing` foreground service type, and the `READ_MEDIA_VIDEO` declaration. Play's media permission policy may push toward the photo picker only; the app works without the permission for sharing and picking.

## Before a public release

- Add the public source URL in `Licenses.kt` (`SOURCE_URL`) so About links to it.
- Run the open real-device checks from M0 and M6 (foreground service from the background, OEM battery restrictions, `MANAGE_MEDIA` on a real gallery file).
- Add screenshots under `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
- Replace the placeholder launcher icon.
