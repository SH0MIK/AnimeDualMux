# AnimeDualMux

Android-first anime MKV converter. Select an MKV, then manually choose the Japanese audio stream and the subtitle stream to burn in.

## Current MVP

- Android app built by GitHub Actions.
- Uses FFprobe to list video, audio, and subtitle streams.
- Manual audio/subtitle selection (no language auto-detection).
- Exports the chosen text subtitle and burns it into one video rendition.
- Writes one MKV with two video tracks, all source audio tracks (Japanese first), and no subtitle tracks.
- Re-probes the result and validates exactly two video streams, zero subtitle streams, and preserved audio-track count.
- Uses software H.264 encoding for compatibility on Android.

## Subtitle support

Text-based subtitles such as ASS/SSA, SRT/SubRip and WebVTT are supported when the FFmpeg build includes the `subtitles`/libass filter. ASS styling is retained by copying the embedded subtitle stream to a temporary file before burning it in. Image-based subtitles such as PGS/VobSub need OCR and are not supported yet.

## Important format limitation

Matroska stores audio and video as separate tracks. A normal MKV does not reliably bind one set of audio tracks exclusively to one video track. The output includes all audio streams, with the selected Japanese stream first and marked default, but a player may allow any audio track with either video. LuluStream may also re-encode the upload and discard or alter tracks; verify its output before relying on this layout.

## Build APK

Every push to main or master, pull request, and manual workflow dispatch triggers **Android APK**. Open the repository's Actions tab, select the successful run, and download the **AnimeDualMux-debug-apk** artifact. The APK is unsigned debug output for testing.

## Notes

- Conversion runs on-device. Large episodes need free storage for the copied input, temporary subtitle and output.
- Keep the app open and the device plugged in during long conversions.
- The app links the FFmpegKit-maintained GPL package. Review its GPL-3.0 terms and included third-party notices before distributing APKs; the app source and linked FFmpeg package must be handled compatibly with those terms.
