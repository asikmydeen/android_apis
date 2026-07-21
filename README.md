# Device Bridge (`android_apis`)

Native **Kotlin** Android app that discovers device sensors, location, battery, network, telephony, and cameras — then serves them over a **local REST + WebSocket API** for Termux, Ubuntu (proot), scripts, and automation.

Default base URL: **`http://127.0.0.1:8765`**

Full design: [PLAN.md](./PLAN.md)

---

## Features (implemented)

| Area | Status |
|------|--------|
| Foreground service + notification | Yes |
| `GET /v1/health` | Yes |
| `GET /v1/capabilities` (sensors, cameras, permissions, device) | Yes |
| `GET /v1/snapshot` | Yes |
| `GET /v1/location` + fused/GPS location collector | Yes |
| `GET /v1/battery`, `/v1/network`, `/v1/telephony`, `/v1/sensors` | Yes |
| `WS /v1/stream` multiplex (location, battery, sensors, network, …) | Yes |
| `WS /v1/stream/location`, `/v1/stream/sensors` | Yes |
| Camera still capture `POST /v1/camera/{id}/capture` + `GET /v1/camera/last.jpg` | Yes |
| **USB host**: list devices, storage volumes, attach events | Yes (v1.1) |
| **USB serial** stream into Termux/Ubuntu via WebSocket | Yes (v1.1, CDC/bulk) |
| **Diagnostics / stale location / USB reconnect** | Yes (v1.2) |
| Boot start + battery unrestricted hint | Yes (v1.2) |
| Termux `scripts/bridge` helper (retry + cache) | Yes (v1.2) |
| **UI: Home / Remote / Devices / Settings** | Yes (v1.3) |
| **Remote: Local · LAN · Tailscale · Cloudflare + token auth** | Yes (v1.3) |
| Bluetooth scan / GATT | Planned |

---

## Build & install

### Requirements

- Android Studio **Ladybug+** (or SDK 35 + JDK 17)
- Device or emulator (this project targets **minSdk 26**, **targetSdk 35**)

### Open & run

1. Clone:
   ```bash
   git clone https://github.com/asikmydeen/android_apis.git
   cd android_apis
   ```
2. Open the folder in **Android Studio**.
3. Let Gradle sync.
4. Run on your phone (USB debugging) or:
   ```bash
   ./gradlew :app:installDebug
   ```

### First run on phone

1. Open **Device Bridge**.
2. Tap **Grant permissions** (location, camera, sensors, notifications).
3. Samsung: set app battery usage to **Unrestricted**.
4. Tap **Start bridge** — keep the notification visible.
5. From Termux / Ubuntu on the same device:

```bash
curl -s http://127.0.0.1:8765/v1/health | jq .
curl -s http://127.0.0.1:8765/v1/capabilities | jq .
curl -s http://127.0.0.1:8765/v1/location | jq .
curl -s http://127.0.0.1:8765/v1/snapshot | jq .
```

---

## API quick reference

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/health` | Liveness + uptime |
| GET | `/v1/config` | Bind/port/version |
| GET | `/v1/capabilities` | Full inventory |
| GET | `/v1/snapshot` | One-shot readings |
| GET | `/v1/location` | Latest GPS/network fix |
| GET | `/v1/battery` | Battery |
| GET | `/v1/network` | Connectivity / Wi‑Fi |
| GET | `/v1/telephony` | Safe telephony fields |
| GET | `/v1/sensors` | Latest sensor map |
| GET | `/v1/sensors/{type}` | One sensor (name or type int) |
| GET | `/v1/cameras` | Camera list |
| POST | `/v1/camera/{id}/capture?base64=1` | Take JPEG |
| GET | `/v1/camera/last.jpg` | Last JPEG bytes |
| GET | `/v1/usb` | USB overview (devices + storage volumes) |
| GET | `/v1/usb/devices` | Connected USB gadgets |
| GET | `/v1/usb/storage` | Mounted volumes (OTG paths for proot bind) |
| POST | `/v1/usb/devices/{id}/permission` | System USB permission dialog |
| POST | `/v1/usb/devices/{id}/serial/open?baud=115200` | Open bulk/CDC serial |
| POST | `/v1/usb/devices/{id}/serial/write` | Write to serial |
| POST | `/v1/usb/devices/{id}/serial/close` | Close serial |
| WS | `/v1/usb/serial/{id}` | Live serial bytes (base64 + text) |
| WS | `/v1/stream/usb` | USB attach/detach + overview |
| WS | `/v1/stream?topics=location,battery,sensors,network,usb` | Multiplex stream |
| WS | `/v1/stream/location` | Location only |
| WS | `/v1/stream/sensors` | Sensors only |

### Remote access (internet)

See **[docs/REMOTE_ACCESS.md](./docs/REMOTE_ACCESS.md)**.

| Mode | How |
|------|-----|
| **Tailscale** | Install Tailscale app → Remote tab → mode Tailscale → use `http://100.x.x.x:8765` + Bearer token |
| **Cloudflare** | Mode Cloudflare → Termux `./scripts/cloudflared-quick.sh` → paste URL in app → curl with token |
| **LAN** | Mode LAN → `http://192.168.x.x:8765` + token |

```bash
export BRIDGE=https://xxxx.trycloudflare.com
export BRIDGE_TOKEN=your_token_from_app
./scripts/bridge snapshot
```

### Failure / diagnostics

```bash
curl -s http://127.0.0.1:8765/v1/diagnostics | jq .
curl -s http://127.0.0.1:8765/v1/debug/log | jq .
# location always returns last known when available; check .stale and .age_sec
curl -s http://127.0.0.1:8765/v1/location | jq .
```

Termux/Ubuntu helper (retries + caches last good snapshot):

```bash
chmod +x scripts/bridge
./scripts/bridge health
./scripts/bridge diag
./scripts/bridge location
./scripts/bridge snapshot   # falls back to ~/.cache/device-bridge/last-snapshot.json
```

### USB → Linux (files + serial)

See **[docs/USB_LINUX.md](./docs/USB_LINUX.md)** for binding flash drives into proot and streaming serial gadgets to Grok/Termux.

```bash
# Flash drive path for proot --bind
curl -s http://127.0.0.1:8765/v1/usb/storage | jq .

# Serial gadget
curl -s http://127.0.0.1:8765/v1/usb/devices | jq .
curl -s -X POST "http://127.0.0.1:8765/v1/usb/devices/ID/permission"
curl -s -X POST "http://127.0.0.1:8765/v1/usb/devices/ID/serial/open?baud=115200"
websocat "ws://127.0.0.1:8765/v1/usb/serial/ID"
```

### Capture example

```bash
# list cameras
curl -s http://127.0.0.1:8765/v1/cameras | jq .

# capture camera 0 (usually back)
curl -s -X POST 'http://127.0.0.1:8765/v1/camera/0/capture' | jq .

# download last image
curl -s http://127.0.0.1:8765/v1/camera/last.jpg -o shot.jpg
```

### WebSocket example

```bash
# if you have websocat
websocat 'ws://127.0.0.1:8765/v1/stream?topics=location,battery'
```

---

## Project layout

```
app/src/main/java/dev/asik/devicebridge/
  BridgeRuntime.kt          # process-wide hub + collectors
  MainActivity.kt / ui/     # Compose control UI
  service/BridgeForegroundService.kt
  server/BridgeServer.kt    # embedded Ktor (CIO)
  collectors/               # battery, sensors, location, network, telephony, camera
  hub/StreamHub.kt
  model/Models.kt
```

Application id: `dev.asik.devicebridge`

---

## Security notes

- Server binds to **`127.0.0.1` only** (on-device clients).
- Camera/location require explicit user grants.
- A persistent **foreground notification** is shown while the API runs.
- Do not change bind to `0.0.0.0` without adding auth (planned).

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `connection refused` | Start the bridge in the app; check notification |
| location `no_fix` | Grant precise location; go outdoors; wait a few seconds |
| camera capture fails | Grant camera; close other camera apps |
| service killed | Battery → Unrestricted for Device Bridge |
| curl from Ubuntu proot fails | Use `127.0.0.1` (not `localhost` if IPv6 issues); ensure bridge is running |

---

## License

Personal / developer tooling. Add a license file if you publish broadly.
