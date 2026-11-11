// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Attested.sol";

/// Voidstep Blade tokens (ERC-721). While a blade is in the game it is locked: only SkyWord2 moves it.
/// /withdraw (SkyWord2.withdrawNft) unlocks it and sends it to the player's main wallet, where it is a
/// normal NFT. /deposit-nft (SkyWord2.depositNft) locks it again and puts it back in the bag.
contract Aotv2 {
    string public constant name = "Voidstep Blade";
    string public constant symbol = "VOID";
    uint256 public nextId = 1;
    address public hold;
    address public admin;

    mapping(uint256 => address) public ownerOf;
    mapping(address => uint256) public balanceOf;
    mapping(uint256 => bool) public locked;
    mapping(uint256 => address) private approved;
    mapping(address => mapping(address => bool)) private operators;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);
    event Approval(address indexed owner, address indexed approved, uint256 indexed id);
    event ApprovalForAll(address indexed owner, address indexed operator, bool approved);
    event Locked(uint256 indexed id, bool locked);

    constructor() {
        admin = msg.sender;
    }

    /// Admin import of an existing blade with the same serial (before setHold).
    function migrateMint(address to, uint256 id) external {
        require(msg.sender == admin && hold == address(0) && to != address(0) && ownerOf[id] == address(0) && id != 0, "migrate");
        ownerOf[id] = to;
        balanceOf[to] += 1;
        locked[id] = true;
        if (id >= nextId) nextId = id + 1;
        emit Transfer(address(0), to, id);
    }

    function setHold(address next) external {
        require(msg.sender == admin && hold == address(0) && next != address(0), "hold");
        hold = next;
        admin = address(0);
    }

    function mint(address to) external returns (uint256 id) {
        require(msg.sender == hold && to != address(0), "hold");
        id = nextId++;
        ownerOf[id] = to;
        balanceOf[to] += 1;
        locked[id] = true;
        emit Transfer(address(0), to, id);
    }

    function setLocked(uint256 id, bool on) external {
        require(msg.sender == hold && ownerOf[id] != address(0), "hold");
        locked[id] = on;
        emit Locked(id, on);
    }

    function approve(address to, uint256 id) external {
        address owner = ownerOf[id];
        require(owner == msg.sender || operators[owner][msg.sender], "owner");
        approved[id] = to;
        emit Approval(owner, to, id);
    }

    function getApproved(uint256 id) external view returns (address) {
        return approved[id];
    }

    function setApprovalForAll(address operator, bool ok) external {
        operators[msg.sender][operator] = ok;
        emit ApprovalForAll(msg.sender, operator, ok);
    }

    function isApprovedForAll(address owner, address operator) external view returns (bool) {
        return operators[owner][operator];
    }

    function transferFrom(address from, address to, uint256 id) public {
        address owner = ownerOf[id];
        require(owner != address(0) && owner == from && to != address(0), "token");
        if (msg.sender != hold) {
            require(!locked[id], "in game");
            require(msg.sender == owner || msg.sender == approved[id] || operators[owner][msg.sender], "owner");
        }
        balanceOf[from] -= 1;
        balanceOf[to] += 1;
        ownerOf[id] = to;
        delete approved[id];
        emit Transfer(from, to, id);
    }

    function safeTransferFrom(address from, address to, uint256 id) external {
        transferFrom(from, to, id);
    }

    function safeTransferFrom(address from, address to, uint256 id, bytes calldata) external {
        transferFrom(from, to, id);
    }

    function supportsInterface(bytes4 id) external pure returns (bool) {
        return id == 0x80ac58cd || id == 0x5b5e139f || id == 0x01ffc9a7;
    }

    function tokenURI(uint256 id) external view returns (string memory) {
        require(ownerOf[id] != address(0), "token");
        return "data:application/json,{\"name\":\"Voidstep Blade\",\"description\":\"A Catharsis SkyBlock sword on Somnia.\",\"image\":\"https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@1.21.4/assets/minecraft/textures/item/netherite_sword.png\"}";
    }
}

/// Bag word: bits 0-31 item, 32-47 count, 64-223 collection, 224-255 serial.
/// Combat word: bits 0-31 mana, 32-95 stamp, 96-127 hp, 128-191 speedUntil.
/// Hardened: bag writes are server-attested once the key is exported (mode B); craft / withdrawNft /
/// depositNft are attested in both modes; slot 8 (hotbar 9, the SkyBlock Menu star) can never hold an item.
contract SkyWord2 is Attested {
    Aotv2 public immutable aotv;
    uint256 public immutable cobble;
    uint256 public constant MAX_MANA = 100;
    uint256 public constant REGEN = 2;
    uint256 public constant MANA_COST = 45;
    uint256 public constant BAG = 41;
    uint8 public constant STAR = 8;

    mapping(address => mapping(uint8 => uint256)) public bag;
    mapping(address => uint256) public combat;
    mapping(address => mapping(uint256 => uint8)) placed;

    event Forged(address indexed player, uint256 indexed serial, uint8 slot);
    event Cast(address indexed player, uint256 indexed serial);
    event Withdrawn(address indexed player, uint256 indexed serial, address to);
    event Deposited(address indexed player, uint256 indexed serial, uint8 slot);

    error StarSlot();

    constructor(address collection, uint256 cobbleId, address signer_, address keys_) Attested(signer_, keys_) {
        aotv = Aotv2(collection);
        cobble = cobbleId;
    }

    function wordsOf(address player) external view returns (uint256[] memory words) {
        words = new uint256[](BAG);
        for (uint256 i = 0; i < BAG; i++) {
            words[i] = bag[player][uint8(i)];
        }
    }

    function manaOf(address player) public view returns (uint256) {
        uint256 packedWord = combat[player];
        if (packedWord == 0) {
            return MAX_MANA;
        }
        uint256 stored = uint32(packedWord);
        uint256 stamp = uint64(packedWord >> 32);
        uint256 grown = stored + (block.timestamp - stamp) * REGEN;
        if (grown > MAX_MANA) {
            return MAX_MANA;
        }
        return grown;
    }

    function write(uint8[] calldata slots, uint256[] calldata words) external guarded {
        uint256 count = slots.length;
        require(count == words.length && count > 0 && count <= 8);
        for (uint256 i = 0; i < count; i++) {
            require(slots[i] < BAG);
            if (slots[i] == STAR && words[i] != 0) revert StarSlot();
            for (uint256 j = 0; j < i; j++) {
                require(slots[i] != slots[j]);
            }
            _clear(msg.sender, bag[msg.sender][slots[i]], slots[i]);
            bag[msg.sender][slots[i]] = 0;
        }
        for (uint256 i = 0; i < count; i++) {
            _put(msg.sender, slots[i], words[i]);
        }
    }

    function craft(uint8 fromSlot, uint8 toSlot) external attested {
        require(fromSlot < BAG && toSlot < BAG);
        if (toSlot == STAR) revert StarSlot();
        uint256 from = bag[msg.sender][fromSlot];
        require(address(uint160(from >> 64)) == address(0));
        require(uint32(from) == uint32(cobble));
        uint256 count = uint16(from >> 32);
        require(count >= 1);
        count -= 1;
        uint256 left = count == 0 ? 0 : (uint256(uint32(cobble)) | (count << 32));
        if (toSlot == fromSlot) {
            require(left == 0);
        } else {
            require(bag[msg.sender][toSlot] == 0);
            bag[msg.sender][fromSlot] = left;
        }
        uint256 serial = aotv.mint(msg.sender);
        uint256 forged = (uint256(1) << 32) | (uint256(uint160(address(aotv))) << 64) | (serial << 224);
        _clear(msg.sender, bag[msg.sender][toSlot], toSlot);
        _put(msg.sender, toSlot, forged);
        emit Forged(msg.sender, serial, toSlot);
    }

    /// Mana spend only; it cannot create or move items, so it needs no attestation.
    function cast(uint8 handSlot) external {
        require(handSlot < BAG);
        uint256 word = bag[msg.sender][handSlot];
        require(address(uint160(word >> 64)) == address(aotv));
        uint256 serial = uint32(word >> 224);
        require(aotv.ownerOf(serial) == msg.sender);
        _spend(MANA_COST);
        emit Cast(msg.sender, serial);
    }

    /// /withdraw: the blade leaves the bag and goes to the player's main wallet as a free NFT.
    function withdrawNft(uint8 slot, address to) external attested {
        require(slot < BAG && to != address(0));
        uint256 word = bag[msg.sender][slot];
        require(address(uint160(word >> 64)) == address(aotv) && uint16(word >> 32) == 1);
        uint256 serial = uint32(word >> 224);
        require(aotv.ownerOf(serial) == msg.sender && aotv.locked(serial));
        _clear(msg.sender, word, slot);
        bag[msg.sender][slot] = 0;
        aotv.setLocked(serial, false);
        aotv.transferFrom(msg.sender, to, serial);
        emit Withdrawn(msg.sender, serial, to);
    }

    /// /deposit-nft: a blade the game wallet received back goes into an empty bag slot and is locked again.
    function depositNft(uint8 slot, uint256 serial) external attested {
        require(slot < BAG && bag[msg.sender][slot] == 0);
        if (slot == STAR) revert StarSlot();
        require(aotv.ownerOf(serial) == msg.sender && !aotv.locked(serial));
        aotv.setLocked(serial, true);
        _put(msg.sender, slot, (uint256(1) << 32) | (uint256(uint160(address(aotv))) << 64) | (serial << 224));
        emit Deposited(msg.sender, serial, slot);
    }

    /// One-time import of a player's SkyWord bag and combat word (admin, before closeMigration).
    function migrate(address player, uint8[] calldata slots, uint256[] calldata words, uint256 combatWord) external migration {
        require(slots.length == words.length);
        for (uint256 i = 0; i < slots.length; i++) {
            require(slots[i] < BAG);
            if (slots[i] == STAR && words[i] != 0) revert StarSlot();
            _clear(player, bag[player][slots[i]], slots[i]);
            _put(player, slots[i], words[i]);
        }
        combat[player] = combatWord;
    }

    function _put(address player, uint8 slot, uint256 word) internal {
        if (word == 0) {
            bag[player][slot] = 0;
            return;
        }
        address collection = address(uint160(word >> 64));
        uint256 count = uint16(word >> 32);
        if (collection == address(0)) {
            require(uint32(word) != 0 && count > 0 && count <= 64);
        } else {
            require(collection == address(aotv) && count == 1);
            uint256 serial = uint32(word >> 224);
            require(serial != 0 && aotv.ownerOf(serial) == player && aotv.locked(serial));
            uint8 mark = placed[player][serial];
            require(mark == 0 || mark == slot + 1);
            placed[player][serial] = slot + 1;
        }
        bag[player][slot] = word;
    }

    function _clear(address player, uint256 previous, uint8 slot) internal {
        if (address(uint160(previous >> 64)) != address(aotv)) {
            return;
        }
        uint256 serial = uint32(previous >> 224);
        if (placed[player][serial] == slot + 1) {
            placed[player][serial] = 0;
        }
    }

    function _spend(uint256 cost) internal {
        uint256 have = manaOf(msg.sender);
        require(have >= cost);
        uint256 packedWord = combat[msg.sender];
        uint256 hp = uint32(packedWord >> 96);
        if (hp == 0) {
            hp = 100;
        }
        uint256 left = have - cost;
        uint256 speedUntil = block.timestamp + 3;
        combat[msg.sender] = left | (block.timestamp << 32) | (hp << 96) | (speedUntil << 128);
    }
}
