// Launch event leaderboard. The Paper plugin writes cumulative counters to
// server/plugins/SomniaSkyblock/event/board.json every 30 s (EventTally.java); this module keeps the event
// window, the start snapshot, review flags and disqualifications in site/data/event.json, and serves
// /api/event (public) and /api/admin/event* (signed-in, same-origin). Reading one small JSON file every
// 30 s is the only load it adds.
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const dataFile = path.join(here, 'data', 'event.json');
const boardFile = path.join(here, '..', 'server', 'plugins', 'SomniaSkyblock', 'event', 'board.json');

export const PRIZES = [100, 50];
const HOUR = 3600 * 1000;

// XP per skill gained inside one rolling hour above which a player is flagged for review (not banned).
const LIMITS = {
  mining: 40000,
  farming: 40000,
  foraging: 40000,
  combat: 60000,
  fishing: 20000,
  enchanting: 100000,
  carpentry: 100000,
};
const RULES = {
  activeMinutes24h: 1000, // about 17 of the last 24 hours with at least one action every minute
  steadyActiveMinutes: 55, // of the last 60 minutes, with
  steadyCv: 0.1, // per-minute action counts almost identical (coefficient of variation below this)
  gapCv: 0.05, // and the last 300+ action intervals near-identical (macro timing)
  sharedIp: 3, // accounts on one public IP since the start
};

let state = load();
let board = null;
let boardMtime = 0;

function blank() {
  return { start: 0, end: 0, stoppedAt: 0, reviewed: false, baseline: null, last: null, flags: {}, dq: {} };
}

function load() {
  try {
    return Object.assign(blank(), JSON.parse(fs.readFileSync(dataFile, 'utf8')));
  } catch (error) {
    return blank();
  }
}

function save() {
  const tmp = dataFile + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state));
  fs.renameSync(tmp, dataFile);
}

function readBoard() {
  try {
    const stat = fs.statSync(boardFile);
    if (stat.mtimeMs !== boardMtime) {
      board = JSON.parse(fs.readFileSync(boardFile, 'utf8'));
      boardMtime = stat.mtimeMs;
    }
  } catch (error) {
    // keep the previous board; the plugin rewrites it every 30 s
  }
  return board;
}

export function phase(now = Date.now()) {
  if (!state.start) return 'idle';
  if (now < state.start) return 'scheduled';
  if (state.stoppedAt || now >= state.end) return 'ended';
  return 'running';
}

function endsAt() {
  return state.stoppedAt ? Math.min(state.stoppedAt, state.end) : state.end;
}

function snapshot(b) {
  const players = {};
  for (const p of b.players || []) {
    players[p.uuid] = { sbXp: p.sbXp || 0, level: p.level || 0, tx: p.tx || 0, txBy: p.txBy || {} };
  }
  return { at: b.at, tx: b.tx || 0, txBy: b.txBy || {}, players };
}

function diffBy(now, base) {
  const out = {};
  for (const [k, v] of Object.entries(now || {})) {
    const d = v - ((base || {})[k] || 0);
    if (d > 0) out[k] = d;
  }
  return out;
}

function flag(uuid, type, value, text) {
  const list = state.flags[uuid] || (state.flags[uuid] = []);
  const row = list.find((f) => f.type === type);
  if (row) {
    row.lastAt = Date.now();
    if (typeof value === 'number' && value > (row.max || 0)) row.max = value;
    return;
  }
  list.push({ type, text, firstAt: Date.now(), lastAt: Date.now(), max: typeof value === 'number' ? value : 0 });
}

function checkFlags(b) {
  const ipCount = {};
  for (const p of b.players || []) {
    if (p.dev || !p.ipTag || p.lastSeen < state.start) continue;
    ipCount[p.ipTag] = (ipCount[p.ipTag] || 0) + 1;
  }
  for (const p of b.players || []) {
    if (p.dev || p.lastSeen < state.start) continue;
    const rate = p.rate || {};
    for (const [skill, limit] of Object.entries(LIMITS)) {
      const v = (rate.xpHour || {})[skill] || 0;
      if (v > limit) flag(p.uuid, 'xp-' + skill, v, skill + ' XP in one hour above ' + limit.toLocaleString('en-US'));
    }
    if ((rate.active24h || 0) >= RULES.activeMinutes24h) {
      flag(p.uuid, 'continuous', rate.active24h, 'active ' + RULES.activeMinutes24h + '+ minutes in 24 hours');
    }
    if ((rate.active60 || 0) >= RULES.steadyActiveMinutes && rate.steadyCv >= 0 && rate.steadyCv < RULES.steadyCv) {
      flag(p.uuid, 'steady', rate.active60, 'the same action count every minute for an hour');
    }
    if (rate.gapCv >= 0 && rate.gapCv < RULES.gapCv && (rate.gapN || 0) >= 300 && (rate.active60 || 0) >= 30) {
      flag(p.uuid, 'macro', rate.gapCv, 'machine-regular action timing (interval CV ' + rate.gapCv + ')');
    }
    if (p.ipTag && ipCount[p.ipTag] >= RULES.sharedIp) {
      flag(p.uuid, 'shared-ip', ipCount[p.ipTag], ipCount[p.ipTag] + ' accounts from one IP');
    }
  }
}

function standings(b) {
  const base = state.baseline || { players: {}, tx: 0, txBy: {} };
  const rows = [];
  let players = 0;
  for (const p of b.players || []) {
    if (p.lastSeen < state.start) continue;
    if (!p.dev) players++;
    const from = base.players[p.uuid] || { sbXp: 0, level: 0, tx: 0, txBy: {} };
    const xp = Math.max(0, (p.sbXp || 0) - from.sbXp);
    const lv = Math.max(0, (p.level || 0) - from.level);
    const tx = Math.max(0, (p.tx || 0) - from.tx);
    if (xp === 0 && lv === 0 && tx === 0) continue;
    rows.push({ uuid: p.uuid, name: p.name || '?', dev: !!p.dev, level: p.level || 0, levelGained: lv, xpGained: xp, tx, txBy: diffBy(p.txBy, from.txBy) });
  }
  rows.sort((a, c) => c.levelGained - a.levelGained || c.xpGained - a.xpGained || c.tx - a.tx || a.name.localeCompare(c.name));
  return {
    at: b.at,
    rows,
    stats: { tx: Math.max(0, (b.tx || 0) - (base.tx || 0)), players, txBy: diffBy(b.txBy, base.txBy) },
  };
}

function sample() {
  const now = Date.now();
  const p = phase(now);
  if (p !== 'running') return;
  const b = readBoard();
  if (!b || !b.at) return;
  if (!state.baseline) {
    state.baseline = snapshot(b);
  }
  if (state.last && state.last.at === b.at) return;
  if (b.at > endsAt()) return; // written after the end: keep the frozen view
  checkFlags(b);
  state.last = standings(b);
  save();
}

setInterval(() => {
  try {
    sample();
  } catch (error) {
    console.error('event sample', error);
  }
}, 15000).unref();
setTimeout(() => {
  try { sample(); } catch (error) {}
}, 2000).unref();

function json(res, status, body) {
  const raw = Buffer.from(JSON.stringify(body));
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': raw.length,
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(raw);
}

function publicView() {
  const now = Date.now();
  const last = state.last || { at: 0, rows: [], stats: { tx: 0, players: 0, txBy: {} } };
  const rows = last.rows.filter((r) => !r.dev && !state.dq[r.uuid]).slice(0, 100).map((r, i) => ({
    rank: i + 1,
    name: r.name,
    level: r.level,
    levelGained: r.levelGained,
    xpGained: r.xpGained,
    tx: r.tx,
    prize: PRIZES[i] || 0,
  }));
  return {
    phase: phase(now),
    now,
    start: state.start,
    end: state.start ? endsAt() : 0,
    reviewed: !!state.reviewed,
    prizes: PRIZES,
    updated: last.at,
    stats: last.stats,
    rows,
  };
}

function adminView() {
  const view = publicView();
  const last = state.last || { rows: [] };
  const seen = new Set(last.rows.map((r) => r.uuid));
  const rows = last.rows.map((r) => ({ ...r, flags: state.flags[r.uuid] || [], dq: state.dq[r.uuid] || null }));
  for (const uuid of Object.keys(state.flags)) {
    if (!seen.has(uuid)) rows.push({ uuid, name: nameOf(uuid), levelGained: 0, xpGained: 0, tx: 0, flags: state.flags[uuid], dq: state.dq[uuid] || null });
  }
  return { ...view, all: rows, limits: LIMITS, rules: RULES, baselineAt: state.baseline ? state.baseline.at : 0 };
}

function nameOf(uuid) {
  const b = board || readBoard();
  const p = b && (b.players || []).find((x) => x.uuid === uuid);
  return p ? p.name : uuid.slice(0, 8);
}

function time(value, fallback) {
  if (value === undefined || value === null || value === '') return fallback;
  const n = typeof value === 'number' ? value : Date.parse(value);
  return Number.isFinite(n) ? n : NaN;
}

export async function handleEvent(req, res, pathname, ward) {
  if (pathname === '/api/event' && req.method === 'GET') {
    json(res, 200, publicView());
    return true;
  }
  if (!pathname.startsWith('/api/admin/event')) return false;
  if (req.method !== 'GET' && !ward.originOk(req)) {
    json(res, 403, { error: 'Wrong origin.' });
    return true;
  }
  if (!ward.authed(req)) {
    json(res, 401, { error: 'Sign in first.' });
    return true;
  }
  try {
    if (pathname === '/api/admin/event' && req.method === 'GET') {
      readBoard();
      json(res, 200, { ...adminView(), boardAt: board ? board.at : 0 });
      return true;
    }
    const body = req.method === 'POST' ? await ward.readBody(req) : {};
    if (pathname === '/api/admin/event/start' && req.method === 'POST') {
      const p = phase();
      if ((p === 'running' || p === 'scheduled') && !body.restart) {
        json(res, 409, { error: 'The event is already ' + p + '. Stop it first.' });
        return true;
      }
      if (p === 'ended' && !body.restart) {
        json(res, 409, { error: 'An event already ended. Starting again wipes its results; confirm to restart.' });
        return true;
      }
      const start = time(body.start, Date.now());
      const end = time(body.end, start + 72 * HOUR);
      if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start || end - start > 30 * 24 * HOUR) {
        json(res, 400, { error: 'Pick a start and an end after it (at most 30 days).' });
        return true;
      }
      state = { ...blank(), start, end };
      save();
      sample();
      json(res, 200, adminView());
      return true;
    }
    if (pathname === '/api/admin/event/stop' && req.method === 'POST') {
      if (phase() !== 'running' && phase() !== 'scheduled') {
        json(res, 409, { error: 'Nothing is running.' });
        return true;
      }
      sample();
      state.stoppedAt = Date.now();
      if (state.stoppedAt < state.start) state.start = 0; // a scheduled event that never began
      save();
      json(res, 200, adminView());
      return true;
    }
    if (pathname === '/api/admin/event/dq' && req.method === 'POST') {
      const uuid = String(body.uuid || '');
      if (!/^[0-9a-f-]{36}$/.test(uuid)) {
        json(res, 400, { error: 'Unknown player.' });
        return true;
      }
      if (body.on) state.dq[uuid] = { at: Date.now(), note: String(body.note || '').replace(/[<>]/g, '').slice(0, 200) };
      else delete state.dq[uuid];
      save();
      json(res, 200, adminView());
      return true;
    }
    if (pathname === '/api/admin/event/review' && req.method === 'POST') {
      state.reviewed = !!body.on;
      save();
      json(res, 200, adminView());
      return true;
    }
    json(res, 404, { error: 'Not found.' });
    return true;
  } catch (error) {
    json(res, 400, { error: 'Could not read that.' });
    return true;
  }
}
