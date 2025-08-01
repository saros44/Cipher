package com.example.Cipher.service;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.CRC32;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VideoDecodeService {

    private static final int BLOCK_SIZE = 8; // Standard DCT block size
    private static final int THRESHOLD = 50; // Variance threshold for block selection

    /**
     * Decodes the hidden message from the encoded video using the provided key.
     *
     * @param videoFile The encoded video file
     * @param key The key used for decryption
     * @return The decoded message
     * @throws Exception If decoding fails due to insufficient data, corruption, or integrity check failure
     */
    public String decode(MultipartFile videoFile, String key) throws Exception {
        // Save input video to temp file
        Path tempInputPath = Files.createTempFile("input_", ".mp4");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);
        Path framesDir = Files.createTempDirectory("frames");

        // Extract frames from the video
        extractFrames(tempInputPath.toString(), framesDir.toString());

        // Extract bits from all frames
        StringBuilder bitStream = new StringBuilder();
        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));
        for (File frameFile : frameFiles) {
            BufferedImage image = javax.imageio.ImageIO.read(frameFile);
            extractBitsFromFrame(image, bitStream);
        }

        // Decode bits with majority voting to handle repetition
        String decodedBits = majorityVoting(bitStream.toString());

        // Ensure enough bits are extracted for length and checksum at minimum
        if (decodedBits.length() < 64) throw new Exception("Insufficient data extracted");

        // Extract length, message bits, and CRC32 checksum
        int length = Integer.parseInt(decodedBits.substring(0, 32), 2);
        int totalBits = 32 + length * 8 + 32;
        if (decodedBits.length() < totalBits) throw new Exception("Corrupted data: not enough bits");

        String msgBits = decodedBits.substring(32, 32 + length * 8);
        String crcBits = decodedBits.substring(32 + length * 8, totalBits);

        byte[] encryptedMessage = bitsToBytes(msgBits);
        long extractedCrc = Long.parseLong(crcBits, 2);

        // Verify data integrity with CRC32
        CRC32 crc = new CRC32();
        crc.update(encryptedMessage);
        if (crc.getValue() != extractedCrc) throw new Exception("Data integrity check failed");

        // Decrypt the message
        String message = decrypt(encryptedMessage, key);

        // Clean up temporary files
        cleanup(tempInputPath, framesDir);
        return message;
    }

    /**
     * Applies majority voting to decode the bit stream where each bit is repeated three times.
     *
     * @param bitStream The extracted bit stream with repeated bits
     * @return The decoded bit stream
     */
    private String majorityVoting(String bitStream) {
        StringBuilder decoded = new StringBuilder();
        for (int i = 0; i < bitStream.length() - 2; i += 3) {
            int sum = (bitStream.charAt(i) - '0') +
                    (bitStream.charAt(i + 1) - '0') +
                    (bitStream.charAt(i + 2) - '0');
            decoded.append(sum >= 2 ? '1' : '0');
        }
        return decoded.toString();
    }

    /**
     * Extracts bits from a frame by analyzing DCT coefficients.
     *
     * @param image The frame image
     * @param bitStream The StringBuilder to append extracted bits
     */
    private void extractBitsFromFrame(BufferedImage image, StringBuilder bitStream) {
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y += BLOCK_SIZE) {
            for (int x = 0; x < width; x += BLOCK_SIZE) {
                int blockWidth = Math.min(BLOCK_SIZE, width - x);
                int blockHeight = Math.min(BLOCK_SIZE, height - y);
                if (blockWidth != BLOCK_SIZE || blockHeight != BLOCK_SIZE) continue;

                double[][] block = getBlock(image, x, y, blockWidth, blockHeight);
                double variance = computeVariance(block);
                if (variance < THRESHOLD) continue;

                double[][] coeffs = dct(block);
                int bit = coeffs[4][4] > coeffs[5][5] ? 1 : 0;
                bitStream.append(bit);
            }
        }
    }

    /**
     * Extracts an 8x8 block from the image.
     *
     * @param image The source image
     * @param x X-coordinate of the block
     * @param y Y-coordinate of the block
     * @param blockWidth Width of the block
     * @param blockHeight Height of the block
     * @return The block as a 2D array
     */
    private double[][] getBlock(BufferedImage image, int x, int y, int blockWidth, int blockHeight) {
        double[][] block = new double[blockHeight][blockWidth];
        for (int i = 0; i < blockHeight; i++) {
            for (int j = 0; j < blockWidth; j++) {
                int pixel = image.getRGB(x + j, y + i);
                int blue = pixel & 0xFF; // Use blue channel
                block[i][j] = blue;
            }
        }
        return block;
    }

    /**
     * Computes the variance of a block to determine if it's suitable Bit embedding.
     *
     * @param data The block data
     * @return The variance
     */
    private double computeVariance(double[][] data) {
        double sum = 0;
        int count = 0;
        for (double[] row : data) {
            for (double value : row) {
                sum += value;
                count++;
            }
        }
        double mean = sum / count;
        double variance = 0;
        for (double[] row : data) {
            for (double value : row) {
                variance += (value - mean) * (value - mean);
            }
        }
        return variance / count;
    }

    /**
     * Computes the DCT of an 8x8 block.
     *
     * @param block The input block
     * @return The DCT coefficients
     */
    private double[][] dct(double[][] block) {
        double[][] result = new double[BLOCK_SIZE][BLOCK_SIZE];
        for (int u = 0; u < BLOCK_SIZE; u++) {
            for (int v = 0; v < BLOCK_SIZE; v++) {
                double sum = 0;
                for (int x = 0; x < BLOCK_SIZE; x++) {
                    for (int y = 0; y < BLOCK_SIZE; y++) {
                        sum += block[x][y] * Math.cos((2 * x + 1) * u * Math.PI / 16) *
                                Math.cos((2 * y + 1) * v * Math.PI / 16);
                    }
                }
                double alphaU = u == 0 ? 1 / Math.sqrt(2) : 1;
                double alphaV = v == 0 ? 1 / Math.sqrt(2) : 1;
                result[u][v] = 0.25 * alphaU * alphaV * sum;
            }
        }
        return result;
    }

    /**
     * Decrypts the encrypted message using AES.
     *
     * @param encrypted The encrypted message bytes
     * @param key The decryption key
     * @return The decrypted message
     * @throws Exception If decryption fails
     */
    private String decrypt(byte[] encrypted, String key) throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
        SecretKeySpec secretKey = new SecretKeySpec(Arrays.copyOf(keyBytes, 16), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(encrypted), "UTF-8");
    }

    /**
     * Converts a bit string to a byte array.
     *
     * @param bits The bit string
     * @return The byte array
     */
    private byte[] bitsToBytes(String bits) {
        byte[] bytes = new byte[bits.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return bytes;
    }

    /**
     * Extracts frames from the video using FFmpeg.
     *
     * @param videoPath The path to the video file
     * @param framesDir The directory to store frames
     * @throws Exception If frame extraction fails
     */
    private void extractFrames(String videoPath, String framesDir) throws Exception {
        new ProcessBuilder("ffmpeg", "-i", videoPath, framesDir + "/frame_%06d.png", "-y")
                .inheritIO().start().waitFor();
    }

    /**
     * Cleans up temporary files and directories.
     *
     * @param paths The paths to clean up
     * @throws IOException If cleanup fails
     */
    private void cleanup(Path... paths) throws IOException {
        for (Path path : paths) {
            if (Files.isDirectory(path)) {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
            } else {
                Files.deleteIfExists(path);
            }
        }
    }
}