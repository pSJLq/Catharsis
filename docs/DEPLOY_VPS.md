# Moving Catharsis to a VPS (Ubuntu 26.04)

Target: one OVH VPS-3 (12 GB RAM) running Paper (with EaglerXServer for browser play), the site, and Caddy for
HTTPS/WSS. Files used here are in `deploy/`:

| File | Goes to |
|---|---|
| `deploy/Caddyfile` | `/etc/caddy/Caddyfile` |
| `deploy/systemd/catharsis-paper.service` | `/etc/systemd/system/` |
| `deploy/systemd/catharsis-site.service` | `/etc/systemd/system/` |
| `deploy/.env.example` | `/etc/catharsis/site.env` |

Layout on the VPS (same relative layout as on Windows, the explorer reads `../server/logs/latest.log`):

```
/opt/catharsis/server     Paper, plugins, world "skyblock"
/opt/catharsis/site       site (server.js, public/, data/)
/opt/catharsis/explorer   explorer (imported by the site)
```

Placeholders: `play.example.com` is the domain, `VPS_IP` is the server address, `ubuntu` is the login OVH gives.

## 1. First login and updates

```bash
ssh ubuntu@VPS_IP
sudo apt update && sudo apt full-upgrade -y
sudo timedatectl set-timezone UTC        # logs in UTC; optional
sudo adduser --system --group --home /opt/catharsis --shell /usr/sbin/nologin catharsis
sudo mkdir -p /opt/catharsis /etc/catharsis
sudo chown catharsis:catharsis /opt/catharsis
```

Use SSH keys for login and keep password login off (`PasswordAuthentication no` in `/etc/ssh/sshd_config.d/`).

## 2. Java, Node, Caddy

Paper here runs on Java 25 (the Windows setup uses JDK 25.0.5).

```bash
sudo apt install -y openjdk-25-jre-headless
java -version          # must say 25
```

If the Ubuntu package is missing or older, install Temurin 25 from adoptium.net (apt repo `packages.adoptium.net`)
and change `/usr/bin/java` in `catharsis-paper.service` to its path.

Node: the Windows setup runs Node 24. Install Node 24 LTS (NodeSource) or the Ubuntu `nodejs` package if it is 22 or
newer:

```bash
curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
sudo apt install -y nodejs
node --version
```

Caddy (official repo):

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

## 3. Firewall (ufw)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw limit 22/tcp          # SSH
sudo ufw allow 80/tcp          # Let's Encrypt + redirect to https
sudo ufw allow 443/tcp         # site + browser play (wss)
sudo ufw allow 443/udp         # HTTP/3 (optional)
# Optional, only if Java Edition clients should join directly:
# sudo ufw allow 25565/tcp
sudo ufw enable
sudo ufw status verbose
```

Do not open 8791 (site), 8790 (plugin inventory API, bound to 127.0.0.1 anyway) or 8787.

If 25565 stays closed, also set `server-ip=127.0.0.1` in `server.properties` so Paper only listens locally. If you open
it for Java clients, leave `server-ip=` empty. A faked `X-Real-IP` on a direct connection is ignored by the plugin,
because it only trusts that header from `network.trusted-proxies` (127.0.0.1 and ::1, see `docs/caddy-real-ip.md`).

Java clients (and some launchers) may auto-send `/login` or the same text as chat the moment they join. Paper's
chat/command spam limiter then kicks them with `Kicked for spamming` in the same second. In `spigot.yml` set
`commands.enable-spam-exclusions: true` and list `/login`, `/l`, `/register`, `/reg`, `/changepassword`, plus the
same words without a slash (chat-style auto-login). Leave the rest of the spam limiter on.

Also check the OVH control panel: the VPS has no OVH network firewall by default; if you enable it, allow the same ports.

## 4. Copy the server (no backups)

Do this at cutover time, after the normal stop on Windows, so the world is saved and nobody is playing:

1. Back up as usual into `backups\before-vps-<timestamp>` (this stays on the PC).
2. If anyone is online write `control\warn`, wait the full 60 seconds, then write one `control\stop`.
3. Create `server\keep.off` so `keep.ps1` does not start Paper again, and wait until port 25565 is closed.

Pack on Windows (PowerShell; `C:\TEMP\somnia-skyblock` is the same folder as the live tree, and avoids non-ASCII
paths in tar). This leaves out every backup folder, old jars, logs, caches, the Windows JDK, and the secrets
(those move separately in step 5):

```powershell
cd C:\TEMP\somnia-skyblock
tar -czf C:\TEMP\catharsis-server.tgz `
  --exclude="server/*backup*" --exclude="server/backups-*" --exclude="server/SomniaSkyblock.jar.backup" `
  --exclude="server/logs" --exclude="server/cache" --exclude="server/keep.log" --exclude="server/keep.off" `
  --exclude="server/*.ps1" --exclude="server/plugins/update/*" `
  --exclude="server/plugins/SomniaSkyblock/secrets.yml" --exclude="server/plugins/SomniaSkyblock/auth/migration.key" `
  server
tar -czf C:\TEMP\catharsis-site.tgz `
  --exclude="site/.cache" --exclude="site/data/auth.json" --exclude="explorer/node_modules" `
  site explorer
tar -tzf C:\TEMP\catharsis-server.tgz | Select-String -Pattern "backup|secrets|migration.key"   # must print nothing
scp C:\TEMP\catharsis-server.tgz C:\TEMP\catharsis-site.tgz ubuntu@VPS_IP:/tmp/
```

`server/cache` (the vanilla server jar) is downloaded again by Paper on first start. `site/public/client/index.html`
(the browser client, about 73 MB) is inside the site archive; it is never committed to git.

On the VPS:

```bash
cd /opt/catharsis
sudo -u catharsis tar -xzf /tmp/catharsis-server.tgz
sudo -u catharsis tar -xzf /tmp/catharsis-site.tgz
cd /opt/catharsis/explorer && sudo -u catharsis npm ci --omit=dev
sudo -u catharsis mkdir -p /opt/catharsis/server/plugins/update /opt/catharsis/server/logs
rm /tmp/catharsis-*.tgz
```

The heap is set in the systemd unit (6 GB), not by `keep.ps1`/`start.ps1` (those are Windows only and are left out).

## 5. Secrets (by hand, never through git or chat)

These files hold the wallet keys and the admin password hash. They are not in the archives above and never go
into git, chat, or a ticket. Copy them straight from the PC to the VPS with `scp`:

| File on Windows (under `C:\TEMP\somnia-skyblock`) | On the VPS |
|---|---|
| `server\plugins\SomniaSkyblock\secrets.yml` | `/opt/catharsis/server/plugins/SomniaSkyblock/secrets.yml` |
| `server\plugins\SomniaSkyblock\auth\migration.key` | `/opt/catharsis/server/plugins/SomniaSkyblock/auth/migration.key` |
| `site\data\auth.json` | `/opt/catharsis/site/data/auth.json` |

```powershell
scp C:\TEMP\somnia-skyblock\server\plugins\SomniaSkyblock\secrets.yml ubuntu@VPS_IP:/tmp/secrets.yml
scp C:\TEMP\somnia-skyblock\server\plugins\SomniaSkyblock\auth\migration.key ubuntu@VPS_IP:/tmp/migration.key
scp C:\TEMP\somnia-skyblock\site\data\auth.json ubuntu@VPS_IP:/tmp/auth.json
```

```bash
P=/opt/catharsis/server/plugins/SomniaSkyblock
sudo install -o catharsis -g catharsis -m 600 /tmp/secrets.yml $P/secrets.yml
sudo install -o catharsis -g catharsis -m 600 /tmp/migration.key $P/auth/migration.key
sudo install -o catharsis -g catharsis -m 600 /tmp/auth.json /opt/catharsis/site/data/auth.json
sudo shred -u /tmp/secrets.yml /tmp/migration.key /tmp/auth.json
```

Do not `cat` these files on the VPS, and keep the copies on the PC (the old backups with keys stay where they are).
Before the first start check `dev-accounts` in `plugins/SomniaSkyblock/config.yml` lists only `X_ShadowMarS_X`.

## 6. Site settings and services

The `deploy/` files come from the public repo:

```bash
git clone https://github.com/pSJLq/Catharsis.git ~/catharsis && cd ~/catharsis
sudo cp deploy/.env.example /etc/catharsis/site.env
sudo nano /etc/catharsis/site.env                                       # set PUBLIC_URL=https://<domain>
sudo chown root:catharsis /etc/catharsis/site.env && sudo chmod 640 /etc/catharsis/site.env

sudo cp deploy/systemd/catharsis-paper.service deploy/systemd/catharsis-site.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now catharsis-paper
journalctl -u catharsis-paper -f        # wait for "Done", Ctrl+C
sudo systemctl enable --now catharsis-site
curl -s http://127.0.0.1:8791/api/join  # {"join":"wss://<domain>","online":true}
```

With `PUBLIC_URL` set, the site rewrites `http://127.0.0.1:8791` in canonical/og tags, `sitemap.xml` and
`robots.txt` to the domain, `/api/join` answers `wss://<domain>`, admin writes accept only that origin, and the admin
cookie is `Secure`. The files on disk stay unchanged, so the same tree still runs locally with no settings.

## 7. Caddy

```bash
cd ~/catharsis
sudo cp deploy/Caddyfile /etc/caddy/Caddyfile
sudo sed -i 's/play\.example\.com/<domain>/g' /etc/caddy/Caddyfile
sudo mkdir -p /var/log/caddy && sudo chown caddy:caddy /var/log/caddy
caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

DNS first: an `A` record for the domain to `VPS_IP` (and `AAAA` for the IPv6 address if you use it). Caddy gets the
certificate by itself on the first request.

### EaglerXServer rate limits behind Caddy

`listener.yml` exempts `127.0.0.0/8` from EaglerXServer's own rate limits (login, HTTP, MOTD/query). Behind Caddy
every browser player arrives from `127.0.0.1`, so those limits stop working on the VPS. The plugin's own per-IP rules
(registration cap, login lockout) still work through `RealIp.java`, but connection floods are no longer throttled.
Turn on EaglerXServer's forwarding on the VPS only (local play on the PC needs no change):

1. Make the secret in Paper's working directory (`forward_secret_file` is read relative to `/opt/catharsis/server`,
   not the plugin folder; a wrong path makes EaglerXServer fail to start, which turns off browser play):
   `sudo -u catharsis sh -c "umask 077; head -c 48 /dev/urandom | base64 | tr -d '/+=\n' | head -c 48 > /opt/catharsis/server/eagler_forwarding.secret"`
2. In `server/plugins/EaglercraftXServer/listener.yml`: `forward_ip: true`, `forward_secret: true`
   (header names stay `X-Real-IP` / `X-Eagler-Secret`).
3. Put the header in its own file, readable only by Caddy, and import it in every `reverse_proxy 127.0.0.1:25565`
   block next to `header_up X-Real-IP {remote_host}` (do not pass it through an environment variable: the packaged
   unit runs `caddy run --environ`, which prints the environment to the journal):
   ```bash
   { printf 'header_up X-Eagler-Secret '; sudo cat /opt/catharsis/server/eagler_forwarding.secret; echo; } | sudo tee /etc/caddy/eagler-secret.caddy >/dev/null
   sudo chown root:caddy /etc/caddy/eagler-secret.caddy && sudo chmod 640 /etc/caddy/eagler-secret.caddy
   # in /etc/caddy/Caddyfile:   import /etc/caddy/eagler-secret.caddy
   ```
   Reload Caddy first, then restart Paper the usual way (backup, warn if anyone is online, one `control/stop`).

A WebSocket straight to port 25565 without the secret is now refused ("Connected without a valid forwarding secret
header"); Java Edition clients on 25565 are not affected.
Now the limits count per real IP, a direct WebSocket without the secret cannot fake its address, and `RealIp.java`
simply sees the real address as the socket address.
## 8. Checks after the move

- `https://<domain>/` opens; `https://<domain>/#play` loads the client and joins (browser play is the main path).
- Paper log shows `Real IP <player ip> for proxy socket 127.0.0.1|...` for browser players (not 127.0.0.1 as
  the player IP).
- `curl -sI https://<domain>/client/index.html -H 'Accept-Encoding: br'` returns `Content-Encoding: br`.
- Admin page sign-in works on `https://<domain>/admin`.
- Run the load test again against the domain.

## 9. Onchain follow-up (needs an admin transaction, only with a yes from Shiny)

Island deed metadata (`SkyIsland4.tokenURI`) uses the base URI set at deploy time
(`http://127.0.0.1:8791/api/island/` by default in `tools/chain/deploy-island4.js`). After the domain works, the admin
can call `setBaseURI("https://<domain>/api/island/")` once so wallets and explorers can read the deed pictures (keep the trailing slash: `tokenURI` is `baseURI + id`). Done for playcatharsis.xyz on 2026-11-19.

## 10. Day-to-day on the VPS

Same rule as on Windows: back up, `control/warn` and wait 60 s if anyone is online, put the new jar into
`plugins/update`, then one `control/stop`. systemd starts Paper again after 10 seconds (`Restart=always`), like
`keep.ps1`. `sudo systemctl stop catharsis-paper` stops it for good (until `start`).

## 11. Notes from the first move (HostHatch London, Ubuntu 24.04, November 2026)

- Ubuntu 24.04 has `openjdk-25-jre-headless` in its updates, so no Temurin repo was needed.
- Windows `tar` stops at the first locked file (world `.mca` files, `eagler_skins_cache.db`), so a copy while Paper
  runs is incomplete. Only `server/libraries` and the plugin jars can be sent ahead; pack the rest after the stop.
- The `catharsis` user cannot read archives in `/root`; extract with `sudo -u catharsis tar -xzf - < /root/file.tgz`.
- `caddy validate` run as root creates `/var/log/caddy/catharsis.log` owned by root and the reload then fails;
  `chown caddy:caddy /var/log/caddy/catharsis.log` fixes it.
- Before DNS works, add a plain `http://VPS_IP { ... }` block with the same handlers and set
  `PUBLIC_URL=http://VPS_IP` and `JOIN_URL=ws://VPS_IP` in `site.env`; switch both to the domain once the
  certificate is issued. Some local HTTP proxies drop the WebSocket upgrade on plain `ws://`, `wss://` is not affected.
- On the PC, `server\keep.off` stops `keep.ps1`, and `CatharsisKeep.vbs` in the Startup folder is the only autostart.
