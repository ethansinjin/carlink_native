# MediaCodec Buffer Handling Reference

Source: https://developer.android.com/reference/android/media/MediaCodec

## getInputBuffer() Rules

> "After calling this method any ByteBuffer previously returned for the same input index MUST no longer be used."

> "Once an input buffer is queued to the codec, it MUST NOT be used until it is later retrieved by getInputBuffer(int) in response to a dequeueInputBuffer(long) return value or an onInputBufferAvailable callback."

**Key Point**: Calling `getInputBuffer(index)` twice with the same index invalidates the first ByteBuffer reference.

## Buffer Ownership Lifecycle

| State | Owner |
|-------|-------|
| After `start()` | Codec owns all buffers |
| After `dequeueInputBuffer()` or `onInputBufferAvailable` | Client owns input buffer |
| After `queueInputBuffer()` | Codec owns input buffer |
| After `dequeueOutputBuffer()` or `onOutputBufferAvailable` | Client owns output buffer |
| After `releaseOutputBuffer()` | Codec owns output buffer |

## Buffer Lifecycle Flow

```
Dequeue → Fill → Queue → Codec Processing → Dequeue Output → Release → Reuse
```

## Deprecated API Note

`getInputBuffers()` was deprecated in API level 21. Use the new `getInputBuffer(int)` method instead each time an input buffer is dequeued. Note: As of API 21, dequeued input buffers are automatically cleared.

## ByteBuffer Position/Limit Warning

From bigflake.com/mediacodec (referenced by Google):

> "A critical issue developers encounter: failing to adjust the ByteBuffer position and limit values. You must manually set these after dequeuing output buffers, as the codec won't do this automatically as of API 19."

## Input Requirements for H.264

> "H.264 decoders require 'access units' with preserved packet boundaries (NAL units). You cannot feed arbitrary byte chunks; the decoder needs proper framing from the encoder."

## Key Points

1. **Buffer invalidation**: Any ByteBuffer obtained via `getInputBuffer(index)` becomes invalid when:
   - You call `getInputBuffer()` again with the **same index**
   - You queue the buffer via `queueInputBuffer()`
   - The codec is flushed or stopped

2. **Must obtain fresh reference**: Always obtain a fresh ByteBuffer reference via `getInputBuffer()` after dequeuing that index again.

3. **Do not hand buffers to MediaCodec**: You ask MediaCodec for a buffer, and if one is available, you copy the data in. You do not hand a buffer with data to MediaCodec.
