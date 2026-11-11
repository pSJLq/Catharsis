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

## Please never send

- Private keys, seed phrases, or `.env` files
- Player password hashes or sealed wallet blobs
- Full copies of `secrets.yml` or server `plugins/` data

If you accidentally committed a secret, tell us in the private report and rotate the key immediately. Do not assume that deleting it from a later commit removes it from git history.
