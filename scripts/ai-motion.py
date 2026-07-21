#!/usr/bin/env python3
"""
AI Motion & Spatial Context Agent for Device Bridge
Powered by local LLM (Ollama / Hugging Face GGUF)

Usage:
  export BRIDGE=http://asik-mydeens-z-fold7.hartley-beta.ts.net:8765
  export BRIDGE_TOKEN=703f8df3212f48719eeb3ef51ba01067
  python3 scripts/ai-motion.py

  Or specify CLI flags:
  python3 scripts/ai-motion.py --bridge http://... --token ... --model smollm2:1.7b
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
    p = argparse.ArgumentParser(description="AI Motion Agent for Device Bridge")
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
    p.add_argument(
        "--action-cmd", default="", help="Optional shell command to execute on gesture"
    )
    return p.parse_args()


def extract_sensors(
    data: Dict[str, Any]
) -> Tuple[Optional[List[float]], Optional[List[float]], Optional[List[float]]]:
    """Extracts linear_acceleration, gyroscope, and accelerometer from any response map."""
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


class MotionDSP:
    """Calculates physical orientation, acceleration vectors, and micro-gestures."""

    def __init__(self) -> None:
        self.pitch = 0.0
        self.roll = 0.0
        self.g_mag = 9.81
        self.gyro_mag = 0.0
        self.jerk = 0.0
        self.last_acc: Optional[List[float]] = None
        self.acc_history: Deque[float] = deque(maxlen=30)
        self.gyro_history: Deque[float] = deque(maxlen=30)
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

            # Pitch & Roll in degrees
            self.pitch = math.atan2(ay, math.sqrt(ax * ax + az * az)) * 180.0 / math.pi
            self.roll = math.atan2(-ax, az) * 180.0 / math.pi

        lx = ly = lz = 0.0
        if lin and len(lin) >= 3:
            lx, ly, lz = lin[0], lin[1], lin[2]

        gx = gy = gz = 0.0
        if gyro and len(gyro) >= 3:
            gx, gy, gz = gyro[0], gyro[1], gyro[2]
            self.gyro_mag = math.sqrt(gx * gx + gy * gy + gz * gz)

        # Jerk
        if self.last_acc and acc and len(acc) >= 3:
            self.jerk = math.sqrt(
                (acc[0] - self.last_acc[0]) ** 2
                + (acc[1] - self.last_acc[1]) ** 2
                + (acc[2] - self.last_acc[2]) ** 2
            )
        if acc and len(acc) >= 3:
            self.last_acc = list(acc)

        lin_mag = math.sqrt(lx * lx + ly * ly + lz * lz)
        self.acc_history.append(lin_mag)
        self.gyro_history.append(self.gyro_mag)

        # Gestures
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
        elif (
            self.pitch > 25.0
            and lin_mag > 1.2
            and (now - self.stationary_since) > 1.5
        ):
            detected_gesture = "PICKUP_PHONE"

        if lin_mag < 0.3 and self.gyro_mag < 0.2:
            pass
        else:
            self.stationary_since = now

        if detected_gesture:
            if (
                not self.recent_gestures
                or self.recent_gestures[-1][0] != detected_gesture
                or (now - self.recent_gestures[-1][1]) > 1.2
            ):
                self.recent_gestures.append((detected_gesture, now))

        # Qualitative State
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


def bar_chart(val: float, max_val: float, width: int = 15) -> str:
    norm = min(1.0, max(0.0, val / max_val))
    filled = int(norm * width)
    return "█" * filled + "░" * (width - filled)


class AIBrainThread:
    """Async worker thread querying local Ollama LLM for motion context & intent reasoning."""

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
            f"- Pitch: {data['pitch']:.1f}°, Roll: {data['roll']:.1f}°\n"
            f"- Force: {data['g_mag']:.2f} m/s², Gyro Rotation: {data['gyro_mag']:.2f} rad/s\n"
            f"- State: {data['state_desc']}\n"
            f"- Gestures: {', '.join(data['recent_gestures']) or 'None'}\n\n"
            f"State what the user is physically doing and their spatial intent in 1 plain sentence. "
            f"End with one action token in brackets e.g. [ACTION: MUTE] or [ACTION: READ] or [ACTION: FOCUS]."
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

            # Remove <think>...</think> tags if thinking model
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
    ai_thread: AIBrainThread,
    model_name: str,
) -> str:
    lines = [CLEAR + HIDE_CUR]
    lines.append(
        f"  {BOLD}{C_CYAN}🤖 AI Motion Intelligence & Spatial Reasoning Agent{RESET}"
    )
    lines.append(f"  {DIM}Bridge: {bridge_url}  │  LLM: {model_name} (Ollama){RESET}")
    lines.append("  " + "─" * 65)

    p_bar = bar_chart(abs(metrics["pitch"]), 90.0, 12)
    r_bar = bar_chart(abs(metrics["roll"]), 180.0, 12)
    g_bar = bar_chart(metrics["g_mag"], 20.0, 12)
    w_bar = bar_chart(metrics["gyro_mag"], 5.0, 12)

    lines.append(
        f"  {C_AMBER}Pitch:{RESET} {metrics['pitch']:+6.1f}° [{p_bar}]   "
        f"{C_AMBER}Roll:{RESET} {metrics['roll']:+6.1f}° [{r_bar}]"
    )
    lines.append(
        f"  {C_GREEN}G-Force:{RESET} {metrics['g_mag']:5.2f}m/s² [{g_bar}]   "
        f"{C_GREEN}Gyro:{RESET}  {metrics['gyro_mag']:5.2f}rad/s [{w_bar}]"
    )
    lines.append(
        f"  {C_PURPLE}Physical State:{RESET} {BOLD}{metrics['state_desc']}{RESET}"
    )

    gestures_str = "  ".join(
        [f"⚡ {g}" for g in metrics["recent_gestures"][-4:]]
    ) or "None (resting)"
    lines.append(f"  {C_MAGENTA}Micro-Gestures:{RESET} {gestures_str}")
    lines.append("  " + "─" * 65)

    with ai_thread.lock:
        analysis = ai_thread.latest_analysis
        intent = ai_thread.active_intent

    lines.append(
        f"  {BOLD}{C_CYAN}🧠 Local LLM Spatial Intent Reasoning:{RESET}"
    )
    lines.append(f"  {C_GRAY}{analysis}{RESET}")
    lines.append(
        f"  {C_GREEN}Intent Action Trigger:{RESET} {BOLD}[{intent}]{RESET}"
    )
    lines.append("  " + "─" * 65)
    lines.append(f"  {DIM}Press Ctrl+C to exit agent.{RESET}")

    return "\n".join(lines) + SHOW_CUR


def main() -> int:
    args = parse_args()
    bridge_url = normalize_bridge_url(args.bridge)
    token = args.token

    model_name = args.model or detect_best_installed_model(args.ollama_url)

    print(
        f"{DIM}Connecting to Device Bridge at {bridge_url}...{RESET}",
        file=sys.stderr,
    )
    print(
        f"{DIM}Using Ollama model: {model_name}{RESET}",
        file=sys.stderr,
    )

    dsp = MotionDSP()
    ai_thread = AIBrainThread(args.ollama_url, model_name)
    ai_thread.start()

    try:
        while True:
            try:
                data = http_get_json(
                    f"{bridge_url}/v1/sensors", token, timeout=3.0
                )
                if isinstance(data, dict):
                    lin, gyro, acc = extract_sensors(data)

                    metrics = dsp.update(lin, gyro, acc)
                    ai_thread.submit_data(metrics)

                    out = render_dashboard(bridge_url, metrics, ai_thread, model_name)
                    sys.stdout.write(out)
                    sys.stdout.flush()

                time.sleep(args.interval)
            except (urllib.error.URLError, TimeoutError, OSError) as ex:
                sys.stdout.write(
                    f"{CLEAR}  Waiting for Device Bridge at {bridge_url} ({type(ex).__name__})...\n"
                )
                sys.stdout.flush()
                time.sleep(1.0)
    except KeyboardInterrupt:
        ai_thread.running = False
        sys.stdout.write(SHOW_CUR + "\n  AI Motion Agent stopped.\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
