# Gas

Players pay gas from their own game wallet in STT. The server does not sponsor transactions, does not share a treasury for gas, and does not queue actions until a later deposit.

## What you pay for

| Action | How it is sent |
| --- | --- |
| Break / pick up / store an item | One SkyWord transaction immediately |
| Skill XP and collection increments | One SkySkills / SkySkills2 transaction about every 4 seconds, combining whatever happened in that window |
| Create an island, craft, market actions, withdraw | One transaction per action, signed by your wallet |

The 4-second XP batch is intentional: it keeps a regular stream of transactions on Shannon. It is not a way to hide play offchain.

## When a transaction fails

If the wallet has no STT, the node rejects the transaction or it never confirms. The plugin then **rolls the world change back**. A broken block reappears. There is no “wait until you top up” queue.

`/deposit` shows the wallet address (click to copy), a faucet link, and the current balance. The Shannon faucet is [https://explorer.testnet.somnia.network/faucet](https://explorer.testnet.somnia.network/faucet): 1 STT per hour, only if the wallet already holds less than 1 STT.

## Limits

The plugin estimates gas and uses the network gas price (with a safety margin), capped by `chain.max-fee-gwei` in `config.yml`. Fallback RPCs are tried in order if the primary Shannon endpoint fails or is rate-limited.

Typical play on testnet is on the order of about 1–2 STT per hour, depending on how fast you gather and trade. That figure will move with gas price and with how many actions you send.

## Operators

When you run your own server, fund **player** wallets, not a shared operator key, for gameplay. Deploy scripts in `scripts/deploy/` use a separate deployer key from a local `.env` and are not used for day-to-day play.

## Measured gas: two key modes (Shannon, 2026-11-11)

Same calls, sent from test wallets against the live contracts. Steady state means the storage slots already exist (rounds 2 and later).

| Call | Before (no attestation) | Mode A (key on server) | Mode B (key exported, attested) |
| --- | --- | --- | --- |
| SkySkills `applyXp` | 176,856 | 183,269 (+3.6 %) | 361,275 (+104 %) |
| SkyWord `write` | 65,914 | 68,945 (+4.6 %) | 245,123 (+272 %) |
| First call (new slots), `applyXp` | 576,856 | 783,292 | 1,161,298 |
| First call (new slots), `write` | 265,914 | 268,945 | 645,123 |
| `SkyKeys.optIn()` (once, at `/exportkey`) | - | 244,496 | - |

Confirmation time was about 0.7-1.7 s per transaction in every mode. Signing an attestation on the server takes under 1 ms.
Mode B costs more because every write checks an EIP-712 signature (ecrecover) and records its nonce.