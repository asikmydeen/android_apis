# Plan: Device Bridge — Native Android Sensor & Media API App

## Goal

Build a **native Android app** that:

1. **Discovers** all available device capabilities (sensors, cameras, location providers, battery, network, etc.).
2. **Reads** whatever the OS allows at runtime (with proper permissions).
3. **Streams / serves** that data over **local realtime APIs** so Termux, Ubuntu (proot), scripts, or Grok can consume it via HTTP/WebSocket.

This replaces fragile Termux:API usage for your Samsung (SM-F966U1, Android 16) setup.

---

## Language & stack (native “most perfect” choice)

| Layer | Choice | Why |
|--------|--------|-----|
| Language | **Kotlin** | Official first-class language for Android; null-safety, coroutines, idiomatic APIs |
| UI | **Jetpack Compose** | Modern native UI toolkit |
| Async | **Kotlin Coroutines + Flow** | Native realtime streams |
| Local API server | **Ktor (CIO engine)** embedded in-process | Pure Kotlin, WebSocket + REST, runs inside the app process |
| DI | **Hilt** | Standard Android DI |
| Serialization | **kotlinx.serialization** | Typed JSON for API payloads |
| Camera | **CameraX** | Official camera library (preview, photo, limited video) |
| Location | **Google Play services Fused Location** (with **LocationManager** fallback) | Best accuracy on OEM devices; fallback if GMS quirks |
| Sensors | **SensorManager** | All hardware sensors |
| Build | **Gradle Kotlin DSL** + **AGP 8.x** | Standard native toolchain |
| Min/Target SDK | **minSdk 26**, **targetSdk 35/36** | Covers your Android 16 device; still installs on older phones |

**Not chosen:** Flutter/React Native (not native), Java-only (legacy), NanoHTTPD alone (weak WebSocket/story), root/magisk modules (out of scope).

**Project name (suggested):** `DeviceBridge`  
**Application ID:** `dev.asik.devicebridge` (change as you like)  
**Repo path (suggested):** `/home/asik/projects/device-bridge`

---

## Product principles

1. **Local-first:** Bind to `127.0.0.1` by default so only apps/shells on the phone can connect.
2. **Capability-first:** Always expose an inventory (`GET /v1/capabilities`) of what exists and what is permitted *right now*.
3. **Graceful degradation:** Missing sensor/permission → reported in JSON, not a crash.
4. **Foreground service** for continuous streaming (Android 8+ / 14+ / 16 requirements).
5. **User control:** On-device toggle for server, token auth, which streams are enabled, camera on/off.
6. **Honest limits:** Document what third-party apps **cannot** do without root/system privileges.

---

## What we can and cannot access

### Available to a normal Play/F-Droid-style app (with permissions)

| Domain | APIs | Notes |
|--------|------|--------|
| Motion / environment sensors | `SensorManager` | Accel, gyro, mag, light, prox, pressure, humidity, step, rotation vectors, etc. |
| Location | Fused / GPS / network / passive | Needs runtime location (+ background if continuous) |
| Battery | `BatteryManager`, sticky `ACTION_BATTERY_CHANGED` | Usually no special permission |
| Network | `ConnectivityManager`, Wi‑Fi info | Some fields need `ACCESS_WIFI_STATE` / location on modern Android |
| Telephony (limited) | `TelephonyManager` | Network type, operator; phone number/IMEI heavily restricted |
| Cameras | CameraX / Camera2 | List cameras, photo capture, preview frames; continuous video needs care |
| Audio | `AudioRecord` (mic) | Separate permission; privacy-sensitive |
| Display / device | `Build`, `WindowManager`, `DisplayManager` | Model, abis, density, refresh rate |
| Storage (scoped) | MediaStore / SAF | Optional; not full disk |
| Activity recognition | Play services AR API | Optional permission |
| Barometer / GNSS extras | Sensors + `GnssStatus` | Satellite count when GPS active |

### Not available without root / system / special OEM APIs

- Arbitrary other apps’ private data  
- Full call recording on many OEMs  
- Raw modem dumps, full radio logs  
- Disabling system security / reading other UIDs  
- Silent camera without user-visible indicator (policy + OS may force indicators)  
- True “all sensors always” while app is killed (OS will stop you)

The app must report **unavailable / denied** clearly rather than pretending full device ownership.

---

## High-level architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     DeviceBridge App                        │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────────────┐  │
│  │ Compose UI  │  │ Permissions  │  │ Settings Store    │  │
│  │ (status,    │  │ Coordinator  │  │ (DataStore)       │  │
│  │  toggles)   │  └──────────────┘  └───────────────────┘  │
│  └──────┬──────┘                                            │
│         │                                                   │
│  ┌──────▼───────────────────────────────────────────────┐  │
│  │           BridgeForegroundService                     │  │
│  │  - starts embedded Ktor server                        │  │
│  │  - owns collectors lifecycle                          │  │
│  │  - sticky notification: "Device Bridge active"        │  │
│  └──────┬───────────────────────────┬───────────────────┘  │
│         │                           │                       │
│  ┌──────▼──────────┐     ┌──────────▼──────────┐           │
│  │ Collectors      │     │ Stream Hub (Shared  │           │
│  │ - Sensors       │────▶│  Flows + last-value │           │
│  │ - Location      │     │  cache)             │           │
│  │ - Battery       │     └──────────┬──────────┘           │
│  │ - Network       │                │                       │
│  │ - Telephony     │     ┌──────────▼──────────┐           │
│  │ - Camera        │     │ Ktor Server         │           │
│  │ - Audio (opt)   │     │ REST + WebSocket    │           │
│  └─────────────────┘     │ 127.0.0.1:8765      │           │
│                          └──────────┬──────────┘           │
└─────────────────────────────────────┼───────────────────────┘
                                      │
                    Termux / Ubuntu / curl / scripts / Grok
```

### Module layout (single app module is fine for v1)

```
device-bridge/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/dev/asik/devicebridge/
        DeviceBridgeApp.kt
        MainActivity.kt
        di/AppModule.kt
        service/BridgeForegroundService.kt
        server/
          BridgeServer.kt          # Ktor install
          AuthPlugin.kt            # optional bearer token
          routes/
            CapabilitiesRoutes.kt
            SnapshotRoutes.kt
            StreamRoutes.kt
            CameraRoutes.kt
            ControlRoutes.kt
        collectors/
          CapabilityScanner.kt
          SensorCollector.kt
          LocationCollector.kt
          BatteryCollector.kt
          NetworkCollector.kt
          TelephonyCollector.kt
          CameraCollector.kt
          AudioCollector.kt        # optional phase 2
        model/
          Capability.kt
          SensorReading.kt
          LocationReading.kt
          DeviceSnapshot.kt
          ApiError.kt
        hub/StreamHub.kt
        ui/
          MainScreen.kt
          PermissionsScreen.kt
          theme/
        util/
          PermissionHelper.kt
          JsonConfig.kt
      res/
```

---

## API design (complete contract)

**Base URL (default):** `http://127.0.0.1:8765`  
**Auth (default off for localhost; recommended on):** header `Authorization: Bearer <token>`  
**Content-Type:** `application/json`  
**API version prefix:** `/v1`

### 1. Health

`GET /v1/health`

```json
{
  "ok": true,
  "service": "device-bridge",
  "version": "1.0.0",
  "uptime_sec": 120,
  "server_time": "2026-07-20T12:00:00Z"
}
```

### 2. Capabilities inventory (core)

`GET /v1/capabilities`

Returns everything discovered + permission state.

```json
{
  "device": {
    "manufacturer": "samsung",
    "model": "SM-F966U1",
    "sdk_int": 36,
    "release": "16",
    "abis": ["arm64-v8a"]
  },
  "permissions": {
    "ACCESS_FINE_LOCATION": "granted",
    "CAMERA": "granted",
    "RECORD_AUDIO": "denied",
    "BODY_SENSORS": "granted"
  },
  "sensors": [
    {
      "type": 1,
      "type_name": "android.sensor.accelerometer",
      "name": "LSM6DSO Accelerometer",
      "vendor": "STMicro",
      "version": 1,
      "max_range": 78.4,
      "resolution": 0.002,
      "power_ma": 0.15,
      "min_delay_us": 2400,
      "available": true
    }
  ],
  "cameras": [
    {
      "id": "0",
      "facing": "back",
      "has_flash": true,
      "hardware_level": "LEVEL_3",
      "available": true
    },
    {
      "id": "1",
      "facing": "front",
      "has_flash": false,
      "available": true
    }
  ],
  "location_providers": ["gps", "network", "passive", "fused"],
  "features": {
    "location": true,
    "camera": true,
    "microphone": true,
    "telephony": true,
    "wifi": true,
    "bluetooth": false
  }
}
```

### 3. Full snapshot (one-shot “everything available now”)

`GET /v1/snapshot`

Optional query:

- `include=sensors,location,battery,network,telephony,camera_meta`  
- `sensors=all` or `sensors=1,4,9` (type ints)

```json
{
  "timestamp": "2026-07-20T12:00:00.123Z",
  "location": {
    "lat": 0.0,
    "lon": 0.0,
    "alt_m": 10.0,
    "accuracy_m": 8.0,
    "speed_mps": 0.0,
    "bearing_deg": 0.0,
    "provider": "fused",
    "elapsed_realtime_nanos": 123456789
  },
  "battery": {
    "percent": 44,
    "status": "discharging",
    "plugged": "none",
    "temp_c": 31.2,
    "voltage_mv": 3823,
    "current_ua": -1300000
  },
  "network": {
    "connected": true,
    "transport": ["wifi", "cellular"],
    "wifi": {
      "ssid": "<may_be_unknown_without_location>",
      "rssi": -55,
      "link_speed_mbps": 866
    }
  },
  "telephony": {
    "network_operator_name": "...",
    "data_network_type": "NR",
    "sim_state": "ready"
  },
  "sensors": {
    "android.sensor.accelerometer": {
      "values": [0.1, 9.7, 0.2],
      "accuracy": 3,
      "timestamp_ns": 123
    }
  },
  "camera_meta": {
    "active_camera_id": null,
    "last_capture": null
  },
  "errors": []
}
```

### 4. Domain-specific REST

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/v1/location` | Latest location only |
| GET | `/v1/battery` | Battery |
| GET | `/v1/sensors` | All latest sensor values |
| GET | `/v1/sensors/{type}` | One sensor type (int or name) |
| GET | `/v1/network` | Connectivity / Wi‑Fi |
| GET | `/v1/telephony` | Safe telephony fields |
| GET | `/v1/cameras` | Camera list |
| POST | `/v1/camera/{id}/capture` | Take JPEG photo → returns metadata + base64 or file URL |
| GET | `/v1/camera/last.jpg` | Last captured image bytes |
| POST | `/v1/control/start` | Ensure collectors running |
| POST | `/v1/control/stop` | Stop non-essential collectors |
| GET | `/v1/config` | Public config (port, bind, enabled streams) |

### 5. Realtime WebSockets

#### A. Multiplexed stream (primary)

`WS /v1/stream?topics=location,sensors,battery,network`

Client may send:

```json
{"op":"subscribe","topics":["location","sensors","battery"]}
{"op":"unsubscribe","topics":["sensors"]}
{"op":"set_rate","topic":"sensors","hz":20}
{"op":"ping"}
```

Server messages:

```json
{"topic":"location","ts":"2026-07-20T12:00:00Z","data":{...}}
{"topic":"sensors","ts":"...","data":{"android.sensor.gyroscope":{"values":[0,0,0]}}}
{"topic":"battery","ts":"...","data":{...}}
{"topic":"hello","data":{"protocol":1,"server":"device-bridge"}}
{"topic":"error","data":{"code":"permission_denied","message":"CAMERA"}}
```

#### B. Sensor-only high-rate stream

`WS /v1/stream/sensors?types=1,4&hz=50`

#### C. Location stream

`WS /v1/stream/location?interval_ms=1000&priority=balanced`

#### D. Camera preview frames (phase 2 — bandwidth heavy)

`WS /v1/stream/camera/{id}?fps=5&max_width=640`  
Binary frames: JPEG bytes, or JSON envelope with base64 (prefer binary + optional text control channel).

**v1 recommendation:** photo capture REST first; preview WS second.

### 6. Error shape

```json
{
  "error": {
    "code": "permission_denied",
    "message": "ACCESS_FINE_LOCATION not granted",
    "permission": "android.permission.ACCESS_FINE_LOCATION"
  }
}
```

HTTP: `401` auth, `403` permission, `404` unknown sensor/camera, `503` collector not running, `429` rate limit.

---

## Collectors — behavior detail

### CapabilityScanner

On service start + on permission change:

1. Enumerate `sensorManager.getSensorList(Sensor.TYPE_ALL)`.
2. Enumerate cameras via CameraX `ProcessCameraProvider` / Camera2 `CameraManager.cameraIdList`.
3. List location providers.
4. PackageManager feature flags (`FEATURE_LOCATION_GPS`, `FEATURE_CAMERA`, etc.).
5. Current permission map.
6. Cache result for `GET /v1/capabilities`.

### SensorCollector

- Register listeners only for sensors that exist **and** user enabled.
- Default: sample important set at 10–25 Hz (accel, gyro, mag, rotation, light, prox, pressure).
- Heavy sensors optional.
- Push to `StreamHub` as Flow; keep last value per type for snapshots.
- Unregister when no REST/WS consumers **or** keep low-rate if “always sample” setting on.

### LocationCollector

- Prefer FusedLocationProviderClient:
  - high accuracy when GPS topic active
  - balanced otherwise
- Fallback: `LocationManager` requestLocationUpdates.
- Expose last known immediately on subscribe.
- Support background only with:
  - foreground service type `location`
  - `ACCESS_BACKGROUND_LOCATION` if you truly need it while UI not visible (Android 10+); for bridge use, FGS usually enough while notification shows.

### BatteryCollector

- Register sticky battery intent + `BatteryManager` properties (`BATTERY_PROPERTY_CURRENT_NOW`, etc.).
- Emit on change + every N seconds.

### NetworkCollector

- `ConnectivityManager.NetworkCallback` for transport changes.
- Wi‑Fi: `WifiManager.connectionInfo` / modern alternatives; note SSID often needs location permission on Android 8–13+.

### TelephonyCollector

- Only non-privileged fields.
- Never crash on SecurityException; return `restricted`.

### CameraCollector

- List cameras + capabilities.
- Capture: bind use cases, take picture, write to app cache `last.jpg`, expose path/base64.
- Optional phase 2: ImageAnalysis for JPEG/WebSocket frames at low FPS.
- Always show system camera privacy indicators; never try to hide them.

### AudioCollector (phase 2)

- Optional mic RMS / PCM chunk streaming — **off by default**, explicit enable in UI.

---

## Android components & lifecycle

### Foreground service

- Type combination (manifest):
  - `specialUse` **or** more precise: `location` + `camera` + `microphone` as needed (Android 14+ `foregroundServiceType`).
- Notification channels: `bridge_service`.
- Notification actions: Stop server, Open app.
- `START_STICKY` + restart on boot only if user enabled “Start on boot” (optional `BOOT_COMPLETED` receiver).

### Manifest permissions (declare all; request at runtime)

```
INTERNET                          // local server
ACCESS_NETWORK_STATE
ACCESS_WIFI_STATE
ACCESS_FINE_LOCATION
ACCESS_COARSE_LOCATION
ACCESS_BACKGROUND_LOCATION        // only if needed; request in second step
CAMERA
RECORD_AUDIO                      // phase 2
BODY_SENSORS                      // heart rate etc. if present
BODY_SENSORS_BACKGROUND           // rare
ACTIVITY_RECOGNITION
HIGH_SAMPLING_RATE_SENSORS        // Android 12+ unrestricted sensor rate
FOREGROUND_SERVICE
FOREGROUND_SERVICE_LOCATION
FOREGROUND_SERVICE_CAMERA
FOREGROUND_SERVICE_MICROPHONE
FOREGROUND_SERVICE_SPECIAL_USE    // if using specialUse
POST_NOTIFICATIONS                // Android 13+
RECEIVE_BOOT_COMPLETED            // optional
```

### UI screens (minimal but clear)

1. **Home:** server running?, URL, token copy, start/stop, last location, sensor count, camera count.
2. **Permissions:** checklist with grant buttons + deep link to system settings.
3. **Streams:** toggles for location / sensors / battery / network / camera / mic.
4. **Security:** bind address (`127.0.0.1` vs `0.0.0.0`), port, token enable/rotate.
5. **Debug:** last 50 API requests / errors (in-memory).

---

## Security model

| Setting | Default | Recommendation |
|---------|---------|----------------|
| Bind | `127.0.0.1` | Keep for Termux/Ubuntu on-device |
| Port | `8765` | Configurable |
| Auth token | Off | **On** if bind `0.0.0.0` |
| TLS | Off (local) | Optional later (self-signed) |
| Camera/mic | Off until enabled | Explicit user toggle |
| LAN exposure | Off | Warn in UI about GPS/camera leak |

If bind is `0.0.0.0`, force token + rate limit + optional allowlist of subnets.

---

## Client usage (Termux / Ubuntu)

```bash
# health
curl -s http://127.0.0.1:8765/v1/health | jq .

# what exists
curl -s http://127.0.0.1:8765/v1/capabilities | jq .

# one-shot everything
curl -s http://127.0.0.1:8765/v1/snapshot | jq .

# GPS only
curl -s http://127.0.0.1:8765/v1/location | jq .

# photo
curl -s -X POST http://127.0.0.1:8765/v1/camera/0/capture | jq .
curl -s http://127.0.0.1:8765/v1/camera/last.jpg -o shot.jpg

# realtime (websocat or python)
websocat ws://127.0.0.1:8765/v1/stream?topics=location,battery
```

With token:

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8765/v1/location
```

Python snippet for Grok/scripts:

```python
import json, urllib.request
print(json.load(urllib.request.urlopen("http://127.0.0.1:8765/v1/snapshot")))
```

---

## Implementation phases

### Phase 0 — Project bootstrap (day 1)

1. Install Android Studio (on a PC) or use command-line SDK in Termux (harder; prefer PC/remote).
2. Create empty Kotlin project `DeviceBridge`, minSdk 26, Compose, Hilt.
3. Add dependencies: Ktor server (CIO, websockets, content-negotiation), serialization, Coroutines, CameraX, Play services location, DataStore.
4. Wire `BridgeForegroundService` + notification + start/stop from UI.
5. Embed Ktor with `GET /v1/health` only.
6. Verify from Termux: `curl http://127.0.0.1:8765/v1/health`.

**Exit criteria:** App runs on SM-F966U1; health check works from Ubuntu/proot.

### Phase 1 — Capabilities + snapshot (day 1–2)

1. Implement `CapabilityScanner`.
2. Battery + network + device info collectors.
3. Sensor enumeration + last-value sampling for available sensors.
4. `GET /v1/capabilities`, `/v1/snapshot`, `/v1/battery`, `/v1/sensors`.
5. Permission helper UI.

**Exit criteria:** Snapshot returns real battery + at least accelerometer if present.

### Phase 2 — Location realtime (day 2)

1. Fused location collector + FGS type location.
2. `GET /v1/location` + `WS /v1/stream/location` + multiplex stream.
3. Test continuous updates while screen off (notification showing).

**Exit criteria:** `curl` and WebSocket show live lat/lon; solves original GPS problem for Grok/Termux.

### Phase 3 — Camera (day 3)

1. Camera list in capabilities.
2. `POST /v1/camera/{id}/capture` → JPEG in app storage + `/v1/camera/last.jpg`.
3. Optional base64 in JSON for small scripts.
4. Hardening: busy camera, permission denied, concurrent capture queue.

**Exit criteria:** Capture from Termux works for back + front cameras.

### Phase 4 — Hardening & polish (day 3–4)

1. Bearer token auth.
2. Rate limits, backpressure on sensor WS.
3. Boot start option.
4. Battery optimization deep-link (Samsung: Settings → Battery → Unrestricted).
5. Unit tests for JSON models; androidTest for server routing with mock collectors.
6. README with install + curl cookbook.

### Phase 5 — Optional expansions

- Mic stream / VAD level meter  
- Camera preview WebSocket  
- GNSS raw/`GnssStatus` satellites  
- BLE scan (permission-heavy)  
- Cloud relay (MQTT/WebSocket out) — only if needed  
- Companion Termux package `device-bridge-cli`  

---

## Build & install instructions (operator)

### On a development machine

```bash
# prerequisites: Android Studio Ladybug+ or SDK 35/36, JDK 17
git clone <repo> device-bridge && cd device-bridge

# debug APK
./gradlew :app:assembleDebug

# install over USB
adb install -r app/build/outputs/apk/debug/app-debug.apk

# or run
./gradlew :app:installDebug
```

### On-device first run

1. Open **Device Bridge**.
2. Grant notifications, location, sensors, camera (as prompted).
3. Samsung: set app battery usage to **Unrestricted**.
4. Tap **Start Bridge**.
5. Confirm notification “Device Bridge active”.
6. In Termux/Ubuntu: `curl http://127.0.0.1:8765/v1/health`.

### Release signing (later)

- Create upload keystore; use `minifyEnabled` carefully (keep Ktor/reflection rules).
- Sideload only is fine for personal bridge; Play Store needs privacy policy if location/camera.

---

## Testing plan

| Test | How |
|------|-----|
| Health | `curl /v1/health` |
| Capabilities | Sensor count > 0 on real device |
| Location | Walk outdoors; accuracy decreases |
| Sensors | Rotate phone; accel values change |
| Battery | Plug/unplug; status flips |
| Camera | Capture front/back; open JPEG |
| WS | `websocat` leave open 5 min; no crash |
| Permission revoke | Revoke location in settings; API returns 403 + capabilities update |
| Process death | Swipe away app; FGS keeps server or restarts per policy |
| Proot path | From Ubuntu: same curl to 127.0.0.1 |

---

## Risk register & mitigations

| Risk | Mitigation |
|------|------------|
| Android 16 / Samsung kills FGS | Proper FGS types, persistent notification, unrestricted battery |
| Sensor flood / heat / battery drain | Default Hz caps, subscribe-driven registration |
| Camera privacy / legal | User toggles, notification, no silent background video in v1 |
| Binding 0.0.0.0 leaks GPS | Default localhost; force token if LAN |
| Play services location missing on de-Googled ROMs | LocationManager fallback |
| Proot networking quirks | Prefer 127.0.0.1; document `adb reverse` if ever using host PC |
| targetSdk photo/video restrictions | Use CameraX; MediaStore if sharing images |

---

## Dependencies (app/build.gradle.kts sketch)

```kotlin
// versions via BOM / catalog
implementation("androidx.core:core-ktx")
implementation("androidx.activity:activity-compose")
implementation("androidx.compose.bom")
implementation("androidx.lifecycle:lifecycle-service")
implementation("androidx.lifecycle:lifecycle-runtime-compose")
implementation("androidx.datastore:datastore-preferences")
implementation("com.google.dagger:hilt-android")
kapt/ksp("com.google.dagger:hilt-compiler")

implementation("io.ktor:ktor-server-cio")
implementation("io.ktor:ktor-server-websockets")
implementation("io.ktor:ktor-server-content-negotiation")
implementation("io.ktor:ktor-serialization-kotlinx-json")
implementation("io.ktor:ktor-server-auth")
implementation("io.ktor:ktor-server-status-pages")
implementation("io.ktor:ktor-server-call-logging")

implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")

implementation("androidx.camera:camera-camera2")
implementation("androidx.camera:camera-lifecycle")
implementation("androidx.camera:camera-view")

implementation("com.google.android.gms:play-services-location")
```

---

## Suggested file-level implementation order

1. `model/*` + serialization  
2. `hub/StreamHub.kt`  
3. `collectors/BatteryCollector.kt` + `CapabilityScanner.kt`  
4. `server/BridgeServer.kt` + health/capabilities/snapshot routes  
5. `service/BridgeForegroundService.kt` + manifest  
6. `ui/MainScreen.kt` start/stop  
7. `LocationCollector` + stream routes  
8. `SensorCollector`  
9. `CameraCollector` + capture routes  
10. Auth + settings + polish  

---

## Success criteria (definition of done)

- [ ] App installs on SM-F966U1 (Android 16)  
- [ ] Foreground service keeps local server alive with notification  
- [ ] `GET /v1/capabilities` lists sensors + cameras + permission state  
- [ ] `GET /v1/snapshot` returns live readings for all *granted* domains  
- [ ] `GET /v1/location` returns GPS/network fix usable from Ubuntu  
- [ ] `WS /v1/stream` pushes location + battery + sensors  
- [ ] Camera capture works for at least one back and one front camera  
- [ ] Denied permissions produce structured errors, not crashes  
- [ ] Default bind is localhost-only  
- [ ] README documents curl/WebSocket examples for Termux  

---

## Out of scope for v1

- iOS  
- Root/magisk modules  
- Full continuous silent video surveillance  
- Reading SMS/call logs (sensitive; add later only if required)  
- Google Play listing / production OAuth cloud backend  
- Replacing Termux itself  

---

## Recommendation summary

| Decision | Choice |
|----------|--------|
| Language | **Kotlin** |
| UI | Jetpack Compose |
| Server | Embedded **Ktor** on `127.0.0.1:8765` |
| Realtime | **WebSocket** + REST snapshots |
| Sensors | Full `SensorManager` inventory + sampled reads |
| Location | Fused Location + FGS |
| Camera | CameraX photo capture first; stream later |
| Security | Localhost default; optional bearer token |
| Delivery | Sideload debug APK; personal device bridge |

---

## Next step after plan approval

Implement **Phase 0 + Phase 1 + Phase 2** first so GPS works for Termux/Ubuntu immediately; then add camera and polish.

Optional: generate the Android Studio project skeleton under `/home/asik/projects/device-bridge` with manifest, Gradle, health endpoint, and README matching this plan.
