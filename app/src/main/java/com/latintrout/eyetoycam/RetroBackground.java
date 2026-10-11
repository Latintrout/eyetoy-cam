package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/** Brushed grey plastic, like the front of an old console. Static, so it costs nothing. */
final class RetroBackground extends View implements Backdrop {
    private Bitmap bmp;

    RetroBackground(Context c) {
        super(c);
    }

    @Override
    public void setAnimated(boolean on) { }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        if (bmp != null) bmp.recycle();
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{Ps2Ui.theme.bgTop, Ps2Ui.theme.bgBottom}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        // fine horizontal brush lines
        Paint line = new Paint();
        Random r = new Random(3);
        for (int y = 0; y < h; y += 2) {
            int a = 4 + r.nextInt(10);
            line.setColor(r.nextBoolean() ? (a << 24) | 0xFFFFFF : (a << 24));
            c.drawRect(0, y, w, y + 1, line);
        }
        // speaker grille near the bottom
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(0x55000000);
        float d = getResources().getDisplayMetrics().density;
        float gap = 7 * d, cx = w / 2f, by = h - 22 * d;
        for (int row = 0; row < 2; row++) {
            for (int i = -3; i <= 3; i++) {
                c.drawCircle(cx + i * gap + (row == 1 ? gap / 2f : 0), by + row * gap, 1.4f * d, dot);
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp != null) c.drawBitmap(bmp, 0, 0, null);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (bmp != null) {
            bmp.recycle();
            bmp = null;
        }
    }
}
