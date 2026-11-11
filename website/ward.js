import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const dataDir = path.join(path.dirname(fileURLToPath(import.meta.url)), 'data');
const newsFile = path.join(dataDir, 'news.json');
const authFile = path.join(dataDir, 'auth.json');
const sessions = new Map();

const publicRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');
const newsImgDir = path.join(publicRoot, 'img', 'news');

const images = new Set([
  '/img/day.jpg',
  '/img/field.jpg',
  '/img/catacombs.jpg',
  '/img/hub.jpg',
  '/img/caves.jpg',
  '/img/ledge.jpg',
]);

function sendJson(res, status, body, extra) {
  const raw = Buffer.from(JSON.stringify(body));
  const headers = {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': raw.length,
    'X-Content-Type-Options': 'nosniff',
  };
  if (extra) Object.assign(headers, extra);
  res.writeHead(status, headers);
  res.end(raw);
}

function imageOk(image) {
  if (images.has(image)) return true;
  if (!/^\/img\/news\/[a-f0-9]{16}\.(png|jpg|gif|webp)$/.test(image)) return false;
  const file = path.resolve(publicRoot, image.replace(/^\/+/, ''));
  const rel = path.relative(newsImgDir, file);
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel) && fs.existsSync(file);
}

function sniff(buf) {
  if (buf.length > 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return 'png';
  if (buf.length > 3 && buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'jpg';
  if (buf.length > 6) {
    const head = buf.slice(0, 6).toString('ascii');
    if (head === 'GIF87a' || head === 'GIF89a') return 'gif';
  }
  if (buf.length > 12 && buf.slice(0, 4).toString('ascii') === 'RIFF' && buf.slice(8, 12).toString('ascii') === 'WEBP') return 'webp';
  return '';
}

function readRaw(req, limit) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(new Error('big'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

export function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > 32768) {
        reject(new Error('big'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      const text = Buffer.concat(chunks).toString('utf8');
      if (!text) {
        resolve({});
        return;
      }
      try {
        resolve(JSON.parse(text));
      } catch (error) {
        reject(new Error('json'));
      }
    });
    req.on('error', reject);
  });
}

function clean(value, max) {
  return String(value || '').replace(/[<>]/g, '').replace(/\s+/g, ' ').trim().slice(0, max);
}

function loadNews() {
  const parsed = JSON.parse(fs.readFileSync(newsFile, 'utf8'));
  return Array.isArray(parsed.items) ? parsed.items : [];
}

function saveNews(items) {
  fs.writeFileSync(newsFile, JSON.stringify({ items }, null, 2));
}

function cookieToken(req) {
  const raw = req.headers.cookie || '';
  const match = raw.match(/(?:^|;\s*)catharsis_session=([A-Za-z0-9_-]+)/);
  return match ? match[1] : '';
}

export function authed(req) {
  const token = cookieToken(req);
  const row = sessions.get(token);
  if (!row) return false;
  if (row.exp < Date.now()) {
    sessions.delete(token);
    return false;
  }
  return true;
}

export function originOk(req) {
  return (req.headers.origin || '') === 'http://127.0.0.1:8791';
}

function checkLogin(name, password) {
  const auth = JSON.parse(fs.readFileSync(authFile, 'utf8'));
  const nameOk = crypto.timingSafeEqual(
    crypto.createHash('sha256').update(String(name)).digest(),
    crypto.createHash('sha256').update(String(auth.name)).digest()
  );
  const actual = crypto.scryptSync(String(password), Buffer.from(auth.salt, 'hex'), auth.keylen, {
    N: auth.n,
    r: auth.r,
    p: auth.p,
  });
  const expected = Buffer.from(auth.hash, 'hex');
  const passOk = actual.length === expected.length && crypto.timingSafeEqual(actual, expected);
  return nameOk && passOk;
}

function hrefOk(href) {
  if (!href) return true;
  if (/^\/[A-Za-z0-9#\/?=&._-]*$/.test(href)) return true;
  try {
    const url = new URL(href);
    const hosts = [
      'x.com',
      'github.com',
      'docs.somnia.network',
      'explorer.somnia.network',
      'shannon-explorer.somnia.network',
    ];
    return url.protocol === 'https:' && hosts.indexOf(url.hostname) >= 0;
  } catch (error) {
    return false;
  }
}

export async function handleWard(req, res, pathname) {
  if (pathname === '/api/news' && req.method === 'GET') {
    sendJson(res, 200, { items: loadNews() });
    return true;
  }
  if (!pathname.startsWith('/api/admin/')) return false;
  if (req.method !== 'GET' && !originOk(req)) {
    sendJson(res, 403, { error: 'Wrong origin.' });
    return true;
  }
  try {
    if (pathname === '/api/admin/login' && req.method === 'POST') {
      const body = await readBody(req);
      if (!checkLogin(body.name, body.password)) {
        sendJson(res, 401, { error: 'Wrong name or password.' });
        return true;
      }
      const token = crypto.randomBytes(24).toString('base64url');
      sessions.set(token, { exp: Date.now() + 12 * 60 * 60 * 1000 });
      sendJson(res, 200, { ok: true }, {
        'Set-Cookie': 'catharsis_session=' + token + '; HttpOnly; SameSite=Strict; Path=/; Max-Age=43200',
      });
      return true;
    }
    if (pathname === '/api/admin/logout' && req.method === 'POST') {
      sessions.delete(cookieToken(req));
      sendJson(res, 200, { ok: true }, {
        'Set-Cookie': 'catharsis_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0',
      });
      return true;
    }
    if (pathname === '/api/admin/me' && req.method === 'GET') {
      sendJson(res, 200, { ok: authed(req) });
      return true;
    }
    if (!authed(req)) {
      sendJson(res, 401, { error: 'Sign in first.' });
      return true;
    }
    if (pathname === '/api/admin/image' && req.method === 'POST') {
      const buf = await readRaw(req, 2_500_000);
      const ext = sniff(buf);
      if (!ext || buf.length < 32) {
        sendJson(res, 400, { error: 'Use a PNG, JPEG, GIF, or WebP.' });
        return true;
      }
      fs.mkdirSync(newsImgDir, { recursive: true });
      const name = crypto.randomBytes(8).toString('hex') + '.' + ext;
      fs.writeFileSync(path.join(newsImgDir, name), buf);
      sendJson(res, 200, { image: '/img/news/' + name });
      return true;
    }
    if (pathname === '/api/admin/news' && req.method === 'POST') {
      const body = await readBody(req);
      const title = clean(body.title, 90);
      const blurb = clean(body.blurb, 240);
      const tag = clean(body.tag, 24) || 'News';
      const image = imageOk(body.image) ? body.image : '';
      const href = clean(body.href, 180);
      if (!title || !blurb || !image || !hrefOk(href)) {
        sendJson(res, 400, { error: 'Title, text, and a picture from this site are required.' });
        return true;
      }
      const items = loadNews();
      items.unshift({
        id: 'n' + Date.now().toString(36) + crypto.randomBytes(3).toString('hex'),
        tag,
        title,
        blurb,
        image,
        href: href || '',
      });
      saveNews(items.slice(0, 40));
      sendJson(res, 200, { items: loadNews() });
      return true;
    }
    if (pathname === '/api/admin/news' && req.method === 'DELETE') {
      const id = new URL(req.url, 'http://127.0.0.1').searchParams.get('id') || '';
      if (!/^n[a-z0-9]+$/i.test(id)) {
        sendJson(res, 400, { error: 'Unknown dispatch.' });
        return true;
      }
      saveNews(loadNews().filter((item) => item.id !== id));
      sendJson(res, 200, { items: loadNews() });
      return true;
    }
    sendJson(res, 404, { error: 'Not found.' });
    return true;
  } catch (error) {
    sendJson(res, 400, { error: 'Could not read that.' });
    return true;
  }
}
