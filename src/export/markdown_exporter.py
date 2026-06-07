"""Exports transcript, summary, and mind map to Markdown files."""

from pathlib import Path


def _render_value(value) -> str:
    if isinstance(value, list):
        items = []
        for item in value:
            if isinstance(item, dict):
                sub = "\n".join(f"  - {v}" for v in item.values())
                items.append(sub)
            else:
                items.append(f"- {item}")
        return "\n".join(items)
    return str(value)


def export_transcript(transcript: str, output_path: Path) -> Path:
    output_path.write_text(f"# Transcript\n\n{transcript}\n", encoding="utf-8")
    return output_path


def export_summary(summary: dict, category_name: str, output_path: Path) -> Path:
    lines = [f"# Summary — {category_name}\n\n"]
    for key, value in summary.items():
        heading = key.replace("_", " ").title()
        lines.append(f"## {heading}\n\n{_render_value(value)}\n\n")
    output_path.write_text("".join(lines), encoding="utf-8")
    return output_path


def export_mindmap(mindmap_markdown: str, output_path: Path) -> Path:
    output_path.write_text(f"# Mind Map\n\n{mindmap_markdown}\n", encoding="utf-8")
    return output_path


def export_full_note(
    title: str,
    transcript: str,
    summary: dict,
    category_name: str,
    mindmap_markdown: str,
    output_path: Path,
) -> Path:
    summary_lines = []
    for key, value in summary.items():
        heading = key.replace("_", " ").title()
        summary_lines.append(f"### {heading}\n\n{_render_value(value)}\n\n")

    content = (
        f"# {title}\n\n"
        f"## Summary ({category_name})\n\n"
        + "".join(summary_lines)
        + f"## Transcript\n\n{transcript}\n\n"
        f"## Mind Map\n\n{mindmap_markdown}\n"
    )
    output_path.write_text(content, encoding="utf-8")
    return output_path
