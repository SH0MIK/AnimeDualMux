# MKV SubEx

**MKV SubEx** is an Android app for exporting subtitle tracks from MKV files. The app processes files locally on the device and does not re-encode the video.

## Features

- Extract a subtitle from one MKV and save it as a standalone file.
- Select multiple MKV files and export their subtitle tracks into one ZIP archive.
- Extract all tracks or manually choose tracks per file.
- Shows track index, codec, language and track title when available.
- Uses FFprobe for track discovery and FFmpeg for extraction/conversion.
- Optionally converts ASS/SSA tracks to plain WebVTT (`.vtt`) and strips subtitle formatting tags, leaving AniVault Player free to apply its own styling. Original ASS cue start/end times are copied directly into VTT timestamps at equivalent millisecond precision.
- Supports ASS/SSA, SubRip/SRT, WebVTT and supported image-based subtitle streams. Image-based tracks are not OCR-converted to text.

## Build an APK

Every push, pull request, and manual workflow dispatch runs the **Android APK** GitHub Actions workflow. Open the repository's Actions tab, choose a successful run, and download the **MKV-SubEx-debug-apk** artifact. This is an unsigned debug APK for testing.

## Notes

- Large MKV files are copied into app cache before scanning; make sure the device has enough free storage.
- ASS/SSA-to-VTT conversion preserves each cue's original start/end times at ASS centisecond precision (converted exactly to VTT milliseconds); it does not retime cues. Formatting tags are removed. Disable conversion to preserve the original ASS/SSA track.
- Subtitle extraction does not re-encode the video.
- The project uses the FFmpegKit-maintained GPL package. Review its GPL-3.0 terms and third-party notices before distributing builds.
