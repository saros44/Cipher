package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class VideoEncodeService {

    private static final Logger logger = LoggerFactory.getLogger(VideoEncodeService.class);

    private static final int MAX_CHARS_PER_FRAME = 100_000;

    private static final String FRAME_DELIM = "<END>";

    private final VideoQualityMetrics videoQualityMetrics;

    public VideoEncodeService(VideoQualityMetrics videoQualityMetrics) {
        this.videoQualityMetrics = videoQualityMetrics;
    }

    /** Convert any input to AVI (Xvid) if needed. */
    public MultipartFile convertToAviIfNeeded(MultipartFile video) throws Exception {
        String originalFilename = video.getOriginalFilename();
        if (originalFilename == null || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("Invalid file name or missing extension");
        }

        String lowerName = originalFilename.toLowerCase();
        if (lowerName.endsWith(".avi")) {
            return video;
        }

        Path tempInput = null;
        Path tempAvi = null;
        try {
            String ext = lowerName.substring(lowerName.lastIndexOf('.'));
            tempInput = Files.createTempFile("input_upload_", ext);
            Files.copy(video.getInputStream(), tempInput, StandardCopyOption.REPLACE_EXISTING);

            tempAvi = Files.createTempFile("converted_", ".avi");

            List<String> cmd = Arrays.asList(
                    "ffmpeg", "-y",
                    "-threads", String.valueOf(Runtime.getRuntime().availableProcessors()),
                    "-i", tempInput.toString(),
                    "-c:v", "libxvid", "-q:v", "2",
                    "-c:a", "copy",
                    tempAvi.toString());

            int exit = runAndPipe(cmd, "[ffmpeg convert]");
            if (exit != 0) {
                throw new IllegalArgumentException("Failed to convert video to AVI format.");
            }

            byte[] aviBytes = Files.readAllBytes(tempAvi);
            final byte[] finalBytes = aviBytes;

            return new MultipartFile() {
                @Override
                public String getName() {
                    return "video";
                }

                @Override
                public String getOriginalFilename() {
                    return "converted.avi";
                }

                @Override
                public String getContentType() {
                    return "video/x-msvideo";
                }

                @Override
                public boolean isEmpty() {
                    return finalBytes.length == 0;
                }

                @Override
                public long getSize() {
                    return finalBytes.length;
                }

                @Override
                public byte[] getBytes() {
                    return finalBytes;
                }

                @Override
                public InputStream getInputStream() {
                    return new ByteArrayInputStream(finalBytes);
                }

                @Override
                public void transferTo(File dest) throws IOException {
                    Files.write(dest.toPath(), finalBytes);
                }
            };
        } finally {
            safeDelete(tempInput);
            safeDelete(tempAvi);
        }
    }

    public byte[] encode(MultipartFile videoFile, String message, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 16 characters long.");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("Message must not be empty.");
        }

        Path tempInputPath = null;
        Path framesDir = null;
        Path outputPath = null;

        try {
            tempInputPath = Files.createTempFile("input_", ".avi");
            Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);

            // Encrypt the message using your custom encryption
            String encryptedMessage = VideoEncryptionUtil.encrypt(message, key);
            System.out.println("Message length: " + message.length());
            System.out.println("Encrypted message length: " + encryptedMessage.length());

            // Get video info (width/height/fps) to compute capacity
            VideoInfo videoInfo = getVideoInfo(tempInputPath.toString());
            int pixelsPerFrame = videoInfo.width * videoInfo.height;
            int bitsPerFrame = pixelsPerFrame; // 1 pixel per bit
            int charsByPixels = bitsPerFrame / 8; // Characters capacity by pixel budget

            // Effective per-frame char capacity = min(MAX_CHARS_PER_FRAME, charsByPixels -
            // delimiter)
            int effectivePerFrame = Math.min(MAX_CHARS_PER_FRAME,
                    Math.max(0, charsByPixels - FRAME_DELIM.length()));
            if (effectivePerFrame <= 0) {
                throw new IllegalStateException("Frame capacity is too small to embed any data.");
            }

            // Split encrypted message into chunks respecting effective capacity
            List<String> chunks = splitMessage(encryptedMessage, effectivePerFrame);

            // Keep cap for stability: previous limit was 10 frames
            int framesNeeded = chunks.size();
            if (framesNeeded > 10) {
                throw new IllegalArgumentException("Message too long for the video.");
            }

            // Compute required pixels (with per-frame delimiters)
            long totalCharsWithDelims = (long) encryptedMessage.length() + (long) framesNeeded * FRAME_DELIM.length();
            long requiredPixels = totalCharsWithDelims * 8L;

            System.out.println("Pixels per frame: " + pixelsPerFrame);
            System.out.println("Effective per-frame char capacity: " + effectivePerFrame);
            System.out.println("Frames needed for encoding: " + framesNeeded);
            System.out.println("Total required pixels (with delimiters): " + requiredPixels);

            // Extract only the specific frames we need to modify
            framesDir = Files.createTempDirectory("frames");
            extractSpecificFrames(tempInputPath.toString(), framesDir.toString(), framesNeeded);

            File[] frameFiles = new File(framesDir.toString()).listFiles((dir, name) -> name.endsWith(".png"));
            if (frameFiles == null || frameFiles.length < framesNeeded) {
                throw new Exception("Not enough frames extracted (expected " + framesNeeded + ").");
            }
            Arrays.sort(frameFiles, Comparator.comparing(File::getName));

            // Encode each chunk into corresponding frame (append FRAME_DELIM per frame)
            encodeChunksAcrossFrames(frameFiles, chunks, pixelsPerFrame);

            // Rebuild video
            outputPath = Files.createTempFile("output_", ".avi");
            createVideoWithSelectiveFrameReplacement(
                    tempInputPath.toString(),
                    framesDir.toString(),
                    outputPath.toString(),
                    key,
                    framesNeeded,
                    videoInfo.fps);

            return Files.readAllBytes(outputPath);
        } finally {
            cleanup(tempInputPath, framesDir, outputPath);
        }
    }

    public EncodingResult encodeWithMetrics(MultipartFile videoFile, String message, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }

        Path tempInputPath = null;
        Path originalCopyPath = null;
        Path encodedTempPath = null;

        try {
            tempInputPath = Files.createTempFile("input_", ".avi");
            Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);

            originalCopyPath = Files.createTempFile("original_copy_", ".avi");
            Files.copy(tempInputPath, originalCopyPath, StandardCopyOption.REPLACE_EXISTING);

            byte[] encodedVideo = encode(videoFile, message, key);

            encodedTempPath = Files.createTempFile("encoded_temp_", ".avi");
            Files.write(encodedTempPath, encodedVideo);

            Map<String, Object> qualityMetrics = videoQualityMetrics.calculateQualityMetrics(
                    originalCopyPath.toString(),
                    encodedTempPath.toString(),
                    10);

            return new EncodingResult(encodedVideo, qualityMetrics);
        } finally {
            safeDelete(originalCopyPath);
            safeDelete(encodedTempPath);
            safeDelete(tempInputPath);
        }
    }

    /** Split message into chunks up to perFrameLimit characters. */
    private List<String> splitMessage(String msg, int perFrameLimit) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < msg.length(); i += perFrameLimit) {
            parts.add(msg.substring(i, Math.min(msg.length(), i + perFrameLimit)));
        }
        return parts;
    }

    /** Get width, height, and fps using ffprobe. */
    private VideoInfo getVideoInfo(String videoPath) throws Exception {
        List<String> cmd = Arrays.asList(
                "ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "csv=p=0",
                videoPath);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String line;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            line = reader.readLine();
        }
        int code = process.waitFor();

        if (code != 0 || line == null) {
            throw new Exception("Could not get video information");
        }

        String[] parts = line.split(",");
        if (parts.length < 3) {
            throw new Exception("Could not parse video information: " + line);
        }

        int width = Integer.parseInt(parts[0].trim());
        int height = Integer.parseInt(parts[1].trim());
        String rFrameRate = parts[2].trim(); // e.g. "30000/1001" or "25/1" or "30"
        double fps = parseFps(rFrameRate);

        return new VideoInfo(width, height, fps);
    }

    private static double parseFps(String rFrameRate) {
        try {
            if (rFrameRate.contains("/")) {
                String[] xy = rFrameRate.split("/", 2);
                double num = Double.parseDouble(xy[0]);
                double den = Double.parseDouble(xy[1]);
                if (den == 0)
                    return 30.0;
                return num / den;
            }
            return Double.parseDouble(rFrameRate);
        } catch (Exception e) {
            return 30.0;
        }
    }

    /** Extract only the first N frames needed for steganography. */
    private void extractSpecificFrames(String videoPath, String framesDir, int frameCount) throws Exception {
        String framePattern = Paths.get(framesDir, "frame_%06d.png").toString();
        List<String> cmd = Arrays.asList(
                "ffmpeg", "-y",
                "-threads", String.valueOf(Runtime.getRuntime().availableProcessors()),
                "-i", videoPath,
                "-vf", "select=lt(n\\," + frameCount + ")",
                "-vsync", "vfr",
                framePattern);

        int exitCode = runAndPipe(cmd, "[ffmpeg extract-specific]");
        if (exitCode != 0) {
            throw new Exception("Failed to extract specific frames. Exit code: " + exitCode);
        }
        logger.info("Extracted {} specific frames for modification", frameCount);
    }

    /**
     * Encode each chunk into its corresponding frame.
     * For each frame: writes (chunk + FRAME_DELIM). No global null terminator.
     */
    private void encodeChunksAcrossFrames(File[] frameFiles, List<String> chunks, int pixelsPerFrame) throws Exception {
        int framesNeeded = chunks.size();
        int threads = Math.min(framesNeeded, Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService ex = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Void>> futures = new ArrayList<>(framesNeeded);

            for (int frameIndex = 0; frameIndex < framesNeeded; frameIndex++) {
                final int idx = frameIndex;
                futures.add(ex.submit(() -> {
                    File frameFile = frameFiles[idx];
                    BufferedImage currentFrame = javax.imageio.ImageIO.read(frameFile);

                    String payload = chunks.get(idx) + FRAME_DELIM;
                    int requiredBits = payload.length() * 8;
                    if (requiredBits > pixelsPerFrame) {
                        throw new IllegalStateException("Chunk exceeds per-frame pixel capacity: need " + requiredBits
                                + " bits, have " + pixelsPerFrame);
                    }

                    int width = currentFrame.getWidth();
                    int height = currentFrame.getHeight();
                    int pixelIndex = 0;

                    for (int i = 0; i < payload.length(); i++) {
                        char c = payload.charAt(i);
                        for (int bitPos = 0; bitPos < 8; bitPos++) {
                            int bit = (c >> (7 - bitPos)) & 1;
                            int x = pixelIndex % width;
                            int y = pixelIndex / width;
                            if (y >= height) {
                                throw new IllegalStateException("Ran out of pixels while encoding frame " + (idx + 1));
                            }
                            int val = (bit == 1) ? 245 : 10;
                            int newRgb = (val << 16) | (val << 8) | val;
                            currentFrame.setRGB(x, y, newRgb);
                            pixelIndex++;
                        }
                    }

                    javax.imageio.ImageIO.write(currentFrame, "png", frameFile);
                    logger.debug("Frame {} encoded. Chars: {}  Pixels used: {}", idx + 1, payload.length(),
                            payload.length() * 8);
                    return null;
                }));
            }

            for (Future<Void> f : futures) {
                f.get(); // propagate exceptions
            }

            logger.info("All {} frame(s) encoded with per-frame delimiter '{}'.", framesNeeded, FRAME_DELIM);
        } finally {
            ex.shutdownNow();
        }
    }

    private void createVideoWithSelectiveFrameReplacement(
            String originalVideoPath,
            String modifiedFramesDir,
            String outputPath,
            String key,
            int modifiedFrameCount,
            double fps) throws Exception {

        // Encode the key in Base64 and embed it in video metadata
        String encodedKey = Base64.getEncoder().encodeToString(key.getBytes());

        createVideoWithMinimalCompression(
                originalVideoPath, modifiedFramesDir, outputPath, encodedKey, modifiedFrameCount, fps);
    }

    private void createVideoWithMinimalCompression(
            String originalVideoPath,
            String modifiedFramesDir,
            String outputPath,
            String encodedKey,
            int modifiedFrameCount,
            double fps) throws Exception {

        logger.info("Using fallback method with minimal compression");

        Path tempAllFramesDir = Files.createTempDirectory("all_frames");
        try {
            // 1) Extract all frames (high quality)
            String allPattern = Paths.get(tempAllFramesDir.toString(), "frame_%06d.png").toString();
            List<String> extractAll = Arrays.asList(
                    "ffmpeg", "-y",
                    "-threads", String.valueOf(Runtime.getRuntime().availableProcessors()),
                    "-i", originalVideoPath,
                    "-q:v", "1",
                    allPattern);

            int extractExit = runAndPipe(extractAll, "[ffmpeg extract-all]");
            if (extractExit != 0) {
                throw new Exception("Failed to extract all frames.");
            }

            // 2) Replace only the modified frames (by filename)
            File[] modified = new File(modifiedFramesDir).listFiles((d, n) -> n.endsWith(".png"));
            if (modified != null && modified.length > 0) {
                List<File> toReplace = Arrays.stream(modified)
                        .sorted(Comparator.comparing(File::getName))
                        .limit(modifiedFrameCount)
                        .collect(Collectors.toList());

                for (File src : toReplace) {
                    Path dst = Paths.get(tempAllFramesDir.toString(), src.getName());
                    Files.copy(src.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);
                    logger.debug("Replaced frame {}", src.getName());
                }
            }

            // 3) Recreate video using source fps
            String inputPattern = Paths.get(tempAllFramesDir.toString(), "frame_%06d.png").toString();
            String fpsString = String.format(Locale.US, "%.3f", (fps > 0 ? fps : 29.970));
            List<String> finalCmd = Arrays.asList(
                    "ffmpeg", "-y",
                    "-threads", String.valueOf(Runtime.getRuntime().availableProcessors()),
                    "-framerate", fpsString,
                    "-i", inputPattern,
                    "-i", originalVideoPath,
                    "-c:v", "libxvid",
                    "-q:v", "2",
                    "-c:a", "copy",
                    "-map", "0:v:0",
                    "-map", "1:a:0?",
                    "-metadata", "title=" + encodedKey,
                    "-shortest",
                    outputPath);

            int finalExitCode = runAndPipe(finalCmd, "[ffmpeg build]");
            if (finalExitCode != 0) {
                throw new Exception("Fallback video creation failed with exit code: " + finalExitCode);
            }
        } finally {
            cleanup(tempAllFramesDir);
        }
    }

    /**
     * Run a process and pipe combined stdout/stderr using logger.
     * Waits with a timeout and forcibly destroys hung processes.
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

    private void cleanup(Path... paths) {
        for (Path path : paths) {
            safeDelete(path);
        }
    }

    private void safeDelete(Path path) {
        if (path == null)
            return;
        try {
            if (Files.isDirectory(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
        }
    }

    private static class VideoInfo {
        final int width;
        final int height;
        final double fps;

        VideoInfo(int width, int height, double fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }
    }

    public record EncodingResult(byte[] encodedVideo, Map<String, Object> qualityMetrics) {
    }
}
