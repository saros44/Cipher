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

@Service
public class DecodeService {

    private static final int AES_KEY_SIZE = 16; // AES key size in bytes (128 bits)
    private static final Logger logger = LoggerFactory.getLogger(DecodeService.class);

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
            byte[] unscrambled = unscrambleBits(encryptedMessage.getBytes("ISO-8859-1"));
            String unscrambledMessage = new String(unscrambled, "UTF-8");

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
        int charBits = 0;
        int bitCount = 0;

        outer: for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = encodedImage.getRGB(x, y);

                // Extract 1 bit from each channel (R, G, B)
                for (int channel = 0; channel < 3; channel++) {
                    int bit;
                    if (channel == 0) {
                        bit = (rgb >> 16) & 1; // Red LSB
                    } else if (channel == 1) {
                        bit = (rgb >> 8) & 1; // Green LSB
                    } else {
                        bit = rgb & 1; // Blue LSB
                    }

                    charBits = (charBits << 1) | bit;
                    bitCount++;

                    if (bitCount == 8) {
                        char c = (char) charBits;
                        if (c == '\0') {
                            break outer;
                        }
                        message.append(c);
                        charBits = 0;
                        bitCount = 0;
                    }
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
        for (int i = 0; i < in.length; i++)
            for (int b = 0; b < 8; b++)
                out[i] |= ((in[i] >> inv[b]) & 1) << b;
        return out;
    }

    private boolean isValidMessage() {
        return true;
    }
}