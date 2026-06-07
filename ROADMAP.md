# Roadmap & Feature Backlog

This document tracks the future development of Daedalus Notetaker. Features are categorized by phase and priority.

## 🚧 Missing Features (by design — deferred)
- [ ] **Audio Playback:** ExoPlayer is included in dependencies, but the recordings screen does not yet have a functional player.
- [ ] **Android Export Formats:** Only Markdown is currently wired up. PDF/DOCX exporters (present in Python prototype) need to be ported/implemented for Android.

## 🧹 Housekeeping
- [x] **Git Hygiene:** `.gradle/` build cache files were being committed. (Resolved: Updated `.gitignore` and pruned git history).
- [x] **Android Docs:** Created `android/BUILD.md` to document the Android build process and environment.
- [x] **Security Audit:** Performed manual audit and cleaned up sensitive temporary files.
- [x] **App Icon:** Updated to modern adaptive icon with branded microphone vector.

## 🚀 Active Development (Phase 2: Android)

### High Priority
- [x] **On-Device Transcription:** Local speech-to-text via Whisper (sherpa-onnx) is now functional.
- [ ] **Background Transcription:** Continue processing audio even when the app is in the background or the screen is off.
- [ ] **Search Engine Optimization:** Full-text search (FTS4) across all transcripts and summaries.
- [ ] **Real-time BLE Status:** Persistent notification showing battery level and recording status of the physical device.

### Medium Priority
- [x] **Interactive Mind Maps:** Knowledge Graph with zoom/pan and node interactivity is functional.
- [ ] **Multi-Speaker Diarization:** Identify and label different speakers in the transcript using local AI.
- [ ] **Batch Processing:** Ability to select multiple recordings for transcription and analysis in one go.
- [x] **Regression Testing:** Automated test suite for Home, Global Mind Map, and AI parsing logic.

## 🗺️ Future Milestones (Phase 3+)

### Advanced AI Capabilities
- [ ] **Real-time Streaming:** Stream audio from the device via BLE/USB for live transcription (if hardware supports it).
- [ ] **Custom Category Creator:** Allow users to define their own analysis prompts and categories.
- [ ] **Audio-to-Video Sync:** Generate a video file with the audio and synchronized subtitles (SRT).

### Ecosystem & Integration
- [ ] **Desktop Companion (Electron/Tauri):** A cross-platform desktop app to mirror the Android experience.
- [ ] **Obsidian/Notion Integration:** Direct export to popular note-taking apps via their APIs.
- [ ] **Web Dashboard:** Secure, encrypted web interface for viewing synced recordings.

## 🛠️ Infrastructure & Maintenance
- [ ] **Unit Test Expansion:** Complete coverage for BLE protocol edge cases.
- [ ] **CI/CD Pipeline:** Automated APK builds and Python linting on GitHub Actions.
- [ ] **Model Optimization:** Support for smaller, faster quantization levels (INT4/INT8) for budget hardware.

## 📝 Backlog / Ideas
- [ ] Apple Watch / WearOS remote control app.
- [ ] Transcription support for 50+ languages via Whisper multilingual models.
- [ ] Automatic "Highlight" clip generation (finding the most important 30 seconds).
- [ ] QR Code sharing for summaries.

---
*Note: This roadmap is subject to change based on protocol discovery and user feedback.*
