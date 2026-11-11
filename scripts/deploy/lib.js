// Shared helpers for the Catharsis deploy scripts. No keys in this file.
const fs = require("fs");
const path = require("path");
const solc = require("solc");
const { ethers } = require("ethers");

function loadEnv() {
  const envPath = path.resolve(__dirname, "../../.env");
  if (!fs.existsSync(envPath)) return;
  for (const line of fs.readFileSync(envPath, "utf8").split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (!m || m[1] in process.env) continue;
    let v = m[2];
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    process.env[m[1]] = v;
  }
}

function root() {
  return path.resolve(__dirname, "../..");
}

function provider() {
  const url = process.env.RPC_URL || "https://api.infra.testnet.somnia.network";
  const chainId = Number(process.env.CHAIN_ID || 50312);
  return new ethers.JsonRpcProvider(url, chainId);
}

function wallet() {
  loadEnv();
  const raw = process.env.PRIVATE_KEY;
  if (!raw) throw new Error("Set PRIVATE_KEY in the environment or in a local .env (see .env.example)");
  return new ethers.Wallet(raw, provider());
}

const fee = {
  maxFeePerGas: ethers.parseUnits("20", "gwei"),
  maxPriorityFeePerGas: ethers.parseUnits("1", "gwei"),
};

function compile(name, viaIR) {
  const file = name + ".sol";
  const source = fs.readFileSync(path.join(root(), "contracts", file), "utf8");
  const out = JSON.parse(solc.compile(JSON.stringify({
    language: "Solidity",
    sources: { [file]: { content: source } },
    settings: {
      viaIR: !!viaIR,
      optimizer: { enabled: true, runs: 200 },
      evmVersion: "cancun",
      outputSelection: { "*": { "*": ["abi", "evm.bytecode.object"] } },
    },
  })));
  const errors = (out.errors || []).filter((e) => e.severity === "error");
  if (errors.length) throw new Error(errors.map((e) => e.formattedMessage).join("\n"));
  const c = out.contracts[file][name];
  fs.writeFileSync(path.join(root(), "contracts", name + ".abi.json"), JSON.stringify(c.abi, null, 2));
  return c;
}

function say(line) {
  process.stdout.write(line + "\n");
}

module.exports = { wallet, fee, compile, root, say, ethers };
