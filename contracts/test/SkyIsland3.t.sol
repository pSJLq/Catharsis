// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "../SkyKeys.sol";
import "../SkyIsland3.sol";

interface Vm3 {
    function prank(address) external;
    function warp(uint256) external;
    function addr(uint256) external returns (address);
    function sign(uint256, bytes32) external returns (uint8, bytes32, bytes32);
}

/// SkyIsland3: co-op deeds, attestation and attacks.
contract SkyIsland3Test {
    Vm3 constant vm = Vm3(address(uint160(uint256(keccak256("hevm cheat code")))));
    bytes32 constant TYPEHASH = keccak256("Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)");

    SkyKeys keys;
    SkyIsland3 isl;
    uint256 constant SIGNER_PK = 0x5151;
    uint256 constant ROGUE_PK = 0xBAD;
    address signer;
    address o = address(0xA0);
    address a = address(0xA1);
    address b = address(0xA2);
    address c = address(0xA3);
    address d = address(0xA4);
    address e = address(0xA5);
    address x = address(0xEE);
    uint64 nonce = 1;

    function setUp() public {
        vm.warp(1_800_000_000);
        signer = vm.addr(SIGNER_PK);
        keys = new SkyKeys(signer);
        isl = new SkyIsland3(signer, address(keys));
        isl.closeMigration();
    }

    // ---------------------------------------------------------------- helpers

    function _attested(address player, bytes memory body, uint256 pk, uint64 n, uint32 deadline, address target) internal returns (bytes memory) {
        bytes32 digest = keccak256(abi.encodePacked(hex"1901", SkyIsland3(target).domainSeparator(),
                keccak256(abi.encode(TYPEHASH, player, target, keccak256(body), uint256(0), uint256(n), uint256(deadline)))));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(pk, digest);
        bytes32 vs = bytes32(uint256(s) | (uint256(v - 27) << 255));
        return abi.encodePacked(body, n, deadline, r, vs);
    }

    function _call(address player, bytes memory data) internal returns (bool ok, bytes memory ret) {
        vm.prank(player);
        (ok, ret) = address(isl).call(data);
    }

    function _do(address player, bytes memory body) internal returns (bytes memory) {
        bytes memory data = _attested(player, body, SIGNER_PK, nonce++, uint32(block.timestamp + 300), address(isl));
        (bool ok, bytes memory ret) = _call(player, data);
        require(ok, _why(ret));
        return ret;
    }

    function _fails(address player, bytes memory body, bytes4 want) internal {
        bytes memory data = _attested(player, body, SIGNER_PK, nonce++, uint32(block.timestamp + 300), address(isl));
        (bool ok, bytes memory ret) = _call(player, data);
        require(!ok, "expected a revert");
        require(_sel(ret) == want, string.concat("wrong revert: ", _why(ret)));
    }

    function _sel(bytes memory ret) internal pure returns (bytes4 s) {
        if (ret.length < 4) return bytes4(0);
        assembly { s := mload(add(ret, 32)) }
    }

    function _why(bytes memory ret) internal pure returns (string memory) {
        bytes4 s = _sel(ret);
        if (s == SkyIsland3.Full.selector) return "Full";
        if (s == SkyIsland3.NotInvited.selector) return "NotInvited";
        if (s == SkyIsland3.AlreadyMember.selector) return "AlreadyMember";
        if (s == SkyIsland3.NotOwner.selector) return "NotOwner";
        if (s == SkyIsland3.NotMember.selector) return "NotMember";
        if (s == Attested.BadSignature.selector) return "BadSignature";
        if (s == Attested.Replayed.selector) return "Replayed";
        return "other revert";
    }

    function _create(address p) internal returns (uint256) {
        _do(p, abi.encodeCall(SkyIsland3.createIsland, ()));
        return isl.memberIsland(p);
    }

    function _invite(address owner, address m) internal {
        _do(owner, abi.encodeCall(SkyIsland3.invite, (m)));
    }

    function _accept(address m, uint256 id) internal {
        _do(m, abi.encodeCall(SkyIsland3.accept, (id)));
    }

    function _members(uint256 id) internal view returns (uint256) {
        (, address[] memory m) = isl.coopOf(id);
        return m.length;
    }

    // ---------------------------------------------------------------- attestation

    function testCreateNeedsAttestation() public {
        (bool ok, bytes memory ret) = _call(o, abi.encodeCall(SkyIsland3.createIsland, ()));
        require(!ok && _sel(ret) == Attested.NoAttestation.selector, "no attestation");
        bytes memory rogue = _attested(o, abi.encodeCall(SkyIsland3.createIsland, ()), ROGUE_PK, 7, uint32(block.timestamp + 300), address(isl));
        (ok, ret) = _call(o, rogue);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "rogue signer");
        uint256 id = _create(o);
        require(id == 1 && isl.ownerOf(1) == o && isl.balanceOf(o) == 1 && isl.nextId() == 2, "deed 1");
        _fails(o, abi.encodeCall(SkyIsland3.createIsland, ()), SkyIsland3.HasIsland.selector);
    }

    function testReplayAndWrongPlayerAndExpiry() public {
        uint256 id = _create(o);
        bytes memory data = _attested(o, abi.encodeCall(SkyIsland3.invite, (a)), SIGNER_PK, 99, uint32(block.timestamp + 300), address(isl));
        (bool ok,) = _call(o, data);
        require(ok, "first use");
        (bool ok2, bytes memory ret) = _call(o, data);
        require(!ok2 && _sel(ret) == Attested.Replayed.selector, "replay");
        // an attestation made for a, sent by x
        bytes memory forA = _attested(a, abi.encodeCall(SkyIsland3.accept, (id)), SIGNER_PK, 5, uint32(block.timestamp + 300), address(isl));
        (ok, ret) = _call(x, forA);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "other player's attestation");
        // changed argument under a valid trailer
        bytes memory body = abi.encodeCall(SkyIsland3.accept, (id));
        bytes memory good = _attested(a, body, SIGNER_PK, 6, uint32(block.timestamp + 300), address(isl));
        bytes memory bad = abi.encodePacked(abi.encodeCall(SkyIsland3.accept, (id + 1)), _slice(good, body.length));
        (ok, ret) = _call(a, bad);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "changed argument");
        // expired
        bytes memory late = _attested(a, body, SIGNER_PK, 8, uint32(block.timestamp + 300), address(isl));
        vm.warp(block.timestamp + 301);
        (ok, ret) = _call(a, late);
        require(!ok && _sel(ret) == Attested.Expired.selector, "expired");
        // attestation for another contract (same code, other address)
        SkyIsland3 other = new SkyIsland3(signer, address(keys));
        bytes memory foreign = _attested(o, abi.encodeCall(SkyIsland3.createIsland, ()), SIGNER_PK, 9, uint32(block.timestamp + 300), address(other));
        (ok, ret) = _call(o, foreign);
        require(!ok, "foreign attestation");
    }

    function _slice(bytes memory b_, uint256 from) internal pure returns (bytes memory out) {
        out = new bytes(b_.length - from);
        for (uint256 i = 0; i < out.length; i++) out[i] = b_[from + i];
    }

    // ---------------------------------------------------------------- co-op flow

    function testInviteAcceptLeave() public {
        uint256 id = _create(o);
        _fails(a, abi.encodeCall(SkyIsland3.accept, (id)), SkyIsland3.NotInvited.selector);
        _fails(a, abi.encodeCall(SkyIsland3.invite, (b)), SkyIsland3.NotOwner.selector);
        _fails(o, abi.encodeCall(SkyIsland3.invite, (o)), SkyIsland3.BadAddress.selector);
        _invite(o, a);
        (address by, uint64 until) = isl.inviteOf(id, a);
        require(by == o && until == block.timestamp + 1 hours, "invite stored");
        _accept(a, id);
        require(isl.memberIsland(a) == id && isl.isMember(id, a) && _members(id) == 1, "a joined");
        (by, until) = isl.inviteOf(id, a);
        require(until == 0, "invite used up");
        _fails(o, abi.encodeCall(SkyIsland3.invite, (a)), SkyIsland3.AlreadyMember.selector);
        _fails(a, abi.encodeCall(SkyIsland3.createIsland, ()), SkyIsland3.HasIsland.selector);
        _fails(o, abi.encodeCall(SkyIsland3.leave, ()), SkyIsland3.OwnerCannotLeave.selector);
        _do(a, abi.encodeCall(SkyIsland3.leave, ()));
        require(isl.memberIsland(a) == 0 && _members(id) == 0, "a left");
        _fails(a, abi.encodeCall(SkyIsland3.leave, ()), SkyIsland3.NotMember.selector);
        // after leaving: own deed again
        uint256 own = _create(a);
        require(own == 2 && isl.ownerOf(own) == a, "solo deed after leave");
    }

    function testInviteExpires() public {
        uint256 id = _create(o);
        _invite(o, a);
        vm.warp(block.timestamp + 1 hours + 1);
        _fails(a, abi.encodeCall(SkyIsland3.accept, (id)), SkyIsland3.NotInvited.selector);
        _invite(o, a); // re-invite refreshes
        _accept(a, id);
        require(isl.memberIsland(a) == id, "joined after re-invite");
    }

    function testRevokeInvite() public {
        uint256 id = _create(o);
        _invite(o, a);
        _do(o, abi.encodeCall(SkyIsland3.revokeInvite, (a)));
        _fails(a, abi.encodeCall(SkyIsland3.accept, (id)), SkyIsland3.NotInvited.selector);
        _fails(o, abi.encodeCall(SkyIsland3.revokeInvite, (a)), SkyIsland3.NotInvited.selector);
    }

    function testJoinerReleasesSoloDeed() public {
        uint256 id = _create(o);
        uint256 solo = _create(a);
        require(solo == 2 && isl.balanceOf(a) == 1, "solo");
        _invite(o, a);
        _accept(a, id);
        require(isl.memberIsland(a) == id && isl.ownerOf(solo) == address(0) && isl.balanceOf(a) == 0, "solo deed burned");
        (address ow, address[] memory m) = isl.coopOf(solo);
        require(ow == address(0) && m.length == 0, "burned deed empty");
        require(isl.nextId() == 3, "ids never reused");
    }

    function testOwnerWithMembersCannotJoin() public {
        uint256 id1 = _create(o);
        uint256 id2 = _create(b);
        _invite(b, c);
        _accept(c, id2);
        _invite(o, b);
        _fails(b, abi.encodeCall(SkyIsland3.accept, (id1)), SkyIsland3.AlreadyMember.selector);
        // a member of another deed must leave first
        _invite(o, c);
        _fails(c, abi.encodeCall(SkyIsland3.accept, (id1)), SkyIsland3.AlreadyMember.selector);
        _do(c, abi.encodeCall(SkyIsland3.leave, ()));
        _invite(o, c);
        _accept(c, id1);
        require(isl.memberIsland(c) == id1 && isl.memberIsland(b) == id2, "c moved");
    }

    function testMaxFive() public {
        uint256 id = _create(o);
        address[4] memory m = [a, b, c, d];
        for (uint256 i = 0; i < 4; i++) {
            _invite(o, m[i]);
            _accept(m[i], id);
        }
        require(_members(id) == 4, "owner + 4");
        _fails(o, abi.encodeCall(SkyIsland3.invite, (e)), SkyIsland3.Full.selector);
    }

    function testPendingInvitesCannotOverfill() public {
        uint256 id = _create(o);
        address[3] memory m = [a, b, c];
        for (uint256 i = 0; i < 3; i++) {
            _invite(o, m[i]);
            _accept(m[i], id);
        }
        _invite(o, d);
        _invite(o, e); // both fit at invite time (4 of 5)
        _accept(d, id);
        _fails(e, abi.encodeCall(SkyIsland3.accept, (id)), SkyIsland3.Full.selector);
        require(_members(id) == 4 && isl.memberIsland(e) == 0, "still 5 players");
    }

    function testKickRules() public {
        uint256 id = _create(o);
        _invite(o, a);
        _accept(a, id);
        _invite(o, b);
        _accept(b, id);
        uint256 other = _create(x);
        // outsider and a member of another deed cannot kick
        _fails(x, abi.encodeCall(SkyIsland3.kick, (a)), SkyIsland3.NotMember.selector);
        _fails(c, abi.encodeCall(SkyIsland3.kick, (a)), SkyIsland3.NotMember.selector);
        // nobody kicks the owner; nobody kicks themselves
        _fails(a, abi.encodeCall(SkyIsland3.kick, (o)), SkyIsland3.CannotKickOwner.selector);
        _fails(a, abi.encodeCall(SkyIsland3.kick, (a)), SkyIsland3.BadAddress.selector);
        // no attestation (in-game vote not passed) -> no kick
        (bool ok, bytes memory ret) = _call(a, abi.encodeCall(SkyIsland3.kick, (b)));
        require(!ok && _sel(ret) == Attested.NoAttestation.selector, "kick needs the server");
        // a member kicks another member after the vote
        _do(a, abi.encodeCall(SkyIsland3.kick, (b)));
        require(isl.memberIsland(b) == 0 && _members(id) == 1, "b kicked");
        _fails(a, abi.encodeCall(SkyIsland3.kick, (b)), SkyIsland3.NotMember.selector);
        _fails(b, abi.encodeCall(SkyIsland3.kick, (a)), SkyIsland3.NotMember.selector);
        // re-invite after kick
        _invite(o, b);
        _accept(b, id);
        require(isl.memberIsland(b) == id && _members(id) == 2, "b back");
        // owner kicks
        _do(o, abi.encodeCall(SkyIsland3.kick, (a)));
        (address ow, address[] memory m) = isl.coopOf(id);
        require(ow == o && m.length == 1 && m[0] == b, "only b left");
        require(isl.ownerOf(other) == x, "other deed untouched");
    }

    function testOldInviteDiesWithTransfer() public {
        uint256 id = _create(o);
        _invite(o, a);
        _do(o, abi.encodeCall(SkyIsland3.transferFrom, (o, x, id)));
        require(isl.ownerOf(id) == x && isl.memberIsland(x) == id && isl.memberIsland(o) == 0, "moved");
        _fails(a, abi.encodeCall(SkyIsland3.accept, (id)), SkyIsland3.NotInvited.selector);
        // deeds with members cannot move
        _invite(x, b);
        _accept(b, id);
        _fails(x, abi.encodeCall(SkyIsland3.transferFrom, (x, c, id)), SkyIsland3.HasMembers.selector);
        _fails(b, abi.encodeCall(SkyIsland3.transferFrom, (x, c, id)), SkyIsland3.NotOwner.selector);
    }

    // ---------------------------------------------------------------- migration

    function testMigration() public {
        SkyIsland3 m = new SkyIsland3(signer, address(keys));
        address[] memory coop = new address[](1);
        coop[0] = a;
        vm.prank(x);
        (bool ok,) = address(m).call(abi.encodeCall(SkyIsland3.migrateIsland, (2, o, coop)));
        require(!ok, "only admin migrates");
        m.migrateIsland(2, o, coop);
        m.migrateIsland(3, b, new address[](0));
        m.migrateNextId(4);
        (address ow, address[] memory mem) = m.coopOf(2);
        require(ow == o && mem.length == 1 && mem[0] == a && m.memberIsland(a) == 2 && m.ownerOf(3) == b && m.nextId() == 4, "imported");
        (ok,) = address(m).call(abi.encodeCall(SkyIsland3.migrateIsland, (3, c, new address[](0))));
        require(!ok, "deed twice");
        (ok,) = address(m).call(abi.encodeCall(SkyIsland3.migrateNextId, (3)));
        require(!ok, "nextId backwards");
        m.closeMigration();
        (ok,) = address(m).call(abi.encodeCall(SkyIsland3.migrateIsland, (9, c, new address[](0))));
        require(!ok, "closed");
    }
}
