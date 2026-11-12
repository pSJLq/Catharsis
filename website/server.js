import fs from 'fs';
import http from 'http';
import net from 'net';
import path from 'path';
import { fileURLToPath } from 'url';
import zlib from 'zlib';
import { pipeline } from 'stream/promises';
import { applyLayout } from './layout.js';
import { handleExplorer } from '../explorer/server.js';
import { handleWard, authed, originOk, readBody } from './ward.js';
import { handleEvent } from './event.js';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), 'public');
const port = 8791;

const types = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.txt': 'text/plain; charset=utf-8',
  '.xml': 'application/xml; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ttf': 'font/ttf',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json',
  '.wasm': 'application/wasm',
};

function paperOpen() {
  return new Promise((resolve) => {
    const socket = net.connect({ host: '127.0.0.1', port: 25565, timeout: 800 });
    let done = false;
    const finish = (ok) => {
      if (done) return;
      done = true;
      socket.destroy();
      resolve(ok);
    };
    socket.on('connect', () => finish(true));
    socket.on('timeout', () => finish(false));
    socket.on('error', () => finish(false));
  });
}

// ---- static files ---------------------------------------------------------------------------------------
// Pages with the shared header/footer stay as before (read, layout applied, no-store). Every other file, and
// any HTML of BIG bytes or more (the 72.8 MB browser client has no layout markers), is streamed from disk
// untouched: ETag / Last-Modified with 304 revalidation, single Range requests, and gzip/brotli copies built
// once in the background (site/.cache, rebuilt when the file's size or mtime changes) served by Accept-Encoding.
// Decompressed, the .gz/.br copies are exactly the bytes on disk.
const BIG = 1024 * 1024;
const PRECOMPRESS_MIN = 256 * 1024;
const compressible = new Set(['.html', '.css', '.js', '.txt', '.xml', '.svg', '.json', '.webmanifest', '.epk', '.wasm', '.map']);
const cacheDir = path.join(path.dirname(root), '.cache');
const variants = new Map(); // file -> { key, gz, br, building }

function statKey(st) {
  return st.size + '-' + Math.floor(st.mtimeMs);
}

function cachePath(file, key, ext) {
  const rel = path.relative(root, file).replace(/[\\/:]/g, '_');
  return path.join(cacheDir, rel + '.' + key + ext);
}

async function buildVariant(file, key, ext, makeStream) {
  const out = cachePath(file, key, ext);
  try {
    await fs.promises.access(out);
    return out; // already built for this exact size+mtime
  } catch (e) {}
  const tmp = out + '.tmp' + process.pid;
  await pipeline(fs.createReadStream(file, { highWaterMark: 1 << 20 }), makeStream(), fs.createWriteStream(tmp));
  await fs.promises.rename(tmp, out);
  return out;
}

async function removeStale(file, key) {
  const prefix = path.basename(cachePath(file, 'X', '')).replace(/\.X$/, '') + '.';
  try {
    for (const name of await fs.promises.readdir(cacheDir)) {
      if (name.startsWith(prefix) && !name.includes('.' + key + '.') ) {
        await fs.promises.unlink(path.join(cacheDir, name)).catch(() => {});
      }
    }
  } catch (e) {}
}

// Starts (once per size+mtime) the background build of the .gz and .br copies; requests meanwhile get identity.
function ensureVariants(file, st) {
  const key = statKey(st);
  let v = variants.get(file);
  if (v && v.key === key) return v;
  v = { key, gz: null, br: null, building: true };
  variants.set(file, v);
  (async () => {
    await fs.promises.mkdir(cacheDir, { recursive: true });
    const t0 = Date.now();
    const gz = await buildVariant(file, key, '.gz', () => zlib.createGzip({ level: 9 }));
    if (variants.get(file) === v) v.gz = gz;
    const br = await buildVariant(file, key, '.br', () => zlib.createBrotliCompress({
      params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 9, [zlib.constants.BROTLI_PARAM_SIZE_HINT]: st.size },
    }));
    if (variants.get(file) === v) v.br = br;
    v.building = false;
    await removeStale(file, key);
    const [g, b] = await Promise.all([fs.promises.stat(gz), fs.promises.stat(br)]);
    console.log('static: compressed ' + path.relative(root, file) + ' ' + st.size + ' -> gzip ' + g.size + ', br ' + b.size + ' in ' + (Date.now() - t0) + ' ms');
  })().catch((error) => {
    v.building = false;
    console.error('static: compress failed for ' + file, error && error.message);
  });
  return v;
}

function pickEncoding(req) {
  const accept = String(req.headers['accept-encoding'] || '').toLowerCase();
  const ok = (name) => new RegExp('(^|,)\\s*' + name + '\\s*(;\\s*q\\s*=\\s*(1(\\.0*)?|0?\\.\\d*[1-9]\\d*))?\\s*(,|$)').test(accept);
  if (ok('br')) return 'br';
  if (ok('gzip')) return 'gzip';
  return null;
}

function etagMatches(req, etag) {
  const inm = req.headers['if-none-match'];
  if (!inm) return false;
  const base = etag.replace(/^W\//, '');
  return inm.split(',').some((t) => {
    const x = t.trim().replace(/^W\//, '');
    return x === '*' || x === base || x.replace(/-(gz|br)"$/, '"') === base;
  });
}

async function sendStatic(req, res, status, file, st) {
  const ext = path.extname(file).toLowerCase();
  const head = req.method === 'HEAD';
  const etag = '"' + statKey(st) + '"';
  const headers = {
    'Content-Type': types[ext] || 'application/octet-stream',
    'X-Content-Type-Options': 'nosniff',
    'Last-Modified': new Date(Math.floor(st.mtimeMs / 1000) * 1000).toUTCString(),
    // Big files (the client) revalidate every time (cheap 304); other assets keep the old 1-day cache.
    'Cache-Control': ext === '.xml' || ext === '.txt' ? 'no-store' : st.size >= BIG ? 'public, no-cache' : 'public, max-age=86400',
    'Accept-Ranges': 'bytes',
  };
  const canCompress = status === 200 && compressible.has(ext) && st.size >= PRECOMPRESS_MIN;
  if (canCompress) headers['Vary'] = 'Accept-Encoding';
  if (status === 200) {
    const ims = Date.parse(req.headers['if-modified-since'] || '');
    if (etagMatches(req, etag) || (!req.headers['if-none-match'] && Number.isFinite(ims) && Math.floor(st.mtimeMs / 1000) * 1000 <= ims)) {
      res.writeHead(304, { ETag: etag, 'Last-Modified': headers['Last-Modified'], 'Cache-Control': headers['Cache-Control'], Vary: headers['Vary'] || 'Accept-Encoding' });
      res.end();
      return;
    }
  }
  const range = status === 200 ? String(req.headers.range || '') : '';
  if (canCompress && !range) {
    const v = ensureVariants(file, st);
    const enc = pickEncoding(req);
    const variant = enc === 'br' ? v.br : enc === 'gzip' ? v.gz : null;
    if (variant) {
      try {
        const vs = await fs.promises.stat(variant);
        headers['Content-Encoding'] = enc;
        headers['Content-Length'] = vs.size;
        headers['ETag'] = etag.replace(/"$/, enc === 'br' ? '-br"' : '-gz"');
        res.writeHead(status, headers);
        if (head) return res.end();
        pipeline(fs.createReadStream(variant, { highWaterMark: 1 << 20 }), res).catch(() => {});
        return;
      } catch (e) {
        // copy vanished: fall through to the plain file
      }
    }
  }
  headers['ETag'] = etag;
  const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
  if (m && (m[1] !== '' || m[2] !== '')) {
    let start = m[1] === '' ? Math.max(0, st.size - Number(m[2])) : Number(m[1]);
    let end = m[1] === '' || m[2] === '' ? st.size - 1 : Math.min(Number(m[2]), st.size - 1);
    const ifRange = req.headers['if-range'];
    if (!ifRange || ifRange === etag) {
      if (start > end || start >= st.size) {
        res.writeHead(416, { 'Content-Range': 'bytes */' + st.size, 'Content-Type': 'text/plain; charset=utf-8' });
        res.end();
        return;
      }
      headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + st.size;
      headers['Content-Length'] = end - start + 1;
      res.writeHead(206, headers);
      if (head) return res.end();
      pipeline(fs.createReadStream(file, { start, end, highWaterMark: 1 << 20 }), res).catch(() => {});
      return;
    }
  }
  headers['Content-Length'] = st.size;
  res.writeHead(status, headers);
  if (head) return res.end();
  pipeline(fs.createReadStream(file, { highWaterMark: 1 << 20 }), res).catch(() => {});
}

async function send(res, status, file, req) {
  const ext = path.extname(file).toLowerCase();
  const st = await fs.promises.stat(file);
  if (ext === '.html' && st.size < BIG) {
    const headers = {
      'Content-Type': types[ext] || 'application/octet-stream',
      'X-Content-Type-Options': 'nosniff',
    };
    const body = Buffer.from(applyLayout(await fs.promises.readFile(file, 'utf8')));
    headers['Content-Length'] = body.length;
    headers['Cache-Control'] = 'no-store';
    res.writeHead(status, headers);
    res.end(req && req.method === 'HEAD' ? undefined : body);
    return;
  }
  await sendStatic(req || { method: 'GET', headers: {} }, res, status, file, st);
}

async function isFile(file) {
  try {
    return (await fs.promises.stat(file)).isFile();
  } catch (e) {
    return false;
  }
}

function notFound(res, req) {
  return send(res, 404, path.join(root, '404.html'), req);
}

function safeFile(pathname) {
  const rel = pathname.replace(/^\/+/, '');
  const file = path.resolve(root, rel);
  const fromRoot = path.relative(root, file);
  if (fromRoot.startsWith('..') || path.isAbsolute(fromRoot)) return null;
  return file;
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', 'http://127.0.0.1');
    if (url.pathname === '/play' || url.pathname === '/play.html') {
      res.writeHead(302, { Location: '/#play', 'Cache-Control': 'no-store' });
      res.end();
      return;
    }
    if (await handleEvent(req, res, url.pathname, { authed, originOk, readBody })) return;
    if (await handleWard(req, res, url.pathname)) return;
    if (await handleExplorer(req, res, url.pathname)) return;
    if (url.pathname === '/api/join') {
      const body = JSON.stringify({
        join: 'ws://127.0.0.1:25565',
        online: await paperOpen(),
      });
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(body);
      return;
    }

    let pathname = decodeURIComponent(url.pathname);
    if (pathname === '/') pathname = '/index.html';
    const file = safeFile(pathname);
    if (!file) {
      await notFound(res, req);
      return;
    }
    if (await isFile(file)) {
      await send(res, 200, file, req);
      return;
    }
    if (await isFile(file + '.html')) {
      await send(res, 200, file + '.html', req);
      return;
    }
    await notFound(res, req);
  } catch (error) {
    if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('error');
    console.error(error);
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log('Catharsis http://127.0.0.1:' + port);
  // Build the compressed copies of the big client files right away instead of on the first visit.
  for (const rel of ['client/index.html', 'client/assets.epk', 'client/sounds.epk']) {
    const file = path.join(root, rel);
    fs.promises.stat(file).then((st) => {
      if (st.isFile() && st.size >= PRECOMPRESS_MIN) ensureVariants(file, st);
    }).catch(() => {});
  }
});
