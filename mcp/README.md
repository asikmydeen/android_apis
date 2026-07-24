# Device Bridge MCP server

Exposes a [Device Bridge](../README.md) (SensIO) phone to MCP-speaking AI agents —
Claude Desktop, Claude Code, Cursor, and anything else that speaks the Model Context
Protocol. The agent gets tools to **read** the phone (location, battery, sensors,
camera, USB, mic level) and **operate** it (launch apps, open links, speak, torch,
vibrate, notify).

It's a thin stdio proxy that runs on **your** machine and forwards MCP tool calls to
the phone's local `/v1` API over the bearer token you already have. It adds no new
attack surface to the phone — it only calls routes that already require the token.

## Prerequisites

- Node.js 18+
- A running Device Bridge with a reachable URL + token. In the app: **Remote tab →
  "Copy connection config"** gives you both values (`BRIDGE_URL`, `BRIDGE_TOKEN`).

## Build

```bash
cd mcp
npm install
npm run build
```

## Configure your MCP client

### Claude Code

```bash
claude mcp add devicebridge -- node /absolute/path/to/mcp/dist/index.js \
  -e BRIDGE_URL=http://127.0.0.1:8765 \
  -e BRIDGE_TOKEN=your-token-here
```

### Claude Desktop / Cursor (JSON config)

```json
{
  "mcpServers": {
    "devicebridge": {
      "command": "node",
      "args": ["/absolute/path/to/mcp/dist/index.js"],
      "env": {
        "BRIDGE_URL": "http://127.0.0.1:8765",
        "BRIDGE_TOKEN": "your-token-here"
      }
    }
  }
}
```

> Use the phone's LAN or Tailscale URL (not `127.0.0.1`) when the agent runs on a
> different machine than the phone. The `/docs` page and the Remote tab show the
> reachable URLs for your current network mode.

## Tools

**Read:** `get_capabilities`, `get_health`, `get_snapshot`, `get_location`,
`get_battery`, `get_network`, `get_sensors`, `get_audio_level`, `list_cameras`,
`capture_photo`, `list_usb_devices`

**Act:** `launch_app`, `open_url`, `speak`, `set_torch`, `vibrate`, `notify`

Ask the agent to "get the device capabilities" first — `get_capabilities` reports what
hardware is present and which permissions are granted, so the agent can plan.

## Notes

- The token lives in your MCP client config on your trusted machine. Never commit it
  or bundle it. Rotating the token in the app invalidates this connection.
- Live streams (WebSocket topics like raw audio PCM) are not exposed as MCP tools —
  MCP tools are request/response. Use the REST/WS API directly for streaming.
