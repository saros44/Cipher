package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class VideoEncodeService {

    private final VideoQualityMetrics videoQualityMetrics;

    public VideoEncodeService(VideoQualityMetrics videoQualityMetrics) {
        this.videoQualityMetrics = videoQualityMetrics;
    }

    public byte[] encode(MultipartFile videoFile, String message, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }

        Path tempInputPath = Files.createTempFile("input_", ".avi");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);

        // Encrypt the message using custom encryption
        String encryptedMessage = VideoEncryptionUtil.encrypt(message, key);
        System.out.println("Message length: " + message.length());
        System.out.println("Encrypted message length: " + encryptedMessage.length());

        // Calculate how many frames we actually need to modify
        int requiredPixels = (encryptedMessage.length() + 1) * 8; // +1 for null terminator

        // Get video info to calculate frame capacity
        VideoInfo videoInfo = getVideoInfo(tempInputPath.toString());
        int frameCapacity = videoInfo.width * videoInfo.height;
        int framesNeeded = (int) Math.ceil((double) requiredPixels / frameCapacity);
        framesNeeded = Math.max(1, Math.min(framesNeeded, 10)); // Use 1-10 frames max

        System.out.println("Frames needed for encoding: " + framesNeeded);
        System.out.println("Frame capacity: " + frameCapacity + " pixels");
        System.out.println("Total required pixels: " + requiredPixels);

        if (requiredPixels > frameCapacity * framesNeeded) {
            throw new Exception("Message too long. Required: " + requiredPixels + " pixels, Available: " + (frameCapacity * framesNeeded) + " pixels");
        }

        // Extract only the specific frames we need to modify
        Path framesDir = Files.createTempDirectory("frames");
        extractSpecificFrames(tempInputPath.toString(), framesDir.toString(), framesNeeded);

        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        if (frameFiles == null || frameFiles.length == 0) {
            throw new Exception("No frames extracted from video");
        }
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));

        // Encode message into the extracted frames
        encodeMessageAcrossFrames(frameFiles, encryptedMessage, frameCapacity);

        // Create output video using selective frame replacement
        Path outputPath = Files.createTempFile("output_", ".avi");
        createVideoWithSelectiveFrameReplacement(tempInputPath.toString(), framesDir.toString(), outputPath.toString(), key, framesNeeded);

        byte[] result = Files.readAllBytes(outputPath);
        cleanup(tempInputPath, framesDir, outputPath);
        return result;
    }

    public EncodingResult encodeWithMetrics(MultipartFile videoFile, String message, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }

        Path tempInputPath = Files.createTempFile("input_", ".avi");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);

        // Keep a copy of original for quality comparison
        Path originalCopyPath = Files.createTempFile("original_copy_", ".avi");
        Files.copy(tempInputPath, originalCopyPath, StandardCopyOption.REPLACE_EXISTING);

        try {
            // Encode the video
            byte[] encodedVideo = encode(videoFile, message, key);

            // Save encoded video temporarily for quality analysis
            Path encodedTempPath = Files.createTempFile("encoded_temp_", ".avi");
            Files.write(encodedTempPath, encodedVideo);

            // Calculate quality metrics for first 10 frames
            Map<String, Object> qualityMetrics = videoQualityMetrics.calculateQualityMetrics(
                originalCopyPath.toString(),
                encodedTempPath.toString(),
                10
            );

            // Cleanup temporary files
            Files.deleteIfExists(originalCopyPath);
            Files.deleteIfExists(encodedTempPath);

            return new EncodingResult(encodedVideo, qualityMetrics);
        } catch (Exception e) {
            Files.deleteIfExists(originalCopyPath);
            throw e;
        } finally {
            Files.deleteIfExists(tempInputPath);
        }
    }

    private VideoInfo getVideoInfo(String videoPath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("ffprobe", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=width,height,r_frame_rate",
                "-of", "csv=p=0", videoPath);

        Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line = reader.readLine();
        reader.close();
        process.waitFor();

        if (line != null) {
            String[] parts = line.split(",");
            if (parts.length >= 2) {
                int width = Integer.parseInt(parts[0]);
                int height = Integer.parseInt(parts[1]);
                return new VideoInfo(width, height);
            }
        }
        throw new Exception("Could not get video information");
    }

    private void extractSpecificFrames(String videoPath, String framesDir, int frameCount) throws Exception {
        // Extract only the first N frames that we need for steganography
        String framePattern = framesDir + "/frame_%06d.png";
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-i", videoPath,
                "-vf", "select=lt(n\\," + frameCount + ")",
                "-vsync", "vfr",
                framePattern, "-y");

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output for debugging
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains("error") || line.contains("Error")) {
                System.out.println("FFmpeg: " + line);
            }
        }
        reader.close();

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("Failed to extract specific frames. Exit code: " + exitCode);
        }

        System.out.println("Extracted " + frameCount + " specific frames for modification");
    }

    private void encodeMessageAcrossFrames(File[] frameFiles, String message, int frameCapacity) throws Exception {
        String messageWithTerminator = message + '\0';
        System.out.println("Encoding message across frames, total length: " + messageWithTerminator.length());

        // Calculate optimal distribution across frames
        int totalChars = messageWithTerminator.length();
        int charsPerFrame = Math.min(frameCapacity / 8, 15000); // Max 15k chars per frame for stability
        int framesNeeded = (int) Math.ceil((double) totalChars / charsPerFrame);
        framesNeeded = Math.min(framesNeeded, frameFiles.length);

        System.out.println("Distributing " + totalChars + " characters across " + framesNeeded + " frames");
        System.out.println("Characters per frame: " + charsPerFrame);

        int charIndex = 0;

        // Encode across multiple frames
        for (int frameIndex = 0; frameIndex < framesNeeded && charIndex < totalChars; frameIndex++) {
            BufferedImage currentFrame = javax.imageio.ImageIO.read(frameFiles[frameIndex]);
            int width = currentFrame.getWidth();
            int height = currentFrame.getHeight();
            int pixelIndex = 0;

            // Calculate how many characters to encode in this frame
            int charsInThisFrame = Math.min(charsPerFrame, totalChars - charIndex);
            int endCharIndex = charIndex + charsInThisFrame;

            System.out.println("Frame " + (frameIndex + 1) + ": encoding characters " + charIndex + " to " + (endCharIndex - 1));

            // Encode characters for this frame
            while (charIndex < endCharIndex && pixelIndex < frameCapacity) {
                char currentChar = messageWithTerminator.charAt(charIndex);

                // Encode each bit of the character
                for (int bitPos = 0; bitPos < 8 && pixelIndex < frameCapacity; bitPos++) {
                    int bit = (currentChar >> (7 - bitPos)) & 1;

                    int x = pixelIndex % width;
                    int y = pixelIndex / width;

                    if (y >= height) {
                        System.out.println("Reached end of frame " + (frameIndex + 1) + " at pixel " + pixelIndex);
                        break;
                    }

                    int rgb = currentFrame.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    // Use high contrast encoding for better survival through compression
                    if (bit == 1) {
                        red = 245;   // Very high value for '1'
                        green = 245;
                        blue = 245;
                    } else {
                        red = 10;    // Very low value for '0'
                        green = 10;
                        blue = 10;
                    }

                    int newRgb = (red << 16) | (green << 8) | blue;
                    currentFrame.setRGB(x, y, newRgb);
                    pixelIndex++;
                }

                charIndex++;

                // Progress logging
                if (charIndex % 2000 == 0) {
                    System.out.println("Encoded " + charIndex + "/" + totalChars + " characters (Frame: " + (frameIndex + 1) + ")");
                }
            }

            // Save the modified frame
            javax.imageio.ImageIO.write(currentFrame, "png", frameFiles[frameIndex]);
            System.out.println("Frame " + (frameIndex + 1) + " completed with " + (charIndex - (endCharIndex - charsInThisFrame)) + " characters, " + pixelIndex + " pixels used");
        }

        System.out.println("Message encoding completed across " + framesNeeded + " frames. Total characters encoded: " + charIndex);
    }

    private void createVideoWithSelectiveFrameReplacement(String originalVideoPath, String modifiedFramesDir,
                                                          String outputPath, String key, int modifiedFrameCount) throws Exception {
        // Encode the key in Base64 and embed it in video metadata
        String encodedKey = Base64.getEncoder().encodeToString(key.getBytes());

        // Use fallback method immediately for better reliability with multi-frame encoding
        createVideoWithMinimalCompression(originalVideoPath, modifiedFramesDir, outputPath, encodedKey, modifiedFrameCount);
    }

    private void createVideoWithMinimalCompression(String originalVideoPath, String modifiedFramesDir,
                                                   String outputPath, String encodedKey, int modifiedFrameCount) throws Exception {
        System.out.println("Using fallback method with minimal compression");

        // Use highest quality settings and lossless compression where possible
        List<String> command = new java.util.ArrayList<>();
        command.add("ffmpeg");
        command.add("-i");
        command.add(originalVideoPath);

        // Extract all frames with high quality
        Path tempAllFramesDir = Files.createTempDirectory("all_frames");
        ProcessBuilder extractAll = new ProcessBuilder("ffmpeg", "-i", originalVideoPath,
                "-q:v", "1", // Highest quality
                tempAllFramesDir.toString() + "/frame_%06d.png", "-y");
        extractAll.inheritIO();
        Process extractProcess = extractAll.start();
        extractProcess.waitFor();

        // Replace only the modified frames
        for (int i = 1; i <= modifiedFrameCount; i++) {
            String srcFrame = modifiedFramesDir + "/frame_" + String.format("%06d", i) + ".png";
            String dstFrame = tempAllFramesDir.toString() + "/frame_" + String.format("%06d", i) + ".png";

            File srcFile = new File(srcFrame);
            if (srcFile.exists()) {
                Files.copy(Paths.get(srcFrame), Paths.get(dstFrame), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("Replaced frame " + i);
            }
        }

        // Recreate video with minimal quality loss
        List<String> finalCommand = new java.util.ArrayList<>();
        finalCommand.add("ffmpeg");
        finalCommand.add("-framerate");
        finalCommand.add("29.97");
        finalCommand.add("-i");
        finalCommand.add(tempAllFramesDir.toString() + "/frame_%06d.png");
        finalCommand.add("-i");
        finalCommand.add(originalVideoPath);
        finalCommand.add("-c:v");
        finalCommand.add("libxvid");
        finalCommand.add("-q:v");
        finalCommand.add("2"); // High quality
        finalCommand.add("-c:a");
        finalCommand.add("copy");
        finalCommand.add("-map");
        finalCommand.add("0:v:0");
        finalCommand.add("-map");
        finalCommand.add("1:a:0?"); // Optional audio mapping
        finalCommand.add("-metadata");
        finalCommand.add("title=" + encodedKey);
        finalCommand.add("-shortest");
        finalCommand.add(outputPath);
        finalCommand.add("-y");

        ProcessBuilder finalPb = new ProcessBuilder(finalCommand);
        finalPb.inheritIO();
        Process finalProcess = finalPb.start();
        int finalExitCode = finalProcess.waitFor();

        // Cleanup temporary directory
        cleanup(tempAllFramesDir);

        if (finalExitCode != 0) {
            throw new Exception("Fallback video creation failed with exit code: " + finalExitCode);
        }
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

    private static class VideoInfo {
        final int width;
        final int height;

        VideoInfo(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public record EncodingResult(byte[] encodedVideo, Map<String, Object> qualityMetrics) {}
}
