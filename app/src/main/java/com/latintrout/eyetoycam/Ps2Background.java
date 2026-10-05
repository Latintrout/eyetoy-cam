package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Dark-blue backdrop with softly rising glowing columns and drifting specks,
 * in the spirit of the PS2 menu (original artwork, no Sony assets).
 */
final class Ps2Background extends View {
    private static final int TOWERS = 15;
    private static final int ORBS = 22;

    private final Paint bgPaint = new Paint();
    private final Paint towerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint capPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignette = new Paint();
    private final RectF rect = new RectF();

    private final float[] tx = new float[TOWERS], tw = new float[TOWERS], speed = new float[TOWERS],
            phase = new float[TOWERS], tall = new float[TOWERS];
    private final int[] talpha = new int[TOWERS];
    private final float[] ox = new float[ORBS], oy = new float[ORBS], ov = new float[ORBS], orad = new float[ORBS];

    private Bitmap towerBmp;
    private boolean animated = true;
    private final long t0 = System.nanoTime();
    private float lastT;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            invalidate();
            if (animated && isAttachedToWindow()) postDelayed(this, 40);
        }
    };

    Ps2Background(Context c) {
        super(c);
        Random r = new Random(7);
        for (int i = 0; i < TOWERS; i++) {
            tx[i] = (i + r.nextFloat() * 0.6f) / TOWERS;
            tw[i] = 0.018f + r.nextFloat() * 0.035f;
            speed[i] = 0.25f + r.nextFloat() * 0.5f;
            phase[i] = r.nextFloat() * 6.28f;
            tall[i] = 0.35f + r.nextFloat() * 0.65f;
            talpha[i] = 70 + r.nextInt(110);
        }
        for (int i = 0; i < ORBS; i++) {
            ox[i] = r.nextFloat();
            oy[i] = r.nextFloat();
            ov[i] = 0.01f + r.nextFloat() * 0.03f;
            orad[i] = 0.8f + r.nextFloat() * 2.2f;
        }
        capPaint.setColor(0xFFBFE6FF);
        orbPaint.setColor(0xFF9FD0FF);
    }

    /** Pausing the animation saves battery/CPU, e.g. while recording. */
    void setAnimated(boolean on) {
        if (animated == on) return;
        animated = on;
        removeCallbacks(tick);
        if (on && isAttachedToWindow()) post(tick);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (animated) post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        bgPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF01030C, 0xFF040B2B, 0xFF0A1F5E},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        vignette.setShader(new RadialGradient(w / 2f, h * 0.55f, Math.max(w, h) * 0.75f,
                new int[]{Color.TRANSPARENT, Color.TRANSPARENT, 0xCC000208},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        if (towerBmp == null) {
            // a 1px-wide column that is bright at the top and fades towards the bottom
            towerBmp = Bitmap.createBitmap(1, 64, Bitmap.Config.ARGB_8888);
            for (int y = 0; y < 64; y++) {
                float f = y / 63f;
                int a = (int) (220 * (1f - f) + 30 * f);
                int rr = (int) (120 * (1f - f) + 20 * f);
                int gg = (int) (190 * (1f - f) + 70 * f);
                towerBmp.setPixel(0, y, Color.argb(a, rr, gg, 255));
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (animated) lastT = (System.nanoTime() - t0) / 1_000_000_000f;
        float t = lastT;

        c.drawRect(0, 0, w, h, bgPaint);

        for (int i = 0; i < TOWERS; i++) {
            float hh = h * 0.6f * tall[i] * (0.62f + 0.38f * (float) Math.sin(t * speed[i] + phase[i]));
            float x = tx[i] * w;
            float tww = Math.max(6f, tw[i] * w);
            rect.set(x, h - hh, x + tww, h);
            towerPaint.setAlpha(talpha[i]);
            c.drawBitmap(towerBmp, null, rect, towerPaint);
            capPaint.setAlpha(Math.min(255, talpha[i] + 60));
            c.drawRect(x, h - hh, x + tww, h - hh + 3f, capPaint);
        }

        for (int i = 0; i < ORBS; i++) {
            float y = ((oy[i] - t * ov[i]) % 1f + 1f) % 1f;
            float x = ox[i] + 0.02f * (float) Math.sin(t * 0.4f + i);
            orbPaint.setAlpha(60 + (int) (50 * Math.sin(t + i)));
            c.drawCircle(x * w, y * h, orad[i] * getResources().getDisplayMetrics().density, orbPaint);
        }

        c.drawRect(0, 0, w, h, vignette);
    }
}
