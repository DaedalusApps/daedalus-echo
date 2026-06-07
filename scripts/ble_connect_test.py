"""
FW920 BLE connection test using the fully reverse-engineered DOWAY protocol.

Connects, runs the 7-command init sequence (GET_FW_VERSION → SET_FW_VERSION →
GET_SERIAL → SYNC_TIME → GET_STATUS → CMD_18 → LIST_FILES), then stays
connected for further interaction.

NOTE: Linux BlueZ may fail to connect because the Intel adapter tries BR/EDR
first (missing 0x04 flag in ESP32-C3 advertising). This works correctly from
Android. Run 'sudo btmgmt power off && sudo btmgmt power on' if connection
times out — or use the Android app (Phase 2) for BLE control.
"""

import asyncio
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from bleak import BleakScanner
from rich.console import Console
from rich.table import Table

from src.ble.protocol import (
    B0B2_NOTIFY_UUID, B0B3_NOTIFY_UUID, B0B4_NOTIFY_UUID,
    WRITE_CHAR_UUID, cmd_get_status, cmd_list_files, parse_response,
    init_sequence,
)
from src.ble.connection import BleConnection

DEVICE_NAME = "FW920"
console = Console()


async def main() -> None:
    console.print(f"[cyan]Scanning for {DEVICE_NAME}…[/cyan]")

    detected = asyncio.Event()
    captured: dict = {}

    def on_detect(device, adv):
        if device.name and DEVICE_NAME in device.name and not detected.is_set():
            captured["device"] = device
            detected.set()

    async with BleakScanner(detection_callback=on_detect):
        await asyncio.wait_for(detected.wait(), timeout=15.0)

    device = captured["device"]
    console.print(f"[green]Found:[/green] {device.name} ({device.address})")
    console.print("[cyan]Connecting and running init sequence…[/cyan]")

    try:
        async with BleConnection(device) as conn:
            console.print("[bold green]CONNECTED and init complete![/bold green]\n")

            # Show device info
            table = Table(title="FW920 Device Info")
            table.add_column("Property")
            table.add_column("Value")
            table.add_row("Serial", conn.device_serial or "?")
            table.add_row("Firmware", conn.fw_version or "?")
            if conn.last_status:
                table.add_row("Battery", f"{conn.last_status.get('battery_pct', '?')}%")
                free = conn.last_status.get('storage_free_kb', 0)
                total = conn.last_status.get('storage_total_kb', 0)
                table.add_row("Storage", f"{free//1024} MB free / {total//1024} MB total")
            console.print(table)

            # Show files
            if conn._files:
                ftable = Table(title="Recordings on Device")
                ftable.add_column("Filename")
                ftable.add_column("Size", justify="right")
                for f in conn._files:
                    ftable.add_row(f["name"], f"{f['size_bytes']//1024} KB")
                console.print(ftable)
            else:
                console.print("[yellow]No recordings found on device.[/yellow]")

            console.print("\n[dim]Staying connected 10s — press Ctrl+C to exit[/dim]")
            await asyncio.sleep(10)

    except asyncio.TimeoutError:
        console.print("[red]Connection timed out.[/red]")
        console.print("[yellow]Linux BLE note:[/yellow] This Intel adapter tries BR/EDR first. "
                      "Android connects fine via TRANSPORT_LE. "
                      "Try from the Android app for reliable BLE control.")
    except Exception as e:
        console.print(f"[red]Error:[/red] {e}")


if __name__ == "__main__":
    asyncio.run(main())
