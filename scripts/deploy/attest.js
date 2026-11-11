// EIP-712 server attestation, the same bytes the Paper plugin appends (Attested.sol):
// calldata | nonce uint64 | deadline uint32 | r | yParityAndS (EIP-2098).
const { ethers } = require("ethers");
const TYPES = { Attest: [
  { name: "player", type: "address" }, { name: "target", type: "address" }, { name: "call", type: "bytes32" },
  { name: "value", type: "uint256" }, { name: "nonce", type: "uint256" }, { name: "deadline", type: "uint256" }] };
function attest(signingKey, { chainId, player, target, data, value = 0n, nonce, deadline }) {
  const domain = { name: "Catharsis", version: "1", chainId, verifyingContract: target };
  const digest = ethers.TypedDataEncoder.hash(domain, TYPES, { player, target, call: ethers.keccak256(data), value, nonce, deadline });
  const sig = ethers.Signature.from(signingKey.sign(digest));
  return data + ethers.solidityPacked(["uint64", "uint32", "bytes32", "bytes32"], [nonce, deadline, sig.r, sig.yParityAndS]).slice(2);
}
module.exports = { attest, TYPES };
