#!/usr/bin/env python3
"""
Live motion UI for Device Bridge — soft, direction-aware, micro-sensitive.

Shows which way the phone is moving (left/right, up/down, toward/away)
with smooth bars that react to tiny movements.

Usage:
  python3 scripts/live-motion.py
  python3 scripts/live-motion.py --token … --sensitivity ultra
  python3 scripts/live-motion.py --bridge http://100.x.x.x:8765
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

# ── soft ANSI (no hard blink) ──────────────────────────────────────────────
RESET = "\033[0m"
DIM = "\033[2m"
BOLD = "\033[1m"
# muted pastels that feel calm on dark terminals
C_SOFT = "\033[38;5;189m"   # soft lavender text
C_DIM = "\033[38;5;60m"     # quiet gray-blue
C_GLOW = "\033[38;5;117m"   # gentle cyan glow
C_WARM = "\033[38;5;180m"   # warm sand
C_MINT = "\033[38;5;151m"   # mint
C_ROSE = "\033[38;5;175m"   # soft rose
C_PEACH = "\033[38;5;216m"
C_LILAC = "\033[38;5;183m"
C_SKY = "\033[38;5;153m"
HIDE_CUR = "\033[?25l"
SHOW_CUR = "\033[?25h"
CLEAR = "\033[2J\033[H"


def load_token_from_files() -> str:
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
    p = argparse.ArgumentParser(description="Soft live motion UI for Device Bridge")
    p.add_argument("--bridge", default=os.environ.get("BRIDGE", "http://127.0.0.1:8765"))
    p.add_argument(
        "--token",
        default=os.environ.get("BRIDGE_TOKEN")
        or os.environ.get("TOKEN")
        or load_token_from_files()
        or "",
    )
    p.add_argument("--http-only", action="store_true")
    p.add_argument("--interval", type=float, default=0.04, help="HTTP poll s (default ~25Hz)")
    p.add_argument(
        "--sensitivity",
        choices=("normal", "high", "ultra"),
        default="ultra",
        help="ultra = micro-movements fill the UI (default)",
    )
    p.add_argument("--width", type=int, default=21, help="half-width of bidirectional bars")
    p.add_argument("--no-color", action="store_true")
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


def mag3(x: float, y: float, z: float) -> float:
    return math.sqrt(x * x + y * y + z * z)


def clamp(v: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, v))


def ema(prev: float, new: float, alpha: float) -> float:
    return prev * (1.0 - alpha) + new * alpha


def extract_from_sensors_map(
    sensors: Dict[str, Any],
) -> Tuple[Optional[List[float]], Optional[List[float]], Optional[List[float]]]:
    lin = gyro = acc = None
    for key, reading in sensors.items():
        k = key.lower()
        vals = reading.get("values") if isinstance(reading, dict) else None
        if not vals or len(vals) < 3:
            continue
        if "linear_acceleration" in k:
            lin = vals
        elif "gyroscope" in k and "uncalibrat" not in k:
            gyro = vals
        elif "accelerometer" in k and "linear" not in k and "uncalibrat" not in k:
            acc = vals
    return lin, gyro, acc


def scale_for(level: str) -> float:
    """How many m/s² map to full bar half-width (smaller = more sensitive)."""
    if level == "ultra":
        return 0.12
    if level == "high":
        return 0.35
    return 1.2


def gyro_scale(level: str) -> float:
    if level == "ultra":
        return 0.08
    if level == "high":
        return 0.25
    return 0.8


# ── bidirectional soft bar ─────────────────────────────────────────────────

def bipolar_bar(
    value: float,
    half: int,
    full_scale: float,
    color_neg: str,
    color_pos: str,
    color_mid: str,
    use_color: bool,
) -> str:
    """
    Centered bar:  LEFT ─────●───── RIGHT
    value < 0 fills left; > 0 fills right. Soft block characters.
    """
    if full_scale <= 1e-9:
        full_scale = 1.0
    t = clamp(value / full_scale, -1.0, 1.0)
    # smooth ease for ASMR feel
    t = math.copysign(abs(t) ** 0.85, t)
    n = int(round(abs(t) * half))
    left = ["·"] * half
    right = ["·"] * half
    # gradient characters from soft to full
    blocks = "⣀⣄⣤⣦⣶⣷⣿"
    for i in range(n):
        # denser toward the tip (direction of motion)
        idx = min(len(blocks) - 1, int((i + 1) / max(1, n) * (len(blocks) - 1)))
        ch = blocks[idx]
        if t < 0:
            left[half - 1 - i] = ch
        else:
            right[i] = ch
    mid = "◆" if n > 0 else "◇"
    if use_color:
        ls = color_neg + "".join(left) + RESET
        rs = color_pos + "".join(right) + RESET
        m = color_mid + mid + RESET
    else:
        ls, rs, m = "".join(left), "".join(right), mid
    return f"{ls}{m}{rs}"


def sparkline(hist: Deque[float], width: int, use_color: str) -> str:
    if not hist:
        return "·" * width
    chars = " ˑ˙·░▒▓█"
    data = list(hist)[-width:]
    while len(data) < width:
        data.insert(0, 0.0)
    mx = max(max(data), 1e-6)
    out = []
    for v in data:
        idx = int(clamp(v / mx, 0, 1) * (len(chars) - 1))
        out.append(chars[idx])
    s = "".join(out)
    return f"{use_color}{s}{RESET}" if use_color else s


class MotionState:
    def __init__(self) -> None:
        self.samples = 0
        self.t0 = time.time()
        # smoothed axes (buttery)
        self.sx = self.sy = self.sz = 0.0
        self.gx = self.gy = self.gz = 0.0
        self.activity = 0.0
        self.last_lin: Optional[List[float]] = None
        self.trail: Deque[float] = deque(maxlen=48)
        self.dir_hold = "still"
        self.dir_hold_until = 0.0
        self.breath = 0.0  # idle pulse

    def update(
        self,
        lin: Optional[List[float]],
        gyro: Optional[List[float]],
        acc: Optional[List[float]],
        alpha: float = 0.28,
    ) -> Dict[str, Any]:
        lx = ly = lz = 0.0
        if lin and len(lin) >= 3:
            lx, ly, lz = float(lin[0]), float(lin[1]), float(lin[2])
        rx = ry = rz = 0.0
        if gyro and len(gyro) >= 3:
            rx, ry, rz = float(gyro[0]), float(gyro[1]), float(gyro[2])

        # EMA smooth — ASMR glide
        self.sx = ema(self.sx, lx, alpha)
        self.sy = ema(self.sy, ly, alpha)
        self.sz = ema(self.sz, lz, alpha)
        self.gx = ema(self.gx, rx, alpha)
        self.gy = ema(self.gy, ry, alpha)
        self.gz = ema(self.gz, rz, alpha)

        jerk = 0.0
        if lin and self.last_lin and len(lin) >= 3:
            jerk = mag3(
                float(lin[0]) - self.last_lin[0],
                float(lin[1]) - self.last_lin[1],
                float(lin[2]) - self.last_lin[2],
            )
        if lin and len(lin) >= 3:
            self.last_lin = [float(lin[0]), float(lin[1]), float(lin[2])]

        lm = mag3(self.sx, self.sy, self.sz)
        gm = mag3(self.gx, self.gy, self.gz)
        activity = math.sqrt(lm * lm + (gm * 1.8) ** 2 + (jerk * 2.5) ** 2)
        self.activity = ema(self.activity, activity, 0.35)
        self.trail.append(self.activity)
        self.samples += 1
        self.breath = 0.5 + 0.5 * math.sin(time.time() * 1.4)

        am = mag3(*(float(a) for a in acc[:3])) if acc and len(acc) >= 3 else 9.8

        return {
            "sx": self.sx,
            "sy": self.sy,
            "sz": self.sz,
            "gx": self.gx,
            "gy": self.gy,
            "gz": self.gz,
            "lin": lm,
            "gyro": gm,
            "jerk": jerk,
            "activity": self.activity,
            "acc": am,
            "raw_lin": (lx, ly, lz),
        }


def dominant_direction(sx: float, sy: float, sz: float, thr: float) -> Tuple[str, str]:
    """
    Phone coords (screen facing you, portrait):
      +X right, -X left
      +Y up (top of phone), -Y down
      +Z toward you (out of screen), -Z away (into table)
    """
    ax, ay, az = abs(sx), abs(sy), abs(sz)
    m = max(ax, ay, az)
    if m < thr:
        return "still", "quietly resting…"

    if ax >= ay and ax >= az:
        if sx > 0:
            return "right", "soft drift  →  right"
        return "left", "soft drift  ←  left"
    if ay >= ax and ay >= az:
        if sy > 0:
            return "up", "lifting  ↑  toward sky / top"
        return "down", "settling  ↓  toward ground"
    if sz > 0:
        return "toward", "drawing  ◎  toward you"
    return "away", "pressing  ○  into the world"


def mood_line(label: str, activity: float, breath: float) -> str:
    if label == "still":
        dots = "·" * (3 + int(breath * 3))
        return f"still space  {dots}"
    if activity < 0.08:
        return "a whisper of motion…"
    if activity < 0.25:
        return "gentle sway…"
    if activity < 0.8:
        return "clear movement…"
    if activity < 2.0:
        return "alive · flowing…"
    return "rush · cascade…"


def phone_glyph(sx: float, sy: float, sz: float, scale: float) -> List[str]:
    """Tiny 7x5 ASCII phone that leans with motion."""
    # map motion to cursor offset inside a small field
    cx, cy = 5, 2
    dx = int(clamp(sx / scale, -1, 1) * 3)
    dy = int(clamp(-sy / scale, -1, 1) * 1)  # up is -row
    px, py = cx + dx, cy + dy
    rows = [[" " for _ in range(11)] for _ in range(5)]
    # soft field dots
    for y in range(5):
        for x in range(11):
            if (x + y) % 2 == 0:
                rows[y][x] = "·"
    # phone body
    body = [
        "╭───╮",
        "│ · │",
        "│   │",
        "╰───╯",
    ]
    # place body centered at px,py
    for i, line in enumerate(body):
        yy = clamp(py - 1 + i, 0, 4)
        for j, ch in enumerate(line):
            xx = clamp(px - 2 + j, 0, 10)
            if 0 <= int(yy) < 5 and 0 <= int(xx) < 11:
                rows[int(yy)][int(xx)] = ch
    # motion comet
    if abs(sx) + abs(sy) + abs(sz) > scale * 0.15:
        tx = clamp(px + (1 if sx > 0 else -1 if sx < 0 else 0), 0, 10)
        ty = clamp(py + (-1 if sy > 0 else 1 if sy < 0 else 0), 0, 4)
        rows[int(ty)][int(tx)] = "✦"
    return ["".join(r) for r in rows]


def render(
    metrics: Dict[str, Any],
    state: MotionState,
    half: int,
    sensitivity: str,
    source: str,
    use_color: bool,
) -> str:
    sc = scale_for(sensitivity)
    gsc = gyro_scale(sensitivity)
    thr = sc * 0.12

    sx, sy, sz = metrics["sx"], metrics["sy"], metrics["sz"]
    key, phrase = dominant_direction(sx, sy, sz, thr)

    # hold direction label briefly so it doesn't flicker (ASMRy stability)
    now = time.time()
    if key != "still":
        state.dir_hold = key
        state.dir_hold_until = now + 0.35
    elif now > state.dir_hold_until:
        state.dir_hold = "still"
        phrase = "quietly resting…"
    else:
        # keep last phrase-ish
        key = state.dir_hold
        _, phrase = dominant_direction(sx, sy, sz, thr * 0.5)
        if key == "still":
            phrase = "quietly resting…"

    def c(code: str) -> str:
        return "" if not use_color else code

    def bipolar(val: float, cn: str, cp: str) -> str:
        return bipolar_bar(
            val, half, sc, c(cn), c(cp), c(C_SOFT), use_color
        )

    lr = bipolar(sx, C_ROSE, C_SKY)
    ud = bipolar(sy, C_PEACH, C_MINT)
    za = bipolar(sz, C_LILAC, C_GLOW)

    # gyro yaw-ish (z) for twist
    twist = bipolar_bar(state.gz, half, gsc, c(C_WARM), c(C_LILAC), c(C_SOFT), use_color)

    glyph = phone_glyph(sx, sy, sz, sc)
    mood = mood_line(key if metrics["activity"] >= thr else "still", metrics["activity"], state.breath)
    trail = sparkline(state.trail, half * 2 + 1, c(C_DIM) if use_color else "")

    # direction compass line
    compass = {
        "still": f"        {c(C_DIM)}· quiet ·{RESET if use_color else ''}",
        "left": f"   {c(C_ROSE)}◀ LEFT{RESET if use_color else 'LEFT'}     ",
        "right": f"        {c(C_SKY)}RIGHT ▶{RESET if use_color else 'RIGHT'}",
        "up": f"     {c(C_MINT)}▲ UP{RESET if use_color else 'UP'}      ",
        "down": f"    {c(C_PEACH)}▼ DOWN{RESET if use_color else 'DOWN'}    ",
        "toward": f"   {c(C_GLOW)}◎ toward you{RESET if use_color else 'toward'}",
        "away": f"   {c(C_LILAC)}○ into world{RESET if use_color else 'away'}",
    }.get(key, "")

    elapsed = int(time.time() - state.t0)
    title = f"{c(BOLD)}{c(C_SOFT)}motion veil{RESET if use_color else 'motion veil'}"
    meta = (
        f"{c(C_DIM)}[{source}]  n={state.samples}  {sensitivity}  t={elapsed}s  "
        f"act={metrics['activity']:.3f}{RESET if use_color else ''}"
    )

    # build soft frame
    w = half * 2 + 14
    edge = "─" * min(48, w)

    lines = [
        CLEAR + HIDE_CUR,
        f"  {title}  {meta}",
        f"  {c(C_DIM)}{edge}{RESET if use_color else ''}",
        f"",
        f"  {c(C_WARM)}{phrase}{RESET if use_color else phrase}",
        f"  {compass}",
        f"  {c(C_DIM)}{mood}{RESET if use_color else mood}",
        f"",
        f"  {c(C_DIM)}left ←{RESET if use_color else 'left'}  {lr}  {c(C_DIM)}→ right{RESET if use_color else 'right'}",
        f"  {c(C_DIM)}down ←{RESET if use_color else 'down'}  {ud}  {c(C_DIM)}→ up{RESET if use_color else 'up'}",
        f"  {c(C_DIM)}away ←{RESET if use_color else 'away'}  {za}  {c(C_DIM)}→ toward you{RESET if use_color else 'toward'}",
        f"  {c(C_DIM)}twist{RESET if use_color else 'twist'}  {twist}",
        f"",
        f"  {c(C_DIM)}flow {trail}{RESET if use_color else ''}",
        f"",
    ]
    for row in glyph:
        lines.append(f"       {c(C_GLOW)}{row}{RESET if use_color else row}")
    lines += [
        f"",
        f"  {c(C_DIM)}x={sx:+.3f}  y={sy:+.3f}  z={sz:+.3f}  m/s² (smoothed linear){RESET if use_color else ''}",
        f"  {c(C_DIM)}|a|={metrics['acc']:.2f} (gravity~9.8)  gyro={metrics['gyro']:.3f}{RESET if use_color else ''}",
        f"",
        f"  {c(C_DIM)}nudge · breathe · tilt — micro moves light the bars{RESET if use_color else ''}",
        f"  {c(C_DIM)}Ctrl+C to leave quietly{RESET if use_color else 'Ctrl+C quit'}",
    ]
    return "\n".join(lines) + SHOW_CUR


def run_http(
    bridge: str,
    token: str,
    interval: float,
    half: int,
    sensitivity: str,
    use_color: bool,
) -> None:
    state = MotionState()
    url = bridge.rstrip("/") + "/v1/sensors"
    print(f"{DIM}soft polling {url}{RESET}", file=sys.stderr)
    while True:
        try:
            data = http_get_json(url, token, timeout=max(1.0, interval * 4))
            if not isinstance(data, dict):
                time.sleep(interval)
                continue
            lin, gyro, acc = extract_from_sensors_map(data)
            metrics = state.update(lin, gyro, acc)
            sys.stdout.write(render(metrics, state, half, sensitivity, "live", use_color))
            sys.stdout.flush()
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as e:
            sys.stdout.write(f"{CLEAR}  waiting for bridge… {e}\n")
            sys.stdout.flush()
            time.sleep(1.0)
            continue
        time.sleep(interval)


def run_websocket(
    bridge: str,
    token: str,
    half: int,
    sensitivity: str,
    use_color: bool,
) -> None:
    try:
        import websocket  # type: ignore
    except ImportError as e:
        raise RuntimeError("websocket-client not installed") from e

    parsed = urlparse(bridge)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    q = urlencode({"token": token}) if token else ""
    ws_url = urlunparse((scheme, parsed.netloc, "/v1/stream/sensors", "", q, ""))

    state = MotionState()
    latest: Dict[str, Any] = {}
    print(f"{DIM}soft stream {ws_url}{RESET}", file=sys.stderr)

    def paint() -> None:
        lin, gyro, acc = extract_from_sensors_map(latest)
        if lin is None and gyro is None and acc is None:
            return
        metrics = state.update(lin, gyro, acc)
        sys.stdout.write(render(metrics, state, half, sensitivity, "stream", use_color))
        sys.stdout.flush()

    def on_message(_ws: Any, message: str) -> None:
        try:
            env = json.loads(message)
        except json.JSONDecodeError:
            return
        data = env.get("data")
        if not isinstance(data, dict):
            return
        for k, v in data.items():
            if isinstance(v, dict) and "values" in v:
                latest[k] = v
        if any("values" in (v or {}) for v in data.values() if isinstance(v, dict)):
            paint()
        else:
            # full map
            lin, gyro, acc = extract_from_sensors_map(data)
            if lin or gyro or acc:
                metrics = state.update(lin, gyro, acc)
                sys.stdout.write(render(metrics, state, half, sensitivity, "stream", use_color))
                sys.stdout.flush()

    headers = [f"Authorization: Bearer {token}"] if token else []
    ws = websocket.WebSocketApp(
        ws_url,
        header=headers,
        on_message=on_message,
        on_error=lambda _w, err: print(f"WS {err}", file=sys.stderr),
    )
    ws.run_forever(ping_interval=20, ping_timeout=10)


def main() -> int:
    args = parse_args()
    bridge = args.bridge.rstrip("/")
    token = args.token
    use_color = not args.no_color and sys.stdout.isatty()
    half = max(9, args.width)

    def try_health(tok: str) -> Any:
        return http_get_json(bridge + "/v1/health", tok, timeout=3.0)

    def prompt_token(reason: str) -> str:
        print(reason, file=sys.stderr)
        print("Device Bridge → Remote → copy token", file=sys.stderr)
        try:
            tok = getpass.getpass("Token (hidden): ").strip()
        except (EOFError, KeyboardInterrupt):
            print("", file=sys.stderr)
            return ""
        if not tok:
            try:
                tok = input("Token (visible): ").strip()
            except (EOFError, KeyboardInterrupt):
                return ""
        return tok

    def maybe_save_token(tok: str) -> None:
        path = os.path.expanduser("~/.device-bridge-token")
        if not tok or os.path.isfile(path):
            return
        try:
            ans = input(f"Save token to {path}? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return
        if ans in ("y", "yes"):
            try:
                with open(path, "w", encoding="utf-8") as f:
                    f.write(tok + "\n")
                os.chmod(path, 0o600)
                print(f"Saved {path}", file=sys.stderr)
            except OSError as ex:
                print(f"Could not save: {ex}", file=sys.stderr)

    health = None
    try:
        health = try_health(token)
    except urllib.error.HTTPError as e:
        if e.code == 401:
            token = prompt_token("Auth required (401).")
            if not token:
                print("No token.", file=sys.stderr)
                return 1
            try:
                health = try_health(token)
            except Exception as e2:
                print(f"Still cannot auth: {e2}", file=sys.stderr)
                return 1
            maybe_save_token(token)
        else:
            print(f"Cannot reach health: {e}", file=sys.stderr)
            return 1
    except Exception as e:
        print(f"Cannot reach {bridge}/v1/health: {e}", file=sys.stderr)
        print("Start Device Bridge, then retry.", file=sys.stderr)
        return 1

    print(
        f"Bridge OK v{health.get('version')}  "
        f"{'degraded' if health.get('degraded') else 'smooth'}  "
        f"sensitivity={args.sensitivity}",
        file=sys.stderr,
    )

    try:
        if not args.http_only:
            try:
                run_websocket(bridge, token, half, args.sensitivity, use_color)
                return 0
            except Exception as e:
                print(f"WebSocket skip ({e}); HTTP…", file=sys.stderr)
        run_http(bridge, token, args.interval, half, args.sensitivity, use_color)
    except KeyboardInterrupt:
        sys.stdout.write(SHOW_CUR + "\n  bye · soft exit\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
