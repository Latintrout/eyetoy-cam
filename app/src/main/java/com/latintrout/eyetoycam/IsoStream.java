package com.latintrout.eyetoycam;

/** Thin wrapper around the native isochronous streaming helper (iso_stream.c). */
final class IsoStream {
    static {
        System.loadLibrary("isostream");
    }

    private IsoStream() { }

    /** @return 0 on success, negative Linux errno on failure */
    static native int nativeStart(int fd, int endpointAddress, int packetSize);
    static native void nativeStop();
    static native boolean nativeIsRunning();
    /** @return the newest complete JPEG frame, or null if none since last call */
    static native byte[] nativeGetFrame();
    /** urbs, packets, dataPackets, bytes, frames, dropped, packetErrors, reapErrors, submitErrors, lastErrno */
    static native long[] nativeStats();
    static native String nativeDebug();
}
