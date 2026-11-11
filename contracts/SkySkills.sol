// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// Catharsis skills and collections. The chain is the source of truth:
/// the server reads xpOf/collectionsOf on login and only caches them.
/// Each player's own game purse signs applyXp in 4 s batches, so nobody
/// can write another player's record. Testnet: the server holds the key
/// (sealed with the player's password), so caps below are the only
/// onchain anti-cheat.
contract SkySkills {
    uint256 public constant SKILLS = 9; // 0 mining, 1 foraging, 2 farming, 3 combat, 4 fishing, 5 enchanting, 6 alchemy, 7 taming, 8 carpentry
    uint256 public constant MAX_ENTRIES = 64;
    uint32 public constant MAX_AMOUNT = 1_000_000;
    uint16 public constant MAX_COLLECTION_ID = 1023;

    mapping(address => uint64[9]) private xp;
    /// Four uint64 collection counters per word: word id >> 2, lane id & 3.
    mapping(address => mapping(uint256 => uint256)) private packed;

    event Xp(address indexed player, uint8 indexed skill, uint32 amount, uint64 total);
    event Collected(address indexed player, uint16 indexed id, uint32 amount, uint64 total);
    event Batch(address indexed player, uint256 entries);

    error Mismatch();
    error TooMany();
    error BadSkill();
    error BadCollection();
    error OverCap();

    function applyXp(uint8[] calldata skill, uint32[] calldata amount, uint16[] calldata collectionId, uint32[] calldata collectionAmt) external {
        uint256 ns = skill.length;
        uint256 nc = collectionId.length;
        if (ns != amount.length || nc != collectionAmt.length) revert Mismatch();
        if (ns + nc == 0 || ns + nc > MAX_ENTRIES) revert TooMany();
        uint64[9] storage mine = xp[msg.sender];
        for (uint256 i = 0; i < ns; i++) {
            uint8 s = skill[i];
            uint32 a = amount[i];
            if (s >= SKILLS) revert BadSkill();
            if (a > MAX_AMOUNT) revert OverCap();
            uint64 total = mine[s] + a;
            mine[s] = total;
            emit Xp(msg.sender, s, a, total);
        }
        mapping(uint256 => uint256) storage words = packed[msg.sender];
        for (uint256 i = 0; i < nc; i++) {
            uint16 id = collectionId[i];
            uint32 a = collectionAmt[i];
            if (id > MAX_COLLECTION_ID) revert BadCollection();
            if (a > MAX_AMOUNT) revert OverCap();
            uint256 w = uint256(id) >> 2;
            uint256 shift = (uint256(id) & 3) * 64;
            uint256 word = words[w];
            uint64 total = uint64(word >> shift) + a;
            words[w] = (word & ~(uint256(type(uint64).max) << shift)) | (uint256(total) << shift);
            emit Collected(msg.sender, id, a, total);
        }
        emit Batch(msg.sender, ns + nc);
    }

    function xpOf(address player) external view returns (uint64[9] memory) {
        return xp[player];
    }

    function collection(address player, uint16 id) public view returns (uint64) {
        return uint64(packed[player][uint256(id) >> 2] >> ((uint256(id) & 3) * 64));
    }

    function collectionsOf(address player, uint16[] calldata ids) external view returns (uint64[] memory out) {
        out = new uint64[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = collection(player, ids[i]);
        }
    }
}
