"""Category 15 'Functionality' — 8 sequential meeting analyses."""
import json

from src.ai.ollama_client import complete
from src.categories import FUNCTIONALITY_PROMPTS


def analyze_functionality(transcript: str) -> dict[str, dict]:
    results: dict[str, dict] = {}
    for key, prompt in FUNCTIONALITY_PROMPTS.items():
        response_text = complete(
            system=prompt,
            user_content=f"Transcript:\n\n{transcript}",
            cache_system=True,
        )
        try:
            results[key] = json.loads(response_text)
        except json.JSONDecodeError as e:
            results[key] = {"error": str(e), "raw": response_text}
    return results
