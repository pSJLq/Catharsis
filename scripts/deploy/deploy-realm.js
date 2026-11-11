// Deploy contracts/SkyRealm.sol to Shannon. Usage: node deploy-realm.js deploy
// VRF coordinator is the public Somnia testnet address (not a secret).
const VRF = "0x0834459256bbb8d2efee23dc6c3f1722266182dd";
const { wallet, fee, compile, say, ethers } = require("./lib");

async function deploy() {
  const c = compile("SkyRealm", true);
  const w = wallet();
  say("bytecode " + c.evm.bytecode.object.length / 2);
  say("deployer " + w.address + " balance " + ethers.formatEther(await w.provider.getBalance(w.address)));
  const factory = new ethers.ContractFactory(c.abi, "0x" + c.evm.bytecode.object, w);
  const realm = await factory.deploy(VRF, true, { ...fee });
  await realm.waitForDeployment();
  const address = await realm.getAddress();
  say("SkyRealm " + address + " tx " + realm.deploymentTransaction().hash);
  say("Add this address to docs/contracts.md, the README, and your local config (realm-contract).");
}

deploy().catch((e) => {
  process.stderr.write(String(e && e.message ? e.message : e) + "\n");
  process.exit(1);
});
