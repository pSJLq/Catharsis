// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "../SkyKeys.sol";
import "../SkyRealm2.sol";
import "../SkyBin2.sol";

interface VmT {
    function prank(address) external;
    function warp(uint256) external;
    function deal(address, uint256) external;
    function addr(uint256) external returns (address);
    function sign(uint256, bytes32) external returns (uint8, bytes32, bytes32);
}

/// Player-to-player trades between two wallets on SkyRealm2 (Bazaar, auctions) and SkyBin2 (BIN).
/// Items are held by the server; these tests check the STT side and the guards the server relies on.
contract TradeFlowsTest {
    VmT constant vm = VmT(address(uint160(uint256(keccak256("hevm cheat code")))));
    bytes32 constant TYPEHASH = keccak256("Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)");
    uint256 constant SIGNER_PK = 0x5151;

    SkyKeys keys;
    SkyRealm2 realm;
    SkyBin2 bin;
    address alice = address(0xA11CE);
    address bob = address(0xB0B);
    uint64 nonce = 1;

    function setUp() public {
        vm.warp(1_800_000_000);
        address signer = vm.addr(SIGNER_PK);
        keys = new SkyKeys(signer);
        realm = new SkyRealm2(address(0xF00D), false, signer, address(keys));
        bin = new SkyBin2(payable(address(realm)), signer, address(keys));
        vm.deal(alice, 100 ether);
        vm.deal(bob, 100 ether);
    }

    function _send(address player, address target, bytes memory body, uint256 value) internal returns (bool ok, bytes memory ret) {
        uint32 deadline = uint32(block.timestamp + 300);
        uint64 n = nonce++;
        bytes32 digest = keccak256(abi.encodePacked(hex"1901", Attested(target).domainSeparator(),
                keccak256(abi.encode(TYPEHASH, player, target, keccak256(body), value, uint256(n), uint256(deadline)))));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(SIGNER_PK, digest);
        bytes32 vs = bytes32(uint256(s) | (uint256(v - 27) << 255));
        vm.prank(player);
        (ok, ret) = target.call{value: value}(abi.encodePacked(body, n, deadline, r, vs));
    }

    function _ok(address player, address target, bytes memory body, uint256 value) internal returns (bytes memory ret) {
        bool ok;
        (ok, ret) = _send(player, target, body, value);
        require(ok, "call reverted");
    }

    function _no(address player, address target, bytes memory body, uint256 value) internal {
        (bool ok, ) = _send(player, target, body, value);
        require(!ok, "expected a revert");
    }

    function testBazaarSellOffer() public {
        // Alice lists 10 items server-side; Bob fills 4 at 0.01 STT: Alice gets 98.75 %, the pool 1.25 %.
        uint256 before = alice.balance;
        _ok(bob, address(realm), abi.encodeCall(SkyRealm2.fillOffer, (1, payable(alice), 7, 4)), 0.04 ether);
        require(alice.balance - before == 0.04 ether * 9875 / 10000, "seller paid minus tax");
        require(realm.pool() == 0.04 ether * 125 / 10000, "tax in pool");
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.fillOffer, (1, payable(alice), 7, 0)), 0.01 ether);
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.fillOffer, (1, payable(alice), 7, 1)), 0);
        // Without the server attestation the fill is refused.
        vm.prank(bob);
        (bool ok, ) = address(realm).call{value: 0.01 ether}(abi.encodeCall(SkyRealm2.fillOffer, (1, payable(alice), 7, 1)));
        require(!ok, "unattested fill");
    }

    function testBazaarBuyOrder() public {
        // Bob escrows 5 x 0.02; Alice sells 3, Bob cancels the rest and gets 2 x 0.02 back.
        bytes memory ret = _ok(bob, address(realm), abi.encodeCall(SkyRealm2.placeBuyOrder, (7, 5, 0.02 ether)), 0.1 ether);
        uint256 id = abi.decode(ret, (uint256));
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.placeBuyOrder, (7, 5, 0.02 ether)), 0.09 ether); // wrong escrow
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.fillBuyOrder, (id, 1)), 0); // own order
        uint256 a0 = alice.balance;
        _ok(alice, address(realm), abi.encodeCall(SkyRealm2.fillBuyOrder, (id, 3)), 0);
        require(alice.balance - a0 == 0.06 ether * 9875 / 10000, "seller paid from escrow");
        _no(alice, address(realm), abi.encodeCall(SkyRealm2.fillBuyOrder, (id, 3)), 0); // only 2 left
        vm.prank(alice);
        (bool ok, ) = address(realm).call(abi.encodeCall(SkyRealm2.cancelBuyOrder, (id)));
        require(!ok, "only the buyer cancels");
        uint256 b0 = bob.balance;
        vm.prank(bob);
        realm.cancelBuyOrder(id);
        require(bob.balance - b0 == 0.04 ether, "rest refunded");
        _no(alice, address(realm), abi.encodeCall(SkyRealm2.fillBuyOrder, (id, 1)), 0); // nothing left
    }

    function testAuctionBidOutbidSettle() public {
        uint256 fee = realm.listingFee(0.1 ether);
        bytes memory ret = _ok(alice, address(realm), abi.encodeCall(SkyRealm2.createAuction, (7, 1, 0.1 ether, 600)), fee);
        uint256 id = abi.decode(ret, (uint256));
        _no(alice, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 0.1 ether); // own auction
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 0.09 ether); // below start
        _ok(bob, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 0.1 ether);
        address carol = address(0xCA201);
        vm.deal(carol, 1 ether);
        _no(carol, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 0.104 ether); // under +5 %
        _ok(carol, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 0.2 ether);
        require(realm.pending(bob) == 0.1 ether, "outbid STT credited");
        uint256 b0 = bob.balance;
        vm.prank(bob);
        realm.withdrawPending();
        require(bob.balance - b0 == 0.1 ether, "outbid refund pulled");
        vm.prank(bob);
        (bool ok, ) = address(realm).call(abi.encodeCall(SkyRealm2.settle, (id)));
        require(!ok, "cannot settle while running");
        vm.warp(block.timestamp + 601);
        _no(bob, address(realm), abi.encodeCall(SkyRealm2.bid, (id)), 1 ether); // ended
        uint256 a0 = alice.balance;
        realm.settle(id);
        require(alice.balance - a0 == 0.2 ether * 98 / 100, "seller paid minus 2 %");
        (bool again, ) = address(realm).call(abi.encodeCall(SkyRealm2.settle, (id)));
        require(!again, "settled once");
    }

    function testAuctionNoBids() public {
        uint256 fee = realm.listingFee(0.05 ether);
        bytes memory ret = _ok(alice, address(realm), abi.encodeCall(SkyRealm2.createAuction, (7, 2, 0.05 ether, 60)), fee);
        uint256 id = abi.decode(ret, (uint256));
        vm.warp(block.timestamp + 61);
        uint256 a0 = alice.balance;
        realm.settle(id);
        require(alice.balance == a0, "no bids: no STT moves (the server returns the item)");
    }

    function testBinBuyCancelExpire() public {
        uint256 fee = bin.listingFee(0.3 ether);
        bytes memory ret = _ok(alice, address(bin), abi.encodeCall(SkyBin2.list, (7, 1, 0.3 ether, 3600)), fee);
        uint256 id = abi.decode(ret, (uint256));
        _no(alice, address(bin), abi.encodeCall(SkyBin2.buy, (id)), 0.3 ether); // own listing
        _no(bob, address(bin), abi.encodeCall(SkyBin2.buy, (id)), 0.29 ether); // wrong price
        _no(bob, address(bin), abi.encodeCall(SkyBin2.cancel, (id)), 0); // not the seller
        uint256 a0 = alice.balance;
        _ok(bob, address(bin), abi.encodeCall(SkyBin2.buy, (id)), 0.3 ether);
        require(alice.balance - a0 == 0.3 ether * 98 / 100, "seller paid minus 2 %");
        _no(bob, address(bin), abi.encodeCall(SkyBin2.buy, (id)), 0.3 ether); // sold once
        _no(alice, address(bin), abi.encodeCall(SkyBin2.cancel, (id)), 0); // sold: no cancel
        // A second listing: cancelled, then nobody can buy it; a third one expires.
        ret = _ok(alice, address(bin), abi.encodeCall(SkyBin2.list, (7, 1, 0.3 ether, 3600)), fee);
        uint256 id2 = abi.decode(ret, (uint256));
        _ok(alice, address(bin), abi.encodeCall(SkyBin2.cancel, (id2)), 0);
        _no(bob, address(bin), abi.encodeCall(SkyBin2.buy, (id2)), 0.3 ether);
        ret = _ok(alice, address(bin), abi.encodeCall(SkyBin2.list, (7, 1, 0.3 ether, 60)), fee);
        uint256 id3 = abi.decode(ret, (uint256));
        vm.warp(block.timestamp + 61);
        _no(bob, address(bin), abi.encodeCall(SkyBin2.buy, (id3)), 0.3 ether);
    }
}
