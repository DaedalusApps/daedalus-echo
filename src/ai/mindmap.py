"""Generates mind map JSON structure and Mermaid/Markdown from transcript."""
import json

from src.ai.ollama_client import complete

_MINDMAP_SYSTEM = (
    "You are a mind map generator. Analyze this transcript and create a hierarchical mind map. "
    "Return ONLY raw JSON, no markdown fences, no explanation. "
    'Use this exact structure: {"title": "short title", "nodes": ['
    '{"id": "root", "label": "Main Topic", "parent": null, "children": ["n1", "n2"]}, '
    '{"id": "n1", "label": "Sub topic", "parent": "root", "children": []}]}. '
    "Use short labels (max 6 words). The root node has parent null."
)


def generate_mindmap(transcript: str) -> dict:
    response_text = complete(
        system=_MINDMAP_SYSTEM,
        user_content=f"Create a mind map for:\n\n{transcript}",
        cache_system=True,
    )
    try:
        return json.loads(response_text)
    except json.JSONDecodeError:
        # Return a minimal valid mindmap structure so callers don't crash.
        return {"title": "Mind Map", "nodes": [], "error": "JSON parse failed", "raw": response_text}


def _build_children_map(nodes: list[dict]) -> dict[str, list[dict]]:
    """Build parent→children map using the 'parent' field (reliable vs children ID refs)."""
    children: dict[str, list[dict]] = {}
    for node in nodes:
        parent = node.get("parent")
        if parent is not None:
            children.setdefault(parent, []).append(node)
    return children


def mindmap_to_mermaid(mindmap: dict) -> str:
    title = mindmap.get("title", "Root")
    nodes: list[dict] = mindmap.get("nodes", [])
    root = next((n for n in nodes if n.get("parent") is None), None)
    children_map = _build_children_map(nodes)

    lines = ["mindmap"]

    def _render(node: dict, depth: int) -> None:
        indent = "  " * depth
        label = node["label"]
        if depth == 1:
            lines.append(f"{indent}root(({label}))")
        else:
            lines.append(f"{indent}{label}")
        for child in children_map.get(node["id"], []):
            _render(child, depth + 1)

    if root is not None:
        _render(root, 1)
    else:
        lines.append(f"  root(({title}))")

    return "\n".join(lines)


def mindmap_to_markdown(mindmap: dict) -> str:
    nodes: list[dict] = mindmap.get("nodes", [])
    root = next((n for n in nodes if n.get("parent") is None), None)
    children_map = _build_children_map(nodes)

    lines: list[str] = []

    def _render(node: dict, depth: int) -> None:
        indent = "  " * (depth - 1)
        lines.append(f"{indent}- {node['label']}")
        for child in children_map.get(node["id"], []):
            _render(child, depth + 1)

    if root is not None:
        _render(root, 1)

    return "\n".join(lines)
