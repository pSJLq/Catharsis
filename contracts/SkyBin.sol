// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// SkyBin: Buy-It-Now listings for the Somnia SkyBlock Auction House (Shannon testnet 50312).
///
/// Bid auctions stay in SkyRealm (createAuction / bid / settle). This contract adds fixed-price BIN.
/// Items are custodial (the Paper server holds them while listed); this contract settles the STT side.
///
/// Fees are an STT sink: half of every fee is burned to 0x…dEaD, the other half goes to the
/// SkyRealm Relic Pool (SkyRealm.receive() adds it to `pool`). Nothing here mints STT.
contract SkyBin {
    address payable public immutable realm;
    address payable public constant BURN = payable(0x000000000000000000000000000000000000dEaD);
    uint256 public constant LISTING_BP = 100;      // 1 % of the price, paid up front
    uint256 public constant MIN_LISTING = 0.001 ether;
    uint256 public constant TAX_BP = 200;          // 2 % of the sale
    uint256 public constant BURN_SHARE_BP = 5000;  // 50 % of every fee is burned

    struct Listing { address seller; uint64 end; uint32 item; uint32 qty; uint128 price; uint8 state; }
    // state: 1 open, 2 sold, 3 cancelled
    uint256 public nextListing = 1;
    mapping(uint256 => Listing) public listings;
    uint256 public burned;
    uint256 public pooled;

    event BinListed(uint256 indexed id, address indexed seller, uint32 item, uint32 qty, uint256 price, uint64 end, uint256 fee);
    event BinSold(uint256 indexed id, address indexed buyer, address indexed seller, uint256 paid, uint256 tax);
    event BinCancelled(uint256 indexed id, address indexed seller);
    event FeeSink(uint256 burned, uint256 pooled);

    constructor(address payable realm_) {
        realm = realm_;
    }

    function listingFee(uint256 price) public pure returns (uint256) {
        uint256 fee = price * LISTING_BP / 10000;
        return fee < MIN_LISTING ? MIN_LISTING : fee;
    }

    function list(uint32 item, uint32 qty, uint128 price, uint32 duration) external payable returns (uint256 id) {
        require(qty > 0 && price > 0 && duration >= 60 && duration <= 14 days, "bad listing");
        uint256 fee = listingFee(price);
        require(msg.value == fee, "fee");
        id = nextListing++;
        uint64 end = uint64(block.timestamp + duration);
        listings[id] = Listing(msg.sender, end, item, qty, price, 1);
        _sink(fee);
        emit BinListed(id, msg.sender, item, qty, price, end, fee);
    }

    function buy(uint256 id) external payable {
        Listing storage l = listings[id];
        require(l.state == 1 && block.timestamp < l.end, "closed");
        require(msg.sender != l.seller, "own listing");
        require(msg.value == l.price, "price");
        l.state = 2;
        uint256 tax = msg.value * TAX_BP / 10000;
        _sink(tax);
        _pay(payable(l.seller), msg.value - tax);
        emit BinSold(id, msg.sender, l.seller, msg.value - tax, tax);
    }

    function cancel(uint256 id) external {
        Listing storage l = listings[id];
        require(l.seller == msg.sender && l.state == 1, "not yours");
        l.state = 3;
        emit BinCancelled(id, msg.sender);
    }

    function _sink(uint256 amount) internal {
        if (amount == 0) return;
        uint256 burn = amount * BURN_SHARE_BP / 10000;
        uint256 rest = amount - burn;
        burned += burn;
        pooled += rest;
        _pay(BURN, burn);
        _pay(realm, rest);
        emit FeeSink(burn, rest);
    }

    function _pay(address payable to, uint256 amount) internal {
        if (amount == 0) return;
        (bool ok, ) = to.call{value: amount}("");
        require(ok, "pay");
    }
}
