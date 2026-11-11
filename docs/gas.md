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
