package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * VideoDecodeService
 *
 * Responsibilities:
 * - Fast metadata check for Base64(key) in title tag
 * - Extract frames (PNG) and decode per-frame payload
 * - Parallel per-frame decoding while preserving order and early stop
 *
 * Reformatting only; logic unchanged.
 */
@Service
public class VideoDecodeService {

    private static final Logger logger = LoggerFactory.getLogger(VideoDecodeService.class);

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

    /** Optional: how many frames at most to scan before giving up (safety). */
    private static final int MAX_FRAMES_TO_SCAN = 10_000;

    /**
     * Decode the hidden message from the provided video using the given secret key.
     *
     * Steps:
     * 1) Save upload to temp file
     * 2) Fast metadata check (ffprobe) for Base64(key)
     * 3) Extract frames losslessly to PNG
     * 4) Read pixels in raster order: 1 pixel = 1 bit (8 bits = 1 char, MSB→LSB)
     * 5) For each frame, collect chars until FRAME_DELIM; append chunk and stop at
     * first frame without delimiter
     * 6) Concatenate chunks and decrypt with VideoEncryptionUtil.decrypt(cipher,
     * key)
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
            // Save upload to a temp file (ffmpeg/ffprobe read files)
            String originalName = safeName(videoFile.getOriginalFilename());
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : ".avi";

            tempInput = Files.createTempFile("decode_input_", ext);
            Files.copy(videoFile.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);

            // Fast metadata-based key check (encoder stores Base64(key) in metadata title)
            try {
                String titleMeta = readTitleMetadataFromFile(tempInput.toString());
                if (titleMeta != null && !titleMeta.isEmpty()) {
                    String expected = Base64.getEncoder().encodeToString(key.getBytes());
                    if (!expected.equals(titleMeta)) {
                        throw new IllegalArgumentException("Provided key does not match.");
                    }
                }
            } catch (IllegalArgumentException e) {
                // validation error: rethrow immediately
                throw e;
            } catch (Exception e) {
                // If ffprobe/check fails, fall back to full decode path
                logger.debug("key-check failed or unavailable: {}", e.getMessage());
            }

            // Extract frames at highest quality; use PNG to avoid further loss
            framesDir = Files.createTempDirectory("decoded_frames_");
            extractAllFrames(tempInput.toString(), framesDir.toString());

            // Iterate frames in numeric order; stop at first one without a complete
            // delimiter
            List<File> frames = listPngFramesSorted(framesDir);
            if (frames.isEmpty()) {
                throw new IllegalStateException("No frames extracted from video.");
            }

            // Parallel decode: preserve order, stop on first null chunk.
            int toScan = Math.min(frames.size(), MAX_FRAMES_TO_SCAN);
            int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), toScan));
            ExecutorService ex = Executors.newFixedThreadPool(threads);

            try {
                List<Future<String>> futures = new ArrayList<>(toScan);

                for (int i = 0; i < toScan; i++) {
                    final File f = frames.get(i);
                    futures.add(ex.submit(() -> {
                        try {
                            BufferedImage img = ImageIO.read(f);
                            if (img == null)
                                return null;
                            return decodeChunkFromFrame(img);
                        } catch (IOException ioe) {
                            logger.warn("Failed to read frame {}: {}", f.getName(), ioe.getMessage());
                            return null;
                        }
                    }));
                }

                StringBuilder cipherAggregate = new StringBuilder();
                int framesWithData = 0;

                for (int i = 0; i < futures.size(); i++) {
                    String chunk = futures.get(i).get();
                    if (chunk == null) {
                        // first non-modified frame -> stop
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
                ex.shutdownNow();
            }
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

            // Fast metadata-based key check (encoder stores Base64(key) in metadata title)
            try {
                String titleMeta = readTitleMetadataFromFile(tempInput.toString());
                if (titleMeta != null && !titleMeta.isEmpty()) {
                    String expected = Base64.getEncoder().encodeToString(key.getBytes());
                    if (!expected.equals(titleMeta)) {
                        throw new IllegalArgumentException("Provided key does not match video metadata.");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                logger.debug("Metadata key-check failed or unavailable: {}", e.getMessage());
            }

            framesDir = Files.createTempDirectory("decoded_frames_");
            extractAllFrames(tempInput.toString(), framesDir.toString());

            List<File> frames = listPngFramesSorted(framesDir);
            if (frames.isEmpty()) {
                throw new IllegalStateException("No frames extracted from video.");
            }

            // Parallel decode with ordered futures
            int toScan = Math.min(frames.size(), MAX_FRAMES_TO_SCAN);
            int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), toScan));
            ExecutorService ex = Executors.newFixedThreadPool(threads);

            try {
                List<Future<String>> futures = new ArrayList<>(toScan);

                for (int i = 0; i < toScan; i++) {
                    final File f = frames.get(i);
                    futures.add(ex.submit(() -> {
                        try {
                            BufferedImage img = ImageIO.read(f);
                            if (img == null)
                                return null;
                            return decodeChunkFromFrame(img);
                        } catch (IOException ioe) {
                            logger.warn("Failed to read frame {}: {}", f.getName(), ioe.getMessage());
                            return null;
                        }
                    }));
                }

                List<String> chunks = new ArrayList<>();
                int framesScanned = 0;

                for (int i = 0; i < futures.size(); i++) {
                    framesScanned++;
                    String chunk = futures.get(i).get();
                    if (chunk == null)
                        break;
                    chunks.add(chunk);
                }

                String cipher = String.join("", chunks);
                String plaintext = cipher.isEmpty() ? "" : VideoEncryptionUtil.decrypt(cipher, key);
                return new DecodingResult(plaintext, cipher, chunks.size(), framesScanned);
            } finally {
                ex.shutdownNow();
            }
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
                "-threads", String.valueOf(Runtime.getRuntime().availableProcessors()),
                "-i", videoPath,
                "-q:v", "1",
                framePattern);

        int exit = runAndPipe(cmd, "[ffmpeg decode-extract]");
        if (exit != 0) {
            throw new IOException("Failed to extract frames for decoding. Exit=" + exit);
        }
    }

    /**
     * Read the `title` metadata tag (where encoder stores Base64(key)).
     * Returns null if tag absent or ffprobe fails.
     */
    private static String readTitleMetadataFromFile(String videoPath) throws Exception {
        List<String> cmd = Arrays.asList(
                "ffprobe", "-v", "error",
                "-show_entries", "format_tags=title",
                "-of", "default=noprint_wrappers=1:nokey=1",
                videoPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        String line;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            line = br.readLine();
        }

        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        int code = finished ? p.exitValue() : -1;
        if (!finished) {
            p.destroyForcibly();
            logger.warn("ffprobe timed out for file {}", videoPath);
        }
        if (code != 0) {
            return null;
        }
        if (line == null || line.trim().isEmpty())
            return null;
        return line.trim();
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
        StringBuilder rolling = new StringBuilder(); // detect delimiter efficiently

        int bitCount = 0;
        int currentByte = 0;

        for (int pixelIndex = 0; pixelIndex < totalPixels; pixelIndex++) {
            int x = pixelIndex % width;
            int y = pixelIndex / width;

            int rgb = img.getRGB(x, y);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // Grayscale values are equal (10 or 245), but after compression they may drift;
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
     * Run a process and pipe output for easier debugging. Uses timeouts to avoid
     * hangs.
     */
    private static int runAndPipe(List<String> command, String prefix) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = br.readLine()) != null) {
                logger.debug("{} {}", prefix, line);
            }
        }

        boolean finished = p.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            logger.warn("{} process timed out: {}", prefix, String.join(" ", command));
            return -1;
        }
        return p.exitValue();
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
