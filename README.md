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
| SkyIsland | [`0xeB97b719A39722D678418a0f87e6f5bEd8016bA0`](https://explorer.testnet.somnia.network/address/0xeB97b719A39722D678418a0f87e6f5bEd8016bA0) | Island deed and membership |
| SkyWord | [`0x699C4FF0a4f1Afa2B7A86ffF7d0ae7D403C5684c`](https://explorer.testnet.somnia.network/address/0x699C4FF0a4f1Afa2B7A86ffF7d0ae7D403C5684c) | Inventory (packed bag words) |
| SkySkills2 | [`0x287bEAE39A6E65B3a2b4026258490B0bDb44BcEf`](https://explorer.testnet.somnia.network/address/0x287bEAE39A6E65B3a2b4026258490B0bDb44BcEf) | Skills and collections (current). Profile epochs and `resetProfile`. [Deploy tx](https://explorer.testnet.somnia.network/tx/0x6af8858630f40fe58616d92367d40e90d50ebc08dafb7a88b1cde27ea76e23b1) (2026-11-10) |
| SkySkills (v1, deprecated) | [`0x81f560a101ea5ea5c37e1b89b6bd6e0251aa854a`](https://explorer.testnet.somnia.network/address/0x81f560a101ea5ea5c37e1b89b6bd6e0251aa854a) | Add-only predecessor. Kept for history after the v2 migration. |
| SkyHold | [`0x26cf755903bA760d90363d5a74C9e877259603F3`](https://explorer.testnet.somnia.network/address/0x26cf755903bA760d90363d5a74C9e877259603F3) | Held items / equipment slots |
| SkyGear | [`0x4f6373aD986d8dCC04D589352abBD707C6A3E1d8`](https://explorer.testnet.somnia.network/address/0x4f6373aD986d8dCC04D589352abBD707C6A3E1d8) | Unique gear tokens |
| AOTV | [`0x378D4201ce775263f101EB5eC4A63d2E5d669c24`](https://explorer.testnet.somnia.network/address/0x378D4201ce775263f101EB5eC4A63d2E5d669c24) | Aspect of the Void token |
| SkyRealm | [`0xEE46A54C65561Ec4F1589Db5a612c30b8fEcC59d`](https://explorer.testnet.somnia.network/address/0xEE46A54C65561Ec4F1589Db5a612c30b8fEcC59d) | Bazaar, auctions, arena runs, Relic Pool |
| SkyBin | [`0xCfA57B693E5AAeBAcCC9F56ED868107c8bF4d638`](https://explorer.testnet.somnia.network/address/0xCfA57B693E5AAeBAcCC9F56ED868107c8bF4d638) | Buy-It-Now listings |

The plugin default config ships public RPC URLs and these addresses. Operator-only fields (admin accounts) are left empty. See [docs/contracts.md](docs/contracts.md).

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
