package com.latintrout.eyetoycam;

import android.graphics.Typeface;

/**
 * The selectable looks. Each one is a set of colours plus a few style switches;
 * Ps2Ui copies the active one into its colour fields before the screen is built.
 */
final class Theme {
    static final String RETRO = "retro", NEON = "neon";
    static final String[] IDS = {RETRO, NEON};
    static final String[] NAMES = {"Retro grey", "Neon blue"};
    static final String DEFAULT = NEON;

    final String id;
    final int text, dim, accent, sel, selText, panel, edge, idle, dialog;
    final int logBg, logText, hudAccent;
    final int bgTop, bgBottom;
    /** Cut (chamfered) corners and a raised bevel, like old console hardware. */
    final boolean chamfer, bevel;
    /** Selected items glow. */
    final boolean glow;
    /** Dark text on a light background: status-bar icons must be dark too. */
    final boolean light;
    private final String normalFont, boldFont;

    private Theme(String id, int text, int dim, int accent, int sel, int selText, int panel, int edge,
                  int idle, int dialog, int logBg, int logText, int hudAccent, int bgTop, int bgBottom,
                  boolean chamfer, boolean bevel, boolean glow, boolean light,
                  String normalFont, String boldFont) {
        this.id = id;
        this.text = text;
        this.dim = dim;
        this.accent = accent;
        this.sel = sel;
        this.selText = selText;
        this.panel = panel;
        this.edge = edge;
        this.idle = idle;
        this.dialog = dialog;
        this.logBg = logBg;
        this.logText = logText;
        this.hudAccent = hudAccent;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.chamfer = chamfer;
        this.bevel = bevel;
        this.glow = glow;
        this.light = light;
        this.normalFont = normalFont;
        this.boldFont = boldFont;
    }

    Typeface face(boolean bold) {
        if ("monospace".equals(bold ? boldFont : normalFont)) {
            return Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        }
        return Typeface.create(bold ? boldFont : normalFont, Typeface.NORMAL);
    }

    /** Grey plastic console with lavender highlights. */
    static final Theme RETRO_GREY = new Theme(RETRO,
            0xFF25272B, 0xFF575A61, 0xFF3D4787, 0xFFA4AEEA, 0xFF1E2140,
            0xFFC2C4C8, 0xFF7C7F85, 0xFFBDBFC3, 0xFFC6C8CC,
            0xFFD9DBD2, 0xFF2B2E33, 0xFFB9C1FF,
            0xFFC0C2C6, 0xFFA4A6AA,
            true, true, false, true, "monospace", "monospace");

    /** Dark navy glass with glowing blue edges. */
    static final Theme NEON_BLUE = new Theme(NEON,
            0xFFDCE8FF, 0xFF8299C8, 0xFF62A6FF, 0xFF1F55E8, 0xFFFFFFFF,
            0x99071433, 0xAA3F6FD6, 0x66061430, 0xF0071433,
            0xCC030A1F, 0xFFBFD6FF, 0xFF6FB0FF,
            0xFF020611, 0xFF071A45,
            true, false, true, false, "sans-serif-condensed", "sans-serif-condensed-medium");

    static Theme byId(String id) {
        if (RETRO.equals(id)) return RETRO_GREY;
        return NEON_BLUE;
    }

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return i;
        return 1;
    }
}
