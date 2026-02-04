package com.carlink.video;

/**
 * Simple fixed-size ring buffer for video packet streaming.
 * Packet format: [4B length][4B skip offset][data]
 */
import java.nio.ByteBuffer;

import com.carlink.util.LogCallback;

public class PacketRingByteBuffer {
    public interface DirectWriteCallback {
         void write(byte[] bytes, int offset);
    }

    private static final int BUFFER_SIZE = 512 * 1024;  // Fixed 512KB

    private final byte[] buffer;
    private int readPosition = 0;
    private int writePosition = 0;
    private int packetCount = 0;

    private LogCallback logCallback;

    public PacketRingByteBuffer(int ignored) {
        buffer = new byte[BUFFER_SIZE];
    }

    private void log(String message) {
        if (logCallback != null) {
            logCallback.log(message);
        }
    }

    public void setLogCallback(LogCallback callback) {
        this.logCallback = callback;
    }

    public synchronized boolean isEmpty() {
        return packetCount == 0;
    }

    public synchronized int availablePacketsToRead() {
        return packetCount;
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

    public void directWriteToBuffer(int length, int skipBytesCount, DirectWriteCallback callback) {
        synchronized (this) {
            if (length < 0 || skipBytesCount < 0 || skipBytesCount > length) {
                return;
            }

            boolean hasSpaceToWriteLength = availableSpaceAtHead() > 8;
            boolean hasSpaceAtHead = availableSpaceAtHead() > length + 8;
            boolean hasSpaceAtStart = availableSpaceAtStart() > length + 8;

            // If no space, drop oldest packet
            while (!hasSpaceToWriteLength || !(hasSpaceAtStart || hasSpaceAtHead)) {
                if (packetCount == 0) {
                    // Buffer empty but still no space - packet too large
                    return;
                }
                dropOldestPacket();
                hasSpaceToWriteLength = availableSpaceAtHead() > 8;
                hasSpaceAtHead = availableSpaceAtHead() > length + 8;
                hasSpaceAtStart = availableSpaceAtStart() > length + 8;
            }

            // Write packet length
            writeInt(writePosition, length);
            writePosition += 4;

            // Write skip bytes count
            writeInt(writePosition, skipBytesCount);
            writePosition += 4;

            // Wrap if needed
            if (!hasSpaceAtHead && hasSpaceAtStart) {
                writePosition = 0;
            }

            callback.write(buffer, writePosition);
            writePosition += length;
            packetCount++;
        }
    }

    private void dropOldestPacket() {
        if (packetCount == 0) return;

        int length = readInt(readPosition);
        if (length <= 0 || length > buffer.length) {
            reset();
            return;
        }

        readPosition += 8;
        if (readPosition + length > buffer.length) {
            readPosition = 0;
        }
        readPosition += length;
        packetCount--;
    }

    public void writePacket(byte[] source, int srcOffset, int length) {
        directWriteToBuffer(length, 0, (buf, off) -> System.arraycopy(source, srcOffset, buf, off, length));
    }

    /** Read packet directly into target buffer. Returns bytes written or 0 if empty/error. */
    int readPacketInto(ByteBuffer target) {
        synchronized (this) {
            if (packetCount == 0) {
                return 0;
            }

            int length = readInt(readPosition);
            readPosition += 4;

            int skipBytes = readInt(readPosition);
            readPosition += 4;

            if (length < 0 || skipBytes < 0 || skipBytes > length) {
                reset();
                return 0;
            }

            if (readPosition + length > buffer.length) {
                readPosition = 0;
            }

            int actualLength = length - skipBytes;
            int startPos = readPosition + skipBytes;

            if (actualLength < 0 || startPos < 0 || startPos + actualLength > buffer.length) {
                reset();
                return 0;
            }

            if (target.remaining() < actualLength) {
                readPosition -= 8;
                return 0;
            }

            target.put(buffer, startPos, actualLength);
            readPosition += length;
            packetCount--;

            return actualLength;
        }
    }

    private void writeInt(int offset, int value) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            return;
        }
        buffer[offset]   = (byte) ((value & 0xFF000000) >> 24);
        buffer[offset+1] = (byte) ((value & 0x00FF0000) >> 16);
        buffer[offset+2] = (byte) ((value & 0x0000FF00) >> 8);
        buffer[offset+3] = (byte)  (value & 0x000000FF);
    }

    private int readInt(int offset) {
        if (offset < 0 || offset + 3 >= buffer.length) {
            return 0;
        }
        return  ((buffer[offset]   << 24) & 0xFF000000) |
                ((buffer[offset+1] << 16) & 0x00FF0000) |
                ((buffer[offset+2] << 8)  & 0x0000FF00) |
                ((buffer[offset+3])       & 0x000000FF);
    }

    public synchronized void reset() {
        packetCount = 0;
        writePosition = 0;
        readPosition = 0;
    }
}
