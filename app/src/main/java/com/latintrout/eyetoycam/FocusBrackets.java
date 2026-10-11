package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Four thin corner marks in the middle of the picture, like a camcorder viewfinder. Decoration only. */
final class FocusBrackets extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    FocusBrackets(Context c) {
        super(c);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ps2Ui.dp(c, 2));
        p.setColor(0xDDFFFFFF);
        p.setShadowLayer(Ps2Ui.dp(c, 2), 0, 0, 0x99000000);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h) * 0.2f, arm = s * 0.3f;
        float l = w / 2f - s / 2f, t = h / 2f - s / 2f, r = l + s, b = t + s;
        c.drawLine(l, t, l + arm, t, p);
        c.drawLine(l, t, l, t + arm, p);
        c.drawLine(r, t, r - arm, t, p);
        c.drawLine(r, t, r, t + arm, p);
        c.drawLine(l, b, l + arm, b, p);
        c.drawLine(l, b, l, b - arm, p);
        c.drawLine(r, b, r - arm, b, p);
        c.drawLine(r, b, r, b - arm, p);
    }
}
