// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Attested.sol";

/// Catharsis skills and collections, v3 (hardened). Same reads and writes as SkySkills2, with hard caps
/// per call and per hour in both modes; applyXp needs a server attestation once the player's key has been
/// exported (mode B, SkyKeys).
/// Profile resets stay free for the player (they only ever lower their own record).
contract SkySkills3 is Attested {
    uint256 public constant VERSION = 3;
    uint256 public constant SKILLS = 9; // 0 mining, 1 foraging, 2 farming, 3 combat, 4 fishing, 5 enchanting, 6 alchemy, 7 taming, 8 carpentry
    uint256 public constant MAX_ENTRIES = 64;
    uint32 public constant MAX_AMOUNT = 1_000_000;
    uint16 public constant MAX_COLLECTION_ID = 1023;
    /// Sum of all XP and collection amounts a player can add in one rolling hour window.
    uint256 public constant HOURLY_CAP = 20_000_000;

    mapping(address => uint32) public epochOf;
    mapping(address => address) public resetOperator;
    mapping(address => mapping(uint32 => uint64[9])) private xp;
    mapping(address => mapping(uint32 => mapping(uint256 => uint256))) private packed;
    /// bits 0-63 window start, 64-127 amount used in the window.
    mapping(address => uint256) public window;

    event Xp(address indexed player, uint8 indexed skill, uint32 amount, uint64 total);
    event Collected(address indexed player, uint16 indexed id, uint32 amount, uint64 total);
    event Batch(address indexed player, uint256 entries);
    event Reset(address indexed player, uint32 indexed epoch, address indexed by);
    event ResetOperator(address indexed player, address operator);
    event Migrated(address indexed player);

    error Mismatch();
    error TooMany();
    error BadSkill();
    error BadCollection();
    error OverCap();
    error NotAllowed();

    constructor(address signer_, address keys_) Attested(signer_, keys_) {}

    function applyXp(uint8[] calldata skill, uint32[] calldata amount, uint16[] calldata collectionId, uint32[] calldata collectionAmt)
        external
        guarded
    {
        uint256 ns = skill.length;
        uint256 nc = collectionId.length;
        if (ns != amount.length || nc != collectionAmt.length) revert Mismatch();
        if (ns + nc == 0 || ns + nc > MAX_ENTRIES) revert TooMany();
        uint32 e = epochOf[msg.sender];
        uint64[9] storage mine = xp[msg.sender][e];
        uint256 sum;
        for (uint256 i = 0; i < ns; i++) {
            uint8 s = skill[i];
            uint32 a = amount[i];
            if (s >= SKILLS) revert BadSkill();
            if (a > MAX_AMOUNT) revert OverCap();
            sum += a;
            uint64 total = mine[s] + a;
            mine[s] = total;
            emit Xp(msg.sender, s, a, total);
        }
        mapping(uint256 => uint256) storage words = packed[msg.sender][e];
        for (uint256 i = 0; i < nc; i++) {
            uint16 id = collectionId[i];
            uint32 a = collectionAmt[i];
            if (id > MAX_COLLECTION_ID) revert BadCollection();
            if (a > MAX_AMOUNT) revert OverCap();
            sum += a;
            uint256 w = uint256(id) >> 2;
            uint256 shift = (uint256(id) & 3) * 64;
            uint256 word = words[w];
            uint64 total = uint64(word >> shift) + a;
            words[w] = (word & ~(uint256(type(uint64).max) << shift)) | (uint256(total) << shift);
            emit Collected(msg.sender, id, a, total);
        }
        _spend(sum);
        emit Batch(msg.sender, ns + nc);
    }

    function _spend(uint256 sum) private {
        uint256 w = window[msg.sender];
        uint256 start = uint64(w);
        uint256 usedNow = uint64(w >> 64);
        if (block.timestamp >= start + 1 hours) {
            start = block.timestamp;
            usedNow = 0;
        }
        usedNow += sum;
        if (usedNow > HOURLY_CAP) revert OverCap();
        window[msg.sender] = start | (usedNow << 64);
    }

    function resetProfile() external returns (uint32) {
        return _reset(msg.sender);
    }

    function resetProfileFor(address player) external returns (uint32) {
        address op = resetOperator[player];
        if (op == address(0) || msg.sender != op) revert NotAllowed();
        return _reset(player);
    }

    function setResetOperator(address operator) external {
        resetOperator[msg.sender] = operator;
        emit ResetOperator(msg.sender, operator);
    }

    function _reset(address player) private returns (uint32 e) {
        e = epochOf[player] + 1;
        epochOf[player] = e;
        emit Reset(player, e, msg.sender);
    }

    /// One-time import of a player's current SkySkills2 totals (admin, before closeMigration).
    function migrate(address player, uint64[9] calldata totals, uint16[] calldata ids, uint64[] calldata amounts) external migration {
        if (ids.length != amounts.length) revert Mismatch();
        uint32 e = epochOf[player];
        uint64[9] storage mine = xp[player][e];
        for (uint256 i = 0; i < SKILLS; i++) {
            mine[i] = totals[i];
        }
        mapping(uint256 => uint256) storage words = packed[player][e];
        for (uint256 i = 0; i < ids.length; i++) {
            uint16 id = ids[i];
            if (id > MAX_COLLECTION_ID) revert BadCollection();
            uint256 w = uint256(id) >> 2;
            uint256 shift = (uint256(id) & 3) * 64;
            uint256 word = words[w];
            words[w] = (word & ~(uint256(type(uint64).max) << shift)) | (uint256(amounts[i]) << shift);
        }
        emit Migrated(player);
    }

    function xpOf(address player) external view returns (uint64[9] memory) {
        return xp[player][epochOf[player]];
    }

    function xpAt(address player, uint32 epoch) external view returns (uint64[9] memory) {
        return xp[player][epoch];
    }

    function collection(address player, uint16 id) public view returns (uint64) {
        return collectionAt(player, epochOf[player], id);
    }

    function collectionAt(address player, uint32 epoch, uint16 id) public view returns (uint64) {
        return uint64(packed[player][epoch][uint256(id) >> 2] >> ((uint256(id) & 3) * 64));
    }

    function collectionsOf(address player, uint16[] calldata ids) external view returns (uint64[] memory out) {
        uint32 e = epochOf[player];
        out = new uint64[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = collectionAt(player, e, ids[i]);
        }
    }
}
