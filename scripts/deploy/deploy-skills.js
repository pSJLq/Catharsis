// Deploy contracts/SkySkills.sol (v1) to Shannon. Usage: node deploy-skills.js deploy
const { wallet, fee, compile, say, ethers } = require("./lib");

async function deploy() {
  const c = compile("SkySkills", false);
  const w = wallet();
  say("bytecode " + c.evm.bytecode.object.length / 2);
  say("deployer " + w.address + " balance " + ethers.formatEther(await w.provider.getBalance(w.address)));
  const factory = new ethers.ContractFactory(c.abi, "0x" + c.evm.bytecode.object, w);
  const skills = await factory.deploy({ ...fee });
  await skills.waitForDeployment();
  const address = await skills.getAddress();
  const tx = skills.deploymentTransaction().hash;
  say("SkySkills " + address + " tx " + tx);
  say("Add this address to docs/contracts.md, the README, and your local config (skills-contract-v1).");
}

deploy().catch((e) => {
  process.stderr.write(String(e && e.message ? e.message : e) + "\n");
  process.exit(1);
});
