# Contributing

Thanks for helping with Catharsis. This repository is the public source and documentation for the game. Please keep it that way: source, docs, and templates only — no secrets, no player data, no server worlds.

## Ground rules

- Player-facing text is English.
- Do not commit `.env`, `secrets.yml`, keys, mnemonics, `auth.json`, player YAML, worlds, backups, logs, or built jars.
- Do not add an in-browser game client to this tree.
- Open a pull request from a fork, with a short description of what changed and why.
- If you touch contracts, run `forge test`. If you touch the plugin, run `gradle jar` (JDK 25).
- Keep changes focused. Large unrelated refactors are hard to review.

## Development setup

1. Fork and clone this repository.
2. Install JDK 25 and Gradle 8.14.3 for the plugin, and [Foundry](https://book.getfoundry.sh/getting-started/installation) for contracts.
3. Copy `.env.example` to `.env` only if you need to run a deploy script against a local or test wallet you control.
4. Read [docs/architecture.md](docs/architecture.md) before changing how inventory or skills are written onchain.

## Pull requests

- One concern per PR when you can.
- New player-facing strings stay in English.
- If you deploy a contract, add the address, the deploy transaction, and an explorer link to `docs/contracts.md` and the README table.
- Do not reformat whole files unless the PR is specifically about formatting.

## Questions

Use GitHub Issues for bugs and proposals. For vulnerabilities, follow [SECURITY.md](SECURITY.md).
