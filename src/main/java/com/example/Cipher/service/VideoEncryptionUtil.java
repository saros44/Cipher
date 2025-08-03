package com.example.Cipher.service;

import java.util.Base64;

public class VideoEncryptionUtil {

    /**
     * Custom encryption using XOR cipher with key rotation
     */
    public static String encrypt(String data, String key) {
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("Data cannot be null or empty");
        }
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Key must be at least 8 characters long");
        }

        byte[] dataBytes = data.getBytes();
        byte[] keyBytes = key.getBytes();
        byte[] encrypted = new byte[dataBytes.length];

        // XOR encryption with key rotation and bit shifting
        for (int i = 0; i < dataBytes.length; i++) {
            int keyIndex = i % keyBytes.length;
            int keyByte = keyBytes[keyIndex];

            // Add some complexity: rotate key byte based on position
            keyByte = ((keyByte << (i % 3)) | (keyByte >> (8 - (i % 3)))) & 0xFF;

            // XOR with rotated key
            encrypted[i] = (byte) (dataBytes[i] ^ keyByte);

            // Add position-based scrambling
            encrypted[i] = (byte) (encrypted[i] ^ (i % 256));
        }

        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * Custom decryption using XOR cipher with key rotation
     */
    public static String decrypt(String encryptedData, String key) {
        try {
            if (encryptedData == null || encryptedData.trim().isEmpty()) {
                throw new IllegalArgumentException("Encrypted data is null or empty");
            }
            if (key == null || key.length() < 8) {
                throw new IllegalArgumentException("Key must be at least 8 characters long");
            }

            // Clean the input - remove any whitespace
            String cleanedData = encryptedData.trim();

            // Validate Base64 format
            if (!isValidBase64(cleanedData)) {
                throw new IllegalArgumentException("Invalid Base64 format - message may be corrupted during extraction. " +
                    "Expected length should be multiple of 4, got: " + cleanedData.length() +
                    ". Data: '" + cleanedData + "'");
            }

            byte[] encryptedBytes = Base64.getDecoder().decode(cleanedData);
            byte[] keyBytes = key.getBytes();
            byte[] decrypted = new byte[encryptedBytes.length];

            // XOR decryption with key rotation (reverse of encryption)
            for (int i = 0; i < encryptedBytes.length; i++) {
                int keyIndex = i % keyBytes.length;
                int keyByte = keyBytes[keyIndex];

                // Apply same key rotation as in encryption
                keyByte = ((keyByte << (i % 3)) | (keyByte >> (8 - (i % 3)))) & 0xFF;

                // Remove position-based scrambling first
                byte unscrambled = (byte) (encryptedBytes[i] ^ (i % 256));

                // XOR with rotated key to decrypt
                decrypted[i] = (byte) (unscrambled ^ keyByte);
            }

            return new String(decrypted);

        } catch (IllegalArgumentException e) {
            if (e.getMessage().contains("Last unit does not have enough valid bits")) {
                throw new IllegalArgumentException("Invalid Base64 format - message may be corrupted during extraction. " +
                    "Expected length should be multiple of 4, got: " + encryptedData.length() +
                    ". Data: '" + encryptedData + "'");
            }
            throw new IllegalArgumentException("Decryption failed: " + e.getMessage() +
                ". Encrypted data: '" + encryptedData + "'");
        } catch (Exception e) {
            throw new RuntimeException("Unexpected error during decryption: " + e.getMessage(), e);
        }
    }

    static boolean isValidBase64(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        // Check length (must be multiple of 4 for valid Base64)
        if (str.length() % 4 != 0) {
            return false;
        }

        // Check if string contains only valid Base64 characters
        return str.matches("^[A-Za-z0-9+/]*={0,2}$");
    }
}

