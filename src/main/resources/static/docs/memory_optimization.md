# Memory Optimization for Video Steganography

## Issue Description

The video steganography feature was failing with an OutOfMemoryError when processing larger videos:

```
Exception in thread "File Watcher" java.lang.OutOfMemoryError: Java heap space
```

The error occurred in the `extractFrames` method of the `VideoEncodeService` class when trying to load all frames from a video into memory at once. For high-resolution videos or videos with many frames, this approach quickly exhausted the available Java heap space.

## Root Causes

1. **Loading all frames into memory**: The original implementation extracted up to 1000 frames from the video and loaded them all into memory simultaneously, regardless of how many frames were actually needed for encoding the message.

2. **High-resolution frames**: No scaling was applied to the extracted frames, so high-resolution videos (e.g., 4K) would consume enormous amounts of memory.

3. **Inefficient video reconstruction**: The video reconstruction process required all frames to be in memory before writing them to disk as PNG files, which further increased memory usage.

4. **Lack of memory management**: No explicit memory management techniques were implemented to free up memory during processing.

## Solution

The issue was resolved by implementing several memory optimization techniques:

### 1. Optimized Frame Extraction

- **Extract only necessary frames**: Modified the `extractFrames` method to extract only the frames needed for encoding (typically 10 frames) instead of up to 1000 frames.
  
- **Downscaling high-resolution frames**: Added a scaling filter to FFmpeg to reduce frame size for high-resolution videos (scaling down to max 720p if larger).
  
- **Immediate file deletion**: Implemented immediate deletion of frame files after reading them to reduce disk space usage.

### 2. Efficient Video Reconstruction

- **Two-step approach**: Implemented a two-step approach for video reconstruction:
  1. Create a short video segment from the modified frames
  2. Use FFmpeg's overlay filter to combine this segment with the original video
  
- **Memory clearing**: Added explicit clearing of frame lists and garbage collection after frames are no longer needed.

- **Streaming approach**: Used FFmpeg's capabilities to handle the video reconstruction without loading all frames into memory.

### 3. Memory Management Techniques

- **Explicit garbage collection**: Added `System.gc()` calls at strategic points to free up memory.

- **Memory usage logging**: Implemented logging of memory usage at various stages of the process to monitor consumption.

- **Batch processing**: Added support for processing frames in batches (though not fully implemented in the current version).

- **Error handling**: Enhanced error handling for OutOfMemoryError with more informative error messages.

### 4. Enhanced Logging

- **Comprehensive logging**: Added detailed logging throughout the process to track memory usage and processing stages.

- **Video information logging**: Added logging of video details (duration, size, resolution) to help with debugging.

## Technical Implementation Details

### Frame Extraction Optimization

```java
// Only extract the frames needed for encoding
command.add("-vframes");
command.add(String.valueOf(FRAMES_TO_ENCODE));

// Scale down high-resolution videos
command.add("-vf");
command.add("scale='min(1280,iw)':'min(720,ih)'");
```

### Memory Management

```java
// Clear frames list to free memory
frames.clear();
System.gc();

// Log memory usage
logMemoryUsage("After encoding message into frames");
```

### Video Reconstruction

```java
// Step 1: Create a short video from the modified frames
// Step 2: Combine with original video using overlay filter
command2.add("-filter_complex");
command2.add("[0:v][1:v]overlay=enable='lt(t," + (FRAMES_TO_ENCODE / frameRate) + ")'[v]");
```

## System Requirements and Recommendations

### Minimum Requirements

- **Java Heap Space**: At least 1GB of heap space (`-Xmx1g`)
- **Disk Space**: At least 3 times the size of the input video for temporary files
- **CPU**: Multi-core processor recommended for faster encoding
- **FFmpeg**: Automatically provided by the jave-all-deps dependency

### Recommended Settings

For processing larger videos:

- **Java Heap Space**: 2-4GB (`-Xmx2g` to `-Xmx4g`)
- **Video Resolution**: 720p or 1080p (higher resolutions will be automatically scaled down)
- **Video Length**: Under 10 minutes for optimal performance
- **Message Size**: Keep messages under 1KB for best results

### JVM Configuration

To increase the Java heap space, add the following JVM arguments when starting the application:

```
-Xmx2g -XX:+UseG1GC -XX:MaxGCPauseMillis=200
```

## Troubleshooting

If you still encounter OutOfMemoryError:

1. **Increase Java Heap Space**: Use the `-Xmx` JVM argument to allocate more memory (e.g., `-Xmx4g`).

2. **Reduce Video Resolution**: Pre-process the video to reduce its resolution before encoding.

3. **Shorten the Video**: Use a shorter segment of the video for encoding.

4. **Monitor Memory Usage**: Check the application logs for memory usage information to identify bottlenecks.

5. **Use a Different Video**: Some video codecs or formats may require more memory to process. Try using a different video format (e.g., MP4 with H.264 encoding).

## Performance Impact

The memory optimizations have minimal impact on the quality of the steganography:

- The message is still securely hidden in the DCT coefficients of the frames
- The video quality remains high due to the optimized FFmpeg parameters
- The original video duration and audio are preserved

However, there may be a slight visual difference in the first few frames of the video where the message is encoded, especially if the frames were scaled down from a higher resolution.

## References

- [Java Memory Management](https://docs.oracle.com/javase/8/docs/technotes/guides/vm/gctuning/introduction.html)
- [FFmpeg Documentation](https://ffmpeg.org/documentation.html)
- [FFmpeg Scaling Documentation](https://trac.ffmpeg.org/wiki/Scaling)
- [JAVE Library Documentation](https://github.com/a-schild/jave2)