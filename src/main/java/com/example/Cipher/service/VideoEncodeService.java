package com.example.Cipher.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;

@Service
public class VideoEncodeService {

    public byte[] encode(MultipartFile videoFile, String message, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }

        Path tempInputPath = Files.createTempFile("input_", ".avi");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);
        Path framesDir = Files.createTempDirectory("frames");

        extractFrames(tempInputPath.toString(), framesDir.toString());

        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        if (frameFiles == null) throw new Exception("No frames extracted from video");
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));

        // Encrypt the message using custom encryption
        String encryptedMessage = VideoEncryptionUtil.encrypt(message, key);
        System.out.println("Message length: " + message.length());
        System.out.println("Encrypted message length: " + encryptedMessage.length());

        // Calculate total capacity across multiple frames for large messages
        BufferedImage firstFrame = javax.imageio.ImageIO.read(frameFiles[0]);
        int frameCapacity = firstFrame.getWidth() * firstFrame.getHeight();
        int totalFrames = frameFiles.length;
        int totalCapacity = frameCapacity * Math.min(totalFrames, 10); // Use up to 10 frames

        System.out.println("Single frame capacity: " + frameCapacity + " pixels");
        System.out.println("Total frames available: " + totalFrames);
        System.out.println("Using frames for encoding: " + Math.min(totalFrames, 10));
        System.out.println("Total encoding capacity: " + totalCapacity + " pixels");

        // Check capacity for multi-channel encoding (each pixel = 1 bit, 8 pixels = 1 char)
        int requiredPixels = (encryptedMessage.length() + 1) * 8; // +1 for null terminator
        if (requiredPixels > totalCapacity) {
            throw new Exception("Message too long. Required: " + requiredPixels + " pixels, Available: " + totalCapacity + " pixels");
        }

        // Encode across multiple frames if needed
        encodeMessageAcrossFrames(frameFiles, encryptedMessage, frameCapacity);

        Path outputPath = Files.createTempFile("output_", ".avi");
        // Pass the original video path for audio extraction
        createVideoFromFrames(framesDir.toString(), outputPath.toString(), key, tempInputPath.toString());

        byte[] result = Files.readAllBytes(outputPath);
        cleanup(tempInputPath, framesDir, outputPath);
        return result;
    }

    private void encodeMessageAcrossFrames(File[] frameFiles, String message, int frameCapacity) throws Exception {
        String messageWithTerminator = message + '\0';
        System.out.println("Encoding message across frames, total length: " + messageWithTerminator.length());

        int currentFrameIndex = 0;
        int pixelIndex = 0;
        BufferedImage currentFrame = javax.imageio.ImageIO.read(frameFiles[currentFrameIndex]);
        int width = currentFrame.getWidth();
        int height = currentFrame.getHeight();

        // Encode each character
        for (int charPos = 0; charPos < messageWithTerminator.length(); charPos++) {
            char currentChar = messageWithTerminator.charAt(charPos);

            // Encode each bit of the character
            for (int bitPos = 0; bitPos < 8; bitPos++) {
                int bit = (currentChar >> (7 - bitPos)) & 1;

                // Check if we need to move to next frame
                if (pixelIndex >= frameCapacity) {
                    // Save current frame and move to next
                    javax.imageio.ImageIO.write(currentFrame, "png", frameFiles[currentFrameIndex]);
                    currentFrameIndex++;

                    if (currentFrameIndex >= frameFiles.length) {
                        throw new Exception("Ran out of frames during encoding");
                    }

                    currentFrame = javax.imageio.ImageIO.read(frameFiles[currentFrameIndex]);
                    pixelIndex = 0;
                    System.out.println("Moved to frame " + currentFrameIndex + " for encoding");
                }

                int x = pixelIndex % width;
                int y = pixelIndex / width;

                int rgb = currentFrame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;

                // Use optimized encoding for large messages - less variation to survive compression better
                if (bit == 1) {
                    red = 240;   // High value for '1'
                    green = 240;
                    blue = 240;
                } else {
                    red = 15;    // Low value for '0'
                    green = 15;
                    blue = 15;
                }

                int newRgb = (red << 16) | (green << 8) | blue;
                currentFrame.setRGB(x, y, newRgb);
                pixelIndex++;
            }

            // Progress logging for large messages
            if (charPos % 1000 == 0 || charPos == messageWithTerminator.length() - 1) {
                System.out.println("Encoded " + charPos + "/" + messageWithTerminator.length() +
                                 " characters (Frame: " + currentFrameIndex + ", Pixel: " + pixelIndex + ")");
            }
        }

        // Save the final frame
        javax.imageio.ImageIO.write(currentFrame, "png", frameFiles[currentFrameIndex]);
        System.out.println("Message encoding completed. Used " + (currentFrameIndex + 1) + " frames, " + pixelIndex + " pixels in final frame");
    }

    private void extractFrames(String videoPath, String framesDir) throws Exception {
        new ProcessBuilder("ffmpeg", "-i", videoPath, framesDir + "/frame_%06d.png", "-y")
                .inheritIO().start().waitFor();
    }

    private void createVideoFromFrames(String framesDir, String outputPath, String key, String originalVideoPath) throws Exception {
        // Encode the key in Base64 and embed it in video metadata
        String encodedKey = Base64.getEncoder().encodeToString(key.getBytes());

        // Extract audio from the original video with better format handling
        Path audioPath = Paths.get(System.getProperty("java.io.tmpdir"), "extracted_audio.wav");

        try {
            // First, probe the video to check if it has audio streams
            ProcessBuilder probeBuilder = new ProcessBuilder("ffprobe", "-v", "error",
                "-select_streams", "a:0", "-show_entries", "stream=codec_name",
                "-of", "csv=p=0", originalVideoPath);
            Process probeProcess = probeBuilder.start();

            BufferedReader probeReader = new BufferedReader(new InputStreamReader(probeProcess.getInputStream()));
            String audioCodec = probeReader.readLine();
            probeReader.close();
            int probeExitCode = probeProcess.waitFor();

            System.out.println("Audio probe result - Exit code: " + probeExitCode + ", Codec: " + audioCodec);

            boolean hasAudio = false;

            if (probeExitCode == 0 && audioCodec != null && !audioCodec.trim().isEmpty()) {
                System.out.println("Found audio stream with codec: " + audioCodec);

                // Try to extract audio - convert to WAV for better compatibility
                ProcessBuilder audioExtractor = new ProcessBuilder("ffmpeg", "-i", originalVideoPath,
                    "-vn", // No video
                    "-acodec", "pcm_s16le", // Convert to PCM WAV
                    "-ar", "44100", // Standard sample rate
                    "-ac", "2", // Stereo
                    audioPath.toString(), "-y");

                // Redirect error output to capture any issues
                audioExtractor.redirectErrorStream(true);
                Process audioProcess = audioExtractor.start();

                // Read the output to see any errors
                BufferedReader audioReader = new BufferedReader(new InputStreamReader(audioProcess.getInputStream()));
                StringBuilder audioOutput = new StringBuilder();
                String line;
                while ((line = audioReader.readLine()) != null) {
                    audioOutput.append(line).append("\n");
                }
                audioReader.close();

                int audioExitCode = audioProcess.waitFor();
                System.out.println("Audio extraction exit code: " + audioExitCode);
                System.out.println("Audio extraction output: " + audioOutput.toString());

                // Check if audio file was created and has content
                if (audioExitCode == 0 && Files.exists(audioPath) && Files.size(audioPath) > 1024) { // At least 1KB
                    hasAudio = true;
                    System.out.println("Audio extraction successful. File size: " + Files.size(audioPath) + " bytes");
                } else {
                    System.out.println("Audio extraction failed or produced empty file");
                    if (Files.exists(audioPath)) {
                        Files.delete(audioPath);
                    }
                }
            } else {
                System.out.println("No audio stream detected in source video");
            }

            // Create video with frames and include audio if it was extracted successfully
            ProcessBuilder videoBuilder;
            if (hasAudio) {
                System.out.println("Creating video with audio track");
                // Include audio in the final video
                videoBuilder = new ProcessBuilder("ffmpeg", "-framerate", "29.97",
                    "-i", framesDir + "/frame_%06d.png",
                    "-i", audioPath.toString(),
                    "-c:v", "libxvid",
                    "-c:a", "aac", // Use AAC for better compatibility
                    "-b:a", "128k", // Set audio bitrate
                    "-map", "0:v:0", // Map video from first input
                    "-map", "1:a:0", // Map audio from second input
                    "-metadata", "title=" + encodedKey,
                    "-metadata", "comment=Steganography Video",
                    "-shortest", // Match shortest stream duration
                    outputPath, "-y");
            } else {
                System.out.println("Creating video without audio");
                // No audio track available, create video-only
                videoBuilder = new ProcessBuilder("ffmpeg", "-framerate", "29.97",
                    "-i", framesDir + "/frame_%06d.png",
                    "-c:v", "libxvid",
                    "-metadata", "title=" + encodedKey,
                    "-metadata", "comment=Steganography Video",
                    outputPath, "-y");
            }

            // Redirect error output for video creation
            videoBuilder.redirectErrorStream(true);
            Process videoProcess = videoBuilder.start();

            // Read the output to see any errors
            BufferedReader videoReader = new BufferedReader(new InputStreamReader(videoProcess.getInputStream()));
            StringBuilder videoOutput = new StringBuilder();
            String line;
            while ((line = videoReader.readLine()) != null) {
                videoOutput.append(line).append("\n");
            }
            videoReader.close();

            int videoExitCode = videoProcess.waitFor();
            System.out.println("Video creation exit code: " + videoExitCode);
            if (videoExitCode != 0) {
                System.out.println("Video creation output: " + videoOutput.toString());
                throw new Exception("FFmpeg video creation failed with exit code: " + videoExitCode);
            }

            // Clean up temporary audio file
            if (Files.exists(audioPath)) {
                Files.delete(audioPath);
            }

        } catch (Exception e) {
            System.out.println("Audio processing failed, creating video without audio: " + e.getMessage());
            e.printStackTrace();

            // Clean up audio file if it exists
            if (Files.exists(audioPath)) {
                try {
                    Files.delete(audioPath);
                } catch (IOException ignored) {}
            }

            // Fallback to video-only creation
            try {
                ProcessBuilder fallbackBuilder = new ProcessBuilder("ffmpeg", "-framerate", "29.97",
                    "-i", framesDir + "/frame_%06d.png",
                    "-c:v", "libxvid",
                    "-metadata", "title=" + encodedKey,
                    "-metadata", "comment=Steganography Video",
                    outputPath, "-y");
                fallbackBuilder.redirectErrorStream(true);
                Process fallbackProcess = fallbackBuilder.start();

                BufferedReader fallbackReader = new BufferedReader(new InputStreamReader(fallbackProcess.getInputStream()));
                StringBuilder fallbackOutput = new StringBuilder();
                String line;
                while ((line = fallbackReader.readLine()) != null) {
                    fallbackOutput.append(line).append("\n");
                }
                fallbackReader.close();

                int fallbackExitCode = fallbackProcess.waitFor();
                if (fallbackExitCode != 0) {
                    System.out.println("Fallback video creation failed: " + fallbackOutput.toString());
                    throw new Exception("Both audio and fallback video creation failed");
                }
            } catch (Exception fallbackException) {
                System.out.println("Fallback video creation also failed: " + fallbackException.getMessage());
                throw new Exception("Complete video creation failure: " + fallbackException.getMessage());
            }
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
}
