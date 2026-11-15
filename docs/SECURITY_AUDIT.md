# Security audit log

Running log of security reviews and fixes. Public-safe: no secrets, private keys, or player data.

## 2026-11-16 pass

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 1 | Site admin (`website/ward.js`) | Admin login had no attempt limit: password brute force, and every try runs a slow hash (CPU DoS). | High | Fixed: 10 tries per IP per 15 min, 60 per minute overall (429), name/password length caps. |
| 2 | Explorer (`explorer/server.js`) | `/events` (SSE) had no connection cap: one client could hold thousands of open streams. | Medium | Fixed: 6 per IP, 800 total (429). |
| 3 | Explorer (`explorer/server.js`) | `/api/tx` forwarded every lookup to the RPC with no limit (RPC amplification). | Medium | Fixed: 30 per IP per minute (429). |
| 4 | Site pages (`website/server.js`) | Pages could be framed by any site (clickjacking) and sent full referrers. | Low | Fixed: `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`. |
| 5 | Plugin (`MoveServer.java`) | The local bag-move endpoint on `127.0.0.1:8790` accepted any POST, so a web page open in a browser on the server machine could trigger item moves (CSRF / DNS rebinding); body size was unbounded. | Medium | Fixed in source: requires `Content-Type: application/json`, no `Origin`, a local `Host`, body at most 4 KB. Live after the next plugin restart. |
| 6 | VPS plan (`docs/DEPLOY_VPS.md`) | Behind Caddy every browser player comes from `127.0.0.1`, which EaglerXServer exempts from its rate limits. | Medium (VPS only) | Documented: enable `forward_ip` + `forward_secret` on the VPS. |

Reviewed with no change needed: login gate before `/login` (commands with a `namespace:` prefix are also blocked,
chat/interact/inventory/drop/pickup are gated), real IP handling (`RealIp.java` trusts `X-Real-IP` only from
`network.trusted-proxies`), site `X-Forwarded-*` handling (trusted only from `TRUSTED_PROXIES`), EaglerXServer
HTTP size limits, rate limits and disabled voice.

Contracts: review only in this pass, no redeploy and no transactions.