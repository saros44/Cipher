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
public class VideoEncodeService {

    private static final int BLOCK_SIZE = 8; // Standard DCT block size
    private static final int THRESHOLD = 50; // Variance threshold for block selection
    private static final double DELTA = 10.0; // Margin for DCT coefficient difference

    // Encode message into video
    public byte[] encode(MultipartFile videoFile, String message, String key) throws Exception {
        // Save input video to temp file
        Path tempInputPath = Files.createTempFile("input_", ".mp4");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);
        Path framesDir = Files.createTempDirectory("frames");

        // Extract frames and get frame rate
        extractFrames(tempInputPath.toString(), framesDir.toString());
        double frameRate = getFrameRate(tempInputPath.toString());

        // Prepare message data
        byte[] encryptedMessage = encrypt(message, key);
        CRC32 crc = new CRC32();
        crc.update(encryptedMessage);
        long crcValue = crc.getValue();

        ByteArrayOutputStream dataStream = new ByteArrayOutputStream();
        dataStream.write(intToBytes(encryptedMessage.length));
        dataStream.write(encryptedMessage);
        dataStream.write(longToBytes(crcValue));
        byte[] data = dataStream.toByteArray();

        // Convert to bits with simple repetition (3x for robustness)
        StringBuilder bitStream = new StringBuilder();
        for (byte b : data) {
            for (int i = 7; i >= 0; i--) {
                int bit = (b >> i) & 1;
                bitStream.append(bit).append(bit).append(bit);
            }
        }

        // Embed bits into frames
        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));
        int bitIndex = 0;
        for (File frameFile : frameFiles) {
            BufferedImage image = javax.imageio.ImageIO.read(frameFile);
            bitIndex = embedBitsInFrame(image, bitStream.toString(), bitIndex);
            javax.imageio.ImageIO.write(image, "png", frameFile);
            if (bitIndex >= bitStream.length()) break;
        }
        if (bitIndex < bitStream.length()) throw new Exception("Not enough capacity to embed message");

        // Reconstruct video with updated FFmpeg settings
        Path tempOutputPath = Files.createTempFile("output_", ".mp4");
        reconstructVideo(framesDir.toString(), tempInputPath.toString(), tempOutputPath.toString(), frameRate);

        byte[] result = Files.readAllBytes(tempOutputPath);
        cleanup(tempInputPath, framesDir, tempOutputPath);
        return result;
    }

    // Decode message from video
    public String decode(MultipartFile videoFile, String key) throws Exception {
        Path tempInputPath = Files.createTempFile("input_", ".mp4");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);
        Path framesDir = Files.createTempDirectory("frames");

        extractFrames(tempInputPath.toString(), framesDir.toString());

        // Extract bits
        StringBuilder bitStream = new StringBuilder();
        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));
        for (File frameFile : frameFiles) {
            BufferedImage image = javax.imageio.ImageIO.read(frameFile);
            extractBitsFromFrame(image, bitStream);
        }

        // Decode bits with majority voting
        StringBuilder dataBits = new StringBuilder();
        String bits = bitStream.toString();
        for (int i = 0; i < bits.length() - 2; i += 3) {
            int sum = (bits.charAt(i) - '0') + (bits.charAt(i + 1) - '0') + (bits.charAt(i + 2) - '0');
            dataBits.append(sum >= 2 ? '1' : '0');
        }

        String bitString = dataBits.toString();
        if (bitString.length() < 64) throw new Exception("Insufficient data extracted");

        int length = Integer.parseInt(bitString.substring(0, 32), 2);
        int totalBits = 32 + length * 8 + 32;
        if (bitString.length() < totalBits) throw new Exception("Corrupted data");

        String msgBits = bitString.substring(32, 32 + length * 8);
        String crcBits = bitString.substring(32 + length * 8, totalBits);

        byte[] encryptedMessage = bitsToBytes(msgBits);
        long extractedCrc = Long.parseLong(crcBits, 2);

        CRC32 crc = new CRC32();
        crc.update(encryptedMessage);
        if (crc.getValue() != extractedCrc) throw new Exception("Data integrity check failed");

        String message = decrypt(encryptedMessage, key);
        cleanup(tempInputPath, framesDir);
        return message;
    }

    // Embed bits into a frame using DCT with higher frequency coefficients
    private int embedBitsInFrame(BufferedImage image, String bitStream, int bitIndex) {
        int width = image.getWidth();
        int height = image.getHeight();

        for (int y = 0; y < height; y += BLOCK_SIZE) {
            for (int x = 0; x < width; x += BLOCK_SIZE) {
                if (bitIndex >= bitStream.length()) return bitIndex;

                int blockWidth = Math.min(BLOCK_SIZE, width - x);
                int blockHeight = Math.min(BLOCK_SIZE, height - y);
                if (blockWidth != BLOCK_SIZE || blockHeight != BLOCK_SIZE) continue; // Skip partial blocks

                // Extract block and compute variance
                double[][] block = getBlock(image, x, y, blockWidth, blockHeight);
                double variance = computeVariance(block);
                if (variance < THRESHOLD) continue;

                // Apply DCT
                double[][] coeffs = dct(block);

                // Embed bit in higher frequency coefficients (e.g., (4,4) and (5,5))
                int bit = bitStream.charAt(bitIndex) - '0';
                if (bit == 1) {
                    coeffs[4][4] = Math.max(coeffs[4][4], coeffs[5][5] + DELTA);
                } else {
                    coeffs[5][5] = Math.max(coeffs[5][5], coeffs[4][4] + DELTA);
                }

                // Inverse DCT
                double[][] modifiedBlock = idct(coeffs);
                setBlock(image, x, y, modifiedBlock, blockWidth, blockHeight);
                bitIndex++;
            }
        }
        return bitIndex;
    }

    // Extract bits from a frame using higher frequency coefficients
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

    // Custom DCT for 8x8 blocks
    private double[][] dct(double[][] block) {
        double[][] result = new double[BLOCK_SIZE][BLOCK_SIZE];
        for (int u = 0; u < BLOCK_SIZE; u++) {
            for (int v = 0; v < BLOCK_SIZE; v++) {
                double sum = 0;
                for (int x = 0; x < BLOCK_SIZE; x++) {
                    for (int y = 0; y < BLOCK_SIZE; y++) {
                        sum += block[x][y] * Math.cos((2 * x + 1) * u * Math.PI / 16) * Math.cos((2 * y + 1) * v * Math.PI / 16);
                    }
                }
                double alphaU = u == 0 ? 1 / Math.sqrt(2) : 1;
                double alphaV = v == 0 ? 1 / Math.sqrt(2) : 1;
                result[u][v] = 0.25 * alphaU * alphaV * sum;
            }
        }
        return result;
    }

    // Custom Inverse DCT for 8x8 blocks
    private double[][] idct(double[][] coeffs) {
        double[][] result = new double[BLOCK_SIZE][BLOCK_SIZE];
        for (int x = 0; x < BLOCK_SIZE; x++) {
            for (int y = 0; y < BLOCK_SIZE; y++) {
                double sum = 0;
                for (int u = 0; u < BLOCK_SIZE; u++) {
                    for (int v = 0; v < BLOCK_SIZE; v++) {
                        double alphaU = u == 0 ? 1 / Math.sqrt(2) : 1;
                        double alphaV = v == 0 ? 1 / Math.sqrt(2) : 1;
                        sum += alphaU * alphaV * coeffs[u][v] * Math.cos((2 * x + 1) * u * Math.PI / 16) * Math.cos((2 * y + 1) * v * Math.PI / 16);
                    }
                }
                result[x][y] = 0.25 * sum;
            }
        }
        return result;
    }

    // Helper methods
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

    private void setBlock(BufferedImage image, int x, int y, double[][] block, int blockWidth, int blockHeight) {
        for (int i = 0; i < blockHeight; i++) {
            for (int j = 0; j < blockWidth; j++) {
                int value = (int) Math.round(block[i][j]);
                value = Math.max(0, Math.min(255, value));
                int pixel = image.getRGB(x + j, y + i);
                int newPixel = (pixel & 0xFFFF00) | value;
                image.setRGB(x + j, y + i, newPixel);
            }
        }
    }

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

    private byte[] encrypt(String message, String key) throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
        SecretKeySpec secretKey = new SecretKeySpec(Arrays.copyOf(keyBytes, 16), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey);
        return cipher.doFinal(message.getBytes("UTF-8"));
    }

    private String decrypt(byte[] encrypted, String key) throws Exception {
        byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));
        SecretKeySpec secretKey = new SecretKeySpec(Arrays.copyOf(keyBytes, 16), "AES");
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, secretKey);
        return new String(cipher.doFinal(encrypted), "UTF-8");
    }

    private byte[] intToBytes(int value) {
        return new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private byte[] longToBytes(long value) {
        byte[] bytes = new byte[4];
        for (int i = 3; i >= 0; i--) {
            bytes[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return bytes;
    }

    private byte[] bitsToBytes(String bits) {
        byte[] bytes = new byte[bits.length() / 8];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return bytes;
    }

    private void extractFrames(String videoPath, String framesDir) throws Exception {
        new ProcessBuilder("ffmpeg", "-i", videoPath, framesDir + "/frame_%06d.png", "-y")
                .inheritIO().start().waitFor();
    }

    private double getFrameRate(String videoPath) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=r_frame_rate", "-of", "default=noprint_wrappers=1:nokey=1", videoPath)
                .start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String[] rate = reader.readLine().split("/");
        p.waitFor();
        return Double.parseDouble(rate[0]) / Double.parseDouble(rate[1]);
    }

    private void reconstructVideo(String framesDir, String inputVideo, String outputPath, double frameRate) throws Exception {
        new ProcessBuilder("ffmpeg", "-framerate", String.valueOf(frameRate), "-i", framesDir + "/frame_%06d.png",
                "-i", inputVideo, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-crf", "18", "-pix_fmt", "yuv420p", "-c:a", "copy", outputPath, "-y")
                .inheritIO().start().waitFor();
    }

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