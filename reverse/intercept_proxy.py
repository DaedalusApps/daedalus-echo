#!/usr/bin/env python3
"""
MITM proxy for DOWAY firmware upgrade API.

Usage:
  python3 intercept_proxy.py           # passthrough — logs all request fields
  python3 intercept_proxy.py --fake    # serves fake "new firmware" response

Setup:
  1. Run this script on the laptop
  2. adb shell settings put global http_proxy <LAPTOP_IP>:8080
  3. In DOWAY: Settings → Firmware Version → Check for updates
  4. Clear when done: adb shell settings put global http_proxy :0
"""

import socket
import threading
import sys
import json
from datetime import datetime

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 8080
TARGET_HOST = "www.dowayai.com"
TARGET_PORT = 8188
FAKE_MODE = "--fake" in sys.argv

# Detect this machine's LAN IP automatically (used in fake firmware URL)
def _get_local_ip() -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()

LAPTOP_IP = _get_local_ip()

def log(msg):
    ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
    print(f"[{ts}] {msg}", flush=True)

def make_fake_response(request_body: bytes) -> bytes:
    try:
        req = json.loads(request_body)
    except Exception:
        req = {}
    log(f"  [FAKE] Crafting response for: {req}")
    fake = {
        "code": 0,
        "msg": "success",
        "data": {
            "version": "1.0.4",
            "url": f"http://{LAPTOP_IP}:{LISTEN_PORT}/firmware/fw920_1.0.4.bin",
            "description": "Bug fixes",
            "forceUpdate": False,
            "fileSize": 1048576
        }
    }
    body = json.dumps(fake).encode()
    return (
        b"HTTP/1.1 200 OK\r\n"
        b"Content-Type: application/json\r\n"
        b"Connection: close\r\n"
        + f"Content-Length: {len(body)}\r\n".encode()
        + b"\r\n" + body
    )

def serve_dummy_firmware(conn):
    size = 1048576
    conn.sendall(
        b"HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n"
        + f"Content-Length: {size}\r\n".encode() + b"\r\n"
    )
    chunk = b"\x00" * 4096
    sent = 0
    while sent < size:
        conn.sendall(chunk)
        sent += len(chunk)

def forward_to_target(raw_request: bytes) -> bytes:
    try:
        s = socket.create_connection((TARGET_HOST, TARGET_PORT), timeout=10)
        s.sendall(raw_request)
        response = b""
        while True:
            chunk = s.recv(4096)
            if not chunk:
                break
            response += chunk
        s.close()
        return response
    except Exception as e:
        log(f"  [FORWARD ERROR] {e}")
        return b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n"

def handle_client(conn, addr):
    try:
        data = b""
        conn.settimeout(5)
        while True:
            try:
                chunk = conn.recv(4096)
                if not chunk:
                    break
                data += chunk
                if b"\r\n\r\n" in data:
                    header_end = data.index(b"\r\n\r\n") + 4
                    headers_raw = data[:header_end].decode(errors="replace")
                    body_so_far = data[header_end:]
                    content_length = 0
                    for line in headers_raw.split("\r\n"):
                        if line.lower().startswith("content-length:"):
                            content_length = int(line.split(":")[1].strip())
                    while len(body_so_far) < content_length:
                        chunk = conn.recv(4096)
                        if not chunk:
                            break
                        body_so_far += chunk
                    break
            except socket.timeout:
                break

        if not data:
            return

        first_line = data.split(b"\r\n")[0].decode(errors="replace")
        log(f"[REQUEST] {addr[0]} -> {first_line}")

        if b"\r\n\r\n" in data:
            body = data[data.index(b"\r\n\r\n") + 4:]
            if body:
                try:
                    log(f"  [BODY JSON] {json.dumps(json.loads(body), ensure_ascii=False)}")
                except Exception:
                    log(f"  [BODY RAW] {body[:500]!r}")

        if b"GET /firmware/" in data:
            log("  [SERVING] Dummy firmware binary")
            serve_dummy_firmware(conn)
            return

        if b"firmware_upgrade" in data or b"dowayai.com" in data:
            if FAKE_MODE:
                log("  [FAKE MODE] Serving fake 'new firmware' response")
                body_bytes = data[data.index(b"\r\n\r\n") + 4:] if b"\r\n\r\n" in data else b""
                conn.sendall(make_fake_response(body_bytes))
            else:
                log("  [PASSTHROUGH] Forwarding to real server")
                response = forward_to_target(data)
                if b"\r\n\r\n" in response:
                    resp_body = response[response.index(b"\r\n\r\n") + 4:]
                    try:
                        log(f"  [RESPONSE JSON] {json.dumps(json.loads(resp_body), ensure_ascii=False)}")
                    except Exception:
                        log(f"  [RESPONSE RAW] {resp_body[:500]!r}")
                conn.sendall(response)
        else:
            conn.sendall(forward_to_target(data))

    except Exception as e:
        log(f"  [ERROR] {e}")
    finally:
        try:
            conn.close()
        except Exception:
            pass

def main():
    mode = "FAKE" if FAKE_MODE else "PASSTHROUGH"
    log(f"DOWAY MITM proxy on {LISTEN_HOST}:{LISTEN_PORT} [{mode} mode]")
    log(f"Target: {TARGET_HOST}:{TARGET_PORT}")
    log("-" * 60)

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((LISTEN_HOST, LISTEN_PORT))
    server.listen(10)
    log("Listening...")

    try:
        while True:
            conn, addr = server.accept()
            threading.Thread(target=handle_client, args=(conn, addr), daemon=True).start()
    except KeyboardInterrupt:
        log("Stopped.")
    finally:
        server.close()

if __name__ == "__main__":
    main()
