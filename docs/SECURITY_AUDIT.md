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

Residual of this round (fixed in round 5): if a container write failed for a reason other than gas after its retries,
the container was still not rolled back. Round 5 snapshots the open container at the first trusted click and restores it.

### Round 4 (auctions, world items, rewards)

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 13 | Item frames, armor stands, pickups (`ItemSync.java`) | Putting an item into an item frame or onto an armor stand is a trusted bag write. With too little gas the write rolled back the bag while the frame or stand kept the item (dupe, same class as #12). A pickup with too little gas could be taken away again by the rollback. | High | Fixed: with too little gas, putting items into player-placed frames/stands, placing frames, and picking items up wait (plugin stands such as minions and NPC labels are not affected; Booster gas is sponsored). Live since the 2026-11-16 00:46 restart (UTC+7). |

Reviewed with no change needed: auctions (no bids on your own lot, a busy flag per lot during bids and settling, outbid
STT refunded by the contract's pull refund, items delivered only after the settle receipt), Buy-It-Now (busy flag
between buy and cancel, expired listings returned without a transaction, expiry skipped while a buy is in flight),
dropping items (drops are burned through the bag write, nothing lands in the world), deaths (keep-inventory on hub and
islands), quests (one linear step counter, each reward once), co-op join/leave (the player's own data is wiped on
both), guests (no break/place/containers/drops/armor stands).

### Round 5 (sponsor cap, world rollback, scoring/craft/XP/permissions)

| # | Area | Finding | Severity | Status |
|---|------|---------|----------|--------|
| 14 | Gas sponsor (`WalletService.java`) | Booster holders got 0.02 STT from the admin wallet every time the purse was short, with no cap. Exporting the key and moving the float out, then triggering another write, repeated it. | High | Fixed: `cookie.sponsor-daily-cap-stt` (default 0.2) is a rolling 24h cap per purse, persisted in `sponsor-topups.json`. Normal play stays sponsored (one 0.02 float covers a long run of writes). Cap hit is logged and the player is told to `/deposit` or wait 24 hours; `shortOnGas` then waits like a non-Booster. |
| 15 | Containers / frames / stands (`WordChain.java`, `ItemSync.java`) | Rounds 3–4 made these wait when gas was too low. If the bag write then failed for another reason (RPC after retries, exported-key player emptying the purse between check and send), the bag rolled back while the chest/furnace/hopper/ender chest/frame/stand kept the item (dupe). | High | Fixed: the first interaction of a pending trusted write snapshots that container/frame/stand (and newly placed frames/stands). A full rollback restores the snapshot (ender chest restore is kept for the next login if they left). Success drops the snapshot and does not restore. |
| 16 | Compression craft (`SkyCommands.java`) | `/craft cobble` and `/craft oak` dropped overflow on the ground, then signed the bag. A failed write put the 160 ingredients back in the bag while the enchanted item stayed on the ground (dupe). | High | Fixed: needs a free slot first; nothing is dropped. The enchanted-item counter increments only after the write lands. |
| 17 | Crafting table (`CraftGui.java`, `Skills.java`) | Crafting with leftovers in the grid granted Carpentry XP before the bag write. A later failed write rolled the bag back and kept the XP. The custom window also sits next to vanilla `CraftItemEvent` carpentry. | Medium | Fixed: leftover crafts wait for the close/quit write before XP. Vanilla `CraftItemEvent` skips `CraftGui.View`. Forge quest steps (`Market.forge`) wait for the bag write too. |
| 18 | SkyProfile writes (`SkillChain.java`) | Every 4 s batch with any pending XP sent `applyXp`, so a Booster mining slowly paid (sponsored) gas on a trickle of tiny writes. | Medium | Fixed: local XP is still instant; onchain `applyXp` waits 20 s so neighbouring gains ride in one write (a full 1,000,000 cap entry is sent at once). Sponsor-cap purses wait instead of bypassing the gas check. |

Reviewed with no change needed:

- Event score: `evXp` counts only this player's own collection part (co-op share cannot be funnelled). A profile reset or co-op join/leave bumps `epoch` and `awaitLoad`, so the website takes `max(best closed profile, current)` and never sums them. Shared IP/wallet tags are flagged. Dev-accounts (by name) and `event-excluded-wallets` are marked `dev`/`excluded` and dropped from public standings and prizes. Hub/island resource XP on player-placed blocks is gated by `words/placed.bin` (survives restart).
- Permissions: `plugin.yml` gives `somnia.admin` (default op) only to `/sbadmin`; the handler also requires that permission or console. Until `/login`, Auth blocks every command except `/register` `/login` `/changepassword` (including `namespace:` prefixes) and hides the rest from tab-complete. `/dev` and `/user` run only for `dev-accounts` after login; the command list hides them from everyone else. Gameplay commands (`/island`, `/craft`, `/bz`, `/ah`, `/exportkey`, `/coop`, …) are open to any logged-in player by design; `/exportkey` needs the password again and `/withdraw` spends only that player's purse.

Residual (not fixed in this pass):

| Item | Note |
|------|------|
| `/island reset` has no cooldown | Each reset rebuilds the starter island and refills the starter chest. Skills and collections stay, so the starter cobble/logs can be farmed again for event XP. A cooldown is a gameplay choice (do not change `/island reset` here). |
| Login lockout also locks the name | 5 wrong passwords lock the name (and IP) for a while. |
| Clicks on a chest while a write is already in flight | The snapshot is taken at the first click. Extra clicks during `bagBusy` ride the next write; if that next write is the first one that fails, those extra clicks follow the existing 2-tick batch rules. |
| Crash mid-write | An in-flight trusted write that never gets a receipt leaves the live container as vanilla left it; the next login reloads the bag from chain. Same class as a crash during a block write. |

### Open items (owner decision)

| Item | Note |
|------|------|
| `/island reset` has no cooldown | Each reset rebuilds the starter island and refills the starter chest (dirt, grass, cobblestone, water and lava buckets, bone meal). Low value, but repeatable; a cooldown would be a gameplay choice. |
| Login lockout also locks the name | 5 wrong passwords lock the name (and IP) for a while, which stops brute force but lets anyone lock a known name out for that time. |
