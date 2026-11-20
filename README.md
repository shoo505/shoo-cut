# Shoo Auto Cut

Native Android (Kotlin) app that automatically keeps **about 50% of a video**
as several segments distributed across the whole timeline, then upscales the
result into a 1280×720 letterboxed canvas with bicubic interpolation and exports
an H.264/AAC MP4.

## Behaviour

- **Retention, not deletion.** 50% means half of the duration is *kept*.
- The keep-ranges are spread across the entire video (equal chunks with equal
  gaps), not the first or last half.
- Segments are chronological and never overlap. The retained duration is
  reported accurately.
- One FFmpeg pass trims all retained ranges, concatenates them, scales and pads
  to 1280×720, and encodes H.264 + AAC.
- The video is streamed through the pipeline; it is never loaded into RAM.

## Architecture

| Class | Responsibility |
| --- | --- |
| `MainActivity` | Thin UI: selection, preview, progress, cancel, open output |
| `VideoPicker` | SAF intents and output-name helper |
| `VideoMetadataReader` | Reads duration/resolution/rotation/audio via `MediaMetadataRetriever` |
| `SegmentSelector` | Deterministic distributed 50% retention plan (pure Kotlin) |
| `FfmpegCommandBuilder` | Builds the single-pass FFmpeg command (pure Kotlin) |
| `VideoProcessor` | Runs the bundled FFmpeg binary with real process cancellation |
| `StorageManager` | Streaming `content://` copies, SAF writes, temp cleanup, free space |
| `ProcessingManager` | Background pipeline, progress, cancellation, temp cleanup |

## Storage

Both input and output use the Storage Access Framework. The selected
`content://` video is streamed into an app-cache temp file because FFmpeg needs a
filesystem path. The finished MP4 is streamed into the user-chosen document URI.
No broad storage permission is requested. Temp files are deleted on success,
failure and cancellation.

## Video engine

`io.github.pao11:ffmpeg-android:1.1.0` (Maven Central) ships `libffmpeg.so` for
`arm64-v8a` and `armeabi-v7a`, built with libx264 and the native AAC encoder,
and includes the `concat`, `scale` (bicubic), `pad`, `transpose` and `trim`
filters. The AAR binary is FFmpeg 2.4.x, so the command avoids newer-only
options (`force_divisible_by`, `-stats_period`) and passes `-strict -2` for the
AAC encoder. Scaling dimensions are computed in Kotlin, so no fragile filter
expressions are used.

## Build

Requirements: JDK 17 and Android SDK platform 34 / build-tools 34.0.0.

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

CI (`.github/workflows/android.yml`) runs both commands on every push/PR and
uploads the APK as the `shoo-cut-debug-apk` artifact.

## Verification status

Unit tests cover the 50% retention maths, segment distribution, ordering,
non-overlap, short/edge durations, invalid metadata, output duration, command
construction, audio mapping and output naming. Automated build verification is
performed by GitHub Actions; physical-device playback is not exercised by CI.
