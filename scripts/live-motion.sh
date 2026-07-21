#!/usr/bin/env bash
# Wrapper: live micro-motion meter for Device Bridge.
# Usage:
#   ./scripts/live-motion.sh
#   BRIDGE=http://100.102.108.113:8765 BRIDGE_TOKEN=xxx ./scripts/live-motion.sh
#   ./scripts/live-motion.sh --sensitivity ultra
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
# Prefer python3; install websocket-client if missing (optional)
if ! python3 -c 'import websocket' 2>/dev/null; then
  echo "Note: pip install websocket-client  # for smoother WS stream; using HTTP if missing" >&2
fi
exec python3 "$DIR/live-motion.py" "$@"
