# android_apis (Device Bridge)

Native **Kotlin** Android app plan: discover device sensors/cameras/location, read what the OS allows, and expose it over **local realtime REST + WebSocket APIs** for Termux, Ubuntu (proot), scripts, and automation.

## Status

Planning complete — implementation not started yet.

## Documents

- **[PLAN.md](./PLAN.md)** — full architecture, API contract, modules, phases, security, and build instructions.

## Target stack (summary)

| Piece | Choice |
|--------|--------|
| Language | Kotlin |
| UI | Jetpack Compose |
| Local server | Embedded Ktor (`127.0.0.1:8765`) |
| Realtime | WebSocket + REST snapshots |
| Camera | CameraX |
| Location | Fused Location Provider |

## Quick intent

Replace fragile Termux:API for on-device GPS/sensors/camera by a dedicated foreground-service app that serves:

```text
GET  /v1/health
GET  /v1/capabilities
GET  /v1/snapshot
GET  /v1/location
WS   /v1/stream?topics=location,sensors,battery
POST /v1/camera/{id}/capture
```

See [PLAN.md](./PLAN.md) for the complete design.
