// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "../SkyKeys.sol";
import "../SkyIsland4.sol";
import "../SkyProfile.sol";

interface VmP {
    function prank(address) external;
    function warp(uint256) external;
    function addr(uint256) external returns (address);
    function sign(uint256, bytes32) external returns (uint8, bytes32, bytes32);
    function deal(address, uint256) external;
}

contract RealmStubP {
    receive() external payable {}
}

contract SkyProfileTest {
    VmP constant vm = VmP(address(uint160(uint256(keccak256("hevm cheat code")))));
    bytes32 constant TYPEHASH = keccak256("Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)");
    uint256 constant SIGNER_PK = 0x5151;
    SkyKeys keys;
    SkyIsland4 isl;
    SkyProfile prof;
    address signer;
    address o = address(0xA0);
    address a = address(0xA1);
    address b = address(0xA2);
    address x = address(0xEE);
    uint64 nonce = 1;

    function setUp() public {
        vm.warp(1_800_000_000);
        signer = vm.addr(SIGNER_PK);
        keys = new SkyKeys(signer);
        isl = new SkyIsland4(signer, address(keys), payable(address(new RealmStubP())), "u/");
        isl.closeMigration();
        prof = new SkyProfile(signer, address(keys), address(isl));
    }

    function _att(address player, bytes memory body, address target) internal returns (bytes memory) {
        uint32 deadline = uint32(block.timestamp + 300);
        uint64 n = nonce++;
        bytes32 digest = keccak256(abi.encodePacked(hex"1901", Attested(target).domainSeparator(),
                keccak256(abi.encode(TYPEHASH, player, target, keccak256(body), uint256(0), uint256(n), uint256(deadline)))));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(SIGNER_PK, digest);
        bytes32 vs = bytes32(uint256(s) | (uint256(v - 27) << 255));
        return abi.encodePacked(body, n, deadline, r, vs);
    }

    function _isl(address p, bytes memory body) internal {
        bytes memory data = _att(p, body, address(isl));
        vm.prank(p);
        (bool ok,) = address(isl).call(data);
        require(ok, "island call failed");
    }

    function _create(address p) internal returns (uint256) {
        _isl(p, abi.encodeCall(SkyIsland4.createIsland, ()));
        return isl.memberIsland(p);
    }

    function _xp(address p, uint256 id, uint8 skill, uint32 amt, uint16 col, uint32 camt, uint32 task) internal returns (bool ok, bytes memory ret) {
        uint8[] memory s = new uint8[](amt == 0 ? 0 : 1);
        uint32[] memory sa = new uint32[](amt == 0 ? 0 : 1);
        if (amt != 0) { s[0] = skill; sa[0] = amt; }
        uint16[] memory c = new uint16[](camt == 0 ? 0 : 1);
        uint32[] memory ca = new uint32[](camt == 0 ? 0 : 1);
        if (camt != 0) { c[0] = col; ca[0] = camt; }
        vm.prank(p);
        (ok, ret) = address(prof).call(abi.encodeCall(SkyProfile.applyXp, (id, s, sa, c, ca, task)));
    }

    function _ok(address p, uint256 id, uint8 skill, uint32 amt, uint16 col, uint32 camt, uint32 task) internal {
        (bool ok,) = _xp(p, id, skill, amt, col, camt, task);
        require(ok, "applyXp failed");
    }

    function _sel(bytes memory ret) internal pure returns (bytes4 s) {
        if (ret.length < 4) return bytes4(0);
        assembly { s := mload(add(ret, 32)) }
    }

    function testTables() public view {
        require(prof.skillLevel(0, 25) == 0, "l0");
        require(prof.skillLevel(49, 25) == 0, "l49");
        require(prof.skillLevel(50, 25) == 1, "l1");
        require(prof.skillLevel(3022425, 25) == 25, "l25");
        require(prof.skillLevel(200000000, 25) == 25, "cap25");
        require(prof.skillLevel(200000000, 60) == 60, "cap60");
        require(prof.skillSbXp(10) == 50, "sb10");
        require(prof.skillSbXp(25) == 200, "sb25");
        require(prof.skillSbXp(50) == 700, "sb50");
        require(prof.skillSbXp(60) == 1000, "sb60");
        require(prof.collectionTier(49) == 0 && prof.collectionTier(50) == 1 && prof.collectionTier(2500) == 6, "tiers");
    }

    function testSoloAndCoopSharing() public {
        uint256 io = _create(o);
        // owner: 175 mining XP (level 2 = 10 SB XP), 100 wheat (tier 2 = 8), 5 task XP
        _ok(o, io, 0, 175, 0, 100, 5);
        require(prof.sbXpOf(io, o) == 23, "owner sbxp");
        require(prof.collection(io, 0) == 100, "col");
        // a joins: its solo deed is burned, it shares the island's collections
        uint256 ia = _create(a);
        _isl(o, abi.encodeCall(SkyIsland4.invite, (a)));
        _isl(a, abi.encodeCall(SkyIsland4.accept, (io)));
        require(isl.memberIsland(a) == io, "joined");
        require(ia != io, "two deeds");
        // a sees the shared collection tier but has no skills
        require(prof.sbXpOf(io, a) == 8, "member sees collection xp");
        // a collects 150 wheat: shared counter is 250 = tier 3 for both
        _ok(a, io, 1, 50, 0, 150, 0);
        require(prof.collection(io, 0) == 250, "shared 250");
        require(prof.sbXpOf(io, o) == 10 + 12 + 5, "owner gets the tier");
        require(prof.sbXpOf(io, a) == 5 + 12, "member foraging 1 + tier 3");
        uint64[9] memory sk = prof.skillsOf(io, a);
        require(sk[1] == 50 && sk[0] == 0, "member skills");
        // a writes to its old burned deed or to another island: refused
        (bool ok, bytes memory ret) = _xp(a, ia, 0, 1, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.NotMember.selector, "old deed refused");
        (ok, ret) = _xp(x, io, 0, 1, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.NotMember.selector, "stranger refused");
        // a leaves: its record stays on the old profile; a new island starts from zero
        _isl(a, abi.encodeCall(SkyIsland4.leave, ()));
        require(prof.skillsOf(io, a)[1] == 50, "record stays");
        (ok, ret) = _xp(a, io, 0, 1, 0, 0, 0);
        require(!ok, "left member cannot write");
        uint256 ia2 = _create(a);
        require(prof.sbXpOf(ia2, a) == 0, "fresh island");
        _ok(a, ia2, 0, 50, 3, 10, 0);
        require(prof.sbXpOf(ia2, a) == 5 && prof.collection(ia2, 3) == 10, "new profile");
        require(prof.collection(io, 3) == 0, "old profile untouched");
        require(prof.levelOfPlayer(o) == 0 && prof.levelOf(io, o) == 0, "level views");
    }

    function testResetProfileAndSelf() public {
        uint256 io = _create(o);
        _ok(o, io, 2, 1000, 5, 600, 30);
        require(prof.sbXpOf(io, o) > 0, "has xp");
        // resetSelf wipes the record only
        vm.prank(o);
        prof.resetSelf(io);
        require(prof.skillsOf(io, o)[2] == 0 && prof.taskXpOf(io, o) == 0, "self reset");
        require(prof.collection(io, 5) == 600, "collections stay");
        // resetProfile: holder, solo -> everything 0
        vm.prank(x);
        (bool ok, bytes memory ret) = address(prof).call(abi.encodeCall(SkyProfile.resetProfile, (io)));
        require(!ok && _sel(ret) == SkyProfile.NotHolder.selector, "stranger cannot reset");
        _create(a);
        _isl(o, abi.encodeCall(SkyIsland4.invite, (a)));
        _isl(a, abi.encodeCall(SkyIsland4.accept, (io)));
        vm.prank(o);
        (ok, ret) = address(prof).call(abi.encodeCall(SkyProfile.resetProfile, (io)));
        require(!ok && _sel(ret) == SkyProfile.HasMembers.selector, "not with members");
        _isl(o, abi.encodeCall(SkyIsland4.kick, (a)));
        vm.prank(o);
        prof.resetProfile(io);
        require(prof.collection(io, 5) == 0 && prof.sbXpOf(io, o) == 0, "profile wiped");
        _ok(o, io, 2, 50, 5, 1, 0);
        require(prof.collection(io, 5) == 1 && prof.skillsOf(io, o)[2] == 50, "writes after reset");
    }

    function testTransferStartsNewEra() public {
        uint256 io = _create(o);
        _ok(o, io, 0, 50, 0, 60, 0);
        vm.prank(o);
        isl.transferFrom(o, b, io);
        require(isl.memberIsland(b) == io, "b plays it");
        require(prof.collection(io, 0) == 0 && prof.sbXpOf(io, o) == 0, "new era for the buyer");
        _ok(b, io, 0, 50, 0, 1, 0);
        require(prof.collection(io, 0) == 1 && prof.epochOf(io) == 2, "b era");
    }

    function testCapsAndAttestation() public {
        uint256 io = _create(o);
        (bool ok, bytes memory ret) = _xp(o, io, 9, 1, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.BadSkill.selector, "bad skill");
        (ok, ret) = _xp(o, io, 0, 1_000_001, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.OverCap.selector, "over amount");
        (ok, ret) = _xp(o, io, 0, 0, 0, 0, 10_001);
        require(!ok && _sel(ret) == SkyProfile.OverCap.selector, "over task");
        (ok, ret) = _xp(o, io, 0, 0, 1024, 1, 0);
        require(!ok && _sel(ret) == SkyProfile.BadCollection.selector, "bad collection");
        (ok, ret) = _xp(o, io, 0, 0, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.TooMany.selector, "empty");
        for (uint256 i = 0; i < 20; i++) _ok(o, io, 0, 1_000_000, 0, 0, 0);
        (ok, ret) = _xp(o, io, 0, 1, 0, 0, 0);
        require(!ok && _sel(ret) == SkyProfile.OverCap.selector, "hourly cap");
        vm.warp(block.timestamp + 3601);
        _ok(o, io, 0, 1, 0, 0, 0);
        // mode B: exported key needs an attestation
        vm.prank(o);
        keys.optIn();
        (ok, ret) = _xp(o, io, 0, 1, 0, 0, 0);
        require(!ok && (_sel(ret) == Attested.BadSignature.selector || _sel(ret) == Attested.Expired.selector || _sel(ret) == Attested.NoAttestation.selector), "needs attestation");
        uint8[] memory s = new uint8[](1);
        uint32[] memory sa = new uint32[](1);
        s[0] = 0; sa[0] = 1;
        bytes memory data = _att(o, abi.encodeCall(SkyProfile.applyXp, (io, s, sa, new uint16[](0), new uint32[](0), uint32(0))), address(prof));
        vm.prank(o);
        (ok,) = address(prof).call(data);
        require(ok, "attested write");
    }

    function testAdmin() public {
        vm.prank(x);
        (bool ok,) = address(prof).call(abi.encodeCall(SkyProfile.setSkillCap, (60)));
        require(!ok, "only admin");
        prof.setSkillCap(60);
        require(prof.skillCap() == 60, "cap");
        (ok,) = address(prof).call(abi.encodeCall(SkyProfile.setSkillCap, (30)));
        require(!ok, "no lowering");
        require(!prof.migrating(), "no import");
    }
}
