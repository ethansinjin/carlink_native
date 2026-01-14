# AMediaCodec NDK Reference

Source: https://developer.android.com/ndk/reference/group/media

## Overview

`AMediaCodec` is the NDK API for encoding and decoding media using Android's native codec infrastructure. Provides access to both hardware-accelerated and software codecs.

## Creating a Codec

```c
// Create decoder by MIME type
AMediaCodec *codec = AMediaCodec_createDecoderByType("video/avc");

// Create encoder by MIME type
AMediaCodec *codec = AMediaCodec_createEncoderByType("video/avc");

// Create codec by exact name
AMediaCodec *codec = AMediaCodec_createCodecByName("c2.android.h264.decoder");
```

## Configuration

```c
AMediaFormat *format = AMediaFormat_new();
AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 1920);
AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 1080);

media_status_t status = AMediaCodec_configure(codec, format, surface, crypto, flags);
AMediaFormat_delete(format);
```

## Input Buffer Handling

```c
// Get available input buffer index (timeout in microseconds)
ssize_t inputIndex = AMediaCodec_dequeueInputBuffer(codec, 5000);

if (inputIndex >= 0) {
    size_t bufferSize = 0;
    uint8_t *inputBuffer = AMediaCodec_getInputBuffer(codec, inputIndex, &bufferSize);

    if (inputBuffer) {
        // Copy data into buffer
        memcpy(inputBuffer, data, dataSize);

        // Queue the buffer for processing
        AMediaCodec_queueInputBuffer(codec, inputIndex, 0, dataSize,
                                     presentationTimeUs, flags);
    }
}
```

## Buffer Flags

```c
AMEDIACODEC_BUFFER_FLAG_KEY_FRAME       // Encoded keyframe
AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG    // Codec-specific data (SPS/PPS)
AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM   // Last buffer in stream
AMEDIACODEC_BUFFER_FLAG_PARTIAL_FRAME   // Partial access unit
AMEDIACODEC_BUFFER_FLAG_MUXER_DATA      // Non-media data for muxer
AMEDIACODEC_BUFFER_FLAG_DECODE_ONLY     // Decode but don't output
```

## Buffer Lifecycle

```
Dequeue → Fill → Queue → Codec Processing → Dequeue Output → Release → Reuse
```

### Key Points

1. **Dequeue Input**: Returns buffer index or:
   - `-1` (INFO_TRY_AGAIN_LATER): No buffer available, retry
   - Negative value: Error code

2. **Get Buffer Pointer**: Returns the actual buffer memory

3. **Queue for Processing**: Parameters:
   - `offset`: Usually 0
   - `size`: Number of bytes filled
   - `time`: Presentation timestamp in microseconds
   - `flags`: AMEDIACODEC_BUFFER_FLAG_* values

4. **Dequeue Output**: Returns:
   - Index >= 0: Valid output buffer
   - AMEDIACODEC_INFO_TRY_AGAIN_LATER: Retry
   - AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED: Format changed
   - AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED: (deprecated)

5. **Release Output**: With render flag for surfaces

## Asynchronous Operation

```c
void onInputAvailable(AMediaCodec *codec, void *userdata, int32_t index) {
    // Process input buffer at index
}

void onOutputAvailable(AMediaCodec *codec, void *userdata, int32_t index,
                       AMediaCodecBufferInfo *bufferInfo) {
    // Process output buffer at index
}

void onFormatChanged(AMediaCodec *codec, void *userdata, AMediaFormat *format) {
    // Handle output format change
}

void onError(AMediaCodec *codec, void *userdata, media_status_t error,
             int32_t actionCode, const char *detail) {
    bool recoverable = AMediaCodecActionCode_isRecoverable(actionCode);
    bool transient = AMediaCodecActionCode_isTransient(actionCode);
}

// Register callbacks
AMediaCodecOnAsyncNotifyCallback callbacks = {
    .onAsyncInputAvailable = onInputAvailable,
    .onAsyncOutputAvailable = onOutputAvailable,
    .onAsyncFormatChanged = onFormatChanged,
    .onAsyncError = onError
};

AMediaCodec_setAsyncNotifyCallback(codec, callbacks, userData);
```

## Async Mode Flush Behavior

> "After calling AMediaCodec_flush(), you must call AMediaCodec_start() to 'resume' receiving input buffers, even if an input surface was created."

## API Level Availability

- **API 21**: Core codec functionality
- **API 26**: Input/Output surfaces, persistent surfaces
- **API 28**: `getName()`, `getInputFormat()`, `getOutputFormat()`, async callbacks
- **API 31**: `AMediaMuxer_append()`, track query methods
