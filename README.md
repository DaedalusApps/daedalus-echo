# Daedalus Echo

A standalone Android voice recorder, transcription, and AI analysis application. Records audio directly (using the built-in or Bluetooth microphone), transcribes recordings on-device with Whisper, generates smart summaries and interactive mind maps with Gemma 3, and lets you ask semantic questions across your entire library — all running 100% locally on-device.

## Features

- **Local Voice Recording** — Record high-quality audio directly in the app using the device microphone or a Bluetooth headset (SCO)
- **On-Device Transcription** — Whisper base.en via sherpa-onnx; speech-to-text happens entirely offline and audio never leaves your phone
- **AI Analysis** — Gemma 3 1B generates a title, summary, key topics, and a structured mind map per recording
- **Ask Your Notes** — Semantic Q&A across your whole library: relevant recordings are retrieved by meaning, then Gemma synthesizes a detailed answer citing specific sources
- **Knowledge Graph** — Visualize semantic connections and shared topics across all of your recordings
- **Full-Text Search** — Instantly search across all transcripts and summaries
- **Export** — Share your summaries, mind maps, and Q&A answers as Markdown or copy them to the clipboard

## Requirements

| | |
|---|---|
| **Hardware** | Standard Android device with built-in microphone (Bluetooth mic/headset optional) |
| **Android** | ARM64, API 26+ (Android 8.0) |
| **Storage** | ~770 MB free (Gemma 3 1B: ~555 MB · Whisper base.en: ~160 MB · text embedder: ~26 MB) |

Tested on Samsung Galaxy S24 Ultra.

## Setup

1. Open the `android/` folder in Android Studio (Hedgehog or newer)
2. Build and run on your device, or via the command line:
   ```bash
   # Windows
   cd android && .\gradlew installDebug

   # macOS / Linux
   cd android && ./gradlew installDebug
   ```
3. On first launch the app will prompt you to download the AI models from Settings:
   - **Gemma 3 1B** (~555 MB) — on-device summarization and Q&A
   - **Whisper base.en** (~160 MB) — on-device speech-to-text
   - **Universal Sentence Encoder** (~26 MB) — on-device text embeddings for semantic Ask
4. Tap the record button on the home screen to start recording audio.

## How It Works

All audio recording and AI processing happens locally on your Android device:

```
[ Built-in / BT Mic ] ──Record──► Android app
                                      │
                                 Whisper STT
                                 (on-device)
                                      │
                                 Gemma 3 1B
                                 (on-device)
                                      │
                           Title · Summary · Topics
                                 Mind Map
```

No data is sent to external servers.

Beyond per-recording analysis, **Ask Your Notes** embeds every transcript on-device so you can query the whole library by meaning — the most relevant recordings are retrieved, then Gemma composes an answer and links back to its sources.

## Tech Stack

| Component | Technology |
|---|---|
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM · Room · StateFlow |
| On-device LLM | MediaPipe 0.10.35 + Gemma 3 1B (`.task` format) |
| Transcription | sherpa-onnx 1.13.2 + Whisper base.en int8 ONNX |
| Semantic search | MediaPipe Text Embedder + Universal Sentence Encoder |
| Audio Recording | AudioRecord + Bluetooth SCO support |
| Database | Room 2.6.1 + SQLite WAL |
| Build | AGP 8.7.3 · Kotlin 2.0.21 · JDK 21 |

## Project Structure

```
android/     Kotlin/Compose Android app (primary product)
src/         Python CLI prototype — BLE exploration & desktop processing (Phase 1)
reverse/     FW920 protocol reverse-engineering notes and tools (reference only)
```

## Building

Requires Android SDK 35 and JDK 21.

```bash
cd android
.\gradlew assembleDebug    # debug APK
.\gradlew assembleRelease  # release APK (ADB debug hooks disabled)
```

> The first build downloads the prebuilt `sherpa-onnx` Android library (~56 MB) from GitHub Releases into `app/libs/` automatically (the `downloadSherpaOnnx` Gradle task), so an internet connection is required for the initial build.

## Notes

- The Python CLI in `src/` and the protocol notes in `reverse/` are legacy artifacts from Phase 1 BLE exploration of the FW920 recorder. The Android application has been fully repurposed as a standalone local voice recorder and AI assistant.
- ADB broadcast commands (`ANALYZE`, `SYNC`, etc.) are only active in debug builds and disabled in release.

## License

MIT
