package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class EncodeService {

    private static final int AES_KEY_SIZE = 16; // AES key size in bytes (128 bits)

    public byte[] encodeMessage(MultipartFile image, String message, String secretKey) throws Exception {
        if (!isImageFile(image)) {
            throw new IllegalArgumentException("Uploaded file is not a valid image.");
        }

        if (secretKey == null || secretKey.length() != AES_KEY_SIZE) {
            throw new IllegalArgumentException("Secret key must be exactly " + AES_KEY_SIZE + " bytes long.");
        }

        SecretKey key = new SecretKeySpec(secretKey.getBytes(), "AES");
        String encryptedMessage = AesUtil.encrypt(message, key);

        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());

        int maxMessageLength = (bufferedImage.getWidth() * bufferedImage.getHeight() * 3) / 8;
        if (encryptedMessage.length() + 1 > maxMessageLength) {
            throw new IllegalArgumentException("Message is too long to encode in this image.");
        }

        // Encode the AES key in the image
        encodeKeyIntoImage(bufferedImage, secretKey);

        // Encode the encrypted message
        BufferedImage encodedImage = encodeMessageIntoImage(bufferedImage, encryptedMessage);

        return encodeImageToBytes(encodedImage);
    }

    private boolean isImageFile(MultipartFile file) {
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            return image != null;
        } catch (IOException e) {
            return false;
        }
    }

    private void encodeKeyIntoImage(BufferedImage image, String key) {
        int width = image.getWidth();
        int height = image.getHeight();

        if (width < AES_KEY_SIZE || height < 1) {
            throw new IllegalArgumentException("Image dimensions are too small.");
        }

        for (int i = 0; i < AES_KEY_SIZE; i++) {
            int rgb = image.getRGB(i, 0);

            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;

            int newRed = key.charAt(i) & 0xFF;

            int newRgb = (newRed << 16) | (green << 8) | blue;
            image.setRGB(i, 0, newRgb);
        }
    }

    private BufferedImage encodeMessageIntoImage(BufferedImage image, String message) {
        int width = image.getWidth();
        int height = image.getHeight();
        message += '\0'; // Add null terminator to the message

        BufferedImage encodedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int messageIndex = 0;
        int bitIndex = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                if (messageIndex < message.length()) {
                    int bit = (message.charAt(messageIndex) >> (7 - bitIndex)) & 1;
                    red = (red & 0xFE) | bit;

                    rgb = (red << 16) | (green << 8) | blue;
                    bitIndex++;
                    if (bitIndex == 8) {
                        bitIndex = 0;
                        messageIndex++;
                    }
                }

                encodedImage.setRGB(x, y, rgb);
            }
        }

        return encodedImage;
    }

    private byte[] encodeImageToBytes(BufferedImage encodedImage) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(encodedImage, "png", baos);
        return baos.toByteArray();
    }
}
