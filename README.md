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

## Run locally
```bash
gradle :app:assembleDebug
```

Debug APK output:
`app/build/outputs/apk/debug/app-debug.apk`

## GitHub Pipeline
Workflow file: `.github/workflows/android-apk.yml`

It builds the debug APK on every push/PR and uploads it as an artifact named:
`webtoon-reader-debug-apk`

Download the APK artifact from the workflow run, install it on Android, and test.
