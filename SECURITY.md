# Security policy

## Reporting a vulnerability

Please **do not** open a public issue for a security problem in the contracts, the plugin, the website, or the explorer.

Report it privately:

1. Use **GitHub Private Vulnerability Reporting** on this repository (Security → Report a vulnerability), or
2. If that is not enabled yet, contact the repository owner through GitHub and wait for a reply before publishing details.

We will acknowledge the report, assess the impact, and coordinate a fix and disclosure.

## Scope

In scope:

- Smart contracts in `contracts/` and the addresses listed in the README
- The Paper plugin in `src/`
- The website and explorer sources in this repository

Out of scope:

- Loss of STT because a player shared their password or drained their own game wallet
- Issues that only exist in third-party RPC providers
- Reports that include no reproduction steps

## Wallet keys: two modes

Every player has a game wallet that sends their transactions and pays their gas.

**Mode A (default, key kept on the server).** The private key exists only on the game server, sealed with the
player's password (Argon2id + AES-256-GCM) and decrypted into memory for the session. Nobody else can send from that
wallet, so routine writes (inventory layout in SkyWord2, skill XP in SkySkills3, stats checkpoints, island counters,
SkyHold2 syncs that do not mint) go without a server signature at normal gas. Hard limits still apply onchain:
at most 1,000,000 per XP/collection entry and 20,000,000 per rolling hour (SkySkills3), hotbar slot 9 (index 8,
the SkyBlock Menu star) can never hold an item (SkyWord2), and the server re-checks everything it reads back.

**Mode B (key exported with `/exportkey`).** Before the key is shown, the player's wallet calls `SkyKeys.optIn()`
and the server waits until Somnia confirms `exported[wallet] = true`. The flag is one-way: no function clears it.
From then on every write from that wallet must carry a server attestation (EIP-712 domain `Catharsis`, version `1`,
per-player nonce bitmap, deadline of at most 15 minutes, hash of the exact calldata). A key alone can pay for a
transaction but cannot decide what it writes: a forged, replayed, altered, expired, or self-signed call reverts.

**Always attested, both modes:** minting or moving NFTs (blade craft, `withdrawNft`/`depositNft`, gear withdraw/deposit,
SkyHold2 syncs that mint gear, item transfers), bazaar and Buy-It-Now escrow, auctions, arena entry and results,
and island deeds. SkyRealm2 also refuses filling your own buy order, waves above 50, and waves finished faster than
3 seconds each; outbid refunds are pulled with `withdrawPending()`.

`/exportkey` asks for the password again, shows the key only to that player (never logged or broadcast), and is
refused for server wallets (the original deployer purse, the admin and the signer).

In scope additionally: any way to land a mode-B write without a valid attestation, or to clear the SkyKeys flag.

## Please never send

- Private keys, seed phrases, or `.env` files
- Player password hashes or sealed wallet blobs
- Full copies of `secrets.yml` or server `plugins/` data

If you accidentally committed a secret, tell us in the private report and rotate the key immediately. Do not assume that deleting it from a later commit removes it from git history.
