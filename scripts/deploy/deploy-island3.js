// SkyIsland3 (full onchain co-op): estimate, deploy, import every SkyIsland2 deed, verify, close, configure.
// Never prints a private key; keys stay in server/plugins/SomniaSkyblock/secrets.yml.
//   node deploy-island3.js estimate   compile + estimateGas for deploy and the import (no tx)
//   node deploy-island3.js deploy     deploy from the admin purse (aborts if the estimate passes BUDGET STT)
//   node deploy-island3.js snapshot   read SkyIsland2 state (server stopped) -> tmp/island3/snapshot.json
//   node deploy-island3.js migrate    migrateIsland / migrateProgress / migrateNextId (resumable)
//   node deploy-island3.js verify     compare SkyIsland3 with the snapshot and with SkyIsland2, field by field
//   node deploy-island3.js close      closeMigration
//   node deploy-island3.js config     profile-contract -> SkyIsland3 (live + src config.yml), explorer/chain.json
const fs = require("fs");
const solc = require("solc");
const { ethers } = require("ethers");
const root = process.env.ROOT || "C:/TEMP/somnia-skyblock";
const RPC = process.env.RPC || "https://api.infra.testnet.somnia.network";
const SECRETS = root + "/server/plugins/SomniaSkyblock/secrets.yml";
const CONFIG = root + "/server/plugins/SomniaSkyblock/config.yml";
const SRC_CONFIG = root + "/src/main/resources/config.yml";
const PLAYERS = root + "/server/plugins/SomniaSkyblock/players";
const OUT = root + "/tmp/island3";
const STATE = OUT + "/deploy.json";
const SNAP = OUT + "/snapshot.json";
const BUDGET = ethers.parseEther(process.env.BUDGET || "0.5");
const fee = { maxFeePerGas: ethers.parseUnits("10", "gwei"), maxPriorityFeePerGas: ethers.parseUnits("1", "gwei") };
const provider = new ethers.JsonRpcProvider(RPC, Number(process.env.CHAIN || 50312), { staticNetwork: true });
const say = (l) => process.stdout.write(l + "\n");
const blame = (e) => String((e && (e.shortMessage || e.reason || e.message)) || "failed").replace(/0x[0-9a-fA-F]{64}/g, "0x…").slice(0, 300);
fs.mkdirSync(OUT, { recursive: true });

function secret(name) {
  const m = fs.readFileSync(SECRETS, "utf8").match(new RegExp("^" + name + ":\\s*['\"]?(?:0x)?([0-9a-fA-F]{64})['\"]?", "m"));
  return m ? "0x" + m[1] : null;
}
function admin() {
  const k = secret("admin-key");
  if (!k) throw new Error("admin-key missing in secrets.yml");
  return new ethers.NonceManager(new ethers.Wallet(k, provider));
}
const signerAddress = () => new ethers.Wallet(secret("signer-key")).address;
const cfgAddr = (k) => { const m = fs.readFileSync(CONFIG, "utf8").match(new RegExp("\\n\\s*" + k + ":\\s*['\"]?(0x[0-9a-fA-F]{40})")); return m ? ethers.getAddress(m[1]) : null; };
const state = () => (fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {});
const save = (s) => fs.writeFileSync(STATE, JSON.stringify(s, null, 2));

function compile() {
  const dir = root + "/contracts";
  const sources = {};
  for (const f of ["Attested.sol", "SkyIsland3.sol"]) sources[f] = { content: fs.readFileSync(dir + "/" + f, "utf8") };
  const out = JSON.parse(solc.compile(JSON.stringify({ language: "Solidity", sources,
    settings: { viaIR: true, optimizer: { enabled: true, runs: 200 }, evmVersion: "cancun", outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } } } })));
  const errors = (out.errors || []).filter((e) => e.severity === "error");
  if (errors.length) throw new Error(errors.map((e) => e.formattedMessage).join("\n"));
  const c = out.contracts["SkyIsland3.sol"].SkyIsland3;
  fs.writeFileSync(dir + "/SkyIsland3.abi.json", JSON.stringify(c.abi, null, 2));
  return { abi: c.abi, bytecode: "0x" + c.evm.bytecode.object };
}

const V2_ABI = [
  "function nextId() view returns (uint256)", "function ownerOf(uint256) view returns (address)", "function balanceOf(address) view returns (uint256)",
  "function memberIsland(address) view returns (uint256)", "function coopOf(uint256) view returns (address owner, address[] members)",
  "function progress(address) view returns (uint64 miningXp,uint64 farmingXp,uint64 foragingXp,uint64 cobble,uint64 oak,uint64 wheat,uint64 enchantedCobble,uint64 enchantedOak)",
];

async function record(s, label, tx) {
  const r = await tx.wait();
  const price = r.gasPrice || r.effectiveGasPrice || 0n;
  const cost = r.gasUsed * price;
  s.txs = s.txs || [];
  s.txs.push({ label, hash: tx.hash, gasUsed: String(r.gasUsed), gasPrice: String(price), feeWei: String(cost), status: r.status });
  s.spentWei = String(BigInt(s.spentWei || "0") + cost);
  save(s);
  say("  " + label + " status " + r.status + " gas " + r.gasUsed + " @ " + ethers.formatUnits(price, "gwei") + " gwei = " + ethers.formatEther(cost) + " STT tx " + tx.hash);
  if (r.status !== 1) throw new Error(label + " failed");
  return r;
}

async function estimate() {
  const c = compile();
  const a = new ethers.Wallet(secret("admin-key")).address;
  const f = new ethers.ContractFactory(c.abi, c.bytecode);
  const keysAddr = cfgAddr("keys-contract");
  const d = await f.getDeployTransaction(signerAddress(), keysAddr);
  const gas = await provider.estimateGas({ from: a, data: d.data });
  const price = (await provider.getFeeData()).gasPrice;
  // import: about 0.9M gas per deed on Somnia (measured on SkyIsland2.migrateIsland), plus nextId and close
  const deeds = Number(await new ethers.Contract(cfgAddr("profile-contract"), V2_ABI, provider).nextId()) - 1;
  const importGas = BigInt(deeds) * 1_000_000n + 400_000n;
  const total = (gas + importGas) * price;
  say("admin " + a + " balance " + ethers.formatEther(await provider.getBalance(a)) + " STT");
  say("signer " + signerAddress() + " keys " + keysAddr + " deeds to import " + deeds);
  say("deploy gas " + gas + ", import gas ~" + importGas + ", gas price " + ethers.formatUnits(price, "gwei") + " gwei");
  say("estimated total " + ethers.formatEther(total) + " STT (budget " + ethers.formatEther(BUDGET) + ")");
  return { total, gas };
}

async function deploy() {
  const s = state();
  if (s.address) { say("already deployed " + s.address); return; }
  const { total } = await estimate();
  if (total > BUDGET) throw new Error("estimate " + ethers.formatEther(total) + " STT is over the budget; nothing sent");
  const c = compile();
  const w = admin();
  const before = await provider.getBalance(await w.getAddress());
  s.balanceBefore = String(before);
  save(s);
  const f = new ethers.ContractFactory(c.abi, c.bytecode, w);
  const contract = await f.deploy(signerAddress(), cfgAddr("keys-contract"), { ...fee });
  const tx = contract.deploymentTransaction();
  await record(s, "deploy SkyIsland3", tx);
  s.address = await contract.getAddress();
  s.signer = signerAddress();
  s.keys = cfgAddr("keys-contract");
  save(s);
  say("SkyIsland3 " + s.address);
}

async function snapshot() {
  const old = cfgAddr("profile-contract");
  const s = state();
  if (s.address && old.toLowerCase() === s.address.toLowerCase()) throw new Error("config already points at SkyIsland3");
  const v2 = new ethers.Contract(old, V2_ABI, provider);
  const nextId = Number(await v2.nextId());
  const wallets = new Set();
  for (const f of fs.readdirSync(PLAYERS).filter((n) => n.endsWith(".yml"))) {
    const m = fs.readFileSync(PLAYERS + "/" + f, "utf8").match(/^wallet:\s*['"]?(0x[0-9a-fA-F]{40})/m);
    if (m) wallets.add(ethers.getAddress(m[1]));
  }
  const islands = [];
  for (let id = 1; id < nextId; id++) {
    const owner = await v2.ownerOf(id);
    const coop = await v2.coopOf(id);
    islands.push({ id, owner, deedOwner: coop.owner, members: [...coop.members] });
    wallets.add(owner);
    for (const m of coop.members) wallets.add(m);
  }
  const players = {};
  for (const w of wallets) {
    if (w === ethers.ZeroAddress) continue;
    const p = await v2.progress(w);
    players[w] = { memberIsland: String(await v2.memberIsland(w)), balanceOf: String(await v2.balanceOf(w)), progress: p.toArray().map(String) };
  }
  const snap = { at: Date.now(), block: await provider.getBlockNumber(), from: old, nextId, islands, players };
  fs.writeFileSync(SNAP, JSON.stringify(snap, null, 2));
  say("snapshot of " + old + ": nextId " + nextId + ", " + islands.length + " deed(s), " + Object.keys(players).length + " wallet(s)");
  for (const i of islands) say("  deed #" + i.id + " owner " + i.owner + " members [" + i.members.join(",") + "]");
  const withProgress = Object.entries(players).filter(([, r]) => r.progress.some((v) => v !== "0"));
  say("  wallets with legacy progress: " + withProgress.length);
}

async function migrate() {
  const s = state();
  const snap = JSON.parse(fs.readFileSync(SNAP, "utf8"));
  const c = new ethers.Contract(s.address, JSON.parse(fs.readFileSync(root + "/contracts/SkyIsland3.abi.json", "utf8")), admin());
  s.migrated = s.migrated || {};
  const done = (k) => s.migrated[k];
  const mark = (k) => { s.migrated[k] = true; save(s); };
  for (const i of snap.islands) {
    if (done("island" + i.id) || i.owner === ethers.ZeroAddress) continue;
    await record(s, "migrateIsland #" + i.id, await c.migrateIsland(i.id, i.owner, i.members, { ...fee }));
    mark("island" + i.id);
  }
  for (const [w, r] of Object.entries(snap.players)) {
    if (!r.progress.some((v) => v !== "0") || done("progress" + w)) continue;
    await record(s, "migrateProgress " + w, await c.migrateProgress(w, r.progress.map(BigInt), { ...fee }));
    mark("progress" + w);
  }
  if (BigInt(snap.nextId) > await c.nextId()) {
    await record(s, "migrateNextId " + snap.nextId, await c.migrateNextId(snap.nextId, { ...fee }));
    mark("nextId");
  }
}

async function verify() {
  const s = state();
  const snap = JSON.parse(fs.readFileSync(SNAP, "utf8"));
  const v3 = new ethers.Contract(s.address, JSON.parse(fs.readFileSync(root + "/contracts/SkyIsland3.abi.json", "utf8")), provider);
  const v2 = new ethers.Contract(snap.from, V2_ABI, provider);
  let bad = 0, n = 0;
  const check = (ok, what) => { n++; if (!ok) { bad++; say("  MISMATCH " + what); } };
  check(Number(await v3.nextId()) === snap.nextId && Number(await v2.nextId()) === snap.nextId, "nextId " + snap.nextId);
  for (const i of snap.islands) {
    const a = await v3.coopOf(i.id);
    const b = await v2.coopOf(i.id);
    const m3 = [...a.members].join(","), m2 = [...b.members].join(",");
    check((await v3.ownerOf(i.id)) === i.owner && (await v2.ownerOf(i.id)) === i.owner, "deed #" + i.id + " owner");
    check(a.owner === i.deedOwner && b.owner === i.deedOwner, "deed #" + i.id + " coop owner");
    check(m3 === i.members.join(",") && m2 === m3, "deed #" + i.id + " members [" + m3 + "] vs [" + m2 + "]");
    say("  deed #" + i.id + " owner " + i.owner + " members [" + m3 + "] ok");
  }
  for (const [w, r] of Object.entries(snap.players)) {
    check(String(await v3.memberIsland(w)) === r.memberIsland && String(await v2.memberIsland(w)) === r.memberIsland, "memberIsland " + w);
    check(String(await v3.balanceOf(w)) === r.balanceOf, "balanceOf " + w);
    const p = (await v3.progress(w)).toArray().map(String);
    check(p.join(",") === r.progress.join(","), "progress " + w);
  }
  check((await v3.signer()) === s.signer && (await v3.keys()) === s.keys, "signer and keys");
  say("verify: " + n + " checks, " + bad + " mismatch(es)" + (bad === 0 ? " - all imported state matches" : ""));
  if (bad) process.exit(2);
}

async function close() {
  const s = state();
  const c = new ethers.Contract(s.address, JSON.parse(fs.readFileSync(root + "/contracts/SkyIsland3.abi.json", "utf8")), admin());
  if (!(await c.migrating())) { say("already closed"); return; }
  await record(s, "closeMigration", await c.closeMigration({ ...fee }));
  const a = new ethers.Wallet(secret("admin-key")).address;
  const after = await provider.getBalance(a);
  s.balanceAfter = String(after);
  save(s);
  say("spent " + ethers.formatEther(BigInt(s.spentWei)) + " STT in " + s.txs.length + " tx; admin balance " + ethers.formatEther(after) + " STT");
}

function patch(file, key, value) {
  if (!fs.existsSync(file)) return false;
  let text = fs.readFileSync(file, "utf8");
  const re = new RegExp("(\\n\\s*" + key + ":\\s*)['\"]?[^'\"\\n]*['\"]?");
  if (!re.test(text)) return false;
  text = text.replace(re, "$1'" + value + "'");
  fs.writeFileSync(file, text);
  return true;
}

function addAfter(file, anchorKey, key, value) {
  if (!fs.existsSync(file)) return;
  let text = fs.readFileSync(file, "utf8");
  if (new RegExp("\\n\\s*" + key + ":").test(text)) { patch(file, key, value); return; }
  text = text.replace(new RegExp("(\\n(\\s*)" + anchorKey + ":[^\\n]*)"), "$1\n$2" + key + ": '" + value + "'");
  fs.writeFileSync(file, text);
}

function config() {
  const s = state();
  const snap = JSON.parse(fs.readFileSync(SNAP, "utf8"));
  for (const f of [CONFIG, SRC_CONFIG]) {
    const had = patch(f, "profile-contract", s.address);
    if (had) addAfter(f, "profile-contract", "old-profile-contract", snap.from);
    say((had ? "patched " : "no profile-contract in ") + f);
  }
  const chainPath = root + "/explorer/chain.json";
  if (fs.existsSync(chainPath)) {
    const chain = JSON.parse(fs.readFileSync(chainPath, "utf8"));
    chain.legacy = chain.legacy || {};
    chain.legacy.island2 = snap.from;
    chain.island = s.address;
    chain.islandTx = (s.txs || []).find((t) => t.label === "deploy SkyIsland3").hash;
    fs.writeFileSync(chainPath, JSON.stringify(chain, null, 2) + "\n");
    say("patched explorer/chain.json");
  }
}

const cmd = process.argv[2];
const run = { estimate, deploy, snapshot, migrate, verify, close, config: async () => config() }[cmd];
if (!run) { say("usage: estimate|deploy|snapshot|migrate|verify|close|config"); process.exit(1); }
run().catch((e) => { process.stderr.write(blame(e) + "\n"); process.exit(1); });
