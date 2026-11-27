package com.latintrout.eyetoycam;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one microphone, shared by every video being recorded at the same time
 * (Android only lets one recorder use a mic). Each Recorder gets the same sound.
 * It keeps running across split parts, so there is no sound gap between them.
 */
final class MicSource {
    static final int SAMPLE_RATE = 44100;

    interface Listener {
        /** pcm: 16-bit mono; capturedNs: System.nanoTime() of the chunk's first sample. */
        void onPcm(byte[] pcm, int n, long capturedNs);
    }

    private final Recorder.Logger log;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private AudioRecord audio;
    private Thread thread;
    private volatile boolean running;
    private volatile float level;

    MicSource(Recorder.Logger log) { this.log = log; }

    @SuppressWarnings("MissingPermission")
    void start(Context ctx) throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new Exception("audio not available");
        audio = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 32768));
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
            release();
            throw new Exception("microphone couldn't start");
        }
        AudioManager am = ctx.getSystemService(AudioManager.class);
        AudioDeviceInfo usbMic = null;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                usbMic = d;
                break;
            }
        }
        if (usbMic != null) {
            audio.setPreferredDevice(usbMic);
            log.log("Using the EyeToy's microphone (" + usbMic.getProductName() + ").");
        } else {
            log.log("EyeToy microphone not found by Android, using the phone's mic.");
        }
        audio.startRecording();
        running = true;
        thread = new Thread(this::loop, "mic");
        thread.start();
    }

    void add(Listener l) { listeners.add(l); }
    void remove(Listener l) { listeners.remove(l); }

    /** Loudness of the last chunk, 0 (silent) to 1 (very loud). */
    float level() { return level; }

    private void loop() {
        byte[] pcm = new byte[4096];
        while (running) {
            int n = audio.read(pcm, 0, pcm.length);
            if (n <= 0) continue;
            long capturedNs = System.nanoTime() - n / 2 * 1_000_000_000L / SAMPLE_RATE;
            level = level(pcm, n);
            for (Listener l : listeners) {
                try { l.onPcm(pcm, n, capturedNs); } catch (Exception ignored) { }
            }
        }
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(2000); } catch (InterruptedException ignored) { }
            thread = null;
        }
        release();
        listeners.clear();
    }

    private void release() {
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) { }
            audio.release();
            audio = null;
        }
    }

    private static float level(byte[] pcm, int n) {
        long sum = 0;
        int count = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int v = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) v * v;
        }
        if (count == 0) return 0f;
        double rms = Math.sqrt(sum / (double) count) / 32768.0;
        double db = 20 * Math.log10(Math.max(rms, 1e-6));
        return (float) Math.max(0, Math.min(1, (db + 50) / 50));
    }
}
