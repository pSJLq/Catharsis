// SkyProfile (skills, shared co-op collections and SkyBlock Level per SkyIsland4 deed): estimate, deploy, verify, config.
// No import: every profile starts from zero. Never prints a private key; keys stay in server/plugins/SomniaSkyblock/secrets.yml.
//   node deploy-profile.js estimate   compile + estimateGas (no tx)
//   node deploy-profile.js deploy     deploy from the admin purse (aborts if the estimate passes BUDGET STT)
//   node deploy-profile.js verify     read back constructor wiring and a few pure views
//   node deploy-profile.js config     skills-contract -> SkyProfile, skills-version 4 (live + src config.yml), explorer/chain.json, contracts page
const fs = require("fs");
const solc = require("solc");
const { ethers } = require("ethers");
const root = process.env.ROOT || "C:/TEMP/somnia-skyblock";
const RPC = process.env.RPC || "https://api.infra.testnet.somnia.network";
const SECRETS = root + "/server/plugins/SomniaSkyblock/secrets.yml";
const CONFIG = root + "/server/plugins/SomniaSkyblock/config.yml";
const SRC_CONFIG = root + "/src/main/resources/config.yml";
const OUT = root + "/tmp/profile";
const STATE = OUT + "/deploy.json";
const BUDGET = ethers.parseEther(process.env.BUDGET || "1");
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
  for (const f of ["Attested.sol", "SkyProfile.sol"]) sources[f] = { content: fs.readFileSync(dir + "/" + f, "utf8") };
  const out = JSON.parse(solc.compile(JSON.stringify({ language: "Solidity", sources,
    settings: { viaIR: true, optimizer: { enabled: true, runs: 200 }, evmVersion: "cancun", outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } } } })));
  const errors = (out.errors || []).filter((e) => e.severity === "error");
  if (errors.length) throw new Error(errors.map((e) => e.formattedMessage).join("\n"));
  const c = out.contracts["SkyProfile.sol"].SkyProfile;
  fs.writeFileSync(dir + "/SkyProfile.abi.json", JSON.stringify(c.abi, null, 2));
  return { abi: c.abi, bytecode: "0x" + c.evm.bytecode.object };
}

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
  const d = await f.getDeployTransaction(signerAddress(), cfgAddr("keys-contract"), cfgAddr("profile-contract"));
  const gas = await provider.estimateGas({ from: a, data: d.data });
  const price = (await provider.getFeeData()).gasPrice;
  const total = gas * price;
  say("admin " + a + " balance " + ethers.formatEther(await provider.getBalance(a)) + " STT");
  say("signer " + signerAddress() + " keys " + cfgAddr("keys-contract") + " deeds (SkyIsland4) " + cfgAddr("profile-contract"));
  say("deploy gas " + gas + ", gas price " + ethers.formatUnits(price, "gwei") + " gwei, estimated " + ethers.formatEther(total) + " STT (budget " + ethers.formatEther(BUDGET) + ")");
  return { total, gas };
}

async function deploy() {
  const s = state();
  if (s.address) { say("already deployed " + s.address); return; }
  const { total } = await estimate();
  if (total > BUDGET) throw new Error("estimate " + ethers.formatEther(total) + " STT is over the budget; nothing sent");
  const c = compile();
  const w = admin();
  const a = await w.getAddress();
  s.balanceBefore = String(await provider.getBalance(a));
  save(s);
  const f = new ethers.ContractFactory(c.abi, c.bytecode, w);
  const contract = await f.deploy(signerAddress(), cfgAddr("keys-contract"), cfgAddr("profile-contract"), { ...fee });
  await record(s, "deploy SkyProfile", contract.deploymentTransaction());
  s.address = await contract.getAddress();
  s.signer = signerAddress();
  s.keys = cfgAddr("keys-contract");
  s.deeds = cfgAddr("profile-contract");
  s.oldSkills = cfgAddr("skills-contract");
  s.balanceAfter = String(await provider.getBalance(a));
  save(s);
  say("SkyProfile " + s.address + "; admin balance " + ethers.formatEther(BigInt(s.balanceAfter)) + " STT");
}

async function verify() {
  const s = state();
  const p = new ethers.Contract(s.address, JSON.parse(fs.readFileSync(root + "/contracts/SkyProfile.abi.json", "utf8")), provider);
  let bad = 0, n = 0;
  const check = (ok, what) => { n++; if (!ok) { bad++; say("  MISMATCH " + what); } else say("  ok " + what); };
  check((await p.signer()) === s.signer, "signer " + s.signer);
  check((await p.keys()) === s.keys, "keys " + s.keys);
  check((await p.deeds()) === s.deeds, "deeds " + s.deeds);
  check((await p.migrating()) === false, "no import (migrating false)");
  check(Number(await p.skillCap()) === 25 && Number(await p.collectionCount()) === 29, "skillCap 25, collectionCount 29");
  check(Number(await p.skillLevel(3022425n, 25)) === 25 && Number(await p.skillSbXp(25)) === 200 && Number(await p.collectionTier(250n)) === 3, "level tables");
  const island = await new ethers.Contract(s.deeds, ["function memberIsland(address) view returns (uint256)"], provider).memberIsland(new ethers.Wallet(secret("admin-key")).address);
  say("  admin memberIsland #" + island + " sbXp " + (island > 0n ? await p.sbXpOf(island, new ethers.Wallet(secret("admin-key")).address) : 0n));
  say("verify: " + n + " checks, " + bad + " mismatch(es)");
  if (bad) process.exit(2);
}

function patch(file, key, value) {
  if (!fs.existsSync(file)) return false;
  let text = fs.readFileSync(file, "utf8");
  const re = new RegExp("(\\n\\s*" + key + ":\\s*)['\"]?[^'\"\\n]*['\"]?");
  if (!re.test(text)) return false;
  text = text.replace(re, "$1" + value);
  fs.writeFileSync(file, text);
  return true;
}
function addAfter(file, anchorKey, key, value) {
  if (!fs.existsSync(file)) return;
  let text = fs.readFileSync(file, "utf8");
  if (new RegExp("\\n\\s*" + key + ":").test(text)) { patch(file, key, value); return; }
  text = text.replace(new RegExp("(\\n(\\s*)" + anchorKey + ":[^\\n]*)"), "$1\n$2" + key + ": " + value);
  fs.writeFileSync(file, text);
}

function config() {
  const s = state();
  const tx = (s.txs || []).find((t) => t.label === "deploy SkyProfile").hash;
  for (const f of [CONFIG, SRC_CONFIG]) {
    const had = patch(f, "skills-contract", "'" + s.address + "'");
    if (had) {
      addAfter(f, "skills-contract", "old-skills-contract", "'" + s.oldSkills + "'");
      if (!patch(f, "skills-version", "4")) addAfter(f, "skills-contract", "skills-version", "4");
      if (!patch(f, "skills-deploy-tx", "'" + tx + "'")) addAfter(f, "skills-version", "skills-deploy-tx", "'" + tx + "'");
    }
    say((had ? "patched " : "no skills-contract in ") + f);
  }
  const chainPath = root + "/explorer/chain.json";
  if (fs.existsSync(chainPath)) {
    const chain = JSON.parse(fs.readFileSync(chainPath, "utf8"));
    chain.legacy = chain.legacy || {};
    chain.legacy.skills3 = s.oldSkills;
    chain.skills = s.address;
    chain.skillsTx = tx;
    fs.writeFileSync(chainPath, JSON.stringify(chain, null, 2) + "\n");
    say("patched explorer/chain.json");
  }
  const page = root + "/site/public/contracts.html";
  if (fs.existsSync(page)) {
    let html = fs.readFileSync(page, "utf8");
    const link = (a) => '<a href="https://shannon-explorer.somnia.network/address/' + a + '"><code>' + a + "</code></a>";
    const oldRow = /<tr>\s*<td>SkySkills3<\/td>\s*<td>[^<]*<\/td>\s*<td>[^\n]*<\/td>\s*<\/tr>/;
    if (oldRow.test(html)) {
      html = html.replace(oldRow, "<tr>\n<td>SkyProfile</td>\n<td>Progress per island deed: shared co-op collections, each member's skill XP, and the SkyBlock Level worked out onchain. At most 1,000,000 per entry and 20,000,000 per hour.</td>\n<td>" + link(s.address) + "</td>\n</tr>");
      html = html.replace(/(<h2>Previous contracts<\/h2>[\s\S]*?<tbody>)/, "$1\n          <tr>\n            <td>SkySkills3</td>\n            <td>SkyProfile (progress per island deed). Not imported: progress started from zero.</td>\n            <td>" + link(s.oldSkills) + "</td>\n          </tr>");
      fs.writeFileSync(page, html);
      say("patched site/public/contracts.html");
    } else {
      say("contracts.html: SkySkills3 row not found, not patched");
    }
  }
}

const cmd = process.argv[2];
const run = { estimate, deploy, verify, config: async () => config() }[cmd];
if (!run) { say("usage: estimate|deploy|verify|config"); process.exit(1); }
run().catch((e) => { process.stderr.write(blame(e) + "\n"); process.exit(1); });
