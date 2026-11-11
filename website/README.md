# Website

Source for the Catharsis site: marketing pages and the **play** page.

Playing in the browser is the main way to join the game. The play site serves the browser client and connects it to the Paper server. The client binary is distributed by the play site and is not stored in this repository.

Static pages, styles, fonts, and images are under `public/`. Layout partials are in `partials/`.

`data/auth.json` (the admin password hash for the tiny news editor) is not in this repository. Use `data/auth.json.example` as a template and keep the real file out of git.

## Run locally

```bash
node server.js
```

The process binds to `127.0.0.1` by default. See `server.js` for the port.

The Monocraft font is licensed under the SIL Open Font License (`public/fonts/OFL.txt`).
