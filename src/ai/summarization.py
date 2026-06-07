"""Category-aware summarization via local Ollama model."""
import json

from src.ai.ollama_client import complete
from src.categories import get_category


def summarize(transcript: str, category_id: int) -> dict:
    category = get_category(category_id)
    response_text = complete(
        system=category.system_prompt,
        user_content=f"Transcript:\n\n{transcript}",
        cache_system=True,
    )
    try:
        return json.loads(response_text)
    except json.JSONDecodeError:
        return {"raw": response_text, "error": "JSON parse failed"}
