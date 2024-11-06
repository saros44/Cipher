package com.example.Cipher.controller;

import com.example.Cipher.service.DecodeService;
import com.example.Cipher.service.EncodeService;
import com.example.Cipher.service.WavSteganography;
import com.example.Cipher.service.WavSteganographyDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/steganography")
public class SteganographyController {

    private static final Logger logger = LoggerFactory.getLogger(SteganographyController.class);

    private final EncodeService encodeService;
    private final DecodeService decodeService;
    private final WavSteganography wavSteganography;
    private final WavSteganographyDecoder wavSteganographyDecoder;

    public SteganographyController(EncodeService encodeService, DecodeService decodeService, WavSteganography wavSteganography, WavSteganographyDecoder wavSteganographyDecoder) {
        this.encodeService = encodeService;
        this.decodeService = decodeService;
        this.wavSteganography = wavSteganography;
        this.wavSteganographyDecoder = wavSteganographyDecoder;
    }

    @PostMapping("/encode")
    public ResponseEntity<byte[]> encode(@RequestParam("image") MultipartFile image,
                                         @RequestParam("message") String message,
                                         @RequestParam("key") String key) {
        try {
            byte[] encodedImage = encodeService.encodeMessage(image, message, key);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "image/png")
                    .body(encodedImage);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(("Encoding failed: " + e.getMessage()).getBytes());
        } catch (Exception e) {
            logger.error("Encoding failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Encoding failed: " + e.getMessage()).getBytes());
        }
    }

    @PostMapping("/decode")
    public ResponseEntity<String> decode(@RequestParam("image") MultipartFile image,
                                         @RequestParam("key") String key) {
        try {
            String message = decodeService.decodeMessage(image, key);
            return ResponseEntity.ok(message);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body("Decoding failed: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Decoding failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Decoding failed: " + e.getMessage());
        }
    }

    @PostMapping("/encode-audio")
    public ResponseEntity<Map<String, Object>> encodeAudio(@RequestParam("audio") MultipartFile audio,
                                                           @RequestParam("message") String message,
                                                           @RequestParam("key") String key) {
        Map<String, Object> response = new HashMap<>();

        try {

            // Encode the message into the WAV audio file
            WavSteganography.SteganographyResult result = wavSteganography.encodeMessageIntoWav(audio, message, key);

            // Prepare the response with Base64-encoded audio and SNR stages
            response.put("encodedAudio", result.encodedAudioBase64());
            response.put("snrStages", result.snrStages());

            // Return the response as JSON
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(response);

        } catch (IllegalArgumentException e) {
            // Handle specific validation errors with a bad request response
            String errorMessage = "Invalid input: " + e.getMessage();
            logger.error(errorMessage);
            response.put("error", errorMessage);
            return ResponseEntity.badRequest().body(response);

        } catch (IOException e) {
            // Handle IO errors with an internal server error response
            String errorMessage = "Audio encoding failed due to an IO error: " + e.getMessage();
            logger.error(errorMessage, e);
            response.put("error", errorMessage);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);

        } catch (Exception e) {
            // Handle any unexpected errors with an internal server error response
            String errorMessage = "Unexpected error occurred during audio encoding: " + e.getMessage();
            logger.error(errorMessage, e);
            response.put("error", errorMessage);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }


    @PostMapping("/decode-audio")
    public ResponseEntity<String> decodeAudio(@RequestParam("audio") MultipartFile audio,
                                              @RequestParam("key") String key) {

        try {
            // Attempt to decode the audio message
            String message = wavSteganographyDecoder.decodeMessageFromWav(audio, key);
            return ResponseEntity.ok(message);
        } catch (IllegalArgumentException e) {
            // Handle invalid arguments or file issues
            logger.error("Invalid input: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IOException e) {
            // Handle I/O exceptions during file processing or decoding
            logger.error("I/O error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        } catch (Exception e) {
            // Handle any unexpected exceptions
            logger.error("Unexpected error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An unexpected error occurred.");
        }
    }
}
