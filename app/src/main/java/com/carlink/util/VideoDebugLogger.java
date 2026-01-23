package com.carlink.util;

import android.util.Log;
import java.util.Locale;

/**
 * Video pipeline debug logging. Stages: USB → RING → CODEC → SURFACE → PERF.
 * Disabled by default. Call setDebugEnabled(true) or setDebugEnabled(BuildConfig.DEBUG)
 * on startup to enable debug logging. No-op when disabled (release builds).
 */
public class VideoDebugLogger {
    private static final String TAG = "CARLINK";

    private static volatile boolean debugEnabled = false;  // Disabled by default for release builds
    private static volatile boolean usbEnabled = true;
    private static volatile boolean ringBufferEnabled = true;
    private static volatile boolean codecEnabled = true;
    private static volatile boolean surfaceEnabled = true;
    private static volatile boolean perfEnabled = true;

    private static long lastUsbLogTime = 0;
    private static long lastRingLogTime = 0;
    private static long lastCodecLogTime = 0;
    private static final long THROTTLE_INTERVAL_MS = 100;

    private static long usbFrameCount = 0;
    private static long ringWriteCount = 0;
    private static long ringReadCount = 0;
    private static long codecInputCount = 0;
    private static long codecOutputCount = 0;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
        if (!enabled) {
            // Reset all counters when disabled
            usbFrameCount = 0;
            ringWriteCount = 0;
            ringReadCount = 0;
            codecInputCount = 0;
            codecOutputCount = 0;
        }
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setStageEnabled(String stage, boolean enabled) {
        switch (stage.toUpperCase(Locale.ROOT)) {
            case "USB":
                usbEnabled = enabled;
                break;
            case "RING":
                ringBufferEnabled = enabled;
                break;
            case "CODEC":
                codecEnabled = enabled;
                break;
            case "SURFACE":
                surfaceEnabled = enabled;
                break;
            case "PERF":
                perfEnabled = enabled;
                break;
        }
    }

    // USB Layer
    public static void logUsbFrameReceived(int payloadLength, int headerType) {
        if (!debugEnabled || !usbEnabled) return;
        usbFrameCount++;

        long now = System.currentTimeMillis();
        if (now - lastUsbLogTime >= THROTTLE_INTERVAL_MS) {
            lastUsbLogTime = now;
            Log.d(TAG, "[VIDEO_USB] Frame #" + usbFrameCount +
                    " received: payload=" + payloadLength + "B, type=0x" +
                    Integer.toHexString(headerType));
        }
    }

    public static void logUsbFrameWithResolution(int payloadLength, int width, int height) {
        if (!debugEnabled || !usbEnabled) return;
        usbFrameCount++;

        long now = System.currentTimeMillis();
        if (now - lastUsbLogTime >= THROTTLE_INTERVAL_MS) {
            lastUsbLogTime = now;
            Log.d(TAG, "[VIDEO_USB] Frame #" + usbFrameCount +
                    ": " + width + "x" + height + ", payload=" + payloadLength + "B");
        }
    }

    public static void logUsbError(String error) {
        if (!debugEnabled) return;
        Log.e(TAG, "[VIDEO_USB] ERROR: " + error);
    }

    public static void logUsbDirectProcessStart(int payloadLength) {
        if (!debugEnabled || !usbEnabled) return;
        Log.v(TAG, "[VIDEO_USB] Direct process start: " + payloadLength + "B");
    }

    // Ring Buffer
    public static void logRingWrite(int length, int skipBytes, int packetCount) {
        if (!debugEnabled || !ringBufferEnabled) return;
        ringWriteCount++;

        long now = System.currentTimeMillis();
        if (now - lastRingLogTime >= THROTTLE_INTERVAL_MS) {
            lastRingLogTime = now;
            Log.d(TAG, "[VIDEO_RING] Write #" + ringWriteCount +
                    ": len=" + length + ", skip=" + skipBytes +
                    ", queued=" + packetCount);
        }
    }

    public static void logRingRead(int length, int actualLength, int remainingPackets) {
        if (!debugEnabled || !ringBufferEnabled) return;
        ringReadCount++;

        long now = System.currentTimeMillis();
        if (now - lastRingLogTime >= THROTTLE_INTERVAL_MS) {
            lastRingLogTime = now;
            Log.d(TAG, "[VIDEO_RING] Read #" + ringReadCount +
                    ": raw=" + length + ", actual=" + actualLength +
                    ", remaining=" + remainingPackets);
        }
    }

    public static void logRingResize(int oldSize, int newSize, int readPos, int writePos) {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_RING] RESIZE: " + (oldSize / 1024 / 1024) + "MB -> " +
                (newSize / 1024 / 1024) + "MB, read=" + readPos + ", write=" + writePos);
    }

    public static void logRingEmergencyReset(int oldSize) {
        Log.e(TAG, "[VIDEO_RING] EMERGENCY RESET: was " + (oldSize / 1024 / 1024) +
                "MB, resetting to 1MB");
    }

    public static void logRingBoundsError(String operation, int offset, int length, int bufferSize) {
        Log.e(TAG, "[VIDEO_RING] BOUNDS ERROR in " + operation +
                ": offset=" + offset + ", length=" + length + ", bufferSize=" + bufferSize);
    }

    // MediaCodec - NAL unit tracking
    private static long idrFrameCount = 0;
    private static long pFrameCount = 0;
    private static long spsCount = 0;
    private static long ppsCount = 0;
    private static long lastIdrTime = 0;

    public static void logCodecInputQueued(int bufferIndex, int dataSize) {
        if (!debugEnabled || !codecEnabled) return;
        codecInputCount++;

        long now = System.currentTimeMillis();
        if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
            lastCodecLogTime = now;
            Log.d(TAG, "[VIDEO_CODEC] Input #" + codecInputCount +
                    ": buffer=" + bufferIndex + ", size=" + dataSize + "B");
        }
    }

    /** Log NAL unit type. IDR/SPS/PPS always logged; P-frames throttled. */
    public static void logNalUnitType(int nalType, int dataSize) {
        if (!debugEnabled) return;

        long now = System.currentTimeMillis();

        switch (nalType) {
            case 5: // IDR frame - ALWAYS log (critical for diagnostics)
                idrFrameCount++;
                long timeSinceLastIdr = (lastIdrTime > 0) ? (now - lastIdrTime) : 0;
                lastIdrTime = now;
                Log.i(TAG, "[VIDEO_NAL] *** IDR KEYFRAME #" + idrFrameCount +
                        " received *** size=" + dataSize + "B" +
                        (timeSinceLastIdr > 0 ? ", interval=" + timeSinceLastIdr + "ms" : " (first)"));
                break;

            case 7: // SPS - always log
                spsCount++;
                Log.i(TAG, "[VIDEO_NAL] SPS #" + spsCount + " received, size=" + dataSize + "B");
                break;

            case 8: // PPS - always log
                ppsCount++;
                Log.i(TAG, "[VIDEO_NAL] PPS #" + ppsCount + " received, size=" + dataSize + "B");
                break;

            case 1: // P-frame - throttled (very high frequency)
                pFrameCount++;
                // Log every 100th P-frame or every second
                if (pFrameCount % 100 == 0) {
                    long timeSinceIdr = (lastIdrTime > 0) ? (now - lastIdrTime) : -1;
                    Log.d(TAG, "[VIDEO_NAL] P-frame #" + pFrameCount +
                            ", size=" + dataSize + "B" +
                            ", IDR count=" + idrFrameCount +
                            (timeSinceIdr >= 0 ? ", time since IDR=" + timeSinceIdr + "ms" : ""));
                }
                break;

            case 6: // SEI - throttled
                if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
                    Log.v(TAG, "[VIDEO_NAL] SEI received, size=" + dataSize + "B");
                }
                break;

            default:
                // Other NAL types - throttled
                if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
                    Log.d(TAG, "[VIDEO_NAL] Type " + nalType + " received, size=" + dataSize + "B");
                }
                break;
        }
    }

    public static String getNalStatsSummary() {
        long timeSinceIdr = (lastIdrTime > 0) ? (System.currentTimeMillis() - lastIdrTime) : -1;
        return String.format(Locale.US, "IDR:%d, P:%d, SPS:%d, PPS:%d, lastIDR=%dms ago",
                idrFrameCount, pFrameCount, spsCount, ppsCount, timeSinceIdr);
    }

    public static void resetNalCounters() {
        idrFrameCount = 0;
        pFrameCount = 0;
        spsCount = 0;
        ppsCount = 0;
        lastIdrTime = 0;
    }

    public static void logCodecOutputReleased(int bufferIndex, int size, boolean rendered) {
        if (!debugEnabled || !codecEnabled) return;
        codecOutputCount++;

        long now = System.currentTimeMillis();
        if (now - lastCodecLogTime >= THROTTLE_INTERVAL_MS) {
            lastCodecLogTime = now;
            Log.d(TAG, "[VIDEO_CODEC] Output #" + codecOutputCount +
                    ": buffer=" + bufferIndex + ", size=" + size +
                    ", rendered=" + rendered);
        }
    }

    public static void logCodecInit(String codecName, int width, int height) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Init: " + codecName + " @ " + width + "x" + height);
    }

    public static void logCodecConfigured(String format) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Configured: " + format);
    }

    public static void logCodecStarted() {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Started");
    }

    public static void logCodecStopped() {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Stopped");
    }

    public static void logCodecReset(long resetCount) {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_CODEC] Reset #" + resetCount);
    }

    public static void logCodecError(String error, boolean isRecoverable, boolean isTransient) {
        Log.e(TAG, "[VIDEO_CODEC] ERROR: " + error +
                " (recoverable=" + isRecoverable + ", transient=" + isTransient + ")");
    }

    public static void logCodecFormatChanged(int width, int height, int colorFormat) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Format changed: " + width + "x" + height +
                ", color=" + colorFormat);
    }

    public static void logCodecInputAvailable(int bufferIndex, int queuedBuffers) {
        if (!debugEnabled || !codecEnabled) return;
        Log.v(TAG, "[VIDEO_CODEC] Input available: buffer=" + bufferIndex +
                ", queued=" + queuedBuffers);
    }

    // Surface
    public static void logSurfaceCreated(int width, int height) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Created: " + width + "x" + height);
    }

    public static void logSurfaceChanged(int width, int height, int format) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Changed: " + width + "x" + height + ", format=" + format);
    }

    public static void logSurfaceDestroyed() {
        if (!debugEnabled) return;
        Log.w(TAG, "[VIDEO_SURFACE] Destroyed");
    }

    public static void logSurfaceBound(boolean valid) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Bound to codec: valid=" + valid);
    }

    // Performance
    public static void logPerformanceStats(double fps, long framesReceived, long framesDecoded,
                                           long framesDropped, double dropRate, double throughputMbps) {
        if (!debugEnabled || !perfEnabled) return;
        Log.i(TAG, String.format("[VIDEO_PERF] FPS: %.1f, Frames: R:%d/D:%d/Drop:%d, " +
                        "DropRate: %.1f%%, Throughput: %.1fMbps",
                fps, framesReceived, framesDecoded, framesDropped, dropRate, throughputMbps));
    }

    public static void logBufferPoolStatus(int smallFree, int mediumFree, int largeFree, int total) {
        if (!debugEnabled || !perfEnabled) return;
        int totalFree = smallFree + mediumFree + largeFree;
        int utilization = ((total - totalFree) * 100) / total;
        Log.i(TAG, String.format("[VIDEO_PERF] Pool: %d/%d used (%d%%), small=%d, med=%d, large=%d",
                total - totalFree, total, utilization, smallFree, mediumFree, largeFree));
    }

    public static void logRingBufferHealth(int packetCount, int bufferSize, int readPos, int writePos) {
        if (!debugEnabled || !perfEnabled) return;
        int usedBytes = (writePos >= readPos) ? (writePos - readPos) : (bufferSize - readPos + writePos);
        int utilization = (usedBytes * 100) / bufferSize;
        Log.i(TAG, String.format("[VIDEO_PERF] Ring: %d packets, %d/%dKB (%d%%), R:%d W:%d",
                packetCount, usedBytes / 1024, bufferSize / 1024, utilization, readPos, writePos));
    }

    public static void logFrameTiming(long usbReceiveTime, long ringWriteTime,
                                      long codecQueueTime, long renderTime) {
        if (!debugEnabled || !perfEnabled) return;
        long totalLatency = renderTime - usbReceiveTime;
        Log.d(TAG, String.format("[VIDEO_PERF] Latency: total=%dms (USB->Ring=%dms, Ring->Codec=%dms, Codec->Render=%dms)",
                totalLatency,
                ringWriteTime - usbReceiveTime,
                codecQueueTime - ringWriteTime,
                renderTime - codecQueueTime));
    }

    public static String getFrameCountSummary() {
        return String.format(Locale.US, "USB:%d, RingW:%d, RingR:%d, CodecIn:%d, CodecOut:%d",
                usbFrameCount, ringWriteCount, ringReadCount, codecInputCount, codecOutputCount);
    }

    public static void resetCounters() {
        usbFrameCount = 0;
        ringWriteCount = 0;
        ringReadCount = 0;
        codecInputCount = 0;
        codecOutputCount = 0;
        // Also reset NAL counters
        resetNalCounters();
        // Reset stall detection
        lastInputQueueTime = 0;
        lastOutputTime = 0;
        stallWarningCount = 0;
    }

    // ==================== External Issue Detection ====================
    // These diagnostics help identify OS/driver issues vs app code issues

    private static long lastInputQueueTime = 0;
    private static long lastOutputTime = 0;
    private static long stallWarningCount = 0;
    private static final long STALL_THRESHOLD_MS = 500; // No output for 500ms = potential stall

    /**
     * Log SPS NAL unit header bytes for integrity verification.
     * Valid SPS starts with: 0x00 0x00 0x00 0x01 0x67 (or 0x00 0x00 0x01 0x67)
     * If first bytes don't match this pattern, data may be corrupted.
     *
     * Note: Corruption detection (ERROR) always logs; valid integrity (INFO) requires debugEnabled.
     */
    public static void logSpsPpsIntegrity(byte[] sps, byte[] pps) {
        if (sps == null || pps == null) return;
        if (!debugEnabled) {
            // Even if debug is disabled, check for corruption and log errors
            boolean validSps = isValidSpsHeader(sps);
            if (!validSps) {
                Log.e(TAG, "[VIDEO_DIAG] *** INVALID SPS HEADER - POSSIBLE DATA CORRUPTION ***");
            }
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[VIDEO_DIAG] SPS/PPS integrity check - SPS[");
        sb.append(sps.length).append("]: ");
        for (int i = 0; i < Math.min(8, sps.length); i++) {
            sb.append(String.format("%02X ", sps[i] & 0xFF));
        }
        sb.append("| PPS[").append(pps.length).append("]: ");
        for (int i = 0; i < Math.min(8, pps.length); i++) {
            sb.append(String.format("%02X ", pps[i] & 0xFF));
        }

        boolean validSps = isValidSpsHeader(sps);

        if (!validSps) {
            sb.append(" *** INVALID SPS HEADER - POSSIBLE CORRUPTION ***");
            Log.e(TAG, sb.toString());
        } else {
            Log.i(TAG, sb.toString());
        }
    }

    /** Helper to validate SPS header format. */
    private static boolean isValidSpsHeader(byte[] sps) {
        if (sps == null || sps.length < 5) return false;
        // Check for 4-byte start code: 00 00 00 01 67
        if (sps[0] == 0 && sps[1] == 0 && sps[2] == 0 && sps[3] == 1 && (sps[4] & 0x1F) == 7) {
            return true;
        }
        // Check for 3-byte start code: 00 00 01 67
        if (sps[0] == 0 && sps[1] == 0 && sps[2] == 1 && (sps[3] & 0x1F) == 7) {
            return true;
        }
        return false;
    }

    /**
     * Track decoder input/output timing to detect stalls.
     * Call when input is queued to codec.
     *
     * Note: Timing is always tracked; warnings only log when debugEnabled OR stall detected.
     */
    public static void logCodecInputTiming() {
        lastInputQueueTime = System.currentTimeMillis();

        // Check for potential stall (input queued but no output for too long)
        // Always log stalls even if debug disabled - they indicate serious issues
        if (lastOutputTime > 0 && lastInputQueueTime - lastOutputTime > STALL_THRESHOLD_MS) {
            stallWarningCount++;
            Log.w(TAG, "[VIDEO_DIAG] Decoder stall detected: " +
                    (lastInputQueueTime - lastOutputTime) + "ms since last output " +
                    "(stall count: " + stallWarningCount + ") - possible driver issue");
        }
    }

    /**
     * Track decoder output timing.
     * Call when output buffer is received.
     *
     * Note: Timing is always tracked; high latency warnings only log when debugEnabled.
     */
    public static void logCodecOutputTiming() {
        long now = System.currentTimeMillis();
        long inputToOutputMs = (lastInputQueueTime > 0) ? (now - lastInputQueueTime) : -1;
        lastOutputTime = now;

        // Log unusually high decode latency (potential driver issue)
        // Only log if debug enabled to avoid spam in production
        if (debugEnabled && inputToOutputMs > 100) {  // > 100ms is unusual for hardware decoder
            Log.w(TAG, "[VIDEO_DIAG] High decode latency: " + inputToOutputMs +
                    "ms - possible driver/thermal throttling");
        }
    }

    /**
     * Log when decoder is being recreated (Intel VPU workaround).
     * This helps correlate corruption with reset events.
     *
     * Note: Always logs (WARN level) - important for diagnosing Intel-specific issues.
     */
    public static void logIntelVpuWorkaround(String reason) {
        Log.w(TAG, "[VIDEO_DIAG] Intel VPU workaround triggered: " + reason +
                " | NAL stats: " + getNalStatsSummary());
    }

    /**
     * Log output buffer anomalies (zero-size, invalid flags, etc.)
     *
     * Note: Only logs when debugEnabled - anomalies can occur during normal operation.
     */
    public static void logOutputBufferAnomaly(int size, int flags, long presentationTimeUs) {
        if (!debugEnabled) return;

        if (size == 0 && (flags & 4) == 0) {  // 4 = BUFFER_FLAG_END_OF_STREAM
            Log.w(TAG, "[VIDEO_DIAG] Zero-size output buffer (not EOS) - flags=" + flags +
                    ", pts=" + presentationTimeUs + " - possible driver issue");
        }
        if (presentationTimeUs < 0) {
            Log.w(TAG, "[VIDEO_DIAG] Negative PTS in output: " + presentationTimeUs +
                    " - possible driver issue");
        }
    }

    /**
     * Get diagnostic summary for external issue detection.
     */
    public static String getDiagnosticSummary() {
        long timeSinceOutput = (lastOutputTime > 0) ?
                (System.currentTimeMillis() - lastOutputTime) : -1;
        return String.format(Locale.US,
                "StallCount:%d, LastOutput:%dms ago, %s",
                stallWarningCount, timeSinceOutput, getNalStatsSummary());
    }
}
