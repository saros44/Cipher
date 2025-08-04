package com.example.Cipher.service;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import static com.example.Cipher.service.VideoEncryptionUtil.isValidBase64;

@Service
public class VideoDecodeService {

    private static final int AES_KEY_SIZE = 16; // AES key size in bytes (128 bits)

    public String decode(MultipartFile videoFile, String key) throws Exception {
        if (key == null || key.length() < 8) {
            throw new IllegalArgumentException("Secret key must be at least 8 characters long.");
        }

        Path tempInputPath = Files.createTempFile("input_", ".avi");
        Files.copy(videoFile.getInputStream(), tempInputPath, StandardCopyOption.REPLACE_EXISTING);
        Path framesDir = Files.createTempDirectory("frames");

        // Extract the secret key from video metadata
        String extractedKey = extractKeyFromMetadata(tempInputPath.toString());
        System.out.println("Extracted key from metadata: " + (extractedKey != null ? "Found" : "Not found"));

        // Verify the provided key matches the embedded key
        if (extractedKey == null || !extractedKey.equals(key)) {
            throw new IllegalArgumentException("Invalid key - provided key doesn't match the key embedded in video");
        }

        extractFrames(tempInputPath.toString(), framesDir.toString());

        File[] frameFiles = framesDir.toFile().listFiles((dir, name) -> name.endsWith(".png"));
        if (frameFiles == null) throw new Exception("No frames extracted from video");
        Arrays.sort(frameFiles, Comparator.comparing(File::getName));

        // Store the frame files directory for multi-frame extraction
        this.currentFramesDir = framesDir;
        this.currentFrameFiles = frameFiles;

        // Decode from frames
        BufferedImage firstFrame = javax.imageio.ImageIO.read(frameFiles[0]);

        // Extract the encrypted message using robust extraction
        String encryptedMessage = extractMessageFromFrame(firstFrame);
        System.out.println("Extracted encrypted message length: " + encryptedMessage.length());
        System.out.println("Extracted encrypted message: " + encryptedMessage);

        if (encryptedMessage.isEmpty()) {
            throw new Exception("No message found in video");
        }

        // Decrypt the message using custom encryption with the extracted key
        String decryptedMessage = VideoEncryptionUtil.decrypt(encryptedMessage, extractedKey);

        cleanup(tempInputPath, framesDir);
        return decryptedMessage;
    }

    // Add instance variables to store frame information for multi-frame extraction
    private Path currentFramesDir;
    private File[] currentFrameFiles;

    private String extractKeyFromMetadata(String videoPath) throws Exception {
        try {
            Process process = new ProcessBuilder("ffprobe", "-v", "error",
                    "-show_entries", "format_tags=title",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    videoPath).start();

            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String encodedKey = reader.readLine();
            reader.close();

            int exitCode = process.waitFor();
            if (exitCode != 0 || encodedKey == null || encodedKey.trim().isEmpty()) {
                return null;
            }

            // Decode the Base64 encoded key
            byte[] decodedBytes = Base64.getDecoder().decode(encodedKey.trim());
            return new String(decodedBytes);

        } catch (Exception e) {
            System.out.println("Failed to extract key from metadata: " + e.getMessage());
            return null;
        }
    }

    private String extractMessageFromFrame(BufferedImage frame) {
        // For large messages, start with optimized multi-frame extraction
        String multiFrameResult = extractFromMultipleFrames();
        if (!multiFrameResult.isEmpty()) {
            System.out.println("Multi-frame extraction successful: " + multiFrameResult.length() + " characters");
            return multiFrameResult;
        }

        // Fallback to single frame extraction
        System.out.println("Starting single-frame message extraction");
        String multiChannelResult = extractWithMultiChannelRedundancy(frame);
        if (!multiChannelResult.isEmpty()) {
            System.out.println("Multi-channel extraction successful: " + multiChannelResult);
            return multiChannelResult;
        }

        // Try traditional redundancy extraction
        String redundancyResult = extractWithRedundancy(frame);
        if (!redundancyResult.isEmpty()) {
            System.out.println("Traditional redundancy extraction successful: " + redundancyResult);
            return redundancyResult;
        }

        // Fallback to multiple threshold approach
        System.out.println("All redundancy extractions failed, trying multiple strategies");

        Map<String, String> extractionResults = new HashMap<>();

        // Strategy 1: Fixed thresholds
        int[] thresholds = {120, 127, 135, 140, 150, 160, 100, 110};
        for (int threshold : thresholds) {
            String result = extractWithFixedThreshold(frame, threshold, "Fixed-" + threshold);
            if (!result.isEmpty()) {
                extractionResults.put("Fixed-" + threshold, result);
            }
        }

        // Strategy 2: Adaptive threshold
        String adaptiveResult = extractWithAdaptiveThreshold(frame);
        if (!adaptiveResult.isEmpty()) {
            extractionResults.put("Adaptive", adaptiveResult);
        }

        System.out.println("All extraction results: " + extractionResults);

        String bestResult = selectBestExtractionFromMap(extractionResults);

        if (bestResult.isEmpty()) {
            throw new IllegalArgumentException("Could not extract valid message with any strategy. Results: " + extractionResults);
        }

        return bestResult;
    }

    private String extractFromMultipleFrames() {
        try {
            System.out.println("Trying multi-frame extraction for large messages");

            // Get all frame files
            File[] frameFiles = getCurrentFrameFiles();
            if (frameFiles == null || frameFiles.length == 0) {
                return "";
            }

            StringBuilder message = new StringBuilder();
            int totalCharsExtracted = 0;
            int maxCharsToExtract = 15000; // Increased for large messages

            // Process up to 10 frames
            for (int frameIndex = 0; frameIndex < Math.min(frameFiles.length, 10); frameIndex++) {
                BufferedImage frame = javax.imageio.ImageIO.read(frameFiles[frameIndex]);
                System.out.println("Processing frame " + frameIndex + " for extraction");

                String frameResult = extractFromSingleFrameOptimized(frame, maxCharsToExtract - totalCharsExtracted);

                if (frameResult.isEmpty()) {
                    if (frameIndex == 0) {
                        // No data in first frame, probably single-frame encoding
                        return "";
                    } else {
                        // End of data
                        break;
                    }
                }

                message.append(frameResult);
                totalCharsExtracted += frameResult.length();

                // Check for null terminator or Base64 ending
                String currentMessage = message.toString();
                if (currentMessage.contains("\0") ||
                    (currentMessage.length() >= 8 && currentMessage.endsWith("="))) {
                    // Found end of message
                    int nullPos = currentMessage.indexOf('\0');
                    if (nullPos != -1) {
                        currentMessage = currentMessage.substring(0, nullPos);
                    }
                    return reconstructBase64Message(currentMessage);
                }

                if (totalCharsExtracted >= maxCharsToExtract) {
                    break;
                }
            }

            if (message.length() > 0) {
                return reconstructBase64Message(message.toString());
            }

            return "";

        } catch (Exception e) {
            System.out.println("Multi-frame extraction failed: " + e.getMessage());
            return "";
        }
    }

    private String extractFromSingleFrameOptimized(BufferedImage frame, int maxChars) {
        try {
            int width = frame.getWidth();
            int height = frame.getHeight();
            StringBuilder message = new StringBuilder();
            int pixelIndex = 0;
            int charsExtracted = 0;

            while (charsExtracted < maxChars && pixelIndex < width * height - 8) {
                int extractedChar = 0;

                // Extract 8 bits for this character
                for (int bitPos = 0; bitPos < 8; bitPos++) {
                    int x = pixelIndex % width;
                    int y = pixelIndex / width;

                    if (y >= height) {
                        break;
                    }

                    int rgb = frame.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    // Optimized bit extraction for large messages
                    int bit;
                    int avg = (red + green + blue) / 3;
                    if (avg >= 200) {
                        bit = 1;
                    } else if (avg <= 50) {
                        bit = 0;
                    } else {
                        bit = (avg >= 128) ? 1 : 0;
                    }

                    extractedChar = (extractedChar << 1) | bit;
                    pixelIndex++;
                }

                char finalChar = (char) extractedChar;

                if (extractedChar == 0) {
                    // Found null terminator
                    System.out.println("Found null terminator at character position " + charsExtracted);
                    break;
                }

                message.append(finalChar);
                charsExtracted++;

                // Progress logging for large extractions
                if (charsExtracted % 1000 == 0) {
                    System.out.println("Extracted " + charsExtracted + " characters from current frame");
                }
            }

            return message.toString();

        } catch (Exception e) {
            System.out.println("Optimized single frame extraction failed: " + e.getMessage());
            return "";
        }
    }

    private File[] getCurrentFrameFiles() {
        // Return the stored frame files for multi-frame processing
        return this.currentFrameFiles;
    }

    private String extractWithMultiChannelRedundancy(BufferedImage frame) {
        try {
            System.out.println("Trying multi-channel redundancy-based extraction");

            int width = frame.getWidth();
            int height = frame.getHeight();
            StringBuilder message = new StringBuilder();
            int pixelIndex = 0;
            int maxCharsToExtract = 15000; // Increased for large messages

            for (int charPos = 0; charPos < maxCharsToExtract; charPos++) {
                int extractedChar = 0;

                // Extract 8 bits for this character
                for (int bitPos = 0; bitPos < 8; bitPos++) {
                    int x = pixelIndex % width;
                    int y = pixelIndex / width;

                    if (y >= height) {
                        System.out.println("Reached end of frame during multi-channel extraction");
                        if (charPos == 0) return ""; // No data found

                        String result = message.toString();
                        return reconstructBase64Message(result);
                    }

                    int rgb = frame.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int green = (rgb >> 8) & 0xFF;
                    int blue = rgb & 0xFF;

                    // Optimized voting for large messages
                    List<Integer> bitVotes = new ArrayList<>();

                    // Simplified thresholds for better performance
                    bitVotes.add((red >= 128) ? 1 : 0);
                    bitVotes.add((green >= 128) ? 1 : 0);
                    bitVotes.add((blue >= 128) ? 1 : 0);

                    // Majority vote from all three channels
                    int bit1Count = bitVotes.stream().mapToInt(Integer::intValue).sum();
                    int finalBit = (bit1Count >= 2) ? 1 : 0; // Majority wins

                    extractedChar = (extractedChar << 1) | finalBit;
                    pixelIndex++;

                    // Minimal debug logging for performance
                    if (charPos < 5 || charPos % 2000 == 0) {
                        System.out.println("Pixel (" + x + "," + y + ") RGB(" + red + "," + green + "," + blue +
                            ") votes: " + bitVotes + " -> bit: " + finalBit);
                    }
                }

                char finalChar = (char) extractedChar;

                // More strict null terminator detection
                if (extractedChar == 0) {
                    System.out.println("Found null terminator at character position " + charPos);
                    break;
                }

                message.append(finalChar);

                // Reduced character logging for performance
                if (charPos < 10 || charPos % 2000 == 0 || charPos == maxCharsToExtract - 1) {
                    String charDisplay = (finalChar >= 32 && finalChar <= 126) ?
                        String.valueOf(finalChar) : "\\x" + Integer.toHexString(extractedChar);
                    System.out.println("Multi-channel extracted char " + charPos + ": '" + charDisplay + "' (ASCII: " + extractedChar + ")");
                }

                // Check for valid Base64 ending for large messages
                if (message.length() >= 100 && (finalChar == '=' ||
                    (message.length() >= 200 && message.toString().endsWith("=")))) {
                    String currentMessage = message.toString();
                    if (currentMessage.length() % 4 == 0 && isValidBase64(currentMessage)) {
                        System.out.println("Detected potential end of Base64 message at position " + charPos);
                        break;
                    }
                }
            }

            String rawResult = message.toString();
            if (rawResult.isEmpty()) {
                return "";
            }

            String result = reconstructBase64Message(rawResult);
            System.out.println("Multi-channel extraction result length: " + result.length());
            return result;

        } catch (Exception e) {
            System.out.println("Multi-channel extraction failed: " + e.getMessage());
            return "";
        }
    }

    private String extractWithRedundancy(BufferedImage frame) {
        try {
            System.out.println("Trying redundancy-based extraction");

            int width = frame.getWidth();
            int height = frame.getHeight();
            StringBuilder message = new StringBuilder();
            int pixelIndex = 0;
            int maxCharsToExtract = 20;

            for (int charPos = 0; charPos < maxCharsToExtract; charPos++) {
                int extractedChar = 0;

                // Extract 8 bits for this character
                for (int bitPos = 0; bitPos < 8; bitPos++) {
                    List<Integer> bitVotes = new ArrayList<>();

                    // Get 3 redundant encodings of this bit
                    for (int redundancy = 0; redundancy < 3; redundancy++) {
                        int x = pixelIndex % width;
                        int y = pixelIndex / width;

                        if (y >= height) {
                            System.out.println("Reached end of frame during redundancy extraction");
                            if (charPos == 0) return ""; // No data found

                            String result = message.toString();
                            return reconstructBase64Message(result);
                        }

                        int rgb = frame.getRGB(x, y);
                        int red = (rgb >> 16) & 0xFF;

                        // Determine bit based on red value
                        // Original encoding: bit 1 -> 240-250, bit 0 -> 5-15
                        int bit;
                        if (red >= 200) {
                            bit = 1;
                        } else if (red <= 50) {
                            bit = 0;
                        } else {
                            // Ambiguous, use threshold
                            bit = (red >= 128) ? 1 : 0;
                        }

                        bitVotes.add(bit);
                        pixelIndex++;
                    }

                    // Majority vote for this bit
                    int bit1Count = (int) bitVotes.stream().mapToInt(Integer::intValue).sum();
                    int finalBit = (bit1Count >= 2) ? 1 : 0; // Majority wins

                    extractedChar = (extractedChar << 1) | finalBit;
                }

                char finalChar = (char) extractedChar;

                if (finalChar == '\0') {
                    // Found null terminator
                    System.out.println("Found null terminator at character position " + charPos);
                    break;
                }

                message.append(finalChar);

                // Log the extracted character
                String charDisplay = (finalChar >= 32 && finalChar <= 126) ?
                    String.valueOf(finalChar) : "\\x" + Integer.toHexString(finalChar);
                System.out.println("Redundancy extracted char " + charPos + ": '" + charDisplay + "' (ASCII: " + extractedChar + ")");
            }

            String rawResult = message.toString();
            if (rawResult.isEmpty()) {
                return "";
            }

            String result = reconstructBase64Message(rawResult);
            System.out.println("Redundancy extraction result: '" + result + "'");
            return result;

        } catch (Exception e) {
            System.out.println("Redundancy extraction failed: " + e.getMessage());
            return "";
        }
    }

    private String extractWithFixedThreshold(BufferedImage frame, int threshold, String strategyName) {
        try {
            System.out.println("Trying extraction strategy: " + strategyName);

            int width = frame.getWidth();
            int height = frame.getHeight();
            StringBuilder message = new StringBuilder();
            int currentChar = 0;
            int bitIndex = 0;
            int maxCharsToExtract = 20;
            int extractedChars = 0;

            outerLoop:
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int rgb = frame.getRGB(x, y);
                    int red = (rgb >> 16) & 0xFF;
                    int bit = (red > threshold) ? 1 : 0;

                    currentChar = (currentChar << 1) | bit;
                    bitIndex++;

                    if (bitIndex == 8) {
                        char extractedChar = (char) currentChar;

                        if (extractedChar == '\0') {
                            break outerLoop;
                        }

                        message.append(extractedChar);
                        extractedChars++;

                        if (extractedChars >= maxCharsToExtract) {
                            break outerLoop;
                        }

                        currentChar = 0;
                        bitIndex = 0;
                    }
                }
            }

            String rawResult = message.toString();
            if (rawResult.isEmpty()) {
                return "";
            }

            String result = reconstructBase64Message(rawResult);
            System.out.println(strategyName + " result: '" + result + "'");
            return result;

        } catch (Exception e) {
            System.out.println(strategyName + " failed: " + e.getMessage());
            return "";
        }
    }

    private String extractWithAdaptiveThreshold(BufferedImage frame) {
        try {
            System.out.println("Trying extraction strategy: Adaptive");

            int width = frame.getWidth();
            int height = frame.getHeight();

            // Sample first 200 pixels to determine threshold
            List<Integer> redValues = new ArrayList<>();
            for (int i = 0; i < Math.min(200, width * height); i++) {
                int x = i % width;
                int y = i / width;
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                redValues.add(red);
            }

            redValues.sort(Integer::compareTo);
            int median = redValues.get(redValues.size() / 2);

            return extractWithFixedThreshold(frame, median, "Adaptive-" + median);

        } catch (Exception e) {
            System.out.println("Adaptive extraction failed: " + e.getMessage());
            return "";
        }
    }

    private String reconstructMessageCharByChar(BufferedImage frame) {
        try {
            System.out.println("Trying character-by-character reconstruction");

            int width = frame.getWidth();
            int height = frame.getHeight();
            StringBuilder message = new StringBuilder();
            int maxCharsToExtract = 15; // Expected encrypted message length

            // For each character position, try multiple thresholds and pick the best
            for (int charPos = 0; charPos < maxCharsToExtract; charPos++) {
                Map<Integer, Integer> charCandidates = new HashMap<>();

                // Try different thresholds for this character position
                int[] thresholds = {100, 110, 120, 127, 130, 135, 140, 150, 160, 170};

                for (int threshold : thresholds) {
                    int extractedChar = extractSingleCharacter(frame, charPos, threshold);
                    if (extractedChar != 0) { // Not null terminator
                        charCandidates.put(extractedChar, charCandidates.getOrDefault(extractedChar, 0) + 1);
                    }
                }

                // Find the most common character (majority vote)
                int bestChar = 0;
                int maxVotes = 0;
                for (Map.Entry<Integer, Integer> entry : charCandidates.entrySet()) {
                    if (entry.getValue() > maxVotes) {
                        maxVotes = entry.getValue();
                        bestChar = entry.getKey();
                    }
                }

                if (bestChar == 0) {
                    // No consensus, stop here
                    break;
                }

                char finalChar = (char) bestChar;

                // Check if this looks like a valid Base64 character
                if (isValidBase64Char(finalChar)) {
                    message.append(finalChar);
                    System.out.println("Character " + charPos + ": '" + finalChar + "' (votes: " + maxVotes + ")");
                } else {
                    // Try to correct it to a valid Base64 character
                    char corrected = correctToBase64(finalChar);
                    message.append(corrected);
                    System.out.println("Character " + charPos + ": '" + finalChar + "' -> '" + corrected + "' (corrected)");
                }
            }

            String result = message.toString();

            // Ensure proper Base64 padding
            while (result.length() % 4 != 0) {
                result += "=";
            }

            if (isValidBase64(result) && result.length() >= 8) {
                System.out.println("CharByChar reconstruction result: '" + result + "'");
                return result;
            }

            return "";

        } catch (Exception e) {
            System.out.println("CharByChar reconstruction failed: " + e.getMessage());
            return "";
        }
    }

    private int extractSingleCharacter(BufferedImage frame, int charPosition, int threshold) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        int currentChar = 0;
        int bitIndex = 0;
        int charIndex = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int bit = (red > threshold) ? 1 : 0;

                currentChar = (currentChar << 1) | bit;
                bitIndex++;

                if (bitIndex == 8) {
                    if (charIndex == charPosition) {
                        return currentChar;
                    }
                    charIndex++;
                    currentChar = 0;
                    bitIndex = 0;
                }
            }
        }

        return 0; // Not found
    }

    private boolean isValidBase64Char(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
               (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=';
    }

    private boolean isLikelyBase64Char(char c) {
        // Check if character might be a corrupted Base64 character
        int ascii = (int) c;

        // Characters close to Base64 ranges that might be compression artifacts
        return (ascii >= 65-10 && ascii <= 90+10) ||   // Near A-Z
               (ascii >= 97-10 && ascii <= 122+10) ||  // Near a-z
               (ascii >= 48-5 && ascii <= 57+5) ||     // Near 0-9
               (ascii >= 43-5 && ascii <= 43+5) ||     // Near +
               (ascii >= 47-5 && ascii <= 47+5) ||     // Near /
               (ascii >= 61-5 && ascii <= 61+5);       // Near =
    }

    private char correctToBase64(char c) {
        int ascii = (int) c;

        // Correct character to nearest valid Base64 character
        if (ascii >= 65-10 && ascii <= 90+10) {
            // Correct to A-Z range
            if (ascii < 65) return 'A';
            if (ascii > 90) return 'Z';
            return (char) Math.max(65, Math.min(90, ascii));
        } else if (ascii >= 97-10 && ascii <= 122+10) {
            // Correct to a-z range
            if (ascii < 97) return 'a';
            if (ascii > 122) return 'z';
            return (char) Math.max(97, Math.min(122, ascii));
        } else if (ascii >= 48-5 && ascii <= 57+5) {
            // Correct to 0-9 range
            if (ascii < 48) return '0';
            if (ascii > 57) return '9';
            return (char) Math.max(48, Math.min(57, ascii));
        } else if (ascii >= 43-5 && ascii <= 43+5) {
            return '+';
        } else if (ascii >= 47-5 && ascii <= 47+5) {
            return '/';
        } else if (ascii >= 61-5 && ascii <= 61+5) {
            return '=';
        }

        // If no specific correction possible, try to map to closest valid Base64 char
        return findClosestBase64Char(c);
    }

    private String selectBestExtractionFromMap(Map<String, String> results) {
        if (results.isEmpty()) {
            return "";
        }

        // Prefer results that are closer to expected length (12 characters)
        String bestResult = "";
        int bestScore = -1;

        for (Map.Entry<String, String> entry : results.entrySet()) {
            String result = entry.getValue();
            int score = calculateResultScore(result);

            System.out.println("Strategy: " + entry.getKey() + ", Result: '" + result + "', Score: " + score);

            if (score > bestScore) {
                bestScore = score;
                bestResult = result;
            }
        }

        System.out.println("Selected best result: '" + bestResult + "' with score: " + bestScore);
        return bestResult;
    }

    private int calculateResultScore(String result) {
        int score = 0;

        // Base score for valid Base64
        if (isValidBase64(result)) {
            score += 100;
        }

        // Length preference (closer to 12 is better)
        int lengthDiff = Math.abs(result.length() - 12);
        score += Math.max(0, 50 - lengthDiff * 5);

        // Bonus for having proper Base64 ending
        if (result.endsWith("==") || result.endsWith("=")) {
            score += 20;
        }

        // Penalty for very short results
        if (result.length() < 8) {
            score -= 30;
        }

        return score;
    }

    private String reconstructBase64Message(String rawMessage) {
        // More lenient Base64 reconstruction for longer messages
        StringBuilder base64Chars = new StringBuilder();

        // First pass: extract obvious Base64 characters
        for (char c : rawMessage.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=') {
                base64Chars.append(c);
            }
        }

        String filtered = base64Chars.toString();
        System.out.println("Initial Base64 filter result: '" + filtered + "'");

        // If we have fewer characters than expected, try aggressive pattern matching
        if (filtered.length() < rawMessage.length() * 0.8) { // More lenient threshold
            StringBuilder reconstructed = new StringBuilder();

            for (int i = 0; i < rawMessage.length(); i++) {
                char c = rawMessage.charAt(i);
                int ascii = (int) c;

                // Try to map common corruption patterns to Base64 characters
                if (ascii >= 'A' && ascii <= 'Z') {
                    reconstructed.append(c);
                } else if (ascii >= 'a' && ascii <= 'z') {
                    reconstructed.append(c);
                } else if (ascii >= '0' && ascii <= '9') {
                    reconstructed.append(c);
                } else if (c == '+' || c == '/' || c == '=') {
                    reconstructed.append(c);
                } else if (ascii >= 200 && ascii <= 255) {
                    // High ASCII values might be corrupted uppercase letters
                    char corrected = (char) ('A' + (ascii % 26));
                    reconstructed.append(corrected);
                    System.out.println("Corrected high ASCII " + ascii + " to '" + corrected + "'");
                } else if (ascii >= 160 && ascii <= 199) {
                    // Mid-high ASCII values might be corrupted lowercase letters
                    char corrected = (char) ('a' + (ascii % 26));
                    reconstructed.append(corrected);
                    System.out.println("Corrected mid ASCII " + ascii + " to '" + corrected + "'");
                } else if (ascii >= 48 && ascii <= 127) {
                    // Printable ASCII range - try to find closest Base64 character
                    char corrected = findClosestBase64Char(c);
                    if (corrected != c) {
                        reconstructed.append(corrected);
                        System.out.println("Corrected '" + c + "' to '" + corrected + "'");
                    } else {
                        reconstructed.append(c); // Keep original if it's already close
                    }
                }
            }

            filtered = reconstructed.toString();
            System.out.println("Aggressive reconstruction result: '" + filtered + "'");
        }

        // Ensure minimum length and proper padding - more lenient for longer messages
        if (filtered.length() < 4) {
            throw new IllegalArgumentException("Could not extract sufficient Base64 data. " +
                "Got: '" + filtered + "' from raw: '" + rawMessage + "'");
        }

        // Ensure proper Base64 padding
        while (filtered.length() % 4 != 0) {
            filtered += "=";
        }

        // Final validation - more lenient for longer messages
        if (!isValidBase64(filtered)) {
            // Try to salvage what we can by removing invalid characters
            String salvaged = filtered.replaceAll("[^A-Za-z0-9+/=]", "");
            while (salvaged.length() % 4 != 0) {
                salvaged += "=";
            }

            if (isValidBase64(salvaged) && salvaged.length() >= 4) {
                System.out.println("Salvaged Base64 result: '" + salvaged + "'");
                return salvaged;
            }

            throw new IllegalArgumentException("Could not reconstruct valid Base64 message. " +
                "Final result: '" + filtered + "' from raw: '" + rawMessage + "'");
        }

        return filtered;
    }

    private char findClosestBase64Char(char c) {
        String base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
        int minDistance = Integer.MAX_VALUE;
        char closest = c;

        for (char b64Char : base64Chars.toCharArray()) {
            int distance = Math.abs((int)c - (int)b64Char);
            if (distance < minDistance) {
                minDistance = distance;
                closest = b64Char;
            }
        }

        return closest;
    }

    private void extractFrames(String videoPath, String framesDir) throws Exception {
        new ProcessBuilder("ffmpeg", "-i", videoPath, framesDir + "/frame_%06d.png", "-y")
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
