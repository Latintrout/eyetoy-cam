package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** The big round shutter / record button. */
final class ShutterView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean photo;
    private boolean recording;

    ShutterView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ps2Ui.WHITE);
        ring.setStrokeWidth(Ps2Ui.dp(c, 4));
        inner.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    void setState(boolean photoMode, boolean isRecording) {
        photo = photoMode;
        recording = isRecording;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - ring.getStrokeWidth();
        ring.setShadowLayer(Ps2Ui.dp(getContext(), 6), 0, 0, 0x806FB5FF);
        c.drawCircle(cx, cy, r, ring);
        inner.setColor(photo ? Ps2Ui.WHITE : Ps2Ui.RED);
        if (recording) {
            float h = r * 0.52f;
            c.drawRoundRect(new RectF(cx - h, cy - h, cx + h, cy + h), h * 0.3f, h * 0.3f, inner);
        } else {
            c.drawCircle(cx, cy, r * 0.76f, inner);
        }
    }
}
