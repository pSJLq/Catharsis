// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// One sword, one token. Enchantments and the reforge change this token.
/// The token id does not.
contract SkyGear {
    string public constant name = "SkyGear";
    string public constant symbol = "GEAR";

    address public hold;
    address public admin;
    uint256 public nextId = 1;

    mapping(uint256 => address) public ownerOf;
    mapping(address => uint256) public balanceOf;
    mapping(uint256 => address) private approved;
    mapping(address => mapping(address => bool)) private operators;

    mapping(uint256 => uint32) public itemOf;
    mapping(uint256 => uint16) public damageOf;
    mapping(uint256 => string) public keyOf;
    mapping(uint256 => string) public labelOf;
    mapping(uint256 => string) public reforgeOf;
    mapping(uint256 => string) public enchantsOf;

    event Transfer(address indexed from, address indexed to, uint256 indexed id);
    event Approval(address indexed owner, address indexed approved, uint256 indexed id);
    event ApprovalForAll(address indexed owner, address indexed operator, bool approved);
    event Traits(uint256 indexed id, uint16 damage, string reforge, string enchants);

    error Missing();
    error Forbidden();
    error Zero();

    constructor() {
        admin = msg.sender;
    }

    function setHold(address next) external {
        require(msg.sender == admin && hold == address(0) && next != address(0), "hold");
        hold = next;
        admin = address(0);
    }

    function mint(
        address to,
        uint32 item,
        uint16 damage,
        string calldata key,
        string calldata label,
        string calldata reforge,
        string calldata enchants
    ) external returns (uint256 id) {
        if (msg.sender != hold || to == address(0) || item == 0) revert Forbidden();
        id = nextId++;
        ownerOf[id] = to;
        balanceOf[to] += 1;
        itemOf[id] = item;
        damageOf[id] = damage;
        keyOf[id] = key;
        labelOf[id] = label;
        reforgeOf[id] = reforge;
        enchantsOf[id] = enchants;
        emit Transfer(address(0), to, id);
        emit Traits(id, damage, reforge, enchants);
    }

    /// Damage always updates. A trait string of "-" leaves the old text in place.
    function update(uint256 id, uint16 damage, string calldata reforge, string calldata enchants) external {
        if (msg.sender != hold || ownerOf[id] == address(0)) revert Forbidden();
        damageOf[id] = damage;
        if (!_unchanged(enchants)) {
            reforgeOf[id] = reforge;
            enchantsOf[id] = enchants;
        }
        emit Traits(id, damage, reforgeOf[id], enchantsOf[id]);
    }

    function burn(uint256 id) external {
        if (msg.sender != hold) revert Forbidden();
        address owner = ownerOf[id];
        if (owner == address(0)) revert Missing();
        balanceOf[owner] -= 1;
        delete ownerOf[id];
        delete approved[id];
        delete itemOf[id];
        delete damageOf[id];
        delete keyOf[id];
        delete labelOf[id];
        delete reforgeOf[id];
        delete enchantsOf[id];
        emit Transfer(owner, address(0), id);
    }

    function traits(uint256 id)
        external
        view
        returns (uint32 item, uint16 damage, string memory key, string memory label, string memory reforge, string memory enchants)
    {
        if (ownerOf[id] == address(0)) revert Missing();
        return (itemOf[id], damageOf[id], keyOf[id], labelOf[id], reforgeOf[id], enchantsOf[id]);
    }

    function approve(address to, uint256 id) external {
        address owner = ownerOf[id];
        if (owner != msg.sender && !operators[owner][msg.sender]) revert Forbidden();
        approved[id] = to;
        emit Approval(owner, to, id);
    }

    function getApproved(uint256 id) external view returns (address) {
        if (ownerOf[id] == address(0)) revert Missing();
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
        if (owner == address(0) || owner != from || to == address(0)) revert Zero();
        if (msg.sender != owner && msg.sender != approved[id] && !operators[owner][msg.sender] && msg.sender != hold) {
            revert Forbidden();
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
        if (ownerOf[id] == address(0)) revert Missing();
        string memory json = string.concat(
            '{"name":"',
            labelOf[id],
            '","description":"A SkyBlock item. This token id stays when its traits change.","image":"https://cdn.jsdelivr.net/gh/InventivetalentDev/minecraft-assets@1.21.4/assets/minecraft/textures/item/',
            keyOf[id],
            '.png","attributes":[{"trait_type":"Item","value":"',
            labelOf[id],
            '"},{"trait_type":"Damage","display_type":"number","value":',
            _num(damageOf[id]),
            "}",
            _extra("Reforge", reforgeOf[id]),
            _extra("Enchantments", enchantsOf[id]),
            "]}"
        );
        return string.concat("data:application/json;base64,", Base64.encode(bytes(json)));
    }

    function _extra(string memory trait, string memory value) internal pure returns (string memory) {
        if (bytes(value).length == 0) return "";
        return string.concat(',{"trait_type":"', trait, '","value":"', value, '"}');
    }

    function _unchanged(string calldata value) internal pure returns (bool) {
        return bytes(value).length == 1 && bytes(value)[0] == 0x2d;
    }

    function _num(uint256 value) internal pure returns (string memory) {
        if (value == 0) return "0";
        bytes memory buf = new bytes(8);
        uint256 i = 8;
        while (value != 0) {
            unchecked {
                i -= 1;
            }
            buf[i] = bytes1(uint8(48 + (value % 10)));
            value /= 10;
        }
        bytes memory out = new bytes(8 - i);
        for (uint256 j = 0; j < out.length; j++) {
            out[j] = buf[i + j];
        }
        return string(out);
    }
}

library Base64 {
    string internal constant TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    function encode(bytes memory data) internal pure returns (string memory) {
        if (data.length == 0) return "";
        bytes memory table = bytes(TABLE);
        uint256 encodedLen = 4 * ((data.length + 2) / 3);
        bytes memory result = new bytes(encodedLen);
        uint256 i;
        uint256 j;
        while (i + 3 <= data.length) {
            uint256 n = (uint256(uint8(data[i])) << 16) | (uint256(uint8(data[i + 1])) << 8) | uint256(uint8(data[i + 2]));
            result[j] = table[(n >> 18) & 63];
            result[j + 1] = table[(n >> 12) & 63];
            result[j + 2] = table[(n >> 6) & 63];
            result[j + 3] = table[n & 63];
            i += 3;
            j += 4;
        }
        if (i + 1 == data.length) {
            uint256 n = uint256(uint8(data[i])) << 16;
            result[j] = table[(n >> 18) & 63];
            result[j + 1] = table[(n >> 12) & 63];
            result[j + 2] = 0x3d;
            result[j + 3] = 0x3d;
        } else if (i + 2 == data.length) {
            uint256 n = (uint256(uint8(data[i])) << 16) | (uint256(uint8(data[i + 1])) << 8);
            result[j] = table[(n >> 18) & 63];
            result[j + 1] = table[(n >> 12) & 63];
            result[j + 2] = table[(n >> 6) & 63];
            result[j + 3] = 0x3d;
        }
        return string(result);
    }
}
