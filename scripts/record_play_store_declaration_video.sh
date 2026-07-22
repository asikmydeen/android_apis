#!/usr/bin/env bash
# Records Play Store Declaration Review Video via ADB
set -euo pipefail

SERIAL="${ADB_SERIAL:-}"
ADB_CMD="adb"
if [ -n "$SERIAL" ]; then
    ADB_CMD="adb -s $SERIAL"
fi

OUTPUT_MP4="docs/sensio_play_store_declaration.mp4"
REMOTE_TMP="/sdcard/sensio_review.mp4"

echo "=== Starting Play Store Declaration Video Recording ==="
echo "Target device: $($ADB_CMD shell getprop ro.product.model)"
echo "Recording to $REMOTE_TMP (max 180s)..."

# Start background recording
$ADB_CMD shell screenrecord --size 1080x2400 --bit-rate 6000000 --time-limit 180 $REMOTE_TMP &
REC_PID=$!

echo "Recording started. Demonstrating flow..."
sleep 2

# 1. Launch SensIO App
echo "[1/4] Launching SensIO App..."
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 3

# 2. Trigger Accessibility Disclosure Dialog in Settings
echo "[2/4] Demonstrating Accessibility Disclosure..."
# Tap Settings tab
$ADB_CMD shell input tap 940 2400
sleep 2

# Tap "Enable Global Touch Tracking" button to show Prominent Disclosure Dialog
$ADB_CMD shell input tap 500 1300
sleep 4

# Tap Decline to demonstrate decline flow
$ADB_CMD shell input tap 300 1500
sleep 2

# Re-trigger Disclosure Dialog
$ADB_CMD shell input tap 500 1300
sleep 3

# Tap "Agree & Enable" to navigate to System Settings
$ADB_CMD shell input tap 750 1500
sleep 3

# Press Back to return to SensIO
$ADB_CMD shell input keyevent 4
sleep 2

# 3. Demonstrate Foreground Service & Ongoing Notification
echo "[3/4] Demonstrating Foreground Service & Ongoing Notification..."
# Tap Home tab
$ADB_CMD shell input tap 150 2400
sleep 2

# Tap Start Service button
$ADB_CMD shell input tap 500 800
sleep 3

# Pull down status bar to show ongoing foreground notification
$ADB_CMD shell cmd statusbar expand-notifications
sleep 4

# Collapse status bar
$ADB_CMD shell cmd statusbar collapse
sleep 2

# 4. Demonstrate Core Feature (Background API execution)
echo "[4/4] Demonstrating Core Feature background access..."
# Background the app
$ADB_CMD shell input keyevent 3
sleep 3

# Stop screen recording
echo "Stopping recording..."
kill -SIGINT $REC_PID 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 2

mkdir -p docs
$ADB_CMD pull $REMOTE_TMP "$OUTPUT_MP4"
$ADB_CMD shell rm "$REMOTE_TMP"

echo "=== Video recording complete! Saved to $OUTPUT_MP4 ==="
