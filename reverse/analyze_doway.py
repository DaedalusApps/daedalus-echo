"""Static analysis of the DOWAY APK to extract BLE protocol details.

Looks for:
- GATT service / characteristic UUIDs (16-bit and 128-bit forms)
- Bluetooth API calls (writeCharacteristic, setCharacteristicNotification, etc.)
- Activation / authentication / handshake methods
- Magic byte sequences sent at connection time
"""
import re
import sys
from collections import defaultdict
from pathlib import Path

from androguard.misc import AnalyzeAPK

APK_PATH = Path(__file__).parent / "doway.apk"

# UUID patterns
RE_UUID_128 = re.compile(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
RE_UUID_16 = re.compile(r"\b(?:0x)?([0-9a-fA-F]{4})\b")

# Hex byte sequence patterns (0xAA, 0x55, etc.)
RE_HEX_BYTE = re.compile(r"0x[0-9a-fA-F]{2}")

BLE_CLASS_HINTS = (
    "BluetoothGatt", "GattCharacteristic", "BluetoothLeScanner", "BleManager",
    "BluetoothDevice", "BleDevice", "BleService", "BleClient",
)

INTERESTING_METHOD_HINTS = (
    "activate", "auth", "handshake", "pair", "register", "init", "login",
    "session", "verify", "connect", "subscribe", "startRecord", "stopRecord",
    "writeCommand", "sendCommand", "sendCmd",
)


def extract_uuids(strings: list[str]) -> dict:
    """Find all 128-bit UUIDs in the strings table."""
    uuids = set()
    for s in strings:
        for m in RE_UUID_128.findall(s):
            uuids.add(m.lower())
    return sorted(uuids)


def find_ble_strings(strings: list[str]) -> list[str]:
    """Find strings that look BLE-related."""
    keywords = ["uuid", "ffe0", "ffe1", "ffe2", "fff0", "fff1", "service", "characteristic",
                "gatt", "ble", "bluetooth", "0x"]
    matches = []
    for s in strings:
        s_lower = s.lower()
        if any(k in s_lower for k in keywords) and 4 < len(s) < 200:
            matches.append(s)
    return matches


def find_ble_classes(dx) -> list:
    """Find classes that reference Bluetooth APIs."""
    hits = []
    for klass in dx.get_classes():
        klass_name = str(klass.name)
        for hint in BLE_CLASS_HINTS:
            if hint in klass_name:
                hits.append(klass_name)
                break
    return sorted(set(hits))


def find_ble_methods(dx) -> dict:
    """Find methods that look like activation / commands / handshake."""
    hits = defaultdict(list)
    for method in dx.get_methods():
        m_name = method.name.lower()
        c_name = str(method.class_name)
        for hint in INTERESTING_METHOD_HINTS:
            if hint in m_name:
                hits[hint].append(f"{c_name}->{method.name}")
                break
    return dict(hits)


def find_byte_arrays_near_ble(dx, strings: list[str]) -> list[str]:
    """Look for small byte literals like 0x01, 0x02 near BLE method calls."""
    candidates = []
    for s in strings:
        if RE_HEX_BYTE.search(s) and len(s) < 100:
            candidates.append(s)
    return candidates[:50]


def main():
    if not APK_PATH.exists():
        print(f"APK not found at {APK_PATH}")
        print("Download DOWAY from apkmirror.com or apkpure.com and save it here.")
        sys.exit(1)

    print(f"Analyzing {APK_PATH}…")
    a, d_list, dx = AnalyzeAPK(str(APK_PATH))

    print("\n=== APK Info ===")
    print(f"Package: {a.get_package()}")
    print(f"Version: {a.get_androidversion_name()} ({a.get_androidversion_code()})")
    print(f"Permissions: {[p for p in a.get_permissions() if 'BLUETOOTH' in p or 'LOCATION' in p]}")

    # Collect all string constants from all DEXes
    print("\n=== Collecting strings… ===")
    all_strings: list[str] = []
    for d in d_list:
        all_strings.extend(str(s) for s in d.get_strings())
    print(f"Total strings: {len(all_strings)}")

    print("\n=== 128-bit UUIDs found ===")
    uuids = extract_uuids(all_strings)
    for u in uuids:
        # Filter out common system UUIDs
        if u.startswith("00000000") or u == "00000000-0000-0000-0000-000000000000":
            continue
        print(f"  {u}")

    print("\n=== BLE-flavored strings (sample) ===")
    ble_strings = find_ble_strings(all_strings)
    for s in ble_strings[:60]:
        print(f"  {s!r}")

    print("\n=== Bluetooth-related classes ===")
    ble_classes = find_ble_classes(dx)
    for c in ble_classes:
        print(f"  {c}")

    print("\n=== Interesting methods (activation / commands / handshake) ===")
    methods = find_ble_methods(dx)
    for kw, methods_list in methods.items():
        print(f"\n  [{kw}]")
        for m in methods_list[:10]:
            print(f"    {m}")
        if len(methods_list) > 10:
            print(f"    ... +{len(methods_list) - 10} more")

    # Save full output to a file for detailed review
    output = {
        "package": a.get_package(),
        "uuids": uuids,
        "ble_strings": ble_strings,
        "ble_classes": ble_classes,
        "ble_methods": {k: v for k, v in methods.items()},
    }
    import json
    out_path = APK_PATH.parent / "doway_analysis.json"
    out_path.write_text(json.dumps(output, indent=2))
    print(f"\n\nFull analysis saved to: {out_path}")


if __name__ == "__main__":
    main()
