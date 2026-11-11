# Contracts

All addresses below are on **Somnia Shannon testnet**, chain id `50312`. Explorer links use [explorer.testnet.somnia.network](https://explorer.testnet.somnia.network).

Source lives in [`contracts/`](../contracts). Each `.sol` file is SPDX `MIT`, `pragma solidity ^0.8.24`, compiled with the Cancun EVM and the optimizer (200 runs). ABIs are checked in next to the source. Bytecode used by the plugin at deploy time is `src/main/resources/SkySkills.bin` and `SkySkills2.bin`.

## Addresses

| Contract | Address | Deploy / notes |
| --- | --- | --- |
| SkyKeys | [`0x75B33d1FFd2cb793Cca8280B8E80762339927B3b`](https://explorer.testnet.somnia.network/address/0x75B33d1FFd2cb793Cca8280B8E80762339927B3b) | One-way flag for wallets whose key was exported (mode B). Deployed 2026-11-11. |
| SkyIsland2 | [`0xaCced5efB1762DfAF2587723c9dC07256fa4eb60`](https://explorer.testnet.somnia.network/address/0xaCced5efB1762DfAF2587723c9dC07256fa4eb60) | Island deed and membership (attested). Migrated from SkyIsland. |
| SkyWord2 | [`0x4611a06Fc96f7dF31D5363c1Cb6D300FA691D405`](https://explorer.testnet.somnia.network/address/0x4611a06Fc96f7dF31D5363c1Cb6D300FA691D405) | Inventory (packed bag words). Slot 9 of the hotbar can never hold an item. |
| SkySkills3 | [`0x891361C58ab3b81c2F6469a9F61011ca6fa4E72d`](https://explorer.testnet.somnia.network/address/0x891361C58ab3b81c2F6469a9F61011ca6fa4E72d) | Skills and collections (current). Caps of 1,000,000 per entry and 20,000,000 per hour. |
| SkyHold2 | [`0x5F802b43A38f5460BAB78f4793601c36eEF7ba7f`](https://explorer.testnet.somnia.network/address/0x5F802b43A38f5460BAB78f4793601c36eEF7ba7f) | Held items / equipment slots |
| SkyGear2 | [`0x67e01A054Dbe23d45695F737a77768d4a842c4b0`](https://explorer.testnet.somnia.network/address/0x67e01A054Dbe23d45695F737a77768d4a842c4b0) | Unique gear tokens |
| AOTV2 | [`0x248a3A61F1227774D4fCeA15e1725CA95dAbF632`](https://explorer.testnet.somnia.network/address/0x248a3A61F1227774D4fCeA15e1725CA95dAbF632) | Aspect of the Void token |
| SkyRealm2 | [`0xE124dD8443c1bF2045E1376f5fe3E277Dcd31c8a`](https://explorer.testnet.somnia.network/address/0xE124dD8443c1bF2045E1376f5fe3E277Dcd31c8a) | Bazaar, auctions, arena runs, Relic Pool |
| SkyBin2 | [`0x420Dc7D312719C041bb3fedc6e83Ce8dFd7789A3`](https://explorer.testnet.somnia.network/address/0x420Dc7D312719C041bb3fedc6e83Ce8dFd7789A3) | Buy-It-Now listings |

Previous contracts (SkyIsland `0xeB97…6bA0`, SkyWord `0x699C…684c`, SkySkills2 `0x287b…BcEf`, SkySkills v1 `0x81f5…854a`, SkyHold `0x26cf…03F3`, SkyGear `0x4f63…E1d8`, AOTV `0x378D…9c24`, SkyRealm `0xEE46…C59d`, SkyBin `0xCfA5…d638`) were migrated on 2026-11-11 and closed; they stay onchain for history.

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
