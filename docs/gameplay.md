# Gameplay (public test v1)

Catharsis is a SkyBlock-style grind MMO. You start on your own island, visit a shared hub, gather resources, level skills, and trade in STT. The chain records inventory, skills, and collections. You pay gas from your game wallet.

## First steps

1. Open the **play site in your browser**. That is the main way to play. The site serves the browser client and connects it to the game server. (The client itself is distributed by the play site, not from this repository.) You can also connect with a desktop Java client (26.3) to the public server address, published at launch.
2. `/register <password> <password>` creates your account and a game wallet. The password seals that wallet. Choose a password you will not reuse elsewhere.
3. `/login <password>` unlocks the wallet for the session.
4. `/deposit` shows your wallet address (click to copy), a faucet link, and your STT balance. Fund the wallet from the [Shannon faucet](https://explorer.testnet.somnia.network/faucet) or another wallet you control.
5. `/island` takes you home. `/hub` takes you to the hub. Falling into the void returns you safely.

Without STT, gathering still *looks* like it happens, then the failed receipt rolls the block back. There is no queue that waits for a later deposit.

## Skills and collections

Nine skills exist: Mining, Foraging, Farming, Combat, Fishing, Enchanting, Alchemy, Taming, and Carpentry. Alchemy, Taming, and Runecrafting progression are marked **Soon** in the menu.

XP and collection counts are written onchain every 4 seconds. The SkyBlock level is `floor(total XP / 100)` and is shown in chat, the menu, and tab as `[N] name`. Level rewards are **Soon**.

## Gathering

- Stone outside the spawn village can be mined and turns to bedrock, then regenerates.
- A log disappears when broken and later regrows. There is no stripped-log stage.
- Trees are breakable everywhere except in structures.
- Resource spots in the hub and on the island regenerate. Darkvein Mine is open (`/warp`). The Deep Mines, Event, and Dungeon portals are **Soon**.

## Combat and stats

Health, Defense, Strength, Crit Chance, Crit Damage, weapon damage, and Mana are live in combat. Speed affects walking when a source of Speed exists. Intelligence, Mining Speed, Fortune stats, Magic Find, Ferocity, Attack Speed, and True Defense are **Soon**.

The Colosseum is sealed in v1. Dungeons are postponed. Pets and the Accessory Bag are **Soon**.

## Economy

- Your purse is the game wallet. `/withdraw <amount> <address>` sends STT out.
- `/bz` is the Bazaar (player-to-player STT orders). `/ah` is the Auction House, including Buy-It-Now.
- Fees and taxes go to the Relic Pool; part of BIN fees is burned. The contracts do not mint STT.
- Coin balances used at some NPCs are a server-side practice currency, not STT.

## Quests and NPCs

Talk to NPCs (left or right click). Lines go to chat one at a time as `[NPC] Name: text`. Quest progress uses OBJECTIVE COMPLETE, then REWARDS, then NEW OBJECTIVE. Skill XP rewards are granted onchain with the usual 4-second batch.

Some NPC stands are placeholders: they show **SOON** instead of **CLICK**.

## Commands

| Command | Purpose |
| --- | --- |
| `/register`, `/login`, `/changepassword` | Account and wallet seal |
| `/deposit`, `/withdraw` | Show the purse; send STT |
| `/island`, `/hub`, `/warp` | Travel |
| `/sb`, `/sbmenu`, `/skills`, `/collection`, `/stats`, `/quest` | Menus and progress |
| `/craft`, `/trade`, `/bz`, `/ah`, `/minion` | Crafting, trading, markets, minions |

Admin commands (`/sbadmin`, `/dev`, `/user`) are for operators only.
