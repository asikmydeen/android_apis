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
        if not path:
            continue
        tok = _read_first_line(path)
        if tok:
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
        if not path:
            continue
        url = _read_first_line(path)
        if url:
            return url
    return ""


def normalize_bridge_url(raw: str) -> str:
    """Accept 100.x.x.x, 100.x.x.x:8765, or full http URL."""
    s = (raw or "").strip().rstrip("/")
    if not s:
        return ""
    if s.startswith("http://") or s.startswith("https://"):
        return s
    # bare host or host:port
    if "://" not in s:
        if ":" not in s:
            s = f"{s}:8765"
        return f"http://{s}"
    return s


def is_localhost_bridge(url: str) -> bool:
    try:
        host = urlparse(url).hostname or ""
    except Exception:
        host = ""
    return host in ("127.0.0.1", "localhost", "::1")


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Soft live motion UI for Device Bridge",
        epilog=(
            "Remote (another PC): script will ask for Tailscale URL if localhost fails. "
            "Or: --bridge http://100.x.x.x:8765  /  env BRIDGE  /  ~/.device-bridge-url"
        ),
    )
    default_bridge = (
        os.environ.get("BRIDGE")
        or load_bridge_from_files()
        or "http://127.0.0.1:8765"
    )
    p.add_argument(
        "--bridge",
        default=default_bridge,
        help="Base URL (env BRIDGE, ~/.device-bridge-url, or default localhost)",
    )
    p.add_argument(
        "--token",
        default=os.environ.get("BRIDGE_TOKEN")
        or os.environ.get("TOKEN")
        or load_token_from_files()
        or "",
    )
    p.add_argument(
        "--ask-bridge",
        action="store_true",
        help="Always prompt for bridge URL (useful from another machine)",
    )
    p.add_argument("--http-only", action="store_true")
    p.add_argument(
        "--interval",
        type=float,
        default=None,
        help="HTTP poll interval seconds (default: 0.04 local, 0.08 remote)",
    )
    p.add_argument(
        "--timeout",
        type=float,
        default=None,
        help="HTTP timeout seconds (default: 3 local, 12 remote Tailscale)",
    )
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


class BridgeHttpClient:
    """
    Keep-alive HTTP client. Reusing one TCP connection avoids Tailscale/handshake
    spikes that show up as random timeouts when polling every few ms.
    """

    def __init__(self, base: str, token: str, timeout: float = 8.0) -> None:
        self.base = base.rstrip("/")
        self.token = token
        self.timeout = timeout
        parsed = urlparse(self.base)
        self.scheme = parsed.scheme or "http"
        self.host = parsed.hostname or "127.0.0.1"
        self.port = parsed.port or (443 if self.scheme == "https" else 80)
        self._conn: Any = None
        self._fail_streak = 0

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    def _connect(self) -> Any:
        import http.client

        self.close()
        if self.scheme == "https":
            conn = http.client.HTTPSConnection(self.host, self.port, timeout=self.timeout)
        else:
            conn = http.client.HTTPConnection(self.host, self.port, timeout=self.timeout)
        self._conn = conn
        return conn

    def get_json(self, path: str) -> Any:
        import http.client

        path = path if path.startswith("/") else "/" + path
        if self.token:
            sep = "&" if "?" in path else "?"
            path = f"{path}{sep}{urlencode({'token': self.token})}"
        headers = {"Connection": "keep-alive", "Accept": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"

        last_err: Optional[Exception] = None
        for attempt in range(3):
            try:
                conn = self._conn or self._connect()
                conn.request("GET", path, headers=headers)
                resp = conn.getresponse()
                body = resp.read()
                if resp.status == 401:
                    raise urllib.error.HTTPError(
                        self.base + path, 401, "Unauthorized", resp.headers, None  # type: ignore
                    )
                if resp.status >= 400:
                    raise urllib.error.HTTPError(
                        self.base + path, resp.status, resp.reason, resp.headers, None  # type: ignore
                    )
                self._fail_streak = 0
                return json.loads(body.decode("utf-8"))
            except urllib.error.HTTPError:
                raise
            except Exception as e:
                last_err = e
                self._fail_streak += 1
                self.close()
                if attempt < 2:
                    time.sleep(0.15 * (attempt + 1))
                    continue
                raise TimeoutError(f"{type(e).__name__}: {e}") from e
        raise TimeoutError(str(last_err) if last_err else "request failed")


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
    """m/s² that maps to full visual deflection (smaller = more sensitive)."""
    if level == "ultra":
        return 0.10
    if level == "high":
        return 0.28
    return 1.0


# Soft palette indices (256-color) — calm dusk / water glass
PAL_DUST = (60, 61, 67, 103, 109, 146, 152, 189, 195, 231)
PAL_AURORA = (54, 55, 61, 97, 103, 140, 146, 183, 189, 225)


def fg(n: int) -> str:
    return f"\033[38;5;{n}m"


class Particle:
    __slots__ = ("a", "b", "c", "phase", "speed", "size")

    def __init__(self, i: int) -> None:
        # quasi-random stable seeds from index
        r = math.sin(i * 12.9898) * 43758.5453
        r = r - math.floor(r)
        self.a = r * math.pi * 2
        r2 = math.sin(i * 78.233) * 43758.5453
        r2 = r2 - math.floor(r2)
        self.b = r2 * math.pi * 2
        r3 = math.sin(i * 45.164) * 43758.5453
        r3 = r3 - math.floor(r3)
        self.c = r3 * math.pi * 2
        self.phase = r * 6.28
        self.speed = 0.35 + r2 * 0.9
        self.size = 0.4 + r3 * 0.6


class MotionState:
    def __init__(self) -> None:
        self.samples = 0
        self.t0 = time.time()
        self.sx = self.sy = self.sz = 0.0
        self.gx = self.gy = self.gz = 0.0
        self.activity = 0.0
        self.last_lin: Optional[List[float]] = None
        self.trail: Deque[float] = deque(maxlen=64)
        self.dir_hold = "still"
        self.dir_hold_until = 0.0
        self.breath = 0.0
        # 4th dimension = integrated rotation / time swirl
        self.w = 0.0
        self.particles = [Particle(i) for i in range(48)]
        self.path: Deque[Tuple[float, float]] = deque(maxlen=28)  # projected trail

    def update(
        self,
        lin: Optional[List[float]],
        gyro: Optional[List[float]],
        acc: Optional[List[float]],
        alpha: float = 0.22,
    ) -> Dict[str, Any]:
        lx = ly = lz = 0.0
        if lin and len(lin) >= 3:
            lx, ly, lz = float(lin[0]), float(lin[1]), float(lin[2])
        rx = ry = rz = 0.0
        if gyro and len(gyro) >= 3:
            rx, ry, rz = float(gyro[0]), float(gyro[1]), float(gyro[2])

        # very soft EMA for glass-smooth motion
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
        activity = math.sqrt(lm * lm + (gm * 1.8) ** 2 + (jerk * 2.2) ** 2)
        self.activity = ema(self.activity, activity, 0.28)
        self.trail.append(self.activity)
        self.samples += 1
        t = time.time()
        self.breath = 0.5 + 0.5 * math.sin(t * 0.9)

        # 4th axis: slow swirl from gyro + time (always gently alive)
        self.w = ema(self.w, self.gz * 0.85 + self.gx * 0.2, 0.15)
        self.w += 0.012  # eternal soft drift in w

        am = mag3(*(float(a) for a in acc[:3])) if acc and len(acc) >= 3 else 9.8

        return {
            "sx": self.sx,
            "sy": self.sy,
            "sz": self.sz,
            "gx": self.gx,
            "gy": self.gy,
            "gz": self.gz,
            "w": self.w,
            "lin": lm,
            "gyro": gm,
            "jerk": jerk,
            "activity": self.activity,
            "acc": am,
        }


def dominant_direction(sx: float, sy: float, sz: float, thr: float) -> Tuple[str, str]:
    ax, ay, az = abs(sx), abs(sy), abs(sz)
    m = max(ax, ay, az)
    if m < thr:
        return "still", "the field is calm"
    if ax >= ay and ax >= az:
        return ("right", "flowing right  →") if sx > 0 else ("left", "flowing left  ←")
    if ay >= ax and ay >= az:
        return ("up", "rising  ↑") if sy > 0 else ("down", "sinking  ↓")
    return ("toward", "blooming toward you  ◎") if sz > 0 else ("away", "dissolving away  ○")


def project_4d(
    x: float,
    y: float,
    z: float,
    w: float,
    t: float,
    width: int,
    height: int,
) -> Tuple[int, int, float]:
    """
    Map 4D-ish point to 2D terminal cell.
    Rotation in XW / YZ planes for a living hyperspace feel.
    """
    # unit-ish coordinates in -1..1
    # rotate (x,w) and (y,z)
    a = t * 0.35 + w * 0.8
    ca, sa = math.cos(a), math.sin(a)
    x1 = x * ca - w * sa
    w1 = x * sa + w * ca
    b = t * 0.22 + w * 0.5
    cb, sb = math.cos(b), math.sin(b)
    y1 = y * cb - z * sb
    z1 = y * sb + z * cb

    # perspective on z1
    depth = 2.2 + z1 * 0.9 + w1 * 0.15
    if depth < 0.35:
        depth = 0.35
    px = x1 / depth
    py = y1 / depth

    col = int((px * 0.45 + 0.5) * (width - 1))
    row = int((-py * 0.45 + 0.5) * (height - 1))
    col = int(clamp(col, 0, width - 1))
    row = int(clamp(row, 0, height - 1))
    # brightness from depth (closer = brighter)
    bright = clamp(1.35 - depth * 0.35, 0.0, 1.0)
    return col, row, bright


def render_field(
    state: MotionState,
    sx: float,
    sy: float,
    sz: float,
    w_axis: float,
    scale: float,
    width: int,
    height: int,
    use_color: bool,
) -> List[str]:
    """Living particle cloud driven by motion (4 axes: x,y,z,w)."""
    t = time.time() - state.t0
    # motion offsets pull the whole cloud
    ox = clamp(sx / scale, -1.5, 1.5)
    oy = clamp(sy / scale, -1.5, 1.5)
    oz = clamp(sz / scale, -1.5, 1.5)
    breath = 0.85 + 0.15 * state.breath + clamp(state.activity * 0.4, 0, 0.35)

    # grid of chars + brightness
    grid = [[" " for _ in range(width)] for _ in range(height)]
    bright = [[0.0 for _ in range(width)] for _ in range(height)]

    # soft background star dust (always drifting)
    for i in range(width * height // 7):
        u = (math.sin(i * 3.1 + t * 0.15) + 1) * 0.5
        v = (math.cos(i * 2.7 - t * 0.11) + 1) * 0.5
        c = int(u * (width - 1))
        r = int(v * (height - 1))
        if grid[r][c] == " ":
            grid[r][c] = "·"
            bright[r][c] = 0.12 + 0.08 * math.sin(t + i)

    glyphs = "·˙⋆✦✧ entrop"  # last chars unused; pick by brightness
    gset = ["·", "˙", "⋆", "∙", "∘", "○", "◎", "✦", "✧", "❋"]

    for i, p in enumerate(state.particles):
        # particle orbits in 4D torus-like space
        ang = p.phase + t * p.speed + w_axis * 0.4
        rad = (0.35 + p.size * 0.55) * breath
        # base on sphere/torus
        x = math.cos(ang + p.a) * rad + ox * 0.55
        y = math.sin(ang * 0.9 + p.b) * rad * 0.85 + oy * 0.55
        z = math.sin(ang * 0.7 + p.c) * rad * 0.7 + oz * 0.55
        ww = math.cos(ang * 0.5 + p.a) * 0.5 + w_axis * 0.15

        col, row, br = project_4d(x, y, z, ww, t, width, height)
        # activity blooms particles
        br = clamp(br + state.activity * 0.5, 0, 1)
        if br > bright[row][col]:
            bright[row][col] = br
            gi = int(br * (len(gset) - 1))
            grid[row][col] = gset[gi]

    # center “you” beacon — pulled by motion
    cx = int(clamp((ox * 0.35 + 0.5) * (width - 1), 0, width - 1))
    cy = int(clamp((-oy * 0.35 + 0.5) * (height - 1), 0, height - 1))
    state.path.append((cx, cy))
    for k, (px, py) in enumerate(state.path):
        fade = (k + 1) / max(1, len(state.path))
        if bright[py][px] < fade * 0.7:
            bright[py][px] = fade * 0.7
            grid[py][px] = "·" if fade < 0.5 else "∘"
    grid[cy][cx] = "◉"
    bright[cy][cx] = 1.0

    # 4D axis guides at edges (subtle)
    mid_r, mid_c = height // 2, width // 2
    # horizontal = X
    for c in range(width):
        if grid[mid_r][c] == " ":
            grid[mid_r][c] = "─" if abs(c - mid_c) > 2 else "┼"
            bright[mid_r][c] = max(bright[mid_r][c], 0.15)
    # vertical = Y
    for r in range(height):
        if grid[r][mid_c] in (" ", "─"):
            grid[r][mid_c] = "│" if abs(r - mid_r) > 1 else "┼"
            bright[r][mid_c] = max(bright[r][mid_c], 0.15)

    lines: List[str] = []
    for r in range(height):
        parts: List[str] = []
        for c in range(width):
            ch = grid[r][c]
            br = bright[r][c]
            if not use_color or ch == " ":
                parts.append(ch)
                continue
            # pick palette by depth/brightness + slight hue shift from w
            pal = PAL_AURORA if (math.sin(w_axis + r * 0.1) > 0) else PAL_DUST
            idx = int(clamp(br, 0, 1) * (len(pal) - 1))
            parts.append(f"{fg(pal[idx])}{ch}{RESET}")
        lines.append("".join(parts))
    return lines


def soft_axis_row(
    label_neg: str,
    label_pos: str,
    value: float,
    scale: float,
    width: int,
    use_color: bool,
) -> str:
    """Minimal calm meter under the field."""
    half = width // 2
    t = clamp(value / max(scale, 1e-6), -1, 1)
    t = math.copysign(abs(t) ** 0.8, t)
    n = int(round(abs(t) * (half - 1)))
    cells = ["·"] * width
    mid = half
    cells[mid] = "│"
    fill = "━"
    if t < 0:
        for i in range(n):
            cells[mid - 1 - i] = fill
    elif t > 0:
        for i in range(n):
            cells[mid + 1 + i] = fill
    bar = "".join(cells)
    if use_color:
        bar = f"{C_DIM}{bar}{RESET}"
    return f"  {C_DIM if use_color else ''}{label_neg:>5}{RESET if use_color else ''} {bar} {C_DIM if use_color else ''}{label_pos:<5}{RESET if use_color else ''}"


def render(
    metrics: Dict[str, Any],
    state: MotionState,
    half: int,
    sensitivity: str,
    source: str,
    use_color: bool,
) -> str:
    sc = scale_for(sensitivity)
    thr = sc * 0.10
    sx, sy, sz = metrics["sx"], metrics["sy"], metrics["sz"]
    w_axis = metrics.get("w", state.w)

    key, phrase = dominant_direction(sx, sy, sz, thr)
    now = time.time()
    if key != "still":
        state.dir_hold = key
        state.dir_hold_until = now + 0.45
    elif now > state.dir_hold_until:
        state.dir_hold = "still"
        phrase = "the field is calm"
    else:
        key = state.dir_hold
        if key != "still":
            _, phrase = dominant_direction(sx, sy, sz, thr * 0.4)

    # field size: wide and tall for immersion
    field_w = max(48, half * 2 + 20)
    field_h = 16
    field = render_field(state, sx, sy, sz, w_axis, sc, field_w, field_h, use_color)

    elapsed = int(time.time() - state.t0)
    title = f"{BOLD}{C_SOFT}4·d  soft field{RESET}" if use_color else "4·d  soft field"
    meta = (
        f"{C_DIM}[{source}]  {sensitivity}  t={elapsed}s  "
        f"act={metrics['activity']:.3f}  w={w_axis:.2f}{RESET}"
        if use_color
        else f"[{source}] act={metrics['activity']:.3f}"
    )

    # gentle whisper line
    whispers = {
        "still": "breathe  ·  everything floats",
        "left": "a tide to the left",
        "right": "a tide to the right",
        "up": "lifting like warm air",
        "down": "settling like snow",
        "toward": "the cloud leans into you",
        "away": "the cloud slips into the dark",
    }
    whisper = whispers.get(key, phrase)

    axis_w = min(41, field_w - 4)
    lines = [
        CLEAR + HIDE_CUR,
        f"  {title}   {meta}",
        f"  {C_WARM if use_color else ''}{whisper}{RESET if use_color else ''}",
        f"  {C_DIM if use_color else ''}x y z  ·  w = time·twist (4th axis){RESET if use_color else ''}",
        "",
    ]
    # frame the field
    border = "─" * field_w
    lines.append(f"  {C_DIM if use_color else ''}╭{border}╮{RESET if use_color else ''}")
    for row in field:
        lines.append(f"  {C_DIM if use_color else ''}│{RESET if use_color else ''}{row}{C_DIM if use_color else ''}│{RESET if use_color else ''}")
    lines.append(f"  {C_DIM if use_color else ''}╰{border}╯{RESET if use_color else ''}")
    lines += [
        "",
        soft_axis_row("left", "right", sx, sc, axis_w, use_color),
        soft_axis_row("down", "up", sy, sc, axis_w, use_color),
        soft_axis_row("away", "near", sz, sc, axis_w, use_color),
        soft_axis_row("back", "spin", state.gz, sc * 0.8, axis_w, use_color),
        "",
        f"  {C_DIM if use_color else ''}◉ you   ✦ particles in 4d   │ cross = x/y plane{RESET if use_color else ''}",
        f"  {C_DIM if use_color else ''}move the phone · the cloud follows · micro motion still counts{RESET if use_color else ''}",
        f"  {C_DIM if use_color else ''}Ctrl+C  soft exit{RESET if use_color else ''}",
    ]
    return "\n".join(lines) + SHOW_CUR



def explain_wait(err: BaseException, bridge: str, fail_streak: int) -> str:
    remote = not is_localhost_bridge(bridge)
    tips = [
        f"waiting for bridge… ({type(err).__name__}: {err})",
        f"url={bridge}  fails_in_a_row={fail_streak}",
        "",
    ]
    if remote:
        tips += [
            "Common on Tailscale (usually temporary):",
            "  • phone slept / Doze paused the app — unlock phone, keep bridge notification",
            "  • Tailscale reconnecting (Wi‑Fi↔cell) or DERP relay blip",
            "  • request timeout too short — script uses longer remote timeouts now",
            "  • set battery Unrestricted for Device Bridge on Samsung",
            "",
            "If it never recovers: open phone, Start bridge, check Tailscale is Connected.",
        ]
    else:
        tips += [
            "Local bridge not answering — is Device Bridge Started?",
        ]
    return CLEAR + "\n  ".join([""] + tips) + "\n"


def run_http(
    bridge: str,
    token: str,
    interval: float,
    half: int,
    sensitivity: str,
    use_color: bool,
    timeout: float,
) -> None:
    state = MotionState()
    client = BridgeHttpClient(bridge, token, timeout=timeout)
    remote = not is_localhost_bridge(bridge)
    print(
        f"{DIM}polling {bridge}/v1/sensors  interval={interval:.0f}ms  timeout={timeout:.0f}s"
        f"{'  (remote/Tailscale)' if remote else ''}{RESET}",
        file=sys.stderr,
    )
    try:
        while True:
            try:
                data = client.get_json("/v1/sensors")
                if not isinstance(data, dict):
                    time.sleep(interval)
                    continue
                lin, gyro, acc = extract_from_sensors_map(data)
                metrics = state.update(lin, gyro, acc)
                sys.stdout.write(render(metrics, state, half, sensitivity, "live", use_color))
                sys.stdout.flush()
                time.sleep(interval)
            except urllib.error.HTTPError as e:
                if e.code == 401:
                    sys.stdout.write(
                        f"{CLEAR}  auth failed (401) — token expired? rotate in app Remote tab\n"
                    )
                    sys.stdout.flush()
                    time.sleep(2.0)
                    continue
                sys.stdout.write(explain_wait(e, bridge, client._fail_streak))
                sys.stdout.flush()
                time.sleep(min(5.0, 0.5 * max(1, client._fail_streak)))
            except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError, ConnectionError) as e:
                sys.stdout.write(explain_wait(e, bridge, client._fail_streak))
                sys.stdout.flush()
                # backoff so we don't spam a sleeping phone
                time.sleep(min(5.0, 0.4 * max(1, client._fail_streak)))
    finally:
        client.close()


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
    bridge = normalize_bridge_url(args.bridge) or "http://127.0.0.1:8765"
    token = args.token
    use_color = not args.no_color and sys.stdout.isatty()
    half = max(9, args.width)

    # Timeouts: localhost can be tight; Tailscale needs headroom (relay, phone wake)
    remote = not is_localhost_bridge(bridge)
    timeout = args.timeout if args.timeout is not None else (12.0 if remote else 3.0)
    interval = args.interval if args.interval is not None else (0.08 if remote else 0.04)

    def try_health(base: str, tok: str) -> Any:
        # remote health also uses longer timeout
        t = 12.0 if not is_localhost_bridge(base) else 3.0
        return http_get_json(base.rstrip("/") + "/v1/health", tok, timeout=t)

    def prompt_token(reason: str) -> str:
        print(reason, file=sys.stderr)
        print("Phone app → Remote tab → copy token", file=sys.stderr)
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

    def prompt_bridge(reason: str, current: str) -> str:
        print(reason, file=sys.stderr)
        print("", file=sys.stderr)
        print("On the phone (Device Bridge → Remote → Tailscale mode):", file=sys.stderr)
        print("  copy the URL like  http://100.x.x.x:8765", file=sys.stderr)
        print("You can also paste just  100.x.x.x  or  100.x.x.x:8765", file=sys.stderr)
        print("", file=sys.stderr)
        default_hint = current if not is_localhost_bridge(current) else ""
        prompt = "Bridge URL"
        if default_hint:
            prompt += f" [{default_hint}]"
        prompt += ": "
        try:
            raw = input(prompt).strip()
        except (EOFError, KeyboardInterrupt):
            print("", file=sys.stderr)
            return ""
        if not raw and default_hint:
            raw = default_hint
        return normalize_bridge_url(raw)

    def maybe_save(path: str, value: str, label: str) -> None:
        if not value or os.path.isfile(path):
            return
        try:
            ans = input(f"Save {label} to {path} for next time? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return
        if ans in ("y", "yes"):
            try:
                parent = os.path.dirname(path)
                if parent:
                    os.makedirs(parent, exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(value + "\n")
                os.chmod(path, 0o600)
                print(f"Saved {path}", file=sys.stderr)
            except OSError as ex:
                print(f"Could not save: {ex}", file=sys.stderr)

    def maybe_save_token(tok: str) -> None:
        maybe_save(os.path.expanduser("~/.device-bridge-token"), tok, "token")

    def maybe_save_bridge(url: str) -> None:
        if is_localhost_bridge(url):
            return
        maybe_save(os.path.expanduser("~/.device-bridge-url"), url, "Tailscale/bridge URL")

    # Always ask if requested; otherwise try localhost/saved URL first
    if args.ask_bridge or (
        not os.environ.get("BRIDGE")
        and is_localhost_bridge(bridge)
        and not load_bridge_from_files()
        and sys.stdin.isatty()
    ):
        # From another machine, localhost never works — probe quickly
        probe_fail = False
        if is_localhost_bridge(bridge):
            try:
                try_health(bridge, token)
            except urllib.error.HTTPError:
                probe_fail = False  # server exists (auth later)
            except Exception:
                probe_fail = True
        if args.ask_bridge or probe_fail:
            new_b = prompt_bridge(
                "No Device Bridge on localhost (normal on another PC)."
                if probe_fail
                else "Enter Device Bridge address.",
                bridge,
            )
            if not new_b:
                print("No bridge URL entered.", file=sys.stderr)
                return 1
            bridge = new_b

    health = None
    for attempt in range(3):
        try:
            health = try_health(bridge, token)
            break
        except urllib.error.HTTPError as e:
            if e.code == 401:
                token = prompt_token("Auth required (401).")
                if not token:
                    print("No token.", file=sys.stderr)
                    return 1
                try:
                    health = try_health(bridge, token)
                    maybe_save_token(token)
                    break
                except urllib.error.HTTPError as e2:
                    if e2.code == 401:
                        print("Token rejected. Check Remote tab / rotate.", file=sys.stderr)
                        token = ""
                        continue
                    print(f"Still cannot auth: {e2}", file=sys.stderr)
                    return 1
                except Exception as e2:
                    print(f"Still cannot reach bridge: {e2}", file=sys.stderr)
                    # fall through to ask URL
            else:
                print(f"HTTP {e.code} from {bridge}/v1/health", file=sys.stderr)
                return 1
        except Exception as e:
            print(f"Cannot reach {bridge}/v1/health: {e}", file=sys.stderr)
            if not sys.stdin.isatty():
                print("Start Device Bridge, or set BRIDGE=http://100.x.x.x:8765", file=sys.stderr)
                return 1
            new_b = prompt_bridge(
                "Could not connect. Paste the phone's Tailscale URL from Device Bridge → Remote.",
                bridge,
            )
            if not new_b:
                print("No bridge URL entered.", file=sys.stderr)
                return 1
            bridge = new_b
            continue

    if health is None:
        print("Could not connect to Device Bridge.", file=sys.stderr)
        return 1

    maybe_save_bridge(bridge)

    # Recompute remote after possible URL prompt
    remote = not is_localhost_bridge(bridge)
    timeout = args.timeout if args.timeout is not None else (12.0 if remote else 3.0)
    interval = args.interval if args.interval is not None else (0.08 if remote else 0.04)

    print(
        f"Bridge OK {bridge}  v{health.get('version')}  "
        f"{'degraded' if health.get('degraded') else 'smooth'}  "
        f"sensitivity={args.sensitivity}  timeout={timeout:.0f}s",
        file=sys.stderr,
    )

    try:
        if not args.http_only:
            try:
                run_websocket(bridge, token, half, args.sensitivity, use_color)
                return 0
            except Exception as e:
                print(f"WebSocket skip ({e}); HTTP…", file=sys.stderr)
        run_http(
            bridge,
            token,
            interval,
            half,
            args.sensitivity,
            use_color,
            timeout=timeout,
        )
    except KeyboardInterrupt:
        sys.stdout.write(SHOW_CUR + "\n  bye · soft exit\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
