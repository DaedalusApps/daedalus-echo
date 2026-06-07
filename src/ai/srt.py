"""MP3 → timestamped SRT via whisper."""
from pathlib import Path


def _format_timestamp(seconds: float) -> str:
    ms = int(round(seconds * 1000))
    h = ms // 3_600_000
    ms %= 3_600_000
    m = ms // 60_000
    ms %= 60_000
    s = ms // 1_000
    ms %= 1_000
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def generate_srt(mp3_path: Path, *, language: str = "auto", model_size: str = "base") -> str:
    try:
        import whisper
    except ImportError as exc:
        raise ImportError(
            "openai-whisper is required for SRT export. "
            "Install it: python3 -m pip install openai-whisper --break-system-packages"
        ) from exc
    model = whisper.load_model(model_size)
    result = model.transcribe(
        str(mp3_path),
        language=None if language == "auto" else language,
        word_timestamps=False,
    )

    lines: list[str] = []
    for i, seg in enumerate(result["segments"], start=1):
        start = _format_timestamp(seg["start"])
        end = _format_timestamp(seg["end"])
        text = seg["text"].strip()
        lines.append(f"{i}\n{start} --> {end}\n{text}\n")

    return "\n".join(lines)
