"""
Force LE-only connection by setting BlueZ discovery filter to LE transport,
then connecting via D-Bus before the device leaves the cache.
"""

import asyncio
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import dbus
import dbus.mainloop.glib
from gi.repository import GLib

from bleak import BleakClient, BleakScanner
from rich.console import Console

DEVICE_NAME = "FW920"
DEVICE_ADDR = ""  # set via env: export FW920_ADDR=$(adb logcat -s R2.a | grep -m1 address | awk '{print $NF}')
console = Console()

received = []


def on_notify(char, data):
    hex_str = bytes(data).hex()
    received.append((str(char.uuid), bytes(data)))
    console.print(f"  [green]NOTIFY[/green] {char.uuid}: {hex_str}")


async def main():
    # Set LE-only scan filter via D-Bus BEFORE scanning
    bus = dbus.SystemBus()
    adapter = bus.get_object("org.bluez", "/org/bluez/hci0")
    adapter_iface = dbus.Interface(adapter, "org.bluez.Adapter1")

    console.print("[cyan]Setting LE-only discovery filter…[/cyan]")
    adapter_iface.SetDiscoveryFilter(dbus.Dictionary({
        "Transport": dbus.String("le"),
    }, signature="sv"))
    console.print("[green]Filter set: Transport=le[/green]")

    # Now scan and connect
    detected = asyncio.Event()
    captured = {"device": None}

    def on_detect(device, adv):
        if device.name and DEVICE_NAME in device.name and not detected.is_set():
            captured["device"] = device
            detected.set()

    async with BleakScanner(detection_callback=on_detect) as _:
        console.print("[cyan]Scanning (LE only)…[/cyan]")
        await asyncio.wait_for(detected.wait(), timeout=15.0)

    device = captured["device"]
    console.print(f"[green]Found:[/green] {device.name} ({device.address})")
    console.print("[cyan]Connecting…[/cyan]")

    async with BleakClient(device, timeout=15.0) as client:
        console.print("[bold green]CONNECTED![/bold green]")

        # Subscribe immediately
        notify_uuids = [
            "0000ffd2-0000-1000-8000-00805f9b34fb",
            "0000ffd3-0000-1000-8000-00805f9b34fb",
            "00002a19-0000-1000-8000-00805f9b34fb",
        ]
        for uuid in notify_uuids:
            try:
                await client.start_notify(uuid, on_notify)
                console.print(f"  [green]subscribed[/green] {uuid}")
            except Exception as e:
                console.print(f"  [dim]skip {uuid}: {e}[/dim]")

        try:
            batt = await client.read_gatt_char("00002a19-0000-1000-8000-00805f9b34fb")
            console.print(f"[green]Battery: {batt[0]}%[/green]")
        except Exception:
            pass

        console.print("[dim]Listening 15s…[/dim]")
        await asyncio.sleep(15)
        console.print(f"Received {len(received)} notifications")


if __name__ == "__main__":
    asyncio.run(main())
