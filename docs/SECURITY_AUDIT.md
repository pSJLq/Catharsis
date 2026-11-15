# Security audit log

Running log of security reviews and fixes. Public-safe: no secrets, private keys, or player data.

## 2026-11-16 pass

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 1 | Site admin (`website/ward.js`) | Admin login had no attempt limit: password brute force, and every try runs a slow hash (CPU DoS). | High | Fixed: 10 tries per IP per 15 min, 60 per minute overall (429), name/password length caps. |
| 2 | Explorer (`explorer/server.js`) | `/events` (SSE) had no connection cap: one client could hold thousands of open streams. | Medium | Fixed: 6 per IP, 800 total (429). |
| 3 | Explorer (`explorer/server.js`) | `/api/tx` forwarded every lookup to the RPC with no limit (RPC amplification). | Medium | Fixed: 30 per IP per minute (429). |
| 4 | Site pages (`website/server.js`) | Pages could be framed by any site (clickjacking) and sent full referrers. | Low | Fixed: `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin`. |
| 5 | Plugin (`MoveServer.java`) | The local bag-move endpoint on `127.0.0.1:8790` accepted any POST, so a web page open in a browser on the server machine could trigger item moves (CSRF / DNS rebinding); body size was unbounded. | Medium | Fixed in source: requires `Content-Type: application/json`, no `Origin`, a local `Host`, body at most 4 KB. Live since the 2026-11-16 00:21 restart (UTC+7). |
| 6 | VPS plan (`docs/DEPLOY_VPS.md`) | Behind Caddy every browser player comes from `127.0.0.1`, which EaglerXServer exempts from its rate limits. | Medium (VPS only) | Documented: enable `forward_ip` + `forward_secret` on the VPS. |
| 7 | Explorer icons (`/icons/<name>.png`) | Any unknown name made two CDN requests every time, with no timeout or size check (outbound amplification, disk fill with non-images). | Low | Fixed: misses remembered, names up to 64 chars, CDN fetches limited to 20 per IP and 200 overall per minute, 8 s timeout, only PNGs up to 256 KB are stored. |
| 8 | Deed metadata (`/api/island/<id>`) | Every uncached id made up to four RPC calls, and the cache grew without bound with random ids. | Medium | Fixed: uncached lookups limited to 60 per IP and 600 overall per minute; cache capped at 5000 entries. |
| 9 | Site `/api/join` | Every call opened a TCP connection to Paper (a flood of page calls became a connection flood on the game port). | Low | Fixed: one shared probe per 5 seconds. |
| 10 | Explorer `/api/move` proxy | Request body was read without a size limit (the route is closed while Word is set, but the code was unbounded). | Low | Fixed: 16 KB cap. |

Reviewed with no change needed: login gate before `/login` (commands with a `namespace:` prefix are also blocked,
chat/interact/inventory/drop/pickup are gated), real IP handling (`RealIp.java` trusts `X-Real-IP` only from
`network.trusted-proxies`), site `X-Forwarded-*` handling (trusted only from `TRUSTED_PROXIES`), EaglerXServer
HTTP size limits, rate limits and disabled voice.

Contracts: review only in this pass, no redeploy and no transactions.