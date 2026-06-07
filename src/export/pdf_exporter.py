"""Exports notes to PDF using fpdf2."""

from pathlib import Path

from fpdf import FPDF


def _render_value_lines(value) -> list[str]:
    if isinstance(value, list):
        lines = []
        for item in value:
            if isinstance(item, dict):
                lines.append("• " + ", ".join(str(v) for v in item.values()))
            else:
                lines.append(f"• {item}")
        return lines
    return [str(value)]


def export_full_note(
    title: str,
    transcript: str,
    summary: dict,
    category_name: str,
    output_path: Path,
) -> Path:
    pdf = FPDF()
    pdf.add_page()

    pdf.set_font("Helvetica", style="B", size=18)
    pdf.multi_cell(0, 10, title)
    pdf.ln(2)

    pdf.set_font("Helvetica", style="I", size=12)
    pdf.multi_cell(0, 8, category_name)
    pdf.ln(2)

    pdf.set_draw_color(0, 0, 0)
    pdf.set_line_width(0.5)
    pdf.line(pdf.get_x(), pdf.get_y(), pdf.get_x() + 190, pdf.get_y())
    pdf.ln(4)

    pdf.set_font("Helvetica", style="B", size=14)
    pdf.multi_cell(0, 8, "Summary")
    pdf.ln(2)

    for key, value in summary.items():
        heading = key.replace("_", " ").title()
        pdf.set_font("Helvetica", style="B", size=12)
        pdf.multi_cell(0, 7, heading)
        pdf.set_font("Helvetica", size=12)
        for line in _render_value_lines(value):
            pdf.multi_cell(0, 6, line)
        pdf.ln(2)

    pdf.set_font("Helvetica", style="B", size=14)
    pdf.multi_cell(0, 8, "Transcript")
    pdf.ln(2)
    pdf.set_font("Helvetica", size=12)
    pdf.multi_cell(0, 6, transcript)

    pdf.output(str(output_path))
    return output_path


def export_transcript(transcript: str, output_path: Path) -> Path:
    pdf = FPDF()
    pdf.add_page()

    pdf.set_font("Helvetica", style="B", size=16)
    pdf.multi_cell(0, 10, "Transcript")
    pdf.ln(4)

    pdf.set_font("Helvetica", size=12)
    pdf.multi_cell(0, 6, transcript)

    pdf.output(str(output_path))
    return output_path
