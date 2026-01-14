# MediaCodec.Callback - Async Mode Reference

Source: https://developer.android.com/reference/android/media/MediaCodec.Callback

## Overview

`MediaCodec.Callback` is an abstract class (added in API level 21) used to notify applications asynchronously of various MediaCodec events.

## Callback Methods

### 1. onInputBufferAvailable
```java
public abstract void onInputBufferAvailable(MediaCodec codec, int index)
```
- **Purpose**: Called when an input buffer becomes available
- **Parameters**:
  - `codec`: The MediaCodec object (non-null)
  - `index`: The index of the available input buffer
- **Usage**: Use this to feed data into the codec

### 2. onOutputBufferAvailable
```java
public abstract void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info)
```
- **Purpose**: Called when an output buffer becomes available
- **Parameters**:
  - `codec`: The MediaCodec object (non-null)
  - `index`: The index of the available output buffer
  - `info`: `MediaCodec.BufferInfo` describing the available output buffer (non-null)
- **Usage**: Retrieve encoded/decoded data from the output buffer

### 3. onOutputFormatChanged
```java
public abstract void onOutputFormatChanged(MediaCodec codec, MediaFormat format)
```
- **Purpose**: Called when the output format has changed
- **Parameters**:
  - `codec`: The MediaCodec object (non-null)
  - `format`: The new output `MediaFormat` (non-null)
- **Usage**: Update UI or processing based on new format (resolution changes, etc.)

### 4. onError
```java
public abstract void onError(MediaCodec codec, MediaCodec.CodecException e)
```
- **Purpose**: Called when the MediaCodec encountered an error
- **Parameters**:
  - `codec`: The MediaCodec object (non-null)
  - `e`: `MediaCodec.CodecException` describing the error (non-null)
- **Usage**: Handle error conditions and cleanup

## Async Mode Restrictions

> "When asynchronous callback is enabled, the client should NOT call:
> - `getInputBuffers()`
> - `getOutputBuffers()`
> - `dequeueInputBuffer()`
> - `dequeueOutputBuffer()`"

## Flush Behavior in Async Mode

> "flush() behaves differently in asynchronous mode. After calling flush(), you MUST call start() to 'resume' receiving input buffers, even if an input surface was created."

## Callback Threading

All callbacks are fired on one internal thread. Do not perform heavy duty tasks on callback thread.

## Usage Pattern

All callback methods are executed asynchronously and should not perform blocking operations. The abstract methods must be implemented by subclasses.

## ExoPlayer Enhancement (API 31+)

Asynchronous buffer queueing operates MediaCodec instances in asynchronous mode and uses additional threads to schedule decoding and rendering of data. Enabling it can reduce dropped frames and audio underruns. Enabled by default on Android 12 (API level 31) and above.
