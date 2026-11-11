// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// SkyRealm: the on-chain economy of Somnia SkyBlock (Shannon testnet 50312).
///
/// Money only moves between players or through the Relic Pool. Nothing here mints STT.
/// Every fee and tax lands in `pool`. Relics, rolled by Somnia VRF after ranked arena
/// runs, are claims on that pool (a parimutuel "risk pool", the Cambria pattern).
///
/// Items are custodial: the Paper server holds them in the SkyWord bag and moves them
/// after the receipt. This contract settles the STT side and leaves the public record.

interface ISomniaVRFCoordinator {
    struct RandomWordsRequest {
        uint32 callbackGasLimit;
        uint16 commitDelayBlocks;
        uint32 numWords;
        bool useVerifiableEntropy;
    }
    function requestRandomWords(RandomWordsRequest calldata req) external returns (uint256 requestId);
}

contract SkyRealm {
    // ---- config ----
    ISomniaVRFCoordinator public immutable coordinator;
    bool public immutable verifiable;
    uint256 public constant BAZAAR_TAX_BP = 125;   // 1.25 % (Hypixel bazaar tax)
    uint256 public constant AUCTION_TAX_BP = 200;  // 2 %
    uint256 public constant LISTING_BP = 100;      // 1 % of the start bid, paid up front
    uint256 public constant MIN_LISTING = 0.001 ether;
    uint256 public constant ARENA_FEE = 0.005 ether;
    uint256 public constant HAIRCUT_BP = 1000;     // 10 % of a cash-in stays in the pool
    uint256 public constant VIRTUAL_RELICS = 10;   // damping: the first relic cannot drain the pool

    // ---- pool ----
    uint256 public pool;
    uint256 public totalRelics;
    mapping(address => uint256) public relics;

    // ---- stats checkpoints ----
    mapping(address => uint256) public stats;
    event Checkpoint(address indexed player, uint256 packed, uint16 milestone);

    // ---- bazaar ----
    struct BuyOrder { address buyer; uint32 item; uint32 left; uint128 unit; }
    uint256 public nextOrder = 1;
    mapping(uint256 => BuyOrder) public buyOrders;
    event OfferFilled(uint256 indexed offerId, address indexed buyer, address indexed seller, uint32 item, uint32 qty, uint256 paid, uint256 tax);
    event BuyOrderPlaced(uint256 indexed orderId, address indexed buyer, uint32 item, uint32 qty, uint256 unit);
    event BuyOrderFilled(uint256 indexed orderId, address indexed seller, uint32 qty, uint256 paid, uint256 tax);
    event BuyOrderCancelled(uint256 indexed orderId, uint256 refund);

    // ---- auction house ----
    struct Auction { address seller; uint64 end; uint32 item; uint32 qty; address bidder; uint128 bid; uint128 start; bool done; }
    uint256 public nextAuction = 1;
    mapping(uint256 => Auction) public auctions;
    event AuctionCreated(uint256 indexed id, address indexed seller, uint32 item, uint32 qty, uint256 start, uint64 end, uint256 fee);
    event Bid(uint256 indexed id, address indexed bidder, uint256 amount);
    event AuctionSettled(uint256 indexed id, address indexed winner, uint256 paid, uint256 tax);

    // ---- arena + VRF ----
    struct Run { address player; uint16 wave; uint32 kills; uint8 status; uint8 relicsWon; uint16 loot; }
    // status: 1 entered, 2 finished (rolling), 3 rolled, 4 finished without roll
    uint256 public nextRun = 1;
    mapping(uint256 => Run) public runs;
    mapping(uint256 => uint256) public runOfRequest;
    mapping(address => uint16) public bestWave;
    event ArenaEntered(uint256 indexed runId, address indexed player);
    event ArenaFinished(uint256 indexed runId, address indexed player, uint16 wave, uint32 kills, uint256 requestId);
    event ArenaLoot(uint256 indexed runId, address indexed player, uint8 relicsWon, uint16 loot, uint256 word);
    event CashIn(address indexed player, uint256 relics, uint256 paid);

    error OnlyCoordinator(address caller);

    constructor(address vrf, bool useVerifiable) {
        coordinator = ISomniaVRFCoordinator(vrf);
        verifiable = useVerifiable;
    }

    receive() external payable { pool += msg.value; }

    // ===== stats =====
    function checkpoint(uint256 packed, uint16 milestone) external {
        stats[msg.sender] = packed;
        emit Checkpoint(msg.sender, packed, milestone);
    }

    // ===== bazaar: sell offers (items held by the server) =====
    function fillOffer(uint256 offerId, address payable seller, uint32 item, uint32 qty) external payable {
        require(msg.value > 0 && qty > 0 && seller != address(0), "bad fill");
        uint256 tax = msg.value * BAZAAR_TAX_BP / 10000;
        pool += tax;
        _pay(seller, msg.value - tax);
        emit OfferFilled(offerId, msg.sender, seller, item, qty, msg.value - tax, tax);
    }

    // ===== bazaar: buy orders (STT escrowed here) =====
    function placeBuyOrder(uint32 item, uint32 qty, uint128 unit) external payable returns (uint256 id) {
        require(qty > 0 && unit > 0 && msg.value == uint256(qty) * unit, "bad order");
        id = nextOrder++;
        buyOrders[id] = BuyOrder(msg.sender, item, qty, unit);
        emit BuyOrderPlaced(id, msg.sender, item, qty, unit);
    }

    function fillBuyOrder(uint256 id, uint32 qty) external {
        BuyOrder storage o = buyOrders[id];
        require(o.buyer != address(0) && qty > 0 && qty <= o.left, "bad fill");
        o.left -= qty;
        uint256 gross = uint256(qty) * o.unit;
        uint256 tax = gross * BAZAAR_TAX_BP / 10000;
        pool += tax;
        _pay(payable(msg.sender), gross - tax);
        emit BuyOrderFilled(id, msg.sender, qty, gross - tax, tax);
    }

    function cancelBuyOrder(uint256 id) external {
        BuyOrder storage o = buyOrders[id];
        require(o.buyer == msg.sender && o.left > 0, "not yours");
        uint256 refund = uint256(o.left) * o.unit;
        o.left = 0;
        _pay(payable(msg.sender), refund);
        emit BuyOrderCancelled(id, refund);
    }

    // ===== auction house =====
    function listingFee(uint256 start) public pure returns (uint256) {
        uint256 fee = start * LISTING_BP / 10000;
        return fee < MIN_LISTING ? MIN_LISTING : fee;
    }

    function createAuction(uint32 item, uint32 qty, uint128 start, uint32 duration) external payable returns (uint256 id) {
        require(qty > 0 && start > 0 && duration >= 60 && duration <= 2 days, "bad auction");
        uint256 fee = listingFee(start);
        require(msg.value == fee, "fee");
        pool += fee;
        id = nextAuction++;
        uint64 end = uint64(block.timestamp + duration);
        auctions[id] = Auction(msg.sender, end, item, qty, address(0), 0, start, false);
        emit AuctionCreated(id, msg.sender, item, qty, start, end, fee);
    }

    function minBid(uint256 id) public view returns (uint256) {
        Auction storage a = auctions[id];
        if (a.bid == 0) return a.start;
        return uint256(a.bid) * 105 / 100 + 1;
    }

    function bid(uint256 id) external payable {
        Auction storage a = auctions[id];
        require(a.seller != address(0) && !a.done && block.timestamp < a.end, "closed");
        require(msg.sender != a.seller, "own auction");
        require(msg.value >= minBid(id), "low bid");
        address previous = a.bidder;
        uint256 back = a.bid;
        a.bidder = msg.sender;
        a.bid = uint128(msg.value);
        if (a.end - block.timestamp < 30) a.end = uint64(block.timestamp + 30); // anti-snipe
        if (previous != address(0)) _pay(payable(previous), back);
        emit Bid(id, msg.sender, msg.value);
    }

    function settle(uint256 id) external {
        Auction storage a = auctions[id];
        require(a.seller != address(0) && !a.done && block.timestamp >= a.end, "running");
        a.done = true;
        uint256 tax = 0;
        if (a.bid > 0) {
            tax = uint256(a.bid) * AUCTION_TAX_BP / 10000;
            pool += tax;
            _pay(payable(a.seller), uint256(a.bid) - tax);
        }
        emit AuctionSettled(id, a.bidder, a.bid > 0 ? uint256(a.bid) - tax : 0, tax);
    }

    // ===== arena =====
    function enterArena() external payable returns (uint256 id) {
        require(msg.value == ARENA_FEE, "fee");
        pool += msg.value;
        id = nextRun++;
        runs[id] = Run(msg.sender, 0, 0, 1, 0, 0);
        emit ArenaEntered(id, msg.sender);
    }

    function finishRun(uint256 id, uint16 wave, uint32 kills) external returns (uint256 requestId) {
        Run storage r = runs[id];
        require(r.player == msg.sender && r.status == 1 && wave <= 50, "bad run");
        r.wave = wave;
        r.kills = kills;
        if (wave > bestWave[msg.sender]) bestWave[msg.sender] = wave;
        if (wave < 3) {
            r.status = 4;
        } else {
            r.status = 2;
            requestId = coordinator.requestRandomWords(ISomniaVRFCoordinator.RandomWordsRequest({
                callbackGasLimit: 1_500_000,
                commitDelayBlocks: 16,
                numWords: 1,
                useVerifiableEntropy: verifiable
            }));
            runOfRequest[requestId] = id;
        }
        emit ArenaFinished(id, msg.sender, wave, kills, requestId);
    }

    function rawFulfillRandomWords(uint256 requestId, uint256[] calldata words) external {
        if (msg.sender != address(coordinator)) revert OnlyCoordinator(msg.sender);
        uint256 id = runOfRequest[requestId];
        Run storage r = runs[id];
        if (id == 0 || r.status != 2 || words.length == 0) return;
        uint256 word = words[0];
        uint256 chance = 5 + uint256(r.wave) * 4;           // wave 3 -> 17 %, wave 10 -> 45 %
        if (chance > 60) chance = 60;
        uint8 won = 0;
        if (word % 100 < chance) won = r.wave >= 10 ? 2 : 1;
        uint16 loot = uint16((word >> 32) % 1000);           // the server maps this to a drop table
        r.status = 3;
        r.relicsWon = won;
        r.loot = loot;
        if (won > 0) {
            relics[r.player] += won;
            totalRelics += won;
        }
        emit ArenaLoot(id, r.player, won, loot, word);
    }

    // ===== relic pool =====
    function relicValue() public view returns (uint256) {
        return pool * (10000 - HAIRCUT_BP) / 10000 / (totalRelics + VIRTUAL_RELICS);
    }

    function cashIn(uint256 n) external {
        require(n > 0 && relics[msg.sender] >= n, "relics");
        uint256 paid = relicValue() * n;
        relics[msg.sender] -= n;
        totalRelics -= n;
        pool -= paid;
        _pay(payable(msg.sender), paid);
        emit CashIn(msg.sender, n, paid);
    }

    function _pay(address payable to, uint256 amount) internal {
        if (amount == 0) return;
        (bool ok, ) = to.call{value: amount}("");
        require(ok, "pay");
    }
}
