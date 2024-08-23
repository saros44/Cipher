package com.example.Cipher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class WavSteganography {

    private static final Logger logger = LoggerFactory.getLogger(WavSteganography.class);
    private static final int HEADER_SIZE = 44; // WAV header size in bytes
    private static final byte MESSAGE_DELIMITER = (byte) 0xFF; // End of message delimiter

    // Main method to encode message into WAV file
    public SteganographyResult encodeMessageIntoWav(MultipartFile inputWavFile, String message, String key) throws IOException {
        try {
            validateInput(inputWavFile, message, key);

            byte[] audioBytes = readAudioFile(inputWavFile);
            byte[] messageBytes = message.getBytes();
            byte[] keyBytes = getKeyBytes(key);

            checkAudioFileSize(audioBytes, messageBytes.length, keyBytes.length);

            double initialSNR = 0;
            double keySNR;
            double keySNRPostKeyLength;
            double messageLengthSNR;
            double messageSNR;
            double delimiterSNR;
            double finalSNR;

            int audioIndex = HEADER_SIZE; // Skip WAV header

            // Encode key length and key bytes
            keySNR = initialSNR;
            encodeInt(audioBytes, audioIndex, keyBytes.length);
            audioIndex += 32;
            keySNRPostKeyLength = calculateSNR(inputWavFile.getBytes(), audioBytes);

            encodeBytesIntoAudio(audioBytes, audioIndex, keyBytes);
            audioIndex += keyBytes.length * 8;
            messageLengthSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);

            // Encode message length and message bytes
            encodeInt(audioBytes, audioIndex, messageBytes.length);
            audioIndex += 32;
            messageSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);

            encodeBytesIntoAudio(audioBytes, audioIndex, messageBytes);
            audioIndex += messageBytes.length * 8;
            delimiterSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);

            // Add delimiter to indicate the end of the encoded message
            encodeByte(audioBytes, audioIndex);

            // Final SNR after encoding
            finalSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);

            logger.info("Message encoding completed successfully. Initial SNR: {}, Key SNR: {}, Key Length SNR: {}, Message Length SNR: {}, Message SNR: {}, Delimiter SNR: {}, Final SNR: {}",
                    initialSNR, keySNR, keySNRPostKeyLength, messageLengthSNR, messageSNR, delimiterSNR, finalSNR);

            // Convert encoded audio bytes to Base64
            String encodedAudioBase64 = Base64.getEncoder().encodeToString(audioBytes);

            return new SteganographyResult(encodedAudioBase64, new double[]{initialSNR, keySNR, keySNRPostKeyLength, messageLengthSNR, messageSNR, delimiterSNR, finalSNR});
        } catch (IllegalArgumentException e) {
            logger.error("Input validation failed: {}", e.getMessage());
            throw e;
        } catch (IOException e) {
            logger.error("Failed to process the audio file: {}", e.getMessage(), e);
            throw new IOException("Failed to process the audio file. Please try again.", e);
        } catch (Exception e) {
            logger.error("Unexpected error occurred during audio encoding: {}", e.getMessage(), e);
            throw new IOException("Unexpected error occurred during audio encoding. Please try again later.", e);
        }
    }

    // Validate input parameters
    private void validateInput(MultipartFile inputWavFile, String message, String key) {
        if (inputWavFile.isEmpty()) {
            String errorMessage = "Audio file must not be empty.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
        if (message == null || message.trim().isEmpty()) {
            String errorMessage = "Message must not be empty.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
        if (key == null || key.trim().isEmpty()) {
            String errorMessage = "Key must not be empty.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
        if (key.length() < 8) {
            String errorMessage = "Key must be at least 8 characters long.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
    }

    // Read audio file bytes
    private byte[] readAudioFile(MultipartFile inputWavFile) throws IOException {
        try {
            return inputWavFile.getBytes();
        } catch (IOException e) {
            logger.error("Failed to read audio file bytes", e);
            throw new IOException("Failed to read audio file bytes", e);
        }
    }

    // Check if audio file is long enough to encode the message
    private void checkAudioFileSize(byte[] audioBytes, int messageLength, int keyLength) {
        int totalBitsRequired = HEADER_SIZE * 8
                + (keyLength * 8)
                + (messageLength * 8)
                + 8; // 8 bits for delimiter

        if (audioBytes.length * 8 < totalBitsRequired) {
            String errorMessage = "Audio file is too short to encode the message.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
    }

    // Encode integer into audio bytes
    private void encodeInt(byte[] audioBytes, int startIndex, int value) {
        for (int i = 0; i < 4; i++) {
            audioBytes[startIndex + i] = (byte) (value >> (i * 8));
        }
    }

    // Encode bytes into audio bytes
    private void encodeBytesIntoAudio(byte[] audioBytes, int startIndex, byte[] dataBytes) {
        int audioIndex = startIndex;
        for (byte b : dataBytes) {
            for (int bit = 0; bit < 8; bit++) {
                int bitValue = (b >> (7 - bit)) & 1;
                audioBytes[audioIndex] = (byte) ((audioBytes[audioIndex] & 0xFE) | bitValue);
                audioIndex++;
            }
        }
    }

    // Encode single byte (delimiter) into audio bytes
    private void encodeByte(byte[] audioBytes, int startIndex) {
        for (int bit = 0; bit < 8; bit++) {
            int bitValue = (MESSAGE_DELIMITER >> (7 - bit)) & 1;
            audioBytes[startIndex] = (byte) ((audioBytes[startIndex] & 0xFE) | bitValue);
            startIndex++;
        }
    }

    // Get key bytes using SHA-256
    private byte[] getKeyBytes(String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(key.getBytes());
        } catch (NoSuchAlgorithmException e) {
            String errorMessage = "SHA-256 algorithm not found.";
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }

    // Calculate Signal-to-Noise Ratio (SNR)
    public double calculateSNR(byte[] originalAudio, byte[] encodedAudio) {
        if (originalAudio.length != encodedAudio.length) {
            throw new IllegalArgumentException("Audio files must be of the same length.");
        }

        long signalPower = 0;
        long noisePower = 0;

        for (int i = 0; i < originalAudio.length; i++) {
            int originalSample = originalAudio[i] & 0xFF;
            int encodedSample = encodedAudio[i] & 0xFF;
            int noise = originalSample - encodedSample;

            signalPower += originalSample * originalSample;
            noisePower += noise * noise;
        }

        double signalPowerDb = 10 * Math.log10((double) signalPower / originalAudio.length);
        double noisePowerDb = 10 * Math.log10((double) noisePower / originalAudio.length);

        return signalPowerDb - noisePowerDb;
    }

    // Helper class to encapsulate the result of encoding
    public record SteganographyResult(String encodedAudioBase64, double[] snrStages) {
    }
}
