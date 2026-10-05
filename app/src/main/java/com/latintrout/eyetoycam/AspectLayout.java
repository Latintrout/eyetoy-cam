package com.latintrout.eyetoycam;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** A frame that is always 4:3 (the EyeToy's picture shape), as large as fits. */
final class AspectLayout extends FrameLayout {
    private static final float RATIO = 4f / 3f;

    AspectLayout(Context c) { super(c); }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = View.MeasureSpec.getSize(wSpec);
        int hMode = View.MeasureSpec.getMode(hSpec);
        int hMax = View.MeasureSpec.getSize(hSpec);
        int h = Math.round(w / RATIO);
        if (hMode != View.MeasureSpec.UNSPECIFIED && h > hMax) {
            h = hMax;
            w = Math.round(h * RATIO);
        }
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
    }
}
