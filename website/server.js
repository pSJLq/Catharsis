import fs from 'fs';
import http from 'http';
import net from 'net';
import path from 'path';
import { fileURLToPath } from 'url';
import { applyLayout } from './layout.js';
import { handleExplorer } from '../explorer/server.js';
import { handleWard } from './ward.js';

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

function send(res, status, file) {
  const ext = path.extname(file).toLowerCase();
  const headers = {
    'Content-Type': types[ext] || 'application/octet-stream',
    'X-Content-Type-Options': 'nosniff',
  };
  if (ext === '.html') {
    const body = Buffer.from(applyLayout(fs.readFileSync(file, 'utf8')));
    headers['Content-Length'] = body.length;
    headers['Cache-Control'] = 'no-store';
    res.writeHead(status, headers);
    res.end(body);
    return;
  }
  const stat = fs.statSync(file);
  headers['Content-Length'] = stat.size;
  headers['Cache-Control'] = ext === '.xml' || ext === '.txt' ? 'no-store' : 'public, max-age=86400';
  res.writeHead(status, headers);
  fs.createReadStream(file).pipe(res);
}

function notFound(res) {
  send(res, 404, path.join(root, '404.html'));
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
      notFound(res);
      return;
    }
    if (fs.existsSync(file) && fs.statSync(file).isFile()) {
      send(res, 200, file);
      return;
    }
    if (fs.existsSync(file + '.html') && fs.statSync(file + '.html').isFile()) {
      send(res, 200, file + '.html');
      return;
    }
    notFound(res);
  } catch (error) {
    if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('error');
    console.error(error);
  }
});

server.listen(port, '127.0.0.1', () => {
  console.log('Catharsis http://127.0.0.1:' + port);
});
