// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// Bag word: bits 0-31 item, 32-47 count, 64-223 collection, 224-255 serial.
/// Combat word: bits 0-31 mana, 32-95 stamp, 96-127 hp, 128-191 speedUntil.
/// A zero combat word means full mana. write() emits nothing; the receipt is the commit.

contract Aotv {
    string public constant name = "Aspect of the Void";
    string public constant symbol = "AOTV";
    uint256 public nextId = 1;
    address public hold;
    mapping(uint256 => address) public ownerOf;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);

    function setHold(address next) external {
        require(hold == address(0) && next != address(0));
        hold = next;
    }

    function mint(address to) external returns (uint256 id) {
        require(msg.sender == hold && to != address(0));
        id = nextId++;
        ownerOf[id] = to;
        emit Transfer(address(0), to, id);
    }
}

contract SkyWord {
    Aotv public immutable aotv;
    uint256 public immutable cobble;
    uint256 public constant MAX_MANA = 100;
    uint256 public constant REGEN = 2;
    uint256 public constant MANA_COST = 45;
    uint256 public constant BAG = 41;

    mapping(address => mapping(uint8 => uint256)) public bag;
    mapping(address => uint256) public combat;
    mapping(address => mapping(uint256 => uint8)) placed;

    event Forged(address indexed player, uint256 indexed serial, uint8 slot);
    event Cast(address indexed player, uint256 indexed serial);

    constructor(address collection, uint256 cobbleId) {
        aotv = Aotv(collection);
        cobble = cobbleId;
    }

    function wordsOf(address player) external view returns (uint256[] memory words) {
        words = new uint256[](BAG);
        for (uint256 i = 0; i < BAG; i++) {
            words[i] = bag[player][uint8(i)];
        }
    }

    function manaOf(address player) public view returns (uint256) {
        uint256 packed = combat[player];
        if (packed == 0) {
            return MAX_MANA;
        }
        uint256 stored = uint32(packed);
        uint256 stamp = uint64(packed >> 32);
        uint256 grown = stored + (block.timestamp - stamp) * REGEN;
        if (grown > MAX_MANA) {
            return MAX_MANA;
        }
        return grown;
    }

    function write(uint8[] calldata slots, uint256[] calldata words) external {
        uint256 count = slots.length;
        require(count == words.length && count > 0 && count <= 8);
        for (uint256 i = 0; i < count; i++) {
            require(slots[i] < BAG);
            for (uint256 j = 0; j < i; j++) {
                require(slots[i] != slots[j]);
            }
            _clear(bag[msg.sender][slots[i]], slots[i]);
            bag[msg.sender][slots[i]] = 0;
        }
        for (uint256 i = 0; i < count; i++) {
            _put(slots[i], words[i]);
        }
    }

    function craft(uint8 fromSlot, uint8 toSlot) external {
        require(fromSlot < BAG && toSlot < BAG);
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
        _store(toSlot, forged);
        emit Forged(msg.sender, serial, toSlot);
    }

    function cast(uint8 handSlot) external {
        require(handSlot < BAG);
        uint256 word = bag[msg.sender][handSlot];
        require(address(uint160(word >> 64)) == address(aotv));
        uint256 serial = uint32(word >> 224);
        require(aotv.ownerOf(serial) == msg.sender);
        _spend(MANA_COST);
        emit Cast(msg.sender, serial);
    }

    function _store(uint8 slot, uint256 word) internal {
        _clear(bag[msg.sender][slot], slot);
        _put(slot, word);
    }

    function _put(uint8 slot, uint256 word) internal {
        if (word == 0) {
            bag[msg.sender][slot] = 0;
            return;
        }
        address collection = address(uint160(word >> 64));
        uint256 count = uint16(word >> 32);
        if (collection == address(0)) {
            require(uint32(word) != 0 && count > 0 && count <= 64);
        } else {
            require(collection == address(aotv) && count == 1);
            uint256 serial = uint32(word >> 224);
            require(serial != 0 && aotv.ownerOf(serial) == msg.sender);
            uint8 mark = placed[msg.sender][serial];
            require(mark == 0 || mark == slot + 1);
            placed[msg.sender][serial] = slot + 1;
        }
        bag[msg.sender][slot] = word;
    }

    function _clear(uint256 previous, uint8 slot) internal {
        if (address(uint160(previous >> 64)) != address(aotv)) {
            return;
        }
        uint256 serial = uint32(previous >> 224);
        if (placed[msg.sender][serial] == slot + 1) {
            placed[msg.sender][serial] = 0;
        }
    }

    function _spend(uint256 cost) internal {
        uint256 have = manaOf(msg.sender);
        require(have >= cost);
        uint256 packed = combat[msg.sender];
        uint256 hp = uint32(packed >> 96);
        if (hp == 0) {
            hp = 100;
        }
        uint256 left = have - cost;
        uint256 speedUntil = block.timestamp + 3;
        combat[msg.sender] = left | (block.timestamp << 32) | (hp << 96) | (speedUntil << 128);
    }
}
