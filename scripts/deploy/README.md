# Deploy scripts

Optional helpers to compile a contract with `solc` and deploy it to Shannon from a wallet **you** control.

They read `PRIVATE_KEY` and optional `RPC_URL` from the environment (or a local `.env` you must not commit). They never read `secrets.yml` or any file inside a running server.

```bash
cp .env.example .env   # then put your own test key in .env
cd scripts/deploy
npm install
node deploy-skills.js deploy      # SkySkills
# node deploy-bin.js              # SkyBin (needs a SkyRealm address)
# node deploy-realm.js deploy     # SkyRealm (uses the public Somnia VRF coordinator)
```

These scripts write the new address to stdout. Update `docs/contracts.md`, the README table, and your **local** server config yourself. Do not point them at a production key you cannot afford to leak.
