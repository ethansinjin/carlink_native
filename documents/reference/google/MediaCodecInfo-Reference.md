# MediaCodecInfo Reference

Source: https://developer.android.com/reference/android/media/MediaCodecInfo

## Overview

`MediaCodecInfo` provides information about media codecs available on an Android device. Added in API level 16.

## Query Codec Properties

| Method | Description | API Level |
|--------|-------------|-----------|
| `isEncoder()` | Check if codec is an encoder | 16 |
| `isHardwareAccelerated()` | Query hardware acceleration | 29 |
| `isSoftwareOnly()` | Query if software-only | 29 |
| `isVendor()` | Check if provided by device manufacturer | 29 |
| `getName()` | Get codec name | 16 |
| `getCanonicalName()` | Get underlying codec name | 29 |
| `getSupportedTypes()` | Get supported MIME types | 16 |
| `getCapabilitiesForType(String)` | Get detailed capabilities | 16 |

## Finding a Codec

```java
private static MediaCodecInfo selectCodec(String mimeType) {
    int numCodecs = MediaCodecList.getCodecCount();
    for (int i = 0; i < numCodecs; i++) {
        MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

        if (!codecInfo.isEncoder()) {
            continue;
        }

        String[] types = codecInfo.getSupportedTypes();
        for (int j = 0; j < types.length; j++) {
            if (types[j].equalsIgnoreCase(mimeType)) {
                return codecInfo;
            }
        }
    }
    return null;
}
```

## Nested Classes

### CodecCapabilities
Detailed codec capabilities including:
- Supported profiles and levels
- Color formats
- Feature support (low latency, etc.)

### CodecProfileLevel
Available profiles and levels for video codecs including H.264.

### VideoCapabilities
Video-specific capabilities:
- Supported resolutions
- Frame rates
- Bitrates

### AudioCapabilities
Audio-specific capabilities.

### EncoderCapabilities
Encoder-specific capabilities.

## Checking Feature Support

```java
MediaCodecInfo codecInfo = // ... get codec info
CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");

// Check low latency support
boolean lowLatency = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);

// Check adaptive playback support
boolean adaptive = caps.isFeatureSupported(CodecCapabilities.FEATURE_AdaptivePlayback);
```

## H.264 Profile Constants (CodecProfileLevel)

| Constant | Value | Description |
|----------|-------|-------------|
| AVCProfileBaseline | 1 | Baseline Profile |
| AVCProfileMain | 2 | Main Profile |
| AVCProfileExtended | 4 | Extended Profile |
| AVCProfileHigh | 8 | High Profile |
| AVCProfileHigh10 | 16 | High 10 Profile |
| AVCProfileHigh422 | 32 | High 4:2:2 Profile |
| AVCProfileHigh444 | 64 | High 4:4:4 Profile |

## Security Model (API 36+)

| Constant | Value | Description |
|----------|-------|-------------|
| SECURITY_MODEL_SANDBOXED | 0 | Runs in sandboxed process |
| SECURITY_MODEL_MEMORY_SAFE | 1 | Memory-safe implementation |
