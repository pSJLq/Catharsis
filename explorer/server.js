import fs from 'fs';
import http from 'http';
import path from 'path';
import { fileURLToPath, pathToFileURL } from 'url';
import { ethers } from 'ethers';
import { applyLayout } from '../site/layout.js';

const root = path.dirname(fileURLToPath(import.meta.url));
const chain = JSON.parse(fs.readFileSync(path.join(root, 'chain.json'), 'utf8'));
const RPC = chain.rpc;
const CHAIN = chain.chainId;
const HOLD = chain.hold.toLowerCase();
const HOLD_PRETTY = ethers.getAddress(chain.hold);
const GEAR = chain.gear.toLowerCase();
const GEAR_PRETTY = ethers.getAddress(chain.gear);
const ISLAND = chain.island.toLowerCase();
const PURSE = ethers.getAddress(chain.purse);
const WORD = chain.word ? ethers.getAddress(chain.word) : null;
const AOTV = chain.aotv ? ethers.getAddress(chain.aotv) : null;
const WORD_L = WORD ? WORD.toLowerCase() : '';
const AOTV_L = AOTV ? AOTV.toLowerCase() : '';
const PORT = 8787;
const CDN = 'https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@1.21.4/assets/minecraft/textures/';

const logPath = path.resolve(root, '..', 'server', 'logs', 'latest.log');
const pagePath = path.join(root, 'public', 'index.html');
const iconDir = path.join(root, 'public', 'icons');
const guiPath = path.join(root, 'public', 'gui', 'inventory.png');

const holdIface = new ethers.Interface([
  'function bagOf(address player) view returns (uint32[] items, uint16[] counts, uint16[] damages, uint256[] gear)',
  'function gears() view returns (address)',
  'event Bag(address indexed player, uint8 slot, uint32 item, uint16 count, uint16 damage, uint256 gear)',
  'event Chest(uint256 indexed chestId, uint8 slot, uint32 item, uint16 count, uint16 damage, uint256 gear)',
  'event Transfer(address indexed from, address indexed to, uint32 indexed item, uint256 amount)',
]);
const gearIface = new ethers.Interface([
  'function traits(uint256 id) view returns (uint32 item, uint16 damage, string key, string label, string reforge, string enchants)',
  'event Traits(uint256 indexed id, uint16 damage, string reforge, string enchants)',
  'event Transfer(address indexed from, address indexed to, uint256 indexed id)',
]);
const islandIface = new ethers.Interface([
  'function createIsland()',
  'function invite(address member)',
  'function revokeInvite(address member)',
  'function accept(uint256 id)',
  'function leave()',
  'function kick(address member)',
  'function transferFrom(address from, address to, uint256 id)',
  'function applyBatch(uint32 cobble, uint32 oak, uint32 wheat)',
  'function craft(uint8 kind)',
  'event Transfer(address indexed from, address indexed to, uint256 indexed id)',
  'event Invited(uint256 indexed id, address indexed member, address indexed by, uint64 until)',
  'event InviteRevoked(uint256 indexed id, address indexed member)',
  'event Joined(uint256 indexed id, address indexed member, uint256 released)',
  'event Left(uint256 indexed id, address indexed member)',
  'event Kicked(uint256 indexed id, address indexed member, address indexed by)',
  'event Batch(address indexed player, uint32 cobble, uint32 oak, uint32 wheat)',
  'event Craft(address indexed player, uint8 kind)',
]);
const wordIface = new ethers.Interface([
  'function wordsOf(address player) view returns (uint256[])',
  'function manaOf(address player) view returns (uint256)',
  'function write(uint8[] slots, uint256[] words)',
  'function craft(uint8 fromSlot, uint8 toSlot)',
  'function cast(uint8 handSlot)',
  'event Forged(address indexed player, uint256 indexed serial, uint8 slot)',
  'event Cast(address indexed player, uint256 indexed serial)',
]);
const aotvIface = new ethers.Interface([
  'event Transfer(address indexed from, address indexed to, uint256 indexed id)',
]);

function fnv(key) {
  let hash = 0x811c9dc5 | 0;
  for (let i = 0; i < key.length; i++) {
    hash ^= key.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193);
  }
  hash &= 0x7fffffff;
  return hash === 0 ? 1 : hash;
}

const names = new Map();
for (const line of fs.readFileSync(path.join(root, 'items.txt'), 'utf8').split(/\r?\n/)) {
  const key = line.trim();
  if (!key) continue;
  const id = fnv(key);
  if (!names.has(id)) names.set(id, key);
}
if (names.get(fnv('minecraft:dirt')) !== 'minecraft:dirt') {
  throw new Error('Item names do not match the plugin hash for dirt');
}
if (fnv('minecraft:cobblestone') !== 1851776868) {
  throw new Error('Item names do not match the plugin hash for cobblestone');
}

function pretty(key) {
  const raw = key.includes(':') ? key.slice(key.indexOf(':') + 1) : key;
  return raw.split('_').filter(Boolean).map((word) => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

function nameOf(id) {
  const key = names.get(id);
  return key ? pretty(key) : '#' + id;
}

function iconFor(fullKey, traitKey) {
  if (traitKey) return traitKey;
  if (fullKey === 'skyblock:enchanted_cobble') return 'cobblestone';
  if (fullKey === 'skyblock:enchanted_oak') return 'oak_log';
  if (!fullKey) return '';
  const colon = fullKey.indexOf(':');
  return colon < 0 ? fullKey : fullKey.slice(colon + 1);
}

function kindOf(to) {
  const address = (to || '').toLowerCase();
  if (WORD_L && address === WORD_L) return 'Word';
  if (AOTV_L && address === AOTV_L) return 'AOTV';
  if (address === HOLD) return 'Inventory';
  if (address === GEAR) return 'Gear';
  if (address === ISLAND) return 'Island';
  return 'Transaction';
}

function actionOf(to, data) {
  if (!data || data === '0x') return null;
  const address = (to || '').toLowerCase();
  const iface = address === WORD_L ? wordIface
    : address === AOTV_L ? aotvIface
    : address === HOLD ? holdIface
    : address === GEAR ? gearIface
    : address === ISLAND ? islandIface
    : null;
  if (!iface) return null;
  try {
    return iface.parseTransaction({ data }).name;
  } catch {
    return null;
  }
}

const provider = new ethers.JsonRpcProvider(RPC, CHAIN);
const hold = new ethers.Contract(HOLD_PRETTY, holdIface, provider);
const gear = new ethers.Contract(GEAR_PRETTY, gearIface, provider);

const txs = [];
const byHash = new Map();
const clients = new Set();
let bagState = null;
let bagDelay = 300;
let bagBusy = false;
let bagAgain = false;
let resolveReady = null;
const ready = new Promise((resolve) => {
  resolveReady = resolve;
});

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function broadcast(event, data) {
  const frame = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of clients) {
    res.write(frame);
  }
}

function hello() {
  return {
    purse: PURSE,
    hold: HOLD_PRETTY,
    gear: GEAR_PRETTY,
    word: WORD,
    aotv: AOTV,
    chain: CHAIN,
    bag: bagState,
    txs,
  };
}

function unpackWord(word) {
  const value = BigInt(word);
  if (value === 0n) return null;
  const item = Number(value & 0xffffffffn);
  const count = Number((value >> 32n) & 0xffffn);
  const collection = (value >> 64n) & ((1n << 160n) - 1n);
  const serial = Number((value >> 224n) & 0xffffffffn);
  if (collection !== 0n && serial > 0 && count === 1) {
    return {
      item: 0,
      count: 1,
      damage: 0,
      name: 'Aspect of the Void',
      gear: String(serial),
      icon: 'netherite_sword',
      reforge: '',
      enchants: 'Instant Transmission',
    };
  }
  if (item === 0 || count <= 0) return null;
  const full = names.get(item) || '';
  return {
    item,
    count,
    damage: 0,
    name: nameOf(item),
    gear: null,
    icon: iconFor(full, ''),
    reforge: '',
    enchants: '',
  };
}

function noteSent(hash, from, to) {
  const key = hash.toLowerCase();
  if (byHash.has(key)) return byHash.get(key);
  const tx = {
    hash: key,
    from: from || PURSE,
    to: to || null,
    kind: kindOf(to),
    action: null,
    phase: 'mempool',
    sentAt: Date.now(),
    changes: [],
    block: null,
    gas: null,
    ms: null,
  };
  byHash.set(key, tx);
  txs.unshift(tx);
  while (txs.length > 200) {
    const dropped = txs.pop();
    byHash.delete(dropped.hash);
  }
  const row = touchRow(key, null);
  row.phase = 'mempool';
  row.kind = tx.kind;
  if (tx.from) {
    row.from = tx.from;
    rememberPlayer(row, tx.from);
  }
  if (tx.to) row.to = tx.to;
  broadcast('tx', tx);
  return tx;
}

function decodeChanges(receipt) {
  const changes = [];
  for (const log of receipt.logs) {
    const address = log.address.toLowerCase();
    if (address === WORD_L || address === AOTV_L) {
      let parsed;
      try {
        parsed = (address === WORD_L ? wordIface : aotvIface).parseLog(log);
      } catch {
        parsed = null;
      }
      if (parsed && parsed.name === 'Forged') {
        changes.push({
          kind: 'forged',
          serial: parsed.args.serial.toString(),
          slot: Number(parsed.args.slot),
          name: 'Aspect of the Void',
        });
      } else if (parsed && parsed.name === 'Cast') {
        changes.push({
          kind: 'cast',
          serial: parsed.args.serial.toString(),
          name: 'Instant Transmission',
        });
      } else if (parsed && parsed.name === 'Transfer') {
        changes.push({
          kind: 'forged',
          serial: parsed.args.id.toString(),
          name: 'Aspect of the Void',
        });
      }
      if (changes.length >= 8) break;
      continue;
    }
    if (address !== HOLD && address !== GEAR) continue;
    let parsed;
    try {
      parsed = address === HOLD ? holdIface.parseLog(log) : gearIface.parseLog(log);
    } catch {
      continue;
    }
    if (!parsed) continue;
    if (parsed.name === 'Bag' || parsed.name === 'Chest') {
      const item = Number(parsed.args.item);
      const gearId = parsed.args.gear;
      changes.push({
        kind: parsed.name === 'Bag' ? 'bag' : 'chest',
        slot: Number(parsed.args.slot),
        item,
        count: Number(parsed.args.count),
        name: nameOf(item),
        gear: gearId && gearId !== 0n ? gearId.toString() : null,
      });
    } else if (parsed.name === 'Transfer') {
      const item = Number(parsed.args.item);
      changes.push({
        kind: 'transfer',
        item,
        amount: parsed.args.amount.toString(),
        name: nameOf(item),
      });
    } else if (parsed.name === 'Traits') {
      changes.push({
        kind: 'traits',
        gear: parsed.args.id.toString(),
        damage: Number(parsed.args.damage),
        enchants: parsed.args.enchants,
      });
    }
    if (changes.length >= 8) break;
  }
  return changes;
}

async function settle(tx) {
  const receipt = await provider.getTransactionReceipt(tx.hash);
  if (!receipt) {
    if (tx.phase === 'mempool' && Date.now() - tx.sentAt > 20000) {
      tx.phase = 'stuck';
      broadcast('tx', tx);
    }
    return;
  }
  const ok = Number(receipt.status) === 1;
  tx.phase = ok ? 'executed' : 'reverted';
  tx.block = Number(receipt.blockNumber);
  tx.gas = Number(receipt.gasUsed);
  tx.ms = Math.max(0, Date.now() - tx.sentAt);
  tx.changes = decodeChanges(receipt);
  try {
    const full = await provider.getTransaction(tx.hash);
    if (full) {
      if (full.from) tx.from = full.from;
      if (full.to) {
        tx.to = full.to;
        tx.kind = kindOf(full.to);
      }
      tx.action = actionOf(full.to, full.data);
    }
  } catch {
    // The receipt is enough to show the row as executed.
  }
  for (const log of receipt.logs) ingestLog(log);
  const row = touchRow(tx.hash, tx.block);
  row.phase = tx.phase;
  row.block = tx.block;
  row.gas = tx.gas;
  row.from = tx.from || row.from;
  row.to = tx.to || row.to;
  row.action = tx.action;
  if (tx.kind) row.kind = tx.kind;
  broadcast('tx', tx);
}

async function confirmLoop() {
  for (;;) {
    const pending = txs.filter((tx) => tx.phase === 'mempool' || tx.phase === 'stuck');
    if (pending.length) {
      await Promise.all(pending.map((tx) => settle(tx).catch(() => {})));
    }
    await sleep(120);
  }
}

async function pollBagOnce() {
  if (bagBusy) {
    bagAgain = true;
    return;
  }
  bagBusy = true;
  try {
    do {
      bagAgain = false;
      try {
        if (WORD) {
          const wordContract = new ethers.Contract(WORD, wordIface, provider);
          const [packed, mana, stt, block] = await Promise.all([
            wordContract.wordsOf(PURSE),
            wordContract.manaOf(PURSE),
            provider.getBalance(PURSE),
            provider.getBlockNumber(),
          ]);
          const slots = [...packed].slice(0, 41).map(unpackWord);
          while (slots.length < 41) slots.push(null);
          bagState = {
            ok: true,
            slots,
            stt: stt.toString(),
            block: Number(block),
            mana: Number(mana),
          };
          bagDelay = 300;
          if (resolveReady) {
            resolveReady();
            resolveReady = null;
          }
          broadcast('bag', bagState);
          continue;
        }
        const result = await hold.bagOf(PURSE);
        const items = result.items ?? result[0];
        const counts = result.counts ?? result[1];
        const damages = result.damages ?? result[2];
        const gears = result.gear ?? result[3];
        const ids = [];
        for (let i = 0; i < gears.length; i++) {
          if (gears[i] > 0n && !ids.some((id) => id === gears[i])) ids.push(gears[i]);
        }
        const traitList = await Promise.all(ids.map(async (id) => {
          try {
            const traits = await gear.traits(id);
            return [id.toString(), traits];
          } catch {
            return [id.toString(), null];
          }
        }));
        const traitById = new Map(traitList);
        const slots = [];
        for (let i = 0; i < items.length; i++) {
          const item = Number(items[i]);
          const count = Number(counts[i]);
          const damage = Number(damages[i]);
          const gearId = gears[i];
          if (item === 0 || count === 0) {
            slots.push(null);
            continue;
          }
          const full = names.get(item) || '';
          const traits = gearId > 0n ? traitById.get(gearId.toString()) : null;
          slots.push({
            item,
            count,
            damage,
            name: traits && traits.label ? traits.label : nameOf(item),
            gear: gearId > 0n ? gearId.toString() : null,
            icon: iconFor(full, traits ? traits.key : ''),
            reforge: traits ? traits.reforge : '',
            enchants: traits ? traits.enchants : '',
          });
        }
        const [stt, block] = await Promise.all([
          provider.getBalance(PURSE),
          provider.getBlockNumber(),
        ]);
        bagState = {
          ok: true,
          slots,
          stt: stt.toString(),
          block: Number(block),
        };
        bagDelay = 300;
        if (resolveReady) {
          resolveReady();
          resolveReady = null;
        }
        broadcast('bag', bagState);
      } catch (error) {
        bagDelay = Math.min(2000, bagDelay + 250);
        if (bagState) {
          bagState = { ...bagState, ok: false, error: error.message };
          broadcast('bag', bagState);
        }
        console.error('bag', error.message);
        bagAgain = false;
      }
    } while (bagAgain);
  } finally {
    bagBusy = false;
  }
}

function kickBag() {}

async function bagLoop() {
  for (;;) {
    await pollBagOnce();
    await sleep(bagDelay);
  }
}

const sentRe = /Live sent (0x[0-9a-fA-F]{64})(?:\s+(0x[0-9a-fA-F]{40}))?(?:\s+(0x[0-9a-fA-F]{40}))?/;

function tailLog() {
  let offset = 0;
  try {
    offset = fs.statSync(logPath).size;
  } catch {
    offset = 0;
  }
  let carry = '';
  setInterval(() => {
    let stat;
    try {
      stat = fs.statSync(logPath);
    } catch {
      return;
    }
    if (stat.size < offset) {
      offset = 0;
      carry = '';
    }
    if (stat.size === offset) return;
    const len = stat.size - offset;
    if (len > 2_000_000) {
      offset = stat.size;
      carry = '';
      return;
    }
    let buf;
    const fd = fs.openSync(logPath, 'r');
    try {
      buf = Buffer.alloc(len);
      fs.readSync(fd, buf, 0, len, offset);
      offset = stat.size;
    } catch {
      return;
    } finally {
      fs.closeSync(fd);
    }
    carry += buf.toString('utf8');
    let nl;
    while ((nl = carry.indexOf('\n')) >= 0) {
      const line = carry.slice(0, nl);
      carry = carry.slice(nl + 1);
      const match = line.match(sentRe);
      if (match) noteSent(match[1], match[2], match[3]);
    }
  }, 80);
}

function sse(req, res) {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    Connection: 'keep-alive',
  });
  res.write(':\n\n');
  Promise.race([ready, sleep(8000)]).then(() => {
    if (!res.writableEnded) {
      res.write(`event: hello\ndata: ${JSON.stringify(hello())}\n\n`);
    }
  }).catch(() => {});
  clients.add(res);
  const ping = setInterval(() => {
    if (!res.writableEnded) res.write(':\n\n');
  }, 15000);
  req.on('close', () => {
    clearInterval(ping);
    clients.delete(res);
  });
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

function proxyMove(req, res) {
  if (WORD) {
    res.writeHead(409, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ ok: false, error: 'This page only watches. Sign moves in game.' }));
    return;
  }
  readBody(req).then((body) => {
    const out = http.request({
      hostname: '127.0.0.1',
      port: 8790,
      path: '/move',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Content-Length': body.length,
      },
      timeout: 35000,
    }, (up) => {
      const chunks = [];
      up.on('data', (chunk) => chunks.push(chunk));
      up.on('end', () => {
        const payload = Buffer.concat(chunks);
        res.writeHead(up.statusCode || 502, {
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        });
        res.end(payload);
      });
    });
    out.on('timeout', () => out.destroy());
    out.on('error', () => {
      if (res.headersSent) return;
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: 'The game is not accepting moves.' }));
    });
    out.end(body);
  }).catch(() => {
    if (!res.headersSent) {
      res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({ ok: false, error: 'Bad move' }));
    }
  });
}

function sendFile(res, file, type) {
  const bytes = fs.readFileSync(file);
  res.writeHead(200, {
    'Content-Type': type,
    'Cache-Control': 'public, max-age=86400',
    'Content-Length': bytes.length,
  });
  res.end(bytes);
}

async function serveIcon(res, name) {
  const file = path.join(iconDir, name + '.png');
  if (fs.existsSync(file)) {
    sendFile(res, file, 'image/png');
    return;
  }
  for (const folder of ['item', 'block']) {
    const response = await fetch(CDN + folder + '/' + name + '.png');
    if (!response.ok) continue;
    const bytes = Buffer.from(await response.arrayBuffer());
    fs.mkdirSync(iconDir, { recursive: true });
    fs.writeFileSync(file, bytes);
    res.writeHead(200, {
      'Content-Type': 'image/png',
      'Cache-Control': 'public, max-age=86400',
      'Content-Length': bytes.length,
    });
    res.end(bytes);
    return;
  }
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('not found');
}

const SHANNON = 'https://shannon-explorer.somnia.network';
const WATCH = [chain.island, chain.hold, chain.gear, chain.word, chain.aotv].filter(Boolean).map((address) => ethers.getAddress(address));
const CONTRACTS = [
  { name: 'Island', address: ethers.getAddress(chain.island), role: 'Island deeds and co-op' },
  { name: 'Hold', address: HOLD_PRETTY, role: 'Bags and chests' },
  { name: 'Gear', address: GEAR_PRETTY, role: 'Tool traits' },
  { name: 'Word', address: WORD, role: 'Spell words' },
  { name: 'AOTV', address: AOTV, role: 'Aspect of the Void' },
  { name: 'Skills', address: chain.skills ? ethers.getAddress(chain.skills) : null, role: 'Skill XP and collections (caps per write and per hour)' },
  { name: 'Realm', address: chain.realm ? ethers.getAddress(chain.realm) : null, role: 'Bazaar, auctions, arena, Relic Pool' },
  { name: 'Bin', address: chain.bin ? ethers.getAddress(chain.bin) : null, role: 'Buy-It-Now listings' },
  { name: 'Keys', address: chain.keys ? ethers.getAddress(chain.keys) : null, role: 'Exported wallet keys (every write server-signed)' },
].filter((item) => item.address);

const feed = [];
const feedByHash = new Map();
const seenLogs = new Set();
let headBlock = null;
let scanFrom = null;
let scanSpan = 400;
let scanning = false;

function shortAddr(address) {
  try {
    const prettyAddress = ethers.getAddress(address);
    return prettyAddress.slice(0, 6) + '…' + prettyAddress.slice(-4);
  } catch {
    return '';
  }
}

function rememberPlayer(row, address) {
  try {
    if (address) row.players.add(ethers.getAddress(address));
  } catch {
    // Ignore a log field that is not an address.
  }
}

function touchRow(hash, block) {
  const key = String(hash || '').toLowerCase();
  let row = feedByHash.get(key);
  if (!row) {
    row = {
      hash: key,
      block: block == null ? null : Number(block),
      players: new Set(),
      bits: [],
      bag: 0,
      chest: 0,
      extra: 0,
      phase: 'executed',
      from: null,
      to: null,
      action: null,
      kind: null,
      gas: null,
    };
    feedByHash.set(key, row);
    feed.unshift(row);
    while (feed.length > 240) {
      const dropped = feed.pop();
      feedByHash.delete(dropped.hash);
    }
  }
  if (block != null) row.block = Number(block);
  return row;
}

function addBit(row, text) {
  if (!text) return;
  if (row.bits.length < 8) row.bits.push(text);
  else row.extra += 1;
}

function describeLog(address, parsed, row) {
  const who = address.toLowerCase();
  const name = parsed.name;
  if (who === ISLAND) {
    if (name === 'Batch') {
      rememberPlayer(row, parsed.args.player);
      addBit(row, 'Gathered cobble ' + parsed.args.cobble + ', oak ' + parsed.args.oak + ', wheat ' + parsed.args.wheat);
    } else if (name === 'Craft') {
      rememberPlayer(row, parsed.args.player);
      const kind = Number(parsed.args.kind);
      const label = kind === 1 ? 'enchanted cobblestone' : kind === 2 ? 'enchanted oak log' : 'kind ' + kind;
      addBit(row, 'Crafted ' + label);
    } else if (name === 'Invited') {
      rememberPlayer(row, parsed.args.member);
      addBit(row, 'Invited ' + shortAddr(parsed.args.member) + ' to island ' + parsed.args.id.toString());
    } else if (name === 'InviteRevoked') {
      rememberPlayer(row, parsed.args.member);
      addBit(row, 'Took back the island ' + parsed.args.id.toString() + ' invite of ' + shortAddr(parsed.args.member));
    } else if (name === 'Joined') {
      rememberPlayer(row, parsed.args.member);
      const released = parsed.args.released.toString();
      addBit(row, shortAddr(parsed.args.member) + ' joined the co-op on island ' + parsed.args.id.toString() + (released !== '0' ? ' (gave up island ' + released + ')' : ''));
    } else if (name === 'Left') {
      rememberPlayer(row, parsed.args.member);
      addBit(row, shortAddr(parsed.args.member) + ' left the co-op on island ' + parsed.args.id.toString());
    } else if (name === 'Kicked') {
      rememberPlayer(row, parsed.args.member);
      rememberPlayer(row, parsed.args.by);
      addBit(row, shortAddr(parsed.args.member) + ' was removed from the co-op on island ' + parsed.args.id.toString());
    } else if (name === 'Transfer') {
      rememberPlayer(row, parsed.args.from);
      rememberPlayer(row, parsed.args.to);
      addBit(row, 'Island ' + parsed.args.id.toString() + ' moved');
    }
    return;
  }
  if (who === HOLD) {
    if (name === 'Bag') {
      row.bag += 1;
      rememberPlayer(row, parsed.args.player);
    } else if (name === 'Chest') {
      row.chest += 1;
    } else if (name === 'Transfer') {
      rememberPlayer(row, parsed.args.from);
      rememberPlayer(row, parsed.args.to);
      addBit(row, 'Moved ' + parsed.args.amount.toString() + ' ' + nameOf(Number(parsed.args.item)));
    }
    return;
  }
  if (who === GEAR) {
    if (name === 'Traits') addBit(row, 'Gear ' + parsed.args.id.toString() + ' traits changed');
    else if (name === 'Transfer') {
      rememberPlayer(row, parsed.args.from);
      rememberPlayer(row, parsed.args.to);
      addBit(row, 'Gear ' + parsed.args.id.toString() + ' moved');
    }
    return;
  }
  if (who === WORD_L) {
    if (name === 'Forged') {
      rememberPlayer(row, parsed.args.player);
      addBit(row, 'Forged Aspect of the Void');
    } else if (name === 'Cast') {
      rememberPlayer(row, parsed.args.player);
      addBit(row, 'Cast Instant Transmission');
    }
    return;
  }
  if (who === AOTV_L && name === 'Transfer') {
    rememberPlayer(row, parsed.args.from);
    rememberPlayer(row, parsed.args.to);
    addBit(row, 'Void sword ' + parsed.args.id.toString() + ' moved');
  }
}

function ingestLog(log) {
  const mark = String(log.transactionHash || '').toLowerCase() + ':' + String(log.index ?? log.logIndex ?? log.data);
  if (seenLogs.has(mark)) return;
  seenLogs.add(mark);
  const address = log.address.toLowerCase();
  const meta = address === ISLAND ? islandIface
    : address === HOLD ? holdIface
    : address === GEAR ? gearIface
    : address === WORD_L ? wordIface
    : address === AOTV_L ? aotvIface
    : null;
  if (!meta) return;
  let parsed;
  try {
    parsed = meta.parseLog(log);
  } catch {
    return;
  }
  if (!parsed) return;
  describeLog(address, parsed, touchRow(log.transactionHash, log.blockNumber));
}

function summaryOf(row) {
  const bits = [];
  if (row.bag) bits.push(row.bag === 1 ? 'Bag slot changed' : 'Bag sync, ' + row.bag + ' slots');
  if (row.chest) bits.push(row.chest === 1 ? 'Chest slot changed' : 'Chest sync, ' + row.chest + ' slots');
  bits.push(...row.bits);
  if (!bits.length && row.action) bits.push((row.kind || 'Game') + ' · ' + row.action);
  if (!bits.length) bits.push(row.kind || 'Game transaction');
  const extra = row.extra ? ' +' + row.extra : '';
  return bits.slice(0, 3).join(' · ') + extra;
}

function publicRow(row) {
  return {
    hash: row.hash,
    block: row.block,
    phase: row.phase,
    from: row.from,
    to: row.to,
    action: row.action,
    kind: row.kind,
    gas: row.gas,
    players: [...row.players],
    summary: summaryOf(row),
    detail: row.bits.slice(0, 8),
    bag: row.bag,
    chest: row.chest,
    shannon: SHANNON + '/tx/' + row.hash,
  };
}

function playerCounts() {
  const counts = new Map();
  for (const row of feed) {
    for (const player of row.players) counts.set(player, (counts.get(player) || 0) + 1);
  }
  return [...counts.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, 12)
    .map(([address, count]) => ({ address, count, shannon: SHANNON + '/address/' + address }));
}

function feedPayload(actions) {
  return {
    chain: CHAIN,
    network: 'Shannon',
    block: headBlock,
    contracts: CONTRACTS.map((item) => ({ ...item, shannon: SHANNON + '/address/' + item.address })),
    players: playerCounts(),
    actions: (actions || feed).map(publicRow),
  };
}

function involves(row, address) {
  const needle = address.toLowerCase();
  if ((row.from || '').toLowerCase() === needle) return true;
  if ((row.to || '').toLowerCase() === needle) return true;
  for (const player of row.players) {
    if (player.toLowerCase() === needle) return true;
  }
  return false;
}

async function loadTx(hash) {
  const key = hash.toLowerCase();
  const full = await provider.getTransaction(key);
  if (!full) return feedByHash.has(key) ? publicRow(feedByHash.get(key)) : null;
  const receipt = await provider.getTransactionReceipt(key);
  if (receipt) {
    for (const log of receipt.logs) ingestLog(log);
  }
  const row = touchRow(key, receipt ? receipt.blockNumber : full.blockNumber);
  row.from = full.from || row.from;
  row.to = full.to || row.to;
  row.action = actionOf(full.to, full.data);
  row.kind = kindOf(full.to);
  if (full.from) rememberPlayer(row, full.from);
  if (receipt) {
    row.phase = Number(receipt.status) === 1 ? 'executed' : 'reverted';
    row.gas = Number(receipt.gasUsed);
    row.block = Number(receipt.blockNumber);
  } else {
    row.phase = 'mempool';
  }
  return publicRow(row);
}

async function scanGameLogs() {
  if (scanning) return;
  scanning = true;
  try {
    const head = await provider.getBlockNumber();
    headBlock = Number(head);
    if (resolveReady) {
      resolveReady();
      resolveReady = null;
    }
    if (scanFrom == null) scanFrom = Math.max(0, headBlock - 6000);
    let guard = 0;
    while (scanFrom <= headBlock && guard < 6) {
      const to = Math.min(headBlock, scanFrom + scanSpan - 1);
      try {
        const logs = await provider.getLogs({ address: WATCH, fromBlock: scanFrom, toBlock: to });
        for (const log of logs) ingestLog(log);
        scanFrom = to + 1;
        scanSpan = Math.min(800, scanSpan + 100);
      } catch (error) {
        scanSpan = Math.max(40, Math.floor(scanSpan / 2));
        console.error('scan', error.message);
        break;
      }
      guard += 1;
    }
  } catch (error) {
    console.error('scan', error.message);
    if (resolveReady) {
      resolveReady();
      resolveReady = null;
    }
  } finally {
    scanning = false;
  }
}

function scanLoop() {
  const tick = () => {
    scanGameLogs().finally(() => setTimeout(tick, 2500));
  };
  tick();
}

function sendJson(res, status, data) {
  const body = Buffer.from(JSON.stringify(data));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': body.length,
  });
  res.end(body);
}

const isMain = process.argv[1]
  && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href;

export async function handleExplorer(req, res, pathname) {
  const url = new URL(req.url, 'http://127.0.0.1');
  const route = pathname || url.pathname;
  if (route === '/events') {
    sse(req, res);
    return true;
  }
  if (route === '/api/feed') {
    sendJson(res, 200, feedPayload());
    return true;
  }
  if (route === '/api/tx') {
    const hash = url.searchParams.get('hash') || '';
    if (!/^0x[0-9a-fA-F]{64}$/.test(hash)) {
      sendJson(res, 400, { error: 'That is not a transaction hash.' });
      return true;
    }
    try {
      const row = await loadTx(hash);
      if (!row) sendJson(res, 404, { error: 'No transaction with that hash.' });
      else sendJson(res, 200, row);
    } catch (error) {
      console.error('tx', error.message);
      sendJson(res, 502, { error: 'Shannon did not return that transaction.' });
    }
    return true;
  }
  if (route === '/api/address') {
    let address;
    try {
      address = ethers.getAddress(url.searchParams.get('a') || '');
    } catch {
      sendJson(res, 400, { error: 'That is not an address.' });
      return true;
    }
    const payload = feedPayload(feed.filter((row) => involves(row, address)));
    payload.address = address;
    payload.shannon = SHANNON + '/address/' + address;
    payload.players = [];
    sendJson(res, 200, payload);
    return true;
  }
  if (route === '/api/move' && req.method === 'POST') {
    proxyMove(req, res);
    return true;
  }
  const page = route === '/explore' || route === '/explore.html'
    || (isMain && (route === '/' || route === '/index.html'));
  if (page) {
    const html = Buffer.from(applyLayout(fs.readFileSync(pagePath, 'utf8')));
    res.writeHead(200, {
      'Content-Type': 'text/html; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': html.length,
    });
    res.end(html);
    return true;
  }
  if (route === '/gui/inventory.png') {
    sendFile(res, guiPath, 'image/png');
    return true;
  }
  const icon = route.match(/^\/icons\/([a-z0-9_]+)\.png$/);
  if (icon) {
    await serveIcon(res, icon[1]);
    return true;
  }
  return false;
}

if (isMain) {
  const server = http.createServer(async (req, res) => {
    try {
      if (await handleExplorer(req, res)) return;
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('not found');
    } catch (error) {
      if (!res.headersSent) {
        res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
      }
      res.end('error');
      console.error(error.message);
    }
  });
  server.listen(PORT, '127.0.0.1', () => {
    console.log('SkyWord dashboard http://127.0.0.1:' + PORT);
    if (WORD) console.log('word ' + WORD);
    if (AOTV) console.log('aotv ' + AOTV);
    console.log('hold ' + HOLD_PRETTY);
    console.log('gear ' + GEAR_PRETTY);
  });
}

if (chain.wordMigration) {
  const seeded = noteSent(chain.wordMigration, PURSE, WORD);
  settle(seeded).catch(() => {});
} else if (chain.migration) {
  const seeded = noteSent(chain.migration, PURSE, HOLD_PRETTY);
  settle(seeded).catch(() => {});
}

tailLog();
scanLoop();
confirmLoop();
