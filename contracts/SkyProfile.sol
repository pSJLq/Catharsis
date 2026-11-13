// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "./Attested.sol";

interface ISkyIslandDeeds {
    function memberIsland(address player) external view returns (uint256);
    function holderOf(uint256 id) external view returns (address);
    function coopOf(uint256 id) external view returns (address owner, address[] memory members);
}

/// Catharsis profiles (Somnia Shannon 50312). A profile is an island deed of SkyIsland4: progress lives on the
/// deed, not on the wallet, like a SkyBlock profile.
///
/// - Collections are shared by everyone on the island: one counter per item per profile (the co-op total).
/// - Skill XP and task SkyBlock XP (quest steps, areas found) are kept per member on that profile.
/// - SkyBlock XP and the SkyBlock Level of a member are computed here from that data:
///   skill levels (5 XP for levels 1-10, 10 for 11-25, 20 for 26-50, 30 for 51-60), 4 XP per collection tier of
///   the shared collections, plus the recorded task XP. Level = XP / 100.
///
/// Only a member of the island (its owner or a co-op member, SkyIsland4.memberIsland) can write to it, from their
/// own wallet, paying their own gas. A member who leaves keeps their record on the old profile and starts from zero
/// on the next island. A profile is wiped by the deed holder (resetProfile, only while solo) or for one member
/// (resetSelf). A deed that changes hands (sold, sent, burned) starts a new, empty profile era by itself.
contract SkyProfile is Attested {
    uint256 public constant VERSION = 1;
    uint256 public constant SKILLS = 9; // 0 mining, 1 foraging, 2 farming, 3 combat, 4 fishing, 5 enchanting, 6 alchemy, 7 taming, 8 carpentry
    uint256 public constant MAX_ENTRIES = 64;
    uint32 public constant MAX_AMOUNT = 1_000_000;
    uint32 public constant MAX_TASK = 10_000;
    uint16 public constant MAX_COLLECTION_ID = 1023;
    uint256 public constant MAX_LEVEL = 60;
    /// Sum of all XP, collection and task amounts one wallet can add in one rolling hour window.
    uint256 public constant HOURLY_CAP = 20_000_000;

    ISkyIslandDeeds public immutable deeds;
    /// Highest skill level counted (the game's current cap). Admin can raise it up to 60.
    uint8 public skillCap = 25;
    /// Collection ids 0..collectionCount-1 count toward SkyBlock XP.
    uint16 public collectionCount = 29;

    struct Era {
        address holder;
        uint32 epoch;
    }

    mapping(uint256 => Era) private eras;
    mapping(uint256 => mapping(address => uint32)) public memberEpoch;
    /// island => epoch => word => 4 packed uint64 collection counters.
    mapping(uint256 => mapping(uint32 => mapping(uint256 => uint256))) private cols;
    /// record key => 3 words: 4 packed uint64 skills each; word 2 = skill 8 | task XP (bits 64-127).
    mapping(bytes32 => uint256[3]) private recs;
    /// bits 0-63 window start, 64-127 amount used in the window.
    mapping(address => uint256) public window;

    event Xp(uint256 indexed island, address indexed player, uint8 indexed skill, uint32 amount, uint64 total);
    event Collected(uint256 indexed island, address indexed player, uint16 indexed id, uint32 amount, uint64 total);
    event Task(uint256 indexed island, address indexed player, uint32 amount, uint64 total);
    event Batch(uint256 indexed island, address indexed player, uint256 entries);
    event NewEra(uint256 indexed island, uint32 indexed epoch, address indexed holder);
    event ProfileReset(uint256 indexed island, uint32 indexed epoch, address indexed by);
    event MemberReset(uint256 indexed island, address indexed member, uint32 epoch);
    event SkillCap(uint8 cap);
    event CollectionCount(uint16 count);

    error Mismatch();
    error TooMany();
    error BadSkill();
    error BadCollection();
    error OverCap();
    error NotMember();
    error NotHolder();
    error HasMembers();
    error BadValue();

    constructor(address signer_, address keys_, address deeds_) Attested(signer_, keys_) {
        require(deeds_ != address(0), "deeds");
        deeds = ISkyIslandDeeds(deeds_);
        // Nothing is imported from SkySkills3: every profile starts from zero.
        migrating = false;
        emit MigrationClosed();
    }

    // ------------------------------------------------------------------ writes

    /// Adds the caller's skill XP, the caller's pickups to the island's shared collections and task SkyBlock XP.
    /// `island` must be the caller's island right now (owner or co-op member).
    function applyXp(
        uint256 island,
        uint8[] calldata skill,
        uint32[] calldata amount,
        uint16[] calldata collectionId,
        uint32[] calldata collectionAmt,
        uint32 task
    ) external guarded {
        uint256 ns = skill.length;
        uint256 nc = collectionId.length;
        if (ns != amount.length || nc != collectionAmt.length) revert Mismatch();
        if (ns + nc == 0 && task == 0) revert TooMany();
        if (ns + nc > MAX_ENTRIES) revert TooMany();
        if (task > MAX_TASK) revert OverCap();
        if (island == 0 || deeds.memberIsland(msg.sender) != island) revert NotMember();
        uint32 e = _era(island);
        uint256 sum = task;
        if (ns != 0 || task != 0) {
            uint256[3] storage r = recs[_rec(island, e, msg.sender)];
            uint256[3] memory w = r;
            for (uint256 i = 0; i < ns; i++) {
                uint8 s = skill[i];
                uint32 a = amount[i];
                if (s >= SKILLS) revert BadSkill();
                if (a > MAX_AMOUNT) revert OverCap();
                sum += a;
                uint256 shift = (uint256(s) & 3) * 64;
                uint64 total = uint64(w[s >> 2] >> shift) + a;
                w[s >> 2] = (w[s >> 2] & ~(uint256(type(uint64).max) << shift)) | (uint256(total) << shift);
                emit Xp(island, msg.sender, s, a, total);
            }
            if (task != 0) {
                uint64 t = uint64(w[2] >> 64) + task;
                w[2] = (w[2] & ~(uint256(type(uint64).max) << 64)) | (uint256(t) << 64);
                emit Task(island, msg.sender, task, t);
            }
            for (uint256 k = 0; k < 3; k++) {
                if (r[k] != w[k]) r[k] = w[k];
            }
        }
        if (nc != 0) {
            mapping(uint256 => uint256) storage words = cols[island][e];
            for (uint256 i = 0; i < nc; i++) {
                uint16 id = collectionId[i];
                uint32 a = collectionAmt[i];
                if (id > MAX_COLLECTION_ID) revert BadCollection();
                if (a > MAX_AMOUNT) revert OverCap();
                sum += a;
                uint256 wi = uint256(id) >> 2;
                uint256 shift = (uint256(id) & 3) * 64;
                uint256 word = words[wi];
                uint64 total = uint64(word >> shift) + a;
                words[wi] = (word & ~(uint256(type(uint64).max) << shift)) | (uint256(total) << shift);
                emit Collected(island, msg.sender, id, a, total);
            }
        }
        _spend(sum);
        emit Batch(island, msg.sender, ns + nc + (task != 0 ? 1 : 0));
    }

    /// Profile delete: the deed holder wipes the whole profile (collections and every member's record).
    /// Only while the island has no co-op members. Lowers state only, so no attestation is needed.
    function resetProfile(uint256 island) external returns (uint32 e) {
        if (island == 0 || deeds.holderOf(island) != msg.sender || deeds.memberIsland(msg.sender) != island) revert NotHolder();
        (, address[] memory members) = deeds.coopOf(island);
        if (members.length != 0) revert HasMembers();
        e = epochOf(island) + 1;
        eras[island] = Era(msg.sender, e);
        emit ProfileReset(island, e, msg.sender);
    }

    /// Wipes only the caller's own record (skills and task XP) on their island; the shared collections stay.
    function resetSelf(uint256 island) external returns (uint32 e) {
        if (island == 0 || deeds.memberIsland(msg.sender) != island) revert NotMember();
        e = memberEpoch[island][msg.sender] + 1;
        memberEpoch[island][msg.sender] = e;
        emit MemberReset(island, msg.sender, e);
    }

    function setSkillCap(uint8 cap) external onlyAdmin {
        if (cap == 0 || cap > MAX_LEVEL || cap < skillCap) revert BadValue();
        skillCap = cap;
        emit SkillCap(cap);
    }

    function setCollectionCount(uint16 count) external onlyAdmin {
        if (count > uint16(MAX_COLLECTION_ID) + 1 || count < collectionCount) revert BadValue();
        collectionCount = count;
        emit CollectionCount(count);
    }

    // ------------------------------------------------------------------ views

    /// The live era of a profile: a new holder (sale, transfer, burn) means a new, empty era.
    function epochOf(uint256 island) public view returns (uint32) {
        Era memory era = eras[island];
        return era.holder == deeds.holderOf(island) ? era.epoch : era.epoch + 1;
    }

    function collection(uint256 island, uint16 id) public view returns (uint64) {
        return _col(island, epochOf(island), id);
    }

    function collectionsOf(uint256 island, uint16[] calldata ids) external view returns (uint64[] memory out) {
        uint32 e = epochOf(island);
        out = new uint64[](ids.length);
        for (uint256 i = 0; i < ids.length; i++) {
            out[i] = _col(island, e, ids[i]);
        }
    }

    /// A member's skill XP on a profile (skill ids as above).
    function skillsOf(uint256 island, address member) public view returns (uint64[9] memory out) {
        uint256[3] memory w = recs[_rec(island, epochOf(island), member)];
        for (uint256 s = 0; s < SKILLS; s++) {
            out[s] = uint64(w[s >> 2] >> ((s & 3) * 64));
        }
    }

    function taskXpOf(uint256 island, address member) public view returns (uint64) {
        return uint64(recs[_rec(island, epochOf(island), member)][2] >> 64);
    }

    /// Everything the game reads at login in one call.
    function profileOf(uint256 island, address member, uint16 count)
        external
        view
        returns (uint32 epoch, uint64[9] memory skills, uint64 task, uint64[] memory collections, uint256 sbXp)
    {
        epoch = epochOf(island);
        skills = skillsOf(island, member);
        task = taskXpOf(island, member);
        collections = new uint64[](count);
        for (uint256 i = 0; i < count; i++) {
            collections[i] = _col(island, epoch, uint16(i));
        }
        sbXp = sbXpOf(island, member);
    }

    /// SkyBlock XP of a member on a profile: skill levels + 4 per shared collection tier + task XP.
    function sbXpOf(uint256 island, address member) public view returns (uint256 total) {
        uint32 e = epochOf(island);
        uint256[3] memory w = recs[_rec(island, e, member)];
        uint256 cap = skillCap;
        for (uint256 s = 0; s < SKILLS; s++) {
            total += skillSbXp(skillLevel(uint64(w[s >> 2] >> ((s & 3) * 64)), cap));
        }
        uint256 n = collectionCount;
        for (uint256 i = 0; i < n; i++) {
            total += 4 * collectionTier(_col(island, e, uint16(i)));
        }
        total += uint64(w[2] >> 64);
    }

    function levelOf(uint256 island, address member) external view returns (uint256) {
        return sbXpOf(island, member) / 100;
    }

    /// SkyBlock Level of a wallet on the island it plays on now.
    function levelOfPlayer(address player) external view returns (uint256) {
        uint256 island = deeds.memberIsland(player);
        return island == 0 ? 0 : sbXpOf(island, player) / 100;
    }

    /// Wiki skill table (cumulative XP to reach each level), capped at `cap`.
    function skillLevel(uint64 xp, uint256 cap) public pure returns (uint256 level) {
        uint256[61] memory t = [
            uint256(0), 50, 175, 375, 675, 1175, 1925, 2925, 4425, 6425,
            9925, 14925, 22425, 32425, 47425, 67425, 97425, 147425, 222425, 322425,
            522425, 822425, 1222425, 1722425, 2322425, 3022425, 3822425, 4722425, 5722425, 6822425,
            8022425, 9322425, 10722425, 12222425, 13822425, 15522425, 17322425, 19222425, 21222425, 23322425,
            25522425, 27822425, 30222425, 32722425, 35322425, 38072425, 40972425, 44072425, 47472425, 51172425,
            55172425, 59472425, 64072425, 68972425, 74172425, 79672425, 85472425, 91572425, 97972425, 104672425,
            111672425
        ];
        if (cap > MAX_LEVEL) cap = MAX_LEVEL;
        while (level < cap && xp >= t[level + 1]) level++;
    }

    /// SkyBlock XP for all skill levels up to `level`: 5 each for 1-10, 10 for 11-25, 20 for 26-50, 30 for 51-60.
    function skillSbXp(uint256 level) public pure returns (uint256 x) {
        for (uint256 l = 1; l <= level; l++) {
            x += l <= 10 ? 5 : l <= 25 ? 10 : l <= 50 ? 20 : 30;
        }
    }

    /// Collection tiers at 50, 100, 250, 500, 1000, 2500.
    function collectionTier(uint64 amount) public pure returns (uint256) {
        if (amount >= 2500) return 6;
        if (amount >= 1000) return 5;
        if (amount >= 500) return 4;
        if (amount >= 250) return 3;
        if (amount >= 100) return 2;
        if (amount >= 50) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ internals

    function _era(uint256 island) private returns (uint32) {
        Era memory era = eras[island];
        address holder = deeds.holderOf(island);
        if (era.holder == holder) return era.epoch;
        uint32 e = era.epoch + 1;
        eras[island] = Era(holder, e);
        emit NewEra(island, e, holder);
        return e;
    }

    function _rec(uint256 island, uint32 e, address member) private view returns (bytes32) {
        return keccak256(abi.encode(island, e, member, memberEpoch[island][member]));
    }

    function _col(uint256 island, uint32 e, uint16 id) private view returns (uint64) {
        return uint64(cols[island][e][uint256(id) >> 2] >> ((uint256(id) & 3) * 64));
    }

    function _spend(uint256 sum) private {
        uint256 w = window[msg.sender];
        uint256 start = uint64(w);
        uint256 usedNow = uint64(w >> 64);
        if (block.timestamp >= start + 1 hours) {
            start = block.timestamp;
            usedNow = 0;
        }
        usedNow += sum;
        if (usedNow > HOURLY_CAP) revert OverCap();
        window[msg.sender] = start | (usedNow << 64);
    }
}
