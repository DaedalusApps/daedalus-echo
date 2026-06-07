"""Notetaker CLI — ELVANZA FW920 voice recorder companion."""
import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path

# Ensure the project root (parent of this file's directory) is on sys.path so
# that "from src.xxx import ..." works regardless of how the script is invoked
# (e.g. "python3 src/cli.py" adds src/ to sys.path[0], not the project root).
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
if str(_PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(_PROJECT_ROOT))

from dotenv import load_dotenv
from rich.console import Console
from rich.panel import Panel
from rich.progress import Progress, SpinnerColumn, TextColumn
from rich.table import Table

load_dotenv()

console = Console()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _export_dir() -> Path:
    d = Path(os.environ.get("EXPORT_DIR", Path.home() / "voicenotes-exports")).expanduser()
    d.mkdir(parents=True, exist_ok=True)
    return d


def _stem(mp3: Path) -> str:
    return mp3.stem


def _require_arg(args: list[str], idx: int, name: str) -> str:
    if len(args) <= idx:
        console.print(f"[red]Error:[/red] missing argument <{name}>")
        sys.exit(1)
    return args[idx]


# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

def cmd_scan(args: list[str]) -> None:
    """Scan for FW920 recorder over BLE."""
    from src.ble.scanner import scan

    console.print("[cyan]Scanning for FW920 recorder...[/cyan]")
    device = asyncio.run(scan(timeout=float(args[0]) if args else 10.0))
    if device is None:
        console.print("[red]FW920 not found.[/red] Make sure the recorder is powered on.")
    else:
        console.print(Panel(
            f"[green]Found:[/green] {device.name}\n"
            f"Address: [bold]{device.address}[/bold]",
            title="FW920 Device",
        ))


def cmd_connect(args: list[str]) -> None:
    """Connect to FW920 and show device status."""
    from src.ble.scanner import scan
    from src.ble.connection import BleConnection

    async def _run() -> None:
        device = await scan()
        if device is None:
            console.print("[red]FW920 not found.[/red]")
            return
        async with BleConnection(device) as conn:
            status = await conn.get_status()
            table = Table(title="FW920 Status")
            table.add_column("Property")
            table.add_column("Value")
            for k, v in status.items():
                table.add_row(k.replace("_", " ").title(), str(v))
            console.print(table)

    asyncio.run(_run())


def cmd_record(args: list[str]) -> None:
    """Control recording: start | stop | pause."""
    action = _require_arg(args, 0, "action: start|stop|pause")

    from src.ble.scanner import scan
    from src.ble.connection import BleConnection

    async def _run() -> None:
        device = await scan()
        if device is None:
            console.print("[red]FW920 not found.[/red]")
            return
        async with BleConnection(device) as conn:
            if action == "start":
                await conn.start_recording()
                console.print("[green]Recording started.[/green] Red LED should flash on device.")
            elif action == "stop":
                await conn.stop_recording()
                console.print("[green]Recording stopped.[/green]")
            elif action == "pause":
                await conn.pause_recording()
                console.print("[yellow]Recording paused.[/yellow]")
            else:
                console.print(f"[red]Unknown action:[/red] {action}. Use start, stop, or pause.")

    asyncio.run(_run())


def cmd_list(args: list[str]) -> None:
    """List recordings from the USB drive."""
    from src.storage.drive_accessor import list_recordings, find_mounted_drive, recording_info

    drive = find_mounted_drive()
    if drive is None:
        console.print("[red]FW920 drive not found.[/red] Plug in the recorder via USB.")
        return

    try:
        recordings = list_recordings(drive)
    except FileNotFoundError as e:
        console.print(f"[red]{e}[/red]")
        return

    if not recordings:
        console.print("[yellow]No recordings found on device.[/yellow]")
        return

    table = Table(title=f"Recordings on {drive.name} ({len(recordings)} files)")
    table.add_column("#", style="dim")
    table.add_column("Filename")
    table.add_column("Size (MB)", justify="right")
    table.add_column("Modified")
    for i, path in enumerate(recordings, 1):
        info = recording_info(path)
        mtime = datetime.fromtimestamp(info["modified"]).strftime("%Y-%m-%d %H:%M")
        table.add_row(str(i), path.name, str(info["size_mb"]), mtime)
    console.print(table)


def cmd_process(args: list[str]) -> None:
    """Run full AI pipeline on a recording file."""
    file_arg = _require_arg(args, 0, "mp3_file")
    mp3_path = Path(file_arg)

    # Parse flags
    category_id = 1
    language = "auto"
    for i, a in enumerate(args[1:], 1):
        if a == "--category" and i + 1 < len(args):
            category_id = int(args[i + 1])
        elif a == "--lang" and i + 1 < len(args):
            language = args[i + 1]

    # If file is on USB drive, import it first
    if not mp3_path.exists():
        from src.storage.drive_accessor import find_mounted_drive, list_recordings, import_recording
        drive = find_mounted_drive()
        if drive:
            recordings = list_recordings(drive)
            matches = [r for r in recordings if r.name == mp3_path.name or r.stem == mp3_path.stem]
            if matches:
                mp3_path = import_recording(matches[0], _export_dir() / "recordings")
                console.print(f"[cyan]Imported:[/cyan] {mp3_path.name}")

    if not mp3_path.exists():
        console.print(f"[red]File not found:[/red] {mp3_path}")
        sys.exit(1)

    from src.categories import get_category, list_categories
    from src.ai.transcription import transcribe
    from src.ai.summarization import summarize
    from src.ai.mindmap import generate_mindmap, mindmap_to_markdown
    from src.export.markdown_exporter import export_full_note

    try:
        category = get_category(category_id)
    except ValueError as e:
        console.print(f"[red]{e}[/red]")
        _print_categories()
        sys.exit(1)

    out_dir = _export_dir() / _stem(mp3_path)
    out_dir.mkdir(parents=True, exist_ok=True)

    with Progress(SpinnerColumn(), TextColumn("{task.description}"), console=console) as progress:
        t1 = progress.add_task("Transcribing…", total=None)
        transcript = transcribe(mp3_path, language=language)
        progress.update(t1, description="[green]Transcription done[/green]")

        t2 = progress.add_task(f"Summarizing ({category.name})…", total=None)
        if category_id == 15:
            from src.ai.functionality import analyze_functionality
            summary = analyze_functionality(transcript)
        else:
            summary = summarize(transcript, category_id)
        progress.update(t2, description="[green]Summary done[/green]")

        t3 = progress.add_task("Generating mind map…", total=None)
        mindmap = generate_mindmap(transcript)
        mm_md = mindmap_to_markdown(mindmap)
        progress.update(t3, description="[green]Mind map done[/green]")

    md_path = export_full_note(
        title=_stem(mp3_path),
        transcript=transcript,
        summary=summary,
        category_name=category.name,
        mindmap_markdown=mm_md,
        output_path=out_dir / f"{_stem(mp3_path)}_note.md",
    )
    console.print(f"\n[green]Note saved:[/green] {md_path}")
    console.print(f"[dim]Run:[/dim] python src/cli.py export {mp3_path} --format pdf,docx,srt")


def cmd_export(args: list[str]) -> None:
    """Export a processed recording in specified formats."""
    file_arg = _require_arg(args, 0, "mp3_file")
    mp3_path = Path(file_arg)

    formats: list[str] = []
    dest = "local"
    category_id = 1
    for i, a in enumerate(args[1:], 1):
        if a == "--format" and i + 1 < len(args):
            formats = args[i + 1].split(",")
        elif a == "--dest" and i + 1 < len(args):
            dest = args[i + 1]
        elif a == "--category" and i + 1 < len(args):
            category_id = int(args[i + 1])

    if not formats:
        formats = ["md"]

    out_dir = _export_dir() / _stem(mp3_path)
    out_dir.mkdir(parents=True, exist_ok=True)
    note_file = out_dir / f"{_stem(mp3_path)}_note.md"

    if not note_file.exists():
        console.print(f"[yellow]Note not found. Running process first…[/yellow]")
        cmd_process([str(mp3_path), "--category", str(category_id)])

    transcript = ""
    summary: dict = {}
    mm_md = ""
    if note_file.exists():
        raw = note_file.read_text()
        # Quick extraction from Markdown sections
        sections = raw.split("\n## ")
        for s in sections:
            if s.startswith("Transcript"):
                transcript = s.split("\n", 1)[-1].strip()
            elif s.startswith("Mind Map"):
                mm_md = s.split("\n", 1)[-1].strip()

    from src.categories import get_category
    category = get_category(category_id)
    exported: list[Path] = []

    for fmt in formats:
        fmt = fmt.strip().lower()
        if fmt == "md":
            exported.append(note_file)
        elif fmt == "pdf":
            from src.export.pdf_exporter import export_full_note
            p = export_full_note(
                title=_stem(mp3_path),
                transcript=transcript,
                summary=summary,
                category_name=category.name,
                output_path=out_dir / f"{_stem(mp3_path)}.pdf",
            )
            exported.append(p)
        elif fmt == "docx":
            from src.export.docx_exporter import export_full_note
            p = export_full_note(
                title=_stem(mp3_path),
                transcript=transcript,
                summary=summary,
                category_name=category.name,
                output_path=out_dir / f"{_stem(mp3_path)}.docx",
            )
            exported.append(p)
        elif fmt == "srt":
            from src.ai.srt import generate_srt
            srt_text = generate_srt(mp3_path)
            srt_path = out_dir / f"{_stem(mp3_path)}.srt"
            srt_path.write_text(srt_text)
            exported.append(srt_path)
        elif fmt == "wav":
            from pydub import AudioSegment
            wav_path = out_dir / f"{_stem(mp3_path)}.wav"
            AudioSegment.from_mp3(str(mp3_path)).export(str(wav_path), format="wav")
            exported.append(wav_path)
        else:
            console.print(f"[yellow]Unknown format:[/yellow] {fmt}")

    if dest == "gdrive" and exported:
        from src.export.gdrive_sync import upload_file
        for f in exported:
            link = upload_file(f)
            console.print(f"[green]Uploaded to Drive:[/green] {f.name} → {link}")
    else:
        for f in exported:
            console.print(f"[green]Exported:[/green] {f}")


def cmd_categories(args: list[str]) -> None:
    """List all 15 recording categories."""
    _print_categories()


def _print_categories() -> None:
    from src.categories import list_categories
    table = Table(title="Recording Categories")
    table.add_column("ID")
    table.add_column("Name")
    table.add_column("Description")
    for id_, name, desc in list_categories():
        table.add_row(str(id_), name, desc)
    console.print(table)


# ---------------------------------------------------------------------------
# Main dispatcher
# ---------------------------------------------------------------------------

COMMANDS = {
    "scan": (cmd_scan, "Scan BLE for FW920 recorder"),
    "connect": (cmd_connect, "Connect and show device status"),
    "record": (cmd_record, "Control recording: start|stop|pause"),
    "list": (cmd_list, "List recordings on USB drive"),
    "process": (cmd_process, "<file> [--category N] [--lang en] — run AI pipeline"),
    "export": (cmd_export, "<file> --format md,pdf,docx,srt,wav [--dest gdrive]"),
    "categories": (cmd_categories, "List all 15 recording categories"),
}


def print_help() -> None:
    console.print(Panel(
        "[bold]Notetaker[/bold] — ELVANZA FW920 AI Voice Recorder Companion\n",
        subtitle="python src/cli.py <command> [args]",
    ))
    table = Table(show_header=False, box=None, padding=(0, 2))
    for cmd, (_, desc) in COMMANDS.items():
        table.add_row(f"[cyan]{cmd}[/cyan]", desc)
    console.print(table)


def main() -> None:
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print_help()
        return

    command = sys.argv[1]
    args = sys.argv[2:]

    if command not in COMMANDS:
        console.print(f"[red]Unknown command:[/red] {command}")
        print_help()
        sys.exit(1)

    COMMANDS[command][0](args)


if __name__ == "__main__":
    main()
