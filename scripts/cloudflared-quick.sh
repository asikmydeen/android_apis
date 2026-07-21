#!/usr/bin/env bash
# Quick Cloudflare tunnel to Device Bridge on this phone.
# Run in Termux while Device Bridge is started (mode Cloudflare or Local).
set -euo pipefail
PORT="${PORT:-8765}"
TARGET="${TARGET:-http://127.0.0.1:${PORT}}"

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "cloudflared not found. Install:"
  echo "  pkg install cloudflared"
  echo "  # or: https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/"
  exit 1
fi

if ! curl -sf --max-time 2 "http://127.0.0.1:${PORT}/" >/dev/null 2>&1; then
  echo "WARNING: nothing answering on 127.0.0.1:${PORT}"
  echo "Open Device Bridge → Start, mode Cloudflare/Local."
fi

echo "Tunneling $TARGET …"
echo "Paste the https://….trycloudflare.com URL into the app Remote tab + use Bearer token."
exec cloudflared tunnel --url "$TARGET"
