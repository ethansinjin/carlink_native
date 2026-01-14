# Google/Android MediaCodec Documentation Index

Downloaded: 2026-01-13

## Source URLs

- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/MediaCodec.Callback
- https://developer.android.com/reference/android/media/MediaFormat
- https://developer.android.com/reference/android/media/MediaCodecInfo
- https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities
- https://developer.android.com/media/platform/supported-formats
- https://developer.android.com/ndk/reference/group/media
- https://bigflake.com/mediacodec/ (unofficial, referenced by Google)

## Document List

| File | Description |
|------|-------------|
| [MediaCodec-BufferHandling.md](MediaCodec-BufferHandling.md) | Buffer ownership, getInputBuffer() rules, invalidation |
| [MediaCodec-AsyncCallback.md](MediaCodec-AsyncCallback.md) | Async mode callbacks, flush behavior, restrictions |
| [MediaCodec-H264-SPS-PPS.md](MediaCodec-H264-SPS-PPS.md) | Codec-specific data, SPS/PPS handling, csd-0/csd-1 |
| [MediaCodec-H264-FormatSupport.md](MediaCodec-H264-FormatSupport.md) | H.264 profile support, container formats, streaming |
| [MediaCodec-LowLatency.md](MediaCodec-LowLatency.md) | KEY_LOW_LATENCY, real-time decoding configuration |
| [MediaCodec-NDK-Reference.md](MediaCodec-NDK-Reference.md) | Native/C API reference (AMediaCodec) |
| [MediaCodecInfo-Reference.md](MediaCodecInfo-Reference.md) | Codec capabilities, profile/level constants |

## Key Findings for H264Renderer.java

### Buffer Invalidation Rule
> "After calling getInputBuffer() any ByteBuffer previously returned for the same input index MUST no longer be used."

This directly impacts `queueIdrWithSpsPps()` at line 1463 which calls `getInputBuffer(bufferIndex)` a second time with the same index.

### Flush Requires CSD Resubmission
> "If you flush the codec too soon after start() – generally, before the first output buffer is received – you will need to resubmit the codec-specific-data."

### Async Mode Flush Requires start()
> "After calling flush(), you MUST call start() to resume receiving input buffers."

### Mid-Stream SPS/PPS Injection
> "Package the entire new codec-specific configuration data together with the key frame into a single buffer (including start codes), and submit as a regular input buffer."

This validates the approach of prepending SPS+PPS to IDR frames, but the implementation must not call getInputBuffer() twice.
