# USB → Linux (Termux / proot / Grok)

Device Bridge runs on **Android**. Grok and your tools run in **Termux / Ubuntu (proot)**.  
USB is owned by Android; this doc shows how to get **files** and **device streams** into that Linux environment.

Base URL (bridge running): `http://127.0.0.1:8765`

---

## Two USB cases

| Plug in… | Goal in Linux | How |
|----------|---------------|-----|
| **Flash drive / SSD** | Read/write files | Android mounts volume → bind path into proot |
| **Serial gadget** (Arduino, GPS dongle, CDC) | Live bytes | Device Bridge serial API → WebSocket/HTTP |

---

## A) USB mass storage (files)

### 1. Plug OTG drive

Use a USB-C OTG adapter. Unlock the phone; accept any “open with Files?” prompts.

### 2. Discover mount path

With **Device Bridge** started:

```bash
curl -s http://127.0.0.1:8765/v1/usb/storage | jq .
```

Look for removable, non-primary volumes, e.g.:

```json
{
  "path": "/storage/ABCD-1234",
  "is_removable": true,
  "is_primary": false,
  "proot_hint": "Bind in Termux proot: --bind /storage/ABCD-1234:/mnt/usb"
}
```

Also try on Android/Termux:

```bash
ls /storage
```

### 3. Bind into Ubuntu (proot-distro)

**Option 1 — login with bind (recommended each session):**

```bash
# In Termux (not inside Ubuntu yet):
proot-distro login ubuntu --bind /storage/ABCD-1234:/mnt/usb
```

Then inside Ubuntu:

```bash
ls -la /mnt/usb
cat /mnt/usb/some-file.txt
```

**Option 2 — copy into home (no bind):**

```bash
# Termux
cp -a /storage/ABCD-1234/myproject ~/uploads/
proot-distro login ubuntu
# files under the Termux home bind are often visible as /data/data/com.termux/files/home
```

Exact home bind paths depend on how you start proot; **`--bind /storage/UUID:/mnt/usb`** is the clearest.

### 4. Use from Grok

Once files are under `/mnt/usb` (or copied into the workspace), Grok can `read_file` / shell as usual — no special USB API needed for **files**.

---

## B) USB serial / custom gadgets (live data)

Mass storage is **not** serial. For Arduino-like devices:

### 1. List devices

```bash
curl -s http://127.0.0.1:8765/v1/usb/devices | jq .
```

Note `device_id` (Android id, e.g. `"1002"`).

### 2. Grant USB permission (on the phone UI)

```bash
curl -s -X POST "http://127.0.0.1:8765/v1/usb/devices/1002/permission"
```

Accept the system dialog on the phone.

### 3. Open serial + stream into Linux

```bash
curl -s -X POST "http://127.0.0.1:8765/v1/usb/devices/1002/serial/open?baud=115200" | jq .

# Live data (Termux or Ubuntu with websocat/python)
websocat "ws://127.0.0.1:8765/v1/usb/serial/1002"
```

Write:

```bash
curl -s -X POST "http://127.0.0.1:8765/v1/usb/devices/1002/serial/write" \
  -H 'Content-Type: text/plain' \
  --data 'hello\n'
```

Python (Ubuntu):

```python
import json, asyncio, websockets

async def main():
    uri = "ws://127.0.0.1:8765/v1/usb/serial/1002"
    async with websockets.connect(uri) as ws:
        async for msg in ws:
            print(json.loads(msg))

asyncio.run(main())
```

### Limits

- Works best with **CDC-ACM** / bulk endpoints.
- Some chips (older FTDI/CP210x) need vendor drivers; if open fails, the JSON error will say so.
- proot will **not** get `/dev/ttyUSB0` — use the WebSocket bridge.

---

## C) Attach / detach events

```bash
websocat 'ws://127.0.0.1:8765/v1/stream/usb'
# or
websocat 'ws://127.0.0.1:8765/v1/stream?topics=usb,usb_event'
```

---

## Snapshot includes USB

```bash
curl -s http://127.0.0.1:8765/v1/snapshot | jq .usb
```

---

## Checklist

- [ ] OTG cable / hub works on this phone  
- [ ] Device Bridge **Start bridge** + notification  
- [ ] Storage: `GET /v1/usb/storage` shows path → proot `--bind`  
- [ ] Serial: permission dialog → `serial/open` → WebSocket  

That’s the intended path: **Android owns USB → Linux/Grok consumes files (mount) or streams (localhost API).**
