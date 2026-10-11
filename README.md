# MKV Subtitle Extractor

Android app for exporting subtitle tracks from MKV files. The app processes files locally on the device and does not re-encode the video.

## Features

- Extract a subtitle from one MKV and save it as a standalone file.
- Select multiple MKV files and export their subtitle tracks into one ZIP archive.
- Extract all tracks or manually choose tracks per file.
- Shows track index, codec, language and track title when available.
- Uses FFprobe for track discovery and FFmpeg stream copy for extraction.
- Supports ASS/SSA, SubRip/SRT, WebVTT and supported image-based subtitle streams. Image-based tracks are not OCR-converted to text.

## Build an APK

Every push, pull request, and manual workflow dispatch runs the **Android APK** GitHub Actions workflow. Open the repository's Actions tab, choose a successful run, and download the **MKV-Subtitle-Extractor-debug-apk** artifact. This is an unsigned debug APK for testing.

## Notes

- Large MKV files are copied into app cache before scanning; make sure the device has enough free storage.
- ASS/SSA formatting is retained when the stream can be copied directly.
- Subtitle extraction does not re-encode the video.
- The project uses the FFmpegKit-maintained GPL package. Review its GPL-3.0 terms and third-party notices before distributing builds.
