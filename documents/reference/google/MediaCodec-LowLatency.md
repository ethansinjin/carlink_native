# MediaCodec Low Latency Decoding Reference

Sources:
- https://developer.android.com/reference/android/media/MediaFormat
- https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities

## KEY_LOW_LATENCY

An optional key describing the low latency decoding mode. Applies only to decoders.

> "When enabled, the decoder doesn't hold input and output data more than required by the codec standards."

## Checking Support

```java
MediaCodecInfo codecInfo = // ... get codec info
CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
boolean lowLatencySupported = caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency);
```

## Enabling Low Latency Mode

```java
MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);

// Check if supported first
if (lowLatencySupported) {
    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
    Log.d(TAG, "Low latency mode enabled");
} else {
    Log.d(TAG, "Low latency mode not supported by decoder");
}

codec.configure(format, surface, null, 0);
```

## FEATURE_LowLatency

From MediaCodecInfo.CodecCapabilities:

The decoder only codec feature indicating support for low latency decoding. If supported, clients can enable the low latency mode for the decoder.

## Related Settings

### KEY_PRIORITY
```java
format.setInteger(MediaFormat.KEY_PRIORITY, 0);  // 0 = Realtime priority
```

### KEY_MAX_INPUT_SIZE (Intel optimization)
```java
// For Intel decoders, disable Adaptive Playback which causes high latency
if (codecName.contains("Intel")) {
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, width * height);
}
```

## Real-Time Streaming Considerations

Device implementations must support dynamic video resolution and frame rate switching through the standard Android APIs within the same stream for all VP8, VP9, H.264, and H.265 codecs in real time.

## B-Frame Latency Note

> "If your app does not use MediaMuxer to assemble the final output file, you may enable B-frames by setting the KEY_LATENCY value to 2 instead of 1. This should allow the codec to produce B-frames."

For real-time low-latency scenarios, B-frames are typically disabled.
