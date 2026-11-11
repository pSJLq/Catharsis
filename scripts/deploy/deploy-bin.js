// Deploy contracts/SkyBin.sol to Shannon. Usage: node deploy-bin.js <SkyRealm address>
const { wallet, fee, compile, say, ethers } = require("./lib");

async function deploy(realm) {
  if (!ethers.isAddress(realm)) throw new Error("usage: node deploy-bin.js <SkyRealm address>");
  const c = compile("SkyBin", true);
  const w = wallet();
  say("deployer " + w.address + " balance " + ethers.formatEther(await w.provider.getBalance(w.address)));
  const factory = new ethers.ContractFactory(c.abi, "0x" + c.evm.bytecode.object, w);
  const bin = await factory.deploy(realm, { ...fee });
  await bin.waitForDeployment();
  const address = await bin.getAddress();
  say("SkyBin " + address + " tx " + bin.deploymentTransaction().hash + " realm " + realm);
  say("Add this address to docs/contracts.md, the README, and your local config (bin-contract).");
}

deploy(process.argv[2]).catch((e) => {
  process.stderr.write(String(e && e.message ? e.message : e) + "\n");
  process.exit(1);
});
