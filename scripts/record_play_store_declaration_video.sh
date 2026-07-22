#!/usr/bin/env bash
# Records Play Store Declaration Review Video via ADB with exact UI coordinates
set -euo pipefail

SERIAL="${ADB_SERIAL:-emulator-5554}"
ADB_CMD="adb -s $SERIAL"

OUTPUT_MP4="docs/sensio_play_store_declaration.mp4"
REMOTE_TMP="/sdcard/sensio_review.mp4"

echo "=== Starting Play Store Declaration Video Recording on $SERIAL ==="
MODEL_NAME=$($ADB_CMD shell getprop ro.product.model || echo "Android Emulator")
echo "Target device model: $MODEL_NAME"

# Start background recording
$ADB_CMD shell screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 120 $REMOTE_TMP &
REC_PID=$!

echo "Recording started..."
sleep 2

# 1. Launch SensIO App
echo "[1/4] Launching SensIO App..."
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 3

# 2. Go to Settings Tab
echo "[2/4] Navigating to Settings Tab..."
$ADB_CMD shell input tap 1185 2800
sleep 3

# Scroll down to reveal "Enable Global Touch Tracking (Accessibility)" button
echo "Scrolling down Settings..."
$ADB_CMD shell input swipe 600 2200 600 800 400
sleep 3

# Tap "Enable Global Touch Tracking (Accessibility)" button -> Shows Prominent Disclosure
echo "Tapping Enable Global Touch Tracking button..."
$ADB_CMD shell input tap 670 826
sleep 4

# Tap "Decline" button -> Demonstrating Decline Flow
echo "Tapping Decline button..."
$ADB_CMD shell input tap 475 2123
sleep 3

# Re-trigger Prominent Disclosure Dialog
echo "Re-triggering Prominent Disclosure..."
$ADB_CMD shell input tap 670 826
sleep 4

# Tap "Agree & Enable" button -> Opens System Accessibility Settings
echo "Tapping Agree & Enable button..."
$ADB_CMD shell input tap 860 2123
sleep 3

# In System Accessibility Settings, tap "SensIO" under Downloaded Apps
echo "Tapping SensIO in System Accessibility Settings..."
$ADB_CMD shell input tap 400 930
sleep 3

# Tap "Use SensIO" toggle
echo "Tapping Use SensIO toggle..."
$ADB_CMD shell input tap 500 848
sleep 3

# Tap "Allow" on System Permission Dialog
echo "Tapping Allow on System Permission Dialog..."
$ADB_CMD shell input tap 600 1940
sleep 3

# Return back to SensIO app
echo "Returning to SensIO app..."
$ADB_CMD shell input keyevent 4
sleep 1
$ADB_CMD shell input keyevent 4
sleep 3

# 3. Go to Home Tab & Start Service
echo "[3/4] Starting SensIO Foreground Service..."
$ADB_CMD shell input tap 150 2800
sleep 3

# Tap "Start SensIO Service" button
$ADB_CMD shell input tap 670 820
sleep 3

# Expand status bar / notification shade to demonstrate active ongoing notification
echo "Expanding status bar notification shade..."
$ADB_CMD shell cmd statusbar expand-notifications
sleep 5

# Collapse notification shade
$ADB_CMD shell cmd statusbar collapse
sleep 3

# 4. Demonstrate Core Feature (Background API execution)
echo "[4/4] Demonstrating background API execution..."
# Press Home button to background the app
$ADB_CMD shell input keyevent 3
sleep 3

# Perform swipes/taps on Home Screen to demonstrate background touch tracking
echo "Performing background touches on Home Screen..."
$ADB_CMD shell input tap 400 1900
sleep 2
$ADB_CMD shell input swipe 900 1500 200 1500 300
sleep 2
$ADB_CMD shell input swipe 200 1500 900 1500 300
sleep 3

# Re-open SensIO to show active status
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 4

# Stop screen recording
echo "Stopping recording..."
kill -SIGINT $REC_PID 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 3

mkdir -p docs
$ADB_CMD pull $REMOTE_TMP "$OUTPUT_MP4"
$ADB_CMD shell rm -f "$REMOTE_TMP"

echo "=== Video recording complete! Saved to $OUTPUT_MP4 ==="
