# Website

Source for the Catharsis marketing site (`website/server.js`).

Static pages, styles, fonts, and images are under `public/`. Layout partials are in `partials/`.

## Not in this repository

- An in-browser game client. Several pages still describe an older “play in the browser on one shared island” prototype. The public v1 test is a **Java Edition server** with private islands and a hub. That copy needs a rewrite before the site is pointed at a public URL.
- `data/auth.json` — the admin password hash for the tiny news editor. Use `data/auth.json.example` as a template and keep the real file out of git.

## Run locally

```bash
node server.js
```

The process binds to `127.0.0.1` by default. See `server.js` for the port.

The Monocraft font is licensed under the SIL Open Font License (`public/fonts/OFL.txt`).
