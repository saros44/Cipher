package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class VideoDecodeService {

    // ==== Must match the encoder ====
    /**
     * Per-frame delimiter that marks the end of the chunk in each modified frame.
     */
    private static final String FRAME_DELIM = "<END>";
    /**
     * Intensity threshold to decide bit value (robust vs. 10/245 encoding and Xvid
     * -q:v 2).
     */
    private static final int BIT_THRESHOLD = 128;

    /** Optional: how many frames at most to scan before giving up (safety) */
    private static final int MAX_FRAMES_TO_SCAN = 10_000;

    /**
     * Decode the hidden message from the provided video using the given secret key.
     * This method:
     * 1) Extracts frames losslessly to PNG.
     * 2) Reads pixels in raster order, 1 pixel = 1 bit, 8 bits = 1 char (MSB →
     * LSB).
     * 3) For each frame, collects chars until it sees FRAME_DELIM; that chunk is
     * appended.
     * 4) Stops at the first frame that does not contain FRAME_DELIM (encoder only
     * modified the first N frames).
     * 5) Concatenates chunks and decrypts with VideoEncryptionUtil.decrypt(cipher,
     * key).
     */
    public String decode(MultipartFile videoFile, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Video file must not be empty.");
        }

        Path tempInput = null;
        Path framesDir = null;

        try {
            // Save upload to a temp file (works for .avi or any other container; ffmpeg
            // will read it)
            String originalName = safeName(videoFile.getOriginalFilename());
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : ".avi";
            tempInput = Files.createTempFile("decode_input_", ext);
            Files.copy(videoFile.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);

            // Extract frames at highest quality; use PNG to avoid further loss
            framesDir = Files.createTempDirectory("decoded_frames_");
            extractAllFrames(tempInput.toString(), framesDir.toString());

            // Iterate frames in numeric order; stop at first one without a complete
            // delimiter
            List<File> frames = listPngFramesSorted(framesDir);
            if (frames.isEmpty()) {
                throw new IllegalStateException("No frames extracted from video.");
            }

            StringBuilder cipherAggregate = new StringBuilder();
            int framesWithData = 0;

            for (int i = 0; i < frames.size() && i < MAX_FRAMES_TO_SCAN; i++) {
                File frameFile = frames.get(i);
                BufferedImage img = ImageIO.read(frameFile);
                if (img == null)
                    continue;

                String chunk = decodeChunkFromFrame(img);
                if (chunk == null) {
                    // No delimiter found in this frame => encoder did not modify this (and
                    // subsequent) frames.
                    break;
                }
                cipherAggregate.append(chunk);
                framesWithData++;
            }

            if (framesWithData == 0) {
                throw new IllegalStateException("No embedded data found. " +
                        "Make sure you're using the correct video and the encoding completed successfully.");
            }

            String encryptedMessage = cipherAggregate.toString();
            String plaintext = VideoEncryptionUtil.decrypt(encryptedMessage, key);
            return plaintext;

        } finally {
            cleanup(framesDir, tempInput);
        }
    }

    /**
     * Same as decode(...) but returns details helpful for debugging/metrics.
     */
    public DecodingResult decodeWithDetails(MultipartFile videoFile, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Video file must not be empty.");
        }

        Path tempInput = null;
        Path framesDir = null;

        try {
            String originalName = safeName(videoFile.getOriginalFilename());
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : ".avi";
            tempInput = Files.createTempFile("decode_input_", ext);
            Files.copy(videoFile.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);

            framesDir = Files.createTempDirectory("decoded_frames_");
            extractAllFrames(tempInput.toString(), framesDir.toString());

            List<File> frames = listPngFramesSorted(framesDir);
            if (frames.isEmpty()) {
                throw new IllegalStateException("No frames extracted from video.");
            }

            List<String> chunks = new ArrayList<>();
            int framesScanned = 0;

            for (int i = 0; i < frames.size() && i < MAX_FRAMES_TO_SCAN; i++) {
                framesScanned++;
                File frameFile = frames.get(i);
                BufferedImage img = ImageIO.read(frameFile);
                if (img == null)
                    continue;

                String chunk = decodeChunkFromFrame(img);
                if (chunk == null) {
                    break; // first non-modified frame encountered
                }
                chunks.add(chunk);
            }

            String cipher = String.join("", chunks);
            String plaintext = cipher.isEmpty() ? "" : VideoEncryptionUtil.decrypt(cipher, key);

            return new DecodingResult(plaintext, cipher, chunks.size(), framesScanned);

        } finally {
            cleanup(framesDir, tempInput);
        }
    }

    // ===================== Internals =====================

    private static String safeName(String name) {
        if (name == null)
            return null;
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void extractAllFrames(String videoPath, String framesDir) throws Exception {
        // Extract every frame to PNG with good quality
        String framePattern = Paths.get(framesDir, "frame_%06d.png").toString();
        List<String> cmd = Arrays.asList(
                "ffmpeg", "-y",
                "-i", videoPath,
                "-q:v", "1",
                framePattern);
        int exit = runAndPipe(cmd, "[ffmpeg decode-extract]");
        if (exit != 0) {
            throw new IOException("Failed to extract frames for decoding. Exit=" + exit);
        }
    }

    private static List<File> listPngFramesSorted(Path framesDir) throws IOException {
        File[] files = framesDir.toFile().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".png"));
        if (files == null)
            return Collections.emptyList();
        return Arrays.stream(files)
                .sorted(Comparator.comparing(File::getName))
                .collect(Collectors.toList());
    }

    /**
     * Decode a single frame’s chunk:
     * - Read bits from pixels in raster order.
     * - Build chars (8 bits, MSB→LSB).
     * - Stop & return the chunk if FRAME_DELIM is seen.
     * - If delimiter never appears, return null (frame not modified).
     */
    private static String decodeChunkFromFrame(BufferedImage img) {
        final int width = img.getWidth();
        final int height = img.getHeight();
        final int totalPixels = width * height;

        StringBuilder sb = new StringBuilder();
        StringBuilder rolling = new StringBuilder(); // to detect delimiter without constantly slicing strings

        int bitCount = 0;
        int currentByte = 0;

        for (int pixelIndex = 0; pixelIndex < totalPixels; pixelIndex++) {
            int x = pixelIndex % width;
            int y = pixelIndex / width;

            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            // Grayscale values are equal (10 or 245), but after compression they might
            // drift;
            // averaging is robust.
            int intensity = (r + g + b) / 3;

            int bit = (intensity >= BIT_THRESHOLD) ? 1 : 0;

            // Build bytes MSB → LSB
            currentByte = (currentByte << 1) | bit;
            bitCount++;

            if (bitCount == 8) {
                char c = (char) (currentByte & 0xFF);
                sb.append(c);

                // Rolling window for delimiter detection (avoid substring calls)
                rolling.append(c);
                if (rolling.length() > FRAME_DELIM.length()) {
                    rolling.deleteCharAt(0);
                }
                if (rolling.toString().equals(FRAME_DELIM)) {
                    // Remove the delimiter from the end and return the chunk
                    int chunkLen = sb.length() - FRAME_DELIM.length();
                    return (chunkLen <= 0) ? "" : sb.substring(0, chunkLen);
                }
                // Reset for next byte
                currentByte = 0;
                bitCount = 0;
            }
        }

        // Exhausted all pixels without seeing the delimiter => not a modified frame
        return null;
    }

    /**
     * Run a process and pipe output for easier debugging (mirrors your encoder
     * helper).
     */
    private static int runAndPipe(List<String> command, String prefix) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                System.out.println(prefix + " " + line);
            }
        }
        return p.waitFor();
    }

    private static void cleanup(Path... paths) {
        for (Path p : paths) {
            safeDelete(p);
        }
    }

    private static void safeDelete(Path path) {
        if (path == null)
            return;
        try {
            if (Files.isDirectory(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(q -> {
                            try {
                                Files.deleteIfExists(q);
                            } catch (IOException ignored) {
                            }
                        });
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    // ===== Result record for detailed decode =====
    public record DecodingResult(
            String plaintext,
            String ciphertext,
            int chunksRecovered,
            int framesScanned) {
    }
}
