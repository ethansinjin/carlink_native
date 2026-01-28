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

✅ **COMPLIANT**: `queueIdrWithSpsPps()` receives the ByteBuffer as a parameter from the callback - it does NOT call `getInputBuffer()` a second time. The buffer is modified in-place correctly.

### Flush Requires CSD Resubmission
> "If you flush the codec too soon after start() – generally, before the first output buffer is received – you will need to resubmit the codec-specific-data."

✅ **COMPLIANT**: `requestCodecConfigInjection()` sets `codecConfigPending=true` after flush, and `onInputBufferAvailable` checks this flag to inject cached SPS+PPS via `injectCodecConfigToBuffer()`.

### Async Mode Flush Requires start()
> "After calling flush(), you MUST call start() to resume receiving input buffers."

✅ **COMPLIANT**: All flush paths (`reset()`, `resume()`, `recreateCodecWithSurface()`) call `mCodec.start()` after `mCodec.flush()`.

### Mid-Stream SPS/PPS Injection
> "Package the entire new codec-specific configuration data together with the key frame into a single buffer (including start codes), and submit as a regular input buffer."

✅ **COMPLIANT**: `queueIdrWithSpsPps()` packages SPS+PPS+IDR into a single buffer and queues as a regular input buffer (without BUFFER_FLAG_CODEC_CONFIG), which is correct for mid-stream injection.

## H264Renderer.java Compliance Summary

| Requirement | Status | Implementation |
|-------------|--------|----------------|
| Buffer invalidation rule | ✅ | Buffer passed as param, not re-fetched |
| CSD resubmission after flush | ✅ | `codecConfigPending` + `injectCodecConfigToBuffer()` |
| start() after flush() in async | ✅ | All paths call start() after flush() |
| Mid-stream SPS/PPS | ✅ | Combined buffer as regular input |
| Low latency check before enable | ✅ | `isFeatureSupported(FEATURE_LowLatency)` |
| Realtime priority | ✅ | `KEY_PRIORITY = 0` |
| No dequeue* calls in async mode | ✅ | Uses only callback-provided indices |
| Dedicated callback thread | ✅ | `HandlerThread` with `THREAD_PRIORITY_URGENT_AUDIO` |

Last verified: 2026-01-26
