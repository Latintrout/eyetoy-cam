package com.latintrout.eyetoycam;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Colours and small building blocks for the app's looks (our own artwork, not Sony's).
 * The colour fields follow the active Theme; call apply() before building the screen.
 */
final class Ps2Ui {
    private Ps2Ui() { }

    // names kept from the first look: WHITE = main text, CYAN = accent
    static int WHITE, DIM, CYAN, SEL, SEL_TEXT, PANEL, EDGE, IDLE, DIALOG, LOG_BG, LOG_TEXT, HUD_ACCENT;
    static final int RED = 0xFFE5232B;
    /** Text drawn over the camera picture, the same in every look. */
    static final int HUD = 0xFFF4F6FA;
    static final int HUD_DIM = 0xFFC2CAD6;
    static Theme theme;

    static {
        apply(Theme.byId(Theme.DEFAULT));
    }

    static void apply(Theme t) {
        theme = t;
        WHITE = t.text;
        DIM = t.dim;
        CYAN = t.accent;
        SEL = t.sel;
        SEL_TEXT = t.selText;
        PANEL = t.panel;
        EDGE = t.edge;
        IDLE = t.idle;
        DIALOG = t.dialog;
        LOG_BG = t.logBg;
        LOG_TEXT = t.logText;
        HUD_ACCENT = t.hudAccent;
    }


    interface BoolCb { void on(boolean v); }
    interface IntCb { void on(int v); }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static Drawable panel(Context c, int fill, int stroke, float radiusDp) {
        if (theme.chamfer) {
            float px = c.getResources().getDisplayMetrics().density;
            boolean selected = fill == SEL;
            return new ThemedPanel(fill, selected && theme.glow ? CYAN : stroke,
                    Math.min(radiusDp, 10f) * 0.7f * px, px, theme.bevel, selected && theme.glow);
        }
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
        t.setTypeface(theme.face(bold));
        return t;
    }

    /** Monospace text for the camera-picture overlay, with a shadow so it reads on any picture. */
    static TextView hud(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        t.setLetterSpacing(0.04f);
        t.setShadowLayer(dp(c, 3), 0, dp(c, 1), 0xCC000000);
        return t;
    }

    /** Background for a button or tab that is not selected. */
    static Drawable idle(Context c, float radiusDp) {
        return panel(c, IDLE, EDGE, radiusDp);
    }

    /** Background for a selected button or tab. */
    static Drawable selected(Context c, float radiusDp) {
        return panel(c, SEL, CYAN, radiusDp);
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
            pill.setTextColor(state[0] ? SEL_TEXT : DIM);
            pill.setBackground(panel(c, state[0] ? SEL : IDLE, state[0] ? CYAN : EDGE, 14));
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
                tvs[i].setTextColor(on ? SEL_TEXT : DIM);
                tvs[i].setBackground(panel(c, on ? SEL : IDLE, on ? CYAN : EDGE, 8));
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
