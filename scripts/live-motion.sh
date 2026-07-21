#!/usr/bin/env bash
# Wrapper: live micro-motion meter for Device Bridge.
# Usage (on phone):
#   ./scripts/live-motion.sh
# Usage (another PC on Tailscale):
#   ./scripts/live-motion.sh --ask-bridge
#   # or it auto-asks if localhost is down
#   BRIDGE=http://100.x.x.x:8765 BRIDGE_TOKEN=xxx ./scripts/live-motion.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
if ! python3 -c 'import websocket' 2>/dev/null; then
  echo "Note: pip install websocket-client  # optional smoother stream" >&2
fi
exec python3 "$DIR/live-motion.py" "$@"
