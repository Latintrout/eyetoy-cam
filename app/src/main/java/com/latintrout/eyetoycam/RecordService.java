package com.latintrout.eyetoycam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Does no work itself. While it runs, Android treats the app as "in use", so a
 * recording keeps going (and its file gets finished) when the screen turns off
 * or you switch to another app.
 */
public final class RecordService extends Service {
    private static final String CHANNEL = "recording";
    private static final int ID = 1;
    private PowerManager.WakeLock wake;

    /** What keeps the service running: a recording, the Wi-Fi webcam, or both. */
    private static volatile boolean recording, wifi;

    /** Call when a recording starts. Never throws; recording works without the service. */
    static void start(Context c) {
        recording = true;
        refresh(c);
    }

    static void stop(Context c) {
        recording = false;
        refresh(c);
    }

    /** Call when the Wi-Fi webcam turns on or off. */
    static void setWifi(Context c, boolean on) {
        wifi = on;
        refresh(c);
    }

    private static void refresh(Context c) {
        try {
            if (recording || wifi) c.startForegroundService(new Intent(c, RecordService.class));
            else c.stopService(new Intent(c, RecordService.class));
        } catch (Exception ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(recording && wifi ? "EyeToy Cam is recording and streaming"
                        : recording ? "EyeToy Cam is recording" : "EyeToy Cam Wi-Fi webcam is on")
                .setContentText(recording ? "Tap to open. Stop the recording in the app."
                        : "Tap to open. Turn it off in the app.")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        try {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(ID, n, type);
        } catch (Exception e) {
            // not allowed right now: the recording carries on, just without background protection
            stopSelf();
            return START_NOT_STICKY;
        }
        if (wake == null) {
            wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eyetoycam:rec");
            wake.acquire(6 * 60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
