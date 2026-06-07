# GEMINI.md - Daedalus Echo Project Guidance

This file provides foundational mandates, architecture, and workflows for the `daedalus-echo` project. It takes precedence over general defaults.

## Project Overview
`daedalus-echo` contains a **standalone Android app** for local on-device voice recording, Whisper transcription, and Gemma 3 AI summaries.

## Tech Stack

### Android App
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Local AI:** MediaPipe LLM Inference (Gemma 3 1B), sherpa-onnx (Whisper)
- **Database:** Room + FTS4
- **Architecture:** MVVM + Clean Architecture principles
- **Versioning:** Automated via git commit count (`versionCode`) and `android/version.properties` (`versionName`).

## Core Mandates & Conventions

### 1. AI & Transcription
- **On-Device Only:** Transcription (Whisper) and Analysis (Gemma 3) must run locally on the phone. Do not send audio or transcripts to external APIs in the Android app.
- **Model Storage:** Models are downloaded to `getExternalFilesDir(null)/models/` on first launch.

### 2. ADB test automation
```powershell
# Trigger analysis for a specific file:
adb shell am broadcast -a com.daedalus.echo.ANALYZE --es filename "20260524213434.mp3" -n com.daedalus.echo/.AdbReceiver
```
`AdbReceiver` (non-exported manifest receiver) re-broadcasts to same package UID, bypassing `RECEIVER_NOT_EXPORTED` on MainActivity's dynamic receiver.

### 3. File System & Storage
- **On-Device Recording first:** Audio files are recorded directly via device microphone and saved to `getExternalFilesDir(null)/Recordings/`.
- **Import Support:** Audio files can be imported from local storage or USB OTG via Storage Access Framework (SAF) using `syncFiles(uris)`.

## Architecture

### Android Structure
- `android/app/src/main/java/com/daedalus/echo/`:
    - `ai/`: Local LLM (Gemma 3) and Transcription (Whisper) services.
    - `recording/`: AudioRecorder for built-in and Bluetooth microphone recording.
    - `data/`: Room Database configurations and RecordingRepository.
    - `ui/`: Compose screens and components.
    - `viewmodel/`: State management for UI.

## Documentation & Tracking
- **README.md:** Project overview and setup.
- **GEMINI.md:** Foundational mandates and architecture (this file).
- **ROADMAP.md:** Future feature development and backlog.
- **android/BUILD.md:** Android build and environment documentation.

## Workflows

### Android Development
- Open the `android/` directory in Android Studio.
- Ensure `Gemma 3 1B` and `Whisper base.en` models are downloaded (see `ModelDownloader.kt` and `WhisperDownloader.kt`).

### Testing (Android)
```bash
cd android
# Run Unit Tests
.\gradlew :app:testDebugUnitTest

# Run Instrumented (UI) Tests
.\gradlew :app:connectedDebugAndroidTest
```
Maintain the regression test suite:
- `RecordingsScreenTest.kt`: Recording list, selection mode, swipe-to-delete.
- `AskHomeScreenTest.kt`: Ask landing screen and library Q&A flow.
- `GlobalMindMapScreenTest.kt`: Knowledge Graph rendering.
- `SmartAnalysisParserTest.kt`: AI response normalization.
- `RecordingDaoTest.kt`: Database integrity.

## Engineering Principles

- **Think Before Coding:** State assumptions clearly. If a requirement is ambiguous, ask for clarification before implementing. Surface trade-offs and push back on over-engineering.
- **Minimalism & Simplicity:** Write the minimum code necessary. Avoid speculative features or premature abstractions. If a senior engineer would call it overcomplicated, simplify it.
- **Goal-Driven Execution:** Transform vague tasks into verifiable goals with a clear plan.
    - **"Fix the bug"** → Reproduce with a test, then make it pass.
    - **"Add feature X"** → Define success criteria, implement, and verify with tests.

## Design Standards

- **Surgical Updates:** Touch only what is necessary. Every changed line must trace directly to the request. Respect existing code style and formatting.
- **Explicit Types:** Use Kotlin's strong typing system rigorously.
- **Visuals:** Android UI should follow modern Material 3 guidelines, prioritizing responsiveness and accessible design.
- **Accessibility & Testability:** Prefer rendering UI nodes as Composables (e.g., `Text`) over direct `Canvas.drawText` to ensure they are discoverable by the semantics tree and screen readers.
- **Cleanup:** Only remove imports, variables, or functions that your changes made obsolete. Do not delete pre-existing dead code unless explicitly asked.
