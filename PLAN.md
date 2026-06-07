# Plan: Notetaker — ELVANZA/FW920T AI Voice Recorder Companion App

## Context

The user owns the ELVANZA AI Voice Recorder (Amazon B0GSQWP9L5), a rebadged **HUXGO FW920** series device (OEM: Shenzhen Chuangyi Technology Co., Ltd). The manufacturer's app is **DOWAY** (`com.doway.record`). 

**Strategy: Python prototype first, Android port second.**

Building a working Python CLI/TUI on the laptop first validates the BLE protocol and AI pipeline before investing in Android development. The Claude API serves as a drop-in for Gemma 4 E4B during prototyping; the Android app swaps it for on-device inference.

---

## Device Facts

| Property | Value |
|---|---|
| BLE advertised name | `FW920` |
| Recording format | MP3, 32 kbps |
| Storage | 64 GB internal, exFAT |
| USB behavior | Mounts as exFAT mass storage (`/RECORD/` folder) |
| Bluetooth | 5.3 BLE (Dual mode) |
| Battery | 400 mAh, ~35 hours recording, 166 days standby |
| Charging | Magnetic USB; 2.5–3 hours full |
| Microphone | Silicon MEMS + bone conduction combo |
| Recording modes | Standard (mic) + Call recording (double-tap to switch) |
| VOR threshold | 70 dB (auto-pause if silent >20 sec) |

---

## Hardware Button & LED Reference (for BLE mapping)

| Action | Procedure | Indicator |
|---|---|---|
| Power ON | Hold power switch 3 sec | Red power ON |
| Power OFF | Hold power switch 3 sec | Red power OFF |
| Start recording | Long press recording button | Red LED flashes |
| Pause recording | Long press recording button again | Red OFF → Blue |
| BT searching | After power on | Blue flashes |
| BT connected | — | Blue solid |
| Reset BT | Hold power switch 8 sec | Blue starts flashing |
| Charging | — | Red flashes |
| Fully charged | — | Red solid |
| Low battery (<10%) | — | Red flashes → auto-off |
| Switch mode | Double-tap recording button | Standard ↔ Call |

---

## 15 Recording Category Modules

Each category selects a domain-specific prompt for summarization.

| # | Category | AI Output Focus |
|---|---|---|
| 1 | General | Generic key points + summary |
| 2 | Meetings | Action items, decisions, next steps |
| 3 | Presentations | Structure outline, key arguments |
| 4 | Call | Topics discussed, commitments made |
| 5 | Interview | Candidate Q&A, evaluation notes |
| 6 | Medical | SOAP note (Subjective/Objective/Assessment/Plan) |
| 7 | Sales | Objections, follow-ups, deal status |
| 8 | Education | Concepts, key definitions, review questions |
| 9 | Consulting | Problem, recommendations, deliverables |
| 10 | Construction | Site issues, tasks, safety notes |
| 11 | Law | Case notes, parties, legal actions |
| 12 | IT | Issue diagnosis, solution, tickets |
| 13 | Real Estate | Property details, client needs, offer terms |
| 14 | Finance | Figures, risks, decisions |
| 15 | Functionality | 8 sub-analyses (see below) |

### Category 15 "Functionality" — 8 Sub-analyses
1. Intention Analysis
2. Key Quantitative Data
3. Speaker Perspective
4. Meeting Points
5. Meeting Minutes
6. Gratitude Hunter
7. To-Do List
8. Meeting Effect Evaluation

---

## Full Feature Set

### Recording & Control
- Start / stop / pause via BLE or device button
- Switch Standard ↔ Call mode
- VOR (Voice Activated Recording): 70 dB / 20 sec timeout
- Recording markers / bookmarks
- App-initiated recording

### File Management
- List + import MP3s from USB drive (`/RECORD/`)
- Delete with confirmation
- Sort / filter by date, length, category, tag
- Full-text search across transcripts

### AI Processing

| Task | Prototype (laptop) | Android |
|---|---|---|
| Transcription | Claude API (`claude-sonnet-4-6`) | Gemma 4 E4B via MediaPipe |
| Summarization | Claude API | Gemma 4 E4B |
| Mind map | Claude API (JSON output) | Gemma 4 E4B |
| Speaker diarization | Claude API | Gemma 4 E4B |
| Translation | Claude API | Gemma 4 E4B |
| SRT (timestamped) | whisper.cpp (CLI) | whisper.cpp JNI |

### Export Formats

| Content | Formats |
|---|---|
| Transcription | TXT, SRT, DOCX, PDF |
| Summary | TXT, DOCX, PDF, Markdown |
| Mind map | JPEG (prototype: ASCII / Mermaid), Markdown |
| Audio | MP3 (original), WAV |

### Export Destinations
- Local files (Downloads / project folder)
- Android Share Sheet (Android only)
- Google Drive (OAuth)
- Markdown to any folder

---

## PHASE 1: Python Prototype (laptop)

### Goals
- Prove BLE protocol works: discover UUIDs, send start/stop, read status
- Prove AI pipeline end-to-end: MP3 → transcript → summary → mind map → Markdown export
- Give user a working tool immediately

### Stack
| Layer | Tool |
|---|---|
| Language | Python 3.13 |
| BLE | `bleak` (async BLE) |
| Audio processing | `pydub` + `ffmpeg` (MP3 → WAV for API) |
| AI | Anthropic Python SDK (`anthropic`) |
| SRT | `whisper` CLI or `openai-whisper` Python package |
| USB file access | Direct filesystem (device mounts as `/media/.../RECORD/`) |
| Export | `python-docx`, `fpdf2`, Markdown string templates |
| Google Drive | `google-api-python-client` + `google-auth-oauthlib` |
| CLI / TUI | `rich` (pretty terminal output) |
| Config | `.env` file + `python-dotenv` |

### Prototype Project Structure
```
notetaker/
├── scripts/
│   └── ble_discover.py           # Step 1: GATT scanner, run first
├── src/
│   ├── ble/
│   │   ├── scanner.py            # scan for "FW920"
│   │   ├── connection.py         # bleak GATT connection + reconnect
│   │   └── protocol.py           # UUIDs + command bytes (filled after discovery)
│   ├── storage/
│   │   └── drive_accessor.py     # find + list /RECORD/ on mounted exFAT
│   ├── ai/
│   │   ├── claude_client.py      # Anthropic SDK wrapper with prompt caching
│   │   ├── transcription.py      # MP3→WAV→Claude→transcript
│   │   ├── srt.py                # whisper CLI → .srt
│   │   ├── summarization.py      # category-aware Claude prompts
│   │   ├── functionality.py      # Category 15: 8 sequential prompts
│   │   ├── mindmap.py            # Claude→JSON→Mermaid/Markdown
│   │   └── translation.py
│   ├── export/
│   │   ├── docx_exporter.py      # python-docx
│   │   ├── pdf_exporter.py       # fpdf2
│   │   ├── markdown_exporter.py
│   │   └── gdrive_sync.py        # google-api-python-client
│   ├── categories.py             # 15 category definitions + prompt templates
│   └── cli.py                    # rich CLI: main entry point
├── tests/
│   └── test_ai_pipeline.py
├── requirements.txt
├── .env.example                  # ANTHROPIC_API_KEY, GDRIVE_CREDENTIALS_FILE
├── PLAN.md                       # copy of this plan
└── CLAUDE.md
```

### Claude API Integration Notes
- Use `claude-sonnet-4-6` as primary model
- Enable **prompt caching** on the system prompt + category prompt templates (static content)
- Pass audio as base64-encoded WAV in the user message for transcription
- Use structured JSON output for mind map and category analysis
- Category 15 "Functionality": 8 sequential API calls, share conversation context to avoid re-sending audio

### Prototype CLI Commands
```bash
python src/cli.py scan              # BLE scan for FW920
python src/cli.py connect           # BLE connect + show device status
python src/cli.py record start      # send start recording command
python src/cli.py record stop       # send stop recording command
python src/cli.py list              # list recordings from USB drive
python src/cli.py process <file>    # run full AI pipeline on a recording
python src/cli.py export <file> --format md,pdf --dest gdrive
```

---

## PHASE 2: Android App (after prototype proves the design)

Port the validated Python prototype to a Kotlin Android app with on-device AI.

### Key differences from prototype
- Claude API → Gemma 4 E4B via MediaPipe LLM Inference API
- Filesystem access → Android SAF (USB OTG)
- `bleak` → Android BluetoothGatt
- `ffmpeg` → Android MediaCodec
- CLI → Jetpack Compose UI
- whisper CLI → whisper.cpp JNI

## Status & Milestones

### Completed: AI-First UI Overhaul (May 2026)
- Inverted app priority: AI Search & Global Knowledge Graph are now the landing screen.
- Implemented universal device status monitoring across all views.
- Overhauled Knowledge Graph with auto-zoom, precise hit-testing, and M3 aesthetics.
- Added library-wide Q&A export (Markdown/Clipboard).

### Android Stack
| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose |
| Architecture | MVVM + Repository + Use Cases |
| AI inference | MediaPipe LLM Inference + Gemma 4 E4B LiteRT INT4 (~2.5 GB) |
| SRT | whisper.cpp JNI (ARM64 .so) |
| Database | Room + FTS4 |
| Playback | ExoPlayer |
| Export | Apache POI (DOCX), iText 7 (PDF) |
| Google Drive | Drive API v3 + play-services-auth |
| DI | Hilt |
| Background | WorkManager |

### Android Project Structure (mirrors prototype)
```
app/src/main/java/com/notetaker/
├── bluetooth/            # BleScanner, BleConnectionManager, Protocol, DeviceStateManager
├── storage/              # UsbDriveAccessor (SAF)
├── ai/                   # GemmaEngine, WhisperEngine, all *Service.kt
├── export/               # DocxExporter, PdfExporter, MarkdownExporter, JpegExporter, WavExporter, GoogleDriveSync
├── data/                 # Room DB, FTS4, RecordingRepository
└── ui/screens/           # DeviceScreen, RecordingsScreen, NoteDetailScreen, MindMapScreen, SettingsScreen
```

---

## Installation (Prototype)

```bash
cd notetaker
pip install anthropic bleak pydub python-docx fpdf2 rich python-dotenv \
            google-api-python-client google-auth-oauthlib openai-whisper
sudo apt install ffmpeg      # for pydub MP3→WAV conversion
cp .env.example .env
# Edit .env: add ANTHROPIC_API_KEY
```

---

## Verification

### Prototype
1. `python src/cli.py scan` finds `FW920` device
2. `python src/cli.py connect` shows battery % and storage
3. `python src/cli.py record start` triggers red LED flash on device
4. `python src/cli.py list` shows MP3 files from USB drive
5. `python src/cli.py process <file>` produces transcript + category summary
6. Category 15 "Functionality" generates all 8 sub-analyses
7. Medical category produces SOAP note format
8. SRT file has correct timestamps
9. Exports to Markdown, PDF, DOCX open correctly

### Android (Phase 2)
10. RecordingsScreen lists MP3s from OTG drive
11. 5-minute recording transcribed in < 2 minutes on S24 Ultra
12. Mind map renders with pan/zoom
13. `adb install -r app-debug.apk` installs cleanly
