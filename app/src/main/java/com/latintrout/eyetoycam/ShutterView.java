package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** The big round shutter / record button. */
final class ShutterView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint base = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dome = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean photo;
    private boolean recording;

    ShutterView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Ps2Ui.WHITE);
        ring.setStrokeWidth(Ps2Ui.dp(c, 4));
        inner.setStyle(Paint.Style.FILL);
        base.setStyle(Paint.Style.FILL);
        dome.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    void setState(boolean photoMode, boolean isRecording) {
        photo = photoMode;
        recording = isRecording;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float cx = w / 2f, cy = h / 2f, r = Math.min(cx, cy) * 0.78f;
        // a silver-lavender dome lit from the top-left
        int[] cols = Ps2Ui.theme.light
                ? new int[]{0xFFFAFAFF, 0xFFD4D7EE, 0xFF8E95C8}
                : new int[]{0xFFF2F4FF, 0xFFB8BEDF, 0xFF5C6596};
        dome.setShader(new RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.4f,
                cols, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        if (Ps2Ui.isPs2()) {
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
            return;
        }

        float d = Ps2Ui.dp(getContext(), 1);
        float outer = Math.min(cx, cy) - 4 * d;
        float r = Math.min(cx, cy) * 0.78f;
        boolean light = Ps2Ui.theme.light;

        // housing the dome sits in
        base.setColor(light ? 0xFF9C9FA5 : 0xFF071230);
        c.drawCircle(cx, cy, outer, base);
        ring.setStrokeWidth(light ? 2 * d : 3 * d);
        if (light) {
            ring.clearShadowLayer();
            ring.setColor(0xFF6C6F75);
            c.drawCircle(cx, cy, outer, ring);
            ring.setColor(0x99FFFFFF);
            ring.setStrokeWidth(1.5f * d);
            c.drawArc(cx - outer + 2 * d, cy - outer + 2 * d, cx + outer - 2 * d, cy + outer - 2 * d,
                    160f, 120f, false, ring);
        } else {
            ring.setColor(recording ? Ps2Ui.RED : 0xFF3D7BFF);
            ring.setShadowLayer(8 * d, 0, 0, recording ? Ps2Ui.RED : 0xFF3D7BFF);
            c.drawCircle(cx, cy, r + 3 * d, ring);
            ring.clearShadowLayer();
            ring.setColor(0x553D7BFF);
            ring.setStrokeWidth(1.5f * d);
            c.drawCircle(cx, cy, outer, ring);
        }

        c.drawCircle(cx, cy, r, dome);

        // red mark: dot = ready to record, square = recording
        inner.setColor(Ps2Ui.RED);
        if (recording) {
            float h = r * 0.34f;
            rect.set(cx - h, cy - h, cx + h, cy + h);
            c.drawRoundRect(rect, h * 0.25f, h * 0.25f, inner);
        } else if (!photo) {
            c.drawCircle(cx, cy, r * 0.24f, inner);
        }
    }
}
