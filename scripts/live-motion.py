#!/usr/bin/env python3
"""
Live motion meter for Device Bridge — shows even micro-movements.

Uses linear acceleration + gyroscope (and optional accel) from the phone API.
Prefers WebSocket sensor stream; falls back to fast HTTP polling.

Usage:
  export BRIDGE=http://127.0.0.1:8765          # or Tailscale URL
  export BRIDGE_TOKEN=your_token               # if auth enabled
  python3 scripts/live-motion.py

  python3 scripts/live-motion.py --bridge http://100.x.x.x:8765 --token abc
  python3 scripts/live-motion.py --http-only   # no websockets
  python3 scripts/live-motion.py --sensitivity high
"""

from __future__ import annotations

import argparse
import getpass
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from collections import deque
from typing import Any, Deque, Dict, List, Optional, Tuple
from urllib.parse import urlencode, urlparse, urlunparse


def load_token_from_files() -> str:
    """Optional token files so you don't have to export every time."""
    candidates = [
        os.environ.get("BRIDGE_TOKEN_FILE", ""),
        os.path.expanduser("~/.config/device-bridge/token"),
        os.path.expanduser("~/.device-bridge-token"),
        os.path.join(os.path.dirname(os.path.abspath(__file__)), ".bridge-token"),
    ]
    for path in candidates:
        if not path:
            continue
        try:
            if os.path.isfile(path):
                tok = open(path, encoding="utf-8").read().strip()
                if tok:
                    return tok
        except OSError:
            continue
    return ""


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Live micro-motion display from Device Bridge")
    p.add_argument(
        "--bridge",
        default=os.environ.get("BRIDGE", "http://127.0.0.1:8765"),
        help="Base URL (default env BRIDGE or http://127.0.0.1:8765)",
    )
    p.add_argument(
        "--token",
        default=os.environ.get("BRIDGE_TOKEN")
        or os.environ.get("TOKEN")
        or load_token_from_files()
        or "",
        help="Bearer token (env BRIDGE_TOKEN/TOKEN, or ~/.device-bridge-token)",
    )
    p.add_argument(
        "--http-only",
        action="store_true",
        help="Force HTTP polling instead of WebSocket",
    )
    p.add_argument(
        "--interval",
        type=float,
        default=0.05,
        help="HTTP poll interval seconds (default 0.05 = 20 Hz)",
    )
    p.add_argument(
        "--sensitivity",
        choices=("normal", "high", "ultra"),
        default="high",
        help="Display scaling for micro-movements (default high)",
    )
    p.add_argument(
        "--width",
        type=int,
        default=40,
        help="Bar width in characters",
    )
    return p.parse_args()


def with_token_url(url: str, token: str) -> str:
    if not token:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}{urlencode({'token': token})}"


def http_get_json(url: str, token: str, timeout: float = 2.0) -> Any:
    full = with_token_url(url, token)
    req = urllib.request.Request(full)
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def mag(vals: Optional[List[float]]) -> float:
    if not vals:
        return 0.0
    return math.sqrt(sum(float(x) * float(x) for x in vals[:3]))


def bar(value: float, max_v: float, width: int) -> str:
    if max_v <= 0:
        max_v = 1.0
    n = int(max(0.0, min(1.0, value / max_v)) * width)
    return "█" * n + "░" * (width - n)


def sensitivity_scales(level: str) -> Tuple[float, float, float]:
    """Return (lin_max, gyro_max, activity_max) for bar scaling."""
    if level == "ultra":
        return 0.15, 0.15, 0.25  # tiny motion fills the bar
    if level == "high":
        return 0.5, 0.4, 0.8
    return 2.0, 1.5, 3.0  # normal walking/shake scale


def extract_from_sensors_map(sensors: Dict[str, Any]) -> Tuple[Optional[List[float]], Optional[List[float]], Optional[List[float]]]:
    """Find linear, gyro, accel by substring (OEM names vary)."""
    lin = gyro = acc = None
    for key, reading in sensors.items():
        k = key.lower()
        vals = reading.get("values") if isinstance(reading, dict) else None
        if not vals:
            continue
        if "linear_acceleration" in k or k.endswith("linear"):
            lin = vals
        elif "gyroscope" in k and "uncalibrat" not in k:
            gyro = vals
        elif k.endswith("accelerometer") or k.endswith(".accelerometer"):
            acc = vals
        elif "accelerometer" in k and "linear" not in k and "uncalibrat" not in k:
            if acc is None:
                acc = vals
    return lin, gyro, acc


class MotionState:
    def __init__(self) -> None:
        self.lin_hist: Deque[float] = deque(maxlen=60)
        self.gyro_hist: Deque[float] = deque(maxlen=60)
        self.last_lin: Optional[List[float]] = None
        self.samples = 0
        self.peak_lin = 0.0
        self.peak_gyro = 0.0

    def update(
        self,
        lin: Optional[List[float]],
        gyro: Optional[List[float]],
        acc: Optional[List[float]],
    ) -> Dict[str, float]:
        lm = mag(lin)
        gm = mag(gyro)
        am = mag(acc)

        # Micro-jerk: change in linear accel vector
        jerk = 0.0
        if lin and self.last_lin and len(lin) >= 3 and len(self.last_lin) >= 3:
            jerk = math.sqrt(
                sum((float(lin[i]) - float(self.last_lin[i])) ** 2 for i in range(3))
            )
        if lin:
            self.last_lin = [float(x) for x in lin[:3]]

        # Activity score blends linear + gyro + jerk (sensitive to micro moves)
        activity = math.sqrt(lm * lm + (gm * 2.0) ** 2 + (jerk * 3.0) ** 2)

        self.lin_hist.append(lm)
        self.gyro_hist.append(gm)
        self.samples += 1
        self.peak_lin = max(self.peak_lin, lm)
        self.peak_gyro = max(self.peak_gyro, gm)

        # baseline = quiet floor (10th percentile-ish of recent)
        def floor(hist: Deque[float]) -> float:
            if not hist:
                return 0.0
            s = sorted(hist)
            return s[max(0, len(s) // 10)]

        lin_floor = floor(self.lin_hist)
        # excess over quiet baseline = "you moved"
        micro = max(0.0, lm - lin_floor * 0.5)

        return {
            "lin": lm,
            "gyro": gm,
            "acc": am,
            "jerk": jerk,
            "activity": activity,
            "micro": micro,
        }


def classify(activity: float, lin: float, sensitivity: str) -> str:
    # thresholds depend on sensitivity
    if sensitivity == "ultra":
        t_still, t_micro, t_move, t_shake = 0.02, 0.05, 0.2, 1.0
    elif sensitivity == "high":
        t_still, t_micro, t_move, t_shake = 0.05, 0.12, 0.5, 2.5
    else:
        t_still, t_micro, t_move, t_shake = 0.15, 0.4, 1.5, 5.0

    if activity < t_still and lin < t_still:
        return "STILL"
    if activity < t_micro:
        return "MICRO"
    if activity < t_move:
        return "MOVE"
    if activity < t_shake:
        return "ACTIVE"
    return "SHAKE"


def render(
    metrics: Dict[str, float],
    state: MotionState,
    width: int,
    sensitivity: str,
    source: str,
) -> str:
    lin_max, gyro_max, act_max = sensitivity_scales(sensitivity)
    label = classify(metrics["activity"], metrics["lin"], sensitivity)
    emoji = {
        "STILL": "·",
        "MICRO": "~",
        "MOVE": "→",
        "ACTIVE": "⚡",
        "SHAKE": "💥",
    }.get(label, "?")

    lines = [
        f"\033[2J\033[H",  # clear screen
        f"Device Bridge live motion  [{source}]  samples={state.samples}  sens={sensitivity}",
        f"",
        f"  {emoji}  {label:6}   activity={metrics['activity']:.4f}",
        f"",
        f"  linear   {metrics['lin']:8.4f} m/s²  {bar(metrics['lin'], lin_max, width)}",
        f"  micro    {metrics['micro']:8.4f}       {bar(metrics['micro'], lin_max, width)}",
        f"  jerk     {metrics['jerk']:8.4f}       {bar(metrics['jerk'], lin_max, width)}",
        f"  gyro     {metrics['gyro']:8.4f} rad/s {bar(metrics['gyro'], gyro_max, width)}",
        f"  |accel|  {metrics['acc']:8.4f} m/s²  (≈9.8 when still in gravity)",
        f"",
        f"  peaks    lin={state.peak_lin:.4f}  gyro={state.peak_gyro:.4f}",
        f"",
        f"  STILL=no motion  MICRO=tiny  MOVE=clear  ACTIVE=strong  SHAKE=violent",
        f"  Tip: rest phone → STILL; nudge slightly → MICRO; walk/wave → MOVE/SHAKE",
        f"  Ctrl+C to quit",
    ]
    return "\n".join(lines)


def run_http(bridge: str, token: str, interval: float, width: int, sensitivity: str) -> None:
    state = MotionState()
    url = bridge.rstrip("/") + "/v1/sensors"
    print("HTTP polling", url, f"every {interval}s …", file=sys.stderr)
    while True:
        try:
            data = http_get_json(url, token, timeout=max(1.0, interval * 4))
            if not isinstance(data, dict):
                time.sleep(interval)
                continue
            # sensors endpoint returns map of type -> reading
            lin, gyro, acc = extract_from_sensors_map(data)
            metrics = state.update(lin, gyro, acc)
            sys.stdout.write(render(metrics, state, width, sensitivity, "HTTP"))
            sys.stdout.flush()
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as e:
            sys.stdout.write(f"\033[2J\033[H  waiting for bridge… ({e})\n")
            sys.stdout.flush()
            time.sleep(1.0)
            continue
        time.sleep(interval)


def run_websocket(bridge: str, token: str, width: int, sensitivity: str) -> None:
    try:
        import websocket  # type: ignore  # websocket-client
    except ImportError:
        raise RuntimeError("websocket-client not installed")

    # build ws URL
    parsed = urlparse(bridge)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    netloc = parsed.netloc
    path = "/v1/stream/sensors"
    q = urlencode({"token": token}) if token else ""
    ws_url = urlunparse((scheme, netloc, path, "", q, ""))

    state = MotionState()
    # latest partial map from streaming individual sensors
    latest: Dict[str, Any] = {}

    print("WebSocket", ws_url, file=sys.stderr)

    def on_message(_ws: Any, message: str) -> None:
        try:
            env = json.loads(message)
        except json.JSONDecodeError:
            return
        if env.get("topic") not in ("sensors", "sensor"):
            # hello etc.
            if env.get("topic") == "hello":
                return
            # stream envelope with sensor map in data
        data = env.get("data")
        if not isinstance(data, dict):
            return
        # data may be { "android.sensor.x": {values:...}, ... } or single
        for k, v in data.items():
            if isinstance(v, dict) and "values" in v:
                latest[k] = v
            elif k == "values" and isinstance(data.get("values"), list):
                # single reading shape unlikely
                pass
        if not latest:
            # maybe whole map is type -> reading
            lin, gyro, acc = extract_from_sensors_map(data)
            if lin is None and gyro is None and acc is None:
                return
            metrics = state.update(lin, gyro, acc)
            sys.stdout.write(render(metrics, state, width, sensitivity, "WS"))
            sys.stdout.flush()
            return

        lin, gyro, acc = extract_from_sensors_map(latest)
        metrics = state.update(lin, gyro, acc)
        sys.stdout.write(render(metrics, state, width, sensitivity, "WS"))
        sys.stdout.flush()

    def on_error(_ws: Any, error: Any) -> None:
        print(f"WS error: {error}", file=sys.stderr)

    def on_close(_ws: Any, *args: Any) -> None:
        print("WS closed", file=sys.stderr)

    headers = []
    if token:
        headers.append(f"Authorization: Bearer {token}")

    ws = websocket.WebSocketApp(
        ws_url,
        header=headers,
        on_message=on_message,
        on_error=on_error,
        on_close=on_close,
    )
    ws.run_forever(ping_interval=20, ping_timeout=10)


def main() -> int:
    args = parse_args()
    bridge = args.bridge.rstrip("/")
    token = args.token

    def try_health(tok: str) -> Any:
        return http_get_json(bridge + "/v1/health", tok, timeout=3.0)

    def prompt_token(reason: str) -> str:
        print(reason, file=sys.stderr)
        print("Device Bridge app → Remote tab → copy token", file=sys.stderr)
        # getpass hides input; also offer visible fallback if empty
        try:
            tok = getpass.getpass("Token (input hidden): ").strip()
        except (EOFError, KeyboardInterrupt):
            print("", file=sys.stderr)
            return ""
        if not tok:
            try:
                tok = input("Token (visible): ").strip()
            except (EOFError, KeyboardInterrupt):
                print("", file=sys.stderr)
                return ""
        return tok

    def maybe_save_token(tok: str) -> None:
        path = os.path.expanduser("~/.device-bridge-token")
        if not tok or os.path.isfile(path):
            return
        try:
            ans = input(f"Save token to {path} for next time? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("", file=sys.stderr)
            return
        if ans in ("y", "yes"):
            try:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(tok + "\n")
                os.chmod(path, 0o600)
                print(f"Saved {path}", file=sys.stderr)
            except OSError as ex:
                print(f"Could not save token: {ex}", file=sys.stderr)

    # quick health check — prompt for token on 401 or if none provided after a failed try
    health = None
    try:
        health = try_health(token)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            token = prompt_token(
                "Auth required (HTTP 401). Paste token from the app Remote tab."
            )
            if not token:
                print("No token entered.", file=sys.stderr)
                return 1
            try:
                health = try_health(token)
            except Exception as e2:
                print(f"Still cannot auth: {e2}", file=sys.stderr)
                return 1
            maybe_save_token(token)
            # keep for this process (WS/HTTP use `token`)
        else:
            print(f"Cannot reach {bridge}/v1/health: {e}", file=sys.stderr)
            return 1
    except Exception as e:
        # connection error — still offer token if they forgot and server needs it later
        print(f"Cannot reach {bridge}/v1/health: {e}", file=sys.stderr)
        print("Is Device Bridge started? Try: curl -s http://127.0.0.1:8765/", file=sys.stderr)
        return 1

    if health is None:
        return 1

    if not token:
        # Public health without auth — ask only if user wants remote later; fine for local
        pass

    print(
        f"Bridge OK version={health.get('version')} degraded={health.get('degraded')}"
        + (" auth=on" if token else " auth=off/local"),
        file=sys.stderr,
    )

    if not args.http_only:
        try:
            run_websocket(bridge, token, args.width, args.sensitivity)
            return 0
        except Exception as e:
            print(f"WebSocket unavailable ({e}); falling back to HTTP…", file=sys.stderr)

    try:
        run_http(bridge, token, args.interval, args.width, args.sensitivity)
    except KeyboardInterrupt:
        print("\nbye", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
