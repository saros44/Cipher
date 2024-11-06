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
    private static final byte MESSAGE_DELIMITER = (byte) 0xFF; // End of message delimiter (used to mark end of hidden message)

    // Main method to encode message into WAV file
    public SteganographyResult encodeMessageIntoWav(MultipartFile inputWavFile, String message, String key) throws IOException {
        try {
            // Validate the input parameters for file, message, and key
            validateInput(inputWavFile, message, key);

            // Read the audio file bytes
            byte[] audioBytes = readAudioFile(inputWavFile);
            byte[] messageBytes = message.getBytes();  // Convert message to byte array
            byte[] keyBytes = getKeyBytes(key);  // Hash the key into a byte array using SHA-256

            // Check if the audio file has enough space for the message and key
            checkAudioFileSize(audioBytes, messageBytes.length, keyBytes.length);

            // Variables for SNR (Signal-to-Noise Ratio) tracking
            double initialSNR = 0;
            double keySNR;
            double keySNRPostKeyLength;
            double messageLengthSNR;
            double messageSNR;
            double delimiterSNR;
            double finalSNR;

            int audioIndex = HEADER_SIZE; // Skip the WAV header as it doesn't store the encoded message

            // Encode key length and key bytes
            keySNR = initialSNR;
            encodeInt(audioBytes, audioIndex, keyBytes.length);  // Encode the length of the key
            audioIndex += 32;  // Move the index forward after encoding 4 bytes (32 bits)
            keySNRPostKeyLength = calculateSNR(inputWavFile.getBytes(), audioBytes);  // Calculate SNR after encoding key length

            encodeBytesIntoAudio(audioBytes, audioIndex, keyBytes);  // Encode the key itself
            audioIndex += keyBytes.length * 8;  // Move the index forward by the number of bits used by the key
            messageLengthSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);  // Calculate SNR after encoding the key

            // Encode message length and message bytes
            encodeInt(audioBytes, audioIndex, messageBytes.length);  // Encode the length of the message
            audioIndex += 32;  // Move the index forward after encoding the message length
            messageSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);  // Calculate SNR after encoding message length

            encodeBytesIntoAudio(audioBytes, audioIndex, messageBytes);  // Encode the actual message into the audio file
            audioIndex += messageBytes.length * 8;  // Move the index forward by the number of bits in the message
            delimiterSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);  // Calculate SNR after encoding message

            // Add delimiter to indicate the end of the encoded message
            encodeByte(audioBytes, audioIndex);  // Add delimiter byte to mark the end of the message

            // Final SNR after encoding
            finalSNR = calculateSNR(inputWavFile.getBytes(), audioBytes);  // Calculate the final SNR after all encoding

            logger.info("Message encoding completed successfully. Initial SNR: {}, Key SNR: {}, Key Length SNR: {}, Message Length SNR: {}, Message SNR: {}, Delimiter SNR: {}, Final SNR: {}",
                    initialSNR, keySNR, keySNRPostKeyLength, messageLengthSNR, messageSNR, delimiterSNR, finalSNR);

            // Convert encoded audio bytes to Base64
            String encodedAudioBase64 = Base64.getEncoder().encodeToString(audioBytes);  // Convert encoded audio to Base64

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

    // Validate input parameters (file, message, and key)
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

    // Read the audio file and return its byte array
    private byte[] readAudioFile(MultipartFile inputWavFile) throws IOException {
        try {
            return inputWavFile.getBytes();  // Convert input audio file to byte array
        } catch (IOException e) {
            logger.error("Failed to read audio file bytes", e);
            throw new IOException("Failed to read audio file bytes", e);
        }
    }

    // Check if the audio file has enough capacity to store the message and key
    private void checkAudioFileSize(byte[] audioBytes, int messageLength, int keyLength) {
        int totalBitsRequired = HEADER_SIZE * 8  // Reserve space for WAV header
                + (keyLength * 8)  // Space for the key
                + (messageLength * 8)  // Space for the message
                + 8;  // 8 bits for the delimiter (end of message)

        if (audioBytes.length * 8 < totalBitsRequired) {
            String errorMessage = "Audio file is too short to encode the message.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }
    }

    // Encode an integer value into the audio bytes (32 bits)
    private void encodeInt(byte[] audioBytes, int startIndex, int value) {
        for (int i = 0; i < 4; i++) {
            audioBytes[startIndex + i] = (byte) (value >> (i * 8));  // Encode each byte of the integer into audio
        }
    }

    // Encode a byte array into the audio file using LSB encoding
    private void encodeBytesIntoAudio(byte[] audioBytes, int startIndex, byte[] dataBytes) {
        int audioIndex = startIndex;
        for (byte b : dataBytes) {
            for (int bit = 0; bit < 8; bit++) {
                int bitValue = (b >> (7 - bit)) & 1;  // Get the bit from the byte
                audioBytes[audioIndex] = (byte) ((audioBytes[audioIndex] & 0xFE) | bitValue);  // Set LSB in audio byte
                audioIndex++;
            }
        }
    }

    // Encode a single byte (delimiter) to mark the end of the message
    private void encodeByte(byte[] audioBytes, int startIndex) {
        for (int bit = 0; bit < 8; bit++) {
            int bitValue = (MESSAGE_DELIMITER >> (7 - bit)) & 1;  // Get each bit of the delimiter
            audioBytes[startIndex] = (byte) ((audioBytes[startIndex] & 0xFE) | bitValue);  // Set LSB in audio byte
            startIndex++;
        }
    }

    // Get the key bytes using SHA-256 hashing
    private byte[] getKeyBytes(String key) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");  // Create SHA-256 digest
            return digest.digest(key.getBytes());  // Convert the key to hashed byte array
        } catch (NoSuchAlgorithmException e) {
            String errorMessage = "SHA-256 algorithm not found.";
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }

    // Calculate the Signal-to-Noise Ratio (SNR) between the original and encoded audio
    public double calculateSNR(byte[] originalAudio, byte[] encodedAudio) {
        if (originalAudio.length != encodedAudio.length) {
            throw new IllegalArgumentException("Audio files must be of the same length.");
        }

        long signalPower = 0;
        long noisePower = 0;

        for (int i = 0; i < originalAudio.length; i++) {
            int originalSample = originalAudio[i] & 0xFF;  // Get unsigned value of original byte
            int encodedSample = encodedAudio[i] & 0xFF;  // Get unsigned value of encoded byte
            int noise = originalSample - encodedSample;  // Calculate noise

            signalPower += originalSample * originalSample;  // Signal power
            noisePower += noise * noise;  // Noise power
        }

        // Calculate signal and noise power in decibels (dB)
        double signalPowerDb = 10 * Math.log10((double) signalPower / originalAudio.length);
        double noisePowerDb = 10 * Math.log10((double) noisePower / originalAudio.length);

        return signalPowerDb - noisePowerDb;  // Return SNR in decibels
    }

    // Helper class to encapsulate the result of encoding
    public record SteganographyResult(String encodedAudioBase64, double[] snrStages) {
    }
}
