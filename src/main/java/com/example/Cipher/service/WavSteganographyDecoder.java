package com.example.Cipher.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import java.nio.ByteBuffer;

@Service
public class WavSteganographyDecoder {

    private static final Logger logger = LoggerFactory.getLogger(WavSteganographyDecoder.class);
    private static final int HEADER_SIZE = 44; // WAV header size in bytes
    private static final byte MESSAGE_DELIMITER = (byte) 0xFF; // End of message delimiter

    // Deterministic mask bitstream derived from key bytes (must match encoder)
    private static class MaskingBitStream {
        private final Random rnd;
        MaskingBitStream(byte[] keyBytes) {
            long seed = ByteBuffer.wrap(keyBytes).getLong();
            this.rnd = new Random(seed);
        }
        int nextBit() { return rnd.nextInt(2); }
    }

    public String decodeMessageFromWav(MultipartFile inputWavFile, String key) throws IOException {
        byte[] audioBytes;
        try (InputStream inputStream = inputWavFile.getInputStream()) {
            audioBytes = inputStream.readAllBytes();
        } catch (IOException e) {
            logger.error("Failed to read WAV file bytes", e);
            throw new IOException("Failed to read WAV file bytes", e);
        }

        if (audioBytes.length < HEADER_SIZE) {
            String errorMessage = "Audio file is too short to contain a message.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        byte[] keyBytes = getKeyBytes(key); // Hash the key for comparison

        int audioIndex = HEADER_SIZE;

        try {
            // Decode key length
            int keyLength = decodeInt(audioBytes, audioIndex);
            audioIndex += 32;

            // Initialize masking stream derived from key
            MaskingBitStream maskStream = new MaskingBitStream(keyBytes);

            // Decode key bytes (masked)
            byte[] decodedKeyBytes = new byte[keyLength];
            decodeBytesMasked(audioBytes, audioIndex, decodedKeyBytes, maskStream);
            audioIndex += keyLength * 8;

            // Verify the decoded key
            if (!MessageDigest.isEqual(decodedKeyBytes, keyBytes)) {
                // Specific error for key mismatch
                String errorMessage = "Invalid key provided; cannot decode message.";
                logger.error(errorMessage);
                throw new IllegalArgumentException(errorMessage);
            }

            // Decode message length (unmasked)
            int messageLength = decodeInt(audioBytes, audioIndex);
            audioIndex += 32;

            if (messageLength <= 0 || audioBytes.length < audioIndex + messageLength * 8 + 8) {
                String errorMessage = "Invalid message length or audio file is too short.";
                logger.error(errorMessage);
                throw new IllegalArgumentException(errorMessage);
            }

            // Decode message bytes (masked)
            byte[] messageBytes = new byte[messageLength];
            decodeBytesMasked(audioBytes, audioIndex, messageBytes, maskStream);
            audioIndex += messageLength * 8;

            // Check for delimiter (masked)
            if (!checkMaskedDelimiter(audioBytes, audioIndex, maskStream)) {
                String errorMessage = "Message delimiter not found; likely no encoded message present.";
                logger.error(errorMessage);
                throw new IllegalArgumentException(errorMessage);
            }

            return new String(messageBytes);
        } catch (ArrayIndexOutOfBoundsException e) {
            String errorMessage = "Failed to decode message due to insufficient data.";
            logger.error(errorMessage, e);
            throw new IllegalArgumentException(errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "Invalid key";
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }

    private int decodeInt(byte[] audioBytes, int startIndex) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value |= (audioBytes[startIndex + i] & 0xFF) << (i * 8);
        }
        return value;
    }

    private void decodeBytes(byte[] audioBytes, int startIndex, byte[] dataBytes) {
        int audioIndex = startIndex;
        for (int i = 0; i < dataBytes.length; i++) {
            byte b = 0;
            for (int bit = 0; bit < 8; bit++) {
                int bitValue = (audioBytes[audioIndex] & 0x01);
                b = (byte) ((b << 1) | bitValue);
                audioIndex++;
            }
            dataBytes[i] = b;
        }
    }

    // New: masked variant that XOR-unmasks each bit using mask stream
    private void decodeBytesMasked(byte[] audioBytes, int startIndex, byte[] dataBytes, MaskingBitStream maskStream) {
        int audioIndex = startIndex;
        for (int i = 0; i < dataBytes.length; i++) {
            byte b = 0;
            for (int bit = 0; bit < 8; bit++) {
                int storedBit = (audioBytes[audioIndex] & 0x01);
                int maskBit = maskStream.nextBit();
                int dataBit = storedBit ^ maskBit;
                b = (byte) ((b << 1) | dataBit);
                audioIndex++;
            }
            dataBytes[i] = b;
        }
    }

    private boolean checkDelimiter(byte[] audioBytes, int startIndex) {
        for (int bit = 0; bit < 8; bit++) {
            int bitValue = (audioBytes[startIndex] & 0x01);
            if (bitValue != ((MESSAGE_DELIMITER >> (7 - bit)) & 1)) {
                return false;
            }
            startIndex++;
        }
        return true;
    }

    // New: masked delimiter check
    private boolean checkMaskedDelimiter(byte[] audioBytes, int startIndex, MaskingBitStream maskStream) {
        for (int bit = 0; bit < 8; bit++) {
            int storedBit = (audioBytes[startIndex] & 0x01);
            int maskBit = maskStream.nextBit();
            int dataBit = storedBit ^ maskBit;
            if (dataBit != ((MESSAGE_DELIMITER >> (7 - bit)) & 1)) {
                return false;
            }
            startIndex++;
        }
        return true;
    }

    private byte[] getKeyBytes(String key) throws IOException {
        if (key.length() < 8) {
            String errorMessage = "Key must be at least 8 characters long.";
            logger.error(errorMessage);
            throw new IllegalArgumentException(errorMessage);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(key.getBytes());
        } catch (NoSuchAlgorithmException e) {
            String errorMessage = "Key hashing algorithm not found.";
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        } catch (Exception e) {
            String errorMessage = "Unexpected error occurred while processing the key.";
            logger.error(errorMessage, e);
            throw new IOException(errorMessage, e);
        }
    }
}
