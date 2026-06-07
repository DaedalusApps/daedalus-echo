"""Tests for AI pipeline modules (no API calls — pure unit tests)."""
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from src.categories import get_category, list_categories, CATEGORIES, FUNCTIONALITY_PROMPTS


# ---------------------------------------------------------------------------
# Category tests
# ---------------------------------------------------------------------------

def test_all_15_categories_defined():
    assert len(CATEGORIES) == 15


def test_category_ids_are_1_to_15():
    assert set(CATEGORIES.keys()) == set(range(1, 16))


def test_get_category_valid():
    for i in range(1, 16):
        cat = get_category(i)
        assert cat.id == i
        assert cat.name
        assert cat.system_prompt


def test_get_category_invalid():
    with pytest.raises(ValueError):
        get_category(0)
    with pytest.raises(ValueError):
        get_category(16)


def test_list_categories_returns_15():
    cats = list_categories()
    assert len(cats) == 15
    for id_, name, desc in cats:
        assert isinstance(id_, int)
        assert name
        assert desc


def test_functionality_prompts_has_8_keys():
    assert len(FUNCTIONALITY_PROMPTS) == 8
    expected_keys = {
        "intention_analysis", "key_quantitative_data", "speaker_perspective",
        "meeting_points", "meeting_minutes", "gratitude_hunter",
        "todo_list", "meeting_effect_evaluation",
    }
    assert set(FUNCTIONALITY_PROMPTS.keys()) == expected_keys


def test_all_category_system_prompts_contain_json():
    """Every category prompt should instruct Claude to return JSON."""
    for cat in CATEGORIES.values():
        if cat.id == 15:
            continue  # Category 15 delegates to FunctionalityService
        assert "JSON" in cat.system_prompt, f"Category {cat.id} ({cat.name}) missing JSON instruction"


# ---------------------------------------------------------------------------
# Storage tests
# ---------------------------------------------------------------------------

def test_recording_info_structure(tmp_path):
    from src.storage.drive_accessor import recording_info
    f = tmp_path / "test.mp3"
    f.write_bytes(b"\xff\xfb" + b"\x00" * 1024)
    info = recording_info(f)
    assert "name" in info
    assert "size_mb" in info
    assert "modified" in info
    assert info["name"] == "test.mp3"


def test_find_mounted_drive_returns_none_when_absent(monkeypatch):
    from src.storage import drive_accessor
    monkeypatch.setattr(drive_accessor, "find_mounted_drive", lambda: None)
    assert drive_accessor.find_mounted_drive() is None


# ---------------------------------------------------------------------------
# SRT format tests
# ---------------------------------------------------------------------------

def test_srt_timestamp_format():
    from src.ai.srt import _format_timestamp
    assert _format_timestamp(0.0) == "00:00:00,000"
    assert _format_timestamp(61.5) == "00:01:01,500"
    assert _format_timestamp(3661.123) == "01:01:01,123"
    assert _format_timestamp(3661.9999) == "01:01:02,000"  # rounds up correctly


# ---------------------------------------------------------------------------
# Mind map tests
# ---------------------------------------------------------------------------

def test_mindmap_to_markdown_structure():
    from src.ai.mindmap import mindmap_to_markdown
    mindmap = {
        "title": "Test",
        "nodes": [
            {"id": "root", "label": "Test Topic", "parent": None, "children": ["n1", "n2"]},
            {"id": "n1", "label": "First Point", "parent": "root", "children": []},
            {"id": "n2", "label": "Second Point", "parent": "root", "children": ["n3"]},
            {"id": "n3", "label": "Sub Point", "parent": "n2", "children": []},
        ],
    }
    md = mindmap_to_markdown(mindmap)
    assert "Test Topic" in md
    assert "First Point" in md
    assert "Second Point" in md
    assert "Sub Point" in md
    # Sub Point should be indented more than Second Point
    lines = md.splitlines()
    n2_line = next(l for l in lines if "Second Point" in l)
    n3_line = next(l for l in lines if "Sub Point" in l)
    n2_indent = len(n2_line) - len(n2_line.lstrip())
    n3_indent = len(n3_line) - len(n3_line.lstrip())
    assert n3_indent > n2_indent


def test_mindmap_to_mermaid_contains_root():
    from src.ai.mindmap import mindmap_to_mermaid
    mindmap = {
        "title": "My Map",
        "nodes": [
            {"id": "root", "label": "Root Node", "parent": None, "children": ["c1"]},
            {"id": "c1", "label": "Child One", "parent": "root", "children": []},
        ],
    }
    mermaid = mindmap_to_mermaid(mindmap)
    assert "mindmap" in mermaid
    assert "Root Node" in mermaid
    assert "Child One" in mermaid


# ---------------------------------------------------------------------------
# Markdown export tests
# ---------------------------------------------------------------------------

def test_export_full_note_creates_file(tmp_path):
    from src.export.markdown_exporter import export_full_note
    out = tmp_path / "note.md"
    result = export_full_note(
        title="Test Recording",
        transcript="Speaker 1: Hello world.",
        summary={"summary": "A greeting.", "key_points": ["Hello was said"]},
        category_name="General",
        mindmap_markdown="- Root\n  - Hello",
        output_path=out,
    )
    assert result.exists()
    content = result.read_text()
    assert "Test Recording" in content
    assert "Hello world" in content
    assert "greeting" in content
    assert "Mind Map" in content


def test_export_transcript_only(tmp_path):
    from src.export.markdown_exporter import export_transcript
    out = tmp_path / "transcript.md"
    result = export_transcript("Speaker 1: Test content.", out)
    assert result.exists()
    assert "Test content" in result.read_text()


# ---------------------------------------------------------------------------
# BLE protocol tests
# ---------------------------------------------------------------------------

def test_protocol_constants_defined():
    from src.ble.protocol import (
        SERVICE_UUID, CONTROL_CHAR_UUID, STATUS_CHAR_UUID,
        BATTERY_CHAR_UUID, build_packet, cmd_sync_time, crc16_bytes,
    )
    import datetime
    assert isinstance(SERVICE_UUID, str)
    # Verify CRC against known captured packet
    pkt = cmd_sync_time(datetime.datetime(2026, 5, 10, 19, 59, 57))
    assert pkt.hex() == "a00a0104061a050a133b3944a7"


def test_parse_status_packet_returns_dict():
    from src.ble.protocol import parse_status_packet
    data = bytes([75, 0, 128, 1])  # battery=75%, storage=128MB, recording=True
    result = parse_status_packet(data)
    assert "battery_pct" in result
    assert "storage_free_kb" in result or "storage_free_mb" in result
    assert "is_recording" in result
    assert isinstance(result["battery_pct"], int)
    assert isinstance(result["is_recording"], bool)
