// Live Shannon checks for the two-mode contracts: gas (old vs mode A vs mode B, same call), latency, attacks.
// Two throwaway wallets are created in memory, funded with a little STT from the admin purse, and swept back.
// t1 stays in mode A (key "on the server"); t2 calls SkyKeys.optIn() and then plays the attacker who holds its key.
// Never prints a private key.  node attack-test.js
const fs = require("fs");
const { ethers } = require("ethers");
const { attest } = require("./attest.js");
const root = process.env.ROOT || "C:/TEMP/somnia-skyblock";
const RPC = process.env.RPC || "https://api.infra.testnet.somnia.network";
const CHAIN = Number(process.env.CHAIN || 50312);
const provider = new ethers.JsonRpcProvider(RPC, CHAIN, { staticNetwork: true });
const fee = { maxFeePerGas: ethers.parseUnits("10", "gwei"), maxPriorityFeePerGas: ethers.parseUnits("1", "gwei") };
const say = (l) => process.stdout.write(l + "\n");
const SECRETS = root + "/server/plugins/SomniaSkyblock/secrets.yml";
const secret = (n) => { const m = fs.readFileSync(SECRETS, "utf8").match(new RegExp("^" + n + ":\\s*['\"]?(?:0x)?([0-9a-fA-F]{64})", "m")); return m ? "0x" + m[1] : null; };
const ABI = (n) => JSON.parse(fs.readFileSync(root + "/contracts/" + n + ".abi.json", "utf8"));
const S = JSON.parse(fs.readFileSync(root + "/tmp/signer/deploy.json", "utf8"));
const ERRORS = new ethers.Interface([...new Set(["SkySkills3", "SkyWord2", "SkyRealm2", "SkyIsland2", "SkyKeys"].flatMap((n) => ABI(n).filter((x) => x.type === "error").map((x) => ethers.ErrorFragment.from(x).format("full")))), "error Error(string)"]);
const signer = new ethers.SigningKey(secret("signer-key"));
const results = { gas: {}, attacks: [], txs: {} };
let nonceSeed = BigInt(Date.now()) << 12n;

async function receipt(hash) {
  for (let i = 0; i < 240; i++) {
    const r = await provider.getTransactionReceipt(hash);
    if (r) return r;
    await new Promise((ok) => setTimeout(ok, 500));
  }
  throw new Error("no receipt for " + hash);
}
async function raw(w, to, data, value = 0n, gasLimit = 6_000_000n) {
  const t0 = Date.now();
  const tx = await w.sendTransaction({ to, data, value, gasLimit, ...fee });
  const r = await receipt(tx.hash);
  return { hash: tx.hash, status: r.status, gas: r.gasUsed, ms: Date.now() - t0 };
}
async function att(w, c, fn, args, value = 0n, opts = {}) {
  const data = c.interface.encodeFunctionData(fn, args);
  const deadline = opts.deadline ?? Math.floor(Date.now() / 1000) + 300;
  return attest(opts.key || signer, { chainId: CHAIN, player: opts.player || w.address, target: c.target, data, value, nonce: nonceSeed++, deadline });
}
async function expectRevert(label, w, to, data, value = 0n) {
  // Simulate first (free), then send it for a public on-chain receipt.
  let sim = "passed";
  try { await provider.call({ from: w.address, to, data, value }); } catch (e) {
    let name = null;
    try { const p = ERRORS.parseError(e.data); name = p && (p.name === "Error" ? p.args[0] : p.name); } catch (_) {}
    sim = name || (e.revert && (e.revert.args && e.revert.args[0] || e.revert.name)) || e.shortMessage || "reverted";
  }
  const r = await raw(w, to, data, value, 4_000_000n);
  const ok = r.status === 0;
  results.attacks.push({ label, reverted: ok, reason: sim, tx: r.hash });
  say((ok ? "REVERTED  " : "LANDED!!  ") + label + "  (" + sim + ") tx " + r.hash);
}

(async () => {
  const admin = new ethers.NonceManager(new ethers.Wallet(secret("admin-key"), provider));
  const ADMIN = await admin.getAddress();
  const t1 = new ethers.NonceManager(ethers.Wallet.createRandom().connect(provider));
  const t2 = new ethers.NonceManager(ethers.Wallet.createRandom().connect(provider));
  const T1 = await t1.getAddress(), T2 = await t2.getAddress();
  t1.address = T1; t2.address = T2;
  const FUND = ethers.parseEther(process.env.FUND || "0.4");
  for (const w of [T1, T2]) { const tx = await admin.sendTransaction({ to: w, value: FUND, ...fee }); await receipt(tx.hash); }
  say("test wallets " + T1 + " (mode A) " + T2 + " (mode B) funded " + ethers.formatEther(FUND) + " STT each from the admin purse");
  const d = S.deployed, old = S.old;
  const keys = new ethers.Contract(d.SkyKeys, ABI("SkyKeys"), provider);
  const skills = new ethers.Contract(d.SkySkills3, ABI("SkySkills3"), provider);
  const word = new ethers.Contract(d.SkyWord2, ABI("SkyWord2"), provider);
  const realm = new ethers.Contract(d.SkyRealm2, ABI("SkyRealm2"), provider);
  const island = new ethers.Contract(d.SkyIsland2, ABI("SkyIsland2"), provider);
  const oldSkills = new ethers.Contract(old.skills, ["function applyXp(uint8[],uint32[],uint16[],uint32[])"], provider);
  const oldWord = new ethers.Contract(old.word, ["function write(uint8[],uint256[])"], provider);
  const COBBLE = 1851776868n;
  const xpArgs = [[2], [10], [0], [3]];
  const wArgs = (i) => [[1], [COBBLE | (BigInt(5 + i) << 32n)]];
  try {
    // ---- mode B switch for t2 (what /exportkey does before showing a key) ----
    const opt = await raw(t2, d.SkyKeys, keys.interface.encodeFunctionData("optIn", []));
    results.gas.optIn = { gas: Number(opt.gas), ms: opt.ms, tx: opt.hash };
    say("t2 optIn status " + opt.status + " gas " + opt.gas + " " + opt.ms + " ms tx " + opt.hash + "; exported=" + (await keys.exported(T2)) + ", t1 exported=" + (await keys.exported(T1)));
    // ---- gas + latency: old / mode A bare / mode B attested, same call, 3 rounds ----
    for (let i = 0; i < 3; i++) {
      const o = await raw(t1, old.skills, oldSkills.interface.encodeFunctionData("applyXp", xpArgs));
      const a = await raw(t1, d.SkySkills3, skills.interface.encodeFunctionData("applyXp", xpArgs));
      const b = await raw(t2, d.SkySkills3, await att(t2, skills, "applyXp", xpArgs));
      results.gas["applyXp#" + i] = { old: Number(o.gas), modeA: Number(a.gas), modeB: Number(b.gas), ms: { old: o.ms, modeA: a.ms, modeB: b.ms }, status: [o.status, a.status, b.status], tx: { old: o.hash, modeA: a.hash, modeB: b.hash } };
      const ow = await raw(t1, old.word, oldWord.interface.encodeFunctionData("write", wArgs(i)));
      const aw = await raw(t1, d.SkyWord2, word.interface.encodeFunctionData("write", wArgs(i)));
      const bw = await raw(t2, d.SkyWord2, await att(t2, word, "write", wArgs(i)));
      results.gas["write#" + i] = { old: Number(ow.gas), modeA: Number(aw.gas), modeB: Number(bw.gas), ms: { old: ow.ms, modeA: aw.ms, modeB: bw.ms }, status: [ow.status, aw.status, bw.status], tx: { old: ow.hash, modeA: aw.hash, modeB: bw.hash } };
      say(`gas round ${i}: applyXp old ${o.gas} A ${a.gas} B ${b.gas} (${o.ms}/${a.ms}/${b.ms} ms) | write old ${ow.gas} A ${aw.gas} B ${bw.gas} (${ow.ms}/${aw.ms}/${bw.ms} ms) status ${[o.status, a.status, b.status, ow.status, aw.status, bw.status].join("")}`);
    }
    // ---- attacks from t2: the wallet whose key was exported ----
    await expectRevert("[B] applyXp without attestation (1,000,000 XP)", t2, d.SkySkills3, skills.interface.encodeFunctionData("applyXp", [[2], [1000000], [], []]));
    await expectRevert("[B] SkyWord write without attestation (64 items)", t2, d.SkyWord2, word.interface.encodeFunctionData("write", [[2], [COBBLE | (64n << 32n)]]));
    await expectRevert("[B] stats checkpoint without attestation", t2, d.SkyRealm2, realm.interface.encodeFunctionData("checkpoint", [999999n, 50]));
    await expectRevert("[B] island applyBatch without attestation", t2, d.SkyIsland2, island.interface.encodeFunctionData("applyBatch", [64, 64, 64]));
    await expectRevert("[B] blade craft without attestation", t2, d.SkyWord2, word.interface.encodeFunctionData("craft", [1, 3]));
    const good = await att(t2, skills, "applyXp", [[2], [7], [], []]);
    const landed = await raw(t2, d.SkySkills3, good);
    say("[B] honest attested applyXp status " + landed.status + " tx " + landed.hash);
    results.txs.honestB = landed.hash;
    await expectRevert("[B] replay of that exact attested call", t2, d.SkySkills3, good);
    const fresh = await att(t2, skills, "applyXp", [[2], [7], [], []]);
    await expectRevert("[B] attested call with the amount changed (7 -> 1,000,000)", t2, d.SkySkills3, fresh.replace(ethers.toBeHex(7, 32).slice(2), ethers.toBeHex(1000000, 32).slice(2)));
    await expectRevert("[B] attestation signed with a non-server key", t2, d.SkySkills3, await att(t2, skills, "applyXp", [[2], [7], [], []], 0n, { key: new ethers.SigningKey(ethers.hexlify(ethers.randomBytes(32))) }));
    await expectRevert("[B] expired attestation", t2, d.SkySkills3, await att(t2, skills, "applyXp", [[2], [7], [], []], 0n, { deadline: Math.floor(Date.now() / 1000) - 60 }));
    await expectRevert("[B] item into hotbar slot 9 (index 8), attested", t2, d.SkyWord2, await att(t2, word, "write", [[8], [COBBLE | (1n << 32n)]]));
    await expectRevert("[B] over the 1M per-entry cap, attested", t2, d.SkySkills3, await att(t2, skills, "applyXp", [[2], [1000001], [], []]));
    await expectRevert("[B] createIsland without attestation", t2, d.SkyIsland2, island.interface.encodeFunctionData("createIsland", []));
    // another player's attestation (made for t1) used by t1 after... t2 cannot use it:
    await expectRevert("[B] using an attestation made for another player", t2, d.SkySkills3, await att(t1, skills, "applyXp", [[2], [7], [], []]));
    // ---- mode A protections that stay on (t1) ----
    await expectRevert("[A] item into hotbar slot 9 (index 8)", t1, d.SkyWord2, word.interface.encodeFunctionData("write", [[8], [COBBLE | (1n << 32n)]]));
    await expectRevert("[A] over the 1M per-entry cap", t1, d.SkySkills3, skills.interface.encodeFunctionData("applyXp", [[2], [1000001], [], []]));
    await expectRevert("[A] blade craft (NFT mint) without attestation", t1, d.SkyWord2, word.interface.encodeFunctionData("craft", [1, 3]));
    await expectRevert("[A] enterArena without attestation", t1, d.SkyRealm2, realm.interface.encodeFunctionData("enterArena", []), ethers.parseEther("0.005"));
    // ---- SkyRealm2 exploits ----
    const unit = ethers.parseEther("0.001");
    const place = await raw(t1, d.SkyRealm2, await att(t1, realm, "placeBuyOrder", [7, 2, unit], unit * 2n), unit * 2n);
    const orderId = (await realm.nextOrder()) - 1n;
    say("victim (t1) buy order #" + orderId + " escrow 0.002 STT tx " + place.hash);
    await expectRevert("[B] fillBuyOrder exploit (take escrow without an item)", t2, d.SkyRealm2, realm.interface.encodeFunctionData("fillBuyOrder", [orderId, 2]));
    await expectRevert("buyer fills own order, attested", t1, d.SkyRealm2, await att(t1, realm, "fillBuyOrder", [orderId, 1]));
    const cancel = await raw(t1, d.SkyRealm2, realm.interface.encodeFunctionData("cancelBuyOrder", [orderId]));
    say("victim cancelled the order, escrow refunded, status " + cancel.status + " tx " + cancel.hash);
    const enter = await raw(t2, d.SkyRealm2, await att(t2, realm, "enterArena", [], ethers.parseEther("0.005")), ethers.parseEther("0.005"));
    const runId = (await realm.nextRun()) - 1n;
    say("ranked run #" + runId + " entered tx " + enter.hash);
    await expectRevert("[B] finishRun: claim wave 50 without attestation", t2, d.SkyRealm2, realm.interface.encodeFunctionData("finishRun", [runId, 50, 999]));
    await expectRevert("finishRun wave 50 seconds after entry, even attested", t2, d.SkyRealm2, await att(t2, realm, "finishRun", [runId, 50, 999]));
    await expectRevert("finishRun wave 51, attested", t2, d.SkyRealm2, await att(t2, realm, "finishRun", [runId, 51, 1]));
    const fin = await raw(t2, d.SkyRealm2, await att(t2, realm, "finishRun", [runId, 0, 0]));
    say("run closed honestly with wave 0, status " + fin.status + " tx " + fin.hash);
  } finally {
    for (const w of [t1, t2]) {
      const addr = await w.getAddress();
      const bal = await provider.getBalance(addr);
      const cost = 21000n * fee.maxFeePerGas;
      if (bal > cost) { const tx = await w.sendTransaction({ to: ADMIN, value: bal - cost, gasLimit: 21000n, ...fee }); await receipt(tx.hash); say("swept " + ethers.formatEther(bal - cost) + " STT back from " + addr); }
    }
    fs.writeFileSync(root + "/tmp/signer/attack-test.json", JSON.stringify(results, null, 2));
    const bad = results.attacks.filter((x) => !x.reverted);
    say(results.attacks.length + " attacks, " + bad.length + " landed");
    if (bad.length) process.exitCode = 3;
  }
})().catch((e) => { console.error(String(e.shortMessage || e.message).slice(0, 400)); process.exit(1); });
