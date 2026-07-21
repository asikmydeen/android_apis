# Remote access: LAN, Tailscale, Cloudflare

Device Bridge is local-first (`127.0.0.1`). You can expose it safely with **token auth** plus **Tailscale** or **Cloudflare Tunnel**.

## Security model

| Mode | Bind | Auth | Who can connect |
|------|------|------|-----------------|
| **Local** | `127.0.0.1` | Optional | Only apps on this phone |
| **LAN** | `0.0.0.0` | **Forced** | Same Wi‑Fi (still use token) |
| **Tailscale** | `0.0.0.0` | **Forced** | Your tailnet only |
| **Cloudflare** | `127.0.0.1` | **Forced** | Anyone with URL **and** token |

Always send:

```bash
curl -s -H "Authorization: Bearer YOUR_TOKEN" https://YOUR_HOST/v1/health
# or
curl -s "https://YOUR_HOST/v1/health?token=YOUR_TOKEN"
```

Token lives in the app **Remote** tab (copy / rotate).

---

## A) Tailscale (recommended for “internet” without public exposure)

1. Install [Tailscale](https://tailscale.com/download/android) on the phone and your PC.
2. Sign in to the same tailnet; enable the VPN.
3. In Device Bridge → **Remote** → mode **Tailscale**.
4. **Start** the bridge.
5. Note the `http://100.x.x.x:8765` URL shown in the app.
6. From a PC on the tailnet:

```bash
export TOKEN=...
curl -s -H "Authorization: Bearer $TOKEN" http://100.x.x.x:8765/v1/snapshot | jq .
```

No open router ports. Traffic stays on your tailnet (unless you enable Tailscale Funnel — not required).

---

## B) Cloudflare Tunnel (public HTTPS)

Cloudflared runs in **Termux**, not inside the APK.

### Quick tunnel (ephemeral URL)

```bash
# Termux
pkg install cloudflared   # or download binary from Cloudflare

# Device Bridge: mode Cloudflare, Start bridge
cloudflared tunnel --url http://127.0.0.1:8765
```

Copy the `https://….trycloudflare.com` URL into the app **Public URL** field.

```bash
curl -s -H "Authorization: Bearer $TOKEN" https://XXXX.trycloudflare.com/v1/diagnostics
```

### Named tunnel (stable hostname)

Use a Cloudflare account + `cloudflared tunnel create` / config.yml pointing at `http://127.0.0.1:8765`.  
Same auth header required.

Helper script: `scripts/cloudflared-quick.sh`

---

## C) LAN only

1. Mode **LAN**, Start bridge.
2. Use `http://192.168.x.x:8765` from another device on Wi‑Fi.
3. Always use the token (guest Wi‑Fi is not safe without it).

---

## WebSocket with token

```bash
websocat "ws://100.x.x.x:8765/v1/stream?topics=location,battery&token=$TOKEN"
```

---

## Checklist

- [ ] Battery unrestricted  
- [ ] Bridge running (notification)  
- [ ] Mode set (Local / LAN / Tailscale / Cloudflare)  
- [ ] Token copied  
- [ ] Tailscale connected **or** cloudflared running  
- [ ] Test: `curl …/v1/health` returns JSON, not 401  

Never commit tokens. Rotate from the Remote tab if leaked.
