// Local anvil test for the two-mode Catharsis contracts (SkyKeys + Attested guarded/attested).
const { ethers } = require("ethers");
const { attest } = require("./attest.js");
const build = require("./build.json");
const p = new ethers.JsonRpcProvider("http://127.0.0.1:8545");
let nonceCtr = 1000n, fails = 0, passes = 0;
const gas = {};
const REJ = "NoAttestation|Expired|BadSignature"; // any of these = no valid server attestation
const ok = (c, m) => { if (!c) { console.log("FAIL", m); fails++; } else { console.log("ok  ", m); passes++; } };
const ERR = new ethers.Interface([...new Set(Object.values(build).flatMap((b) => b.abi.filter((x) => x.type === "error").map((x) => ethers.ErrorFragment.from(x).format("full")))), "error Error(string)"]);
const why = (e) => { const d = e && (e.data || (e.info && e.info.error && e.info.error.data)); try { const x = ERR.parseError(d); return x.name === "Error" ? x.args[0] : x.name; } catch (_) { return ""; } };
async function reverts(promise, label, want) {
  const simWhy = (promise && promise.simWhy) || "";
  if (promise && promise.box) promise = promise.box;
  try { const tx = await promise; const r = await tx.wait(); ok(false, label + " (did not revert, status " + r.status + ")"); }
  catch (e) {
    const s = simWhy + " " + why(e) + " " + String(e.shortMessage || e.message) + " " + (e.revert ? e.revert.name + " " + (e.revert.args || []).join(",") : "");
    if (want && !want.split("|").some((w) => s.includes(w))) ok(false, label + " reverted but not with " + want + ": " + s.slice(0, 120));
    else ok(true, label + " reverts" + (want ? " (" + want + ")" : ""));
  }
}
(async () => {
  const chainId = Number((await p.getNetwork()).chainId);
  const admin = (await p.listAccounts())[0];
  const signerKey = new ethers.SigningKey(ethers.hexlify(ethers.randomBytes(32)));
  const signerAddr = ethers.computeAddress(signerKey.publicKey);
  const fund = async (a) => (await admin.sendTransaction({ to: a, value: ethers.parseEther("10") })).wait();
  const mkW = () => ethers.Wallet.createRandom().connect(p);
  const P = mkW(), Q = mkW(), MAIN = mkW(), SIGNER = new ethers.Wallet(signerKey, p);
  for (const w of [P, Q, MAIN, SIGNER]) await fund(w.address);
  for (const w of [P, Q, MAIN, SIGNER]) { const orig = w.sendTransaction.bind(w); let n = await p.getTransactionCount(w.address); w.sendTransaction = async (tx) => { const r = await orig({ ...tx, nonce: n }); n++; return r; }; }
  const dep = async (name, ...args) => { const f = new ethers.ContractFactory(build[name].abi, build[name].bytecode, admin); const c = await f.deploy(...args); await c.waitForDeployment(); return c; };
  const keys = await dep("SkyKeys", signerAddr);
  const K = await keys.getAddress();
  const isl = await dep("SkyIsland2", signerAddr, K);
  const skills = await dep("SkySkills3", signerAddr, K);
  const aotv = await dep("Aotv2");
  const COBBLE = 1851776868;
  const word = await dep("SkyWord2", await aotv.getAddress(), COBBLE, signerAddr, K);
  const gear = await dep("SkyGear2");
  const hold = await dep("SkyHold2", await isl.getAddress(), await gear.getAddress(), signerAddr, K);
  const vrfStub = await dep("SkyKeys", signerAddr); // stand-in VRF address (not called below wave 3)
  const realm = await dep("SkyRealm2", await vrfStub.getAddress(), true, signerAddr, K);
  const bin = await dep("SkyBin2", await realm.getAddress(), signerAddr, K);
  // ---- migration (admin only, before close) ----
  await (await aotv.migrateMint(P.address, 1)).wait();
  await (await aotv.setHold(await word.getAddress())).wait();
  await (await gear.migrateMint(5, P.address, 89190163, 0, "wooden_axe", "Wooden Axe", "", "")).wait();
  await (await gear.setHold(await hold.getAddress())).wait();
  const aw = (s) => (1n << 32n) | (BigInt(aotv.target) << 64n) | (BigInt(s) << 224n);
  const cob = (n) => BigInt(COBBLE) | (BigInt(n) << 32n);
  await (await word.migrate(P.address, [0, 3], [cob(10), aw(1)], 0)).wait();
  await reverts(word.migrate(P.address, [8], [cob(1)], 0), "migrate an item into slot 8", "StarSlot");
  await (await hold.migrateBag(P.address, [2], [89190163], [1], [0], [5])).wait();
  await (await skills.migrate(P.address, [18, 24, 649, 0, 0, 0, 0, 0, 26], [0, 1], [55, 7])).wait();
  await (await isl.migrateIsland(1, P.address, [])).wait();
  await (await realm.migrateStats(P.address, 12345, 7)).wait();
  ok((await skills.xpOf(P.address))[2] === 649n, "skills migrated");
  ok((await word.wordsOf(P.address))[3] === aw(1), "word bag migrated (blade address swapped)");
  ok((await isl.ownerOf(1)) === P.address, "island deed migrated");
  ok((await realm.bestWave(P.address)) === 7n, "arena stats migrated");
  await reverts(skills.connect(P).migrate(P.address, [1,1,1,1,1,1,1,1,1], [], []), "non-admin migrate", "");

  const now = async () => (await p.getBlock("latest")).timestamp;
  const send = async (w, c, fn, args, opts = {}) => {
    const data = c.interface.encodeFunctionData(fn, args);
    const full = opts.raw ? data : attest(opts.key || signerKey, { chainId, player: opts.player || w.address, target: c.target, data, value: opts.value || 0n, nonce: opts.nonce ?? nonceCtr++, deadline: opts.deadline ?? (await now()) + 300 });
    const value = opts.value || 0n;
    const sim = p.call({ from: w.address, to: c.target, data: full, value }).then(() => "passed-sim", (e) => why(e) || "reverted");
    const simDone = await sim;
    const out = { full, simWhy: simDone };
    out.tx = w.sendTransaction({ to: c.target, data: full, value, gasLimit: 3_000_000 });
    out.tx.simWhy = simDone;
    out.tx.catch(() => {});
    return out;
  };
  const rawSim = async (w, to, data) => { const simWhy = await p.call({ from: w.address, to, data }).then(() => "passed-sim", (e) => why(e) || "reverted"); const tx = w.sendTransaction({ to, data, gasLimit: 3_000_000 }); tx.catch(() => {}); return { box: tx, simWhy }; };
  const land = async (w, c, fn, args, opts, label) => { const r = await (await (await send(w, c, fn, args, opts)).tx).wait(); ok(r.status === 1, label + " (gas " + r.gasUsed + ")"); return r; };

  // ================= MODE A (key not exported) =================
  console.log("--- mode A: P key not exported");
  ok(!(await keys.exported(P.address)), "P starts in mode A");
  let r = await land(P, skills, "applyXp", [[2], [10], [], []], { raw: true }, "mode A: applyXp without attestation lands");
  gas.applyXpA = r.gasUsed;
  ok((await skills.xpOf(P.address))[2] === 659n, "mode A: XP added");
  r = await land(P, skills, "applyXp", [[2], [10], [], []], {}, "mode A: applyXp with a (harmless) trailer still lands");
  gas.applyXpA_trailer = r.gasUsed;
  r = await land(P, word, "write", [[1], [cob(5)]], { raw: true }, "mode A: SkyWord.write without attestation lands");
  gas.writeA = r.gasUsed;
  r = await land(P, word, "write", [[1], [cob(6)]], { raw: true }, "mode A: SkyWord.write again (warm slot)");
  gas.writeA_warm = r.gasUsed;
  await reverts((await send(P, word, "write", [[8], [cob(1)]], { raw: true })).tx, "mode A: item into slot 8 (star)", "");
  await land(P, word, "write", [[8], [0]], { raw: true }, "mode A: clearing slot 8 allowed");
  await reverts((await send(P, skills, "applyXp", [[2], [1_000_001], [], []], { raw: true })).tx, "mode A: applyXp > 1M per entry", "");
  await reverts((await send(P, skills, "applyXp", [Array(21).fill(2), Array(21).fill(1_000_000), [], []], { raw: true })).tx, "mode A: 21M XP in one hour (cap 20M)", "");
  await land(P, skills, "applyXp", [Array(19).fill(3), Array(19).fill(1_000_000), [], []], { raw: true }, "mode A: 19M XP under the hourly cap");
  await reverts((await send(P, skills, "applyXp", [[3, 3], [1_000_000, 1_000_000], [], []], { raw: true })).tx, "mode A: next 2M in the same hour (over 20M)", "");
  await reverts((await send(P, skills, "applyXp", [[9], [1], [], []], { raw: true })).tx, "mode A: bad skill id", "");
  await land(P, realm, "checkpoint", [999, 1], { raw: true }, "mode A: stats checkpoint without attestation");
  await land(P, isl, "applyBatch", [5, 5, 5], { raw: true }, "mode A: island applyBatch without attestation");
  await reverts((await send(P, isl, "applyBatch", [65, 0, 0], { raw: true })).tx, "mode A: applyBatch over MAX_BATCH", "");
  const E9 = [[], [], [], [], [], [], [], [], []];
  await land(P, hold, "sync", [[0], [1], [64], [0], [0], [""], [""], [""], [""], 0, ...E9], { raw: true }, "mode A: SkyHold sync (no mint) without attestation");
  const MINT = ethers.MaxUint256;
  await reverts((await send(P, hold, "sync", [[1], [89190163], [1], [0], [MINT], ["wooden_axe"], ["Wooden Axe"], [""], [""], 0, ...E9], { raw: true })).tx, "mode A: SkyHold sync that MINTS gear without attestation", REJ);
  // valuable actions stay attested in mode A
  await reverts((await send(P, word, "craft", [0, 5], { raw: true })).tx, "mode A: blade craft (mint) without attestation", REJ);
  await reverts((await send(P, word, "withdrawNft", [3, MAIN.address], { raw: true })).tx, "mode A: withdrawNft without attestation", REJ);
  await reverts((await send(P, hold, "withdrawGear", [2, MAIN.address], { raw: true })).tx, "mode A: withdrawGear without attestation", REJ);
  await reverts((await send(P, hold, "transfer", [Q.address, 1, 1], { raw: true })).tx, "mode A: SkyHold transfer without attestation", REJ);
  await reverts((await send(P, realm, "enterArena", [], { raw: true, value: ethers.parseEther("0.005") })).tx, "mode A: enterArena without attestation", REJ);
  await reverts((await send(P, bin, "list", [7, 1, ethers.parseEther("0.01"), 600], { raw: true, value: ethers.parseEther("0.001") })).tx, "mode A: SkyBin list without attestation", REJ);
  await reverts((await send(Q, isl, "createIsland", [], { raw: true })).tx, "mode A: createIsland without attestation", REJ);

  // ================= SkyRealm2 exploit fixes =================
  console.log("--- SkyRealm2 fixes");
  const unit = ethers.parseEther("0.01");
  await land(Q, realm, "placeBuyOrder", [7, 2, unit], { value: unit * 2n }, "Q places a buy order");
  await reverts((await send(Q, realm, "fillBuyOrder", [1, 1])).tx, "buyer fills own buy order (even attested)", "bad fill");
  await reverts((await send(P, realm, "fillBuyOrder", [1, 1], { raw: true })).tx, "fillBuyOrder without attestation", REJ);
  await land(P, realm, "fillBuyOrder", [1, 1], {}, "attested fillBuyOrder by another player");
  await land(P, realm, "enterArena", [], { value: ethers.parseEther("0.005") }, "attested enterArena");
  await reverts((await send(P, realm, "finishRun", [1, 51, 9])).tx, "finishRun wave 51 (max 50)", "bad run");
  await reverts((await send(P, realm, "finishRun", [1, 50, 999])).tx, "finishRun wave 50 right after entry (too fast)", "too fast");
  await land(P, realm, "finishRun", [1, 0, 0], {}, "finishRun wave 0 (no roll)");
  await land(Q, realm, "createAuction", [7, 1, unit, 60], { value: ethers.parseEther("0.001") }, "auction created");
  await land(P, realm, "bid", [1], { value: unit }, "P bids");
  await land(MAIN, realm, "bid", [1], { value: unit * 2n }, "MAIN outbids");
  ok((await realm.pending(P.address)) === unit, "outbid refund credited (pull payment)");
  await (await realm.connect(P).withdrawPending()).wait();
  ok((await realm.pending(P.address)) === 0n, "withdrawPending pays it out (free, no attestation)");

  // ================= the flag =================
  console.log("--- SkyKeys flag");
  await reverts(keys.connect(Q).markExported(P.address), "random wallet marks someone else exported", "NotAllowed");
  await reverts(keys.connect(Q).setSigner(Q.address), "random wallet changes registry signer", "NotAllowed");
  ok(!keys.interface.fragments.some((f) => f.type === "function" && /unmark|clear|unset|revoke|remove/i.test(f.name)), "registry has no function that clears the flag");
  const opt = await (await keys.connect(P).optIn()).wait();
  gas.optIn = opt.gasUsed;
  ok(await keys.exported(P.address), "P.optIn() -> mode B (gas " + opt.gasUsed + ")");
  await (await keys.connect(P).optIn()).wait();
  ok(await keys.exported(P.address), "optIn again is a no-op, flag stays");
  await (await keys.connect(SIGNER).markExported(MAIN.address)).wait();
  ok(await keys.exported(MAIN.address), "server signer can mark a player");
  ok(await skills.exportedKey(P.address), "contracts see the flag");

  // ================= MODE B (key exported) =================
  console.log("--- mode B: P key exported, P now holds the key (attacker)");
  await reverts((await send(P, skills, "applyXp", [[2], [999999], [], []], { raw: true })).tx, "mode B: forged applyXp without attestation", REJ);
  await reverts((await send(P, word, "write", [[1], [cob(64)]], { raw: true })).tx, "mode B: forged SkyWord.write without attestation", REJ);
  await reverts((await send(P, realm, "checkpoint", [1, 1], { raw: true })).tx, "mode B: forged checkpoint", REJ);
  await reverts((await send(P, isl, "applyBatch", [64, 64, 64], { raw: true })).tx, "mode B: forged applyBatch", REJ);
  await reverts((await send(P, isl, "craft", [1], { raw: true })).tx, "mode B: forged island craft", REJ);
  await reverts((await send(P, hold, "sync", [[0], [1], [64], [0], [0], [""], [""], [""], [""], 0, ...E9], { raw: true })).tx, "mode B: forged SkyHold sync", REJ);
  await reverts((await send(P, word, "craft", [0, 6], { raw: true })).tx, "mode B: forged blade craft", REJ);
  await reverts((await send(P, skills, "applyXp", [[2], [10], [], []], { key: P.signingKey })).tx, "mode B: self-signed attestation (player key)", "BadSignature");
  await reverts((await send(P, skills, "applyXp", [[2], [10], [], []], { deadline: (await now()) - 5 })).tx, "mode B: expired deadline", "Expired");
  await reverts((await send(P, skills, "applyXp", [[2], [10], [], []], { deadline: (await now()) + 5000 })).tx, "mode B: deadline beyond 15 min", "Expired");
  const good = await send(P, skills, "applyXp", [[2], [10], [], []]);
  r = await (await good.tx).wait(); gas.applyXpB = r.gasUsed;
  ok(r.status === 1, "mode B: attested applyXp lands (gas " + r.gasUsed + ")");
  await reverts(await rawSim(P, skills.target, good.full), "mode B: replay of a used attestation", "Replayed");
  {
    const data = skills.interface.encodeFunctionData("applyXp", [[2], [10], [], []]);
    const full = attest(signerKey, { chainId, player: P.address, target: skills.target, data, value: 0n, nonce: nonceCtr++, deadline: (await now()) + 300 });
    const tamper = full.replace(ethers.toBeHex(10, 32).slice(2), ethers.toBeHex(999999, 32).slice(2));
    await reverts(await rawSim(P, skills.target, tamper), "mode B: amount changed under a valid attestation", "BadSignature");
    const forQ = attest(signerKey, { chainId, player: Q.address, target: skills.target, data, value: 0n, nonce: nonceCtr++, deadline: (await now()) + 300 });
    await reverts(await rawSim(P, skills.target, forQ), "mode B: another player's attestation", "BadSignature");
    const wd = word.interface.encodeFunctionData("write", [[1], [cob(7)]]);
    const forSkills = attest(signerKey, { chainId, player: P.address, target: skills.target, data: wd, value: 0n, nonce: nonceCtr++, deadline: (await now()) + 300 });
    await reverts(await rawSim(P, word.target, forSkills), "mode B: attestation for another contract", "BadSignature");
  }
  r = await land(P, word, "write", [[1], [cob(7)]], {}, "mode B: attested SkyWord.write lands"); gas.writeB = r.gasUsed;
  r = await land(P, word, "write", [[1], [cob(8)]], {}, "mode B: attested SkyWord.write again"); gas.writeB_warm = r.gasUsed;
  await reverts((await send(P, word, "write", [[8], [cob(1)]])).tx, "mode B: item into slot 8 even attested", "StarSlot");
  await reverts((await send(P, skills, "applyXp", [[2], [1_000_001], [], []])).tx, "mode B: caps still apply when attested", "OverCap");
  await land(P, realm, "checkpoint", [5, 2], {}, "mode B: attested checkpoint");
  await land(P, word, "cast", [3], { raw: true }, "mode B: cast (mana only) stays free");
  await land(P, word, "withdrawNft", [3, MAIN.address], {}, "mode B: attested withdrawNft");
  ok((await aotv.ownerOf(1)) === MAIN.address && !(await aotv.locked(1)), "blade on main wallet, unlocked");
  await reverts(aotv.connect(P).transferFrom(P.address, MAIN.address, 99), "move a token you do not own", "");
  await (await aotv.connect(MAIN).transferFrom(MAIN.address, P.address, 1)).wait();
  await land(P, word, "depositNft", [4, 1], {}, "mode B: attested depositNft");
  await reverts(aotv.connect(P).transferFrom(P.address, MAIN.address, 1), "mode B: exported key moves a locked in-game blade directly", "in game");
  await reverts(gear.connect(P).transferFrom(P.address, MAIN.address, 5), "mode B: exported key moves locked gear directly", "");
  // Q still in mode A: unaffected
  await land(Q, skills, "applyXp", [[0], [5], [], []], { raw: true }, "Q (mode A) unaffected by P's export");
  // migration close
  for (const c of [skills, word, isl, hold, realm, bin]) await (await c.closeMigration()).wait();
  await reverts(skills.migrate(P.address, [1,1,1,1,1,1,1,1,1], [], []), "migrate after closeMigration", "MigrationOver");
  console.log("\nGAS (anvil EVM units, Somnia differs):", JSON.stringify(Object.fromEntries(Object.entries(gas).map(([k, v]) => [k, String(v)]))));
  console.log(`\n${passes} passed, ${fails} failed`);
  process.exit(fails ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
