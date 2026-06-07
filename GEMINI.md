# GEMINI.md - Daedalus Echo Project Guidance

This file provides foundational mandates, architecture, and workflows for the `daedalus-echo` project. It takes precedence over general defaults.

## Project Overview
`daedalus-echo` is a companion application for the **ELVANZA FW920** (HUXGO OEM) AI voice recorder. The project is split into two phases:
1. **Phase 1 (Current):** A Python CLI prototype for BLE control, transcription (Whisper), and Claude-based analysis.
2. **Phase 2 (Active):** An Android app (Kotlin/Compose) for on-device inference and mobile-first experience.

## Tech Stack

### Python Prototype (Phase 1)
- **Runtime:** Python 3.13.7
- **BLE:** `bleak` (async)
- **Transcription:** `openai-whisper` (local)
- **AI Analysis:** Anthropic SDK (`claude-sonnet-4-6`)
- **Audio:** `pydub`, `ffmpeg`
- **Export:** `fpdf2` (PDF), `python-docx` (DOCX), Google Drive API
- **CLI:** `rich`

### Android App (Phase 2)
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

### 2. BLE Protocol (confirmed via HCI snoop of DOWAY reference app)

**UUIDs:**
- Service: `0000b0b0-0000-1000-8000-00805f9b34fb`
- Write: `0000b0b1-...` — send commands here
- Notify B0B2: control responses (A0 0A 01 packets)
- Notify B0B3/B0B4: audio data chunks during download

**Packet format:** `A0 0A 01 [CMD] [LEN] [PAYLOAD...] [CRC16-ARC-hi] [CRC16-ARC-lo]`

**Key commands:**
| CMD  | Direction | Purpose |
|------|-----------|---------|
| 0x01 | →device   | Get serial number |
| 0x02 | →device   | Get firmware version |
| 0x04 | →device   | Sync time |
| 0x05 | →device   | Get storage/battery status |
| 0x06 | →device   | Start recording |
| 0x07 | →device   | Confirm done (after stop) |
| 0x08 | →device   | Stop recording |
| 0x0A | →device   | List files (device replies with one 0x0A packet per file, null-entry = end) |
| 0x0B | →device   | **Download file** — payload: 14-byte filename (space-padded) + 4-byte LE offset (always 0x00000000) |
| 0x0D | →device   | **Delete file** — payload: 14-byte filename (space-padded) |

**Download protocol (cmd=0x0B):**
1. Send `buildPacket(0x0B, nameBytes + [0,0,0,0])` — 14-byte space-padded filename, zero offset
2. Device responds `Ack(0x0B)` = "ready"
3. Device streams `AudioChunk` packets on B0B3/B0B4
4. Device sends second `Ack(0x0B)` = end-of-file
5. **Do not send any confirm/continue packet** — one request, device sends complete file

**MTU:** Must request MTU 512 before service discovery (`gatt.requestMtu(512)` → `onMtuChanged` → `gatt.discoverServices()`). Without this, device may not stream audio chunks.

**File list `sizeBytes` field is NOT in bytes** — it appears to be raw PCM sample count or some other unit. Do not use it for size comparison. Trust the actual bytes received; a clean `Ack(0x0B)` after data = complete file.

**ADB test automation:**
```powershell
# Trigger sync:
adb shell am broadcast -a com.daedalus.echo.SYNC -n com.daedalus.echo/.AdbReceiver
# Start/stop recording:
adb shell am broadcast -a com.daedalus.echo.START_RECORDING -n com.daedalus.echo/.AdbReceiver
adb shell am broadcast -a com.daedalus.echo.STOP_RECORDING -n com.daedalus.echo/.AdbReceiver
# Trigger analysis for a specific file:
adb shell am broadcast -a com.daedalus.echo.ANALYZE --es filename "20260524213434.mp3" -n com.daedalus.echo/.AdbReceiver
```
`AdbReceiver` (exported manifest receiver) re-broadcasts to same package UID, bypassing `RECEIVER_NOT_EXPORTED` on MainActivity's dynamic receiver.

### 3. File System & Storage
- **BLE-First:** Audio files are downloaded via BLE (cmd=0x0B) into `getExternalFilesDir(null)/Recordings/`. USB OTG path is legacy/fallback only.
- **Android SAF:** On Android, use Storage Access Framework (SAF) for USB OTG access if needed.

## Architecture

### Python Structure
- `src/cli.py`: Main entry point.
- `src/ai/`: Wrappers for Whisper and Claude.
- `src/ble/`: BLE communication logic.
- `src/storage/`: Filesystem access for the recorder.
- `src/export/`: Exporters for various formats.
- `src/categories.py`: Source of truth for 15 recording categories and their prompts.

### Android Structure
- `android/app/src/main/java/com/daedalus/echo/`:
    - `ai/`: Local LLM (Gemma 3) and Transcription (Whisper) services.
    - `ble/`: Bluetooth management and FW920 protocol.
    - `ui/`: Compose screens and components.
    - `viewmodel/`: State management for UI.

## Documentation & Tracking
- **README.md:** Project overview and setup.
- **GEMINI.md:** Foundational mandates and architecture (this file).
- **ROADMAP.md:** Future feature development and backlog.
- **PLAN.md:** Original design document and strategy.
- **android/BUILD.md:** Android build and environment documentation.

## Workflows

### Setup & Development
```bash
# Python dependencies (system packages might require --break-system-packages)
python3 -m pip install -r requirements.txt --break-system-packages

# BLE Discovery
python3 scripts/ble_discover.py

# Testing
python3 -m pytest tests/ -v
```

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
- **Explicit Types:** Use Python type hints and Kotlin's strong typing system rigorously.
- **Interactive CLI:** Use `rich` for all user-facing CLI output to ensure a polished experience.
- **Visuals:** Android UI should follow modern Material 3 guidelines, prioritizing responsiveness and accessible design.
- **Accessibility & Testability:** Prefer rendering UI nodes as Composables (e.g., `Text`) over direct `Canvas.drawText` to ensure they are discoverable by the semantics tree and screen readers.
- **Cleanup:** Only remove imports, variables, or functions that your changes made obsolete. Do not delete pre-existing dead code unless explicitly asked.

