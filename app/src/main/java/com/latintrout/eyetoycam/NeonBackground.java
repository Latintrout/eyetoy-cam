package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Dark navy with slowly flowing glowing light lines at the top and bottom. */
final class NeonBackground extends View implements Backdrop {
    private static final int SEGMENTS = 36;
    // per wave: vertical position (0..1 of height), amplitude, wavelength, speed, phase, alpha, width
    private static final float[][] WAVES = {
            {0.06f, 0.025f, 1.3f, 0.35f, 0.0f, 200, 2.0f},
            {0.09f, 0.035f, 0.9f, -0.25f, 1.7f, 120, 1.2f},
            {0.13f, 0.02f, 1.7f, 0.2f, 3.1f, 70, 1.0f},
            {0.88f, 0.03f, 1.1f, -0.3f, 0.6f, 190, 2.0f},
            {0.92f, 0.025f, 0.8f, 0.22f, 2.4f, 110, 1.2f},
            {0.96f, 0.035f, 1.5f, -0.18f, 4.0f, 70, 1.0f},
    };

    private final Paint bgPaint = new Paint();
    private final Paint glowPaint = new Paint();
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
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

    NeonBackground(Context c) {
        super(c);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(0xFF4C8DFF);
    }

    @Override
    public void setAnimated(boolean on) {
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
                new int[]{Ps2Ui.theme.bgTop, Ps2Ui.theme.bgBottom, Ps2Ui.theme.bgTop},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        glowPaint.setShader(new RadialGradient(w / 2f, h * 0.45f, Math.max(w, h) * 0.6f,
                new int[]{0x302F6BFF, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (animated) lastT = (System.nanoTime() - t0) / 1_000_000_000f;
        float t = lastT;
        float d = getResources().getDisplayMetrics().density;

        c.drawRect(0, 0, w, h, bgPaint);
        c.drawRect(0, 0, w, h, glowPaint);

        for (float[] wv : WAVES) {
            path.reset();
            for (int i = 0; i <= SEGMENTS; i++) {
                float fx = i / (float) SEGMENTS;
                float y = (wv[0] + wv[1] * (float) Math.sin(fx * 6.283f / wv[2] + t * wv[3] * 6.283f + wv[4])) * h;
                if (i == 0) path.moveTo(0, y); else path.lineTo(fx * w, y);
            }
            linePaint.setAlpha((int) wv[5]);
            linePaint.setStrokeWidth(wv[6] * d);
            linePaint.setShadowLayer(6 * d, 0, 0, 0xFF2F6BFF);
            c.drawPath(path, linePaint);
        }
    }
}
