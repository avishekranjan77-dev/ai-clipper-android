# AI Clipper for Android

An offline-first Android video clipping app built with Kotlin, Jetpack Compose and AndroidX Media3.

## Current features

- Import a video from the Android photo picker or a direct HTTP(S) MP4, M4V, MOV or WebM file URL.
- Choose 1–30 clips, set a maximum clip length from 1 to 3 minutes, and optionally describe the moments the AI should prioritize. Generated clips remain at least 60 seconds long.
- Analyse speech, audio energy, scene changes and faces, then rank meaningful moments.
- Use on-device Qwen3 transcript understanding when its one-time model download is installed; deterministic local signals remain as a fallback.
- Automatically reframe clips using face tracking and a two-person split-screen layout when two faces are present in that specific clip.
- Export in 9:16, 1:1, 16:9 or the source video's original aspect ratio.
- Burn in timed captions with Classic, Bold Pop, Word Focus/Karaoke and Minimal styles. Karaoke highlighting uses word-level timestamps.
- Review actual rendered clips, search by AI title/reason, skip unwanted clips, and save clips individually or in bulk.
- Adjust a rendered clip's framing, zoom, aspect ratio and trim points, then re-render it. The adjustment editor enforces a 60-second minimum.
- Save finished MP4 files to Android Gallery under **Movies/AI Clipper** with descriptive filenames.

## AI and privacy

- Speech recognition uses the Vosk English model downloaded to the device.
- Face detection uses bundled Google ML Kit.
- Semantic highlight selection uses the on-device LiteRT-LM Qwen3-0.6B model when installed.
- No paid API key or account is required. After model downloads, speech and semantic analysis run locally.
- Qwen3 is a text model: it reasons over the transcript and supplied visual/audio summaries; it does not directly inspect raw video frames. Face tracking, scene sampling and video rendering are handled by separate on-device components.
- Model downloads require an internet connection and several hundred megabytes of free storage.

## Current limitations

- YouTube links are recognized but are not downloaded. Use a direct video-file URL or import a local file.
- Direct URL import downloads the source video locally (up to the app's configured 2 GB limit).
- Keep the app open while analysis and rendering run. The current pipeline is not yet a persistent WorkManager background job, so force-stopping the app can interrupt processing.
- Device testing is still required for real-world codec compatibility, model memory use, rendering speed and Gallery behavior.

## Build

The repository's GitHub Actions workflow runs unit tests and creates a debug APK:

```bash
gradle testDebugUnitTest assembleDebug
```

The APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions uploads it as the **ai-clipper-debug-apk** artifact when the workflow succeeds.
