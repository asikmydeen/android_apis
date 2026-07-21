#!/usr/bin/env python3
"""
SensIO: AI Motion & Touch Spatial Reasoning Agent
Powered by local LLM (Ollama / Hugging Face GGUF)

Usage:
  export BRIDGE=http://asik-mydeens-z-fold7.hartley-beta.ts.net:8765
  export BRIDGE_TOKEN=703f8df3212f48719eeb3ef51ba01067
  ./scripts/ai-motion.sh
"""

from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import deque
from typing import Any, Deque, Dict, List, Optional, Tuple
from urllib.parse import urlencode, urlparse

# ANSI terminal colors
RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
C_CYAN = "\033[38;5;117m"
C_GREEN = "\033[38;5;120m"
C_AMBER = "\033[38;5;215m"
C_PURPLE = "\033[38;5;183m"
C_MAGENTA = "\033[38;5;207m"
C_ROSE = "\033[38;5;203m"
C_GRAY = "\033[38;5;242m"
CLEAR = "\033[2J\033[H"
HIDE_CUR = "\033[?25l"
SHOW_CUR = "\033[?25h"

PREFERRED_FAST_MODELS = [
    "smollm2:1.7b",
    "smollm2:360m",
    "llama3.2:1b",
    "llama3.2:3b",
    "qwen2.5:1.5b",
    "qwen2.5:0.5b",
    "gemma2:2b",
    "phi4-mini",
]


def _read_first_line(path: str) -> str:
    try:
        if os.path.isfile(path):
            return open(path, encoding="utf-8").read().strip()
    except OSError:
        pass
    return ""


def load_token_from_files() -> str:
    candidates = [
        os.environ.get("BRIDGE_TOKEN_FILE", ""),
        os.path.expanduser("~/.config/device-bridge/token"),
        os.path.expanduser("~/.device-bridge-token"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), ".bridge-token"),
    ]
    for path in candidates:
        if path and (tok := _read_first_line(path)):
            return tok
    return ""


def load_bridge_from_files() -> str:
    candidates = [
        os.environ.get("BRIDGE_URL_FILE", ""),
        os.path.expanduser("~/.config/device-bridge/url"),
        os.path.expanduser("~/.device-bridge-url"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), ".bridge-url"),
    ]
    for path in candidates:
        if path and (url := _read_first_line(path)):
            return url
    return ""


def normalize_bridge_url(raw: str) -> str:
    s = (raw or "").strip().rstrip("/")
    if not s:
        return ""
    if s.startswith("http://") or s.startswith("https://"):
        return s
    if ":" not in s:
        s = f"{s}:8765"
    return f"http://{s}"


def http_get_json(url: str, token: str = "", timeout: float = 3.0) -> Any:
    if token:
        sep = "&" if "?" in url else "?"
        url = f"{url}{sep}{urlencode({'token': token})}"
    req = urllib.request.Request(url)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def http_post_json(url: str, data: Dict[str, Any], timeout: float = 30.0) -> Any:
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def detect_best_installed_model(ollama_url: str) -> str:
    try:
        data = http_get_json(f"{ollama_url.rstrip('/')}/api/tags", timeout=3.0)
        models = [m.get("name", "") for m in data.get("models", [])]
        for pref in PREFERRED_FAST_MODELS:
            for installed in models:
                if pref in installed or installed.startswith(pref):
                    return installed
        if models:
            return models[0]
    except Exception:
        pass
    return "qwen3.5:latest"


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="SensIO AI Motion Agent")
    default_bridge = (
        os.environ.get("BRIDGE")
        or load_bridge_from_files()
        or "http://127.0.0.1:8765"
    )
    p.add_argument("--bridge", default=default_bridge, help="Device Bridge base URL")
    p.add_argument(
        "--token",
        default=os.environ.get("BRIDGE_TOKEN")
        or os.environ.get("TOKEN")
        or load_token_from_files()
        or "",
        help="Device Bridge auth token",
    )
    p.add_argument(
        "--ollama-url", default="http://localhost:11434", help="Ollama local API URL"
    )
    p.add_argument(
        "--model", default="", help="Ollama model name (auto-detects if omitted)"
    )
    p.add_argument(
        "--interval", type=float, default=0.05, help="Sensor poll interval in seconds"
    )
    return p.parse_args()


def extract_sensors(
    data: Dict[str, Any]
) -> Tuple[Optional[List[float]], Optional[List[float]], Optional[List[float]]]:
    lin = gyro = acc = None
    for key, reading in data.items():
        if not isinstance(reading, dict):
            continue
        vals = reading.get("values")
        if not vals or len(vals) < 3:
            continue
        k = key.lower()
        if "linear_acceleration" in k:
            lin = [float(v) for v in vals[:3]]
        elif "gyroscope" in k and "uncalibrat" not in k:
            gyro = [float(v) for v in vals[:3]]
        elif "accelerometer" in k and "linear" not in k and "uncalibrat" not in k:
            acc = [float(v) for v in vals[:3]]
    return lin, gyro, acc


class TouchState:
    """Tracks touchscreen coordinates, normalized positions, and active region."""

    def __init__(self) -> None:
        self.action = "UP"
        self.x = 0.0
        self.y = 0.0
        self.x_norm = 0.5
        self.y_norm = 0.5
        self.screen_w = 1080
        self.screen_h = 2316
        self.last_touch_time = 0.0
        self.touch_trail: Deque[Tuple[float, float, float]] = deque(maxlen=8)

    def update(self, touch_data: Optional[Dict[str, Any]]) -> None:
        if not touch_data or not isinstance(touch_data, dict):
            return
        self.action = touch_data.get("action", "UP")
        self.screen_w = touch_data.get("screen_width", 1080)
        self.screen_h = touch_data.get("screen_height", 2316)

        pointers = touch_data.get("pointers", [])
        if pointers and isinstance(pointers, list):
            p0 = pointers[0]
            if isinstance(p0, dict):
                self.x = p0.get("x", 0.0)
                self.y = p0.get("y", 0.0)
                self.x_norm = p0.get("x_norm", 0.5)
                self.y_norm = p0.get("y_norm", 0.5)
                now = time.time()
                self.last_touch_time = now
                self.touch_trail.append((self.x_norm, self.y_norm, now))

    def get_region_label(self) -> str:
        if (time.time() - self.last_touch_time) > 1.5:
            return "No Active Touch"
        horiz = "Left" if self.x_norm < 0.35 else ("Right" if self.x_norm > 0.65 else "Center")
        vert = "Top" if self.y_norm < 0.35 else ("Bottom" if self.y_norm > 0.65 else "Middle")
        return f"{vert}-{horiz} ({self.action})"


class MotionDSP:
    """Calculates orientation, acceleration, and micro-gestures."""

    def __init__(self) -> None:
        self.pitch = 0.0
        self.roll = 0.0
        self.g_mag = 9.81
        self.gyro_mag = 0.0
        self.jerk = 0.0
        self.last_acc: Optional[List[float]] = None
        self.recent_gestures: Deque[Tuple[str, float]] = deque(maxlen=10)
        self.last_shake_time = 0.0
        self.stationary_since = time.time()

    def update(
        self,
        lin: Optional[List[float]],
        gyro: Optional[List[float]],
        acc: Optional[List[float]],
    ) -> Dict[str, Any]:
        now = time.time()
        ax = ay = az = 0.0
        if acc and len(acc) >= 3:
            ax, ay, az = acc[0], acc[1], acc[2]
            self.g_mag = math.sqrt(ax * ax + ay * ay + az * az)
            self.pitch = math.atan2(ay, math.sqrt(ax * ax + az * az)) * 180.0 / math.pi
            self.roll = math.atan2(-ax, az) * 180.0 / math.pi

        lx = ly = lz = 0.0
        if lin and len(lin) >= 3:
            lx, ly, lz = lin[0], lin[1], lin[2]

        gx = gy = gz = 0.0
        if gyro and len(gyro) >= 3:
            gx, gy, gz = gyro[0], gyro[1], gyro[2]
            self.gyro_mag = math.sqrt(gx * gx + gy * gy + gz * gz)

        if self.last_acc and acc and len(acc) >= 3:
            self.jerk = math.sqrt(
                (acc[0] - self.last_acc[0]) ** 2
                + (acc[1] - self.last_acc[1]) ** 2
                + (acc[2] - self.last_acc[2]) ** 2
            )
        if acc and len(acc) >= 3:
            self.last_acc = list(acc)

        lin_mag = math.sqrt(lx * lx + ly * ly + lz * lz)

        detected_gesture = None
        if self.g_mag < 2.5:
            detected_gesture = "FREEFALL_DROP"
        elif self.jerk > 16.0 or lin_mag > 18.0:
            if now - self.last_shake_time < 0.8:
                detected_gesture = "DOUBLE_SHAKE"
            self.last_shake_time = now
        elif self.gyro_mag > 3.8:
            detected_gesture = "WRIST_FLICK"
        elif az < -7.0 and abs(ax) < 4.0:
            detected_gesture = "FLIP_FACE_DOWN"
        elif self.pitch > 25.0 and lin_mag > 1.2 and (now - self.stationary_since) > 1.5:
            detected_gesture = "PICKUP_PHONE"

        if lin_mag >= 0.3 or self.gyro_mag >= 0.2:
            self.stationary_since = now

        if detected_gesture:
            if (
                not self.recent_gestures
                or self.recent_gestures[-1][0] != detected_gesture
                or (now - self.recent_gestures[-1][1]) > 1.2
            ):
                self.recent_gestures.append((detected_gesture, now))

        if (now - self.stationary_since) > 2.0:
            state_desc = "Stationary on flat surface"
        elif az < -7.0:
            state_desc = "Face-down screen muted"
        elif self.pitch > 20.0:
            state_desc = "In-hand facing user"
        else:
            state_desc = "In-hand active motion"

        return {
            "pitch": self.pitch,
            "roll": self.roll,
            "g_mag": self.g_mag,
            "gyro_mag": self.gyro_mag,
            "jerk": self.jerk,
            "state_desc": state_desc,
            "recent_gestures": [g[0] for g in self.recent_gestures],
        }


def bar_chart(val: float, max_val: float, width: int = 12) -> str:
    norm = min(1.0, max(0.0, val / max_val))
    filled = int(norm * width)
    return "█" * filled + "░" * (width - filled)


def render_screen_pad(touch: TouchState, width: int = 24, height: int = 8) -> List[str]:
    """Draws an ASCII smartphone display pad showing real-time touch coordinates."""
    grid = [[" " for _ in range(width)] for _ in range(height)]
    now = time.time()

    # Draw touch history trail
    for x_n, y_n, ts in touch.touch_trail:
        col = int(x_n * (width - 1))
        row = int(y_n * (height - 1))
        if 0 <= col < width and 0 <= row < height:
            age = now - ts
            grid[row][col] = "•" if age < 0.8 else "·"

    # Draw active touch pointer
    if (now - touch.last_touch_time) <= 2.5:
        col = int(touch.x_norm * (width - 1))
        row = int(touch.y_norm * (height - 1))
        col = max(0, min(width - 1, col))
        row = max(0, min(height - 1, row))
        grid[row][col] = "🔴" if touch.action in ("DOWN", "MOVE") else "⦿"

    border_top = "┌─ Phone Screen ─┐".center(width + 2, "─")
    border_bottom = "└" + "─" * width + "┘"

    lines = [f"{C_GRAY}{border_top}{RESET}"]
    for r in range(height):
        row_str = "".join(grid[r])
        lines.append(f"{C_GRAY}│{RESET}{row_str}{C_GRAY}│{RESET}")
    lines.append(f"{C_GRAY}{border_bottom}{RESET}")
    return lines


class AIBrainThread:
    """Async worker thread querying local Ollama LLM for motion & touch intent reasoning."""

    def __init__(self, ollama_url: str, model_name: str) -> None:
        self.ollama_url = ollama_url.rstrip("/")
        self.model_name = model_name
        self.lock = threading.Lock()
        self.latest_analysis = "Querying Ollama local AI model..."
        self.active_intent = "CALM"
        self.last_query_time = 0.0
        self.running = True
        self.pending_payload: Optional[Dict[str, Any]] = None

    def submit_data(self, telemetry: Dict[str, Any]) -> None:
        now = time.time()
        if now - self.last_query_time >= 2.0:
            with self.lock:
                if not self.pending_payload:
                    self.pending_payload = telemetry

    def start(self) -> None:
        t = threading.Thread(target=self._worker_loop, daemon=True)
        t.start()

    def _worker_loop(self) -> None:
        while self.running:
            payload = None
            with self.lock:
                if self.pending_payload:
                    payload = self.pending_payload
                    self.pending_payload = None
                    self.last_query_time = time.time()

            if payload:
                self._query_ollama(payload)
            else:
                time.sleep(0.15)

    def _query_ollama(self, data: Dict[str, Any]) -> None:
        with self.lock:
            self.latest_analysis = f"Thinking ({self.model_name})..."

        prompt = (
            f"Smartphone Sensor Reading:\n"
            f"- Motion State: {data['state_desc']} (Pitch: {data['pitch']:.1f}°, Roll: {data['roll']:.1f}°)\n"
            f"- G-Force: {data['g_mag']:.2f} m/s², Gyro: {data['gyro_mag']:.2f} rad/s\n"
            f"- Touchpad Region: {data['touch_region']}\n"
            f"- Micro-Gestures: {', '.join(data['recent_gestures']) or 'None'}\n\n"
            f"Describe user action and intent in 1 sentence. End with 1 action token e.g. [ACTION: TAP_SELECT] or [ACTION: READ] or [ACTION: MUTE]."
        )

        url = f"{self.ollama_url}/api/generate"
        req_data = {
            "model": self.model_name,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0.0, "num_predict": 40},
        }

        try:
            res = http_post_json(url, req_data, timeout=30.0)
            text = res.get("response", "").strip()
            text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL).strip()

            if text:
                with self.lock:
                    self.latest_analysis = text
                    if "[ACTION:" in text:
                        start = text.find("[ACTION:") + 8
                        end = text.find("]", start)
                        if end > start:
                            self.active_intent = text[start:end].strip()
        except Exception as ex:
            with self.lock:
                self.latest_analysis = f"Ollama ({self.model_name}): {type(ex).__name__}"


def render_dashboard(
    bridge_url: str,
    metrics: Dict[str, Any],
    touch: TouchState,
    ai_thread: AIBrainThread,
    model_name: str,
) -> str:
    lines = [CLEAR + HIDE_CUR]
    lines.append(f"  {BOLD}{C_CYAN}🤖 SensIO: Multi-Modal AI Motion & Touch Agent{RESET}")
    lines.append(f"  {DIM}Bridge: {bridge_url}  │  LLM: {model_name} (Ollama){RESET}")
    lines.append("  " + "─" * 65)

    p_bar = bar_chart(abs(metrics["pitch"]), 90.0, 10)
    r_bar = bar_chart(abs(metrics["roll"]), 180.0, 10)
    g_bar = bar_chart(metrics["g_mag"], 20.0, 10)

    # ASCII Screen Pad
    screen_pad = render_screen_pad(touch, width=24, height=8)

    # Layout Row: Sensors on Left, ASCII Touch Screen on Right
    lines.append(
        f"  {C_AMBER}Pitch:{RESET} {metrics['pitch']:+5.1f}° [{p_bar}]   {screen_pad[0]}"
    )
    lines.append(
        f"  {C_AMBER}Roll: {RESET} {metrics['roll']:+5.1f}° [{r_bar}]   {screen_pad[1]}"
    )
    lines.append(
        f"  {C_GREEN}Force:{RESET} {metrics['g_mag']:5.2f}m/s² [{g_bar}]   {screen_pad[2]}"
    )
    lines.append(
        f"  {C_PURPLE}State:{RESET} {BOLD}{metrics['state_desc'][:22]:<22}{RESET} {screen_pad[3]}"
    )

    t_str = f"x:{touch.x:.0f} y:{touch.y:.0f} ({touch.x_norm:.2f}, {touch.y_norm:.2f})"
    lines.append(
        f"  {C_ROSE}Touch:{RESET} {BOLD}{touch.get_region_label()[:22]:<22}{RESET} {screen_pad[4]}"
    )
    lines.append(
        f"  {C_GRAY}{t_str:<32}{RESET} {screen_pad[5]}"
    )
    lines.append(
        f"  {'':<39} {screen_pad[6]}"
    )
    lines.append(
        f"  {'':<39} {screen_pad[7]}"
    )
    lines.append(
        f"  {'':<39} {screen_pad[8]}"
    )

    gestures_str = "  ".join([f"⚡ {g}" for g in metrics["recent_gestures"][-3:]]) or "None"
    lines.append(f"  {C_MAGENTA}Micro-Gestures:{RESET} {gestures_str}")
    lines.append("  " + "─" * 65)

    with ai_thread.lock:
        analysis = ai_thread.latest_analysis
        intent = ai_thread.active_intent

    lines.append(f"  {BOLD}{C_CYAN}🧠 Local LLM Multi-Modal Intent Reasoning:{RESET}")
    lines.append(f"  {C_GRAY}{analysis}{RESET}")
    lines.append(f"  {C_GREEN}Intent Action Trigger:{RESET} {BOLD}[{intent}]{RESET}")
    lines.append("  " + "─" * 65)
    lines.append(f"  {DIM}Press Ctrl+C to exit agent.{RESET}")

    return "\n".join(lines) + SHOW_CUR


def main() -> int:
    args = parse_args()
    bridge_url = normalize_bridge_url(args.bridge)
    token = args.token

    model_name = args.model or detect_best_installed_model(args.ollama_url)

    print(f"{DIM}Connecting to SensIO Bridge at {bridge_url}...{RESET}", file=sys.stderr)
    print(f"{DIM}Using Ollama model: {model_name}{RESET}", file=sys.stderr)

    dsp = MotionDSP()
    touch = TouchState()
    ai_thread = AIBrainThread(args.ollama_url, model_name)
    ai_thread.start()

    try:
        while True:
            try:
                # Fetch sensor data
                s_data = http_get_json(f"{bridge_url}/v1/sensors", token, timeout=3.0)
                if isinstance(s_data, dict):
                    lin, gyro, acc = extract_sensors(s_data)
                    metrics = dsp.update(lin, gyro, acc)

                # Fetch touch data
                t_data = http_get_json(f"{bridge_url}/v1/touch", token, timeout=1.5)
                if isinstance(t_data, dict):
                    touch.update(t_data)

                metrics["touch_region"] = touch.get_region_label()
                ai_thread.submit_data(metrics)

                out = render_dashboard(bridge_url, metrics, touch, ai_thread, model_name)
                sys.stdout.write(out)
                sys.stdout.flush()

                time.sleep(args.interval)
            except (urllib.error.URLError, TimeoutError, OSError) as ex:
                sys.stdout.write(
                    f"{CLEAR}  Waiting for SensIO Bridge at {bridge_url} ({type(ex).__name__})...\n"
                )
                sys.stdout.flush()
                time.sleep(1.0)
    except KeyboardInterrupt:
        ai_thread.running = False
        sys.stdout.write(SHOW_CUR + "\n  SensIO AI Agent stopped.\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
