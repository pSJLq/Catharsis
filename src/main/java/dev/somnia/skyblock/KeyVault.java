package dev.somnia.skyblock;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Wallet key sealing. Player blobs: Argon2id(password, random 16-byte salt) -> 32-byte key -> AES-256-GCM
 * (random 12-byte nonce, AAD binds the blob to the profile and address). Format:
 * {@code a2id$m=<KiB>,t=<iterations>,p=<lanes>$<salt>$<nonce>$<ciphertext+tag>} (base64url, no padding).
 * Only ciphertext, salt, nonce and KDF params are stored; a wrong password fails the GCM tag.
 * Migration blobs ({@code mk$<nonce>$<ct>}) use a random server key that is deleted when no migration is pending.
 */
final class KeyVault {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    record Params(int memoryKb, int iterations, int lanes) {
    }

    private KeyVault() {
    }

    static byte[] random(int size) {
        byte[] out = new byte[size];
        RANDOM.nextBytes(out);
        return out;
    }

    static byte[] derive(String password, byte[] salt, Params p) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(p.memoryKb())
                .withIterations(p.iterations())
                .withParallelism(p.lanes())
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[32];
        try {
            gen.generateBytes(pw, out);
        } finally {
            Arrays.fill(pw, (byte) 0);
        }
        return out;
    }

    static String seal(byte[] secret, String password, Params p, byte[] aad) throws GeneralSecurityException {
        byte[] salt = random(16);
        byte[] key = derive(password, salt, p);
        try {
            byte[] nonce = random(12);
            byte[] ct = gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, secret);
            return "a2id$m=" + p.memoryKb() + ",t=" + p.iterations() + ",p=" + p.lanes() + "$" + ENC.encodeToString(salt) + "$"
                    + ENC.encodeToString(nonce) + "$" + ENC.encodeToString(ct);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Opens a player blob; throws (AEADBadTagException) when the password is wrong. */
    static byte[] open(String blob, String password, byte[] aad) throws GeneralSecurityException {
        String[] parts = blob.split("\\$");
        if (parts.length != 5 || !parts[0].equals("a2id")) {
            throw new GeneralSecurityException("Unknown key format");
        }
        int m = 0, t = 0, l = 0;
        for (String kv : parts[1].split(",")) {
            String[] e = kv.split("=", 2);
            int v = Integer.parseInt(e[1]);
            switch (e[0]) {
                case "m" -> m = v;
                case "t" -> t = v;
                case "p" -> l = v;
                default -> {
                }
            }
        }
        byte[] key = derive(password, DEC.decode(parts[2]), new Params(m, t, l));
        try {
            return gcm(Cipher.DECRYPT_MODE, key, DEC.decode(parts[3]), aad, DEC.decode(parts[4]));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static String sealRaw(byte[] secret, byte[] key, byte[] aad) throws GeneralSecurityException {
        byte[] nonce = random(12);
        return "mk$" + ENC.encodeToString(nonce) + "$" + ENC.encodeToString(gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, secret));
    }

    static byte[] openRaw(String blob, byte[] key, byte[] aad) throws GeneralSecurityException {
        String[] parts = blob.split("\\$");
        if (parts.length != 3 || !parts[0].equals("mk")) {
            throw new GeneralSecurityException("Unknown pending format");
        }
        return gcm(Cipher.DECRYPT_MODE, key, DEC.decode(parts[1]), aad, DEC.decode(parts[2]));
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] data) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }

    static byte[] aad(java.util.UUID id, String wallet) {
        return ("somnia-skyblock|" + id + "|" + (wallet == null ? "" : wallet.toLowerCase(java.util.Locale.ROOT))).getBytes(StandardCharsets.UTF_8);
    }
}
