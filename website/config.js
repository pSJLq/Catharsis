// Deployment settings for the site process. Every value comes from an environment variable and defaults to the
// local setup (http://127.0.0.1:8791, game on ws://127.0.0.1:25565), so running `node server.js` with no
// environment behaves exactly as before. On a VPS, systemd loads them from /etc/catharsis/site.env
// (see deploy/site.env.example and docs/DEPLOY_VPS.md).

import net from 'net';

const env = process.env;

function intOr(value, fallback) {
  const n = Number.parseInt(String(value || ''), 10);
  return Number.isFinite(n) && n > 0 && n < 65536 ? n : fallback;
}

function list(value) {
  return String(value || '').split(',').map((s) => s.trim()).filter(Boolean);
}

function stripSlash(url) {
  return String(url).replace(/\/+$/, '');
}

// The literal URLs written into the static pages (canonical, og:url, og:image, sitemap, robots, play page).
export const DEFAULT_PUBLIC_URL = 'http://127.0.0.1:8791';
export const DEFAULT_JOIN_URL = 'ws://127.0.0.1:25565';

// Where the HTTP server listens. Behind Caddy keep 127.0.0.1.
export const HOST = env.SITE_HOST || '127.0.0.1';
export const PORT = intOr(env.SITE_PORT, 8791);

// The address visitors use, without a trailing slash, for example https://play.example.com.
export const PUBLIC_URL = stripSlash(env.PUBLIC_URL || DEFAULT_PUBLIC_URL);

// The WebSocket address /api/join hands to the browser client. The client only accepts the page's own host
// (or localhost / a private IP) and drops any path, so behind Caddy this is wss://<same host as PUBLIC_URL>.
// Unset: derived from an https PUBLIC_URL, otherwise the local default.
function deriveJoin() {
  if (env.JOIN_URL) return stripSlash(env.JOIN_URL);
  if (env.PUBLIC_URL) {
    try {
      const u = new URL(PUBLIC_URL);
      if (u.protocol === 'https:') return 'wss://' + u.host;
    } catch (e) {}
  }
  return DEFAULT_JOIN_URL;
}
export const JOIN_URL = deriveJoin();

// The Paper server the /api/join "online" probe connects to.
export const PAPER_HOST = env.PAPER_HOST || '127.0.0.1';
export const PAPER_PORT = intOr(env.PAPER_PORT, 25565);

// Origins allowed to send admin writes (news editor, event admin). Default: the origin of PUBLIC_URL.
function originOf(url) {
  try {
    return new URL(url).origin;
  } catch (e) {
    return url;
  }
}
export const ALLOWED_ORIGINS = list(env.ALLOWED_ORIGINS).map(originOf).concat(env.ALLOWED_ORIGINS ? [] : [originOf(PUBLIC_URL)]);

// Proxies whose X-Forwarded-For / X-Forwarded-Proto headers are believed (plain IP literals, comma separated).
// Default: none, so forwarded headers are ignored unless the operator lists the proxy (127.0.0.1,::1 for Caddy
// on the same machine).
export const TRUSTED_PROXIES = new Set(list(env.TRUSTED_PROXIES).map(normIp).filter(Boolean));

// The admin session cookie gets the Secure flag when the site is served over https.
export const SECURE_COOKIES = PUBLIC_URL.startsWith('https://');

function normIp(ip) {
  let s = String(ip || '').trim();
  if (s.startsWith('::ffff:') && net.isIPv4(s.slice(7))) s = s.slice(7);
  return net.isIP(s) ? s.toLowerCase() : '';
}

export function fromTrustedProxy(req) {
  return TRUSTED_PROXIES.size > 0 && TRUSTED_PROXIES.has(normIp(req.socket && req.socket.remoteAddress));
}

// Visitor IP: the socket address, or the last X-Forwarded-For hop when the socket is a trusted proxy.
export function clientIp(req) {
  const direct = normIp(req.socket && req.socket.remoteAddress);
  if (!fromTrustedProxy(req)) return direct;
  const hops = String(req.headers['x-forwarded-for'] || '').split(',').map(normIp).filter(Boolean);
  return hops.length ? hops[hops.length - 1] : direct;
}

// 'https' only when a trusted proxy says so.
export function forwardedProto(req) {
  if (!fromTrustedProxy(req)) return '';
  return String(req.headers['x-forwarded-proto'] || '').split(',')[0].trim().toLowerCase();
}

// Rewrites the local URLs in a page to the configured ones. With the defaults the text is returned unchanged.
export function publicize(text) {
  let out = text;
  if (PUBLIC_URL !== DEFAULT_PUBLIC_URL) out = out.replaceAll(DEFAULT_PUBLIC_URL, PUBLIC_URL);
  if (JOIN_URL !== DEFAULT_JOIN_URL) out = out.replaceAll(DEFAULT_JOIN_URL, JOIN_URL);
  return out;
}

export const REWRITES = PUBLIC_URL !== DEFAULT_PUBLIC_URL || JOIN_URL !== DEFAULT_JOIN_URL;
