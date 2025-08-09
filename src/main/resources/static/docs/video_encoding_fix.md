# Video Encoding Fix Documentation

## Issue Description

The video steganography feature was successfully encoding messages into MP4 videos, but the resulting encoded videos were corrupted and could not be played properly. This issue occurred because the FFmpeg command used to reconstruct the video after embedding the message lacked essential encoding parameters.

## Solution

The issue was resolved by enhancing the FFmpeg command in the `reconstructVideo` method of the `VideoEncodeService` class with additional parameters to ensure proper video encoding and container formatting.

### Changes Made

The following parameters were added to the FFmpeg command:

1. **Pixel Format Specification**:
   ```
   -pix_fmt yuv420p
   ```
   This specifies a widely compatible pixel format that ensures the video can be played on most devices and platforms.

2. **Video Quality Control**:
   ```
   -crf 23
   -preset medium
   ```
   - The Constant Rate Factor (CRF) controls quality (lower values = higher quality, range 18-28 is good)
   - The preset balances encoding speed and compression ratio

3. **Keyframe Interval**:
   ```
   -g 25
   ```
   Sets a keyframe every 25 frames, which improves seeking and playability.

4. **H.264 Profile and Level**:
   ```
   -profile:v main
   -level 4.0
   ```
   Ensures compatibility with a wide range of devices and players.

5. **Container Format Optimization**:
   ```
   -movflags +faststart
   ```
   Optimizes the MP4 container for web playback by moving metadata to the beginning of the file, allowing playback to start before the entire file is downloaded.

## Technical Details

### Why These Parameters Matter

1. **Pixel Format**: When converting from PNG images (which use RGB color space) to MP4 video (which typically uses YUV color space), specifying the pixel format is crucial. The `yuv420p` format is widely supported by most players and devices.

2. **Quality Control**: The CRF value provides a balance between quality and file size. A value of 23 provides good quality while keeping the file size reasonable. The preset determines how much time the encoder spends optimizing the video.

3. **Keyframe Interval**: Keyframes (I-frames) are complete frames that don't depend on other frames. Having regular keyframes improves seeking within the video and helps with error recovery.

4. **Profile and Level**: The H.264 profile and level determine the features and capabilities used in the encoding. The "main" profile with level 4.0 provides good compatibility across devices while supporting HD resolution.

5. **Container Optimization**: The `faststart` flag reorganizes the MP4 file structure so that metadata appears at the beginning of the file, allowing players to start playback before downloading the entire file.

## Impact on Steganography

These encoding parameters do not affect the steganographic content embedded in the video frames. The message remains securely hidden in the DCT coefficients of the frames, while the video itself is now properly encoded and playable.

## Testing

The solution was tested by:
1. Encoding a message into an MP4 video
2. Verifying that the encoded video plays correctly in various media players
3. Confirming that the steganographic content is preserved and can be extracted

## References

- [FFmpeg H.264 Video Encoding Guide](https://trac.ffmpeg.org/wiki/Encode/H.264)
- [FFmpeg Documentation](https://ffmpeg.org/documentation.html)
- [H.264 Profiles and Levels](https://en.wikipedia.org/wiki/H.264/MPEG-4_AVC#Profiles)