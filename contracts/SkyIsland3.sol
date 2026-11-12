// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Attested.sol";

/// Catharsis island deeds with full onchain co-op (Hypixel SkyBlock rules: up to 5 players on one island).
///
/// One deed per island; the deed owner plus up to 4 co-op members. Every write is server-attested
/// (Attested.sol, EIP-712 "Catharsis" v1) in both key modes, and every write is sent and paid for by the
/// player it concerns:
///   createIsland()  a player's own solo deed
///   invite(member)  the deed owner invites (owner pays); the invite names the owner and expires
///   accept(id)      the invited player joins (joiner pays). A joiner who owns a solo deed (no members)
///                   releases it in the same call: the deed is burned and the player moves to the co-op.
///   leave()         a member leaves (the owner cannot leave while the deed exists)
///   kick(member)    a member or the owner removes another member (never the owner); the server attests
///                   only after the in-game vote passed
///   transferFrom    the owner moves a deed that has no members to a player without an island
/// A kicked or departed player can be invited again. Imported from SkyIsland2 once (admin, then closed).
contract SkyIsland3 is Attested {
    uint256 public constant VERSION = 3;
    uint256 public constant MAX_MEMBERS = 5; // owner + 4
    uint256 public constant INVITE_TTL = 1 hours;

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

    struct Invite {
        address by;
        uint64 until;
    }

    uint256 public nextId = 1;
    mapping(uint256 => Deed) private deeds;
    mapping(address => uint256) public memberIsland;
    /// Legacy island counters (SkyIsland v1/v2), kept readable; the game writes XP and collections to SkySkills.
    mapping(address => Progress) public progress;
    mapping(uint256 => address) public ownerOf;
    mapping(address => uint256) public balanceOf;
    mapping(uint256 => mapping(address => Invite)) private invites;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);
    event Invited(uint256 indexed id, address indexed member, address indexed by, uint64 until);
    event InviteRevoked(uint256 indexed id, address indexed member);
    event Joined(uint256 indexed id, address indexed member, uint256 released);
    event Left(uint256 indexed id, address indexed member);
    event Kicked(uint256 indexed id, address indexed member, address indexed by);

    error HasIsland();
    error NotOwner();
    error NotMember();
    error Full();
    error AlreadyMember();
    error NotInvited();
    error OwnerCannotLeave();
    error CannotKickOwner();
    error HasMembers();
    error BadAddress();

    constructor(address signer_, address keys_) Attested(signer_, keys_) {}

    // ------------------------------------------------------------------ deeds

    function createIsland() external attested returns (uint256 id) {
        if (memberIsland[msg.sender] != 0) revert HasIsland();
        id = nextId++;
        deeds[id].owner = msg.sender;
        ownerOf[id] = msg.sender;
        balanceOf[msg.sender] += 1;
        memberIsland[msg.sender] = id;
        emit Transfer(address(0), msg.sender, id);
    }

    /// The owner moves a deed without members to a player who has no island.
    function transferFrom(address from, address to, uint256 id) external attested {
        if (msg.sender != from || ownerOf[id] != from) revert NotOwner();
        if (to == address(0) || to == from) revert BadAddress();
        if (deeds[id].coopCount != 0) revert HasMembers();
        if (memberIsland[to] != 0) revert HasIsland();
        ownerOf[id] = to;
        deeds[id].owner = to;
        balanceOf[from] -= 1;
        balanceOf[to] += 1;
        memberIsland[from] = 0;
        memberIsland[to] = id;
        emit Transfer(from, to, id);
    }

    // ------------------------------------------------------------------ co-op

    function invite(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || ownerOf[id] != msg.sender) revert NotOwner();
        if (member == address(0) || member == msg.sender) revert BadAddress();
        if (memberIsland[member] == id) revert AlreadyMember();
        if (uint256(deeds[id].coopCount) + 1 >= MAX_MEMBERS) revert Full();
        uint64 until = uint64(block.timestamp + INVITE_TTL);
        invites[id][member] = Invite(msg.sender, until);
        emit Invited(id, member, msg.sender, until);
    }

    function revokeInvite(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || ownerOf[id] != msg.sender) revert NotOwner();
        if (invites[id][member].until == 0) revert NotInvited();
        delete invites[id][member];
        emit InviteRevoked(id, member);
    }

    function accept(uint256 id) external attested {
        Invite memory inv = invites[id][msg.sender];
        if (inv.until == 0 || inv.until < block.timestamp || inv.by == address(0) || ownerOf[id] != inv.by) revert NotInvited();
        delete invites[id][msg.sender];
        uint256 current = memberIsland[msg.sender];
        uint256 released;
        if (current != 0) {
            if (current == id) revert AlreadyMember();
            // Only a solo deed can be given up on join; a member must leave first, an owner with members cannot join.
            if (ownerOf[current] != msg.sender || deeds[current].coopCount != 0) revert AlreadyMember();
            _burn(current, msg.sender);
            released = current;
        }
        _join(id, msg.sender);
        emit Joined(id, msg.sender, released);
    }

    function leave() external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0) revert NotMember();
        if (ownerOf[id] == msg.sender) revert OwnerCannotLeave();
        _remove(id, msg.sender);
        emit Left(id, msg.sender);
    }

    function kick(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || memberIsland[member] != id) revert NotMember();
        if (member == ownerOf[id]) revert CannotKickOwner();
        if (member == msg.sender) revert BadAddress();
        _remove(id, member);
        emit Kicked(id, member, msg.sender);
    }

    // ------------------------------------------------------------------ views

    function coopOf(uint256 id) external view returns (address owner, address[] memory members) {
        Deed storage deed = deeds[id];
        owner = deed.owner;
        members = new address[](deed.coopCount);
        for (uint256 i = 0; i < deed.coopCount; i++) {
            members[i] = deed.coop[i];
        }
    }

    function inviteOf(uint256 id, address member) external view returns (address by, uint64 until) {
        Invite memory inv = invites[id][member];
        return (inv.by, inv.until);
    }

    function isMember(uint256 id, address player) external view returns (bool) {
        return id != 0 && memberIsland[player] == id;
    }

    // ------------------------------------------------------------------ one-time import from SkyIsland2 (admin, then closeMigration)

    function migrateIsland(uint256 id, address owner, address[] calldata coop) external migration {
        require(id != 0 && owner != address(0) && ownerOf[id] == address(0) && memberIsland[owner] == 0, "deed");
        deeds[id].owner = owner;
        ownerOf[id] = owner;
        balanceOf[owner] += 1;
        memberIsland[owner] = id;
        emit Transfer(address(0), owner, id);
        for (uint256 i = 0; i < coop.length; i++) {
            if (coop[i] == address(0) || memberIsland[coop[i]] != 0) revert AlreadyMember();
            _join(id, coop[i]);
        }
        if (id >= nextId) nextId = id + 1;
    }

    function migrateNextId(uint256 next) external migration {
        require(next >= nextId, "next");
        nextId = next;
    }

    function migrateProgress(address player, Progress calldata value) external migration {
        progress[player] = value;
    }

    // ------------------------------------------------------------------ internals

    function _join(uint256 id, address member) private {
        Deed storage deed = deeds[id];
        if (uint256(deed.coopCount) + 1 >= MAX_MEMBERS) revert Full();
        deed.coop[deed.coopCount] = member;
        deed.coopCount += 1;
        memberIsland[member] = id;
    }

    function _remove(uint256 id, address member) private {
        Deed storage deed = deeds[id];
        uint256 n = deed.coopCount;
        for (uint256 i = 0; i < n; i++) {
            if (deed.coop[i] == member) {
                deed.coop[i] = deed.coop[n - 1];
                deed.coop[n - 1] = address(0);
                deed.coopCount = uint8(n - 1);
                memberIsland[member] = 0;
                return;
            }
        }
        revert NotMember();
    }

    function _burn(uint256 id, address owner) private {
        ownerOf[id] = address(0);
        deeds[id].owner = address(0);
        balanceOf[owner] -= 1;
        memberIsland[owner] = 0;
        emit Transfer(owner, address(0), id);
    }
}
