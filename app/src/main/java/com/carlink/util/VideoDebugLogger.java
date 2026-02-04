package com.carlink.util;

import android.util.Log;

/**
 * Video pipeline debug logging. Disabled by default.
 * Call setDebugEnabled(true) or setDebugEnabled(BuildConfig.DEBUG) on startup to enable.
 */
public class VideoDebugLogger {
    private static final String TAG = "CARLINK";
    private static volatile boolean debugEnabled = false;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    // Codec lifecycle
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
        // Always log errors
        Log.e(TAG, "[VIDEO_CODEC] ERROR: " + error +
                " (recoverable=" + isRecoverable + ", transient=" + isTransient + ")");
    }

    public static void logCodecFormatChanged(int width, int height, int colorFormat) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_CODEC] Format changed: " + width + "x" + height +
                ", color=" + colorFormat);
    }

    public static void logCodecInputAvailable(int bufferIndex, int queuedBuffers) {
        if (!debugEnabled) return;
        Log.v(TAG, "[VIDEO_CODEC] Input available: buffer=" + bufferIndex +
                ", queued=" + queuedBuffers);
    }

    // Surface
    public static void logSurfaceBound(boolean valid) {
        if (!debugEnabled) return;
        Log.i(TAG, "[VIDEO_SURFACE] Bound to codec: valid=" + valid);
    }
}
