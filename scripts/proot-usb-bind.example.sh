#!/data/data/com.termux/files/usr/bin/bash
# Example: discover a removable USB volume via Device Bridge and login to Ubuntu with it bound.
# Run from Termux (not from inside proot).

set -euo pipefail
BRIDGE="${BRIDGE:-http://127.0.0.1:8765}"
DISTRO="${DISTRO:-ubuntu}"
MNT="${MNT:-/mnt/usb}"

if ! curl -sf "$BRIDGE/v1/health" >/dev/null; then
  echo "Device Bridge not running at $BRIDGE — open the app and tap Start bridge."
  exit 1
fi

PATH_JSON=$(curl -sf "$BRIDGE/v1/usb/storage")
echo "$PATH_JSON" | head -c 2000
echo

# Prefer first removable non-primary volume with a path (requires jq)
if command -v jq >/dev/null 2>&1; then
  USB_PATH=$(echo "$PATH_JSON" | jq -r '
    [.[] | select(.is_removable == true and .is_primary == false and .path != null)]
    | first | .path // empty
  ')
else
  echo "Install jq for auto-select, or set USB_PATH=/storage/XXXX-XXXX manually."
  USB_PATH="${USB_PATH:-}"
fi

if [ -z "${USB_PATH}" ]; then
  echo "No removable volume path found. Plug OTG drive and re-run, or:"
  echo "  USB_PATH=/storage/XXXX-XXXX $0"
  exit 1
fi

echo "Binding $USB_PATH -> $MNT in $DISTRO"
exec proot-distro login "$DISTRO" --bind "$USB_PATH:$MNT"
