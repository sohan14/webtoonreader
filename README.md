# Webtoon Reader APK

Android app for reading Webtoon pages from **PDF** or **image files**.

## Features
- Import PDF and image files from your device.
- Render each page in a long scrolling list.
- OCR text extraction with ML Kit.
- Read pages aloud with Android Text-To-Speech (configured to prefer female voices when available).
- Error log section in UI for failed file/page processing.

## Build requirements
- JDK 17
- Gradle 8.7
- Android SDK Platform 34 + Build-Tools 34.0.0

## Run locally
```bash
gradle :app:assembleDebug
```

Debug APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub auto-build and error detection
Workflow file: `.github/workflows/android-apk.yml`

On every push/PR (and manual dispatch), the pipeline:
1. prints Java/Gradle/SDK versions for diagnostics
2. installs required Android SDK packages (with retry)
3. builds `:app:assembleDebug` (with retry)
4. uploads build logs as `build-logs` (always)
5. uploads APK as `webtoon-reader-debug-apk` (on success)
6. writes build status to the workflow summary

If build fails, download `build-logs` and share `assembleDebug.log`.
