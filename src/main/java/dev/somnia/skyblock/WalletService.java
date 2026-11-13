package dev.somnia.skyblock;

import org.bukkit.configuration.file.YamlConfiguration;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Convert;
import org.web3j.utils.Numeric;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class WalletService {
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(21_000L);
    private static final BigInteger GAS_CAP = BigInteger.valueOf(3_000_000L);

    private final SomniaSkyblockPlugin plugin;
    private final Web3j web3;
    private final RpcFailover rpc;
    private static final BigInteger GWEI = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger PRIORITY = GWEI;
    /** Somnia's base fee has been 6 gwei; never sign below it. */
    private static final BigInteger FEE_FLOOR = BigInteger.valueOf(6L).multiply(GWEI);
    private volatile BigInteger gasPriceCache;
    private volatile long gasPriceAt;
    private final long chainId;
    private final File secretsFile;
    private final String deployerAddress;
    private final Attestor attestor;
    private final Object sendLock = new Object();
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Map<String, BigInteger> castGas = new ConcurrentHashMap<>();
    /** Last Shannon purse balances, keyed by lower-case address. Filled on every Shannon balance read. */
    private final Map<String, BigInteger> balanceCache = new ConcurrentHashMap<>();
    /** Last computed Shannon gas fee cap (estimate * maxFee) from a write attempt. */
    private volatile BigInteger lastFeeWei;
    /** Conservative gas units used only when no write has computed a fee yet (main-thread, no RPC). */
    private static final BigInteger DEFAULT_GAS_UNITS = BigInteger.valueOf(450_000L);

    WalletService(SomniaSkyblockPlugin plugin) {
        this.plugin = plugin;
        String rpcUrl = plugin.getConfig().getString("chain.rpc", "https://api.infra.testnet.somnia.network");
        this.chainId = plugin.getConfig().getLong("chain.chain-id", 50312L);
        List<String> endpoints = new ArrayList<>();
        endpoints.add(rpcUrl);
        for (String extra : plugin.getConfig().getStringList("chain.rpc-fallbacks")) {
            if (extra != null && !extra.isBlank() && !endpoints.contains(extra.trim())) {
                endpoints.add(extra.trim());
            }
        }
        this.rpc = new RpcFailover(endpoints, plugin.getLogger());
        this.web3 = Web3j.build(rpc);
        plugin.getLogger().info("Shannon RPC " + String.join(", ", endpoints));
        this.secretsFile = new File(plugin.getDataFolder(), "secrets.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(secretsFile);
        this.deployerAddress = yaml.getString("deployer-address", "");
        String signerKey = yaml.getString("signer-key", "");
        Attestor made = null;
        if (signerKey == null || signerKey.isBlank()) {
            plugin.getLogger().severe("secrets.yml has no signer-key: hardened contracts will reject every write.");
        } else {
            try {
                made = new Attestor(signerKey.trim(), chainId, plugin.getLogger());
                plugin.getLogger().info("Server attestation signer " + made.address());
                made.benchmark(50);
                plugin.getLogger().info(String.format(Locale.ROOT, "Attest self-test: %.3f ms per signature (local, no RPC)", made.benchmark(500)));
            } catch (Exception exception) {
                plugin.getLogger().severe("signer-key in secrets.yml is not a valid key.");
            }
        }
        this.attestor = made;
        if (deployerAddress == null || deployerAddress.isBlank()) {
            plugin.getLogger().warning("secrets.yml has no deployer address.");
        } else {
            plugin.getLogger().info("Deployer purse " + deployerAddress);
        }
    }

    String deployerAddress() {
        return deployerAddress == null ? "" : deployerAddress;
    }

    /** Purses are made at /register (Auth); nothing is created here any more. */
    void ensure(Profile profile) {
    }

    /** A fresh purse key (32 bytes) for every new player. The deployer key is never handed out. */
    byte[] newPurseKey() {
        try {
            ECKeyPair pair = Keys.createEcKeyPair();
            return Numeric.toBytesPadded(pair.getPrivateKey(), 32);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not create a purse", exception);
        }
    }

    /** Signing credentials for a logged-in player, or an error: no onchain call for players who are not logged in. */
    private Credentials credentials(Profile profile) {
        byte[] key = plugin.auth().key(profile);
        if (key == null) {
            throw new IllegalStateException("This player is not logged in.");
        }
        return Credentials.create(ECKeyPair.create(key));
    }

    boolean canSign(Profile profile) {
        return plugin.auth().key(profile) != null;
    }

    String balance(String address) throws IOException {
        return format(readBalance(address));
    }

    /** Sends STT and waits for the receipt; returns the hash only when status is 1, throws otherwise. */
    String withdraw(Profile profile, String to, BigDecimal amount) throws IOException {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        if (to == null || !to.matches("(?i)0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("That is not an address.");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero.");
        }
        BigInteger value = Convert.toWei(amount, Convert.Unit.ETHER).toBigIntegerExact();
        Credentials credentials = credentials(profile);
        BigInteger balance = readBalance(credentials.getAddress());
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        // Somnia prices a plain transfer to a never-funded address far above 21000 (about 631k gas), so the limit
        // always comes from eth_estimateGas + 20%; a hardcoded 21000 made those withdraws revert out of gas.
        BigInteger gas = transferGas(credentials.getAddress(), to, value);
        BigInteger fee = gas.multiply(maxFee);
        noteFee(fee);
        if (balance.compareTo(value.add(fee)) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT. This needs " + format(value) + " plus " + format(fee) + " STT for gas.");
        }
        // Same per-purse nonce lane as every contract write, so a withdraw never collides with bag, skill, or island txs.
        Lane lane = lanes.computeIfAbsent(credentials.getAddress().toLowerCase(Locale.ROOT), ignored -> new Lane());
        String hash;
        synchronized (lane.lock) {
            BigInteger nonce = nextNonce(lane, credentials.getAddress());
            RawTransaction transaction = RawTransaction.createEtherTransaction(chainId, nonce, gas, to, value, priority, maxFee);
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                if (isNonceError(sent.getError().getMessage())) {
                    lane.next = null;
                }
                throw new IllegalStateException(sent.getError().getMessage());
            }
            lane.next = nonce.add(BigInteger.ONE);
            hash = sent.getTransactionHash();
        }
        EventTally.tx(profile, to, "withdraw");
        plugin.getLogger().info("Withdraw sent " + hash + " gas limit " + gas + " from " + credentials.getAddress());
        TransactionReceipt receipt;
        try {
            receipt = waitReceipt(hash, 240);
        } catch (Exception exception) {
            throw new IllegalStateException("Withdraw " + hash + " was sent, but its receipt could not be read (" + exception.getMessage() + "). Check the explorer before trying again.");
        }
        if (receipt == null) {
            throw new IllegalStateException("Withdraw " + hash + " has no receipt after 60 s. Check the explorer before trying again.");
        }
        BigInteger used = receipt.getGasUsed();
        plugin.getLogger().info("Withdraw " + hash + " status " + receipt.getStatus() + " gas used " + used + " of " + gas);
        if (!receipt.isStatusOK()) {
            String why = used != null && used.compareTo(gas) >= 0 ? "it ran out of gas" : "Shannon reverted it";
            throw new IllegalStateException("Withdraw failed: " + why + " (" + hash + "). Your STT was not sent; only the gas fee was spent.");
        }
        return hash;
    }

    /** eth_estimateGas for a plain STT transfer, +20%, at least 21000 and at most GAS_CAP. Never guesses: no estimate, no send. */
    private BigInteger transferGas(String from, String to, BigInteger value) throws IOException {
        EthEstimateGas estimated = web3.ethEstimateGas(new Transaction(from, null, null, null, to, value, null)).send();
        if (estimated.hasError() || estimated.getAmountUsed() == null || estimated.getAmountUsed().signum() <= 0) {
            String why = estimated.hasError() ? trim(estimated.getError().getMessage()) : "no estimate";
            throw new IllegalStateException("Shannon could not estimate this transfer (" + why + "). Nothing was sent.");
        }
        BigInteger gas = estimated.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        if (gas.compareTo(GAS_LIMIT) < 0) {
            gas = GAS_LIMIT;
        }
        if (gas.compareTo(GAS_CAP) > 0) {
            throw new IllegalStateException("This transfer needs " + gas + " gas, above the " + GAS_CAP + " cap. Nothing was sent.");
        }
        return gas;
    }

    void close() {
        web3.shutdown();
    }

    String contract() {
        return plugin.getConfig().getString("chain.profile-contract", "");
    }

    String holdContract() {
        String value = plugin.getConfig().getString("chain.hold-contract", "");
        return value == null ? "" : value.trim();
    }

    boolean holdEnabled() {
        return plugin.getConfig().getBoolean("chain.enabled") && !holdContract().isBlank();
    }

    String wordContract() {
        String value = plugin.getConfig().getString("chain.word-contract", "");
        return value == null ? "" : value.trim();
    }

    String aotvContract() {
        String value = plugin.getConfig().getString("chain.aotv-contract", "");
        return value == null ? "" : value.trim();
    }

    boolean wordEnabled() {
        return plugin.getConfig().getBoolean("chain.enabled") && !wordContract().isBlank() && !aotvContract().isBlank();
    }

    String syncHold(
            Profile profile,
            int[] bagSlots,
            int[] bagItems,
            int[] bagCounts,
            int[] bagDamages,
            long[] bagGear,
            String[] bagKeys,
            String[] bagLabels,
            String[] bagReforges,
            String[] bagEnchants,
            long chestId,
            int[] chestSlots,
            int[] chestItems,
            int[] chestCounts,
            int[] chestDamages,
            long[] chestGear,
            String[] chestKeys,
            String[] chestLabels,
            String[] chestReforges,
            String[] chestEnchants
    ) throws Exception {
        Function function = new Function(
                "sync",
                List.of(
                        words8(bagSlots),
                        words32(bagItems),
                        words16(bagCounts),
                        words16(bagDamages),
                        words256(bagGear),
                        wordsText(bagKeys),
                        wordsText(bagLabels),
                        wordsText(bagReforges),
                        wordsText(bagEnchants),
                        new Uint256(BigInteger.valueOf(chestId)),
                        words8(chestSlots),
                        words32(chestItems),
                        words16(chestCounts),
                        words16(chestDamages),
                        words256(chestGear),
                        wordsText(chestKeys),
                        wordsText(chestLabels),
                        wordsText(chestReforges),
                        wordsText(chestEnchants)
                ),
                Collections.emptyList()
        );
        return sendData(profile, holdContract(), FunctionEncoder.encode(function), 12_000_000L);
    }

    String transfer(Profile profile, String to, int item, int amount) throws Exception {
        Function function = new Function(
                "transfer",
                List.of(
                        new Address(to),
                        new Uint32(BigInteger.valueOf(item)),
                        new Uint256(BigInteger.valueOf(amount))
                ),
                Collections.emptyList()
        );
        return sendData(profile, holdContract(), FunctionEncoder.encode(function), 6_000_000L);
    }

    HoldItems.View bagOf(String player) throws IOException {
        Function function = new Function("bagOf", List.of(new Address(player)), Collections.emptyList());
        return readSlots(player, function, HoldItems.BAG);
    }

    HoldItems.View chestOf(long chestId) throws IOException {
        Function function = new Function(
                "chestOf",
                List.of(new Uint256(BigInteger.valueOf(chestId))),
                Collections.emptyList()
        );
        return readSlots(holdContract(), function, HoldItems.CHEST);
    }

    BigInteger memberIsland(String player) throws IOException {
        Function function = new Function(
                "memberIsland",
                List.of(new Address(player)),
                List.of(new TypeReference<Uint256>() {
                })
        );
        String data = FunctionEncoder.encode(function);
        String raw = web3.ethCall(
                Transaction.createEthCallTransaction(player, contract(), data),
                DefaultBlockParameterName.LATEST
        ).send().getValue();
        if (raw == null || raw.equals("0x")) {
            return BigInteger.ZERO;
        }
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(raw, function.getOutputParameters());
        if (decoded.isEmpty()) {
            return BigInteger.ZERO;
        }
        return (BigInteger) decoded.get(0).getValue();
    }

    String createIsland(Profile profile) throws Exception {
        Function function = new Function("createIsland", Collections.emptyList(), Collections.emptyList());
        return sendData(profile, contract(), FunctionEncoder.encode(function), 8_000_000L);
    }

    /** SkyIsland4.holderOf(deed) (ownerOf without the ERC-721 revert), or null when the deed does not exist. */
    String islandOwner(BigInteger deed) throws IOException {
        if (deed == null || deed.signum() <= 0) {
            return null;
        }
        Function function = new Function("holderOf", List.of(new Uint256(deed)), List.of(new TypeReference<Address>() {
        }));
        String raw = call("0x0000000000000000000000000000000000000000", contract(), function);
        if (raw == null || raw.equals("0x")) {
            return null;
        }
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(raw, function.getOutputParameters());
        if (decoded.isEmpty()) {
            return null;
        }
        String owner = decoded.get(0).getValue().toString();
        return owner.equalsIgnoreCase("0x0000000000000000000000000000000000000000") ? null : owner;
    }

    /** SkyIsland4.invite(member) from the island owner's purse (attested; the owner pays the gas). */
    String inviteToIsland(Profile owner, String member) throws Exception {
        Function function = new Function("invite", List.of(new Address(member)), Collections.emptyList());
        return sendData(owner, contract(), FunctionEncoder.encode(function), 8_000_000L);
    }

    /** SkyIsland4.accept(deed) from the joiner's purse (attested; the joiner pays). Burns the joiner's solo deed. */
    String acceptIsland(Profile joiner, BigInteger deed) throws Exception {
        Function function = new Function("accept", List.of(new Uint256(deed)), Collections.emptyList());
        return sendData(joiner, contract(), FunctionEncoder.encode(function), 12_000_000L);
    }

    /** SkyIsland4.leave() from the leaving member's purse (attested; the member pays). */
    String leaveIsland(Profile member) throws Exception {
        Function function = new Function("leave", Collections.emptyList(), Collections.emptyList());
        return sendData(member, contract(), FunctionEncoder.encode(function), 8_000_000L);
    }

    /** SkyIsland4.kick(member) from the purse of the co-op member who completed the kick (attested). */
    String kickFromIsland(Profile by, String member) throws Exception {
        Function function = new Function("kick", List.of(new Address(member)), Collections.emptyList());
        return sendData(by, contract(), FunctionEncoder.encode(function), 8_000_000L);
    }

    /** SkyIsland4.coopOf(deed): the deed owner first, then the co-op members. Empty when the deed does not exist. */
    List<String> islandCoop(BigInteger deed) throws IOException {
        List<String> out = new ArrayList<>();
        if (deed == null || deed.signum() <= 0) {
            return out;
        }
        Function function = new Function("coopOf", List.of(new Uint256(deed)), List.of(new TypeReference<Address>() {
        }, new TypeReference<DynamicArray<Address>>() {
        }));
        String raw = call("0x0000000000000000000000000000000000000000", contract(), function);
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(raw, function.getOutputParameters());
        if (decoded.size() < 2) {
            return out;
        }
        String owner = decoded.get(0).getValue().toString();
        if (owner.equalsIgnoreCase("0x0000000000000000000000000000000000000000")) {
            return out;
        }
        out.add(owner);
        @SuppressWarnings("unchecked")
        List<Address> members = (List<Address>) decoded.get(1).getValue();
        for (Address a : members) {
            out.add(a.getValue());
        }
        return out;
    }

    /** SkyIsland4.inviteOf(deed, member): seconds-since-epoch expiry of a live invite from the current owner, else 0. */
    long islandInviteUntil(BigInteger deed, String member) throws IOException {
        Function function = new Function("inviteOf", List.of(new Uint256(deed), new Address(member)), List.of(new TypeReference<Address>() {
        }, new TypeReference<org.web3j.abi.datatypes.generated.Uint64>() {
        }));
        String raw = call("0x0000000000000000000000000000000000000000", contract(), function);
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(raw, function.getOutputParameters());
        if (decoded.size() < 2) {
            return 0;
        }
        String by = decoded.get(0).getValue().toString();
        String owner = islandOwner(deed);
        long until = ((BigInteger) decoded.get(1).getValue()).longValue();
        if (owner == null || !owner.equalsIgnoreCase(by) || until <= System.currentTimeMillis() / 1000) {
            return 0;
        }
        return until;
    }



    /** SkyIsland4.nextId(): deeds 1 .. nextId-1 have been minted (some may be burned). */
    long islandNextId() throws IOException {
        Function function = new Function("nextId", Collections.emptyList(), List.of(new TypeReference<Uint256>() {
        }));
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(call("0x0000000000000000000000000000000000000000", contract(), function), function.getOutputParameters());
        return decoded.isEmpty() ? 1 : ((BigInteger) decoded.get(0).getValue()).longValue();
    }

    /** SkyIsland4.sales(deed): seller, end (s), kind (1 BIN, 2 auction), price, bid, bidder. Null when not listed. */
    IslandDeeds.Sale islandSale(long deed) throws IOException {
        Function function = new Function("sales", List.of(new Uint256(BigInteger.valueOf(deed))), List.of(
                new TypeReference<Address>() {
                }, new TypeReference<org.web3j.abi.datatypes.generated.Uint64>() {
                }, new TypeReference<Uint8>() {
                }, new TypeReference<org.web3j.abi.datatypes.generated.Uint128>() {
                }, new TypeReference<org.web3j.abi.datatypes.generated.Uint128>() {
                }, new TypeReference<Address>() {
                }));
        @SuppressWarnings("rawtypes")
        List<Type> d = FunctionReturnDecoder.decode(call("0x0000000000000000000000000000000000000000", contract(), function), function.getOutputParameters());
        if (d.size() < 6) {
            return null;
        }
        String seller = d.get(0).getValue().toString();
        if (seller.equalsIgnoreCase("0x0000000000000000000000000000000000000000")) {
            return null;
        }
        String bidder = d.get(5).getValue().toString();
        return new IslandDeeds.Sale(deed, seller, ((BigInteger) d.get(1).getValue()).longValue() * 1000L,
                ((BigInteger) d.get(2).getValue()).intValue(), (BigInteger) d.get(3).getValue(), (BigInteger) d.get(4).getValue(),
                bidder.equalsIgnoreCase("0x0000000000000000000000000000000000000000") ? null : bidder);
    }

    /** SkyIsland4.pending(player): outbid refunds and failed payouts waiting for withdrawPending(). */
    BigInteger islandPending(String player) throws IOException {
        Function function = new Function("pending", List.of(new Address(player)), List.of(new TypeReference<Uint256>() {
        }));
        @SuppressWarnings("rawtypes")
        List<Type> decoded = FunctionReturnDecoder.decode(call("0x0000000000000000000000000000000000000000", contract(), function), function.getOutputParameters());
        return decoded.isEmpty() ? BigInteger.ZERO : (BigInteger) decoded.get(0).getValue();
    }

    BigInteger blockNumber() throws IOException {
        return web3.ethBlockNumber().send().getBlockNumber();
    }

    /** Every SkyIsland4 log in [from, to] (inclusive). */
    List<org.web3j.protocol.core.methods.response.Log> islandLogs(BigInteger from, BigInteger to) throws IOException {
        org.web3j.protocol.core.methods.request.EthFilter filter = new org.web3j.protocol.core.methods.request.EthFilter(
                org.web3j.protocol.core.DefaultBlockParameter.valueOf(from), org.web3j.protocol.core.DefaultBlockParameter.valueOf(to), contract());
        org.web3j.protocol.core.methods.response.EthLog result = web3.ethGetLogs(filter).send();
        if (result.hasError()) {
            throw new IOException(trim(result.getError().getMessage()));
        }
        List<org.web3j.protocol.core.methods.response.Log> out = new ArrayList<>();
        for (org.web3j.protocol.core.methods.response.EthLog.LogResult<?> r : result.getLogs()) {
            if (r.get() instanceof org.web3j.protocol.core.methods.response.Log log) {
                out.add(log);
            }
        }
        return out;
    }

    BigInteger[] wordsOf(String player) throws IOException {
        Function function = new Function("wordsOf", List.of(new Address(player)), Collections.emptyList());
        return decodeWords(call(player, wordContract(), function));
    }

    BigInteger combatOf(String player) throws IOException {
        Function function = new Function("combat", List.of(new Address(player)), Collections.emptyList());
        String raw = call(player, wordContract(), function);
        return new BigInteger(1, Numeric.hexStringToByteArray(raw));
    }

    TransactionReceipt writeWords(Profile profile, int[] slots, BigInteger[] words) throws Exception {
        Function function = new Function(
                "write",
                List.of(words8(slots), wordsBig(words)),
                Collections.emptyList()
        );
        // write() sets slots to absolute values, so a resend is safe: retry with a fresh nonce before the caller rolls back.
        return sendPackedRetry(profile, wordContract(), FunctionEncoder.encode(function));
    }

    TransactionReceipt craftVoid(Profile profile, int from, int to) throws Exception {
        Function function = new Function(
                "craft",
                List.of(new Uint8(BigInteger.valueOf(from)), new Uint8(BigInteger.valueOf(to))),
                Collections.emptyList()
        );
        return sendPacked(profile, wordContract(), FunctionEncoder.encode(function));
    }

    /** SkyWord2.withdrawNft(slot, to): the blade leaves the bag for the player's main wallet. */
    TransactionReceipt withdrawNft(Profile profile, int slot, String to) throws Exception {
        if (to == null || !to.matches("(?i)0x[0-9a-f]{40}")) {
            throw new IllegalArgumentException("That is not an address.");
        }
        Function function = new Function(
                "withdrawNft",
                List.of(new Uint8(BigInteger.valueOf(slot)), new Address(to)),
                Collections.emptyList()
        );
        return sendPacked(profile, wordContract(), FunctionEncoder.encode(function));
    }

    /** SkyWord2.depositNft(slot, serial): a blade the game wallet owns again goes back into the bag. */
    TransactionReceipt depositNft(Profile profile, int slot, long serial) throws Exception {
        Function function = new Function(
                "depositNft",
                List.of(new Uint8(BigInteger.valueOf(slot)), new Uint256(BigInteger.valueOf(serial))),
                Collections.emptyList()
        );
        return sendPacked(profile, wordContract(), FunctionEncoder.encode(function));
    }

    String aotvOwner(long serial) throws IOException {
        Function function = new Function("ownerOf", List.of(new Uint256(BigInteger.valueOf(serial))), Collections.emptyList());
        String raw = call("0x0000000000000000000000000000000000000000", aotvContract(), function);
        return "0x" + raw.substring(raw.length() - 40);
    }

    boolean aotvLocked(long serial) throws IOException {
        Function function = new Function("locked", List.of(new Uint256(BigInteger.valueOf(serial))), Collections.emptyList());
        return new BigInteger(1, Numeric.hexStringToByteArray(call("0x0000000000000000000000000000000000000000", aotvContract(), function))).signum() != 0;
    }

    /** SkyRealm2.pending(player): outbid refunds waiting to be pulled. */
    BigInteger pendingRefund(String player) throws IOException {
        Function function = new Function("pending", List.of(new Address(player)), Collections.emptyList());
        return new BigInteger(1, Numeric.hexStringToByteArray(call("0x0000000000000000000000000000000000000000", realmContract(), function)));
    }

    String submitCast(Profile profile, int slot) throws Exception {
        Function function = new Function(
                "cast",
                List.of(new Uint8(BigInteger.valueOf(slot))),
                Collections.emptyList()
        );
        return submitPacked(profile, wordContract(), FunctionEncoder.encode(function), true);
    }

    void confirmCast(String hash) throws Exception {
        try {
            TransactionReceipt receipt = waitForReceipt(hash);
            plugin.getLogger().info("Word gas " + receipt.getGasUsed());
        } catch (Exception exception) {
            castGas.clear();
            throw exception;
        }
    }

    private TransactionReceipt sendPackedRetry(Profile profile, String to, String data) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            String hash = null;
            try {
                hash = submitPacked(profile, to, data, false);
                TransactionReceipt receipt = waitForReceipt(hash);
                plugin.getLogger().info("Word gas " + receipt.getGasUsed() + (attempt > 1 ? " (try " + attempt + ")" : ""));
                return receipt;
            } catch (Exception exception) {
                last = exception;
                String reason = exception.getMessage() == null ? "" : exception.getMessage();
                plugin.getLogger().warning("Word write try " + attempt + " failed" + (hash == null ? "" : " tx " + hash) + ": " + trim(reason));
                boolean retry = isNonceError(reason)
                        || reason.contains("reverted")
                        || reason.contains("not returned a receipt")
                        || reason.toLowerCase(Locale.ROOT).contains("timeout")
                        || reason.toLowerCase(Locale.ROOT).contains("underpriced")
                        || reason.toLowerCase(Locale.ROOT).contains("already known");
                if (!retry) {
                    throw exception;
                }
                Lane lane = lanes.get(credentials(profile).getAddress().toLowerCase(Locale.ROOT));
                if (lane != null) {
                    synchronized (lane.lock) {
                        lane.next = null;
                    }
                }
                Thread.sleep(400L * attempt);
            }
        }
        throw last;
    }

    private TransactionReceipt sendPacked(Profile profile, String to, String data) throws Exception {
        String hash = submitPacked(profile, to, data, false);
        TransactionReceipt receipt = waitForReceipt(hash);
        plugin.getLogger().info("Word gas " + receipt.getGasUsed());
        return receipt;
    }

    private String submitPacked(Profile profile, String to, String plainData, boolean cast) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
        String data = attest(from, to, plainData, BigInteger.ZERO);
        String fromKey = from.toLowerCase(Locale.ROOT);
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        BigInteger gas = cast ? castGas.get(fromKey) : null;
        if (gas == null || gas.signum() <= 0 || gas.compareTo(GAS_CAP) > 0) {
            EthEstimateGas estimated = web3.ethEstimateGas(Transaction.createEthCallTransaction(from, to, data)).send();
            if (estimated.hasError()) {
                plugin.getLogger().warning("Estimate rejected: " + trim(estimated.getError().getMessage()));
                throw new IllegalStateException("Shannon rejected that signature.");
            }
            gas = estimated.getAmountUsed();
            if (gas == null || gas.signum() <= 0) {
                throw new IllegalStateException("Shannon rejected that signature.");
            }
            if (gas.compareTo(GAS_CAP) > 0) {
                throw new IllegalStateException("This signature needs " + gas + " gas. The cap is 3000000.");
            }
            if (cast) {
                BigInteger padded = gas.multiply(BigInteger.valueOf(6)).divide(BigInteger.valueOf(5));
                if (padded.compareTo(GAS_CAP) > 0) {
                    padded = GAS_CAP;
                }
                castGas.put(fromKey, padded);
                gas = padded;
            }
        }
        BigInteger balance = readBalance(from);
        BigInteger fee = gas.multiply(maxFee);
        noteFee(fee);
        balance = sponsored(profile, from, balance, fee, fee);
        if (balance.compareTo(fee) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT. This signature can cost up to " + format(fee) + " STT.");
        }
        Lane lane = lanes.computeIfAbsent(fromKey, ignored -> new Lane());
        String hash;
        synchronized (lane.lock) {
            lane.next = nextNonce(lane, from);
            RawTransaction transaction = RawTransaction.createTransaction(
                    chainId,
                    lane.next,
                    gas,
                    to,
                    BigInteger.ZERO,
                    data,
                    priority,
                    maxFee
            );
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError() && isNonceError(sent.getError().getMessage())) {
                // Another path (deed, item mint, withdraw) used this purse; resync from the pending count once.
                lane.next = null;
                lane.next = nextNonce(lane, from);
                transaction = RawTransaction.createTransaction(chainId, lane.next, gas, to, BigInteger.ZERO, data, priority, maxFee);
                signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
                sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            }
            if (sent.hasError()) {
                String reason = sent.getError().getMessage();
                if (isNonceError(reason)) {
                    lane.next = null;
                }
                if (cast) {
                    castGas.remove(fromKey);
                }
                throw new IllegalStateException(trim(reason));
            }
            lane.next = lane.next.add(BigInteger.ONE);
            hash = sent.getTransactionHash();
        }
        EventTally.tx(profile, to, null);
        plugin.getLogger().info("Live sent " + hash + " " + from + " " + to);
        return hash;
    }

    private TransactionReceipt waitForReceipt(String hash) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            var found = web3.ethGetTransactionReceipt(hash).send().getTransactionReceipt();
            if (found.isPresent()) {
                TransactionReceipt receipt = found.get();
                if (!receipt.isStatusOK()) {
                    throw new IllegalStateException("Shannon reverted the signature.");
                }
                return receipt;
            }
            Thread.sleep(40L);
        }
        throw new IllegalStateException("Shannon has not returned a receipt yet.");
    }

    private String call(String from, String to, Function function) throws IOException {
        var response = web3.ethCall(
                Transaction.createEthCallTransaction(from, to, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST
        ).send();
        if (response.hasError()) {
            throw new IOException(trim(response.getError().getMessage()));
        }
        String value = response.getValue();
        if (value == null || value.equals("0x")) {
            throw new IOException("Shannon returned an empty view.");
        }
        return value;
    }

    private static BigInteger[] decodeWords(String hex) throws IOException {
        byte[] data = Numeric.hexStringToByteArray(hex);
        if (data.length < 64) {
            throw new IOException("Shannon returned a short inventory view.");
        }
        int offset = new BigInteger(1, Arrays.copyOfRange(data, 0, 32)).intValue();
        if (offset < 0 || offset + 32 > data.length) {
            throw new IOException("Shannon returned a broken inventory view.");
        }
        int length = new BigInteger(1, Arrays.copyOfRange(data, offset, offset + 32)).intValue();
        if (length != HoldItems.BAG) {
            throw new IOException("Shannon returned " + length + " slots.");
        }
        BigInteger[] words = new BigInteger[HoldItems.BAG];
        for (int i = 0; i < HoldItems.BAG; i++) {
            int start = offset + 32 + i * 32;
            if (start + 32 > data.length) {
                throw new IOException("Shannon returned a short inventory view.");
            }
            words[i] = new BigInteger(1, Arrays.copyOfRange(data, start, start + 32));
        }
        return words;
    }

    private static DynamicArray<Uint256> wordsBig(BigInteger[] values) {
        List<Uint256> list = new ArrayList<>();
        for (BigInteger value : values) {
            list.add(new Uint256(value == null ? BigInteger.ZERO : value));
        }
        return new DynamicArray<>(Uint256.class, list);
    }

    private static String trim(String message) {
        if (message == null || message.isBlank()) {
            return "Shannon rejected that signature.";
        }
        String clean = message.replaceAll("0x[0-9a-fA-F]{64}", "0x…");
        return clean.length() > 140 ? clean.substring(0, 140) : clean;
    }

    /** Next nonce for a purse: the larger of our cached lane value and the node's pending count. */
    private BigInteger nextNonce(Lane lane, String from) throws java.io.IOException {
        BigInteger pending = web3.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        return lane.next == null || lane.next.compareTo(pending) < 0 ? pending : lane.next;
    }

    private static boolean isNonceError(String reason) {
        return reason != null && reason.toLowerCase(Locale.ROOT).contains("nonce");
    }

    private static final class Lane {
        final Object lock = new Object();
        BigInteger next;
    }

    /**
     * Fee cap for a new transaction: the node's eth_gasPrice plus 25 % headroom (at least gasPrice + tip),
     * never under the 6 gwei floor and never over chain.max-fee-gwei. The gas price is cached for 30 s.
     */
    BigInteger maxFee() {
        BigInteger cap = BigInteger.valueOf(Math.max(6, plugin.getConfig().getInt("chain.max-fee-gwei", 20))).multiply(GWEI);
        long now = System.currentTimeMillis();
        if (gasPriceCache == null || now - gasPriceAt > 30_000L) {
            try {
                BigInteger price = web3.ethGasPrice().send().getGasPrice();
                if (price != null && price.signum() > 0) {
                    gasPriceCache = price;
                    gasPriceAt = now;
                }
            } catch (Exception exception) {
                plugin.getLogger().warning("eth_gasPrice failed: " + exception.getMessage());
            }
        }
        BigInteger base = gasPriceCache == null ? cap : gasPriceCache;
        BigInteger fee = base.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4)).max(base.add(PRIORITY)).max(FEE_FLOOR);
        if (fee.compareTo(cap) > 0) {
            if (base.compareTo(cap) > 0) {
                plugin.getLogger().warning("Shannon gas price " + base + " wei is above chain.max-fee-gwei; signing at the cap.");
            }
            fee = cap;
        }
        return fee;
    }

    /** eth_estimateGas x1.3, never above the old fixed limit; the fixed limit if the node cannot estimate. */
    private BigInteger estimateLimit(String from, String to, String data, long fixedLimit) {
        BigInteger ceiling = BigInteger.valueOf(fixedLimit);
        try {
            EthEstimateGas estimated = web3.ethEstimateGas(Transaction.createEthCallTransaction(from, to, data)).send();
            if (!estimated.hasError() && estimated.getAmountUsed() != null && estimated.getAmountUsed().signum() > 0) {
                BigInteger gas = estimated.getAmountUsed().multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN);
                return gas.min(ceiling);
            }
            if (estimated.hasError()) {
                plugin.getLogger().warning("Estimate failed, using " + fixedLimit + ": " + trim(estimated.getError().getMessage()));
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Estimate failed, using " + fixedLimit + ": " + exception.getMessage());
        }
        return ceiling;
    }

    String rpcUrl() {
        return rpc.activeUrl();
    }

    /**
     * Calldata with the server attestation appended (Attested.sol) when the write needs one. The player's purse
     * still sends and pays. Mode A (key never exported): routine writes go bare, normal gas. Mode B (key exported,
     * SkyKeys flag): everything is attested. When the mode is unknown the write is attested: the 76-byte trailer is
     * ignored by a mode-A contract call, so attesting is always safe, skipping it is only done when sure.
     */
    String attest(String from, String to, String data, BigInteger value) {
        if (attestor == null || to == null || to.isBlank()) {
            return data;
        }
        if (Attestor.guarded(data) && modeA(from)) {
            return data;
        }
        return attestor.wrap(from, to, data, value);
    }

    // ===== SkyKeys (two modes) =====
    private static final long MODE_A_TTL_MS = 10 * 60_000L;
    private final Map<String, Boolean> exportedCache = new ConcurrentHashMap<>();
    private final Map<String, Long> exportedAt = new ConcurrentHashMap<>();

    String keysContract() {
        String value = plugin.getConfig().getString("chain.keys-contract", "");
        return value == null ? "" : value.trim();
    }

    /** True only when SkyKeys says this purse's key was never exported (cached; mode B is permanent). */
    boolean modeA(String wallet) {
        if (wallet == null || wallet.isBlank() || keysContract().isBlank()) {
            return false;
        }
        String key = wallet.toLowerCase(Locale.ROOT);
        Boolean known = exportedCache.get(key);
        Long at = exportedAt.get(key);
        if (known != null && (known || (at != null && System.currentTimeMillis() - at < MODE_A_TTL_MS))) {
            return !known;
        }
        try {
            boolean exported = keyExportedOnchain(wallet);
            exportedCache.put(key, exported);
            exportedAt.put(key, System.currentTimeMillis());
            return !exported;
        } catch (Exception exception) {
            return false;
        }
    }

    /** SkyKeys.exported(wallet), straight from the chain. */
    boolean keyExportedOnchain(String wallet) throws IOException {
        Function function = new Function("exported", List.of(new Address(wallet)), Collections.emptyList());
        String raw = call("0x0000000000000000000000000000000000000000", keysContract(), function);
        return raw != null && raw.length() > 2 && new BigInteger(1, Numeric.hexStringToByteArray(raw)).signum() != 0;
    }

    /** Warm the mode cache at login (async caller). */
    void loadMode(String wallet) {
        modeA(wallet);
    }

    /**
     * /exportkey step 1: SkyKeys.optIn() from the player's own purse (the player pays the gas). Every later write from
     * this purse is attested; the cache flips first so nothing in flight goes out bare. Returns the receipt once the
     * chain confirms the flag.
     */
    TransactionReceipt optInExport(Profile profile) throws Exception {
        String keys = keysContract();
        if (keys.isBlank()) {
            throw new IllegalStateException("Key export is not available on this server yet.");
        }
        String key = profile.wallet.toLowerCase(Locale.ROOT);
        exportedCache.put(key, true);
        exportedAt.put(key, System.currentTimeMillis());
        TransactionReceipt receipt = null;
        if (!keyExportedOnchain(profile.wallet)) {
            receipt = sendTo(profile, keys, new Function("optIn", Collections.emptyList(), Collections.emptyList()), BigInteger.ZERO);
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            if (keyExportedOnchain(profile.wallet)) {
                return receipt;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("Somnia has not confirmed the key flag yet. Try /exportkey again in a minute.");
    }

    /** Wallets whose key must never leave the server (the original deployer purse and server roles). */
    boolean exportBlocked(String wallet) {
        if (wallet == null || wallet.isBlank()) {
            return true;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(secretsFile);
        for (String name : new String[]{"deployer-address", "admin-address", "signer-address"}) {
            String value = yaml.getString(name, "");
            if (value != null && value.trim().equalsIgnoreCase(wallet.trim())) {
                return true;
            }
        }
        for (String value : plugin.getConfig().getStringList("chain.no-export")) {
            if (value != null && value.trim().equalsIgnoreCase(wallet.trim())) {
                return true;
            }
        }
        return attestor != null && attestor.address().equalsIgnoreCase(wallet.trim());
    }

    Attestor attestor() {
        return attestor;
    }

    private String sendData(Profile profile, String to, String plainData, long gasLimit) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        Credentials credentials = credentials(profile);
        String data = attest(credentials.getAddress(), to, plainData, BigInteger.ZERO);
        BigInteger limit = estimateLimit(credentials.getAddress(), to, data, gasLimit);
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        BigInteger balance = readBalance(credentials.getAddress());
        BigInteger fee = limit.multiply(maxFee);
        noteFee(fee);
        balance = sponsored(profile, credentials.getAddress(), balance, fee, fee);
        if (balance.compareTo(fee) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT. This signature can cost up to " + format(fee) + " STT.");
        }
        String hash;
        Lane lane = lanes.computeIfAbsent(credentials.getAddress().toLowerCase(Locale.ROOT), ignored -> new Lane());
        synchronized (lane.lock) {
            BigInteger nonce = nextNonce(lane, credentials.getAddress());
            lane.next = nonce;
            RawTransaction transaction = RawTransaction.createTransaction(
                    chainId,
                    nonce,
                    limit,
                    to,
                    BigInteger.ZERO,
                    data,
                    priority,
                    maxFee
            );
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                if (isNonceError(sent.getError().getMessage())) {
                    lane.next = null;
                }
                throw new IllegalStateException(sent.getError().getMessage());
            }
            lane.next = nonce.add(BigInteger.ONE);
            hash = sent.getTransactionHash();
        }
        EventTally.tx(profile, to, null);
        plugin.getLogger().info("Live sent " + hash + " " + credentials.getAddress() + " " + to);
        waitUntilOk(hash);
        return hash;
    }

    private void waitUntilOk(String hash) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            Thread.sleep(400L);
            var receipt = web3.ethGetTransactionReceipt(hash).send().getTransactionReceipt();
            if (receipt.isEmpty()) {
                continue;
            }
            if ("0x1".equals(receipt.get().getStatus())) {
                return;
            }
            throw new IllegalStateException("Transaction reverted: " + hash);
        }
        throw new IllegalStateException("Shannon has not returned a receipt for " + hash);
    }

    static String shortAddress(String address) {
        if (address == null || address.length() < 12) {
            return "No purse";
        }
        return address.substring(0, 6) + "..." + address.substring(address.length() - 4);
    }

    private HoldItems.View readSlots(String from, Function function, int size) throws IOException {
        String data = FunctionEncoder.encode(function);
        String raw = web3.ethCall(
                Transaction.createEthCallTransaction(from, holdContract(), data),
                DefaultBlockParameterName.LATEST
        ).send().getValue();
        SlotPayload decoded = decodeSlots(raw);
        if (decoded.items().length != size || decoded.counts().length != size || decoded.damages().length != size || decoded.gears().length != size) {
            throw new IOException("Unexpected item payload from Shannon");
        }
        return new HoldItems.View(decoded.items(), decoded.counts(), decoded.damages(), decoded.gears());
    }

    private static DynamicArray<Uint8> words8(int[] values) {
        List<Uint8> list = new ArrayList<>();
        for (int value : values) {
            list.add(new Uint8(BigInteger.valueOf(value)));
        }
        return new DynamicArray<>(Uint8.class, list);
    }

    private static DynamicArray<Uint16> words16(int[] values) {
        List<Uint16> list = new ArrayList<>();
        for (int value : values) {
            list.add(new Uint16(BigInteger.valueOf(value)));
        }
        return new DynamicArray<>(Uint16.class, list);
    }

    private static DynamicArray<Uint32> words32(int[] values) {
        List<Uint32> list = new ArrayList<>();
        for (int value : values) {
            list.add(new Uint32(BigInteger.valueOf(value)));
        }
        return new DynamicArray<>(Uint32.class, list);
    }

    private static DynamicArray<Uint256> words256(long[] values) {
        List<Uint256> list = new ArrayList<>();
        BigInteger mint = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);
        for (long value : values) {
            list.add(new Uint256(value < 0 ? mint : BigInteger.valueOf(value)));
        }
        return new DynamicArray<>(Uint256.class, list);
    }

    private static DynamicArray<Utf8String> wordsText(String[] values) {
        List<Utf8String> list = new ArrayList<>();
        for (String value : values) {
            list.add(new Utf8String(value == null ? "" : value));
        }
        return new DynamicArray<>(Utf8String.class, list);
    }

    private record SlotPayload(int[] items, int[] counts, int[] damages, long[] gears) {
    }

    private static SlotPayload decodeSlots(String hex) {
        int[] empty = new int[0];
        long[] noGear = new long[0];
        if (hex == null || hex.length() < 2 + 64 * 4) {
            return new SlotPayload(empty, empty, empty, noGear);
        }
        byte[] data = Numeric.hexStringToByteArray(hex);
        if (data.length < 128) {
            return new SlotPayload(empty, empty, empty, noGear);
        }
        return new SlotPayload(
                readArray(data, word(data, 0)),
                readArray(data, word(data, 32)),
                readArray(data, word(data, 64)),
                readLongArray(data, word(data, 96))
        );
    }

    private static int[] readArray(byte[] data, int offset) {
        if (offset < 0 || offset + 32 > data.length) {
            return new int[0];
        }
        int length = word(data, offset);
        int[] values = new int[length];
        for (int i = 0; i < length; i++) {
            int start = offset + 32 + i * 32;
            if (start + 32 > data.length) {
                return values;
            }
            values[i] = word(data, start);
        }
        return values;
    }

    private static long[] readLongArray(byte[] data, int offset) {
        if (offset < 0 || offset + 32 > data.length) {
            return new long[0];
        }
        int length = word(data, offset);
        long[] values = new long[length];
        for (int i = 0; i < length; i++) {
            int start = offset + 32 + i * 32;
            if (start + 32 > data.length) {
                return values;
            }
            values[i] = new BigInteger(1, Arrays.copyOfRange(data, start, start + 32)).longValue();
        }
        return values;
    }

    private static int word(byte[] data, int offset) {
        return new BigInteger(1, Arrays.copyOfRange(data, offset, offset + 32)).intValue();
    }

    private static int[] fit(int[] values, int size) {
        int[] fitted = new int[size];
        System.arraycopy(values, 0, fitted, 0, Math.min(values.length, size));
        return fitted;
    }

    // ===== SkyRealm (bazaar, auction, arena, relics, checkpoints) =====
    private static final BigInteger REALM_GAS_CAP = BigInteger.valueOf(8_000_000L);

    String realmContract() {
        String value = plugin.getConfig().getString("chain.realm-contract", RealmChain.DEFAULT_REALM);
        return value == null || value.isBlank() ? RealmChain.DEFAULT_REALM : value.trim();
    }

    boolean realmEnabled() {
        return plugin.getConfig().getBoolean("chain.enabled") && !realmContract().isBlank();
    }

    BigInteger balanceWei(String address) throws IOException {
        return readBalance(address);
    }

    /** Shannon eth_getBalance plus the in-memory cache used by the main-thread gas gate. */
    private BigInteger readBalance(String address) throws IOException {
        BigInteger wei = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
        noteBalance(address, wei);
        return wei;
    }

    void noteBalance(String address, BigInteger wei) {
        if (address == null || address.isBlank() || wei == null) {
            return;
        }
        balanceCache.put(address.toLowerCase(Locale.ROOT), wei);
    }

    BigInteger cachedBalance(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        return balanceCache.get(address.toLowerCase(Locale.ROOT));
    }

    private void noteFee(BigInteger fee) {
        if (fee != null && fee.signum() > 0) {
            lastFeeWei = fee;
        }
    }

    /**
     * Last observed Shannon write fee, or a conservative default from the cached gas price / 6 gwei floor
     * and chain.max-fee-gwei. Safe on the main thread: no RPC.
     */
    BigInteger gasFeeCap() {
        BigInteger last = lastFeeWei;
        if (last != null && last.signum() > 0) {
            return last;
        }
        BigInteger cap = BigInteger.valueOf(Math.max(6, plugin.getConfig().getInt("chain.max-fee-gwei", 20))).multiply(GWEI);
        BigInteger price = gasPriceCache == null ? FEE_FLOOR : gasPriceCache.multiply(BigInteger.valueOf(5)).divide(BigInteger.valueOf(4)).max(gasPriceCache.add(PRIORITY)).max(FEE_FLOOR);
        if (price.compareTo(cap) > 0) {
            price = cap;
        }
        return DEFAULT_GAS_UNITS.multiply(price);
    }

    /**
     * Main-thread safe. True when the cached Shannon purse is below the gas fee cap and a Booster Cookie
     * is not sponsoring gas. Unknown cache is optimistic (false) until a balance read lands.
     */
    boolean shortOnGas(Profile profile) {
        if (profile == null || profile.wallet == null || profile.wallet.isBlank()) {
            return false;
        }
        if (plugin.cookies() != null && plugin.cookies().active(profile)) {
            return false;
        }
        BigInteger bal = cachedBalance(profile.wallet);
        if (bal == null) {
            return false;
        }
        return bal.compareTo(gasFeeCap()) < 0;
    }

    List<Type> viewRealm(Function function) throws IOException {
        String hex = call("0x0000000000000000000000000000000000000000", realmContract(), function);
        return FunctionReturnDecoder.decode(hex, function.getOutputParameters());
    }

    /** Value-carrying SkyRealm write. Somnia cold storage costs ~1M gas per slot, so the cap is 8M. */
    TransactionReceipt sendRealm(Profile profile, Function function, BigInteger value) throws Exception {
        return sendTo(profile, realmContract(), function, value);
    }

    /** SkyBin (Buy-It-Now) contract address, or blank when not deployed. */
    String binContract() {
        String value = plugin.getConfig().getString("chain.bin-contract", "");
        return value == null ? "" : value.trim();
    }

    List<Type> viewAt(String to, Function function) throws IOException {
        String hex = call("0x0000000000000000000000000000000000000000", to, function);
        return FunctionReturnDecoder.decode(hex, function.getOutputParameters());
    }

    /** Value-carrying write to any game contract, signed by the player's own purse (same lanes and checks as SkyRealm). */
    TransactionReceipt sendTo(Profile profile, String to, Function function, BigInteger value) throws Exception {
        String hash = submitTo(profile, to, function, value);
        TransactionReceipt receipt = waitReceipt(hash, 120);
        if (receipt == null) {
            throw new IllegalStateException("No receipt yet for " + hash + ".");
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("Shannon reverted " + function.getName() + " (" + hash + ").");
        }
        return receipt;
    }

    /** Polls for a receipt (250 ms steps). Returns null when none arrived; the caller checks the status. */
    TransactionReceipt waitReceipt(String hash, int attempts) throws Exception {
        for (int attempt = 0; attempt < attempts; attempt++) {
            var found = web3.ethGetTransactionReceipt(hash).send().getTransactionReceipt();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(250L);
        }
        return null;
    }

    /** Signs and broadcasts; returns the hash without waiting. */
    String submitTo(Profile profile, String to, Function function, BigInteger value) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        if (to == null || to.isBlank()) {
            throw new IllegalStateException("That contract is not configured.");
        }
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
        String data = attest(from, to, FunctionEncoder.encode(function), value);
        String fromKey = from.toLowerCase(Locale.ROOT);
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        EthEstimateGas estimated = web3.ethEstimateGas(new Transaction(from, null, null, null, to, value, data)).send();
        if (estimated.hasError()) {
            throw new IllegalStateException(trim(estimated.getError().getMessage()));
        }
        BigInteger gas = estimated.getAmountUsed();
        if (gas == null || gas.signum() <= 0) {
            throw new IllegalStateException("Shannon could not estimate that transaction.");
        }
        gas = gas.multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN);
        if (gas.compareTo(REALM_GAS_CAP) > 0) {
            gas = REALM_GAS_CAP;
        }
        BigInteger balance = readBalance(from);
        BigInteger need = value.add(gas.multiply(maxFee));
        noteFee(gas.multiply(maxFee));
        balance = sponsored(profile, from, balance, need, gas.multiply(maxFee));
        if (balance.compareTo(need) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT, this needs up to " + format(need) + " STT. /deposit shows your purse.");
        }
        Lane lane = lanes.computeIfAbsent(fromKey, ignored -> new Lane());
        String hash;
        synchronized (lane.lock) {
            lane.next = nextNonce(lane, from);
            RawTransaction transaction = RawTransaction.createTransaction(chainId, lane.next, gas, to, value, data, priority, maxFee);
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError() && isNonceError(sent.getError().getMessage())) {
                // Another path (deed, item mint, withdraw) used this purse; resync from the pending count once.
                lane.next = null;
                lane.next = nextNonce(lane, from);
                transaction = RawTransaction.createTransaction(chainId, lane.next, gas, to, value, data, priority, maxFee);
                signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
                sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            }
            if (sent.hasError()) {
                String reason = sent.getError().getMessage();
                if (isNonceError(reason)) {
                    lane.next = null;
                }
                throw new IllegalStateException(trim(reason));
            }
            lane.next = lane.next.add(BigInteger.ONE);
            hash = sent.getTransactionHash();
        }
        EventTally.tx(profile, to, null);
        plugin.getLogger().info("Realm sent " + hash + " " + function.getName() + " from " + from);
        return hash;
    }

    // ===== SkySkills (skill XP + collections; chain is the source of truth) =====

    /** Contract creation signed by a logged-in player's purse. Returns {address, hash}. */
    String[] deployFrom(Profile profile, String bytecodeHex) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        String data = bytecodeHex.startsWith("0x") ? bytecodeHex : "0x" + bytecodeHex;
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        EthEstimateGas estimated = web3.ethEstimateGas(Transaction.createContractTransaction(from, null, null, data)).send();
        if (estimated.hasError()) {
            throw new IllegalStateException(trim(estimated.getError().getMessage()));
        }
        BigInteger gas = estimated.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        if (gas.compareTo(BigInteger.valueOf(30_000_000L)) > 0) {
            throw new IllegalStateException("Deploy needs " + gas + " gas.");
        }
        BigInteger balance = readBalance(from);
        noteFee(gas.multiply(maxFee));
        balance = sponsored(profile, from, balance, gas.multiply(maxFee), gas.multiply(maxFee));
        if (balance.compareTo(gas.multiply(maxFee)) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT, the deploy needs up to " + format(gas.multiply(maxFee)) + " STT.");
        }
        Lane lane = lanes.computeIfAbsent(from.toLowerCase(Locale.ROOT), ignored -> new Lane());
        String hash;
        synchronized (lane.lock) {
            lane.next = nextNonce(lane, from);
            RawTransaction transaction = RawTransaction.createTransaction(chainId, lane.next, gas, "", BigInteger.ZERO, data, priority, maxFee);
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                lane.next = null;
                throw new IllegalStateException(trim(sent.getError().getMessage()));
            }
            lane.next = lane.next.add(BigInteger.ONE);
            hash = sent.getTransactionHash();
        }
        EventTally.tx(profile, null, "deploy");
        TransactionReceipt receipt = waitReceipt(hash, 240);
        if (receipt == null || !receipt.isStatusOK() || receipt.getContractAddress() == null) {
            throw new IllegalStateException("Deploy " + hash + " did not land with status 1.");
        }
        return new String[]{receipt.getContractAddress(), hash};
    }


    /** SkySkills2.resetProfile(): starts a new profile epoch for msg.sender (skills and collections read 0). */
    static Function resetProfile() {
        return new Function("resetProfile", List.of(), Collections.emptyList());
    }

    /** SkyProfile.resetProfile(island): the solo deed holder wipes the whole island profile. */
    static Function resetIslandProfile(long island) {
        return new Function("resetProfile", List.of(new Uint256(BigInteger.valueOf(island))), Collections.emptyList());
    }

    /** SkyProfile.resetSelf(island): wipes only the caller's own record on their island. */
    static Function resetSelf(long island) {
        return new Function("resetSelf", List.of(new Uint256(BigInteger.valueOf(island))), Collections.emptyList());
    }

    /** SkyProfile.applyXp(island, skills, amounts, collectionIds, collectionAmounts, taskXp). */
    static Function applyXp(long island, int[] skills, long[] amounts, int[] cols, long[] colAmts, long task) {
        List<Uint8> s = new ArrayList<>();
        List<Uint32> a = new ArrayList<>();
        List<Uint16> c = new ArrayList<>();
        List<Uint32> ca = new ArrayList<>();
        for (int i = 0; i < skills.length; i++) {
            s.add(new Uint8(BigInteger.valueOf(skills[i])));
            a.add(new Uint32(BigInteger.valueOf(amounts[i])));
        }
        for (int i = 0; i < cols.length; i++) {
            c.add(new Uint16(BigInteger.valueOf(cols[i])));
            ca.add(new Uint32(BigInteger.valueOf(colAmts[i])));
        }
        return new Function("applyXp", List.of(
                new Uint256(BigInteger.valueOf(island)),
                new DynamicArray<>(Uint8.class, s),
                new DynamicArray<>(Uint32.class, a),
                new DynamicArray<>(Uint16.class, c),
                new DynamicArray<>(Uint32.class, ca),
                new Uint32(BigInteger.valueOf(task))
        ), Collections.emptyList());
    }

    /** One SkyProfile read: era, the member's skills and task XP, the island's shared collections, contract SkyBlock XP. */
    record ProfileView(long epoch, long[] skills, long task, long[] collections, long sbXp) {
    }

    /** SkyProfile.profileOf(island, member, n). Collections padded to SkillChain.COLLECTIONS. */
    ProfileView profileOf(String contract, long island, String member, int n) throws IOException {
        Function function = new Function("profileOf", List.of(new Uint256(BigInteger.valueOf(island)), new Address(member),
                new Uint16(BigInteger.valueOf(n))), Collections.emptyList());
        byte[] data = Numeric.hexStringToByteArray(call(member, contract, function));
        int head = 13 * 32;
        if (data.length < head + 32) {
            throw new IOException("Shannon returned a short profile view.");
        }
        java.util.function.IntFunction<BigInteger> word = i -> new BigInteger(1, Arrays.copyOfRange(data, i * 32, i * 32 + 32));
        long epoch = word.apply(0).longValue();
        long[] skills = new long[SkillChain.SKILLS];
        for (int i = 0; i < SkillChain.SKILLS; i++) {
            skills[i] = word.apply(1 + i).longValue();
        }
        long task = word.apply(10).longValue();
        int offset = word.apply(11).intValue();
        long sbXp = word.apply(12).longValue();
        if (offset + 32 > data.length) {
            throw new IOException("Shannon returned a broken profile view.");
        }
        int length = new BigInteger(1, Arrays.copyOfRange(data, offset, offset + 32)).intValue();
        if (length != n || offset + 32 + 32 * n > data.length) {
            throw new IOException("Shannon returned a broken profile view.");
        }
        long[] cols = new long[SkillChain.COLLECTIONS];
        for (int i = 0; i < n && i < cols.length; i++) {
            int start = offset + 32 + i * 32;
            cols[i] = new BigInteger(1, Arrays.copyOfRange(data, start, start + 32)).longValue();
        }
        return new ProfileView(epoch, skills, task, cols, sbXp);
    }

    static Function applyXp(int[] skills, long[] amounts, int[] cols, long[] colAmts) {
        List<Uint8> s = new ArrayList<>();
        List<Uint32> a = new ArrayList<>();
        List<Uint16> c = new ArrayList<>();
        List<Uint32> ca = new ArrayList<>();
        for (int i = 0; i < skills.length; i++) {
            s.add(new Uint8(BigInteger.valueOf(skills[i])));
            a.add(new Uint32(BigInteger.valueOf(amounts[i])));
        }
        for (int i = 0; i < cols.length; i++) {
            c.add(new Uint16(BigInteger.valueOf(cols[i])));
            ca.add(new Uint32(BigInteger.valueOf(colAmts[i])));
        }
        return new Function("applyXp", List.of(
                new DynamicArray<>(Uint8.class, s),
                new DynamicArray<>(Uint32.class, a),
                new DynamicArray<>(Uint16.class, c),
                new DynamicArray<>(Uint32.class, ca)
        ), Collections.emptyList());
    }

    /** xpOf(player): uint64[9], returned as nine static words. */
    long[] skillsOf(String contract, String player) throws IOException {
        Function function = new Function("xpOf", List.of(new Address(player)), Collections.emptyList());
        byte[] data = Numeric.hexStringToByteArray(call(player, contract, function));
        if (data.length < 32 * SkillChain.SKILLS) {
            throw new IOException("Shannon returned a short skills view.");
        }
        long[] out = new long[SkillChain.SKILLS];
        for (int i = 0; i < SkillChain.SKILLS; i++) {
            out[i] = new BigInteger(1, Arrays.copyOfRange(data, i * 32, i * 32 + 32)).longValue();
        }
        return out;
    }

    /** collectionsOf(player, [0..n-1]) padded to SkillChain.COLLECTIONS. */
    long[] collectionsOf(String contract, String player, int n) throws IOException {
        List<Uint16> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(new Uint16(BigInteger.valueOf(i)));
        }
        Function function = new Function("collectionsOf", List.of(new Address(player), new DynamicArray<>(Uint16.class, ids)), Collections.emptyList());
        byte[] data = Numeric.hexStringToByteArray(call(player, contract, function));
        if (data.length < 64) {
            throw new IOException("Shannon returned a short collections view.");
        }
        int offset = new BigInteger(1, Arrays.copyOfRange(data, 0, 32)).intValue();
        int length = new BigInteger(1, Arrays.copyOfRange(data, offset, offset + 32)).intValue();
        if (length != n || offset + 32 + 32 * n > data.length) {
            throw new IOException("Shannon returned a broken collections view.");
        }
        long[] out = new long[SkillChain.COLLECTIONS];
        for (int i = 0; i < n && i < out.length; i++) {
            int start = offset + 32 + i * 32;
            out[i] = new BigInteger(1, Arrays.copyOfRange(data, start, start + 32)).longValue();
        }
        return out;
    }

    private static String format(BigInteger wei) {
        return Convert.fromWei(new BigDecimal(wei), Convert.Unit.ETHER).stripTrailingZeros().toPlainString();
    }

    // ===== Booster Cookie: gas sponsorship (Shannon) and the SOMI payment (mainnet) =====

    private volatile Credentials sponsor;

    /** The admin wallet (secrets.yml admin-key), loaded once; the key is never logged. Null when it is missing. */
    private Credentials sponsorCredentials() {
        Credentials made = sponsor;
        if (made != null) {
            return made;
        }
        String key = YamlConfiguration.loadConfiguration(secretsFile).getString("admin-key", "");
        if (key == null || !key.trim().matches("(?i)(0x)?[0-9a-f]{64}")) {
            plugin.getLogger().warning("Gas sponsor: secrets.yml has no admin-key; Booster Cookie gas is not paid.");
            return null;
        }
        made = Credentials.create(key.trim());
        sponsor = made;
        plugin.getLogger().info("Gas sponsor wallet " + made.getAddress());
        return made;
    }

    /**
     * Booster Cookie holders: when the purse is short only by gas, the admin wallet sends the missing STT first
     * (no cap, Shiny's choice). The value of a write (bids, fees) is never sponsored, and neither is /withdraw, so the
     * float cannot be cashed out for free. Returns the balance to use.
     */
    private BigInteger sponsored(Profile profile, String from, BigInteger balance, BigInteger need, BigInteger gasPart) {
        if (balance.compareTo(need) >= 0 || plugin.cookies() == null || !plugin.cookies().active(profile)) {
            return balance;
        }
        BigInteger missing = need.subtract(balance);
        if (missing.compareTo(gasPart) > 0) {
            return balance;
        }
        // Top up a small float (cookie.sponsor-topup-stt, default 0.02) so a run of bag writes needs one top-up, not one each.
        BigInteger floatWei;
        try {
            floatWei = Convert.toWei(new BigDecimal(plugin.getConfig().getString("cookie.sponsor-topup-stt", "0.02").trim()), Convert.Unit.ETHER).toBigInteger();
        } catch (Exception exception) {
            floatWei = new BigInteger("20000000000000000");
        }
        if (missing.compareTo(floatWei) < 0) {
            missing = floatWei;
        }
        try {
            Credentials admin = sponsorCredentials();
            if (admin == null) {
                return balance;
            }
            String payer = admin.getAddress();
            BigInteger gas = transferGas(payer, from, missing);
            BigInteger maxFee = maxFee();
            Lane lane = lanes.computeIfAbsent(payer.toLowerCase(Locale.ROOT), ignored -> new Lane());
            String hash;
            synchronized (lane.lock) {
                BigInteger nonce = nextNonce(lane, payer);
                RawTransaction transaction = RawTransaction.createEtherTransaction(chainId, nonce, gas, from, missing, PRIORITY, maxFee);
                EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(TransactionEncoder.signMessage(transaction, chainId, admin))).send();
                if (sent.hasError()) {
                    if (isNonceError(sent.getError().getMessage())) {
                        lane.next = null;
                    }
                    throw new IllegalStateException(trim(sent.getError().getMessage()));
                }
                lane.next = nonce.add(BigInteger.ONE);
                hash = sent.getTransactionHash();
            }
            TransactionReceipt receipt = waitReceipt(hash, 120);
            if (receipt == null || !receipt.isStatusOK()) {
                throw new IllegalStateException("top-up " + hash + " did not land");
            }
            plugin.getLogger().info("Gas sponsor: " + format(missing) + " STT to " + from + " (Booster Cookie) " + hash);
            return readBalance(from);
        } catch (Exception exception) {
            plugin.getLogger().warning("Gas sponsor failed for " + from + ": " + exception.getMessage());
            return balance;
        }
    }

    /** Player text for a purse that cannot cover a mainnet payment plus a plain transfer's network fee. */
    static String shortSomi(BigInteger balance, BigInteger least) {
        BigDecimal have = Convert.fromWei(new BigDecimal(balance == null ? BigInteger.ZERO : balance), Convert.Unit.ETHER)
                .setScale(4, java.math.RoundingMode.DOWN).stripTrailingZeros();
        BigDecimal need = Convert.fromWei(new BigDecimal(least), Convert.Unit.ETHER)
                .setScale(4, java.math.RoundingMode.CEILING).stripTrailingZeros();
        return "not enough SOMI. Your purse on Somnia mainnet has " + have.toPlainString() + " SOMI and this needs about "
                + need.toPlainString() + " SOMI (price plus network fee). /cookie address shows where to send SOMI.";
    }

    /** A plain SOMI transfer on Somnia mainnet signed by the player's own purse key (same address as on Shannon). */
    String mainnetPay(Profile profile, Web3j main, long mainChainId, String to, BigInteger value) throws Exception {
        return mainnetPay(profile, main, mainChainId, to, value, null);
    }

    /**
     * Same as above; {@code beforeSend} gets the transaction hash (computed from the signed bytes) before it is
     * broadcast, so a crash or restart between sending and recording cannot lose a paid purchase.
     */
    String mainnetPay(Profile profile, Web3j main, long mainChainId, String to, BigInteger value,
                      java.util.function.Consumer<String> beforeSend) throws Exception {
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
        // Balance first: with too little SOMI the node's estimate only answers "execution reverted".
        BigInteger price = main.ethGasPrice().send().getGasPrice();
        if (price == null || price.compareTo(FEE_FLOOR) < 0) {
            price = FEE_FLOOR;
        }
        BigInteger balance = main.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger least = value.add(BigInteger.valueOf(25_200L).multiply(price)); // 21000 gas * 1.2
        if (balance == null || balance.compareTo(least) < 0) {
            throw new IllegalStateException(shortSomi(balance, least));
        }
        EthEstimateGas estimated = main.ethEstimateGas(new Transaction(from, null, null, null, to, value, null)).send();
        if (estimated.hasError() || estimated.getAmountUsed() == null || estimated.getAmountUsed().signum() <= 0) {
            String why = estimated.hasError() ? trim(estimated.getError().getMessage()) : "no estimate";
            String low = why.toLowerCase(Locale.ROOT);
            if (low.contains("insufficient") || low.contains("reverted")) {
                throw new IllegalStateException(shortSomi(balance, least));
            }
            throw new IllegalStateException(why + ".");
        }
        BigInteger gas = estimated.getAmountUsed().multiply(BigInteger.valueOf(12)).divide(BigInteger.TEN);
        BigInteger need = value.add(gas.multiply(price));
        if (balance.compareTo(need) < 0) {
            throw new IllegalStateException("your mainnet purse has " + format(balance) + " SOMI, this needs " + format(value)
                    + " SOMI plus up to " + format(gas.multiply(price)) + " SOMI network fee. /cookie address shows where to send SOMI.");
        }
        BigInteger nonce = main.ethGetTransactionCount(from, DefaultBlockParameterName.PENDING).send().getTransactionCount();
        RawTransaction transaction = RawTransaction.createEtherTransaction(nonce, price, gas, to, value);
        byte[] signed = TransactionEncoder.signMessage(transaction, mainChainId, credentials);
        if (beforeSend != null) {
            beforeSend.accept(Numeric.toHexString(org.web3j.crypto.Hash.sha3(signed)));
        }
        EthSendTransaction sent = main.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError()) {
            throw new IllegalStateException(trim(sent.getError().getMessage()));
        }
        plugin.getLogger().info("Cookie payment sent on mainnet " + sent.getTransactionHash() + " from " + from + " to " + to + " value " + format(value));
        return sent.getTransactionHash();
    }
}
