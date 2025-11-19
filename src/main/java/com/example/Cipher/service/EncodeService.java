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

    // Fully hardcoded positions table (example provided by user)
    // Positions are linear indices inside a block, row-major order.
    private static final int[][] BLOCK_POSITIONS = new int[][]{
            {0, 3, 5, 12, 15, 7, 2, 10},
            {1, 4, 6, 13, 14, 8, 9, 11},
            {2, 5, 7, 0, 12, 6, 1, 14},
            {3, 6, 0, 9, 15, 2, 4, 11}
    };

    public byte[] encodeMessage(MultipartFile image, String message, String secretKey) throws Exception {
        if (!isImageFile(image)) {
            throw new IllegalArgumentException("Uploaded file is not a valid image.");
        }

        if (secretKey == null || secretKey.length() != AES_KEY_SIZE) {
            throw new IllegalArgumentException("Secret key must be exactly " + AES_KEY_SIZE + " bytes long.");
        }

        SecretKey key = new SecretKeySpec(secretKey.getBytes(), "AES");
        String encryptedMessage = AesUtil.encrypt(message, key);

        // Scramble the encrypted message bytes
        byte[] scrambled = scrambleBits(encryptedMessage.getBytes("UTF-8"));
        String scrambledMessage = new String(scrambled, "ISO-8859-1");

        BufferedImage bufferedImage = ImageIO.read(image.getInputStream());

        // Capacity check based on available positions across blocks (now multiple bits per block)
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();
        int availableHeight = height - 1; // exclude first row y=0
        if (availableHeight <= 0) {
            throw new IllegalArgumentException("Image height must be at least 2 pixels.");
        }
        int blocksX = (width + 7) / 8; // ceil(width/8)
        int blocksY = (availableHeight + 7) / 8; // ceil((height-1)/8)

        // Compute exact capacity in bits by counting valid positions inside each block
        int capacityBits = 0;
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int startX = bx * 8;
                int startY = 1 + by * 8;
                int blockW = Math.min(8, width - startX);
                int blockH = Math.min(8, (height - 1) - by * 8);
                if (blockW <= 0 || blockH <= 0) continue;
                int rowIdx = (bx + by) % BLOCK_POSITIONS.length;
                int[] positions = BLOCK_POSITIONS[rowIdx];
                for (int p : positions) {
                    int localX = p % 8;
                    int localY = p / 8;
                    if (localX >= blockW || localY >= blockH) continue;
                    capacityBits++;
                }
            }
        }

        int requiredBits = (scrambledMessage.length() + 1) * 8; // include null terminator
        if (requiredBits > capacityBits) {
            throw new IllegalArgumentException("Message is too long to encode in this image (capacity exceeded). "
                    + "Available bits: " + capacityBits + ", required bits: " + requiredBits);
        }

        // Encode the AES key in the image (first row, red channel)
        encodeKeyIntoImage(bufferedImage, secretKey);

        // Encode the scrambled (encrypted) message using per-position LSB embedding
        BufferedImage encodedImage = encodeMessageIntoImage(bufferedImage, scrambledMessage);

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

    // New embedding: write one bit per valid position in BLOCK_POSITIONS (MSB-first across bytes)
    private BufferedImage encodeMessageIntoImage(BufferedImage image, String message) {
        int width = image.getWidth();
        int height = image.getHeight();
        // Append null terminator
        message += '\0';

        // Start with a copy of the original image
        BufferedImage encodedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                encodedImage.setRGB(x, y, image.getRGB(x, y));
            }
        }

        int msgIndex = 0;      // byte index in message-as-String (ISO-8859-1 compatible)
        int bitIndex = 0;      // bit position within current byte (0..7), MSB first

        int availableHeight = height - 1; // exclude first row (y=0) to preserve AES key row entirely
        int blocksX = (width + 7) / 8;
        int blocksY = (availableHeight + 7) / 8;

        outer:
        for (int by = 0; by < blocksY; by++) {
            for (int bx = 0; bx < blocksX; bx++) {
                int startX = bx * 8;
                int startY = 1 + by * 8; // start from row 1 to avoid the key row
                int blockW = Math.min(8, width - startX);
                int blockH = Math.min(8, (height - 1) - by * 8);
                if (blockW <= 0 || blockH <= 0) continue;

                int rowIdx = (bx + by) % BLOCK_POSITIONS.length; // i.e., % 4
                int[] positions = BLOCK_POSITIONS[rowIdx];

                for (int p : positions) {
                    int localX = p % 8; // positions are defined for 8x8 blocks
                    int localY = p / 8;
                    if (localX >= blockW || localY >= blockH) continue; // position falls outside this (smaller) block
                    int px = startX + localX;
                    int py = startY + localY;

                    if (msgIndex < message.length()) {
                        int currentByte = message.charAt(msgIndex) & 0xFF;
                        int bit = (currentByte >> (7 - bitIndex)) & 1; // MSB first

                        int rgb = encodedImage.getRGB(px, py);
                        int red = (rgb >> 16) & 0xFF;
                        int green = (rgb >> 8) & 0xFF;
                        int blue = rgb & 0xFF;

                        blue = (blue & 0xFE) | (bit & 1); // set LSB to desired bit

                        int newRgb = (red << 16) | (green << 8) | (blue & 0xFF);
                        encodedImage.setRGB(px, py, newRgb);

                        bitIndex++;
                        if (bitIndex == 8) {
                            bitIndex = 0;
                            msgIndex++;
                        }

                        if (msgIndex >= message.length()) {
                            break outer;
                        }
                    } else {
                        break outer;
                    }
                }
            }
        }

        return encodedImage;
    }

    private byte[] encodeImageToBytes(BufferedImage encodedImage) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(encodedImage, "png", baos);
        return baos.toByteArray();
    }

    // Scramble bits using a hardcoded shuffle pattern
    private byte[] scrambleBits(byte[] in) {
        int[] o = { 2, 5, 0, 7, 1, 4, 6, 3 };
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++)
            for (int b = 0; b < 8; b++)
                out[i] |= ((in[i] >> o[b]) & 1) << b;
        return out;
    }
}