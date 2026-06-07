"""Async BLE connection manager for the FW920 recorder.

Uses the B0B0 service command channel discovered via DOWAY HCI snoop:
  Write:   B0B1 (0x002B) — send commands
  Notify:  B0B2/B0B3/B0B4 — receive responses

Init sequence runs automatically on connect; the device disconnects in
~5 seconds without SYNC_TIME, so the 7-command sequence fires immediately
after subscribing to notifications.
"""

import asyncio
import datetime
import logging

from bleak import BleakClient
from bleak.backends.device import BLEDevice
from bleak.exc import BleakError

from src.ble.protocol import (
    B0B2_NOTIFY_UUID,
    B0B3_NOTIFY_UUID,
    B0B4_NOTIFY_UUID,
    WRITE_CHAR_UUID,
    cmd_cmd18,
    cmd_confirm_done,
    cmd_get_fw_version,
    cmd_get_serial,
    cmd_get_status,
    cmd_list_files,
    cmd_set_fw_version,
    cmd_start_recording,
    cmd_stop_recording,
    cmd_sync_time,
    parse_response,
    parse_status_packet,
)

log = logging.getLogger(__name__)

_RECONNECT_ATTEMPTS = 3
_CMD_TIMEOUT = 4.0
_INTER_CMD_DELAY = 0.15


class BleConnection:
    def __init__(self, device: BLEDevice) -> None:
        self._device = device
        self._client: BleakClient | None = None
        self.last_status: dict = {}
        self.device_serial: str = ""
        self.fw_version: str = "176046"
        self._response_queue: asyncio.Queue = asyncio.Queue()
        self._files: list[dict] = []

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _on_notify(self, _sender, data: bytearray) -> None:
        parsed = parse_response(bytes(data))
        if parsed:
            self._response_queue.put_nowait(parsed)
            if "battery_pct" in parsed or "storage_free_kb" in parsed:
                self.last_status = parsed
            if "serial" in parsed:
                self.device_serial = parsed["serial"]
            if "fw_version" in parsed:
                self.fw_version = parsed["fw_version"]

    async def _send(self, packet: bytes) -> None:
        if self._client is None or not self._client.is_connected:
            raise BleakError("Not connected.")
        await self._client.write_gatt_char(WRITE_CHAR_UUID, packet, response=False)

    async def _send_recv(self, packet: bytes, timeout: float = _CMD_TIMEOUT) -> dict | None:
        cmd_byte = packet[3]
        while not self._response_queue.empty():
            self._response_queue.get_nowait()
        await self._send(packet)
        deadline = asyncio.get_event_loop().time() + timeout
        while asyncio.get_event_loop().time() < deadline:
            try:
                resp = await asyncio.wait_for(self._response_queue.get(), timeout=0.5)
                if resp.get("cmd") == cmd_byte:
                    return resp
            except asyncio.TimeoutError:
                pass
        return None

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    async def connect(self) -> None:
        for attempt in range(1, _RECONNECT_ATTEMPTS + 1):
            try:
                self._client = BleakClient(self._device, timeout=20.0)
                await self._client.connect()
                log.info("Connected to FW920 (%s)", self._device.address)

                for uuid in (B0B2_NOTIFY_UUID, B0B3_NOTIFY_UUID, B0B4_NOTIFY_UUID):
                    try:
                        await self._client.start_notify(uuid, self._on_notify)
                    except BleakError:
                        pass

                await self._run_init()
                return

            except (BleakError, asyncio.TimeoutError):
                self._client = None
                if attempt == _RECONNECT_ATTEMPTS:
                    raise
                await asyncio.sleep(1.0)

    async def disconnect(self) -> None:
        if self._client is None or not self._client.is_connected:
            return
        for uuid in (B0B2_NOTIFY_UUID, B0B3_NOTIFY_UUID, B0B4_NOTIFY_UUID):
            try:
                await self._client.stop_notify(uuid)
            except BleakError:
                pass
        try:
            await self._client.disconnect()
        except BleakError:
            pass
        self._client = None

    async def _run_init(self) -> None:
        """7-command init sequence — must complete before device's ~5s timeout."""
        cmds = [
            ("GET_FW_VERSION", cmd_get_fw_version()),
            ("SET_FW_VERSION", cmd_set_fw_version(self.fw_version)),
            ("GET_SERIAL",     cmd_get_serial()),
            ("SYNC_TIME",      cmd_sync_time()),
            ("GET_STATUS",     cmd_get_status()),
            ("CMD_18",         cmd_cmd18()),
            ("LIST_FILES",     cmd_list_files()),
        ]
        self._files = []
        for name, pkt in cmds:
            resp = await self._send_recv(pkt, timeout=2.0)
            if resp:
                log.debug("Init %s → %s", name, resp)
                if name == "GET_FW_VERSION" and "fw_version" in resp:
                    self.fw_version = resp["fw_version"]
            await asyncio.sleep(_INTER_CMD_DELAY)

        await self._collect_file_list()

    async def _collect_file_list(self) -> None:
        await asyncio.sleep(0.3)
        while not self._response_queue.empty():
            resp = self._response_queue.get_nowait()
            if resp.get("cmd") == 0x0A and "filename" in resp:
                self._files.append({
                    "name": resp["filename"],
                    "size_bytes": resp.get("size_bytes", 0),
                })

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def get_status(self) -> dict:
        resp = await self._send_recv(cmd_get_status())
        if resp:
            self.last_status = resp
        return self.last_status

    async def list_files(self) -> list[dict]:
        self._files = []
        await self._send(cmd_list_files())
        await asyncio.sleep(1.0)
        await self._collect_file_list()
        return self._files

    async def sync_time(self, dt: datetime.datetime | None = None) -> bool:
        resp = await self._send_recv(cmd_sync_time(dt))
        return bool(resp and resp.get("ok"))

    async def send_command(self, packet: bytes) -> dict | None:
        return await self._send_recv(packet)

    async def start_recording(self) -> dict | None:
        """Start recording (CMD 0x06). Device responds with new filename."""
        return await self._send_recv(cmd_start_recording())

    async def stop_recording(self) -> dict | None:
        """Stop recording (CMD 0x08) then confirm done (CMD 0x07)."""
        resp = await self._send_recv(cmd_stop_recording())
        await asyncio.sleep(0.2)
        await self._send_recv(cmd_confirm_done())
        return resp

    async def pause_recording(self) -> None:
        # Not supported — device only has start/stop, no pause via BLE
        raise NotImplementedError("FW920 does not support BLE pause; use physical button.")

    async def __aenter__(self) -> "BleConnection":
        await self.connect()
        return self

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        await self.disconnect()
