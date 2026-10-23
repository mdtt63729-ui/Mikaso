# Insta Video Downloader (yt-dlp)

Android/Kotlin + Jetpack Compose Material 3 app concept, using Chaquopy to run yt-dlp on-device. It has no app-owned backend and does not call a third-party extraction API. Network requests go directly to the target website through yt-dlp.

## Features included in this source

- Dark warm Material 3 UI matching the approved mockup.
- URL input, clipboard paste, Android share intent (`ACTION_SEND`).
- On-device yt-dlp metadata extraction and dynamic format list per URL.
- Video+audio, video-only and audio-only filtering.
- Skeleton loading placeholders and animated screen transitions.
- Download progress polling from yt-dlp progress hooks.
- Copies completed media into Android MediaStore under `Movies/VideoDL` or `Music/VideoDL`.
- Recent download history stored locally in SharedPreferences.
- GitHub Actions workflow builds and uploads a debug APK artifact.

## Build with GitHub Actions

1. Push to `main` or `master`, or open **Actions → Build Insta Video Downloader APK → Run workflow**.
2. Wait for the workflow to finish.
3. Download the `Insta-Video-Downloader-release-apk` artifact. It contains `app-release.apk`, signed with the release keystore.

The workflow first builds FFmpeg (`ffmpeg` + `ffprobe`) from pinned source for Android arm64 using `scripts/build-ffmpeg-android.sh`, and places the binaries into `app/src/main/jniLibs/arm64-v8a/` before the app build.

This workflow produces a **signed release APK**.

### Release signing (required repository secrets)

The release keystore is never committed. The workflow reads it from GitHub Actions secrets:

| Secret | Meaning |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded `release.keystore` file |
| `KEYSTORE_PASSWORD` | Keystore (store) password |
| `KEY_ALIAS` | Key alias inside the keystore |
| `KEY_PASSWORD` | Key password |

The workflow decodes the keystore to a temporary path, builds `assembleRelease`, and removes the keystore afterwards. To rotate the key, replace these four secrets with a new keystore. Keep an offline backup of the keystore and passwords: if they are lost you cannot publish updates that Android will accept as the same app.

## Important technical limits

- `yt-dlp` support depends on each site's current extractor and access rules. No app can guarantee every supported website will work at all times.
- FFmpeg is bundled: `ffmpeg` and `ffprobe` are built from pinned source (FFmpeg n7.1.1) for Android arm64 and shipped in the APK, so separate video/audio streams are merged automatically. Sites that need optional extractor dependencies may still fail.
- The UI shows dynamic qualities returned by yt-dlp. The default filter favours formats that contain both audio and video, to avoid a merge requirement.
- Downloads currently run in an in-app worker thread. A long download may be interrupted if Android kills the process; a production-grade foreground service and persistent Room queue should be added and tested on real devices.
- The concurrent-download setting is presently a UI preference, not an enforced scheduler.
- App cache cleanup, engine update check, and per-download pause/resume are not implemented in this initial source. The engine version is bundled; updating it requires rebuilding the APK.
- `extract_info` may be blocked by a website, require sign-in, or fail when a site changes. Private content, DRM bypass, and unauthorized access are not supported.
- The project is configured for ARM64 (`arm64-v8a`) to keep the APK smaller.

## Legal and privacy note

Use only for videos you own, have permission to download, or that are otherwise legally available for downloading. Public visibility does not automatically grant download or redistribution rights. Follow the target site's terms and applicable copyright law.

## Stack

- Kotlin, Jetpack Compose, Material 3
- Chaquopy + Python 3.11
- yt-dlp
- FFmpeg (bundled, built from source in CI)
- Android MediaStore
- GitHub Actions

## First build validation

This generated source archive has not been executed by GitHub Actions from this environment. Run the workflow once and inspect the build log; dependency/plugin compatibility and extractor behavior must be validated in the actual CI build and on an Android device.
