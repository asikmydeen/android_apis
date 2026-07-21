#!/usr/bin/env bash
# Wrapper script for AI Motion & Spatial Context Agent
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"

export BRIDGE="${BRIDGE:-http://127.0.0.1:8765}"
export BRIDGE_TOKEN="${BRIDGE_TOKEN:-}"

exec python3 "$DIR/ai-motion.py" "$@"
