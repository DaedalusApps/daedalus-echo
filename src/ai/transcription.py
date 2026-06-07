"""MP3 → transcript via openai-whisper (local inference).

Claude's API does NOT support audio input — only images and documents
(application/pdf, text/plain). Audio transcription is handled locally by
openai-whisper, and the resulting text is then sent to Claude for
summarization.
"""
from pathlib import Path


def transcribe(mp3_path: Path, *, language: str = "auto") -> str:
    """Transcribe an MP3 file to text using whisper.

    Args:
        mp3_path: Path to the MP3 recording.
        language: BCP-47 language code (e.g. "en", "es") or "auto" for
            automatic detection.

    Returns:
        Plain-text transcript with speaker-turn line breaks where whisper
        segment boundaries fall.
    """
    try:
        import whisper
    except ImportError as exc:
        raise ImportError(
            "openai-whisper is required for transcription. "
            "Install it: python3 -m pip install openai-whisper --break-system-packages"
        ) from exc

    model = whisper.load_model("base")
    result = model.transcribe(
        str(mp3_path),
        language=None if language == "auto" else language,
        word_timestamps=False,
    )

    segments = result.get("segments", [])
    if segments:
        return "\n".join(seg["text"].strip() for seg in segments if seg["text"].strip())
    return result.get("text", "").strip()
