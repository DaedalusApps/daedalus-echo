"""Anthropic SDK wrapper with prompt caching enabled.

Note: Claude's API supports text, images, and documents (PDF/plain-text) as
input. It does NOT support raw audio. Audio transcription is handled by
openai-whisper (see src/ai/transcription.py); Claude is used for downstream
text tasks (summarization, mind maps, translation, etc.).
"""
import os

import anthropic

MODEL = "claude-sonnet-4-6"
MAX_TOKENS = 4096


def _client() -> anthropic.Anthropic:
    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise EnvironmentError("ANTHROPIC_API_KEY not set. Copy .env.example to .env and add your key.")
    return anthropic.Anthropic(api_key=api_key)


def complete(
    system: str,
    user_content: list | str,
    *,
    cache_system: bool = True,
    json_mode: bool = False,
) -> str:
    """Send a completion request with optional prompt caching on the system prompt."""
    client = _client()

    system_block: list[dict] = [
        {
            "type": "text",
            "text": system,
            **({"cache_control": {"type": "ephemeral"}} if cache_system else {}),
        }
    ]

    if isinstance(user_content, str):
        user_content = [{"type": "text", "text": user_content}]

    kwargs: dict = dict(
        model=MODEL,
        max_tokens=MAX_TOKENS,
        system=system_block,
        messages=[{"role": "user", "content": user_content}],
    )

    response = client.messages.create(**kwargs)
    return response.content[0].text
