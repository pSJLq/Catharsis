// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "../SkySkills2.sol";

interface Vm {
    function prank(address) external;
    function expectRevert(bytes4) external;
}

contract SkySkills2Test {
    Vm constant vm = Vm(address(uint160(uint256(keccak256("hevm cheat code")))));
    SkySkills2 s;
    address p = address(0xA11CE);
    address q = address(0xB0B);

    function setUp() public {
        s = new SkySkills2();
    }

    function _add(address who, uint32 a, uint16 c, uint32 ca) internal {
        uint8[] memory sk = new uint8[](2);
        sk[0] = 0;
        sk[1] = 8;
        uint32[] memory am = new uint32[](2);
        am[0] = a;
        am[1] = a * 2;
        uint16[] memory ci = new uint16[](2);
        ci[0] = c;
        ci[1] = 1023;
        uint32[] memory cam = new uint32[](2);
        cam[0] = ca;
        cam[1] = 7;
        vm.prank(who);
        s.applyXp(sk, am, ci, cam);
    }

    function testResetFlow() public {
        _add(p, 50, 3, 10);
        _add(p, 5, 3, 1);
        _add(q, 9, 3, 4);
        uint64[9] memory x = s.xpOf(p);
        require(x[0] == 55 && x[8] == 110, "xp");
        require(s.collection(p, 3) == 11 && s.collection(p, 1023) == 14, "col");
        vm.prank(p);
        uint32 e = s.resetProfile();
        require(e == 1 && s.epochOf(p) == 1, "epoch");
        x = s.xpOf(p);
        for (uint256 i; i < 9; i++) require(x[i] == 0, "zero xp");
        require(s.collection(p, 3) == 0 && s.collection(p, 1023) == 0, "zero col");
        uint16[] memory ids = new uint16[](2);
        ids[0] = 3;
        ids[1] = 1023;
        uint64[] memory c = s.collectionsOf(p, ids);
        require(c[0] == 0 && c[1] == 0, "zero cols");
        require(s.xpAt(p, 0)[0] == 55 && s.collectionAt(p, 0, 3) == 11, "history");
        require(s.xpOf(q)[0] == 9 && s.collection(q, 3) == 4, "other");
        _add(p, 2, 3, 1);
        require(s.xpOf(p)[0] == 2 && s.collection(p, 3) == 1, "fresh");
    }

    function testOperator() public {
        _add(p, 5, 0, 1);
        vm.expectRevert(SkySkills2.NotAllowed.selector);
        vm.prank(q);
        s.resetProfileFor(p);
        vm.prank(p);
        s.setResetOperator(q);
        vm.prank(q);
        s.resetProfileFor(p);
        require(s.xpOf(p)[0] == 0 && s.epochOf(p) == 1, "op reset");
    }

    function testLimits() public {
        uint8[] memory sk = new uint8[](1);
        sk[0] = 0;
        uint32[] memory am = new uint32[](1);
        am[0] = 1_000_001;
        uint16[] memory ci = new uint16[](0);
        uint32[] memory cam = new uint32[](0);
        vm.expectRevert(SkySkills2.OverCap.selector);
        vm.prank(p);
        s.applyXp(sk, am, ci, cam);
        sk[0] = 9;
        am[0] = 1;
        vm.expectRevert(SkySkills2.BadSkill.selector);
        vm.prank(p);
        s.applyXp(sk, am, ci, cam);
        uint8[] memory big = new uint8[](65);
        uint32[] memory bam = new uint32[](65);
        vm.expectRevert(SkySkills2.TooMany.selector);
        vm.prank(p);
        s.applyXp(big, bam, ci, cam);
    }
}
