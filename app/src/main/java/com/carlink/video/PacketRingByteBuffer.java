package com.carlink.video;

/**
   * PacketRingByteBuffer - Thread-safe circular buffer for streaming packet data
   *
   * Purpose:
   * Manages high-throughput video/audio packet streams from the CPC200-CCPA adapter
   * in a memory-efficient ring buffer. Designed for automotive environments where
   * memory constraints and stability are critical.
   *
   * Key Features:
   * - Circular buffer with automatic resizing (1MB min, 64MB max)
   * - Thread-safe packet write/read operations with wrap-around support
   * - Safe-copy reads to prevent race conditions with MediaCodec
   * - Zero-copy direct write callback for performance-critical paths
   * - Emergency reset mechanisms to prevent OutOfMemoryError
   * - Extensive bounds validation to prevent buffer corruption
   *
   * Packet Structure:
   * Each packet consists of:
   *   [4 bytes: packet length] [4 bytes: skip offset] [n bytes: data]
   *
   * Usage:
   * - writePacket() - Add complete packet data to buffer
   * - directWriteToBuffer() - Zero-copy write via callback (preferred for H.264)
   * - readPacketInto(ByteBuffer) - Direct copy to target buffer (preferred for MediaCodec)
   * - readPacket() - Retrieve next packet as new ByteBuffer (legacy, allocates memory)
   * - availablePacketsToRead() - Check queued packet count
   *
   * Thread Safety:
   * All read/write operations are synchronized. Multiple threads can safely
   * write packets while a decoder thread reads them.
   *
   * Safety Limits:
   * MIN: 1MB | MAX: 64MB | EMERGENCY_RESET: 32MB
   * Exceeding limits triggers automatic reorganization or emergency reset.
   */
import java.io.IOException;
import java.nio.ByteBuffer;

import com.carlink.util.LogCallback;
import com.carlink.util.VideoDebugLogger;

public class PacketRingByteBuffer {
    public interface DirectWriteCallback {
         void write(byte[] bytes, int offset);
    }

    // Java memory management best practices - prevent OutOfMemoryError
    // Adjusted for automotive environment following project documentation specs
    private static final int MAX_BUFFER_SIZE = 64 * 1024 * 1024; // 64MB maximum for automotive safety
    private static final int MIN_BUFFER_SIZE = 1024 * 1024; // 1MB minimum for efficiency
    private static final int EMERGENCY_RESET_THRESHOLD = 32 * 1024 * 1024; // 32MB emergency threshold

    private byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;

    private int lastWritePositionBeforeEnd = 0;

    private int packetCount = 0;
    private int resizeAttemptCount = 0; // Track resize attempts for monitoring

    private LogCallback logCallback;

    public PacketRingByteBuffer(int initialSize) {
        // Enforce minimum and maximum size limits following Java best practices
        int safeSize = Math.max(MIN_BUFFER_SIZE, Math.min(initialSize, MAX_BUFFER_SIZE));
        buffer = new byte[safeSize];

        if (safeSize != initialSize) {
            log("Buffer size adjusted from " + initialSize + " to " + safeSize +
                " (min: " + MIN_BUFFER_SIZE + ", max: " + MAX_BUFFER_SIZE + ")");
        }
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    public boolean isEmpty() {
        return packetCount == 0;
    }

    public int availablePacketsToRead() {
        return packetCount;
    }

    private void reorganizeAndResizeIfNeeded() {

        int available = 0;
        if (writePosition > readPosition) {
            available = readPosition + buffer.length - writePosition;
        }
        else {
            available = readPosition - writePosition;
        }

        // Resize with safety limits following Java memory management best practices
        int newLength = buffer.length;
        if (available < buffer.length / 2) {
            int proposedSize = newLength * 2;
            resizeAttemptCount++;

            // Check against maximum size limit to prevent OutOfMemoryError
            if (proposedSize > MAX_BUFFER_SIZE) {
                if (buffer.length >= EMERGENCY_RESET_THRESHOLD) {
                    log("EMERGENCY: Buffer at " + (buffer.length / (1024*1024)) + "MB, performing emergency reset");
                    VideoDebugLogger.logRingEmergencyReset(buffer.length);
                    performEmergencyReset();
                    return;
                } else {
                    newLength = MAX_BUFFER_SIZE;
                    log("RESIZE capped at maximum: " + (newLength / (1024*1024)) + "MB");
                }
            } else {
                newLength = proposedSize;
                log("RESIZE to: " + (newLength / (1024*1024)) + "MB, attempt: " + resizeAttemptCount +
                    ", read:" + readPosition + ", write:" + writePosition + ", count:" + availablePacketsToRead());
                VideoDebugLogger.logRingResize(buffer.length, newLength, readPosition, writePosition);
            }
        }

        byte[] newBuffer = new byte[newLength];

        if (writePosition < readPosition) {
            int dataAtEndLength = lastWritePositionBeforeEnd - readPosition;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (dataAtEndLength < 0 || dataAtEndLength > buffer.length || readPosition < 0 || readPosition + dataAtEndLength > buffer.length) {
                log("CRITICAL: Invalid end copy parameters - readPosition: " + readPosition + ", dataAtEndLength: " + dataAtEndLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            if (writePosition < 0 || writePosition > buffer.length || dataAtEndLength + writePosition > newBuffer.length) {
                log("CRITICAL: Invalid start copy parameters - writePosition: " + writePosition + ", dataAtEndLength: " + dataAtEndLength + ", newBufferLength: " + newBuffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            // copy end
            System.arraycopy(buffer, readPosition, newBuffer, 0, dataAtEndLength);

            // copy from start
            System.arraycopy(buffer, 0, newBuffer, dataAtEndLength, writePosition);

            // update positions
            readPosition = 0;
            writePosition += dataAtEndLength;
        }
        else {
            int copyLength = writePosition - readPosition;
            
            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (copyLength < 0 || readPosition < 0 || readPosition + copyLength > buffer.length || copyLength > newBuffer.length) {
                log("CRITICAL: Invalid linear copy parameters - readPosition: " + readPosition + ", writePosition: " + writePosition + ", copyLength: " + copyLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return;
            }

            System.arraycopy(buffer, readPosition, newBuffer, 0, copyLength);

            writePosition -= readPosition;
            readPosition = 0;
        }

        log("RESIZE done, read:"+readPosition+", write:"+writePosition+", length:"+buffer.length+", count:"+availablePacketsToRead());

        buffer = newBuffer;
    }

    private int availableSpaceAtHead() {
        if (writePosition < readPosition) {
            return readPosition - writePosition;
        }

        return buffer.length - writePosition;
    }
    private int availableSpaceAtStart() {
        if (writePosition < readPosition) {
            return 0;
        }

        return readPosition;
    }

    // Maximum resize attempts before forcing emergency reset to prevent infinite loop
    private static final int MAX_RESIZE_ATTEMPTS = 5;

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            // Validate input parameters to prevent corruption
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                log("CRITICAL: Invalid write parameters - length: " + length + ", skipBytesCount: " + skipBytesCount);
                return; // Abort write operation
            }

            // Prevent excessively large packets that could cause memory issues
            if (length > buffer.length / 2) {
                log("WARNING: Large packet size " + length + " bytes, buffer size: " + buffer.length);
                // Still allow but warn about potential issues
            }

            boolean hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;

            int resizeLoopCount = 0;
            while (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                resizeLoopCount++;

                // Prevent infinite loop - if we've tried too many times, force emergency reset
                if (resizeLoopCount > MAX_RESIZE_ATTEMPTS) {
                    log("CRITICAL: Resize loop exceeded " + MAX_RESIZE_ATTEMPTS + " attempts, forcing emergency reset");
                    performEmergencyReset();
                    // After reset, recalculate space availability
                    hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
                    hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
                    hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;

                    // If still no space after emergency reset, packet is too large - drop it
                    if (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                        log("CRITICAL: Packet too large (" + length + " bytes) even after emergency reset - dropping");
                        return;
                    }
                    break;
                }

                reorganizeAndResizeIfNeeded();

                hasSpaceToWriteLength = availableSpaceAtHead() > 4 + 4;
                hasSpaceAtHead = availableSpaceAtHead() > length + 4 + 4;
                hasSpaceAtStart = availableSpaceAtStart() > length + 4 + 4;
            }

            // 1. write packet length
            writeInt(writePosition, length);
            writePosition += 4;

            // 2. write skip bytes count
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            // 3. write data
            if (!hasSpaceAtHead && hasSpaceAtStart) {
                // mark
                lastWritePositionBeforeEnd = writePosition;

                // reset position
                writePosition = 0;
            }

            callback.write(buffer, writePosition);

            // 4. update position
            writePosition += length;

            // 5. update count
            packetCount ++;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingWrite(length, skipBytesCount, packetCount);
        }
    }


    public void writePacket(byte[] source, int srcOffset, int length) {
        directWriteToBuffer(length, 0, (buf, off) -> System.arraycopy(source, srcOffset, buf, off, length));
    }

    ByteBuffer readPacket() {
        synchronized (this) {
            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            // reset position if on the end
            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            // Additional bounds checking
            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return ByteBuffer.allocate(0); // Return empty buffer
            }

            // CRITICAL FIX: Safe copy instead of zero-copy wrap
            //
            // Previously used ByteBuffer.wrap() which shared memory with ring buffer.
            // This caused a race condition: USB write thread could overwrite data before
            // MediaCodec finished reading it, causing H.264 reference frame corruption.
            //
            // Symptoms: Progressive video quality degradation (MPEG-like artifacts) that
            // only recovered after codec reset (which clears the ring buffer).
            //
            // The copy ensures data integrity at the cost of ~1-2% CPU overhead.
            // This is acceptable for stable video quality.
            //
            // See: https://developer.android.com/reference/android/media/MediaCodec
            // "The client needs to copy the data before modifying the buffer"
            byte[] packetData = new byte[actualLength];
            System.arraycopy(buffer, startPos, packetData, 0, actualLength);
            ByteBuffer result = ByteBuffer.wrap(packetData);

            readPosition += length;
            packetCount--;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingRead(length, actualLength, packetCount);

            return result;
        }
    }

    /**
     * Read next packet directly into a target ByteBuffer (zero-intermediate-allocation).
     *
     * OPTIMIZATION: This method copies directly from the ring buffer into the target
     * ByteBuffer, eliminating the intermediate byte[] allocation that readPacket() requires.
     * Use this when the target buffer is already available (e.g., MediaCodec input buffer).
     *
     * @param target The ByteBuffer to copy packet data into (must have sufficient capacity)
     * @return Number of bytes written to target, or 0 if no packet available or error
     */
    int readPacketInto(ByteBuffer target) {
        synchronized (this) {
            if (packetCount == 0) {
                return 0;
            }

            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            // Validate parameters to prevent ArrayIndexOutOfBoundsException
            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                log("CRITICAL: Invalid packet parameters - length: " + length + ", skipBytes: " + skipBytes);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            // Reset position if on the end
            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            // Calculate actual data bounds
            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                log("CRITICAL: Buffer bounds exceeded - startPos: " + startPos + ", actualLength: " + actualLength + ", bufferLength: " + buffer.length);
                // Reset to safe state
                readPosition = 0;
                writePosition = 0;
                packetCount = 0;
                return 0;
            }

            // Check target buffer has sufficient space
            if (target.remaining() < actualLength) {
                log("CRITICAL: Target buffer too small - remaining: " + target.remaining() + ", needed: " + actualLength);
                // Don't consume packet if we can't write it
                readPosition -= 8; // Rewind header reads
                return 0;
            }

            // Direct copy from ring buffer to target ByteBuffer (single copy, no intermediate allocation)
            target.put(buffer, startPos, actualLength);

            readPosition += length;
            packetCount--;

            // Debug logging (throttled inside VideoDebugLogger)
            VideoDebugLogger.logRingRead(length, actualLength, packetCount);

            return actualLength;
        }
    }

    private void writeInt(int offset, int value) {
        // Bounds checking to prevent ArrayIndexOutOfBoundsException
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: writeInt bounds exceeded - offset: " + offset + ", bufferLength: " + buffer.length);
            VideoDebugLogger.logRingBoundsError("writeInt", offset, 4, buffer.length);
            return; // Abort write operation
        }

        buffer[offset]   = (byte) ((value & 0xFF000000) >> 24);
        buffer[offset+1] = (byte) ((value & 0x00FF0000) >> 16);
        buffer[offset+2] = (byte) ((value & 0x0000FF00) >> 8);
        buffer[offset+3] = (byte)  (value & 0x000000FF);
    }

    private int readInt(int offset) {
        // Bounds checking to prevent ArrayIndexOutOfBoundsException
        if (offset < 0 || offset + 3 >= buffer.length) {
            log("CRITICAL: readInt bounds exceeded - offset: " + offset + ", bufferLength: " + buffer.length);
            VideoDebugLogger.logRingBoundsError("readInt", offset, 4, buffer.length);
            return 0; // Return safe default value
        }

        return  ((buffer[offset]   << 24) & 0xFF000000) |
                ((buffer[offset+1] << 16) & 0x00FF0000) |
                ((buffer[offset+2] << 8)  & 0x0000FF00) |
                ((buffer[offset+3])       & 0x000000FF);
    }

    public void reset() {
        packetCount = 0;
        writePosition = 0;
        readPosition = 0;
    }

    private void performEmergencyReset() {
        log("EMERGENCY RESET: Resetting buffer to prevent OutOfMemoryError - was " +
            (buffer.length / (1024*1024)) + "MB, resetting to " + (MIN_BUFFER_SIZE / (1024*1024)) + "MB");

        // Reset to minimum safe size following Java memory management guidelines
        buffer = new byte[MIN_BUFFER_SIZE];
        readPosition = 0;
        writePosition = 0;
        lastWritePositionBeforeEnd = 0;
        packetCount = 0;
        resizeAttemptCount = 0;

        // Note: Explicit System.gc() removed per Android best practices
        // Android runtime manages GC timing; explicit calls can cause unpredictable pauses
        // Reference: https://developer.android.com/topic/performance/memory
        log("Emergency reset complete");
    }
}
