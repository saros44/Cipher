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
            logger.error("Invalid AES key length. Expected length: {}, Provided length: {}", AES_KEY_SIZE, key.length());
            return "Error: Invalid AES key length.";
        }

        SecretKey secretKey = new SecretKeySpec(key.getBytes(), "AES");

        // Decode the encrypted message from the image
        String encryptedMessage = decodeMessageFromImage(encodedImage);

        if (encryptedMessage.isEmpty()) {
            logger.warn("No message found in the image.");
            return "Error: No message found.";
        }

        // Decrypt the message using AES
        try {
            String decryptedMessage = AesUtil.decrypt(encryptedMessage, secretKey);

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

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = encodedImage.getRGB(x, y);
                int redBit = (rgb >> 16) & 1;

                charBits = (charBits << 1) | redBit;
                bitCount++;

                if (bitCount >= 8) {
                    char c = (char) charBits;
                    if (c == '\0') {
                        return message.toString();
                    }
                    message.append(c);
                    charBits = 0;
                    bitCount = 0;
                }
            }
        }

        // If no null character found, return whatever was decoded
        return message.toString();
    }

    private boolean isValidMessage() {
        // Add your validation logic for the message, if applicable
        // For example, checking for certain formats or content
        return true; // Placeholder: modify this based on your requirements
    }
}
