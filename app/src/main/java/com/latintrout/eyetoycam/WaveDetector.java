package com.latintrout.eyetoycam;

import java.util.ArrayDeque;

/**
 * Decides whether someone is waving in the top-right corner of the picture.
 * Works on a small grid of brightness samples (no camera or Android code here).
 *
 * Needs about a second of steady movement in the zone; ignores whole-picture
 * brightness changes (the camera adjusting its exposure) and has a cooldown
 * after each trigger.
 */
final class WaveDetector {
    /** The zone, as a fraction of the displayed picture (top-right corner). */
    static final float ZX0 = 0.66f, ZX1 = 1.0f, ZY0 = 0.0f, ZY1 = 0.36f;
    static final int GX = 12, GY = 9;

    private static final int CHANGE = 28;             // brightness change that counts as "moved"
    private static final float MOTION_FRACTION = 0.14f;
    private static final long COMPARE_AGE_MS = 150;   // compare against a frame this old
    private static final float CHARGE_SECONDS = 1.2f;
    private static final float DECAY_SECONDS = 0.8f;
    private static final long COOLDOWN_MS = 4000;
    private static final int GLOBAL_JUMP = 10;

    private static final class Sample {
        final long t;
        final int[] z;
        final int global;
        Sample(long t, int[] z, int global) { this.t = t; this.z = z; this.global = global; }
    }

    private final ArrayDeque<Sample> history = new ArrayDeque<>();
    private long lastMs = -1;
    private long cooldownUntil = 0;
    private volatile float charge;

    float charge() { return charge; }

    void reset() {
        history.clear();
        lastMs = -1;
        charge = 0;
    }

    /**
     * @param zone   brightness samples (0..255) for the zone, row by row (GX*GY values)
     * @param global average brightness of the whole picture (0..255)
     * @return true when a wave has just been recognised
     */
    boolean update(int[] zone, int global, long nowMs) {
        float dt = lastMs < 0 ? 0f : Math.min(0.25f, (nowMs - lastMs) / 1000f);
        lastMs = nowMs;

        Sample ref = null;
        for (Sample s : history) {          // newest sample that is old enough
            if (nowMs - s.t >= COMPARE_AGE_MS) ref = s;
        }
        boolean motion = false;
        if (ref != null && Math.abs(global - ref.global) <= GLOBAL_JUMP) {
            int changed = 0;
            for (int i = 0; i < zone.length; i++) {
                if (Math.abs(zone[i] - ref.z[i]) > CHANGE) changed++;
            }
            motion = changed >= zone.length * MOTION_FRACTION;
        }
        history.addLast(new Sample(nowMs, zone.clone(), global));
        while (history.size() > 14 || (history.size() > 1 && nowMs - history.peekFirst().t > 600)) {
            history.removeFirst();
        }

        if (nowMs < cooldownUntil) {
            charge = 0f;
            return false;
        }
        float c = charge + (motion ? dt / CHARGE_SECONDS : -dt / DECAY_SECONDS);
        c = Math.max(0f, Math.min(1f, c));
        if (c >= 1f) {
            charge = 0f;
            cooldownUntil = nowMs + COOLDOWN_MS;
            history.clear();
            return true;
        }
        charge = c;
        return false;
    }
}
