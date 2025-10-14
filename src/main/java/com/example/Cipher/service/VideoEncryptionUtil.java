package com.example.Cipher.service;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class VideoEncryptionUtil {

    // New scheme constants
    private static final byte[] MAGIC = new byte[]{'A', 'E', 'G', '1'}; // AES-GCM v1
    private static final int SALT_LEN = 16; // 128-bit salt for PBKDF2
    private static final int IV_LEN = 12;   // 96-bit IV recommended for GCM
    private static final int PBKDF2_ITER = 100_000;
    private static final int KEY_LEN_BITS = 256;

    /**
     * Strong encryption using AES-GCM with PBKDF2 key derivation.
     * Output format (Base64 of bytes): MAGIC(4) | SALT(16) | IV(12) | CIPHERTEXT+TAG
     */
    public static String encrypt(String data, String key) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Key must be at least 8 characters long");
        }
        try {
            byte[] plaintext = data.getBytes(StandardCharsets.UTF_8);

            byte[] salt = new byte[SALT_LEN];
            byte[] iv = new byte[IV_LEN];
            SecureRandom sr = new SecureRandom();
            sr.nextBytes(salt);
            sr.nextBytes(iv);

            SecretKeySpec aesKey = deriveKey(key, salt);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, gcmSpec);
            byte[] ct = cipher.doFinal(plaintext);

            byte[] out = new byte[MAGIC.length + SALT_LEN + IV_LEN + ct.length];
            int pos = 0;
            System.arraycopy(MAGIC, 0, out, pos, MAGIC.length);
            pos += MAGIC.length;
            System.arraycopy(salt, 0, out, pos, SALT_LEN);
            pos += SALT_LEN;
            System.arraycopy(iv, 0, out, pos, IV_LEN);
            pos += IV_LEN;
            System.arraycopy(ct, 0, out, pos, ct.length);

            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decryption supporting AES-GCM (preferred) and falling back to legacy XOR for backward compatibility.
     */
    public static String decrypt(String encryptedData, String key) {
        try {
            if (encryptedData == null || encryptedData.trim().isEmpty()) {
                throw new IllegalArgumentException("Encrypted data is null or empty");
            }
            if (key == null || key.length() < 8) {
                throw new IllegalArgumentException("Key must be at least 8 characters long");
            }

            String cleanedData = encryptedData.trim();

            if (!isValidBase64(cleanedData)) {
                throw new IllegalArgumentException("Invalid Base64 format - message may be corrupted during extraction. " +
                        "Expected length should be multiple of 4, got: " + cleanedData.length() +
                        ". Data: '" + cleanedData + "'");
            }

            byte[] all = Base64.getDecoder().decode(cleanedData);

            // Check for new format magic header
            if (all.length >= MAGIC.length + SALT_LEN + IV_LEN &&
                    Arrays.equals(Arrays.copyOfRange(all, 0, MAGIC.length), MAGIC)) {
                byte[] salt = Arrays.copyOfRange(all, MAGIC.length, MAGIC.length + SALT_LEN);
                byte[] iv = Arrays.copyOfRange(all, MAGIC.length + SALT_LEN, MAGIC.length + SALT_LEN + IV_LEN);
                byte[] ct = Arrays.copyOfRange(all, MAGIC.length + SALT_LEN + IV_LEN, all.length);

                SecretKeySpec aesKey = deriveKey(key, salt);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
                cipher.init(Cipher.DECRYPT_MODE, aesKey, gcmSpec);
                byte[] pt = cipher.doFinal(ct);
                return new String(pt, StandardCharsets.UTF_8);
            }

            // Legacy fallback: XOR scheme (for previously encoded assets)
            return legacyXorDecrypt(all, key);

        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().contains("Last unit does not have enough valid bits")) {
                throw new IllegalArgumentException("Invalid Base64 format - message may be corrupted during extraction. " +
                        "Expected length should be multiple of 4, got: " + encryptedData.length() +
                        ". Data: '" + encryptedData + "'");
            }
            throw new IllegalArgumentException("Decryption failed: " + e.getMessage() +
                    ". Encrypted data: '" + encryptedData + "'");
        } catch (AEADBadTagException e) {
            // AES-GCM authentication failed — this typically means the key (or data) is wrong
            throw new IllegalArgumentException("Invalid key");
        } catch (BadPaddingException e) {
            // Some providers throw BadPaddingException for tag mismatch
            throw new IllegalArgumentException("Invalid key");
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error during decryption: " + e.getMessage(), e);
        }
    }

    private static SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITER, KEY_LEN_BITS);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = skf.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // ===== Legacy XOR methods (kept for backward compatibility with old videos) =====
    private static String legacyXorDecrypt(byte[] encryptedBytes, String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] decrypted = new byte[encryptedBytes.length];
        for (int i = 0; i < encryptedBytes.length; i++) {
            int keyIndex = i % keyBytes.length;
            int keyByte = keyBytes[keyIndex];
            keyByte = ((keyByte << (i % 3)) | (keyByte >> (8 - (i % 3)))) & 0xFF;
            byte unscrambled = (byte) (encryptedBytes[i] ^ (i % 256));
            decrypted[i] = (byte) (unscrambled ^ keyByte);
        }
        return new String(decrypted, StandardCharsets.UTF_8);
    }

    static boolean isValidBase64(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        if (str.length() % 4 != 0) {
            return false;
        }
        return str.matches("^[A-Za-z0-9+/]*={0,2}$");
    }
}