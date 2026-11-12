# Catharsis

An onchain SkyBlock-style grind MMO on the [Somnia](https://somnia.network) Shannon testnet. **Play in the browser** — that is the main way to join — or connect with a desktop Java client. Both talk to the same Paper server.

You mine, forage, farm, fight, trade, and quest. Inventory, skill XP, and collections are written to smart contracts. **Each player pays their own gas** from a personal game wallet. The chain is the source of truth: if a transaction fails or the wallet has no STT, the world action rolls back.

Status: **public test of v1** on Shannon (chain id `50312`). The contracts are not formally audited. Some features are marked **Soon** in game and are not part of this release.

## What's inside

| Path | What it is |
| --- | --- |
| [`src/`](src) | Paper 26.3 plugin (Java 25): worlds, combat, menus, quests, and the chain client |
| [`contracts/`](contracts) | Solidity contracts, ABIs, and Foundry tests |
| [`docs/`](docs) | Gameplay, contract addresses, gas, and architecture |
| [`scripts/deploy/`](scripts/deploy) | Optional deploy helpers (read a key from `.env`, never from the repo) |
| [`website/`](website) | Site source, including the play page. The browser client is distributed by the play site and is not stored in this repository |
| [`explorer/`](explorer) | Lightweight explorer for game transactions on Shannon |

## Network

| | Shannon testnet |
| --- | --- |
| Chain ID | `50312` |
| Native token | STT |
| RPC | `https://api.infra.testnet.somnia.network` |
| Fallback RPCs | `https://dream-rpc.somnia.network`, `https://rpc.ankr.com/somnia_testnet`, `https://50312.rpc.thirdweb.com` |
| Explorer | [explorer.testnet.somnia.network](https://explorer.testnet.somnia.network) |
| Faucet | [1 STT per hour](https://explorer.testnet.somnia.network/faucet) if the wallet holds less than 1 STT |

Deployer (public EOA, not a key): [`0x9EbD5ED2BD30F943fF9ffEE6443a4963aaD966d6`](https://explorer.testnet.somnia.network/address/0x9EbD5ED2BD30F943fF9ffEE6443a4963aaD966d6)

## Contracts (Shannon)

| Contract | Address | Role |
| --- | --- | --- |
| SkyKeys | [`0x75B33d1FFd2cb793Cca8280B8E80762339927B3b`](https://explorer.testnet.somnia.network/address/0x75B33d1FFd2cb793Cca8280B8E80762339927B3b) | One-way flag for wallets whose key was exported (mode B). Deployed 2026-11-11. |
| SkyIsland3 | [`0xeeEA12518a25852242E7841cf8436141e57475Cf`](https://explorer.testnet.somnia.network/address/0xeeEA12518a25852242E7841cf8436141e57475Cf) | Island deeds and co-op (attested): invite, accept, leave, kick, at most 5 players per island. Imported from SkyIsland2 on 2026-11-12. |
| SkyWord2 | [`0x4611a06Fc96f7dF31D5363c1Cb6D300FA691D405`](https://explorer.testnet.somnia.network/address/0x4611a06Fc96f7dF31D5363c1Cb6D300FA691D405) | Inventory (packed bag words). Slot 9 of the hotbar can never hold an item. |
| SkySkills3 | [`0x891361C58ab3b81c2F6469a9F61011ca6fa4E72d`](https://explorer.testnet.somnia.network/address/0x891361C58ab3b81c2F6469a9F61011ca6fa4E72d) | Skills and collections (current). Caps of 1,000,000 per entry and 20,000,000 per hour. |
| SkyHold2 | [`0x5F802b43A38f5460BAB78f4793601c36eEF7ba7f`](https://explorer.testnet.somnia.network/address/0x5F802b43A38f5460BAB78f4793601c36eEF7ba7f) | Held items / equipment slots |
| SkyGear2 | [`0x67e01A054Dbe23d45695F737a77768d4a842c4b0`](https://explorer.testnet.somnia.network/address/0x67e01A054Dbe23d45695F737a77768d4a842c4b0) | Unique gear tokens |
| AOTV2 | [`0x248a3A61F1227774D4fCeA15e1725CA95dAbF632`](https://explorer.testnet.somnia.network/address/0x248a3A61F1227774D4fCeA15e1725CA95dAbF632) | Aspect of the Void token |
| SkyRealm2 | [`0xE124dD8443c1bF2045E1376f5fe3E277Dcd31c8a`](https://explorer.testnet.somnia.network/address/0xE124dD8443c1bF2045E1376f5fe3E277Dcd31c8a) | Bazaar, auctions, arena runs, Relic Pool |
| SkyBin2 | [`0x420Dc7D312719C041bb3fedc6e83Ce8dFd7789A3`](https://explorer.testnet.somnia.network/address/0x420Dc7D312719C041bb3fedc6e83Ce8dFd7789A3) | Buy-It-Now listings |

The plugin default config ships public RPC URLs and these addresses. Operator-only fields (admin accounts) are left empty. See [docs/contracts.md](docs/contracts.md).

Previous contracts (SkyIsland `0xeB97…6bA0`, SkyWord `0x699C…684c`, SkySkills2 `0x287b…BcEf`, SkySkills v1 `0x81f5…854a`, SkyHold `0x26cf…03F3`, SkyGear `0x4f63…E1d8`, AOTV `0x378D…9c24`, SkyRealm `0xEE46…C59d`, SkyBin `0xCfA5…d638`) were migrated on 2026-11-11 and closed; they stay onchain for history.

SkyIsland2 [`0xaCced5efB1762DfAF2587723c9dC07256fa4eb60`](https://explorer.testnet.somnia.network/address/0xaCced5efB1762DfAF2587723c9dC07256fa4eb60) is old and no longer written: its 3 deeds and the island counters were imported into SkyIsland3 on 2026-11-12 and its import was closed.

## Wallet keys: two modes

- **Key kept on the server (default).** Routine writes (inventory, XP, stats) need no server signature and cost normal gas. Onchain caps still apply.
- **Key exported (`/exportkey confirm <password>`).** The wallet is first marked in **SkyKeys** (one-way), then the key is shown to you only. After that every write from that wallet needs a server attestation (EIP-712, `Catharsis` v1), so the key cannot be used to write game state around the server.
- NFT mints and moves, bazaar, auctions, arena and island deeds are server-attested in both modes.

Measured gas (Shannon, same call): see docs/gas.md.

## How the onchain loop works

1. On `/register`, the player gets a game wallet. The private key is sealed with their password (Argon2id + AES-256-GCM) and stored only as ciphertext. `/login` decrypts it into server memory for that session.
2. Game actions are signed by **that wallet**. The player pays gas in STT. There is no server gas tank, treasury, or sponsorship.
3. Breaking a block writes the item to **SkyWord** immediately. If the transaction fails (including a zero STT balance), the block is rolled back.
4. Skill XP and collections are batched every **4 seconds** into one **SkySkills** / **SkySkills2** transaction, so the chain sees a steady stream of play.
5. The island and the hub use the **same** write path: inventory, XP, and collections. There is no separate island counter in the live plugin.
6. Deleting a profile resets skills and inventory onchain (SkySkills2 `resetProfile`, plus a fresh island) after the receipts confirm zeroes.

More detail: [docs/architecture.md](docs/architecture.md), [docs/gas.md](docs/gas.md), [docs/gameplay.md](docs/gameplay.md).

## Build

### Plugin

You need **JDK 25** and **Gradle 8.14.3** (or run the same version through your own wrapper).

```bash
gradle jar
# -> build/libs/SomniaSkyblock.jar
```

Copy the jar into a Paper 26.3 server's `plugins/` folder (or `plugins/update/` if you use a staged restart). Do not commit server worlds, player data, or `secrets.yml`.

### Contracts

```bash
# https://book.getfoundry.sh/getting-started/installation
forge test
```

Optional deploy scripts read `PRIVATE_KEY` from a local `.env` (see `.env.example`). They never look for a key in this repository.

## Contributing and security

- [CONTRIBUTING.md](CONTRIBUTING.md) — how to open a pull request
- [SECURITY.md](SECURITY.md) — how to report a vulnerability privately
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)

Please do not open issues or PRs that include private keys, mnemonics, player password hashes, or live `secrets.yml` files.

## License

MIT. See [LICENSE](LICENSE). The Monocraft font bundled with the website is under the SIL Open Font License (see `website/public/fonts/OFL.txt`).
