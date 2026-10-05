package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colours and small building blocks for the PS2-menu look (our own artwork, not Sony's). */
final class Ps2Ui {
    private Ps2Ui() { }

    static final int WHITE = 0xFFEAF2FF;
    static final int DIM = 0xFF8FA3C8;
    static final int CYAN = 0xFF7FD2FF;
    static final int BLUE = 0xFF2F6BFF;
    static final int SEL = 0xFF1F4FD0;
    static final int PANEL = 0xB0071033;
    static final int EDGE = 0x805FA8FF;
    static final int RED = 0xFFE5232B;

    interface BoolCb { void on(boolean v); }
    interface IntCb { void on(int v); }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable panel(Context c, int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static GradientDrawable circle(int fill, int stroke, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(fill);
        if (stroke != 0) g.setStroke(strokePx, stroke);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif-light", Typeface.NORMAL));
        return t;
    }

    static TextView glow(TextView t, int color) {
        t.setShadowLayer(dp(t.getContext(), 6), 0, 0, color);
        return t;
    }

    /** A framed settings section; adds itself to the parent and returns the box to fill. */
    static LinearLayout section(Context c, ViewGroup parent, String title) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(panel(c, PANEL, EDGE, 10));
        int p = dp(c, 14);
        box.setPadding(p, dp(c, 10), p, dp(c, 8));
        TextView h = text(c, title.toUpperCase(), 12, CYAN, true);
        h.setLetterSpacing(0.14f);
        box.addView(h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        parent.addView(box, lp);
        return box;
    }

    static View toggleRow(Context c, String title, String sub, boolean init, BoolCb cb) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 8), 0, dp(c, 8));
        row.setClickable(true);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        left.addView(text(c, title, 15, WHITE, false));
        if (sub != null) {
            TextView s = text(c, sub, 12, DIM, false);
            left.addView(s);
        }
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView pill = text(c, "", 12, WHITE, true);
        pill.setGravity(Gravity.CENTER);
        pill.setMinWidth(dp(c, 58));
        pill.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        row.addView(pill);

        final boolean[] state = {init};
        final Runnable paint = () -> {
            pill.setText(state[0] ? "ON" : "OFF");
            pill.setTextColor(state[0] ? WHITE : DIM);
            pill.setBackground(panel(c, state[0] ? SEL : 0x40000000, state[0] ? CYAN : EDGE, 14));
        };
        paint.run();
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            cb.on(state[0]);
        });
        return row;
    }

    static View segmentRow(Context c, String title, String sub, String[] opts, int sel, IntCb cb) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 8), 0, dp(c, 8));
        box.addView(text(c, title, 15, WHITE, false));
        if (sub != null) box.addView(text(c, sub, 12, DIM, false));

        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(c, 8);
        box.addView(seg, slp);

        final TextView[] tvs = new TextView[opts.length];
        final int[] selected = {sel};
        final Runnable paint = () -> {
            for (int i = 0; i < tvs.length; i++) {
                boolean on = i == selected[0];
                tvs[i].setTextColor(on ? WHITE : DIM);
                tvs[i].setBackground(panel(c, on ? SEL : 0x40000000, on ? CYAN : EDGE, 8));
            }
        };
        for (int i = 0; i < opts.length; i++) {
            final int idx = i;
            TextView t = text(c, opts[i], 13, WHITE, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(c, 9), 0, dp(c, 9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(c, 3);
            lp.rightMargin = dp(c, 3);
            seg.addView(t, lp);
            tvs[i] = t;
            t.setOnClickListener(v -> {
                selected[0] = idx;
                paint.run();
                cb.on(idx);
            });
        }
        paint.run();
        return box;
    }
}
