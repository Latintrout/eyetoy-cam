package com.latintrout.eyetoycam;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Panel background for the Retro and Neon looks: cut corners, an optional raised
 * bevel (Retro) and an optional glow when selected (Neon).
 */
final class ThemedPanel extends Drawable {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int fill, stroke;
    private final float cut, px;
    private final boolean bevel, glow;

    /**
     * @param cutPx    size of the cut corners
     * @param px       one dp in pixels
     * @param bevel    draw a light top-left / dark bottom-right edge
     * @param glow     draw a soft glow along the edge
     */
    ThemedPanel(int fill, int stroke, float cutPx, float px, boolean bevel, boolean glow) {
        this.fill = fill;
        this.stroke = stroke;
        this.cut = cutPx;
        this.px = px;
        this.bevel = bevel;
        this.glow = glow;
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        bevelPaint.setStyle(Paint.Style.STROKE);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        super.onBoundsChange(b);
        // keep the glow inside the view so it isn't clipped by the parent
        float in = glow ? 3 * px : px / 2f;
        float l = b.left + in, t = b.top + in, r = b.right - in, bt = b.bottom - in;
        float c = Math.min(cut, Math.min(r - l, bt - t) / 3f);
        path.reset();
        path.moveTo(l + c, t);
        path.lineTo(r - c, t);
        path.lineTo(r, t + c);
        path.lineTo(r, bt - c);
        path.lineTo(r - c, bt);
        path.lineTo(l + c, bt);
        path.lineTo(l, bt - c);
        path.lineTo(l, t + c);
        path.close();
        if (bevel || glow) {
            // a slightly lighter top half makes the surface look curved
            fillPaint.setShader(new LinearGradient(0, t, 0, bt,
                    new int[]{lighten(fill, bevel ? 0.18f : 0.22f), fill, darken(fill, bevel ? 0.06f : 0.1f)},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        } else {
            fillPaint.setShader(null);
            fillPaint.setColor(fill);
        }
    }

    @Override
    public void draw(Canvas c) {
        c.drawPath(path, fillPaint);
        if (bevel) {
            float w = 2 * px;
            bevelPaint.setStrokeWidth(w);
            c.save();
            c.clipPath(path);
            bevelPaint.setColor(0xA0FFFFFF);
            c.translate(w * 0.6f, w * 0.6f);
            c.drawPath(path, bevelPaint);
            bevelPaint.setColor(0x55000000);
            c.translate(-w * 1.2f, -w * 1.2f);
            c.drawPath(path, bevelPaint);
            c.restore();
        }
        if (stroke != 0) {
            strokePaint.setColor(stroke);
            strokePaint.setStrokeWidth(Math.max(1f, (glow ? 1.5f : 1f) * px));
            if (glow) strokePaint.setShadowLayer(5 * px, 0, 0, stroke);
            else strokePaint.clearShadowLayer();
            c.drawPath(path, strokePaint);
        }
    }

    private static int lighten(int col, float f) {
        int a = col >>> 24, r = (col >> 16) & 255, g = (col >> 8) & 255, b = col & 255;
        r += (int) ((255 - r) * f);
        g += (int) ((255 - g) * f);
        b += (int) ((255 - b) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int darken(int col, float f) {
        int a = col >>> 24, r = (col >> 16) & 255, g = (col >> 8) & 255, b = col & 255;
        r -= (int) (r * f);
        g -= (int) (g * f);
        b -= (int) (b * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
