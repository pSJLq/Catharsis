# Architecture

```
Java client  →  Paper 26.3  →  SomniaSkyblock plugin
                                  ├─ world, hub, islands, menus, combat
                                  ├─ KeyVault (Argon2id + AES-256-GCM)
                                  └─ chain client (web3j)
                                         ├─ SkyWord, SkySkills2, SkyIsland, …
                                         └─ public RPCs with failover
```

## Plugin

The plugin (`dev.somnia.skyblock`) is the game server. It owns the hub world, private islands, NPCs, menus, and combat, and it submits transactions on behalf of a logged-in player using that player's game wallet.

Default configuration is `src/main/resources/config.yml` (public RPCs and contract addresses only). A live server may override it. Admin names belong in the operator's local config, not in this repository.

## Wallets and custody

- `/register` creates a secp256k1 key and seals it with the player's password (Argon2id, then AES-256-GCM).
- Only ciphertext, salt, nonce, and KDF parameters are stored on disk.
- `/login` opens the blob into process memory. Quit or restart wipes it; the next join requires `/login` again (unless a short session is configured).
- The plugin signs gameplay transactions with that in-memory key. The player still pays the gas. There is no wallet popup in the game client because the sealed key is the game wallet, not an external browser wallet.

## Chain as source of truth

- **Inventory** lives in SkyWord. The plugin caches it after a confirmed receipt.
- **Skills and collections** live in SkySkills2 (v1 exists for history). On login the plugin reads `xpOf` / `collectionsOf` and only caches them.
- **Island membership** lives in SkyIsland (`createIsland` and members).
- **Profile delete** sends `resetProfile` from the player's wallet, checks that onchain XP and collections read as zero, and rebuilds the island from the starter template.

A broken block on the island and a broken block in the hub go through the same code path (immediate SkyWord write, then the 4-second XP/collection batch).

## RPC failover

`chain.rpc` is tried first. If it fails or is rate-limited, the plugin walks `chain.rpc-fallbacks`. All listed endpoints must serve Shannon (`eth_chainId` `0xc488` / `50312`).

## Website and explorer

`website/` is the marketing site. It can sit on the same machine as the game server. The in-browser client that some older pages still mention is **not** published here and is not part of the public test.

`explorer/` is a small node service that reads Shannon and lists game-contract activity. Item icons are intentionally omitted from this tree (see `explorer/README.md`).
