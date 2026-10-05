package com.latintrout.eyetoycam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Picture adjustments done on the phone (never on the camera):
 * mirror, flip and a brightness boost.
 */
final class Look {
    volatile boolean mirror, flip;
    private volatile int brightness;          // 0..100
    private ColorMatrixColorFilter cached;
    private int cachedFor = -1;

    int brightness() { return brightness; }
    void setBrightness(int b) { brightness = Math.max(0, Math.min(100, b)); }

    private float mul() { return 1f + brightness / 100f * 1.2f; }
    private float lift() { return brightness / 100f * 10f; }

    /** @return a colour filter for the current brightness, or null when no boost is set. */
    synchronized ColorMatrixColorFilter filter() {
        if (brightness <= 0) return null;
        if (cached == null || cachedFor != brightness) {
            float m = mul(), l = lift();
            cached = new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    m, 0, 0, 0, l,
                    0, m, 0, 0, l,
                    0, 0, m, 0, l,
                    0, 0, 0, 1, 0}));
            cachedFor = brightness;
        }
        return cached;
    }

    /** Returns a new bitmap with mirror/flip/brightness applied. */
    Bitmap apply(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(filter());
        c.save();
        c.scale(mirror ? -1f : 1f, flip ? -1f : 1f, w / 2f, h / 2f);
        c.drawBitmap(src, 0, 0, p);
        c.restore();
        return out;
    }
}
