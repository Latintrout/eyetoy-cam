package com.latintrout.eyetoycam;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings. */
final class Prefs {
    static final String MODE_VIDEO = "video", MODE_PHOTO = "photo", MODE_TIMELAPSE = "timelapse";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("eyetoycam", Context.MODE_PRIVATE);
    }

    boolean b(String key, boolean def) { return sp.getBoolean(key, def); }
    void putB(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }

    int i(String key, int def) { return sp.getInt(key, def); }
    void putI(String key, int v) { sp.edit().putInt(key, v).apply(); }

    String s(String key, String def) { return sp.getString(key, def); }
    void putS(String key, String v) { sp.edit().putString(key, v).apply(); }

    int fps() { return i("fps", 30); }
}
