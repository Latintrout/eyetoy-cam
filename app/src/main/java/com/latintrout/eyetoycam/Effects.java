package com.latintrout.eyetoycam;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Retro picture effects, all done on the phone. The same drawing is used for the
 * live picture, photos and video, so what you see is what gets saved.
 *
 * Colour changes come from {@link #colorMatrix(int)} (merged into the picture's
 * colour filter); shape changes from {@link #drawPicture} and {@link #drawOverlay}.
 */
final class Effects {
    static final int OFF = 0, SCANLINES = 1, VHS = 2, PIXEL = 3, BW = 4, SEPIA = 5, PS2 = 6;
    static final String[] NAMES = {"OFF", "SCANLINES", "VHS", "PIXEL", "B&W", "SEPIA", "PS2"};

    private Effects() { }

    /** True when the effect changes more than colours (so the live picture must be redrawn). */
    static boolean reshapes(int e) {
        return e == SCANLINES || e == VHS || e == PIXEL;
    }

    /** The effect's colour change, or null for none. */
    static ColorMatrix colorMatrix(int e) {
        ColorMatrix m = new ColorMatrix();
        switch (e) {
            case BW: {
                m.setSaturation(0f);
                m.postConcat(contrast(1.2f));
                return m;
            }
            case SEPIA: {
                m.setSaturation(0f);
                m.postConcat(new ColorMatrix(new float[]{
                        1.07f, 0, 0, 0, 18,
                        0, 0.88f, 0, 0, 6,
                        0, 0, 0.66f, 0, -8,
                        0, 0, 0, 1, 0}));
                return m;
            }
            case PS2: {
                // punchy, slightly cool colours like an early-2000s console capture
                m.setSaturation(1.45f);
                m.postConcat(contrast(1.18f));
                m.postConcat(new ColorMatrix(new float[]{
                        0.96f, 0, 0, 0, 0,
                        0, 1.0f, 0, 0, 2,
                        0, 0, 1.1f, 0, 12,
                        0, 0, 0, 1, 0}));
                return m;
            }
            case VHS: {
                // washed out and warm
                m.setSaturation(0.75f);
                m.postConcat(contrast(0.9f));
                m.postConcat(new ColorMatrix(new float[]{
                        1.04f, 0, 0, 0, 10,
                        0, 1.0f, 0, 0, 4,
                        0, 0, 0.92f, 0, 0,
                        0, 0, 0, 1, 0}));
                return m;
            }
            default:
                return null;
        }
    }

    private static ColorMatrix contrast(float k) {
        float off = 128f * (1f - k);
        return new ColorMatrix(new float[]{
                k, 0, 0, 0, off,
                0, k, 0, 0, off,
                0, 0, k, 0, off,
                0, 0, 0, 1, 0});
    }

    // ------------------------------------------------------------------ drawing

    private static final ColorMatrixColorFilter RED_ONLY = new ColorMatrixColorFilter(new float[]{
            1, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 0, 0.35f, 0});
    private static final ColorMatrixColorFilter BLUE_ONLY = new ColorMatrixColorFilter(new float[]{
            0, 0, 0, 0, 0,
            0, 0, 0, 0, 0,
            0, 0, 1, 0, 0,
            0, 0, 0, 0.35f, 0});

    /**
     * Draws src into dst with the effect's shape changes. p carries the colour filter;
     * the canvas may already be mirrored or zoomed.
     */
    static void drawPicture(Canvas c, Bitmap src, RectF dst, Paint p, int e, long t) {
        if (e == PIXEL) {
            int sw = Math.max(8, src.getWidth() / 8), sh = Math.max(6, src.getHeight() / 8);
            Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
            Paint q = new Paint(p);
            q.setFilterBitmap(false);
            q.setAntiAlias(false);
            // not recycled here: a hardware canvas (video) draws it later
            c.drawBitmap(small, null, dst, q);
            return;
        }
        if (e == VHS) {
            drawVhs(c, src, dst, p, t);
            return;
        }
        c.drawBitmap(src, null, dst, p);
    }

    private static void drawVhs(Canvas c, Bitmap src, RectF dst, Paint p, long t) {
        int bands = 24;
        int sh = src.getHeight(), sw = src.getWidth();
        float dh = dst.height() / bands, scale = dst.width() / sw;
        // the "tracking" glitch rolls down the picture every few seconds
        float trackY = (t % 4000) / 4000f;
        Rect s = new Rect();
        RectF d = new RectF();
        for (int i = 0; i < bands; i++) {
            float pos = (i + 0.5f) / bands;
            float wobble = (float) (Math.sin(t / 170.0 + i * 0.8) * 1.6 + Math.sin(t / 53.0 + i * 2.3) * 0.7);
            float near = Math.abs(pos - trackY);
            if (near < 0.06f) wobble += (0.06f - near) / 0.06f * 9f;
            s.set(0, i * sh / bands, sw, (i + 1) * sh / bands);
            float dx = wobble * scale;
            d.set(dst.left + dx, dst.top + i * dh, dst.right + dx, dst.top + (i + 1) * dh + 1);
            c.drawBitmap(src, s, d, p);
        }
        // colour bleed: red and blue ghosts shifted sideways
        Paint g = new Paint(Paint.FILTER_BITMAP_FLAG);
        g.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SCREEN));
        float shift = 3f * scale;
        g.setColorFilter(RED_ONLY);
        c.drawBitmap(src, null, new RectF(dst.left + shift, dst.top, dst.right + shift, dst.bottom), g);
        g.setColorFilter(BLUE_ONLY);
        c.drawBitmap(src, null, new RectF(dst.left - shift, dst.top, dst.right - shift, dst.bottom), g);
    }

    private static BitmapShader lineShader;
    private static int lineShaderH;
    private static final Random RNG = new Random();

    /** Things drawn on top of the whole picture (never mirrored): scanlines and VHS noise. */
    static void drawOverlay(Canvas c, float w, float h, int e, long t) {
        if (e == SCANLINES) {
            int period = Math.max(2, Math.round(h / 240f));
            BitmapShader sh = lineShader(period);
            Paint p = new Paint();
            p.setShader(sh);
            c.drawRect(0, 0, w, h, p);
            // soft dark corners, like a curved TV
            Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
            v.setShader(new android.graphics.RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.72f,
                    new int[]{0x00000000, 0x00000000, 0x88000000}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, v);
        } else if (e == VHS) {
            float trackY = (t % 4000) / 4000f * h;
            Paint n = new Paint();
            n.setColor(0x55FFFFFF);
            float bar = h * 0.05f;
            for (int i = 0; i < 70; i++) {
                float x = RNG.nextFloat() * w, y = trackY - bar / 2f + RNG.nextFloat() * bar;
                c.drawRect(x, y, x + 2f + RNG.nextFloat() * w * 0.03f, y + Math.max(1f, h / 480f), n);
            }
            n.setColor(0x14FFFFFF);
            c.drawRect(0, trackY - bar / 2f, w, trackY + bar / 2f, n);
        }
    }

    private static synchronized BitmapShader lineShader(int period) {
        if (lineShader == null || lineShaderH != period) {
            Bitmap b = Bitmap.createBitmap(1, period, Bitmap.Config.ARGB_8888);
            b.eraseColor(0x00000000);
            b.setPixel(0, period - 1, 0x66000000);
            lineShader = new BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            lineShaderH = period;
        }
        return lineShader;
    }

    /**
     * For the live picture: a new bitmap with the shape changes and overlay drawn in
     * (colours are left to the picture's colour filter). Returns src when nothing changes.
     */
    static Bitmap render(Bitmap src, int e, long t) {
        if (!reshapes(e)) return src;
        int w = src.getWidth(), h = src.getHeight();
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(0xFF000000);
        drawPicture(c, src, new RectF(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG), e, t);
        drawOverlay(c, w, h, e, t);
        return out;
    }
}
