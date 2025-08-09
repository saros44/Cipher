# Video Steganography Documentation

## Overview

This document describes the implementation and usage of the video steganography feature in the Cipher application. The feature allows users to hide text messages in MP4 videos using DCT (Discrete Cosine Transform) based steganography with AES encryption for added security.

## Implementation Details

### VideoEncodeService

The `VideoEncodeService` class implements the core functionality for encoding messages into MP4 videos:

1. **Input Validation**: Validates that the input video is an MP4 file, the message is not empty, and the secret key is exactly 16 characters long.

2. **Message Encryption**: Uses AES encryption with the provided secret key to encrypt the message before embedding it in the video.

3. **Frame Extraction**: Extracts frames from the input video using FFmpeg.

4. **DCT-Based Embedding**:
   - Applies DCT to 8x8 blocks in the luminance (Y) channel of each frame
   - Modifies mid-frequency DCT coefficients to embed the encrypted message bits
   - Applies inverse DCT to reconstruct the modified blocks

5. **Video Reconstruction**: Reconstructs the video with the modified frames, preserving the original audio.

### SteganographyController

The `SteganographyController` exposes the video steganography functionality through a REST API endpoint:

- **POST /api/steganography/encode-video**: Encodes a message into an MP4 video.

## How It Works

### DCT-Based Steganography

The implementation uses DCT-based steganography, which is more robust than simple LSB (Least Significant Bit) techniques:

1. Each frame is divided into 8x8 pixel blocks.
2. DCT is applied to each block, transforming it from the spatial domain to the frequency domain.
3. Message bits are embedded by modifying mid-frequency DCT coefficients.
4. Inverse DCT is applied to transform the block back to the spatial domain.

This approach is less susceptible to detection and more resistant to compression and other transformations.

### Security Features

1. **AES Encryption**: The message is encrypted using AES with a 16-character secret key before embedding.
2. **Limited Frame Usage**: Only a subset of frames is used for embedding, making it harder to detect and extract the message.
3. **Mid-Frequency Coefficient Modification**: Changes to mid-frequency coefficients are less perceptible to the human eye.

## API Usage

### Encode a Message in a Video

**Endpoint**: `POST /api/steganography/encode-video`

**Parameters**:
- `video`: The MP4 video file (multipart/form-data)
- `message`: The text message to encode
- `key`: A 16-character secret key for AES encryption

**Response**:
- Success: The encoded MP4 video file with Content-Type: video/mp4
- Error: An error message with an appropriate HTTP status code

**Example using cURL**:
```bash
curl -X POST \
  http://localhost:8080/api/steganography/encode-video \
  -H 'Content-Type: multipart/form-data' \
  -F 'video=@/path/to/video.mp4' \
  -F 'message=This is a secret message' \
  -F 'key=1234567890123456' \
  --output encoded_video.mp4
```

## Limitations

1. **Video Length**: The video must have at least 10 frames.
2. **Message Size**: The maximum message length depends on the video resolution and the number of frames.
3. **Processing Time**: Encoding large videos or long messages may take significant processing time.
4. **Video Format**: Only MP4 videos are supported.

## Technical Requirements

- FFmpeg must be available on the system for frame extraction and video reconstruction.
- Sufficient disk space for temporary files during processing.
- Adequate memory for processing video frames.

## Error Handling

The API returns appropriate error messages and HTTP status codes for various error conditions:

- 400 Bad Request: For validation errors (invalid video format, empty message, incorrect key length, etc.)
- 500 Internal Server Error: For processing errors (IO errors, FFmpeg errors, etc.)