package com.latintrout.eyetoycam;

import android.media.ExifInterface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;

/** File names and the camera info stored inside photos and videos. */
final class Metadata {
    private Metadata() { }

    static String safe(String s) {
        if (s == null) return "";
        return s.trim().replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** e.g. EyeToy_Grey_20261005_202937 (or EyeToy_20261005_202937 for the default name). */
    static String baseName(String label, String stamp) {
        String l = safe(label);
        if (l.isEmpty() || l.equalsIgnoreCase("EyeToy")) return "EyeToy_" + stamp;
        return "EyeToy_" + l + "_" + stamp;
    }

    static String megapixels(int w, int h) {
        return String.format(Locale.US, "%.2f MP", w * (double) h / 1_000_000.0);
    }

    static String description(String label, String sensor, int w, int h, int fps, boolean timelapse) {
        StringBuilder sb = new StringBuilder("Sony EyeToy (PS2) USB camera");
        if (label != null && !label.isEmpty() && !label.equalsIgnoreCase("EyeToy")) sb.append(" \"").append(label).append("\"");
        sb.append(" - ").append(w).append("x").append(h).append(" (").append(megapixels(w, h)).append(")");
        sb.append(", OV519 chip");
        if (sensor != null && !sensor.isEmpty()) sb.append(" + ").append(sensor).append(" sensor");
        sb.append(timelapse ? ", time-lapse " : ", ").append(fps).append(" fps");
        return sb.toString();
    }

    /** Adds the camera info to a saved JPEG. */
    static void writePhotoExif(FileDescriptor fd, String label, String sensor, int w, int h, String software)
            throws IOException {
        ExifInterface ex = new ExifInterface(fd);
        String when = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date());
        ex.setAttribute(ExifInterface.TAG_MAKE, "Sony");
        ex.setAttribute(ExifInterface.TAG_MODEL, "EyeToy USB camera (PS2)");
        ex.setAttribute(ExifInterface.TAG_SOFTWARE, software);
        ex.setAttribute(ExifInterface.TAG_DATETIME, when);
        ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, when);
        ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, when);
        ex.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, String.valueOf(w));
        ex.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, String.valueOf(h));
        ex.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description(label, sensor, w, h, 0, false)
                .replace(", 0 fps", ""));
        ex.saveAttributes();
    }
}
