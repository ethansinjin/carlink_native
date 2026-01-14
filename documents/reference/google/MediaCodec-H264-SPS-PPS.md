# H.264 Codec-Specific Data (SPS/PPS) Reference

Sources:
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/MediaFormat
- https://bigflake.com/mediacodec/

## What is Codec-Specific Data (CSD)?

> "Some formats, notably AAC audio and MPEG4, H.264 and H.265 video formats require the actual data to be prefixed by a number of buffers containing setup data, or codec specific data."

## CSD Contents for H.264/AVC

| Key | Content | Description |
|-----|---------|-------------|
| csd-0 | SPS | Sequence Parameter Set |
| csd-1 | PPS | Picture Parameter Set |

Each parameter set MUST start with a start code: `\x00\x00\x00\x01`

## Method 1: Via MediaFormat (Preferred)

```java
MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);

// SPS with start code (00 00 00 01 67 ...)
ByteBuffer spsBuffer = ByteBuffer.wrap(spsData);
format.setByteBuffer("csd-0", spsBuffer);

// PPS with start code (00 00 00 01 68 ...)
ByteBuffer ppsBuffer = ByteBuffer.wrap(ppsData);
format.setByteBuffer("csd-1", ppsBuffer);

codec.configure(format, surface, null, 0);
codec.start();
// CSD is automatically submitted to codec on start()
```

**Important**: When using this method, you must NOT submit CSD data explicitly via input buffers.

## Method 2: Via Input Buffers

```java
// Get input buffer
int index = codec.dequeueInputBuffer(timeout);
ByteBuffer buffer = codec.getInputBuffer(index);

// Copy SPS+PPS data (can be concatenated)
buffer.put(spsData);  // With start code
buffer.put(ppsData);  // With start code

// Queue with BUFFER_FLAG_CODEC_CONFIG
codec.queueInputBuffer(index, 0, totalSize, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
```

## Propagating CSD from Encoder to Decoder

> "If you are feeding encoder output to the decoder, the first packet from the encoder has the BUFFER_FLAG_CODEC_CONFIG flag set. You need to propagate this flag to the decoder."

## Critical Warning: Flushing

> "If you flush the codec too soon after start() – generally, before the first output buffer or output format change is received – you will need to resubmit the codec-specific-data to the codec."

## Mid-Stream Configuration Changes

> "For H.264, H.265, VP8 and VP9, it is possible to change the picture size or configuration mid-stream. To do this, you must package the entire new codec-specific configuration data together with the key frame into a single buffer (including any start codes), and submit it as a regular input buffer."

Example: SPS + PPS + IDR in single buffer for mid-stream resolution change.

## Common Mistake

> "A common mistake is neglecting to set the Codec-Specific Data through the keys 'csd-0' and 'csd-1'. This is raw data containing things like Sequence Parameter Set (SPS) and Picture Parameter Set (PPS)."

## NAL Unit Types for Reference

| NAL Type | Description |
|----------|-------------|
| 1 | Non-IDR slice (P-frame, B-frame) |
| 5 | IDR slice (keyframe) |
| 6 | SEI (Supplemental Enhancement Information) |
| 7 | SPS (Sequence Parameter Set) |
| 8 | PPS (Picture Parameter Set) |

## H.264 Annex B Format

Start codes:
- 3-byte: `00 00 01`
- 4-byte: `00 00 00 01`

NAL header byte follows start code. Lower 5 bits = NAL type.
