"""
FW920 BLE GATT protocol — fully reverse-engineered from DOWAY HCI snoop.

BLE service used for commands: B0B0 (NOT FFD0 as originally thought)
  Write:   0000b0b1-0000-1000-8000-00805f9b34fb  (handle 0x002B)
  Notify:  0000b0b2-0000-1000-8000-00805f9b34fb  (handle 0x002D)

Packet format: A0 0A | category(1) | cmd(1) | len(1) | payload(len) | CRC16(2)
CRC: CRC16-ARC (poly=0x8005, init=0, refIn=True, refOut=True), big-endian bytes.

Init sequence (must complete within ~5s or device disconnects):
  1. GET_FW_VERSION  (cmd 0x02)
  2. SET_FW_VERSION  (cmd 0x03, echo firmware version back)
  3. GET_SERIAL      (cmd 0x01)
  4. SYNC_TIME       (cmd 0x04, send current time)
  5. GET_STATUS      (cmd 0x05)
  6. CMD_18          (cmd 0x18, payload 0x00)
  7. LIST_FILES      (cmd 0x0A)

Captured FW920 firmware version: "176046"
Captured FW920 serial: "KF9HA11240"
"""
from __future__ import annotations
import datetime

# ---------------------------------------------------------------------------
# GATT UUIDs — B0B0 service is the command channel
# ---------------------------------------------------------------------------
B0B0_SERVICE_UUID  = "0000b0b0-0000-1000-8000-00805f9b34fb"
B0B1_WRITE_UUID    = "0000b0b1-0000-1000-8000-00805f9b34fb"  # write commands here
B0B2_NOTIFY_UUID   = "0000b0b2-0000-1000-8000-00805f9b34fb"  # receive responses here
B0B3_NOTIFY_UUID   = "0000b0b3-0000-1000-8000-00805f9b34fb"
B0B4_NOTIFY_UUID   = "0000b0b4-0000-1000-8000-00805f9b34fb"

# FFD0 service is present but NOT used for the main protocol
FFD0_SERVICE_UUID  = "0000ffd0-0000-1000-8000-00805f9b34fb"
FFD1_WRITE_UUID    = "0000ffd1-0000-1000-8000-00805f9b34fb"
FFD2_NOTIFY_UUID   = "0000ffd2-0000-1000-8000-00805f9b34fb"

# Backwards-compatible aliases
SERVICE_UUID       = B0B0_SERVICE_UUID
WRITE_CHAR_UUID    = B0B1_WRITE_UUID
NOTIFY_CHAR_UUID   = B0B2_NOTIFY_UUID
CONTROL_CHAR_UUID  = B0B1_WRITE_UUID
STATUS_CHAR_UUID   = B0B2_NOTIFY_UUID

BATTERY_CHAR_UUID  = "00002a19-0000-1000-8000-00805f9b34fb"

# ---------------------------------------------------------------------------
# CRC16-ARC (poly=0x8005, init=0, refIn, refOut, big-endian output)
# ---------------------------------------------------------------------------
_CRC16_TABLE: list[int] = []

def _build_table() -> None:
    for i in range(256):
        crc = 0
        b = i
        for _ in range(8):
            if (b ^ crc) & 1:
                crc = (crc >> 1) ^ 0xA001
            else:
                crc >>= 1
            b >>= 1
        _CRC16_TABLE.append(crc)

_build_table()


def crc16_arc(data: bytes) -> int:
    crc = 0x0000
    for byte in data:
        crc = (crc >> 8) ^ _CRC16_TABLE[(crc ^ byte) & 0xFF]
    return crc


def crc16_bytes(data: bytes) -> bytes:
    """Return 2 CRC bytes in big-endian order."""
    crc = crc16_arc(data)
    return bytes([crc >> 8, crc & 0xFF])


# ---------------------------------------------------------------------------
# Packet builder
# ---------------------------------------------------------------------------
HEADER   = bytes([0xA0, 0x0A])
CATEGORY = 0x01


def build_packet(cmd: int, payload: bytes = b"") -> bytes:
    body = bytes([CATEGORY, cmd, len(payload)]) + payload
    packet = HEADER + body
    return packet + crc16_bytes(packet)


# ---------------------------------------------------------------------------
# Command packets (pre-built or factory functions)
# ---------------------------------------------------------------------------
def cmd_get_fw_version() -> bytes:
    return build_packet(0x02)


def cmd_set_fw_version(version: str = "176046") -> bytes:
    v = version.encode()[:6].ljust(16, b"\x00") + b"\x00" * (17 - min(len(version), 16))
    return build_packet(0x03, v[:17])


def cmd_get_serial() -> bytes:
    return build_packet(0x01)


def cmd_sync_time(dt: datetime.datetime | None = None) -> bytes:
    if dt is None:
        dt = datetime.datetime.now()
    payload = bytes([
        dt.year - 2000,
        dt.month,
        dt.day,
        dt.hour,
        dt.minute,
        dt.second,
    ])
    return build_packet(0x04, payload)


def cmd_get_status() -> bytes:
    return build_packet(0x05)


def cmd_cmd18() -> bytes:
    return build_packet(0x18, bytes([0x00]))


def cmd_list_files() -> bytes:
    return build_packet(0x0A)


def cmd_download_file(filename: str) -> bytes:
    """Request download of a recording by its base filename (e.g. '20260507121415')."""
    payload = b"\x10\x00" + filename.encode()[:14].ljust(14, b"\x00")
    return build_packet(0x06, payload)


def cmd_request_file_info() -> bytes:
    """Request file metadata (CMD 0x15, empty payload).
    Device responds with filename + size, then streams audio on B0B3 (0x0030).
    """
    return build_packet(0x15)


def cmd_start_recording() -> bytes:
    """Start recording via in-app button (CMD 0x06, empty payload).
    Verified raw: a00a010600a1d1
    Device responds with CMD 0x06 + new filename.
    """
    return build_packet(0x06)


def cmd_stop_recording() -> bytes:
    """Stop recording via in-app button (CMD 0x08, empty payload).
    Verified raw: a00a010800c1d5
    Device responds with CMD 0x08 + final filename.
    """
    return build_packet(0x08)


def cmd_confirm_done() -> bytes:
    """Confirm transfer/recording done (CMD 0x07, empty payload).
    Verified raw: a00a01070031d0
    Send after stop recording. Device responds with final file info.
    """
    return build_packet(0x07)


# ---------------------------------------------------------------------------
# Full recording protocol (from HCI snoop — in-app button session)
# ---------------------------------------------------------------------------
# Via PHYSICAL BUTTON (device-initiated):
#   DEV→APP CMD 0x06  - device notifies app a recording started/is available
#   APP→DEV CMD 0x15  - app requests file metadata
#   DEV→APP CMD 0x15  - device responds with filename + size
#   DEV→APP h=0x0030  - device streams raw audio in real-time via B0B3
#   DEV→APP CMD 0x0F  - periodic status: battery %, rec_state=1 (recording)
#   DEV→APP CMD 0x07  - recording/transfer complete
#
# Via IN-APP BUTTON (app-initiated):
#   APP→DEV CMD 0x06  - start recording          → raw: a00a010600a1d1
#   DEV→APP CMD 0x06  - ACK + new filename
#   DEV→APP h=0x0030  - real-time audio stream
#   DEV→APP CMD 0x0F  - periodic status updates
#   APP→DEV CMD 0x08  - stop recording           → raw: a00a010800c1d5
#   DEV→APP CMD 0x08  - ACK + final filename
#   APP→DEV CMD 0x07  - confirm done             → raw: a00a01070031d0
#   DEV→APP CMD 0x07  - final file info (name, size)
#
# Status update (CMD 0x0F) payload layout:
#   byte 0:    0x00 (padding)
#   byte 1:    battery % (0x64 = 100%)
#   byte 2:    recording state (0x01 = recording, 0x00 = idle)
#   byte 3:    unknown flags


# ---------------------------------------------------------------------------
# INIT_SEQUENCE: send these in order after subscribing to B0B2 notifications
# ---------------------------------------------------------------------------
def init_sequence(fw_version: str = "176046") -> list[bytes]:
    return [
        cmd_get_fw_version(),
        cmd_set_fw_version(fw_version),
        cmd_get_serial(),
        cmd_sync_time(),
        cmd_get_status(),
        cmd_cmd18(),
        cmd_list_files(),
    ]


# ---------------------------------------------------------------------------
# Response parser
# ---------------------------------------------------------------------------
def parse_response(data: bytes) -> dict | None:
    if len(data) < 5 or data[:2] != HEADER:
        return None
    cat = data[2]
    cmd = data[3]
    plen = data[4]
    if len(data) < 5 + plen + 2:
        return None
    payload = data[5:5 + plen]
    crc = data[5 + plen:5 + plen + 2]

    result: dict = {"cat": cat, "cmd": cmd, "payload": payload, "raw": data.hex()}

    if cmd == 0x01 and plen >= 10:
        result["serial"] = payload[:10].decode("ascii", "ignore").rstrip("\x00")
    elif cmd == 0x02 and plen > 0:
        result["fw_version"] = payload[:6].decode("ascii", "ignore").rstrip("\x00")
    elif cmd == 0x03:
        result["ok"] = plen >= 1 and payload[0] == 0
    elif cmd == 0x04:
        result["ok"] = plen >= 1 and payload[0] == 0
    elif cmd == 0x05 and plen >= 10:
        result["storage_free_kb"] = int.from_bytes(payload[0:4], "little") // 1024
        result["storage_total_kb"] = int.from_bytes(payload[4:8], "little") // 1024
        result["battery_pct"] = payload[9] if plen > 9 else 0
        fw = payload[14:30].decode("ascii", "ignore").rstrip("\x00") if plen > 14 else ""
        result["fw_name"] = fw
    elif cmd == 0x0A and plen >= 15:
        name_raw = payload[1:15].decode("ascii", "ignore").rstrip("\x00")
        if name_raw:
            size = int.from_bytes(payload[16:20], "little") if plen >= 20 else 0
            result["filename"] = name_raw + ".mp3"
            result["size_bytes"] = size
        else:
            result["end_of_list"] = True
    elif cmd == 0x0F and plen >= 3:
        # Periodic status update sent during recording
        result["battery_pct"] = payload[1] if plen > 1 else 0
        result["is_recording"] = bool(payload[2]) if plen > 2 else False
    elif cmd == 0x15 and plen >= 15:
        # File info response (app sent CMD 0x15 with empty payload to request)
        name_raw = payload[2:16].decode("ascii", "ignore").rstrip("\x00")
        if name_raw:
            size = int.from_bytes(payload[16:20], "little") if plen >= 20 else 0
            result["filename"] = name_raw + ".mp3"
            result["size_bytes"] = size
    elif cmd == 0x06:
        # Device notifying app a recording is available/started
        name_raw = payload[2:16].decode("ascii", "ignore").rstrip("\x00") if plen >= 16 else ""
        if name_raw:
            result["filename"] = name_raw + ".mp3"
        result["recording_available"] = True
    elif cmd == 0x07:
        # Transfer / recording complete
        name_raw = payload[0:14].decode("ascii", "ignore").rstrip("\x00") if plen >= 14 else ""
        if name_raw:
            result["filename"] = name_raw + ".mp3"
        result["transfer_complete"] = True

    return result


def parse_status_packet(data: bytes) -> dict:
    """Backwards-compatible wrapper."""
    parsed = parse_response(data) or {}
    return {
        "raw_hex": data.hex(),
        "battery_pct": parsed.get("battery_pct", 0),
        "storage_free_kb": parsed.get("storage_free_kb", 0),
        "is_recording": False,
        **parsed,
    }
