package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** A small retro LED-style microphone level meter: green, then yellow, then red, with a peak marker. */
final class LevelMeter extends View {
    private static final int SEGMENTS = 12;
    private final Paint on = new Paint(), off = new Paint();
    private float level, peak;
    private long peakAt;

    LevelMeter(Context c) {
        super(c);
        off.setColor(0x33FFFFFF);
    }

    /** 0 to 1; falls back gently so the bars don't flicker. */
    void setLevel(float l) {
        long now = System.currentTimeMillis();
        level = l > level ? l : Math.max(l, level - 0.06f);
        if (level >= peak || now - peakAt > 1200) {
            peak = level;
            peakAt = now;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = Math.max(1f, w / 60f);
        float sw = (w - gap * (SEGMENTS - 1)) / SEGMENTS;
        int lit = Math.round(level * SEGMENTS);
        int pk = Math.min(SEGMENTS - 1, Math.round(peak * SEGMENTS) - 1);
        for (int i = 0; i < SEGMENTS; i++) {
            float x = i * (sw + gap);
            boolean isOn = i < lit || i == pk;
            on.setColor(i >= SEGMENTS - 2 ? 0xFFFF4A4A : i >= SEGMENTS - 5 ? 0xFFFFD23F : 0xFF45E07A);
            c.drawRect(x, 0, x + sw, h, isOn ? on : off);
        }
    }
}
