// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "../SkyKeys.sol";
import "../SkyIsland4.sol";

interface Vm3 {
    function deal(address, uint256) external;
    function expectRevert(bytes4) external;
    function prank(address) external;
    function warp(uint256) external;
    function addr(uint256) external returns (address);
    function sign(uint256, bytes32) external returns (uint8, bytes32, bytes32);
}

contract RealmStub {
    uint256 public got;
    receive() external payable { got += msg.value; }
}

contract Receiver {
    function onERC721Received(address, address, uint256, bytes calldata) external pure returns (bytes4) {
        return Receiver.onERC721Received.selector;
    }
}

contract NotAReceiver {}

contract Rejecter {
    receive() external payable { revert("no"); }
}

/// SkyIsland4: ERC-721 deeds, co-op, the island market, attestation and attacks.
contract SkyIsland4Test {
    Vm3 constant vm = Vm3(address(uint160(uint256(keccak256("hevm cheat code")))));
    bytes32 constant TYPEHASH = keccak256("Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)");

    SkyKeys keys;
    SkyIsland4 isl;
    RealmStub realm;
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
        realm = new RealmStub();
        isl = new SkyIsland4(signer, address(keys), payable(address(realm)), "http://127.0.0.1:8791/api/island/");
        isl.closeMigration();
    }

    // ---------------------------------------------------------------- helpers

    function _attested(address player, bytes memory body, uint256 pk, uint64 n, uint32 deadline, address target) internal returns (bytes memory) {
        return _attestedV(player, body, pk, n, deadline, target, 0);
    }

    function _attestedV(address player, bytes memory body, uint256 pk, uint64 n, uint32 deadline, address target, uint256 value) internal returns (bytes memory) {
        bytes32 digest = keccak256(abi.encodePacked(hex"1901", SkyIsland4(target).domainSeparator(),
                keccak256(abi.encode(TYPEHASH, player, target, keccak256(body), value, uint256(n), uint256(deadline)))));
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
        if (s == SkyIsland4.Full.selector) return "Full";
        if (s == SkyIsland4.NotInvited.selector) return "NotInvited";
        if (s == SkyIsland4.AlreadyMember.selector) return "AlreadyMember";
        if (s == SkyIsland4.NotOwner.selector) return "NotOwner";
        if (s == SkyIsland4.NotMember.selector) return "NotMember";
        if (s == Attested.BadSignature.selector) return "BadSignature";
        if (s == Attested.Replayed.selector) return "Replayed";
        return "other revert";
    }

    function _create(address p) internal returns (uint256) {
        _do(p, abi.encodeCall(SkyIsland4.createIsland, ()));
        return isl.memberIsland(p);
    }

    function _invite(address owner, address m) internal {
        _do(owner, abi.encodeCall(SkyIsland4.invite, (m)));
    }

    function _accept(address m, uint256 id) internal {
        _do(m, abi.encodeCall(SkyIsland4.accept, (id)));
    }

    function _members(uint256 id) internal view returns (uint256) {
        (, address[] memory m) = isl.coopOf(id);
        return m.length;
    }

    // ---------------------------------------------------------------- attestation

    function testCreateNeedsAttestation() public {
        (bool ok, bytes memory ret) = _call(o, abi.encodeCall(SkyIsland4.createIsland, ()));
        require(!ok && _sel(ret) == Attested.NoAttestation.selector, "no attestation");
        bytes memory rogue = _attested(o, abi.encodeCall(SkyIsland4.createIsland, ()), ROGUE_PK, 7, uint32(block.timestamp + 300), address(isl));
        (ok, ret) = _call(o, rogue);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "rogue signer");
        uint256 id = _create(o);
        require(id == 1 && isl.ownerOf(1) == o && isl.balanceOf(o) == 1 && isl.nextId() == 2, "deed 1");
        _fails(o, abi.encodeCall(SkyIsland4.createIsland, ()), SkyIsland4.HasIsland.selector);
    }

    function testReplayAndWrongPlayerAndExpiry() public {
        uint256 id = _create(o);
        bytes memory data = _attested(o, abi.encodeCall(SkyIsland4.invite, (a)), SIGNER_PK, 99, uint32(block.timestamp + 300), address(isl));
        (bool ok,) = _call(o, data);
        require(ok, "first use");
        (bool ok2, bytes memory ret) = _call(o, data);
        require(!ok2 && _sel(ret) == Attested.Replayed.selector, "replay");
        // an attestation made for a, sent by x
        bytes memory forA = _attested(a, abi.encodeCall(SkyIsland4.accept, (id)), SIGNER_PK, 5, uint32(block.timestamp + 300), address(isl));
        (ok, ret) = _call(x, forA);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "other player's attestation");
        // changed argument under a valid trailer
        bytes memory body = abi.encodeCall(SkyIsland4.accept, (id));
        bytes memory good = _attested(a, body, SIGNER_PK, 6, uint32(block.timestamp + 300), address(isl));
        bytes memory bad = abi.encodePacked(abi.encodeCall(SkyIsland4.accept, (id + 1)), _slice(good, body.length));
        (ok, ret) = _call(a, bad);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "changed argument");
        // expired
        bytes memory late = _attested(a, body, SIGNER_PK, 8, uint32(block.timestamp + 300), address(isl));
        vm.warp(block.timestamp + 301);
        (ok, ret) = _call(a, late);
        require(!ok && _sel(ret) == Attested.Expired.selector, "expired");
        // attestation for another contract (same code, other address)
        SkyIsland4 other = new SkyIsland4(signer, address(keys), payable(address(realm)), "");
        bytes memory foreign = _attested(o, abi.encodeCall(SkyIsland4.createIsland, ()), SIGNER_PK, 9, uint32(block.timestamp + 300), address(other));
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
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.NotInvited.selector);
        _fails(a, abi.encodeCall(SkyIsland4.invite, (b)), SkyIsland4.NotOwner.selector);
        _fails(o, abi.encodeCall(SkyIsland4.invite, (o)), SkyIsland4.BadAddress.selector);
        _invite(o, a);
        (address by, uint64 until) = isl.inviteOf(id, a);
        require(by == o && until == block.timestamp + 1 hours, "invite stored");
        _accept(a, id);
        require(isl.memberIsland(a) == id && isl.isMember(id, a) && _members(id) == 1, "a joined");
        (by, until) = isl.inviteOf(id, a);
        require(until == 0, "invite used up");
        _fails(o, abi.encodeCall(SkyIsland4.invite, (a)), SkyIsland4.AlreadyMember.selector);
        _fails(a, abi.encodeCall(SkyIsland4.createIsland, ()), SkyIsland4.HasIsland.selector);
        _fails(o, abi.encodeCall(SkyIsland4.leave, ()), SkyIsland4.OwnerCannotLeave.selector);
        _do(a, abi.encodeCall(SkyIsland4.leave, ()));
        require(isl.memberIsland(a) == 0 && _members(id) == 0, "a left");
        _fails(a, abi.encodeCall(SkyIsland4.leave, ()), SkyIsland4.NotMember.selector);
        // after leaving: own deed again
        uint256 own = _create(a);
        require(own == 2 && isl.ownerOf(own) == a, "solo deed after leave");
    }

    function testInviteExpires() public {
        uint256 id = _create(o);
        _invite(o, a);
        vm.warp(block.timestamp + 1 hours + 1);
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.NotInvited.selector);
        _invite(o, a); // re-invite refreshes
        _accept(a, id);
        require(isl.memberIsland(a) == id, "joined after re-invite");
    }

    function testRevokeInvite() public {
        uint256 id = _create(o);
        _invite(o, a);
        _do(o, abi.encodeCall(SkyIsland4.revokeInvite, (a)));
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.NotInvited.selector);
        _fails(o, abi.encodeCall(SkyIsland4.revokeInvite, (a)), SkyIsland4.NotInvited.selector);
    }

    function testJoinerReleasesSoloDeed() public {
        uint256 id = _create(o);
        uint256 solo = _create(a);
        require(solo == 2 && isl.balanceOf(a) == 1, "solo");
        _invite(o, a);
        _accept(a, id);
        require(isl.memberIsland(a) == id && isl.holderOf(solo) == address(0) && isl.balanceOf(a) == 0, "solo deed burned");
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
        _fails(b, abi.encodeCall(SkyIsland4.accept, (id1)), SkyIsland4.AlreadyMember.selector);
        // a member of another deed must leave first
        _invite(o, c);
        _fails(c, abi.encodeCall(SkyIsland4.accept, (id1)), SkyIsland4.AlreadyMember.selector);
        _do(c, abi.encodeCall(SkyIsland4.leave, ()));
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
        _fails(o, abi.encodeCall(SkyIsland4.invite, (e)), SkyIsland4.Full.selector);
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
        _fails(e, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.Full.selector);
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
        _fails(x, abi.encodeCall(SkyIsland4.kick, (a)), SkyIsland4.NotMember.selector);
        _fails(c, abi.encodeCall(SkyIsland4.kick, (a)), SkyIsland4.NotMember.selector);
        // nobody kicks the owner; nobody kicks themselves
        _fails(a, abi.encodeCall(SkyIsland4.kick, (o)), SkyIsland4.CannotKickOwner.selector);
        _fails(a, abi.encodeCall(SkyIsland4.kick, (a)), SkyIsland4.BadAddress.selector);
        // no attestation (in-game vote not passed) -> no kick
        (bool ok, bytes memory ret) = _call(a, abi.encodeCall(SkyIsland4.kick, (b)));
        require(!ok && _sel(ret) == Attested.NoAttestation.selector, "kick needs the server");
        // a member kicks another member after the vote
        _do(a, abi.encodeCall(SkyIsland4.kick, (b)));
        require(isl.memberIsland(b) == 0 && _members(id) == 1, "b kicked");
        _fails(a, abi.encodeCall(SkyIsland4.kick, (b)), SkyIsland4.NotMember.selector);
        _fails(b, abi.encodeCall(SkyIsland4.kick, (a)), SkyIsland4.NotMember.selector);
        // re-invite after kick
        _invite(o, b);
        _accept(b, id);
        require(isl.memberIsland(b) == id && _members(id) == 2, "b back");
        // owner kicks
        _do(o, abi.encodeCall(SkyIsland4.kick, (a)));
        (address ow, address[] memory m) = isl.coopOf(id);
        require(ow == o && m.length == 1 && m[0] == b, "only b left");
        require(isl.ownerOf(other) == x, "other deed untouched");
    }

    function testOldInviteDiesWithTransfer() public {
        uint256 id = _create(o);
        _invite(o, a);
        vm.prank(o);
        isl.transferFrom(o, x, id);
        require(isl.ownerOf(id) == x && isl.memberIsland(x) == id && isl.memberIsland(o) == 0, "moved");
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.NotInvited.selector);
        // deeds with members cannot move
        _invite(x, b);
        _accept(b, id);
        (bool ok, bytes memory ret) = _call(x, abi.encodeCall(SkyIsland4.transferFrom, (x, c, id)));
        require(!ok && _sel(ret) == SkyIsland4.HasMembers.selector, "members block the move");
        (ok, ret) = _call(b, abi.encodeCall(SkyIsland4.transferFrom, (x, c, id)));
        require(!ok && _sel(ret) == SkyIsland4.NotApproved.selector, "a member cannot move the deed");
    }

    // ---------------------------------------------------------------- migration

    function testMigration() public {
        SkyIsland4 m = new SkyIsland4(signer, address(keys), payable(address(realm)), "");
        address[] memory coop = new address[](1);
        coop[0] = a;
        vm.prank(x);
        (bool ok,) = address(m).call(abi.encodeCall(SkyIsland4.migrateIsland, (2, o, coop)));
        require(!ok, "only admin migrates");
        m.migrateIsland(2, o, coop);
        m.migrateIsland(3, b, new address[](0));
        m.migrateNextId(4);
        (address ow, address[] memory mem) = m.coopOf(2);
        require(ow == o && mem.length == 1 && mem[0] == a && m.memberIsland(a) == 2 && m.ownerOf(3) == b && m.nextId() == 4, "imported");
        (ok,) = address(m).call(abi.encodeCall(SkyIsland4.migrateIsland, (3, c, new address[](0))));
        require(!ok, "deed twice");
        (ok,) = address(m).call(abi.encodeCall(SkyIsland4.migrateNextId, (3)));
        require(!ok, "nextId backwards");
        m.closeMigration();
        (ok,) = address(m).call(abi.encodeCall(SkyIsland4.migrateIsland, (9, c, new address[](0))));
        require(!ok, "closed");
    }

    // ---------------------------------------------------------------- ERC-721 and the island market

    function _doV(address player, bytes memory body, uint256 value) internal {
        bytes memory data = _attestedV(player, body, SIGNER_PK, nonce++, uint32(block.timestamp + 300), address(isl), value);
        vm.deal(player, player.balance + value);
        vm.prank(player);
        (bool ok, bytes memory ret) = address(isl).call{value: value}(data);
        require(ok, _why(ret));
    }

    function _failsV(address player, bytes memory body, uint256 value, bytes4 want) internal {
        bytes memory data = _attestedV(player, body, SIGNER_PK, nonce++, uint32(block.timestamp + 300), address(isl), value);
        vm.deal(player, player.balance + value);
        vm.prank(player);
        (bool ok, bytes memory ret) = address(isl).call{value: value}(data);
        require(!ok, "expected a revert");
        require(_sel(ret) == want, "wrong revert");
    }

    function _list(address p, uint256 id, uint8 kind, uint128 price, uint32 secs) internal {
        _doV(p, abi.encodeCall(SkyIsland4.listIsland, (id, kind, price, secs)), isl.listingFee(price));
    }

    function testMetadata() public {
        require(keccak256(bytes(isl.name())) == keccak256("Catharsis Island") && keccak256(bytes(isl.symbol())) == keccak256("ISLAND"), "name/symbol");
        require(isl.supportsInterface(0x80ac58cd) && isl.supportsInterface(0x5b5e139f) && isl.supportsInterface(0x01ffc9a7) && !isl.supportsInterface(0xffffffff), "erc165");
        (bool ok, bytes memory ret) = address(isl).call(abi.encodeCall(SkyIsland4.ownerOf, (1)));
        require(!ok && _sel(ret) == SkyIsland4.NoDeed.selector, "ownerOf unknown reverts");
        (ok, ret) = address(isl).call(abi.encodeCall(SkyIsland4.tokenURI, (1)));
        require(!ok, "tokenURI unknown reverts");
        uint256 id = _create(o);
        for (uint256 i = 0; i < 11; i++) _create(address(uint160(0x1000 + i)));
        require(keccak256(bytes(isl.tokenURI(id))) == keccak256("http://127.0.0.1:8791/api/island/1"), "uri 1");
        require(keccak256(bytes(isl.tokenURI(12))) == keccak256("http://127.0.0.1:8791/api/island/12"), "uri 12");
        vm.prank(x);
        (ok,) = address(isl).call(abi.encodeCall(SkyIsland4.setBaseURI, ("https://evil/")));
        require(!ok, "only admin sets the base URI");
        isl.setBaseURI("https://catharsis.example/api/island/");
        require(keccak256(bytes(isl.tokenURI(id))) == keccak256("https://catharsis.example/api/island/1"), "new base");
    }

    function testTransferToNewPlayerMakesItActive() public {
        uint256 id = _create(o);
        vm.prank(o);
        isl.transferFrom(o, x, id);
        require(isl.ownerOf(id) == x && isl.balanceOf(o) == 0 && isl.balanceOf(x) == 1, "moved");
        require(isl.memberIsland(x) == id && isl.memberIsland(o) == 0, "x plays on it, o has none");
        // the old owner can create a fresh solo deed
        uint256 fresh = _create(o);
        require(fresh == 2 && isl.memberIsland(o) == 2, "fresh deed");
        // a stranger cannot move it
        (bool ok, bytes memory ret) = _call(o, abi.encodeCall(SkyIsland4.transferFrom, (x, o, id)));
        require(!ok && _sel(ret) == SkyIsland4.NotApproved.selector, "stranger");
        (ok, ret) = _call(x, abi.encodeCall(SkyIsland4.transferFrom, (o, a, id)));
        require(!ok && _sel(ret) == SkyIsland4.NotOwner.selector, "wrong from");
        (ok, ret) = _call(x, abi.encodeCall(SkyIsland4.transferFrom, (x, address(0), id)));
        require(!ok && _sel(ret) == SkyIsland4.BadAddress.selector, "to zero");
        (ok, ret) = _call(x, abi.encodeCall(SkyIsland4.transferFrom, (x, address(isl), id)));
        require(!ok && _sel(ret) == SkyIsland4.BadAddress.selector, "to the contract");
    }

    function testApprovals() public {
        uint256 id = _create(o);
        (bool ok, bytes memory ret) = _call(x, abi.encodeCall(SkyIsland4.approve, (x, id)));
        require(!ok && _sel(ret) == SkyIsland4.NotApproved.selector, "only the owner approves");
        vm.prank(o);
        isl.approve(a, id);
        require(isl.getApproved(id) == a, "approved");
        vm.prank(a);
        isl.transferFrom(o, b, id);
        require(isl.ownerOf(id) == b && isl.getApproved(id) == address(0), "approval cleared on transfer");
        (ok, ret) = _call(a, abi.encodeCall(SkyIsland4.transferFrom, (b, a, id)));
        require(!ok && _sel(ret) == SkyIsland4.NotApproved.selector, "old approval gone");
        vm.prank(b);
        isl.setApprovalForAll(c, true);
        require(isl.isApprovedForAll(b, c), "operator");
        vm.prank(c);
        isl.approve(d, id); // an operator may approve
        vm.prank(d);
        isl.transferFrom(b, e, id);
        require(isl.ownerOf(id) == e && isl.memberIsland(e) == id && isl.memberIsland(b) == 0, "operator path");
        vm.prank(b);
        isl.setApprovalForAll(c, false);
        require(!isl.isApprovedForAll(b, c), "operator off");
    }

    function testSafeTransfer() public {
        uint256 id = _create(o);
        NotAReceiver bad = new NotAReceiver();
        (bool ok, bytes memory ret) = _call(o, abi.encodeWithSignature("safeTransferFrom(address,address,uint256)", o, address(bad), id));
        require(!ok && _sel(ret) == SkyIsland4.NotReceiver.selector, "non-receiver contract");
        Receiver good = new Receiver();
        vm.prank(o);
        isl.safeTransferFrom(o, address(good), id);
        require(isl.ownerOf(id) == address(good), "receiver contract");
        uint256 id2 = _create(a);
        vm.prank(a);
        isl.safeTransferFrom(a, b, id2, "hi");
        require(isl.ownerOf(id2) == b, "to a wallet");
    }

    function testHeldDeedAndUseIsland() public {
        uint256 mine = _create(a);
        uint256 gift = _create(o);
        vm.prank(o);
        isl.transferFrom(o, a, gift);
        require(isl.balanceOf(a) == 2 && isl.memberIsland(a) == mine, "a gift does not replace the island a plays on");
        _fails(x, abi.encodeCall(SkyIsland4.useIsland, (gift)), SkyIsland4.NotOwner.selector);
        _fails(a, abi.encodeCall(SkyIsland4.useIsland, (mine)), SkyIsland4.AlreadyMember.selector);
        (bool ok, bytes memory ret) = _call(a, abi.encodeCall(SkyIsland4.useIsland, (gift)));
        require(!ok && _sel(ret) == Attested.NoAttestation.selector, "useIsland is attested");
        _do(a, abi.encodeCall(SkyIsland4.useIsland, (gift)));
        require(isl.memberIsland(a) == gift && isl.ownerOf(mine) == a, "switched; old deed held");
        // a held deed can be sent on; the player keeps the active one
        vm.prank(a);
        isl.transferFrom(a, b, mine);
        require(isl.memberIsland(a) == gift && isl.memberIsland(b) == mine, "held deed moved");
        // in a co-op: a received deed is held and cannot be used until the co-op is left
        uint256 coop = _create(c);
        _invite(c, d);
        _accept(d, coop);
        uint256 extra = _create(e);
        vm.prank(e);
        isl.transferFrom(e, d, extra);
        require(isl.memberIsland(d) == coop && isl.ownerOf(extra) == d, "held by a co-op member");
        _fails(d, abi.encodeCall(SkyIsland4.useIsland, (extra)), SkyIsland4.InCoop.selector);
        // the co-op owner with members cannot switch either
        uint256 extra2 = _create(x);
        vm.prank(x);
        isl.transferFrom(x, c, extra2);
        _fails(c, abi.encodeCall(SkyIsland4.useIsland, (extra2)), SkyIsland4.InCoop.selector);
        _do(d, abi.encodeCall(SkyIsland4.leave, ()));
        _do(d, abi.encodeCall(SkyIsland4.useIsland, (extra)));
        require(isl.memberIsland(d) == extra, "after leaving");
    }

    function testInviteOnHeldDeedIsDead() public {
        uint256 id = _create(o);
        _invite(o, a);
        uint256 other = _create(x);
        vm.prank(x);
        isl.transferFrom(x, o, other);
        _do(o, abi.encodeCall(SkyIsland4.useIsland, (other)));
        // o still owns id, but plays on `other`: the old invite to id must not let a join a deed nobody plays on
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.NotInvited.selector);
        // invites go to the active deed
        _invite(o, a);
        _accept(a, other);
        require(isl.memberIsland(a) == other, "joined the active deed");
    }

    function testListNeedsOwnerFeeAttestation() public {
        uint256 id = _create(o);
        uint128 price = 1 ether;
        uint256 fee = isl.listingFee(price);
        require(fee == 0.01 ether && isl.listingFee(0.05 ether) == 0.001 ether, "fee: 1%, min 0.001");
        _failsV(x, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 3600)), fee, SkyIsland4.NotOwner.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 3600)), fee - 1, SkyIsland4.WrongValue.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 3, price, 3600)), fee, SkyIsland4.BadSale.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, 0, 3600)), 0.001 ether, SkyIsland4.BadSale.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 59)), fee, SkyIsland4.BadSale.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 14 days + 1)), fee, SkyIsland4.BadSale.selector);
        vm.deal(o, 1 ether);
        vm.prank(o);
        (bool ok, bytes memory ret) = address(isl).call{value: fee}(abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 3600)));
        require(!ok && (_sel(ret) == Attested.Expired.selector || _sel(ret) == Attested.BadSignature.selector), "listing is attested");
        // a value other than the attested one breaks the signature
        bytes memory data = _attestedV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 3600)), SIGNER_PK, nonce++, uint32(block.timestamp + 300), address(isl), fee);
        vm.deal(o, 1 ether);
        vm.prank(o);
        (ok, ret) = address(isl).call{value: fee + 1}(data);
        require(!ok && _sel(ret) == Attested.BadSignature.selector, "value is signed");
        uint256 burnBefore = address(0xdEaD).balance;
        _list(o, id, 1, price, 3600);
        require(address(0xdEaD).balance - burnBefore == fee / 2 && realm.got() == fee / 2 && isl.burned() == fee / 2 && isl.pooled() == fee / 2, "fee split");
        require(address(isl).balance == 0, "nothing kept");
        _failsV(o, abi.encodeCall(SkyIsland4.listIsland, (id, 1, price, 3600)), fee, SkyIsland4.Listed.selector);
        // members block a listing
        uint256 c2 = _create(c);
        _invite(c, d);
        _accept(d, c2);
        _failsV(c, abi.encodeCall(SkyIsland4.listIsland, (c2, 1, price, 3600)), fee, SkyIsland4.HasMembers.selector);
    }

    function testListingLocksTheDeed() public {
        uint256 id = _create(o);
        _invite(o, a); // invited before the listing
        _list(o, id, 1, 1 ether, 3600);
        (bool ok, bytes memory ret) = _call(o, abi.encodeCall(SkyIsland4.transferFrom, (o, x, id)));
        require(!ok && _sel(ret) == SkyIsland4.Listed.selector, "no transfer while listed");
        _fails(o, abi.encodeCall(SkyIsland4.invite, (b)), SkyIsland4.Listed.selector);
        _fails(a, abi.encodeCall(SkyIsland4.accept, (id)), SkyIsland4.Listed.selector);
        // the seller cannot give the listed solo deed up by joining a co-op
        uint256 other = _create(x);
        _invite(x, o);
        _fails(o, abi.encodeCall(SkyIsland4.accept, (other)), SkyIsland4.Listed.selector);
        _fails(a, abi.encodeCall(SkyIsland4.cancelIslandSale, (id)), SkyIsland4.NotOwner.selector);
        _do(o, abi.encodeCall(SkyIsland4.cancelIslandSale, (id)));
        (address seller,,,,,) = isl.sales(id);
        require(seller == address(0), "unlisted");
        vm.prank(o);
        isl.transferFrom(o, b, id);
        require(isl.ownerOf(id) == b, "moves after cancel");
    }

    function testBuyItNow() public {
        uint256 id = _create(o);
        uint256 buyerOld = _create(a);
        uint128 price = 2 ether;
        _list(o, id, 1, price, 3600);
        _failsV(o, abi.encodeCall(SkyIsland4.buyIsland, (id)), price, SkyIsland4.OwnSale.selector);
        _failsV(a, abi.encodeCall(SkyIsland4.buyIsland, (id)), price - 1, SkyIsland4.WrongValue.selector);
        _failsV(a, abi.encodeCall(SkyIsland4.bidIsland, (id)), price, SkyIsland4.SaleClosed.selector);
        uint256 sellerBefore = o.balance;
        uint256 realmBefore = realm.got();
        _doV(a, abi.encodeCall(SkyIsland4.buyIsland, (id)), price);
        uint256 tax = uint256(price) * 200 / 10000;
        require(o.balance - sellerBefore == price - tax, "seller paid, 2% tax");
        require(realm.got() - realmBefore == tax / 2, "half the tax to the pool");
        require(isl.ownerOf(id) == a && isl.memberIsland(a) == id && isl.ownerOf(buyerOld) == a, "bought deed is active, the old one held");
        require(isl.memberIsland(o) == 0 && isl.balanceOf(o) == 0, "seller has no island");
        _failsV(b, abi.encodeCall(SkyIsland4.buyIsland, (id)), price, SkyIsland4.SaleClosed.selector);
        require(address(isl).balance == 0, "no STT stuck");
        // a co-op member who buys holds the deed
        uint256 sold2 = _create(x);
        uint256 coop = _create(c);
        _invite(c, d);
        _accept(d, coop);
        _list(x, sold2, 1, 1 ether, 3600);
        _doV(d, abi.encodeCall(SkyIsland4.buyIsland, (sold2)), 1 ether);
        require(isl.ownerOf(sold2) == d && isl.memberIsland(d) == coop, "co-op member keeps playing on the co-op");
    }

    function testBinExpires() public {
        uint256 id = _create(o);
        _list(o, id, 1, 1 ether, 60);
        _fails(x, abi.encodeCall(SkyIsland4.cancelIslandSale, (id)), SkyIsland4.NotOwner.selector);
        (bool ok, bytes memory ret) = address(isl).call(abi.encodeCall(SkyIsland4.settleIsland, (id)));
        require(!ok && _sel(ret) == SkyIsland4.SaleRunning.selector, "still running");
        vm.warp(block.timestamp + 61);
        _failsV(a, abi.encodeCall(SkyIsland4.buyIsland, (id)), 1 ether, SkyIsland4.SaleClosed.selector);
        isl.settleIsland(id);
        require(isl.ownerOf(id) == o && isl.memberIsland(o) == id, "unlocked, still the seller's");
        (ok, ret) = address(isl).call(abi.encodeCall(SkyIsland4.settleIsland, (id)));
        require(!ok && _sel(ret) == SkyIsland4.SaleClosed.selector, "settled once");
    }

    function testAuction() public {
        uint256 id = _create(o);
        _create(b);
        _list(o, id, 2, 1 ether, 600);
        _failsV(a, abi.encodeCall(SkyIsland4.buyIsland, (id)), 1 ether, SkyIsland4.SaleClosed.selector);
        _failsV(o, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1 ether, SkyIsland4.OwnSale.selector);
        _failsV(a, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1 ether - 1, SkyIsland4.LowBid.selector);
        _doV(a, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1 ether);
        require(isl.minBid(id) == 1.05 ether + 1, "+5%");
        _fails(o, abi.encodeCall(SkyIsland4.cancelIslandSale, (id)), SkyIsland4.HasBids.selector);
        _failsV(b, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1.05 ether, SkyIsland4.LowBid.selector);
        _doV(b, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1.2 ether);
        require(isl.pending(a) == 1 ether && isl.pendingTotal() == 1 ether, "outbid credited");
        uint256 aBefore = a.balance;
        vm.prank(a);
        isl.withdrawPending();
        require(a.balance - aBefore == 1 ether && isl.pending(a) == 0, "refund pulled");
        vm.prank(a);
        (bool ok, bytes memory ret) = address(isl).call(abi.encodeCall(SkyIsland4.withdrawPending, ()));
        require(!ok && _sel(ret) == SkyIsland4.Nothing.selector, "nothing twice");
        // anti-snipe
        vm.warp(block.timestamp + 590);
        _doV(a, abi.encodeCall(SkyIsland4.bidIsland, (id)), 1.3 ether);
        (,uint64 end,,,,) = isl.sales(id);
        require(end == block.timestamp + 30, "extended to 30 s");
        vm.warp(block.timestamp + 29);
        (ok, ret) = address(isl).call(abi.encodeCall(SkyIsland4.settleIsland, (id)));
        require(!ok && _sel(ret) == SkyIsland4.SaleRunning.selector, "not yet");
        vm.warp(block.timestamp + 1);
        uint256 sellerBefore = o.balance;
        vm.prank(x); // anyone settles
        isl.settleIsland(id);
        uint256 tax = 1.3 ether * 200 / 10000;
        require(o.balance - sellerBefore == 1.3 ether - tax, "seller paid");
        require(isl.ownerOf(id) == a && isl.memberIsland(a) == id && isl.memberIsland(o) == 0, "winner got the deed");
        require(address(isl).balance == isl.pendingTotal() && isl.pending(b) == 1.2 ether, "only b's refund left");
    }

    function testAuctionNoBids() public {
        uint256 id = _create(o);
        _list(o, id, 2, 1 ether, 60);
        vm.warp(block.timestamp + 60);
        isl.settleIsland(id);
        require(isl.ownerOf(id) == o, "back to the seller");
        // cancel without bids is fine
        _list(o, id, 2, 1 ether, 600);
        _do(o, abi.encodeCall(SkyIsland4.cancelIslandSale, (id)));
    }

    function testSellerThatRejectsStt() public {
        // a contract seller that rejects STT still sells; the STT is credited
        Rejecter r = new Rejecter();
        uint256 id = _create(address(r));
        _list(address(r), id, 1, 1 ether, 600);
        _doV(a, abi.encodeCall(SkyIsland4.buyIsland, (id)), 1 ether);
        require(isl.ownerOf(id) == a && isl.pending(address(r)) == 0.98 ether, "credited");
    }

    function testHeldDeedCanBeSold() public {
        uint256 first = _create(o);
        uint256 second = _create(x);
        vm.prank(x);
        isl.transferFrom(x, o, second);
        require(isl.memberIsland(o) == first, "held");
        _list(o, second, 1, 1 ether, 600);
        _doV(a, abi.encodeCall(SkyIsland4.buyIsland, (second)), 1 ether);
        require(isl.memberIsland(o) == first && isl.ownerOf(second) == a && isl.memberIsland(a) == second, "seller keeps the island they play on");
    }

    function testMigratedDeedIsTradable() public {
        SkyIsland4 m = new SkyIsland4(signer, address(keys), payable(address(realm)), "u/");
        m.migrateIsland(1, o, new address[](0));
        m.migrateNextId(4);
        m.closeMigration();
        require(keccak256(bytes(m.tokenURI(1))) == keccak256("u/1") && m.balanceOf(o) == 1 && m.memberIsland(o) == 1, "imported");
        vm.prank(o);
        m.transferFrom(o, a, 1);
        require(m.ownerOf(1) == a && m.memberIsland(a) == 1, "imported deed moves");
    }
}
