// Two-mode Catharsis contracts (SkyKeys registry + Attested guarded/attested): deploy, import all onchain state from the old contracts, close migration.
// Never prints a private key. Keys live only in server/plugins/SomniaSkyblock/secrets.yml.
//   node deploy-signer.js keys                 create signer-key + admin-key in secrets.yml if missing (prints addresses)
//   node deploy-signer.js deploy               compile, deploy (resumable); the admin purse must already hold STT
//   node deploy-signer.js migrate              import islands, progress, skills, bags, blades, gear, stats; verify
//   node deploy-signer.js close                closeMigration on every new contract
//   node deploy-signer.js config               write the new addresses into config.yml (live + src) and explorer/chain.json
const fs = require("fs");
const path = require("path");
const solc = require("solc");
const { ethers } = require("ethers");
const root = process.env.ROOT || "C:/TEMP/somnia-skyblock";
const RPC = process.env.RPC || "https://api.infra.testnet.somnia.network";
const VRF = "0x0834459256bbb8d2efee23dc6c3f1722266182dd";
const SECRETS = root + "/server/plugins/SomniaSkyblock/secrets.yml";
const CONFIG = root + "/server/plugins/SomniaSkyblock/config.yml";
const OUT = root + "/tmp/signer";
const STATE = OUT + "/deploy.json";
const fee = { maxFeePerGas: ethers.parseUnits("10", "gwei"), maxPriorityFeePerGas: ethers.parseUnits("1", "gwei") };
const provider = new ethers.JsonRpcProvider(RPC, Number(process.env.CHAIN || 50312), { staticNetwork: true });
const say = (l) => process.stdout.write(l + "\n");
const blame = (e) => String((e && (e.shortMessage || e.reason || e.message)) || "failed").replace(/0x[0-9a-fA-F]{64}/g, "0x…").slice(0, 300);

function secret(name) {
  const m = fs.readFileSync(SECRETS, "utf8").match(new RegExp("^" + name + ":\\s*['\"]?(?:0x)?([0-9a-fA-F]{64})['\"]?", "m"));
  return m ? "0x" + m[1] : null;
}
function wallet(name) {
  const k = secret(name);
  if (!k) throw new Error(name + " missing in secrets.yml");
  return new ethers.NonceManager(new ethers.Wallet(k, provider));
}
const state = () => (fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {});
const save = (s) => fs.writeFileSync(STATE, JSON.stringify(s, null, 2));

function keys() {
  let text = fs.readFileSync(SECRETS, "utf8");
  for (const name of ["signer-key", "admin-key"]) {
    if (!secret(name)) {
      const w = ethers.Wallet.createRandom();
      if (!text.endsWith("\n")) text += "\n";
      text += name + ": '" + w.privateKey + "'\n";
      fs.writeFileSync(SECRETS, text);
    }
    const role = name.replace("-key", "");
    const address = new ethers.Wallet(secret(name)).address;
    // Public address next to the key, so the plugin can refuse /exportkey for server wallets.
    text = fs.readFileSync(SECRETS, "utf8");
    if (!new RegExp("^" + role + "-address:", "m").test(text)) {
      if (!text.endsWith("\n")) text += "\n";
      text += role + "-address: '" + address + "'\n";
      fs.writeFileSync(SECRETS, text);
    }
    say(role + " " + address);
  }
}

function compile() {
  const dir = root + "/contracts";
  const sources = {};
  for (const f of ["SkyKeys.sol", "Attested.sol", "SkySkills3.sol", "SkyWord2.sol", "SkyIsland2.sol", "SkyGear2.sol", "SkyHold2.sol", "SkyRealm2.sol", "SkyBin2.sol"]) {
    sources[f] = { content: fs.readFileSync(dir + "/" + f, "utf8") };
  }
  const out = JSON.parse(solc.compile(JSON.stringify({ language: "Solidity", sources,
    settings: { viaIR: true, optimizer: { enabled: true, runs: 200 }, evmVersion: "cancun", outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } } } })));
  const errors = (out.errors || []).filter((e) => e.severity === "error");
  if (errors.length) throw new Error(errors.map((e) => e.formattedMessage).join("\n"));
  const build = {};
  for (const f in out.contracts) for (const c in out.contracts[f]) {
    const x = out.contracts[f][c];
    if (!x.evm.bytecode.object) continue;
    build[c] = { abi: x.abi, bytecode: "0x" + x.evm.bytecode.object };
    if (c !== "Base64") fs.writeFileSync(dir + "/" + c + ".abi.json", JSON.stringify(x.abi, null, 2));
  }
  return build;
}

const cfgAddr = (k) => { const m = fs.readFileSync(CONFIG, "utf8").match(new RegExp("\\n\\s*" + k + ":\\s*['\"]?(0x[0-9a-fA-F]{40})")); return m ? m[1] : null; };
function oldAddresses() {
  const s = state();
  if (s.old) return s.old;
  const old = { island: cfgAddr("profile-contract"), hold: cfgAddr("hold-contract"), gear: cfgAddr("gear-contract"), word: cfgAddr("word-contract"),
    aotv: cfgAddr("aotv-contract"), realm: cfgAddr("realm-contract"), bin: cfgAddr("bin-contract"), skills: cfgAddr("skills-contract") };
  s.old = old; save(s);
  return old;
}

async function deploy() {
  const signerAddr = new ethers.Wallet(secret("signer-key")).address;
  const admin = wallet("admin-key");
  const adminAddr = await admin.getAddress();
  const old = oldAddresses();
  let bal = await provider.getBalance(adminAddr);
  say("admin " + adminAddr + " balance " + ethers.formatEther(bal));
  const min = ethers.parseEther(process.env.MIN_ADMIN || "1.6");
  if (bal < min) {
    throw new Error("admin purse " + adminAddr + " has " + ethers.formatEther(bal) + " STT; send it at least " + ethers.formatEther(min) + " STT first");
  }
  const build = compile();
  const s = state();
  s.deployed = s.deployed || {};
  s.txs = s.txs || {};
  const word1 = new ethers.Contract(old.word, ["function cobble() view returns (uint256)"], provider);
  const cobble = await word1.cobble();
  const K = () => s.deployed.SkyKeys;
  const plan = [
    ["SkyKeys", () => [signerAddr]],
    ["SkyIsland2", () => [signerAddr, K()]],
    ["SkySkills3", () => [signerAddr, K()]],
    ["Aotv2", () => []],
    ["SkyWord2", () => [s.deployed.Aotv2, cobble, signerAddr, K()]],
    ["SkyGear2", () => []],
    ["SkyHold2", () => [s.deployed.SkyIsland2, s.deployed.SkyGear2, signerAddr, K()]],
    ["SkyRealm2", () => [VRF, true, signerAddr, K()]],
    ["SkyBin2", () => [s.deployed.SkyRealm2, signerAddr, K()]],
  ];
  for (const [name, args] of plan) {
    if (s.deployed[name]) { say(name + " already at " + s.deployed[name]); continue; }
    const f = new ethers.ContractFactory(build[name].abi, build[name].bytecode, admin);
    const c = await f.deploy(...args(), { ...fee });
    await c.waitForDeployment();
    s.deployed[name] = await c.getAddress();
    s.txs[name] = c.deploymentTransaction().hash;
    save(s);
    const r = await provider.getTransactionReceipt(s.txs[name]);
    say(name + " " + s.deployed[name] + " gas " + r.gasUsed + " tx " + s.txs[name]);
  }
  say("admin balance now " + ethers.formatEther(await provider.getBalance(adminAddr)));
}

const ABI = (n) => JSON.parse(fs.readFileSync(root + "/contracts/" + n + ".abi.json", "utf8"));
async function send(c, fn, args, label) {
  const tx = await c[fn](...args, { ...fee });
  const r = await tx.wait();
  say("  " + label + " gas " + r.gasUsed + " tx " + tx.hash);
  return r;
}

async function migrate() {
  const snapPath = OUT + "/state-migrate.json";
  if (!fs.existsSync(snapPath)) throw new Error("run scan-state.js " + snapPath + " first (server stopped)");
  const snap = JSON.parse(fs.readFileSync(snapPath, "utf8"));
  const s = state();
  const d = s.deployed;
  const old = s.old;
  const admin = wallet("admin-key");
  s.migrated = s.migrated || {};
  const done = (k) => s.migrated[k];
  const mark = (k) => { s.migrated[k] = true; save(s); };
  const isl = new ethers.Contract(d.SkyIsland2, ABI("SkyIsland2"), admin);
  const sk = new ethers.Contract(d.SkySkills3, ABI("SkySkills3"), admin);
  const aotv = new ethers.Contract(d.Aotv2, ABI("Aotv2"), admin);
  const word = new ethers.Contract(d.SkyWord2, ABI("SkyWord2"), admin);
  const gear = new ethers.Contract(d.SkyGear2, ABI("SkyGear2"), admin);
  const hold = new ethers.Contract(d.SkyHold2, ABI("SkyHold2"), admin);
  const realm = new ethers.Contract(d.SkyRealm2, ABI("SkyRealm2"), admin);
  const players = snap.players;
  const nz = (a) => a.some((v) => v !== "0");
  // islands + coop
  for (const i of snap.islands) {
    if (done("island" + i.id)) continue;
    const coop = Object.entries(players).filter(([w, r]) => r.memberIsland === String(i.id) && w !== i.owner.toLowerCase()).map(([w]) => ethers.getAddress(w));
    await send(isl, "migrateIsland", [i.id, i.owner, coop], "island #" + i.id + " -> " + i.owner + (coop.length ? " coop " + coop.join(",") : ""));
    mark("island" + i.id);
  }
  if (!done("nextIsland")) { await send(isl, "migrateNextId", [snap.nextIsland], "island nextId " + snap.nextIsland); mark("nextIsland"); }
  for (const [w, r] of Object.entries(players)) {
    if (nz(r.progress) && !done("progress" + w)) {
      const p = r.progress.map(BigInt);
      await send(isl, "migrateProgress", [ethers.getAddress(w), { miningXp: p[0], farmingXp: p[1], foragingXp: p[2], cobble: p[3], oak: p[4], wheat: p[5], enchantedCobble: p[6], enchantedOak: p[7] }], "progress " + w);
      mark("progress" + w);
    }
    if ((nz(r.xp) || nz(r.col)) && !done("skills" + w)) {
      const base = (r.baseXp || []), cbase = (r.baseCol || []);
      const totals = r.xp.map((v, i) => { const x = BigInt(v) - BigInt(base[i] || 0); return x > 0n ? x : 0n; });
      const ids = [], amts = [];
      r.col.forEach((v, i) => { const x = BigInt(v) - BigInt(cbase[i] || 0); if (x > 0n) { ids.push(i); amts.push(x); } });
      await send(sk, "migrate", [ethers.getAddress(w), totals, ids, amts], "skills " + w + " xp [" + totals.join(",") + "] collections " + ids.length);
      mark("skills" + w);
    }
  }
  // blades (same serials), then the word bags with the blade address swapped
  for (const t of snap.aotv) {
    if (t.owner === ethers.ZeroAddress || done("aotv" + t.id)) continue;
    await send(aotv, "migrateMint", [t.owner, t.id], "blade #" + t.id + " -> " + t.owner);
    mark("aotv" + t.id);
  }
  if (!done("aotvHold")) { await send(aotv, "setHold", [d.SkyWord2], "blade hold = SkyWord2"); mark("aotvHold"); }
  const oldA = BigInt(old.aotv), newA = BigInt(d.Aotv2);
  const MASK160 = (1n << 160n) - 1n;
  for (const [w, r] of Object.entries(players)) {
    if ((!nz(r.words) && r.combat === "0") || done("word" + w)) continue;
    const words = r.words.map(BigInt).map((x) => (((x >> 64n) & MASK160) === oldA ? (x & ~(MASK160 << 64n)) | (newA << 64n) : x));
    if (words[8] !== 0n) { const free = words.findIndex((x, i) => i < 36 && i !== 8 && x === 0n); if (free >= 0) { words[free] = words[8]; words[8] = 0n; say("  moved slot 8 item to slot " + free + " for " + w); } else { say("  WARNING slot 8 item kept off-chain for " + w); words[8] = 0n; } }
    const slots = [], vals = [];
    words.forEach((x, i) => { if (x !== 0n) { slots.push(i); vals.push(x); } });
    await send(word, "migrate", [ethers.getAddress(w), slots, vals, BigInt(r.combat)], "word bag " + w + " slots " + slots.join(","));
    mark("word" + w);
  }
  // legacy SkyGear tokens (same ids + traits), then SkyHold bags
  for (const g of snap.gear) {
    if (done("gear" + g.id)) continue;
    await send(gear, "migrateMint", [g.id, g.owner, g.item, g.damage, g.key, g.label, g.reforge, g.enchants], "gear #" + g.id + " " + g.label + " -> " + g.owner);
    mark("gear" + g.id);
  }
  if (!done("gearHold")) { await send(gear, "setHold", [d.SkyHold2], "gear hold = SkyHold2"); mark("gearHold"); }
  for (const [w, r] of Object.entries(players)) {
    const b = r.holdBag;
    if (!b.counts.some((c) => c !== "0") || done("hold" + w)) continue;
    const slots = [], items = [], counts = [], damages = [], gears = [];
    b.counts.forEach((c, i) => { if (c !== "0") { slots.push(i); items.push(b.items[i]); counts.push(c); damages.push(b.damages[i]); gears.push(b.gear[i]); } });
    await send(hold, "migrateBag", [ethers.getAddress(w), slots, items, counts, damages, gears], "hold bag " + w + " slots " + slots.length);
    mark("hold" + w);
  }
  for (const i of snap.islands) if (i.chest) say("  WARNING island chest " + i.id + " has items (not imported)");
  for (const [w, r] of Object.entries(players)) {
    if ((r.stats !== "0" || r.bestWave !== "0") && !done("stats" + w)) {
      await send(realm, "migrateStats", [ethers.getAddress(w), BigInt(r.stats), Number(r.bestWave)], "stats " + w + " best wave " + r.bestWave);
      mark("stats" + w);
    }
    if (r.relics !== "0") say("  NOTE " + w + " holds " + r.relics + " relics on the old SkyRealm (claimable there)");
  }
  await verify(snap);
}

async function verify(snap) {
  const s = state(), d = s.deployed, old = s.old;
  const isl = new ethers.Contract(d.SkyIsland2, ABI("SkyIsland2"), provider);
  const sk = new ethers.Contract(d.SkySkills3, ABI("SkySkills3"), provider);
  const word = new ethers.Contract(d.SkyWord2, ABI("SkyWord2"), provider);
  const hold = new ethers.Contract(d.SkyHold2, ABI("SkyHold2"), provider);
  const realm = new ethers.Contract(d.SkyRealm2, ABI("SkyRealm2"), provider);
  const aotv = new ethers.Contract(d.Aotv2, ABI("Aotv2"), provider);
  const gear = new ethers.Contract(d.SkyGear2, ABI("SkyGear2"), provider);
  let bad = 0;
  const check = (c, m) => { if (!c) { bad++; say("  MISMATCH " + m); } };
  const ids = [...Array(64).keys()];
  const oldA = BigInt(old.aotv), newA = BigInt(d.Aotv2), M = (1n << 160n) - 1n;
  for (const i of snap.islands) check((await isl.ownerOf(i.id)).toLowerCase() === i.owner.toLowerCase(), "island " + i.id);
  for (const [w, r] of Object.entries(snap.players)) {
    check(String(await isl.memberIsland(w)) === r.memberIsland, "memberIsland " + w);
    check([...(await isl.progress(w))].map(String).join() === r.progress.join(), "progress " + w);
    const xp = [...(await sk.xpOf(w))].map(String);
    check(xp.join() === r.xp.map((v, i) => { const x = BigInt(v) - BigInt((r.baseXp || [])[i] || 0); return (x > 0n ? x : 0n).toString(); }).join(), "xp " + w);
    const col = [...(await sk.collectionsOf(w, ids))].map(String);
    check(col.join() === r.col.map((v, i) => { const x = BigInt(v) - BigInt((r.baseCol || [])[i] || 0); return (x > 0n ? x : 0n).toString(); }).join(), "collections " + w);
    const words = [...(await word.wordsOf(w))].map(BigInt);
    const want = r.words.map(BigInt).map((x) => (((x >> 64n) & M) === oldA ? (x & ~(M << 64n)) | (newA << 64n) : x));
    const sum = (a) => a.filter((x) => x !== 0n).map(String).sort().join();
    check(sum(words) === sum(want), "word bag " + w);
    check(String(await word.combat(w)) === r.combat, "combat " + w);
    const b = await hold.bagOf(w);
    check([...b[1]].map(String).join() === r.holdBag.counts.join() && [...b[3]].map(String).join() === r.holdBag.gear.join(), "hold bag " + w);
    check(String(await realm.stats(w)) === r.stats && String(await realm.bestWave(w)) === r.bestWave, "stats " + w);
  }
  for (const t of snap.aotv) check((await aotv.ownerOf(t.id)).toLowerCase() === t.owner.toLowerCase() && (await aotv.locked(t.id)), "blade " + t.id);
  for (const g of snap.gear) check((await gear.ownerOf(g.id)).toLowerCase() === g.owner.toLowerCase() && (await gear.labelOf(g.id)) === g.label, "gear " + g.id);
  check(Number(await isl.nextId()) === snap.nextIsland, "island nextId");
  say(bad === 0 ? "verify: all imported state matches the old contracts" : "verify: " + bad + " mismatches");
  if (bad) process.exitCode = 2;
}

async function close() {
  const s = state(), d = s.deployed;
  const admin = wallet("admin-key");
  for (const n of ["SkyIsland2", "SkySkills3", "SkyWord2", "SkyHold2", "SkyRealm2", "SkyBin2"]) {
    const c = new ethers.Contract(d[n], ABI(n), admin);
    if (await c.migrating()) await send(c, "closeMigration", [], n + " closeMigration");
    else say("  " + n + " already closed");
  }
}

function patch(file, key, value) {
  if (!fs.existsSync(file)) return;
  let text = fs.readFileSync(file, "utf8");
  const re = new RegExp("(\\n\\s*" + key + ":\\s*)(['\"]?)[^'\"\\n]*\\2");
  if (re.test(text)) text = text.replace(re, (m, a) => a + "'" + value + "'");
  else text = text.replace(/(\n\s*chain-id:[^\n]*)/, "$1\n  " + key + ": '" + value + "'");
  fs.writeFileSync(file, text);
}

function config() {
  const s = state(), d = s.deployed, old = s.old;
  for (const f of [CONFIG, root + "/src/main/resources/config.yml"]) {
    patch(f, "profile-contract", d.SkyIsland2);
    patch(f, "hold-contract", d.SkyHold2);
    patch(f, "gear-contract", d.SkyGear2);
    patch(f, "word-contract", d.SkyWord2);
    patch(f, "aotv-contract", d.Aotv2);
    patch(f, "realm-contract", d.SkyRealm2);
    patch(f, "bin-contract", d.SkyBin2);
    patch(f, "skills-contract", d.SkySkills3);
    patch(f, "keys-contract", d.SkyKeys);
    patch(f, "old-realm-contract", old.realm);
    let text = fs.readFileSync(f, "utf8");
    if (/skills-version:/.test(text)) text = text.replace(/skills-version:\s*\d+/, "skills-version: 3");
    else text = text.replace(/(\n\s*skills-contract:[^\n]*)/, "$1\n  skills-version: 3");
    fs.writeFileSync(f, text);
  }
  const chainPath = root + "/explorer/chain.json";
  const chain = fs.existsSync(chainPath) ? JSON.parse(fs.readFileSync(chainPath, "utf8")) : {};
  chain.legacy = { island: old.island, hold: old.hold, gear: old.gear, word: old.word, aotv: old.aotv, realm: old.realm, bin: old.bin, skills: old.skills };
  Object.assign(chain, { island: d.SkyIsland2, hold: d.SkyHold2, gear: d.SkyGear2, word: d.SkyWord2, aotv: d.Aotv2, realm: d.SkyRealm2, bin: d.SkyBin2, skills: d.SkySkills3, keys: d.SkyKeys,
    signer: new ethers.Wallet(secret("signer-key")).address, realmTx: s.txs.SkyRealm2, binTx: s.txs.SkyBin2 });
  fs.writeFileSync(chainPath, JSON.stringify(chain, null, 2) + "\n");
  say("config + explorer/chain.json updated");
}

const cmd = process.argv[2];
const run = { keys: async () => keys(), deploy, migrate, close, config: async () => config(),
  verify: async () => verify(JSON.parse(fs.readFileSync(OUT + "/state-migrate.json", "utf8"))) }[cmd];
if (!run) { say("usage: keys|deploy|migrate|close|config|verify"); process.exit(1); }
run().catch((e) => { process.stderr.write(blame(e) + "\n"); process.exit(1); });
