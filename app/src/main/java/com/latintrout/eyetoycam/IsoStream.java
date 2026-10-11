package com.latintrout.eyetoycam;

/**
 * Thin wrapper around the native isochronous streaming helper (iso_stream.c).
 * Each camera streams in its own slot (0 to MAX_SLOTS - 1).
 */
final class IsoStream {
    static final int MAX_SLOTS = 4;

    static {
        System.loadLibrary("isostream");
    }

    private IsoStream() { }

    /** @return 0 on success, negative Linux errno on failure (-28 = not enough USB bandwidth) */
    static native int nativeStart(int slot, int fd, int endpointAddress, int packetSize);
    static native void nativeStop(int slot);
    static native boolean nativeIsRunning(int slot);
    /** @return the newest complete JPEG frame, or null if none since last call */
    static native byte[] nativeGetFrame(int slot);
    /** urbs, packets, dataPackets, bytes, frames, dropped, packetErrors, reapErrors, submitErrors, lastErrno */
    static native long[] nativeStats(int slot);
    static native String nativeDebug(int slot);
}
