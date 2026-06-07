# FW920 Wi-Fi OTA Discovery — Session Handoff

**Last updated**: 2026-05-23  
**Goal**: Trigger FW920 into OTA mode via BLE, capture the Wi-Fi hotspot SSID, then implement Wi-Fi file transfer in Daedalus Voicenotes (~10 MB/s vs BLE's ~10 KB/s).

---

## Hardware

| Device | Detail |
|---|---|
| Recorder | ELVANZA FW920 (rebadged HUXGO FW920) |
| Chip | ESP32-C3 (BLE + Wi-Fi native) + ATS2837 audio DSP |
| BLE protocol | "B0B0" custom service (see below) |
| MAC address | redacted — run `adb logcat -s R2.a` while DOWAY connects to find it |
| Firmware | v1.0.3 (shown in DOWAY app) |

---

## BLE Protocol (B0B0 Service)

**Service UUID**: `B0B0B0B0-B0B0-B0B0-B0B0-B0B0B0B0B0B0` (or similar — check `FW920Protocol.kt`)

**Packet format** (write to control characteristic):
```
A0 0A 01 [CMD] [LEN] [PAYLOAD...] [CRC16-ARC lo] [CRC16-ARC hi]
```

**Known commands**:
| CMD | Description | Response |
|---|---|---|
| 0x05 | Get device info (fw version, storage, name) | 30-byte payload |
| 0x06 | Start recording | RecordingStarted (device gives no ACK — optimistic update) |
| 0x08 | Stop recording | Ack |
| 0x07 | Confirm done | Ack |
| 0x0F | Periodic status notification | isRecording state |
| 0x19 | File list request | File entries |
| 0x1A | Only undocumented cmd that responds | Response: `[00]` |
| 0x1C | Download file chunk | File data |

**CMD 0x05 response layout** (bytes 0-29):
- Bytes 0-3: totalBytes (U32 LE)
- Bytes 4-7: freeBytes (U32 LE)
- Bytes 14-30: fwName (ASCII, filter 0x20–0x7E, rest is garbage)

---

## DOWAY APK Findings

**APK location in this repo**: `reverse/doway.apk`, `reverse/doway.xapk`  
**Architecture**: Flutter (Dart AOT) + React Native BLE bridge (`R2.a` in logcat)  
**Protocol identifier**: `xlx_2837` (used in firmware API calls)

### OTA BLE Service (only appears AFTER device enters OTA mode)
```
Service:  E606E15D-DA3D-6F45-5978-A7A5B8CEA2C0
Write:    E606E15E-DA3D-6F45-5978-A7A5B8CEA2C0
Notify:   E606E15F-DA3D-6F45-5978-A7A5B8CEA2C0
```
These UUIDs are **absent** from normal GATT scan. They only appear after a specific BLE trigger command is sent.

### Wi-Fi OTA Flow (reconstructed from `libapp.so` strings)
1. Phone sends BLE trigger command → FW920 reboots into OTA mode
2. FW920 starts Wi-Fi hotspot (SSID displayed in DOWAY as "Connect to %s's hotspot")
3. E606 OTA service becomes visible in a new BLE scan
4. Phone connects to FW920 hotspot
5. Phone transfers firmware via HTTP to device
6. Device flashes and reboots

Relevant strings from `libapp_strings.txt`:
```
doway_my_wifi_turn_on_hotspot
Connect to %s's hotspot
showWifiSheetDialog
package:doway/dowayProtocol/bluetooth/xlx_link/ota/esp32c3/esp32c3_wifi_ota_mgr.dart
```

### Firmware Upgrade API
- **Endpoint**: `http://www.dowayai.com:8188/api/player/firmware_upgrade`
- **Method**: HTTP POST (plain HTTP, not HTTPS — easy to MITM)
- **Known fields** (from partial logcat capture):
  ```json
  {
    "sn": "...",
    "hardwareVersion": "xlx_2837",
    "deviceType": "...",
    "version": "1.0.3"
  }
  ```
- `hardwareVersion: "xlx_2837"` → server returns 404 (device type known, no firmware for it)
- All other hardwareVersion values → HTTP 500 with "硬件版本" (hardware version error)
- **"Check for updates" in DOWAY sends NO BLE command** — it's a pure HTTP call

### DOWAY BLE Behavior on Connect (from logcat)
```
R2.a: on native side observed method: writeCharacteristicForIdentifier  (×4 times)
R2.a: on native side observed method: requestMtu (MTU 512)
```
DOWAY requests MTU 512 on connect; our app does not — could affect transfer speed.

---

## What We Know vs. What We Need

| Known | Unknown |
|---|---|
| OTA service UUIDs (E606...) | **The BLE trigger command byte(s)** that puts device into OTA mode |
| Device creates Wi-Fi hotspot | The hotspot SSID/password |
| API endpoint + some fields | Exact JSON fields to get a "new firmware" response |
| OTA uses HTTP file transfer | HTTP port + path on device's hotspot |

---

## MITM Proxy Plan (interrupted, resume here)

### Goal
Intercept DOWAY's firmware API call to determine:
1. All request fields (especially `sn`, `deviceType`, full payload)
2. What a "new firmware available" response looks like
3. Craft a fake response → watch DOWAY send the BLE OTA trigger → capture that command

### Setup

**Step 1**: Start the intercept proxy on the laptop:
```bash
python3 /tmp/intercept_proxy.py          # passthrough — captures request fields
# OR
python3 /tmp/intercept_proxy.py --fake   # serves fake "new firmware" response
```

Proxy script is at `/tmp/intercept_proxy.py` on the old laptop. It's also been recreated here for the new computer — see `reverse/intercept_proxy.py`.

**Step 2**: Set phone Wi-Fi proxy (use new laptop's LAN IP):
```bash
adb shell settings put global http_proxy <LAPTOP_IP>:8080
```

**Step 3**: In DOWAY app → Settings → Firmware Version → tap "Check for updates"

**Step 4**: Watch proxy output for full JSON body

**Step 5**: Clear proxy when done:
```bash
adb shell settings put global http_proxy :0
```

### Phase 2 — Fake Response
Once you have all request fields, switch to `--fake` mode. DOWAY should:
1. Show "new firmware available" dialog → user taps "Update"
2. Send BLE command to FW920 (captured in logcat: `R2.a: writeCharacteristicForIdentifier`)
3. FW920 starts hotspot → logcat shows SSID

Watch logcat while doing this:
```bash
adb logcat -s R2.a BleManager:* | grep -i "write\|hotspot\|wifi\|ota"
```

### Phase 3 — Capture OTA Trigger Command
Once DOWAY sends the BLE trigger, we need the raw bytes. Enable HCI snoop:
```bash
adb shell settings put secure bluetooth_hci_log 1
# Then trigger OTA via fake response
# Then pull the snoop log:
adb pull /sdcard/Android/data/com.android.bluetooth/files/btsnooplog.log /tmp/
# Or find it at:
adb shell find /data/misc/bluetooth -name "*.log" 2>/dev/null
```

---

## Probe Results (completed in prior session)

### Normal GATT Services (BLE scan of FW920 in normal mode)
- `B0B0...` — main control service (start/stop recording, file list, etc.)
- `1800`, `1801` — GAP/GATT standard services
- `FFD0`, `C0C0`, `E49A` — probed, no useful response to our protocol or AT commands

### CMD Probe Results (0x19–0x50)
Only `0x1A` responded with `[00]`. All others timed out or got no response.

### NOT Present in Normal Mode
- `E606...` OTA service — absent; appears only in OTA mode

---

## Daedalus Voicenotes App Status

**Branch**: `main`  
**Last commit**: `5004c60 ux: remove BLE per-file sync button from recordings list`

**Working**:
- BLE connect/disconnect to FW920
- Start/Stop recording (with optimistic UI update)
- Device info (firmware version, storage)
- File list via BLE
- USB auto-sync (copies MP3s from recorder's RECORD/ folder)
- Audio playback
- Gemma 1.1 2B CPU local AI (transcription via MediaPipe, summarization, mind map)
- Room DB persistence

**Gemma model**:
- Working: `gemma-1.1-2b-it-cpu-int4.bin` (~1.35 GB) — CPU inference, ~20s on S24 Ultra
- Broken: GPU int4 model fails with `libvndksupport.so not found` (Samsung sandbox blocks OpenCL)
- Model stored at: `context.filesDir/models/` on the phone

**Known UX gaps** (not bugs, just missing features):
- Recordings list shows BLE file list, not local DB list — means synced files don't appear until BLE connected
- Whisper transcription not in Android (must inject transcript manually for now)
- NoteDetailScreen header shows raw filename instead of formatted date
- Export (PDF, DOCX) not implemented in Android

---

## ADB Quick Reference

```bash
# Check device
adb devices

# Install APK
adb install -r android/app/build/outputs/apk/debug/app-debug.apk

# Trigger sync (USB OTG)
adb shell am broadcast -a com.daedalus.voicenotes.SYNC -p com.daedalus.voicenotes

# Trigger BLE probe (logs all GATT services + probes CMD bytes)
adb shell am broadcast -a com.daedalus.voicenotes.PROBE -p com.daedalus.voicenotes

# Trigger BLE service probe (FFD0/C0C0/E49A)
adb shell am broadcast -a com.daedalus.voicenotes.PROBE2 -p com.daedalus.voicenotes

# Watch relevant logs
adb logcat -s DaedalusBLE DaedalusSync DaedalusAI DaedalusADB FW920_PROBE R2.a

# Build APK
cd android && ./gradlew assembleDebug
```

---

## Files in This Repo

| Path | Description |
|---|---|
| `reverse/doway.apk` | DOWAY Android app (Flutter + RN BLE bridge) |
| `reverse/libapp_strings.txt` | String dump of `libapp.so` — all OTA/Wi-Fi strings are here |
| `reverse/analyze_doway.py` | Script used to extract strings/UUIDs from APK |
| `reverse/intercept_proxy.py` | MITM proxy for firmware upgrade API (see MITM section above) |
| `android/app/src/main/java/com/daedalus/voicenotes/ble/BleManager.kt` | BLE protocol impl + probe methods |
| `android/app/src/main/java/com/daedalus/voicenotes/ble/FW920Protocol.kt` | Packet parsing (CMD 0x05 layout) |

---

## Next Session — Resume Steps

1. `cd` into the repo root
2. `git log --oneline -5` — verify you're on latest main
3. Read this file
4. Ensure phone ADB connected: `adb devices`
5. Get laptop LAN IP: `ip route get 1 | awk '{print $7; exit}'`
6. Start proxy: `python3 reverse/intercept_proxy.py`
7. Set phone proxy: `adb shell settings put global http_proxy <LAPTOP_IP>:8080`
8. In DOWAY: Settings → Firmware Version → Check for updates
9. Watch proxy output — capture full request JSON
10. Then switch to `--fake` mode and watch for OTA trigger in logcat
