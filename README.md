# AnimeDualMux

Android-first anime MKV converter. Pick one MKV and the app scans its streams, auto-selects Japanese audio and a text-based English subtitle when possible, and lets you manually override both selections.

## Current MVP

- Android app built by GitHub Actions.
- Uses FFprobe to inspect video, audio, and subtitle streams.
- Auto-detects Japanese audio and English text subtitles; manual track pickers remain available.
- Exports the selected subtitle, burns it into a video rendition, and makes a clean rendition.
- Writes one MKV with two video tracks, all source audio tracks (Japanese first), and no subtitle tracks.
- Re-probes the result and validates exactly two video streams, zero subtitle streams, and preserved audio-track count.
- Uses a software H.264 encoder for the first stable implementation.

## Important format limitation

Matroska stores audio and video as separate tracks. A normal MKV does not reliably bind one set of audio tracks exclusively to one video track. The output includes the selected Japanese audio and every other audio track, but a player may allow any audio track with either video track. LuluStream may also re-encode the upload and discard or alter tracks; verify its output before relying on this layout.

The first version supports text subtitle codecs (SRT/SubRip, ASS/SSA, WebVTT and similar). Image subtitles such as PGS/VobSub need OCR or another conversion path and are rejected with an explanatory error.

## Build APK

Every push to main or master, pull request, and manual workflow dispatch triggers **Android APK**. Open the repository's Actions tab, select the successful run, and download the **AnimeDualMux-debug-apk** artifact. The APK is unsigned debug output for testing.

## Notes

- Conversion runs on-device. Large episodes need temporary free storage for the copied input, subtitle export and output.
- Keep the app open and the device plugged in during long conversions.
- The app links the FFmpegKit-maintained GPL package. Review its GPL-3.0 terms and included third-party notices before distributing APKs; the app source and linked FFmpeg package must be handled compatibly with those terms.
