"""Async BLE scanner that finds the FW920 recorder."""

from bleak import BleakScanner
from bleak.backends.device import BLEDevice


async def scan(timeout: float = 10.0) -> BLEDevice | None:
    return await BleakScanner.find_device_by_filter(
        lambda d, _ad: d.name is not None and "FW920" in d.name,
        timeout=timeout,
    )
