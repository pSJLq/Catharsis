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

### Round 2 review (plugin, no code change needed)

- GUIs: `Market.View`, `SbMenu.View` and `Menu` cancel every click and drag (shift-click, number keys and double-click
  included); the trade window only accepts items in the player's own offer slots, and the bag is re-shown from the
  chain when a trade ends. Trades are closed while Word mode is on.
- Merchant: a sale removes the item before coins are paid, coins come only after the bag write lands, and buy-back
  removes its entry after paying; everything runs on the main thread, so double clicks cannot pay twice.
- Commands are not written to the server log (`spigot.yml commands.log: false`), so `/login`, `/register` and
  `/exportkey confirm <password>` passwords stay out of the logs. Key export needs the password again.
- Chat and click actions use plain text components; player names are checked by Paper (letters, digits, underscore).
- EaglerXServer: skin downloads only from `textures.minecraft.net`, skin/cape/brand/webview lookups rate limited,
  voice off; the plugin registers no plugin-message channels of its own.

### Round 3 (bag writes, market, minions)

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 11 | Bag writes (`WordChain.java`) | A trusted bag write that touches more than 8 slots goes out in several batches. When the first batch landed and a later one failed (for example a purse with gas for exactly one write), the market callbacks were told "rolled back": `/bz claim` put everything back in the stash although part of it was already signed into the bag (a repeatable dupe; refunds behaved the same). | High | Fixed: a partly landed write is never reported as rolled back. With the player online the rest is signed later (every 10 s, waiting for gas); if it cannot finish, the waiting callbacks are dropped and logged instead of refunded. Live since the 2026-11-16 00:40 restart (UTC+7). |
| 12 | Containers (`WordChain.java`) | Moving items into a chest, furnace, hopper or ender chest is a trusted bag write. With too little gas the write failed and the bag went back to its last signed state while the container kept the items (dupe). | High | Fixed: with too little gas, container clicks and drags wait (same rule as breaking and placing blocks). Booster holders are not affected (their gas is sponsored). Live since the 00:40 restart. |

Reviewed with no change needed: Bazaar `buyOffer` / `sellToOrder` / `cancel` (a busy flag per offer/order plus a per-player lock, items taken before the
bag write and returned on failure, everything on the main thread), stash claim, minions (owner only; storage
decremented by what actually moved), SkyWord2 / Attested contracts (bag writes at most 8 slots, the menu-star slot
can never hold an item; exported keys need a server EIP-712 attestation bound to player, contract, chain, a one-shot
nonce and a deadline, so attestations cannot be replayed).

Residual (not fixed, needs a bigger change): if a container write fails for a reason other than gas after its retries
(RPC error, or a player with an exported key draining the purse between the check and the send), the container is
still not rolled back. A full fix snapshots the open container at the first trusted click and restores it on rollback.

### Open items (owner decision)

| Item | Note |
|------|------|
| Gas sponsorship for Booster holders has no cap | A Booster holder whose purse is short on gas gets a 0.02 STT float from the admin wallet each time. Withdrawing or exporting the key and moving the float out, then triggering another write, repeats it. A daily cap per purse would stop draining without changing normal play. |
| Login lockout also locks the name | 5 wrong passwords lock the name (and IP) for a while, which stops brute force but lets anyone lock a known name out for that time. |
