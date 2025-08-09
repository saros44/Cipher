# Video Duration Fix Documentation

## Issue Description

The video steganography feature was successfully encoding messages into MP4 videos, but the resulting encoded videos were significantly shorter than the original videos, typically only 1-2 seconds long. This issue occurred because:

1. Only a limited number of frames (10) were being extracted from the original video
2. The FFmpeg command used to reconstruct the video was using the `-shortest` flag, which made the output video only as long as the shortest input stream

## Solution

The issue was resolved by making several changes to the `VideoEncodeService` class:

### 1. Extracting More Frames

- Added a new constant `MAX_FRAMES_TO_EXTRACT = 1000` to allow extracting more frames from the original video
- Modified the FFmpeg command in the `extractFrames` method to extract up to `MAX_FRAMES_TO_EXTRACT` frames instead of just `FRAMES_TO_ENCODE` (10) frames
- Updated the frame reading logic to read all extracted frames, not just the first 10

### 2. Processing All Frames

- Modified the `encodeMessageIntoFrames` method to process all frames, not just the first 10
- Added a flag `encodeInThisFrame` to determine whether to encode the message in a particular frame
- Updated the block processing logic to use this flag when deciding whether to encode the message in a frame
- Modified the logic for adding frames to the output list to ensure all frames are processed

### 3. Maintaining Original Video Duration

- Removed the `-shortest` flag from the FFmpeg command in the `reconstructVideo` method
- Added code to calculate and log the original video's duration and frame rate
- Added the `-t` parameter to specify the exact duration of the output video, ensuring it matches the original
- Added the `-loop` parameter (when needed) to ensure all frames are used in the output video, even if we have fewer frames than the original

## Technical Details

### Frame Extraction

The original implementation extracted only 10 frames from the video, regardless of its length. This meant that even for a 30-second video, we were only using 10 frames (less than 1 second of content at typical frame rates).

The updated implementation extracts up to 1000 frames, which is sufficient for videos up to about 30-40 seconds at typical frame rates (24-30 fps). For longer videos, we still might not extract all frames, but we extract enough to maintain the visual continuity.

### Message Encoding

We still only encode the message in the first 10 frames for security and efficiency reasons. This is sufficient for the steganographic content while keeping the processing time reasonable.

### Video Reconstruction

The key to maintaining the original video duration is the combination of:

1. Using the correct frame rate from the original video
2. Setting the exact duration with the `-t` parameter
3. Enabling frame looping with `-loop 1` when necessary
4. Removing the `-shortest` flag which was truncating the video

## Impact on Steganography

These changes do not affect the steganographic content embedded in the video frames. The message is still securely hidden in the DCT coefficients of the first 10 frames, while the video itself now maintains its original duration.

## Testing

The solution was tested by:
1. Encoding a message into an MP4 video of various lengths
2. Verifying that the encoded video plays correctly and has the same duration as the original
3. Confirming that the steganographic content is preserved and can be extracted

## References

- [FFmpeg Documentation on the -t option](https://ffmpeg.org/ffmpeg.html#toc-Main-options)
- [FFmpeg Documentation on the -loop option](https://ffmpeg.org/ffmpeg.html#toc-Advanced-options)