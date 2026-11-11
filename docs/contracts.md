# Contracts

All addresses below are on **Somnia Shannon testnet**, chain id `50312`. Explorer links use [explorer.testnet.somnia.network](https://explorer.testnet.somnia.network).

Source lives in [`contracts/`](../contracts). Each `.sol` file is SPDX `MIT`, `pragma solidity ^0.8.24`, compiled with the Cancun EVM and the optimizer (200 runs). ABIs are checked in next to the source. Bytecode used by the plugin at deploy time is `src/main/resources/SkySkills.bin` and `SkySkills2.bin`.

## Addresses

| Contract | Address | Deploy / notes |
| --- | --- | --- |
| SkyIsland | [`0xeB97b719A39722D678418a0f87e6f5bEd8016bA0`](https://explorer.testnet.somnia.network/address/0xeB97b719A39722D678418a0f87e6f5bEd8016bA0) | Island deed (`createIsland`, membership). The live plugin no longer uses this contract as a per-island gather counter. |
| SkyWord | [`0x699C4FF0a4f1Afa2B7A86ffF7d0ae7D403C5684c`](https://explorer.testnet.somnia.network/address/0x699C4FF0a4f1Afa2B7A86ffF7d0ae7D403C5684c) | Packed inventory words. A confirmed `write` is the inventory commit. |
| SkySkills2 | [`0x287bEAE39A6E65B3a2b4026258490B0bDb44BcEf`](https://explorer.testnet.somnia.network/address/0x287bEAE39A6E65B3a2b4026258490B0bDb44BcEf) | Current skills contract: per-player profile epochs, `resetProfile` / `resetProfileFor`, same caps as v1 (64 entries, 1_000_000 per entry). Deployed 2026-11-10, [tx](https://explorer.testnet.somnia.network/tx/0x6af8858630f40fe58616d92367d40e90d50ebc08dafb7a88b1cde27ea76e23b1). |
| SkySkills (v1, deprecated) | [`0x81f560a101ea5ea5c37e1b89b6bd6e0251aa854a`](https://explorer.testnet.somnia.network/address/0x81f560a101ea5ea5c37e1b89b6bd6e0251aa854a) | Add-only predecessor. Kept for history after the v2 migration. |
| SkyHold | [`0x26cf755903bA760d90363d5a74C9e877259603F3`](https://explorer.testnet.somnia.network/address/0x26cf755903bA760d90363d5a74C9e877259603F3) | Held stacks and gear-token slots. |
| SkyGear | [`0x4f6373aD986d8dCC04D589352abBD707C6A3E1d8`](https://explorer.testnet.somnia.network/address/0x4f6373aD986d8dCC04D589352abBD707C6A3E1d8) | Unique gear tokens (enchants, reforge). |
| AOTV | [`0x378D4201ce775263f101EB5eC4A63d2E5d669c24`](https://explorer.testnet.somnia.network/address/0x378D4201ce775263f101EB5eC4A63d2E5d669c24) | Aspect of the Void token, minted through the hold path. |
| SkyRealm | [`0xEE46A54C65561Ec4F1589Db5a612c30b8fEcC59d`](https://explorer.testnet.somnia.network/address/0xEE46A54C65561Ec4F1589Db5a612c30b8fEcC59d) | Bazaar orders, bid auctions, ranked arena entry, Relic Pool, Somnia VRF. |
| SkyBin | [`0xCfA57B693E5AAeBAcCC9F56ED868107c8bF4d638`](https://explorer.testnet.somnia.network/address/0xCfA57B693E5AAeBAcCC9F56ED868107c8bF4d638) | Buy-It-Now. Half of fees burn to `0x000…dEaD`, half goes to the Relic Pool. |

The plugin also talks to the public Somnia VRF coordinator on Shannon: `0x0834459256bbb8d2efee23dc6c3f1722266182dd`.

## Design notes

- **No contract mints STT.** Player-to-player trades, deposits, and the Relic Pool (funded by fees) are the only STT sources in game. BIN fees are a sink (burn + pool).
- **Players sign their own state.** `applyXp` and bag writes are `msg.sender =` the player's game wallet. Nobody can write another player's record.
- **SkySkills2 resets.** `resetProfile()` is called by the player's wallet (or by an operator that player authorized). Old epochs remain readable as history (`xpAt`, `collectionAt`).
- **SkyIsland counters.** The deployed SkyIsland contract still has add-only gather fields. The current plugin ignores them for gameplay accounting and writes XP and collections through SkySkills instead, on the island and in the hub alike.
- **Items in markets.** While a Bazaar or auction listing is open, the Paper server holds the items; SkyRealm / SkyBin settle the STT side and leave the public record.

## Tests

```bash
forge test
```

`contracts/test/SkySkills2.t.sol` covers the reset flow, an authorized operator, and the entry caps.
