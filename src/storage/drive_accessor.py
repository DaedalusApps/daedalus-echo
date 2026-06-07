"""Finds and lists recordings from the FW920 exFAT USB drive."""
import os
import shutil
from pathlib import Path


DEVICE_VOLUME_NAMES = ["FW920", "RECORDER", "RECORD", "VOICE", "DC9E-7859"]
RECORD_FOLDER_NAMES = ["RECORDER", "RECORD"]  # FW920 uses "RECORDER"


def _find_record_dir(drive: Path) -> Path | None:
    for name in RECORD_FOLDER_NAMES:
        d = drive / name
        if d.is_dir():
            return d
    return None


def find_mounted_drive() -> Path | None:
    """Search common Linux mount points for the FW920 exFAT drive."""
    search_roots = [
        Path("/media") / os.environ.get("USER", ""),
        Path("/media"),
        Path("/mnt"),
        Path("/run/media") / os.environ.get("USER", ""),
    ]
    for root in search_roots:
        if not root.exists():
            continue
        for candidate in root.iterdir():
            if candidate.name.upper() in [v.upper() for v in DEVICE_VOLUME_NAMES]:
                return candidate
            # Also accept any drive that has a RECORDER or RECORD folder
            if _find_record_dir(candidate) is not None:
                return candidate
    return None


def list_recordings(drive: Path | None = None) -> list[Path]:
    """Return sorted list of MP3 files in the RECORDER/RECORD folder."""
    if drive is None:
        drive = find_mounted_drive()
    if drive is None:
        raise FileNotFoundError(
            "FW920 drive not found. Plug in the recorder via USB and try again."
        )
    record_dir = _find_record_dir(drive)
    if record_dir is None:
        raise FileNotFoundError(f"RECORDER folder not found on drive: {drive}")
    files = sorted(record_dir.glob("*.mp3"), key=lambda f: f.stat().st_mtime, reverse=True)
    return files


def import_recording(src: Path, dest_dir: Path) -> Path:
    """Copy a recording from the USB drive to local storage. Returns the local path."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / src.name
    if not dest.exists():
        shutil.copy2(src, dest)
    return dest


def recording_info(path: Path) -> dict:
    """Return size and mtime metadata for a recording file."""
    stat = path.stat()
    return {
        "name": path.name,
        "path": str(path),
        "size_mb": round(stat.st_size / 1_048_576, 2),
        "modified": stat.st_mtime,
    }
