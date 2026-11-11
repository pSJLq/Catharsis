// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface ISkyKeys {
    function exported(address player) external view returns (bool);
}

/// Server attestation for Catharsis writes (Somnia Shannon 50312).
///
/// The player's own game wallet still sends every transaction and pays the gas. The Paper server
/// signs an EIP-712 "Attest" for the exact call it wants to happen and appends 76 bytes to the
/// calldata:  nonce (uint64) | deadline (uint32) | r (bytes32) | vs (bytes32, EIP-2098 compact).
/// The contract hashes everything before those 76 bytes (selector + arguments), rebuilds the typed
/// data, and checks that ecrecover gives the configured signer. A wallet key on its own can pay for a
/// transaction but cannot decide what it writes.
///
/// Nonces are per player and one-shot (a bitmap, like Permit2), so writes that a player has in flight
/// at the same time never block each other and there is no global server nonce. A used nonce, a changed
/// argument, another player's attestation, another contract's attestation, or an expired deadline revert.
///
/// Two modes (SkyKeys): `attested` functions (NFT mint/withdraw/deposit, bazaar, auctions, arena, deeds)
/// always need an attestation. `guarded` functions (XP, bag layout, stats, island counters) need one only
/// for players whose key has been exported (mode B); in mode A the key never leaves the server, so the
/// write goes without one at normal gas. Hard caps in each contract apply in both modes.
abstract contract Attested {
    bytes32 private constant TYPEHASH =
        keccak256("Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)");
    bytes32 private constant DOMAIN_TYPEHASH =
        keccak256("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    uint256 private constant TRAILER = 76;
    bytes32 private constant LOW_S = 0x7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff;
    /// An attestation can be used for at most 15 minutes.
    uint256 public constant MAX_TTL = 900;

    bytes32 private immutable domain;
    uint256 private immutable chain;
    /// Key registry: players listed there (key exported) need an attestation for every write.
    ISkyKeys public immutable keys;

    address public admin;
    address public signer;
    /// Admin-only state import from the previous contract. Closed for good with closeMigration().
    bool public migrating = true;

    mapping(uint256 => uint256) private used;

    event SignerChanged(address indexed signer);
    event AdminChanged(address indexed admin);
    event MigrationClosed();

    error NotAdmin();
    error MigrationOver();
    error NoAttestation();
    error Expired();
    error BadSignature();
    error Replayed();

    constructor(address signer_, address keys_) {
        require(signer_ != address(0), "signer");
        require(keys_ != address(0), "keys");
        keys = ISkyKeys(keys_);
        admin = msg.sender;
        signer = signer_;
        chain = block.chainid;
        domain = _domain();
        emit SignerChanged(signer_);
        emit AdminChanged(msg.sender);
    }

    modifier onlyAdmin() {
        if (msg.sender != admin) revert NotAdmin();
        _;
    }

    modifier migration() {
        if (msg.sender != admin) revert NotAdmin();
        if (!migrating) revert MigrationOver();
        _;
    }

    modifier attested() {
        _attest();
        _;
    }

    /// Attestation only for players whose key has been exported (mode B).
    modifier guarded() {
        if (keys.exported(msg.sender)) _attest();
        _;
    }

    /// Attestation when forced (a valuable effect such as minting) or when the key has been exported.
    function _guard(bool force) internal {
        if (force || keys.exported(msg.sender)) _attest();
    }

    /// True when the caller's key has been exported (mode B).
    function exportedKey(address player) public view returns (bool) {
        return keys.exported(player);
    }

    function setSigner(address next) external onlyAdmin {
        require(next != address(0), "signer");
        signer = next;
        emit SignerChanged(next);
    }

    function setAdmin(address next) external onlyAdmin {
        require(next != address(0), "admin");
        admin = next;
        emit AdminChanged(next);
    }

    function closeMigration() external onlyAdmin {
        migrating = false;
        emit MigrationClosed();
    }

    function domainSeparator() public view returns (bytes32) {
        return block.chainid == chain ? domain : _domain();
    }

    function nonceUsed(address player, uint256 nonce) external view returns (bool) {
        return used[_key(player, nonce)] & (uint256(1) << (nonce & 255)) != 0;
    }

    function _domain() private view returns (bytes32) {
        return keccak256(abi.encode(DOMAIN_TYPEHASH, keccak256("Catharsis"), keccak256("1"), block.chainid, address(this)));
    }

    function _key(address player, uint256 nonce) private pure returns (uint256) {
        return (uint256(uint160(player)) << 96) | (nonce >> 8);
    }

    function _attest() internal {
        uint256 size = msg.data.length;
        if (size < 4 + TRAILER) revert NoAttestation();
        uint256 body = size - TRAILER;
        uint256 nonce;
        uint256 deadline;
        bytes32 r;
        bytes32 vs;
        bytes32 callHash;
        uint256 value;
        assembly ("memory-safe") {
            nonce := shr(192, calldataload(body))
            deadline := shr(224, calldataload(add(body, 8)))
            r := calldataload(add(body, 12))
            vs := calldataload(add(body, 44))
            let ptr := mload(0x40)
            calldatacopy(ptr, 0, body)
            callHash := keccak256(ptr, body)
            value := callvalue()
        }
        if (deadline < block.timestamp || deadline > block.timestamp + MAX_TTL) revert Expired();
        bytes32 digest = keccak256(
            abi.encodePacked(
                hex"1901",
                domainSeparator(),
                keccak256(abi.encode(TYPEHASH, msg.sender, address(this), callHash, value, nonce, deadline))
            )
        );
        address got = ecrecover(digest, uint8(uint256(vs) >> 255) + 27, r, vs & LOW_S);
        if (got == address(0) || got != signer) revert BadSignature();
        uint256 key = _key(msg.sender, nonce);
        uint256 bit = uint256(1) << (nonce & 255);
        uint256 word = used[key];
        if (word & bit != 0) revert Replayed();
        used[key] = word | bit;
    }
}
