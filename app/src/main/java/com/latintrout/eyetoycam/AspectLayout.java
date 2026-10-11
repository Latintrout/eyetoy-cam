package com.latintrout.eyetoycam;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * A frame that keeps a fixed shape, as large as fits: 4:3 (the EyeToy's picture
 * shape) for one camera, or the shape of the camera grid for several.
 */
final class AspectLayout extends FrameLayout {
    private float ratio = 4f / 3f;

    AspectLayout(Context c) { super(c); }

    void setRatio(float r) {
        if (r == ratio) return;
        ratio = r;
        requestLayout();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = View.MeasureSpec.getSize(wSpec);
        int hMode = View.MeasureSpec.getMode(hSpec);
        int hMax = View.MeasureSpec.getSize(hSpec);
        int h = Math.round(w / ratio);
        if (hMode != View.MeasureSpec.UNSPECIFIED && h > hMax) {
            h = hMax;
            w = Math.round(h * ratio);
        }
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    }
}
