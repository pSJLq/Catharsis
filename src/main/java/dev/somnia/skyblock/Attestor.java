package dev.somnia.skyblock;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Server attestations (EIP-712) for game writes, verified onchain by Attested.sol. Two modes: valuable writes
 * are always attested; routine writes (GUARDED) only once the player's key has been exported (SkyKeys).
 * The player's game wallet still sends the transaction and pays the gas; this only appends 76 bytes:
 * nonce (uint64) | deadline (uint32) | r | yParityAndS (EIP-2098). Signing is local (no RPC, no server
 * nonce). Nonces are per player and one-shot (bitmap onchain), seeded from the clock at startup so a
 * restart never reuses one. The signing key comes from secrets.yml (signer-key) and is never logged.
 */
final class Attestor {
    private static final byte[] TYPEHASH = Hash.sha3(
            "Attest(address player,address target,bytes32 call,uint256 value,uint256 nonce,uint256 deadline)".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] DOMAIN_TYPEHASH = Hash.sha3(
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] NAME = Hash.sha3("Catharsis".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] VERSION = Hash.sha3("1".getBytes(StandardCharsets.US_ASCII));
    static final int TTL_SECONDS = 300;

    /** Functions that can only lower the caller's own state or pull their own funds: sent without an attestation. */
    private static final Set<String> FREE = Set.of(
            selector("cast(uint8)"),
            selector("resetProfile()"),
            selector("resetProfile(uint256)"),
            selector("resetSelf(uint256)"),
            selector("cancelBuyOrder(uint256)"),
            selector("settle(uint256)"),
            selector("cashIn(uint256)"),
            selector("withdrawPending()"),
            selector("optIn()")
    );

    /**
     * Routine writes that the contracts check only for players whose key has been exported (mode B, SkyKeys).
     * In mode A the key never leaves the server, so these go without an attestation at normal gas.
     */
    private static final Set<String> GUARDED = Set.of(
            selector("write(uint8[],uint256[])"),
            selector("applyXp(uint8[],uint32[],uint16[],uint32[])"),
            selector("applyXp(uint256,uint8[],uint32[],uint16[],uint32[],uint32)"),
            selector("checkpoint(uint256,uint16)"),
            selector("applyBatch(uint32,uint32,uint32)"),
            selector("craft(uint8)"),
            selector("sync(uint8[],uint32[],uint16[],uint16[],uint256[],string[],string[],string[],string[],uint256,uint8[],uint32[],uint16[],uint16[],uint256[],string[],string[],string[],string[])")
    );
    /** SkyHold2.MINT (type(uint256).max) as it appears in calldata: a sync that mints gear is attested in both modes. */
    private static final String MINT_WORD = "f".repeat(64);

    /** True for a routine write that needs an attestation only in mode B (a gear-minting sync always needs one). */
    static boolean guarded(String data) {
        if (data == null || data.length() < 10 || !GUARDED.contains(data.substring(0, 10).toLowerCase(Locale.ROOT))) {
            return false;
        }
        return !data.substring(10).toLowerCase(Locale.ROOT).contains(MINT_WORD);
    }

    private final ECKeyPair key;
    private final String address;
    private final long chainId;
    private final Logger log;
    private final Map<String, AtomicLong> nonces = new ConcurrentHashMap<>();
    private final Map<String, byte[]> domains = new ConcurrentHashMap<>();
    private final long seed;
    private final AtomicLong signed = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private volatile long reportedAt;

    Attestor(String privateKeyHex, long chainId, Logger log) {
        this.key = ECKeyPair.create(Numeric.toBigInt(privateKeyHex));
        this.address = Keys.toChecksumAddress(Keys.getAddress(key.getPublicKey()));
        this.chainId = chainId;
        this.log = log;
        // Aligned to a 256-nonce bitmap word; a later restart always starts above any nonce used before.
        this.seed = (System.currentTimeMillis() << 12);
        this.reportedAt = System.currentTimeMillis();
    }

    String address() {
        return address;
    }

    static String selector(String signature) {
        return Numeric.toHexString(Hash.sha3(signature.getBytes(StandardCharsets.US_ASCII))).substring(0, 10).toLowerCase(Locale.ROOT);
    }

    static boolean free(String data) {
        return data != null && data.length() >= 10 && FREE.contains(data.substring(0, 10).toLowerCase(Locale.ROOT));
    }

    /** Calldata plus the attestation trailer for exactly this player, contract, call and value. */
    String wrap(String player, String target, String data, BigInteger value) {
        if (free(data)) {
            return data;
        }
        long started = System.nanoTime();
        String playerKey = player.toLowerCase(Locale.ROOT);
        long nonce = nonces.computeIfAbsent(playerKey, ignored -> new AtomicLong(seed)).getAndIncrement();
        long deadline = System.currentTimeMillis() / 1000L + TTL_SECONDS;
        byte[] call = Hash.sha3(Numeric.hexStringToByteArray(data));
        byte[] struct = Hash.sha3(concat(
                TYPEHASH,
                word(Numeric.toBigInt(player)),
                word(Numeric.toBigInt(target)),
                call,
                word(value == null ? BigInteger.ZERO : value),
                word(BigInteger.valueOf(nonce)),
                word(BigInteger.valueOf(deadline))
        ));
        byte[] domain = domains.computeIfAbsent(target.toLowerCase(Locale.ROOT), t -> Hash.sha3(concat(
                DOMAIN_TYPEHASH, NAME, VERSION, word(BigInteger.valueOf(chainId)), word(Numeric.toBigInt(t)))));
        byte[] digest = Hash.sha3(concat(new byte[]{0x19, 0x01}, domain, struct));
        Sign.SignatureData sig = Sign.signMessage(digest, key, false);
        int v = sig.getV()[0] & 0xff;
        byte[] vs = sig.getS().clone();
        if (v == 28) {
            vs[0] = (byte) (vs[0] | 0x80);
        }
        ByteBuffer trailer = ByteBuffer.allocate(76);
        trailer.putLong(nonce);
        trailer.putInt((int) deadline);
        trailer.put(sig.getR());
        trailer.put(vs);
        long took = System.nanoTime() - started;
        nanos.addAndGet(took);
        long count = signed.incrementAndGet();
        long now = System.currentTimeMillis();
        if (now - reportedAt > 600_000L) {
            reportedAt = now;
            log.info(String.format(Locale.ROOT, "Attest: %d signed, avg %.3f ms", count, nanos.get() / 1e6 / count));
        }
        return data + Numeric.toHexStringNoPrefix(trailer.array());
    }

    /** Startup self-test: signs dummy attestations (nonces never sent) and returns the average milliseconds. */
    double benchmark(int rounds) {
        String player = "0x000000000000000000000000000000000000beef";
        String target = "0x000000000000000000000000000000000000cafe";
        String data = "0x846c7853" + "00".repeat(320);
        long started = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            wrap(player, target, data, BigInteger.ZERO);
        }
        double avg = (System.nanoTime() - started) / 1e6 / rounds;
        nonces.remove(player);
        signed.addAndGet(-rounds);
        nanos.set(0);
        return avg;
    }

    /** Average signing time so far in milliseconds, and the count. */
    double averageMs() {
        long count = signed.get();
        return count == 0 ? 0 : nanos.get() / 1e6 / count;
    }

    long count() {
        return signed.get();
    }

    private static byte[] word(BigInteger value) {
        return Numeric.toBytesPadded(value, 32);
    }

    private static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] out = new byte[size];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
