package com.latintrout.eyetoycam;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Lays out one 4:3 tile per camera: a single tile, a column (stacked) or two
 * columns (grid). Its parent AspectLayout is given the matching shape.
 */
final class CamGrid extends ViewGroup {
    static final int AUTO = 0, STACKED = 1, GRID = 2;

    private int cols = 1, rows = 1;
    private final int gap;

    CamGrid(Context c, int gapPx) {
        super(c);
        gap = gapPx;
    }

    /** Picks columns and rows for n cameras. */
    void setShape(int n, int layout) {
        n = Math.max(1, n);
        boolean grid = layout == GRID || (layout == AUTO && n >= 3);
        if (n == 1) { cols = 1; rows = 1; }
        else if (grid) { cols = 2; rows = (n + 1) / 2; }
        else { cols = 1; rows = n; }
        requestLayout();
    }

    /** Width divided by height of the whole grid, gaps ignored. */
    float ratio() { return (cols * 4f) / (rows * 3f); }

    /** Index of the visible tile under (x, y), or -1. */
    int tileAt(float x, float y) {
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            if (x >= v.getLeft() && x < v.getRight() && y >= v.getTop() && y < v.getBottom()) return i;
        }
        return -1;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);
        int tw = Math.max(0, (w - gap * (cols - 1)) / cols);
        int th = Math.max(0, (h - gap * (rows - 1)) / rows);
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            v.measure(MeasureSpec.makeMeasureSpec(tw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(th, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t;
        int tw = Math.max(0, (w - gap * (cols - 1)) / cols);
        int th = Math.max(0, (h - gap * (rows - 1)) / rows);
        int k = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int cx = k % cols, cy = k / cols;
            // a lone tile on the last row of a grid sits in the middle
            int left = cx * (tw + gap);
            if (cols == 2 && cy == rows - 1 && k == getVisibleCount() - 1 && k % 2 == 0) left = (w - tw) / 2;
            int top = cy * (th + gap);
            v.layout(left, top, left + tw, top + th);
            k++;
        }
    }

    private int getVisibleCount() {
        int n = 0;
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i).getVisibility() != GONE) n++;
        return n;
    }
}
