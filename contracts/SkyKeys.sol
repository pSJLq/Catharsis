// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// Catharsis key registry (two modes).
///
/// Mode A (default): the player's game wallet key exists only on the Paper server, sealed with the player's
/// password. Nobody else can send from that wallet, so routine writes (XP, bag layout, stats) need no server
/// attestation and cost normal gas.
///
/// Mode B: once a player's key is handed to them (/exportkey), `exported[player]` is set here first. From then
/// on every Catharsis contract requires a server attestation (Attested.sol) for every write from that wallet,
/// so the key alone can pay for transactions but cannot decide what they write.
///
/// The flag is one-way: there is no function that clears it. It can be set by the player's own wallet (the
/// server sends optIn() from the purse before showing the key), or by the admin / server signer.
contract SkyKeys {
    address public admin;
    address public signer;
    mapping(address => bool) public exported;

    event Exported(address indexed player, address indexed by);
    event SignerChanged(address indexed signer);
    event AdminChanged(address indexed admin);

    error NotAllowed();

    constructor(address signer_) {
        require(signer_ != address(0), "signer");
        admin = msg.sender;
        signer = signer_;
        emit AdminChanged(msg.sender);
        emit SignerChanged(signer_);
    }

    /// The player's own wallet switches itself to mode B (one-way).
    function optIn() external {
        _mark(msg.sender);
    }

    /// Admin or server signer switches a player to mode B (one-way).
    function markExported(address player) external {
        if (msg.sender != admin && msg.sender != signer) revert NotAllowed();
        require(player != address(0), "player");
        _mark(player);
    }

    function setSigner(address next) external {
        if (msg.sender != admin) revert NotAllowed();
        require(next != address(0), "signer");
        signer = next;
        emit SignerChanged(next);
    }

    function setAdmin(address next) external {
        if (msg.sender != admin) revert NotAllowed();
        require(next != address(0), "admin");
        admin = next;
        emit AdminChanged(next);
    }

    function _mark(address player) private {
        if (exported[player]) return;
        exported[player] = true;
        emit Exported(player, msg.sender);
    }
}
