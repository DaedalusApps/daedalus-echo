"""Transcript translation via local Ollama model."""
from src.ai.ollama_client import complete


def translate(transcript: str, target_language: str) -> str:
    system = (
        f"You are an expert translator. Translate the provided transcript to {target_language}. "
        "Preserve speaker labels (Speaker 1:, etc.) and formatting. "
        "Return only the translation, no explanations."
    )
    return complete(system=system, user_content=transcript, cache_system=True)
