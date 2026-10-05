package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Saving data... do not unplug" panel, in the style of an old console memory-card save. */
final class SaveOverlay extends FrameLayout {

    private final TextView title, sub;
    private final Bar bar;
    private final IconView icon;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean showing;
    private boolean finished;
    private long shownAt;
    private Runnable pendingHide;

    SaveOverlay(Context c) {
        super(c);
        setBackgroundColor(0xAA000610);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = Ps2Ui.dp(c, 24);
        box.setPadding(p, p, p, p);
        box.setBackground(Ps2Ui.panel(c, 0xF0081445, Ps2Ui.EDGE, 12));

        icon = new IconView(c, IconView.SAVE);
        icon.setColor(Ps2Ui.CYAN);
        box.addView(icon, new LinearLayout.LayoutParams(Ps2Ui.dp(c, 56), Ps2Ui.dp(c, 56)));

        title = Ps2Ui.glow(Ps2Ui.text(c, "Saving data...", 18, Ps2Ui.WHITE, true), 0x806FB5FF);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ps2Ui.dp(c, 12);
        box.addView(title, tlp);

        sub = Ps2Ui.text(c, "", 13, Ps2Ui.DIM, false);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ps2Ui.dp(c, 4);
        box.addView(sub, slp);

        bar = new Bar(c);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ps2Ui.dp(c, 230), Ps2Ui.dp(c, 8));
        blp.topMargin = Ps2Ui.dp(c, 16);
        box.addView(bar, blp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(box, lp);

        setOnClickListener(v -> {
            if (finished) hideNow();
        });
    }

    boolean isShowing() { return showing; }

    /** Call on the UI thread. */
    void showSaving(String line1, String line2) {
        cancelHide();
        showing = true;
        finished = false;
        shownAt = SystemClock.uptimeMillis();
        title.setText(line1);
        sub.setText(line2);
        bar.setMode(Bar.RUNNING);
        setVisibility(VISIBLE);
        bringToFront();
    }

    /** Call on the UI thread. Waits a moment so the message can always be read. */
    void showDone(String message) {
        long wait = Math.max(0, 1300 - (SystemClock.uptimeMillis() - shownAt));
        cancelHide();
        pendingHide = () -> {
            finished = true;
            title.setText("Saved");
            sub.setText(message);
            bar.setMode(Bar.DONE);
            pendingHide = this::hideNow;
            h.postDelayed(pendingHide, 2000);
        };
        h.postDelayed(pendingHide, wait);
    }

    /** Call on the UI thread. */
    void showFail(String message) {
        long wait = Math.max(0, 900 - (SystemClock.uptimeMillis() - shownAt));
        cancelHide();
        pendingHide = () -> {
            finished = true;
            title.setText("Couldn't save");
            sub.setText(message);
            bar.setMode(Bar.FAILED);
            pendingHide = this::hideNow;
            h.postDelayed(pendingHide, 3500);
        };
        h.postDelayed(pendingHide, wait);
    }

    private void cancelHide() {
        if (pendingHide != null) h.removeCallbacks(pendingHide);
        pendingHide = null;
    }

    private void hideNow() {
        cancelHide();
        bar.setMode(Bar.IDLE);
        setVisibility(GONE);
        showing = false;
        finished = false;
    }

    /** The progress bar: a moving highlight while saving, full when done. */
    private static final class Bar extends View {
        static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private int mode = IDLE;

        Bar(Context c) {
            super(c);
            track.setColor(0x33FFFFFF);
        }

        void setMode(int m) {
            mode = m;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), hgt = getHeight();
            r.set(0, 0, w, hgt);
            c.drawRoundRect(r, hgt / 2f, hgt / 2f, track);
            if (mode == DONE) {
                fill.setColor(Ps2Ui.CYAN);
                c.drawRoundRect(r, hgt / 2f, hgt / 2f, fill);
            } else if (mode == FAILED) {
                fill.setColor(Ps2Ui.RED);
                c.drawRoundRect(r, hgt / 2f, hgt / 2f, fill);
            } else if (mode == RUNNING) {
                float band = w * 0.35f;
                float pos = (SystemClock.uptimeMillis() % 1100) / 1100f * (w + band) - band;
                fill.setColor(Ps2Ui.CYAN);
                c.save();
                c.clipRect(0, 0, w, hgt);
                c.drawRoundRect(new RectF(pos, 0, pos + band, hgt), hgt / 2f, hgt / 2f, fill);
                c.restore();
                postInvalidateOnAnimation();
            }
        }
    }
}
