"""Aggressive BLE connection diagnostic for the FW920.

Strategies tried (in order):
1. Active scan + immediate connect on first advertisement seen
2. Scan with detection callback, log advertisement details, then connect
3. Try connection with explicit address types

Goal: figure out why bleak's standard connect times out.
"""

import asyncio
import logging
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from bleak import BleakClient, BleakScanner
from bleak.exc import BleakError
from rich.console import Console

DEVICE_NAME = "FW920"
console = Console()

# Enable bleak debug logging to see what's happening
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
)
# But keep dbus_fast quiet — too noisy
logging.getLogger("dbus_fast").setLevel(logging.WARNING)


async def strategy_active_scan_then_connect() -> bool:
    """Strategy 1: capture full advertisement details, then connect."""
    console.print("\n[bold cyan]── Strategy 1: capture advertisement, then connect ──[/bold cyan]")

    detected_event = asyncio.Event()
    captured = {}

    def detection_callback(device, adv_data):
        if device.name and DEVICE_NAME in device.name:
            captured["device"] = device
            captured["adv"] = adv_data
            detected_event.set()

    async with BleakScanner(detection_callback=detection_callback) as scanner:
        console.print("[dim]Scanning (max 15s)…[/dim]")
        try:
            await asyncio.wait_for(detected_event.wait(), timeout=15.0)
        except asyncio.TimeoutError:
            console.print("[red]No advertisements received in 15s.[/red]")
            return False

    device = captured["device"]
    adv = captured["adv"]
    console.print(f"[green]✓ Saw advertisement:[/green] {device.name} ({device.address})")
    console.print(f"  RSSI: {adv.rssi} dBm")
    console.print(f"  Service UUIDs in adv: {adv.service_uuids}")
    console.print(f"  Service data:        {adv.service_data}")
    console.print(f"  Manufacturer data:   {adv.manufacturer_data}")
    console.print(f"  TX Power:            {adv.tx_power}")
    console.print(f"  Local name:          {adv.local_name}")
    console.print(f"  Platform data:       {adv.platform_data}")

    console.print("\n[dim]Attempting connect (timeout 20s)…[/dim]")
    try:
        client = BleakClient(device, timeout=20.0)
        await client.connect()
        console.print(f"[green]✓ CONNECTED![/green]")
        for svc in client.services:
            console.print(f"  Service: {svc.uuid}")
        await client.disconnect()
        return True
    except Exception as exc:
        console.print(f"[red]✗ Connect failed:[/red] {type(exc).__name__}: {exc}")
        return False


async def strategy_connect_during_scan() -> bool:
    """Strategy 2: connect while scanner is still actively running."""
    console.print("\n[bold cyan]── Strategy 2: connect WHILE scanning is still active ──[/bold cyan]")

    detected = asyncio.Event()
    captured_device = {"d": None}

    def callback(device, adv_data):
        if device.name and DEVICE_NAME in device.name and not detected.is_set():
            captured_device["d"] = device
            detected.set()

    scanner = BleakScanner(detection_callback=callback)
    await scanner.start()
    console.print("[dim]Scanner running, waiting for FW920…[/dim]")
    try:
        await asyncio.wait_for(detected.wait(), timeout=15.0)
    except asyncio.TimeoutError:
        await scanner.stop()
        console.print("[red]No advertisements.[/red]")
        return False

    device = captured_device["d"]
    console.print(f"[green]✓ Saw FW920 at {device.address}[/green]")
    console.print("[yellow]NOT stopping scanner — connecting during scan…[/yellow]")

    try:
        client = BleakClient(device, timeout=20.0)
        await client.connect()
        console.print(f"[green]✓ CONNECTED![/green]")
        await asyncio.sleep(1)
        for svc in client.services:
            console.print(f"  Service: {svc.uuid}")
        await client.disconnect()
        await scanner.stop()
        return True
    except Exception as exc:
        console.print(f"[red]✗ Connect failed:[/red] {type(exc).__name__}: {exc}")
        await scanner.stop()
        return False


async def main() -> None:
    console.print("[bold]FW920 connection diagnostic[/bold]")
    console.print(f"Make sure: device is ON, blue LED FLASHING (BT search), no other phones connected.\n")

    if await strategy_active_scan_then_connect():
        console.print("\n[bold green]Strategy 1 worked.[/bold green]")
        return

    if await strategy_connect_during_scan():
        console.print("\n[bold green]Strategy 2 worked.[/bold green]")
        return

    console.print("\n[bold red]All strategies failed.[/bold red]")
    console.print("Check the DEBUG log above for the exact failure point.")


if __name__ == "__main__":
    asyncio.run(main())
