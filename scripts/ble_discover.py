"""Standalone BLE discovery script for the FW920 ELVANZA AI voice recorder."""

import asyncio
import json
from pathlib import Path

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from rich.console import Console
from rich.table import Table

DEVICE_NAME = "FW920"
LISTEN_SECONDS = 5
PROJECT_ROOT = Path(__file__).parent.parent
OUTPUT_FILE = PROJECT_ROOT / "ble_discovery_output.json"

console = Console()


async def find_device():
    console.print(f"[bold cyan]Scanning for {DEVICE_NAME}...[/bold cyan]")
    device = await BleakScanner.find_device_by_name(DEVICE_NAME, timeout=10.0)
    return device


async def try_read(client: BleakClient, uuid: str) -> bytes | None:
    try:
        return await client.read_gatt_char(uuid)
    except Exception:
        return None


async def try_write(client: BleakClient, uuid: str, value: bytes) -> str:
    try:
        await client.write_gatt_char(uuid, value, response=True)
        return "ack"
    except Exception as exc:
        return f"error: {exc}"


async def discover(client: BleakClient) -> dict:
    services_data = []
    notify_uuids: list[str] = []
    notifications: dict[str, list[str]] = {}

    table = Table(title=f"GATT Services — {DEVICE_NAME}", show_lines=True)
    table.add_column("Service UUID", style="yellow")
    table.add_column("Char UUID", style="cyan")
    table.add_column("Handle", justify="right")
    table.add_column("Properties", style="green")
    table.add_column("Value / Write result")

    for service in client.services:
        svc_entry = {"service_uuid": str(service.uuid), "characteristics": []}

        for char in service.characteristics:
            props = char.properties
            value_str = ""
            char_entry: dict = {
                "uuid": str(char.uuid),
                "handle": char.handle,
                "properties": props,
                "value": None,
                "write_0x01": None,
                "write_0x02": None,
            }

            if "read" in props:
                raw = await try_read(client, char.uuid)
                if raw is not None:
                    char_entry["value"] = raw.hex()
                    value_str = f"[read] {raw.hex()}"

            if "write" in props or "write-without-response" in props:
                r1 = await try_write(client, char.uuid, bytes([0x01]))
                r2 = await try_write(client, char.uuid, bytes([0x02]))
                char_entry["write_0x01"] = r1
                char_entry["write_0x02"] = r2
                value_str += f" [w01={r1}] [w02={r2}]"

            if "notify" in props or "indicate" in props:
                notify_uuids.append(str(char.uuid))
                notifications[str(char.uuid)] = []
                value_str += " [notify-pending]"

            table.add_row(
                str(service.uuid),
                str(char.uuid),
                str(char.handle),
                ", ".join(props),
                value_str.strip(),
            )
            svc_entry["characteristics"].append(char_entry)

        services_data.append(svc_entry)

    console.print(table)
    return {"services": services_data, "notify_uuids": notify_uuids, "notifications": notifications}


async def listen_notifications(client: BleakClient, notify_uuids: list[str], notifications: dict):
    if not notify_uuids:
        console.print("[yellow]No notify characteristics found.[/yellow]")
        return

    def make_handler(uuid: str):
        def handler(_sender, data: bytearray):
            hex_str = data.hex()
            notifications[uuid].append(hex_str)
            console.print(f"  [bold green]NOTIFY[/bold green] {uuid}: {hex_str}")
        return handler

    for uuid in notify_uuids:
        try:
            await client.start_notify(uuid, make_handler(uuid))
        except Exception as exc:
            console.print(f"  [red]Could not subscribe to {uuid}: {exc}[/red]")

    console.print(f"\n[bold]Listening for notifications ({LISTEN_SECONDS}s)...[/bold]")
    await asyncio.sleep(LISTEN_SECONDS)

    for uuid in notify_uuids:
        try:
            await client.stop_notify(uuid)
        except Exception:
            pass


async def main():
    device = await find_device()
    if device is None:
        console.print(f"[bold red]Device '{DEVICE_NAME}' not found. Is it powered on and nearby?[/bold red]")
        return

    console.print(f"[bold green]Found:[/bold green] {device.name} ({device.address})")

    try:
        async with BleakClient(device, timeout=30.0) as client:
            result = await discover(client)
            await listen_notifications(client, result["notify_uuids"], result["notifications"])
            result["device"] = {"name": device.name, "address": device.address}

    except BleakError as exc:
        console.print(f"[bold red]BLE error: {exc}[/bold red]")
        return

    OUTPUT_FILE.write_text(json.dumps(result, indent=2))
    console.print(f"\n[bold]Raw discovery data saved to:[/bold] {OUTPUT_FILE}")


if __name__ == "__main__":
    asyncio.run(main())
