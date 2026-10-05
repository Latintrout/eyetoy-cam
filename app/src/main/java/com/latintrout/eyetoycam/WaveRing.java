package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The "wave here" ring in the top-right corner of the picture, filling up as you wave. */
final class WaveRing extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private boolean active;
    private float charge;

    WaveRing(Context c) {
        super(c);
        float d = Ps2Ui.dp(c, 1);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * d);
        ring.setColor(0x99FFFFFF);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(5 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(Ps2Ui.CYAN);
        label.setColor(0xCCFFFFFF);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(10 * d * 1.5f);
        label.setFakeBoldText(true);
        setClickable(false);
    }

    void setActive(boolean on) {
        active = on;
        if (!on) charge = 0f;
        invalidate();
    }

    void setCharge(float c) {
        charge = c;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!active) return;
        float w = getWidth(), h = getHeight();
        float cx = (WaveDetector.ZX0 + WaveDetector.ZX1) / 2f * w;
        float cy = (WaveDetector.ZY0 + WaveDetector.ZY1) / 2f * h;
        float r = Math.min((WaveDetector.ZX1 - WaveDetector.ZX0) * w, (WaveDetector.ZY1 - WaveDetector.ZY0) * h) / 2f * 0.62f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        c.drawOval(oval, ring);
        if (charge > 0.01f) c.drawArc(oval, -90f, 360f * charge, false, arc);
        c.drawText("WAVE", cx, cy + label.getTextSize() * 0.35f, label);
    }
}
