package com.latintrout.eyetoycam;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;

/**
 * Picture adjustments done on the phone (never on the camera):
 * mirror, flip, zoom, a brightness boost and a retro effect.
 */
final class Look {
    volatile boolean mirror, flip;
    /** Digital zoom, 1.0 to 4.0, about the middle of the picture. */
    volatile float zoom = 1f;
    private volatile int brightness;          // 0..100
    /** One of the Effects constants. */
    volatile int effect = Effects.OFF;
    private ColorMatrixColorFilter cached;
    private int cachedFor = -1;

    int brightness() { return brightness; }
    void setBrightness(int b) { brightness = Math.max(0, Math.min(100, b)); }

    private float mul() { return 1f + brightness / 100f * 1.2f; }
    private float lift() { return brightness / 100f * 10f; }

    /** @return a colour filter for the current brightness and effect, or null when neither is set. */
    synchronized ColorMatrixColorFilter filter() {
        ColorMatrix fx = Effects.colorMatrix(effect);
        if (brightness <= 0 && fx == null) return null;
        int key = brightness * 16 + effect;
        if (cached == null || cachedFor != key) {
            float m = mul(), l = lift();
            ColorMatrix cm = new ColorMatrix(new float[]{
                    m, 0, 0, 0, l,
                    0, m, 0, 0, l,
                    0, 0, m, 0, l,
                    0, 0, 0, 1, 0});
            if (fx != null) cm.postConcat(fx);
            cached = new ColorMatrixColorFilter(cm);
            cachedFor = key;
        }
        return cached;
    }

    /** Returns a new bitmap with mirror/flip/zoom/brightness and the effect applied. */
    Bitmap apply(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(filter());
        int fx = effect;
        long t = System.currentTimeMillis();
        c.save();
        c.scale((mirror ? -1f : 1f) * zoom, (flip ? -1f : 1f) * zoom, w / 2f, h / 2f);
        Effects.drawPicture(c, src, new android.graphics.RectF(0, 0, w, h), p, fx, t);
        c.restore();
        Effects.drawOverlay(c, w, h, fx, t);
        return out;
    }
}
