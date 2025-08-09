# FFmpeg Configuration for Video Steganography

## Issue Resolution

### Problem

The video steganography feature was failing with the following error:

```
Video encoding failed: java.io.IOException: Cannot run program "C:\Users\VICTUS\AppData\Local\Temp\jave\ffmpeg-amd64-3.1.1.exe": CreateProcess error=2, The system cannot find the file specified
```

This error occurred because the application couldn't find the FFmpeg executable that is required for video processing operations. The `VideoEncodeService` class uses the JAVE library (Java Audio Video Encoder) which depends on FFmpeg for video manipulation.

### Solution

The issue was resolved by adding the `jave-all-deps` dependency to the project's `pom.xml` file:

```xml
<dependency>
    <groupId>ws.schild</groupId>
    <artifactId>jave-all-deps</artifactId>
    <version>3.1.1</version>
</dependency>
```

This dependency provides the FFmpeg binaries for all supported platforms (Windows, Linux, macOS). The JAVE library uses a class called `DefaultFFMPEGLocator` to extract and locate these binaries at runtime.

## How It Works

1. When the application starts, the `DefaultFFMPEGLocator` extracts the appropriate FFmpeg binary for the current platform from the `jave-all-deps` JAR file.
2. The binary is extracted to a temporary directory (e.g., `C:\Users\VICTUS\AppData\Local\Temp\jave\`).
3. When video processing is needed, the `VideoEncodeService` uses this extracted FFmpeg binary to perform operations like frame extraction and video reconstruction.

## Technical Requirements

- The application must have write access to the temporary directory where FFmpeg is extracted.
- Sufficient disk space must be available for the extracted FFmpeg binary and temporary video processing files.
- The `jave-core` and `jave-all-deps` dependencies must be of the same version to ensure compatibility.

## Troubleshooting

If you encounter FFmpeg-related issues:

1. Verify that both `jave-core` and `jave-all-deps` dependencies are included in the project with matching versions.
2. Check if the application has sufficient permissions to write to the temporary directory.
3. Ensure that there is enough disk space available for the FFmpeg binary and temporary files.
4. Look for any antivirus or security software that might be blocking the execution of the extracted FFmpeg binary.

## References

- [JAVE Library Documentation](https://github.com/a-schild/jave2)
- [FFmpeg Official Website](https://ffmpeg.org/)