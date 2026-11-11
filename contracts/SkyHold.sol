// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

interface ISkyIsland {
    function memberIsland(address player) external view returns (uint256);
}

interface ISkyGear {
    function mint(
        address to,
        uint32 item,
        uint16 damage,
        string calldata key,
        string calldata label,
        string calldata reforge,
        string calldata enchants
    ) external returns (uint256);

    function update(uint256 id, uint16 damage, string calldata reforge, string calldata enchants) external;

    function burn(uint256 id) external;

    function ownerOf(uint256 id) external view returns (address);
}

/// Items the player is holding now. Stacks stay as counts.
/// A tool, weapon, or armor piece is a SkyGear token id in the slot.
contract SkyHold {
    uint8 public constant BAG = 41;
    uint8 public constant CHEST = 54;
    uint16 public constant STACK = 64;
    uint256 public constant MINT = type(uint256).max;

    struct Slot {
        uint32 item;
        uint16 count;
        uint16 damage;
        uint256 gear;
    }

    ISkyIsland public immutable isles;
    ISkyGear public immutable gears;

    mapping(address => mapping(uint8 => Slot)) private bags;
    mapping(uint256 => mapping(uint8 => Slot)) private chests;
    mapping(address => mapping(uint32 => uint256)) public balanceOf;
    mapping(uint256 => mapping(uint32 => uint256)) public chestBalance;

    event Bag(address indexed player, uint8 slot, uint32 item, uint16 count, uint16 damage, uint256 gear);
    event Chest(uint256 indexed chestId, uint8 slot, uint32 item, uint16 count, uint16 damage, uint256 gear);
    event Transfer(address indexed from, address indexed to, uint32 indexed item, uint256 amount);

    error Length();
    error Range();
    error Empty();
    error NoIsland();
    error Short();
    error Gear();

    constructor(address isles_, address gears_) {
        isles = ISkyIsland(isles_);
        gears = ISkyGear(gears_);
    }

    function sync(
        uint8[] calldata bagSlots,
        uint32[] calldata bagItems,
        uint16[] calldata bagCounts,
        uint16[] calldata bagDamages,
        uint256[] calldata bagGear,
        string[] calldata bagKeys,
        string[] calldata bagLabels,
        string[] calldata bagReforges,
        string[] calldata bagEnchants,
        uint256 chestId,
        uint8[] calldata chestSlots,
        uint32[] calldata chestItems,
        uint16[] calldata chestCounts,
        uint16[] calldata chestDamages,
        uint256[] calldata chestGear,
        string[] calldata chestKeys,
        string[] calldata chestLabels,
        string[] calldata chestReforges,
        string[] calldata chestEnchants
    ) external {
        if (chestId != 0 && isles.memberIsland(msg.sender) == 0) revert NoIsland();
        uint256[] memory oldBag = _write(
            bags[msg.sender],
            balanceOf[msg.sender],
            BAG,
            0,
            bagSlots,
            bagItems,
            bagCounts,
            bagDamages,
            bagGear,
            bagKeys,
            bagLabels,
            bagReforges,
            bagEnchants
        );
        uint256[] memory oldChest;
        if (chestId != 0) {
            oldChest = _write(
                chests[chestId],
                chestBalance[chestId],
                CHEST,
                chestId,
                chestSlots,
                chestItems,
                chestCounts,
                chestDamages,
                chestGear,
                chestKeys,
                chestLabels,
                chestReforges,
                chestEnchants
            );
        }
        _unique(msg.sender, chestId);
        _burnLost(msg.sender, chestId, oldBag);
        if (oldChest.length != 0) _burnLost(msg.sender, chestId, oldChest);
    }

    function transfer(address to, uint32 item, uint256 amount) external {
        if (to == address(0) || to == msg.sender || item == 0 || amount == 0) revert Empty();
        uint16 damage = _pull(msg.sender, item, amount);
        _push(to, item, amount, damage);
        balanceOf[msg.sender][item] -= amount;
        balanceOf[to][item] += amount;
        emit Transfer(msg.sender, to, item, amount);
    }

    function bagOf(address player)
        external
        view
        returns (uint32[] memory items, uint16[] memory counts, uint16[] memory damages, uint256[] memory gear)
    {
        return _read(bags[player], BAG);
    }

    function chestOf(uint256 chestId)
        external
        view
        returns (uint32[] memory items, uint16[] memory counts, uint16[] memory damages, uint256[] memory gear)
    {
        return _read(chests[chestId], CHEST);
    }

    function _read(mapping(uint8 => Slot) storage slots, uint8 size)
        internal
        view
        returns (uint32[] memory items, uint16[] memory counts, uint16[] memory damages, uint256[] memory gear)
    {
        items = new uint32[](size);
        counts = new uint16[](size);
        damages = new uint16[](size);
        gear = new uint256[](size);
        for (uint8 i = 0; i < size; ) {
            Slot storage slot = slots[i];
            items[i] = slot.item;
            counts[i] = slot.count;
            damages[i] = slot.damage;
            gear[i] = slot.gear;
            unchecked {
                i++;
            }
        }
    }

    function _write(
        mapping(uint8 => Slot) storage slots,
        mapping(uint32 => uint256) storage bal,
        uint8 limit,
        uint256 chestId,
        uint8[] calldata indexes,
        uint32[] calldata items,
        uint16[] calldata counts,
        uint16[] calldata damages,
        uint256[] calldata gearIds,
        string[] calldata keys,
        string[] calldata labels,
        string[] calldata reforges,
        string[] calldata enchants
    ) internal returns (uint256[] memory olds) {
        uint256 n = indexes.length;
        if (
            n != items.length || n != counts.length || n != damages.length || n != gearIds.length || n != keys.length
                || n != labels.length || n != reforges.length || n != enchants.length || n > limit
        ) revert Length();
        olds = new uint256[](n);
        for (uint256 i = 0; i < n; i++) {
            uint8 index = indexes[i];
            if (index >= limit) revert Range();
            Slot storage prev = slots[index];
            olds[i] = prev.gear;
            Slot memory next = _clean(items[i], counts[i], damages[i]);
            uint256 gear = gearIds[i];
            if (next.count == 0) {
                gear = 0;
            } else if (gear == MINT) {
                if (next.count != 1) revert Gear();
                gear = gears.mint(msg.sender, next.item, next.damage, keys[i], labels[i], reforges[i], enchants[i]);
            } else if (gear != 0) {
                if (next.count != 1 || gears.ownerOf(gear) != msg.sender) revert Gear();
                gears.update(gear, next.damage, reforges[i], enchants[i]);
            }
            _delta(bal, prev, next);
            prev.item = next.item;
            prev.count = next.count;
            prev.damage = next.damage;
            prev.gear = gear;
            if (chestId == 0) {
                emit Bag(msg.sender, index, next.item, next.count, next.damage, gear);
            } else {
                emit Chest(chestId, index, next.item, next.count, next.damage, gear);
            }
        }
    }

    function _unique(address player, uint256 chestId) internal view {
        uint256[96] memory seen;
        uint256 n;
        n = _collect(bags[player], BAG, seen, n);
        if (chestId != 0) n = _collect(chests[chestId], CHEST, seen, n);
        for (uint256 i = 0; i < n; i++) {
            for (uint256 j = i + 1; j < n; j++) {
                if (seen[i] == seen[j]) revert Gear();
            }
        }
    }

    function _collect(mapping(uint8 => Slot) storage slots, uint8 limit, uint256[96] memory seen, uint256 n)
        internal
        view
        returns (uint256)
    {
        for (uint8 i = 0; i < limit; i++) {
            uint256 gear = slots[i].gear;
            if (gear != 0) {
                if (n == seen.length) revert Gear();
                seen[n++] = gear;
            }
        }
        return n;
    }

    function _burnLost(address player, uint256 chestId, uint256[] memory olds) internal {
        for (uint256 i = 0; i < olds.length; i++) {
            uint256 gear = olds[i];
            if (gear != 0 && gear != MINT && !_holds(player, chestId, gear)) {
                gears.burn(gear);
            }
        }
    }

    function _holds(address player, uint256 chestId, uint256 gear) internal view returns (bool) {
        if (_in(bags[player], BAG, gear)) return true;
        if (chestId != 0 && _in(chests[chestId], CHEST, gear)) return true;
        return false;
    }

    function _in(mapping(uint8 => Slot) storage slots, uint8 limit, uint256 gear) internal view returns (bool) {
        for (uint8 i = 0; i < limit; i++) {
            if (slots[i].gear == gear) return true;
        }
        return false;
    }

    function _pull(address player, uint32 item, uint256 amount) internal returns (uint16 damage) {
        uint256 left = amount;
        for (uint8 i = 0; i < BAG && left > 0; ) {
            Slot storage slot = bags[player][i];
            if (slot.gear == 0 && slot.item == item && slot.count > 0) {
                uint256 take = slot.count > left ? left : slot.count;
                if (take == left && take == slot.count) damage = slot.damage;
                slot.count = uint16(uint256(slot.count) - take);
                if (slot.count == 0) {
                    slot.item = 0;
                    slot.damage = 0;
                }
                left -= take;
                emit Bag(player, i, slot.item, slot.count, slot.damage, 0);
            }
            unchecked {
                i++;
            }
        }
        if (left != 0) revert Short();
    }

    function _push(address to, uint32 item, uint256 amount, uint16 damage) internal {
        uint256 left = amount;
        if (damage == 0) {
            for (uint8 i = 0; i < BAG && left > 0; ) {
                Slot storage slot = bags[to][i];
                if (slot.gear == 0 && slot.item == item && slot.damage == 0 && slot.count > 0 && slot.count < STACK) {
                    uint256 room = uint256(STACK - slot.count);
                    uint256 add = room > left ? left : room;
                    slot.count = uint16(uint256(slot.count) + add);
                    left -= add;
                    emit Bag(to, i, slot.item, slot.count, slot.damage, 0);
                }
                unchecked {
                    i++;
                }
            }
        }
        for (uint8 i = 0; i < BAG && left > 0; ) {
            Slot storage slot = bags[to][i];
            if (slot.count == 0) {
                uint256 add = left > STACK ? uint256(STACK) : left;
                slot.item = item;
                slot.count = uint16(add);
                slot.damage = damage;
                slot.gear = 0;
                left -= add;
                emit Bag(to, i, item, slot.count, damage, 0);
                damage = 0;
            }
            unchecked {
                i++;
            }
        }
        if (left != 0) revert Short();
    }

    function _clean(uint32 item, uint16 count, uint16 damage) internal pure returns (Slot memory) {
        if (item == 0 || count == 0) return Slot(0, 0, 0, 0);
        return Slot(item, count, damage, 0);
    }

    function _delta(mapping(uint32 => uint256) storage bal, Slot storage prev, Slot memory next) internal {
        if (prev.item != 0 && prev.count != 0) bal[prev.item] -= prev.count;
        if (next.item != 0 && next.count != 0) bal[next.item] += next.count;
    }
}
