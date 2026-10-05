package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Simple line icons drawn in code (our own designs). */
final class IconView extends View {
    static final int CAMERA = 0, GEAR = 1, LOG = 2, MIRROR = 3, FLIP = 4, VIDEO = 5, TIMELAPSE = 6, SAVE = 7;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int type;
    private int color = Ps2Ui.WHITE;

    IconView(Context c, int type) {
        super(c);
        this.type = type;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    void setType(int t) { type = t; invalidate(); }
    void setColor(int c) { color = c; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight());
        c.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
        c.scale(s / 100f, s / 100f);
        stroke.setStrokeWidth(6f);
        stroke.setColor(color);
        fill.setColor(color);
        switch (type) {
            case CAMERA:
                c.drawRoundRect(10, 30, 90, 80, 10, 10, stroke);
                c.drawCircle(50, 55, 15, stroke);
                c.drawLine(36, 21, 64, 21, stroke);
                break;
            case GEAR:
                c.drawCircle(50, 50, 26, stroke);
                c.drawCircle(50, 50, 9, stroke);
                for (int k = 0; k < 8; k++) {
                    c.save();
                    c.rotate(k * 45f, 50, 50);
                    c.drawRoundRect(44, 8, 56, 22, 3, 3, fill);
                    c.restore();
                }
                break;
            case LOG:
                c.drawRoundRect(22, 10, 78, 90, 8, 8, stroke);
                c.drawLine(34, 34, 66, 34, stroke);
                c.drawLine(34, 50, 66, 50, stroke);
                c.drawLine(34, 66, 54, 66, stroke);
                break;
            case MIRROR:
                drawMirror(c);
                break;
            case FLIP:
                c.save();
                c.rotate(90f, 50, 50);
                drawMirror(c);
                c.restore();
                break;
            case VIDEO:
                c.drawRoundRect(8, 30, 64, 72, 8, 8, stroke);
                Path w = new Path();
                w.moveTo(70, 46);
                w.lineTo(92, 34);
                w.lineTo(92, 68);
                w.lineTo(70, 56);
                w.close();
                c.drawPath(w, stroke);
                break;
            case TIMELAPSE:
                c.drawCircle(50, 50, 38, stroke);
                c.drawLine(50, 50, 50, 26, stroke);
                c.drawLine(50, 50, 68, 60, stroke);
                break;
            case SAVE:
                Path card = new Path();
                card.moveTo(24, 10);
                card.lineTo(60, 10);
                card.lineTo(78, 28);
                card.lineTo(78, 90);
                card.lineTo(24, 90);
                card.close();
                c.drawPath(card, stroke);
                for (int i = 0; i < 4; i++) c.drawRect(32 + i * 9, 16, 37 + i * 9, 30, fill);
                c.drawRoundRect(34, 50, 68, 76, 4, 4, stroke);
                break;
            default:
                break;
        }
    }

    private void drawMirror(Canvas c) {
        Path l = new Path();
        l.moveTo(8, 72);
        l.lineTo(40, 28);
        l.lineTo(40, 72);
        l.close();
        c.drawPath(l, stroke);
        Path r = new Path();
        r.moveTo(92, 72);
        r.lineTo(60, 28);
        r.lineTo(60, 72);
        r.close();
        c.drawPath(r, fill);
        for (int y = 14; y < 86; y += 14) c.drawLine(50, y, 50, y + 7, stroke);
    }
}
