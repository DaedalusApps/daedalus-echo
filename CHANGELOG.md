# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [1.2.3] - 2026-08-05

### Fixed
- When a note's AI analysis could not be parsed, the fallback title could still come out as "Untitled Recording" if the AI's response happened to start with a bullet or heading line. It now uses the first line that actually has text. (#64)

## [1.2.2] - 2026-08-05

### Fixed
- A recording or conversation whose AI analysis couldn't be parsed is no longer saved as a blank, untitled note; it now gets a title and preview derived from its own text (#54).
- Settings left at their default values are now included in backups, so restoring a backup carries over every setting's effective value (#55).
- Conversation voice settings (spoken replies, speech speed, voice, instant send, auto-listen) are now included in backups and restored correctly, instead of being lost (#59).

## [1.2.1] - 2026-08-05

### Fixed
- Conversation mode now retrieves relevant saved notes into the AI's prompt with a 0.4 relevance floor, so questions about your notes are answered from actual note content and unrelated questions get no note bleed (#56, #57).

## [1.2.0] - 2026-08-04

### Added
- Conversation mode: on-device AI chat with persistent sessions that can be resumed, and ended into an analyzed library note with mind map, topics, and todos.
- Push-to-talk voice input for conversations.
- Spoken replies via on-device text-to-speech, with a voice picker and speed presets.
- Per-message replay of spoken responses.
- Voice-only mode with a single morphing center button.
- Opt-in hands-free auto-listen loop.
- Instant send after voice transcription.
- New-conversation save dialog, with Conversation chips in the library.
- Max recording duration with auto-stop.
- Adjustable AI text budget controlling chunking and analysis.
- Offline guardrail included in all AI prompts.
- Keep-screen-awake during voice sessions.

### Fixed
- Paused time is no longer counted in saved recording duration.
- In-flight speech now stops when sending a message or ending a session.

## [1.1.0] - 2026-07

### Added
- Automatic extraction of todos from notes.
- Automatic versioned backups.
- Semantic Ask over notes.
- Mind maps for notes.
