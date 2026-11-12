# Real player IPs behind Caddy (VDS)

Per-IP rules (accounts per IP, login lockout, the event's shared-IP flag) use the player's real IP.
The plugin (`RealIp.java`) reads it like this:

- Direct connection (Java client, or browser client on `ws://host:25565`): the socket address. Nothing to configure.
- Browser client through a reverse proxy: EaglerXServer sees the proxy's address. The plugin then takes
  `X-Real-IP` (or, if that is missing, the **last** `X-Forwarded-For` hop) from the WebSocket upgrade, but **only**
  when the connection comes from an address in `network.trusted-proxies` (default `127.0.0.1` and `::1`).
  A header sent from anywhere else is ignored, so nobody can fake an IP by connecting to the game port directly.
  Only plain IP literals are accepted (no host names, no DNS).

## Caddy

Caddy on the same machine as Paper (so it connects from 127.0.0.1):

```
play.example.com {
    # browser client WebSocket (wss) -> EaglerXServer on the game port
    reverse_proxy 127.0.0.1:25565 {
        header_up X-Real-IP {remote_host}
    }
}
```

- `{remote_host}` is the address of the client that connected to Caddy. If Caddy itself sits behind another proxy or
  CDN (for example Cloudflare), configure Caddy's `trusted_proxies` / `client_ip_headers` so that `{client_ip}` is the
  visitor, and send `header_up X-Real-IP {client_ip}` instead.
- If Caddy runs on a different host, add that host's address to `network.trusted-proxies` in
  `plugins/SomniaSkyblock/config.yml`, and nothing else.
- Keep the game port and the site's internal ports firewalled so that only Caddy (and Java clients, if you allow
  them on 25565) can reach them.
- EaglerXServer's own `forward_ip` can stay `false`: with it `true`, every connection without the header is
  dropped, which breaks direct local play.

Each proxied connection logs one line, `Real IP <ip> for proxy socket 127.0.0.1|<port>`
(`network.log-real-ip: false` turns it off).