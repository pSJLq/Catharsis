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
    private final String deployerKey;
    private boolean deployerClaimed;
    private final Object sendLock = new Object();
    private final Map<String, Lane> lanes = new ConcurrentHashMap<>();
    private final Map<String, BigInteger> castGas = new ConcurrentHashMap<>();

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
        this.deployerKey = yaml.getString("deployer-key", "");
        this.deployerClaimed = yaml.getBoolean("deployer-claimed");
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

    /** A new purse key (32 bytes). The first one ever claims the deployer purse, as before. */
    byte[] newPurseKey() {
        if (!deployerClaimed && deployerKey != null && !deployerKey.isBlank()) {
            deployerClaimed = true;
            saveClaimed();
            return Numeric.toBytesPadded(Numeric.toBigInt(deployerKey), 32);
        }
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
        BigInteger wei = web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
        return format(wei);
    }

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
        BigInteger balance = web3.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        BigInteger fee = GAS_LIMIT.multiply(maxFee);
        if (balance.compareTo(value.add(fee)) < 0) {
            throw new IllegalStateException("Purse has " + format(balance) + " STT. This needs " + format(value) + " plus " + format(fee) + " STT for gas.");
        }
        // Same per-purse nonce lane as every contract write, so a withdraw never collides with bag, skill, or island txs.
        Lane lane = lanes.computeIfAbsent(credentials.getAddress().toLowerCase(Locale.ROOT), ignored -> new Lane());
        synchronized (lane.lock) {
            BigInteger nonce = nextNonce(lane, credentials.getAddress());
            RawTransaction transaction = RawTransaction.createEtherTransaction(chainId, nonce, GAS_LIMIT, to, value, priority, maxFee);
            byte[] signed = TransactionEncoder.signMessage(transaction, chainId, credentials);
            EthSendTransaction sent = web3.ethSendRawTransaction(Numeric.toHexString(signed)).send();
            if (sent.hasError()) {
                if (isNonceError(sent.getError().getMessage())) {
                    lane.next = null;
                }
                throw new IllegalStateException(sent.getError().getMessage());
            }
            lane.next = nonce.add(BigInteger.ONE);
            return sent.getTransactionHash();
        }
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

    private String submitPacked(Profile profile, String to, String data, boolean cast) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
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
        BigInteger balance = web3.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger fee = gas.multiply(maxFee);
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

    private String sendData(Profile profile, String to, String data, long gasLimit) throws Exception {
        if (!canSign(profile)) {
            throw new IllegalStateException("This player is not logged in.");
        }
        Credentials credentials = credentials(profile);
        BigInteger limit = estimateLimit(credentials.getAddress(), to, data, gasLimit);
        BigInteger maxFee = maxFee();
        BigInteger priority = PRIORITY;
        BigInteger balance = web3.ethGetBalance(credentials.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger fee = limit.multiply(maxFee);
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

    private void saveClaimed() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(secretsFile);
        yaml.set("deployer-claimed", true);
        try {
            yaml.save(secretsFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not mark the deployer purse as claimed: " + exception.getMessage());
        }
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
        return web3.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
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
        String data = FunctionEncoder.encode(function);
        Credentials credentials = credentials(profile);
        String from = credentials.getAddress();
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
        BigInteger balance = web3.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().getBalance();
        BigInteger need = value.add(gas.multiply(maxFee));
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
        BigInteger balance = web3.ethGetBalance(from, DefaultBlockParameterName.LATEST).send().getBalance();
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
}
