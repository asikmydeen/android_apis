#!/usr/bin/env bash
# Records Play Store Declaration Review Video directly from connected Samsung Z Fold7 (or specified SERIAL)
set -euo pipefail

SERIAL="${ADB_SERIAL:-adb-RFCYA0TQ03B-QmKPLF._adb-tls-connect._tcp}"
ADB_CMD="adb -s $SERIAL"

OUTPUT_MP4="docs/sensio_play_store_declaration.mp4"
REMOTE_TMP="/sdcard/sensio_review.mp4"

echo "=== Starting Play Store Declaration Video Recording ==="
MODEL_NAME=$($ADB_CMD shell getprop ro.product.model || echo "Z Fold7")
echo "Target device model: $MODEL_NAME ($SERIAL)"

# Wake screen and unlock if locked
$ADB_CMD shell input keyevent 224 || true
$ADB_CMD shell input keyevent 82 || true
$ADB_CMD shell input swipe 540 2000 540 500 200 || true
sleep 2

# Start background recording
$ADB_CMD shell screenrecord --size 720x1600 --bit-rate 4000000 --time-limit 180 $REMOTE_TMP &
REC_PID=$!

echo "Recording started..."
sleep 2

# 1. Launch SensIO App
echo "[1/5] Launching SensIO App..."
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 4

# 2. Go to Settings Tab
echo "[2/5] Navigating to Settings Tab..."
$ADB_CMD shell input tap 950 2330
sleep 3

# Scroll down Settings screen
echo "Scrolling down Settings..."
$ADB_CMD shell input swipe 500 1800 500 600 300
sleep 3

# Tap "Enable Global Touch Tracking (Accessibility)" button -> Shows Prominent Disclosure Dialog
echo "Tapping Enable Global Touch Tracking button..."
$ADB_CMD shell input tap 540 745
sleep 4

# Tap "Decline" button -> Demonstrating Decline Flow
echo "Tapping Decline button..."
$ADB_CMD shell input tap 380 1768
sleep 3

# Re-trigger Prominent Disclosure Dialog
echo "Re-triggering Prominent Disclosure..."
$ADB_CMD shell input tap 540 745
sleep 4

# Tap "Agree & Enable" button -> Opens System Accessibility Settings
echo "Tapping Agree & Enable button..."
$ADB_CMD shell input tap 710 1768
sleep 4

# 3. Complete System Accessibility Activation Flow
echo "[3/5] Navigating & Activating SensIO in System Settings..."
# Tap "Installed apps"
$ADB_CMD shell input tap 400 1450
sleep 3

# Tap "SensIO" under Installed apps list
$ADB_CMD shell input tap 400 1650
sleep 3

# Toggle "SensIO" switch ON
$ADB_CMD shell input tap 940 370
sleep 3

# Tap "Allow" on System Permission Dialog
echo "Tapping Allow on System Permission Dialog..."
$ADB_CMD shell input tap 870 2250
sleep 3

# Return back to SensIO app
echo "Returning to SensIO app..."
$ADB_CMD shell input keyevent 4
sleep 1
$ADB_CMD shell input keyevent 4
sleep 1
$ADB_CMD shell input keyevent 4
sleep 3

# 4. Start Foreground Service & Show Persistent Notification
echo "[4/5] Starting SensIO Foreground Service..."
# Tap Home tab
$ADB_CMD shell input tap 130 2330
sleep 3

# Tap "Start SensIO Service" button
$ADB_CMD shell input tap 500 820
sleep 3

# Expand status bar / notification shade to demonstrate active ongoing notification
echo "Expanding status bar notification shade..."
$ADB_CMD shell cmd statusbar expand-notifications
sleep 5

# Collapse notification shade
$ADB_CMD shell cmd statusbar collapse
sleep 3

# 5. Demonstrate Background API & Sensor Server Execution
echo "[5/5] Demonstrating background API execution..."
# Press Home button to background SensIO app
$ADB_CMD shell input keyevent 3
sleep 3

# Perform touch interactions across phone home screen / apps
echo "Performing background touches across device..."
$ADB_CMD shell input tap 300 1500
sleep 2
$ADB_CMD shell input swipe 900 1200 200 1200 300
sleep 2
$ADB_CMD shell input swipe 200 1200 900 1200 300
sleep 3

# Re-open SensIO to show active running server & live touch coordinates update
echo "Re-opening SensIO..."
$ADB_CMD shell am start -n dev.asik.devicebridge/.MainActivity
sleep 5

# Stop screen recording
echo "Stopping recording..."
kill -SIGINT $REC_PID 2>/dev/null || true
wait $REC_PID 2>/dev/null || true
sleep 3

mkdir -p docs
$ADB_CMD pull $REMOTE_TMP "$OUTPUT_MP4"
$ADB_CMD shell rm -f "$REMOTE_TMP"

echo "=== Video recording complete! Saved to $OUTPUT_MP4 ==="
