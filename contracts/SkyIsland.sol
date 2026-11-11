// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// One island deed per coop, plus each player's own skill progress.
/// The player signs and pays gas from their purse. The server holds that key,
/// so Minecraft never opens a wallet popup.
contract SkyIsland {
    uint256 public constant MAX_MEMBERS = 5;
    uint256 public constant MAX_BATCH = 64;
    uint256 public constant CRAFT_TIER = 100;

    struct Progress {
        uint64 miningXp;
        uint64 farmingXp;
        uint64 foragingXp;
        uint64 cobble;
        uint64 oak;
        uint64 wheat;
        uint64 enchantedCobble;
        uint64 enchantedOak;
    }

    struct Deed {
        address owner;
        address[4] coop;
        uint8 coopCount;
    }

    uint256 public nextId = 1;
    mapping(uint256 => Deed) private deeds;
    mapping(address => uint256) public memberIsland;
    mapping(address => Progress) public progress;
    mapping(uint256 => address) public ownerOf;
    mapping(address => uint256) public balanceOf;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);
    event Invited(uint256 indexed id, address indexed member);
    event Batch(address indexed player, uint32 cobble, uint32 oak, uint32 wheat);
    event Craft(address indexed player, uint8 kind);

    error HasIsland();
    error NotOwner();
    error Full();
    error AlreadyMember();
    error OverCap();
    error CollectionLocked();
    error BadKind();

    function createIsland() external returns (uint256 id) {
        if (memberIsland[msg.sender] != 0) revert HasIsland();
        id = nextId++;
        deeds[id].owner = msg.sender;
        ownerOf[id] = msg.sender;
        balanceOf[msg.sender] += 1;
        memberIsland[msg.sender] = id;
        emit Transfer(address(0), msg.sender, id);
    }

    /// Owner plus up to four coop members. Five seats, same as a small SkyBlock coop.
    function invite(address member) external {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || ownerOf[id] != msg.sender) revert NotOwner();
        if (member == address(0) || memberIsland[member] != 0) revert AlreadyMember();
        Deed storage deed = deeds[id];
        if (uint256(deed.coopCount) + 1 >= MAX_MEMBERS) revert Full();
        deed.coop[deed.coopCount] = member;
        deed.coopCount += 1;
        memberIsland[member] = id;
        emit Invited(id, member);
    }

    function transferFrom(address from, address to, uint256 id) external {
        if (to == address(0) || ownerOf[id] != from || msg.sender != from) revert NotOwner();
        ownerOf[id] = to;
        deeds[id].owner = to;
        balanceOf[from] -= 1;
        balanceOf[to] += 1;
        emit Transfer(from, to, id);
    }

    function applyBatch(uint32 cobble, uint32 oak, uint32 wheat) external {
        if (cobble > MAX_BATCH || oak > MAX_BATCH || wheat > MAX_BATCH) revert OverCap();
        Progress storage stats = progress[msg.sender];
        stats.cobble += cobble;
        stats.oak += oak;
        stats.wheat += wheat;
        stats.miningXp += cobble;
        stats.foragingXp += uint64(oak) * 6;
        stats.farmingXp += uint64(wheat) * 4;
        emit Batch(msg.sender, cobble, oak, wheat);
    }

    /// kind 1 = enchanted cobblestone, kind 2 = enchanted oak log.
    /// The plugin burns the 160 plain items before this call.
    function craft(uint8 kind) external {
        Progress storage stats = progress[msg.sender];
        if (kind == 1) {
            if (stats.cobble < CRAFT_TIER) revert CollectionLocked();
            stats.enchantedCobble += 1;
        } else if (kind == 2) {
            if (stats.oak < CRAFT_TIER) revert CollectionLocked();
            stats.enchantedOak += 1;
        } else {
            revert BadKind();
        }
        emit Craft(msg.sender, kind);
    }
}
