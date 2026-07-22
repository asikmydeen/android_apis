#!/usr/bin/env bash
# Records Play Store Declaration Review Video via ADB (supports Android Emulator & Physical Devices)
set -euo pipefail

SERIAL="${ADB_SERIAL:-emulator-5554}"
ADB_CMD="adb -s $SERIAL"

OUTPUT_MP4="docs/sensio_play_store_declaration.mp4"
REMOTE_TMP="/sdcard/sensio_review.mp4"

echo "=== Starting Play Store Declaration Video Recording on $SERIAL ==="
MODEL_NAME=$($ADB_CMD shell getprop ro.product.model || echo "Android Emulator")
echo "Target device model: $MODEL_NAME"

# Start background recording
$ADB_CMD shell screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 180 $REMOTE_TMP &
REC_PID=$!

echo "Recording started. Executing automated demonstration sequence..."
sleep 2

# 1. Launch SensIO App
echo "[1/4] Launching SensIO App..."
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 3

# 2. Trigger Accessibility Disclosure Dialog in Settings
echo "[2/4] Demonstrating Accessibility Disclosure & Consent Flow..."
# Tap Settings tab (Coordinates tailored for emulator resolution)
$ADB_CMD shell input tap 1000 2800
sleep 2

# Scroll down to reveal Touchscreen section & Accessibility button
$ADB_CMD shell input swipe 500 2000 500 1000 300
sleep 2

# Tap "Enable Global Touch Tracking" button to trigger Prominent Disclosure Dialog
$ADB_CMD shell input tap 670 1450
sleep 4

# Tap "Decline" button (Demonstrating Decline Flow)
$ADB_CMD shell input tap 400 1750
sleep 2

# Re-trigger Prominent Disclosure Dialog
$ADB_CMD shell input tap 670 1450
sleep 3

# Tap "Agree & Enable" button (Demonstrating Consent Flow)
$ADB_CMD shell input tap 900 1750
sleep 3

# Return back to SensIO
$ADB_CMD shell input keyevent 4
sleep 2

# 3. Demonstrate Foreground Service & Ongoing Notification
echo "[3/4] Demonstrating Foreground Service & Ongoing Notification..."
# Tap Home tab
$ADB_CMD shell input tap 150 2800
sleep 2

# Tap "Start SensIO Service" button
$ADB_CMD shell input tap 670 850
sleep 3

# Pull down notification shade to demonstrate ongoing foreground notification
$ADB_CMD shell cmd statusbar expand-notifications
sleep 4

# Collapse notification shade
$ADB_CMD shell cmd statusbar collapse
sleep 2

# 4. Demonstrate Core Feature (Background API & Location/Camera/Special Use FGS)
echo "[4/4] Demonstrating Core Feature background access..."
# Background the app to show background service execution
$ADB_CMD shell input keyevent 3
sleep 3

# Stop screen recording
echo "Stopping recording..."
kill -SIGINT $REC_PID 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 3

mkdir -p docs
$ADB_CMD pull $REMOTE_TMP "$OUTPUT_MP4"
$ADB_CMD shell rm -f "$REMOTE_TMP"

echo "=== Video recording complete! Saved to $OUTPUT_MP4 ==="
