// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Attested.sol";

interface IERC721Receiver {
    function onERC721Received(address operator, address from, uint256 tokenId, bytes calldata data) external returns (bytes4);
}

/// Catharsis island deeds as ERC-721 tokens, with full onchain co-op (Hypixel SkyBlock rules: up to 5 players
/// on one island) and an island market (Buy-It-Now and bid auctions) built in.
///
/// Every deed is one island. The deed owner plus up to 4 co-op members play on it. `memberIsland(player)` is the
/// island a player plays on (their active deed, or the co-op they joined). A wallet can hold more deeds than it
/// plays on; the other deeds are held and can be sold, sent, or made active with useIsland().
///
/// ERC-721: approve, setApprovalForAll, transferFrom and both safeTransferFrom work from any wallet, without a
/// server attestation. A deed moves only when it has no co-op members and is not listed for sale. A received deed
/// becomes the receiver's active island when they play on none; otherwise it is held.
///
/// Game writes (createIsland, useIsland, co-op, market listings, buys, bids) are server-attested (Attested.sol,
/// EIP-712 "Catharsis" v1) and sent and paid for by the player they concern. settleIsland() is open to anyone.
/// Market fees follow SkyBin2: 1% listing fee (min 0.001 STT) up front, 2% tax on the sale; half of every fee is
/// burned to 0x...dEaD and half goes to the SkyRealm Relic Pool. Bids follow SkyRealm2: +5% minimum raise,
/// 30 s anti-snipe, outbid STT credited for withdrawPending().
///
/// Imported from SkyIsland3 once (admin, then closed).
contract SkyIsland4 is Attested {
    uint256 public constant VERSION = 4;
    uint256 public constant MAX_MEMBERS = 5; // owner + 4
    uint256 public constant INVITE_TTL = 1 hours;
    uint256 public constant LISTING_BP = 100;
    uint256 public constant MIN_LISTING = 0.001 ether;
    uint256 public constant TAX_BP = 200;
    uint256 public constant BURN_SHARE_BP = 5000;
    uint256 public constant MIN_DURATION = 60;
    uint256 public constant MAX_DURATION = 14 days;
    uint256 public constant ANTI_SNIPE = 30;
    uint8 public constant BIN = 1;
    uint8 public constant AUCTION = 2;
    address payable public constant BURN = payable(0x000000000000000000000000000000000000dEaD);

    string public constant name = "Catharsis Island";
    string public constant symbol = "ISLAND";

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

    struct Sale {
        address seller;
        uint64 end;
        uint8 kind;
        uint128 price; // BIN price, or the starting bid
        uint128 bid;
        address bidder;
    }

    address payable public immutable realm;
    string public baseURI;
    uint256 public nextId = 1;
    mapping(uint256 => Deed) private deeds;
    mapping(address => uint256) public memberIsland;
    /// Legacy island counters (SkyIsland v1-v3), kept readable; the game writes XP and collections to SkySkills.
    mapping(address => Progress) public progress;
    mapping(uint256 => address) private owners;
    mapping(address => uint256) public balanceOf;
    mapping(uint256 => mapping(address => Invite)) private invites;
    mapping(uint256 => address) private approvals;
    mapping(address => mapping(address => bool)) public isApprovedForAll;
    mapping(uint256 => Sale) public sales;
    /// Outbid refunds and failed payouts, pulled with withdrawPending().
    mapping(address => uint256) public pending;
    uint256 public pendingTotal;
    uint256 public burned;
    uint256 public pooled;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);
    event Approval(address indexed owner, address indexed approved, uint256 indexed id);
    event ApprovalForAll(address indexed owner, address indexed operator, bool approved);
    event IslandActive(address indexed player, uint256 indexed id);
    event Invited(uint256 indexed id, address indexed member, address indexed by, uint64 until);
    event InviteRevoked(uint256 indexed id, address indexed member);
    event Joined(uint256 indexed id, address indexed member, uint256 released);
    event Left(uint256 indexed id, address indexed member);
    event Kicked(uint256 indexed id, address indexed member, address indexed by);
    event IslandListed(uint256 indexed id, address indexed seller, uint8 kind, uint256 price, uint64 end, uint256 fee);
    event IslandBid(uint256 indexed id, address indexed bidder, uint256 amount, uint64 end);
    event IslandSold(uint256 indexed id, address indexed buyer, address indexed seller, uint256 paid, uint256 tax);
    event IslandSaleClosed(uint256 indexed id, address indexed seller);
    event Credited(address indexed to, uint256 amount);
    event Withdrawn(address indexed to, uint256 amount);
    event FeeSink(uint256 burned, uint256 pooled);
    event BaseURI(string uri);

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
    error NoDeed();
    error NotApproved();
    error Listed();
    error InCoop();
    error BadSale();
    error SaleClosed();
    error SaleRunning();
    error OwnSale();
    error WrongValue();
    error LowBid();
    error HasBids();
    error NotReceiver();
    error Nothing();
    error PayFailed();

    constructor(address signer_, address keys_, address payable realm_, string memory baseURI_) Attested(signer_, keys_) {
        if (realm_ == address(0)) revert BadAddress();
        realm = realm_;
        baseURI = baseURI_;
        emit BaseURI(baseURI_);
    }

    // ------------------------------------------------------------------ ERC-165 / ERC-721 metadata

    function supportsInterface(bytes4 id) external pure returns (bool) {
        return id == 0x01ffc9a7 || id == 0x80ac58cd || id == 0x5b5e139f;
    }

    function tokenURI(uint256 id) external view returns (string memory) {
        if (owners[id] == address(0)) revert NoDeed();
        return string.concat(baseURI, _str(id));
    }

    function setBaseURI(string calldata uri) external onlyAdmin {
        baseURI = uri;
        emit BaseURI(uri);
    }

    // ------------------------------------------------------------------ ERC-721

    function ownerOf(uint256 id) public view returns (address owner) {
        owner = owners[id];
        if (owner == address(0)) revert NoDeed();
    }

    /// ownerOf without the revert: address(0) when the deed does not exist.
    function holderOf(uint256 id) external view returns (address) {
        return owners[id];
    }

    function getApproved(uint256 id) external view returns (address) {
        if (owners[id] == address(0)) revert NoDeed();
        return approvals[id];
    }

    function approve(address to, uint256 id) external {
        address owner = ownerOf(id);
        if (msg.sender != owner && !isApprovedForAll[owner][msg.sender]) revert NotApproved();
        approvals[id] = to;
        emit Approval(owner, to, id);
    }

    function setApprovalForAll(address operator, bool approved) external {
        if (operator == msg.sender) revert BadAddress();
        isApprovedForAll[msg.sender][operator] = approved;
        emit ApprovalForAll(msg.sender, operator, approved);
    }

    /// Moves a deed with no co-op members that is not listed for sale.
    function transferFrom(address from, address to, uint256 id) public {
        address owner = ownerOf(id);
        if (owner != from) revert NotOwner();
        if (msg.sender != owner && approvals[id] != msg.sender && !isApprovedForAll[owner][msg.sender]) revert NotApproved();
        if (to == address(0) || to == from || to == address(this)) revert BadAddress();
        if (deeds[id].coopCount != 0) revert HasMembers();
        if (sales[id].seller != address(0)) revert Listed();
        _transfer(from, to, id, false);
    }

    function safeTransferFrom(address from, address to, uint256 id) external {
        safeTransferFrom(from, to, id, "");
    }

    function safeTransferFrom(address from, address to, uint256 id, bytes memory data) public {
        transferFrom(from, to, id);
        if (to.code.length > 0) {
            try IERC721Receiver(to).onERC721Received(msg.sender, from, id, data) returns (bytes4 got) {
                if (got != IERC721Receiver.onERC721Received.selector) revert NotReceiver();
            } catch {
                revert NotReceiver();
            }
        }
    }

    // ------------------------------------------------------------------ deeds

    function createIsland() external attested returns (uint256 id) {
        if (memberIsland[msg.sender] != 0) revert HasIsland();
        id = nextId++;
        _mint(msg.sender, id);
    }

    /// Makes a held deed the caller's active island. Not while the caller is in a co-op (as a member, or as the
    /// owner of an island with members); a solo island the caller was on stays held.
    function useIsland(uint256 id) external attested {
        if (owners[id] != msg.sender) revert NotOwner();
        uint256 current = memberIsland[msg.sender];
        if (current == id) revert AlreadyMember();
        if (current != 0 && !_solo(msg.sender, current)) revert InCoop();
        memberIsland[msg.sender] = id;
        emit IslandActive(msg.sender, id);
    }

    // ------------------------------------------------------------------ co-op

    function invite(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || owners[id] != msg.sender) revert NotOwner();
        if (sales[id].seller != address(0)) revert Listed();
        if (member == address(0) || member == msg.sender) revert BadAddress();
        if (memberIsland[member] == id) revert AlreadyMember();
        if (uint256(deeds[id].coopCount) + 1 >= MAX_MEMBERS) revert Full();
        uint64 until = uint64(block.timestamp + INVITE_TTL);
        invites[id][member] = Invite(msg.sender, until);
        emit Invited(id, member, msg.sender, until);
    }

    function revokeInvite(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || owners[id] != msg.sender) revert NotOwner();
        if (invites[id][member].until == 0) revert NotInvited();
        delete invites[id][member];
        emit InviteRevoked(id, member);
    }

    function accept(uint256 id) external attested {
        Invite memory inv = invites[id][msg.sender];
        // The invite is live only while its sender still owns the deed and plays on it.
        if (inv.until == 0 || inv.until < block.timestamp || inv.by == address(0) || owners[id] != inv.by || memberIsland[inv.by] != id) {
            revert NotInvited();
        }
        if (sales[id].seller != address(0)) revert Listed();
        delete invites[id][msg.sender];
        uint256 current = memberIsland[msg.sender];
        uint256 released;
        if (current != 0) {
            if (current == id) revert AlreadyMember();
            // Only a solo deed can be given up on join; a member must leave first, an owner with members cannot join.
            if (!_solo(msg.sender, current)) revert AlreadyMember();
            if (sales[current].seller != address(0)) revert Listed();
            _burn(current, msg.sender);
            released = current;
        }
        _join(id, msg.sender);
        emit Joined(id, msg.sender, released);
    }

    function leave() external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0) revert NotMember();
        if (owners[id] == msg.sender) revert OwnerCannotLeave();
        _remove(id, msg.sender);
        emit Left(id, msg.sender);
    }

    function kick(address member) external attested {
        uint256 id = memberIsland[msg.sender];
        if (id == 0 || memberIsland[member] != id) revert NotMember();
        if (member == owners[id]) revert CannotKickOwner();
        if (member == msg.sender) revert BadAddress();
        _remove(id, member);
        emit Kicked(id, member, msg.sender);
    }

    // ------------------------------------------------------------------ island market

    function listingFee(uint256 price) public pure returns (uint256) {
        uint256 fee = price * LISTING_BP / 10000;
        return fee < MIN_LISTING ? MIN_LISTING : fee;
    }

    function minBid(uint256 id) public view returns (uint256) {
        Sale storage s = sales[id];
        if (s.bid == 0) return s.price;
        return uint256(s.bid) * 105 / 100 + 1;
    }

    /// Lists a deed the caller owns (no co-op members) for Buy-It-Now (kind 1) or bids (kind 2). The deed stays with
    /// the seller and cannot move, take members, or be given up until the sale ends.
    function listIsland(uint256 id, uint8 kind, uint128 price, uint32 duration) external payable attested {
        if (owners[id] != msg.sender) revert NotOwner();
        if (deeds[id].coopCount != 0) revert HasMembers();
        if (sales[id].seller != address(0)) revert Listed();
        if ((kind != BIN && kind != AUCTION) || price == 0 || duration < MIN_DURATION || duration > MAX_DURATION) revert BadSale();
        uint256 fee = listingFee(price);
        if (msg.value != fee) revert WrongValue();
        uint64 end = uint64(block.timestamp + duration);
        sales[id] = Sale(msg.sender, end, kind, price, 0, address(0));
        _sink(fee);
        emit IslandListed(id, msg.sender, kind, price, end, fee);
    }

    function buyIsland(uint256 id) external payable attested {
        Sale memory s = sales[id];
        if (s.seller == address(0) || s.kind != BIN || block.timestamp >= s.end) revert SaleClosed();
        if (msg.sender == s.seller) revert OwnSale();
        if (msg.value != s.price) revert WrongValue();
        delete sales[id];
        uint256 tax = msg.value * TAX_BP / 10000;
        _sink(tax);
        _transfer(s.seller, msg.sender, id, true);
        _payOrCredit(s.seller, msg.value - tax);
        emit IslandSold(id, msg.sender, s.seller, msg.value - tax, tax);
    }

    function bidIsland(uint256 id) external payable attested {
        Sale storage s = sales[id];
        if (s.seller == address(0) || s.kind != AUCTION || block.timestamp >= s.end) revert SaleClosed();
        if (msg.sender == s.seller) revert OwnSale();
        if (msg.value < minBid(id) || msg.value > type(uint128).max) revert LowBid();
        address previous = s.bidder;
        uint256 back = s.bid;
        s.bidder = msg.sender;
        s.bid = uint128(msg.value);
        if (s.end - block.timestamp < ANTI_SNIPE) s.end = uint64(block.timestamp + ANTI_SNIPE);
        if (previous != address(0)) _credit(previous, back);
        emit IslandBid(id, msg.sender, msg.value, s.end);
    }

    /// Ends a finished auction (anyone may call): the top bidder gets the deed and the seller the STT, or the deed
    /// is unlocked when there were no bids. Also closes an expired BIN listing.
    function settleIsland(uint256 id) external {
        Sale memory s = sales[id];
        if (s.seller == address(0)) revert SaleClosed();
        if (block.timestamp < s.end) revert SaleRunning();
        delete sales[id];
        if (s.kind == AUCTION && s.bid > 0) {
            uint256 tax = uint256(s.bid) * TAX_BP / 10000;
            _sink(tax);
            _transfer(s.seller, s.bidder, id, true);
            _payOrCredit(s.seller, uint256(s.bid) - tax);
            emit IslandSold(id, s.bidder, s.seller, uint256(s.bid) - tax, tax);
        } else {
            emit IslandSaleClosed(id, s.seller);
        }
    }

    /// The seller takes a listing down: a BIN at any time, an auction only while it has no bids.
    function cancelIslandSale(uint256 id) external attested {
        Sale memory s = sales[id];
        if (s.seller != msg.sender) revert NotOwner();
        if (s.kind == AUCTION && s.bid > 0) revert HasBids();
        delete sales[id];
        emit IslandSaleClosed(id, msg.sender);
    }

    function withdrawPending() external {
        uint256 amount = pending[msg.sender];
        if (amount == 0) revert Nothing();
        pending[msg.sender] = 0;
        pendingTotal -= amount;
        _pay(payable(msg.sender), amount);
        emit Withdrawn(msg.sender, amount);
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

    // ------------------------------------------------------------------ one-time import from SkyIsland3 (admin, then closeMigration)

    function migrateIsland(uint256 id, address owner, address[] calldata coop) external migration {
        require(id != 0 && owner != address(0) && owners[id] == address(0) && memberIsland[owner] == 0, "deed");
        _mint(owner, id);
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

    function _solo(address player, uint256 id) private view returns (bool) {
        return owners[id] == player && deeds[id].coopCount == 0;
    }

    function _mint(address to, uint256 id) private {
        deeds[id].owner = to;
        owners[id] = to;
        balanceOf[to] += 1;
        memberIsland[to] = id;
        emit Transfer(address(0), to, id);
        emit IslandActive(to, id);
    }

    /// `activate`: a bought deed also replaces the buyer's solo island (which stays held); a deed sent with
    /// transferFrom becomes active only for a receiver who plays on no island.
    function _transfer(address from, address to, uint256 id, bool activate) private {
        delete approvals[id];
        owners[id] = to;
        deeds[id].owner = to;
        balanceOf[from] -= 1;
        balanceOf[to] += 1;
        if (memberIsland[from] == id) {
            memberIsland[from] = 0;
            emit IslandActive(from, 0);
        }
        uint256 current = memberIsland[to];
        if (current == 0 || (activate && _solo(to, current))) {
            memberIsland[to] = id;
            emit IslandActive(to, id);
        }
        emit Transfer(from, to, id);
    }

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
        delete approvals[id];
        owners[id] = address(0);
        deeds[id].owner = address(0);
        balanceOf[owner] -= 1;
        memberIsland[owner] = 0;
        emit Transfer(owner, address(0), id);
    }

    function _sink(uint256 amount) private {
        if (amount == 0) return;
        uint256 burn = amount * BURN_SHARE_BP / 10000;
        uint256 rest = amount - burn;
        burned += burn;
        pooled += rest;
        _pay(BURN, burn);
        _pay(realm, rest);
        emit FeeSink(burn, rest);
    }

    function _credit(address to, uint256 amount) private {
        if (amount == 0) return;
        pending[to] += amount;
        pendingTotal += amount;
        emit Credited(to, amount);
    }

    function _payOrCredit(address to, uint256 amount) private {
        if (amount == 0) return;
        (bool ok, ) = payable(to).call{value: amount, gas: 50_000}("");
        if (!ok) _credit(to, amount);
    }

    function _pay(address payable to, uint256 amount) private {
        if (amount == 0) return;
        (bool ok, ) = to.call{value: amount}("");
        if (!ok) revert PayFailed();
    }

    function _str(uint256 v) private pure returns (string memory) {
        if (v == 0) return "0";
        uint256 len;
        for (uint256 t = v; t != 0; t /= 10) len++;
        bytes memory out = new bytes(len);
        while (v != 0) {
            out[--len] = bytes1(uint8(48 + v % 10));
            v /= 10;
        }
        return string(out);
    }
}
