package com.example.Cipher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Service
public class DecodeService {

    private static final int AES_KEY_SIZE = 16; // AES key size in bytes (128 bits)
    private static final Logger logger = LoggerFactory.getLogger(DecodeService.class);

    private static final int[][] BLOCK_POSITIONS = new int[][] {
            { 0, 3, 5, 12, 15, 7, 2, 10 },
            { 1, 4, 6, 13, 14, 8, 9, 11 },
            { 2, 5, 7, 0, 12, 6, 1, 14 },
            { 3, 6, 0, 9, 15, 2, 4, 11 }
    };

    public String decodeMessage(MultipartFile imageFile, String key) throws IOException {
        BufferedImage encodedImage = ImageIO.read(imageFile.getInputStream());

        if (encodedImage == null) {
            logger.error("Unsupported image format or unable to decode the image.");
            return "Error: Unsupported image format or unable to decode the image.";
        }

        // Validate the key length
        if (key.length() != AES_KEY_SIZE) {
            logger.error("Invalid AES key length. Expected length: {}, Provided length: {}", AES_KEY_SIZE,
                    key.length());
            return "Error: Invalid AES key length.";
        }

        SecretKey secretKey = new SecretKeySpec(key.getBytes(), "AES");

        // Decode the encrypted (scrambled) message from the image
        String encryptedMessage = decodeMessageFromImage(encodedImage);

        if (encryptedMessage.isEmpty()) {
            logger.warn("No message found in the image.");
            return "Error: No message found.";
        }

        try {
            // Unscramble the message bytes before decryption
            byte[] unscrambled = unscrambleBits(encryptedMessage.getBytes(StandardCharsets.ISO_8859_1));
            String unscrambledMessage = new String(unscrambled, StandardCharsets.UTF_8);

            String decryptedMessage = AesUtil.decrypt(unscrambledMessage, secretKey);

            // Check if decryption resulted in an empty or invalid message
            if (decryptedMessage.isEmpty() || !isValidMessage()) {
                logger.warn("Decryption resulted in an invalid or empty message.");
                return "Error: Decryption failed. Invalid key or corrupted message.";
            }

            return decryptedMessage;
        } catch (Exception e) {
            logger.error("Decryption failed.", e);
            return "Error: Invalid key or corrupted message."; // Updated message for decryption failure
        }
    }

    private String decodeMessageFromImage(BufferedImage encodedImage) {
        int width = encodedImage.getWidth();
        int height = encodedImage.getHeight();

        StringBuilder message = new StringBuilder();
        int currentByte = 0;
        int bitCount = 0; // bits collected for current byte

        int availableHeight = height - 1; // skip first row (AES key)
        if (availableHeight <= 0) {
            return "";
        }
        int blocksX = (width + 7) / 8;
        int blocksY = (availableHeight + 7) / 8;

        outer: for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int startX = bx * 8;
                int startY = 1 + by * 8; // start from y=1
                int blockW = Math.min(8, width - startX);
                int blockH = Math.min(8, (height - 1) - by * 8);
                if (blockW <= 0 || blockH <= 0)
                    continue;

                int rowIdx = (bx + by) % BLOCK_POSITIONS.length; // deterministic selection
                int[] positions = BLOCK_POSITIONS[rowIdx];

                // Compute parity over valid positions in this block
                int parity = 0;
                boolean hasCarrier = false;
                for (int p : positions) {
                    int localX = p % 8;
                    int localY = p / 8;
                    if (localX >= blockW || localY >= blockH)
                        continue;
                    int px = startX + localX;
                    int py = startY + localY;
                    int rgb = encodedImage.getRGB(px, py);
                    int blue = rgb & 0xFF;
                    parity ^= (blue & 1);
                    hasCarrier = true;
                }

                if (!hasCarrier)
                    continue;

                // Use parity as the extracted bit (MSB-first ordering across bytes)
                int bit = parity & 1;
                currentByte = (currentByte << 1) | bit;
                bitCount++;
                if (bitCount == 8) {
                    char c = (char) (currentByte & 0xFF);
                    if (c == '\0') {
                        break outer;
                    }
                    message.append(c);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }

        return message.toString();
    }

    // Unscramble bits using the inverse of the hardcoded shuffle pattern
    private byte[] unscrambleBits(byte[] in) {
        int[] o = { 2, 5, 0, 7, 1, 4, 6, 3 };
        int[] inv = new int[8];
        for (int i = 0; i < 8; i++)
            inv[o[i]] = i;
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) {
            int val = in[i] & 0xFF;
            int res = 0;
            for (int b = 0; b < 8; b++) {
                res |= ((val >> inv[b]) & 1) << b;
            }
            out[i] = (byte) (res & 0xFF);
        }
        return out;
    }

    private boolean isValidMessage() {
        return true;
    }
}