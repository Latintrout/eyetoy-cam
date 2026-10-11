package com.latintrout.eyetoycam;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashMap;
import java.util.Map;

/**
 * Lays out one picture tile per camera: a single tile, a column (stacked),
 * two columns (grid), or free windows the user drags and resizes.
 * Each tile's tag is its camera number. One tile can be shown alone ("solo").
 */
final class CamGrid extends ViewGroup {
    static final int AUTO = 0, STACKED = 1, GRID = 2, FREE = 3;
    /** Smallest free window, as a share of the area's width. */
    private static final float MIN_W = 0.25f;

    private int cols = 1, rows = 1;
    private boolean free;
    private int solo = -1;
    private final int gap;
    /** Free windows by camera number: left, top (shares of the area) and width (share of the area's width). */
    private final Map<Integer, float[]> wins = new HashMap<>();

    CamGrid(Context c, int gapPx) {
        super(c);
        gap = gapPx;
    }

    /** Picks columns and rows for n cameras. */
    void setShape(int n, int layout) {
        n = Math.max(1, n);
        free = layout == FREE && n > 1;
        boolean grid = layout == GRID || (layout == AUTO && n >= 3);
        if (n == 1) { cols = 1; rows = 1; }
        else if (grid || free) { cols = 2; rows = (n + 1) / 2; }
        else { cols = 1; rows = n; }
        requestLayout();
    }

    boolean isFree() { return free; }

    /** Shows only the tile of camera number k (or all tiles again with -1). */
    void setSolo(int k) {
        solo = k;
        requestLayout();
    }

    int solo() { return solo; }

    /** Width divided by height of the grid, gaps ignored (only used for a single camera). */
    float ratio() { return (cols * 4f) / (rows * 3f); }

    /** Camera number of the top tile under (x, y), or -1. */
    int camAt(float x, float y) {
        View v = viewAt(x, y);
        return v == null ? -1 : (Integer) v.getTag();
    }

    /** Topmost visible tile under (x, y). */
    View viewAt(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE || v.getWidth() == 0) continue;
            if (x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom()) return v;
        }
        return null;
    }

    void setWindow(int k, float[] pos) { wins.put(k, pos); }

    float[] window(int k) { return wins.get(k); }

    /** Moves a free window by (dx, dy) pixels. */
    void moveWindow(int k, float dx, float dy) {
        float[] p = wins.get(k);
        if (p == null || getWidth() == 0) return;
        p[0] += dx / getWidth();
        p[1] += dy / getHeight();
        clamp(p);
        requestLayout();
    }

    /** Resizes a free window to the given width in pixels, keeping its top-left corner (or its centre). */
    void resizeWindow(int k, float newWidthPx, boolean aroundCentre) {
        float[] p = wins.get(k);
        if (p == null || getWidth() == 0) return;
        float W = getWidth(), H = getHeight();
        float oldW = p[2];
        float maxW = Math.min(1f, (H * 4f / 3f) / W);
        float nw = Math.max(MIN_W, Math.min(maxW, newWidthPx / W));
        if (aroundCentre) {
            p[0] -= (nw - oldW) / 2f;
            p[1] -= (nw - oldW) * W * 0.75f / H / 2f;
        }
        p[2] = nw;
        clamp(p);
        requestLayout();
    }

    private void clamp(float[] p) {
        if (getWidth() == 0) return;
        float W = getWidth(), H = getHeight();
        float hShare = p[2] * W * 0.75f / H;
        p[0] = Math.max(0f, Math.min(1f - p[2], p[0]));
        p[1] = Math.max(0f, Math.min(Math.max(0f, 1f - hShare), p[1]));
    }

    /** Free windows start out where the grid would put them. */
    private float[] defaultWindow(int index, int n, int W, int H) {
        int r = (n + 1) / 2;
        float cellW = (W - gap) / 2f, cellH = (H - gap * (r - 1)) / (float) r;
        float tw = Math.min(cellW, cellH * 4f / 3f);
        float th = tw * 0.75f;
        float left = (index % 2) * (cellW + gap) + (cellW - tw) / 2f;
        float top = (index / 2) * (cellH + gap) + (cellH - th) / 2f;
        return new float[]{left / W, top / H, tw / W};
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        // children are sized in onLayout; measure them to what they'll get
        Rect[] rs = rects(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            Rect r = rs[i];
            v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, r.r - r.l), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, r.b - r.t), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        Rect[] rs = rects(r - l, b - t);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            Rect q = rs[i];
            v.layout(q.l, q.t, q.r, q.b);
        }
    }

    private static final class Rect {
        int l, t, r, b;
        Rect(float l, float t, float r, float b) {
            this.l = Math.round(l); this.t = Math.round(t); this.r = Math.round(r); this.b = Math.round(b);
        }
    }

    /** Where each child goes. Pictures keep their 4:3 shape and are centred in their cell. */
    private Rect[] rects(int W, int H) {
        int n = getChildCount();
        Rect[] out = new Rect[n];
        if (solo >= 0) {
            for (int i = 0; i < n; i++) {
                boolean on = (Integer) getChildAt(i).getTag() == solo;
                out[i] = on ? fit(0, 0, W, H) : new Rect(0, 0, 0, 0);
            }
            return out;
        }
        if (free) {
            for (int i = 0; i < n; i++) {
                int k = (Integer) getChildAt(i).getTag();
                float[] p = wins.get(k);
                if (p == null) {
                    p = defaultWindow(indexOfCam(k), n, W, H);
                    wins.put(k, p);
                }
                if (W > 0 && H > 0) {
                    float hShare = p[2] * W * 0.75f / H;
                    if (hShare > 1f) p[2] = H * 4f / 3f / W;
                }
                float left = p[0] * W, top = p[1] * H, w = p[2] * W, h = w * 0.75f;
                left = Math.max(0, Math.min(W - w, left));
                top = Math.max(0, Math.min(H - h, top));
                out[i] = new Rect(left, top, left + w, top + h);
            }
            return out;
        }
        float cw = (W - gap * (cols - 1)) / (float) cols;
        float ch = (H - gap * (rows - 1)) / (float) rows;
        for (int i = 0; i < n; i++) {
            int cx = i % cols, cy = i / cols;
            float left = cx * (cw + gap), top = cy * (ch + gap), w = cw;
            // a lone tile on the last row of a grid gets the whole row
            if (cols == 2 && i == n - 1 && n % 2 == 1) { left = 0; w = W; }
            out[i] = fit(left, top, w, ch);
        }
        return out;
    }

    /** Index the camera with number k has among the children in camera-number order. */
    private int indexOfCam(int k) {
        int idx = 0;
        for (int i = 0; i < getChildCount(); i++) if ((Integer) getChildAt(i).getTag() < k) idx++;
        return idx;
    }

    /** The biggest 4:3 box centred in the cell. */
    private static Rect fit(float l, float t, float w, float h) {
        float tw = Math.min(w, h * 4f / 3f), th = tw * 0.75f;
        float x = l + (w - tw) / 2f, y = t + (h - th) / 2f;
        return new Rect(x, y, x + tw, y + th);
    }
}
