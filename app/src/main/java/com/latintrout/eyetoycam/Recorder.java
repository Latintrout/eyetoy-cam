package com.latintrout.eyetoycam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the EyeToy picture to an MP4 (H.264 video + AAC audio) in Movies/EyeToyCam.
 * Video frames are drawn onto the encoder's input surface as they arrive, so the
 * video keeps the camera's real timing even when its frame rate changes.
 */
final class Recorder {

    interface Logger { void log(String msg); }

    private static final int SAMPLE_RATE = 44100;

    private final Logger log;
    private final Object muxLock = new Object();
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private MediaCodec venc, aenc;
    private Surface inSurface;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private ContentResolver cr;
    private Uri uri;
    private String fileName;
    private AudioRecord audio;
    private Thread videoThread, audioThread;

    private int vTrack = -1, aTrack = -1;
    private boolean muxStarted;
    private boolean withAudio;
    private volatile boolean running;
    private long startNs;
    private int width, height;
    private long videoSamples;

    Recorder(Logger logger) { log = logger; }

    boolean isRunning() { return running; }

    long elapsedMs() { return running ? (System.nanoTime() - startNs) / 1_000_000 : 0; }

    void start(Context ctx, int w, int h, int fps, boolean wantAudio) throws Exception {
        width = w;
        height = h;
        cr = ctx.getContentResolver();
        fileName = "eyetoy_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EyeToyCam");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("couldn't create the video file");
        pfd = cr.openFileDescriptor(uri, "rw");
        if (pfd == null) throw new Exception("couldn't open the video file");
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // ---- video encoder ----
        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inSurface = venc.createInputSurface();
        venc.start();

        // ---- audio ----
        withAudio = false;
        if (wantAudio) {
            try {
                startAudio(ctx);
                withAudio = true;
            } catch (Exception e) {
                log.log("Recording without sound: " + e.getMessage());
                releaseAudio();
            }
        }

        startNs = System.nanoTime();
        videoSamples = 0;
        running = true;
        videoThread = new Thread(this::videoLoop, "video-enc");
        videoThread.start();
        if (withAudio) {
            audioThread = new Thread(this::audioLoop, "audio-enc");
            audioThread.start();
        }
        log.log("Recording to Movies/EyeToyCam/" + fileName + (withAudio ? "" : " (no sound)"));
    }

    @SuppressWarnings("MissingPermission")
    private void startAudio(Context ctx) throws Exception {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) throw new Exception("audio not available");
        audio = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 16384));
        if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("microphone couldn't start");

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

        MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
        af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
        af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        aenc.start();
        audio.startRecording();
    }

    /** Called from the frame thread for every decoded camera frame. */
    void drawFrame(Bitmap bmp) {
        if (!running) return;
        try {
            Canvas c = inSurface.lockHardwareCanvas();
            try {
                c.drawColor(Color.BLACK);
                c.drawBitmap(bmp, null, new Rect(0, 0, width, height), paint);
            } finally {
                inSurface.unlockCanvasAndPost(c);
            }
        } catch (Exception e) {
            // a dropped frame is not worth stopping the recording for
        }
    }

    // ---- muxer helpers ----

    private void addTrack(boolean isVideo, MediaFormat f) {
        synchronized (muxLock) {
            if (isVideo) vTrack = muxer.addTrack(f);
            else aTrack = muxer.addTrack(f);
            boolean ready = vTrack >= 0 && (!withAudio || aTrack >= 0);
            if (ready && !muxStarted) {
                muxer.start();
                muxStarted = true;
                muxLock.notifyAll();
            }
        }
    }

    /** Blocks until the muxer is started (or recording is stopping). */
    private boolean waitForMuxer() {
        synchronized (muxLock) {
            while (!muxStarted) {
                try { muxLock.wait(100); } catch (InterruptedException e) { return false; }
                if (!running && !muxStarted) return false;
            }
            return true;
        }
    }

    private void write(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (muxStarted) muxer.writeSampleData(track, buf, info);
        }
    }

    // ---- video ----

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eosSent = false;
        long lastPts = -1;
        long base = Long.MIN_VALUE;
        while (true) {
            if (!running && !eosSent) {
                try { venc.signalEndOfInputStream(); } catch (Exception ignored) { }
                eosSent = true;
            }
            int idx;
            try { idx = venc.dequeueOutputBuffer(info, 10_000); } catch (Exception e) { break; }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(true, venc.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer out = venc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (!config && info.size > 0 && out != null && (muxStarted || waitForMuxer())) {
                    if (base == Long.MIN_VALUE) {
                        // surface timestamps normally share System.nanoTime's clock; if not, line them up
                        long d = info.presentationTimeUs - startNs / 1000;
                        base = (d >= 0 && d < 10_000_000L) ? startNs / 1000
                                : info.presentationTimeUs - (System.nanoTime() - startNs) / 1000;
                    }
                    info.presentationTimeUs -= base;
                    if (info.presentationTimeUs > lastPts) {
                        lastPts = info.presentationTimeUs;
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        write(vTrack, out, info);
                        videoSamples++;
                    }
                }
                venc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } else if (eosSent && idx == MediaCodec.INFO_TRY_AGAIN_LATER && vTrack < 0) {
                break; // nothing was ever recorded
            }
        }
    }

    // ---- audio ----

    private void drainAudio(MediaCodec.BufferInfo info, boolean untilEos) {
        int idle = 0;
        while (true) {
            int idx;
            try { idx = aenc.dequeueOutputBuffer(info, untilEos ? 10_000 : 0); } catch (Exception e) { return; }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++idle > 100) return;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, aenc.getOutputFormat());
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer out = aenc.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (!config && info.size > 0 && out != null && (muxStarted || waitForMuxer())) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                write(aTrack, out, info);
            }
            aenc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    private void audioLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[4096];
        long samplesWritten = 0;
        long audioStartUs = -1;
        while (running) {
            int n = audio.read(pcm, 0, pcm.length);
            if (n <= 0) continue;
            long nowUs = (System.nanoTime() - startNs) / 1000;
            if (audioStartUs < 0) audioStartUs = Math.max(0, nowUs - n / 2 * 1_000_000L / SAMPLE_RATE);
            // timestamps from the sample count keep the audio smooth and in step with the clock
            long pts = audioStartUs + samplesWritten * 1_000_000L / SAMPLE_RATE;
            int in = aenc.dequeueInputBuffer(10_000);
            if (in >= 0) {
                ByteBuffer b = aenc.getInputBuffer(in);
                if (b != null) {
                    b.clear();
                    int len = Math.min(n, b.remaining());
                    b.put(pcm, 0, len);
                    aenc.queueInputBuffer(in, 0, len, pts, 0);
                    samplesWritten += len / 2;
                }
            }
            drainAudio(info, false);
        }
        int in = aenc.dequeueInputBuffer(50_000);
        if (in >= 0) {
            long pts = audioStartUs < 0 ? 0 : audioStartUs + samplesWritten * 1_000_000L / SAMPLE_RATE;
            aenc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drainAudio(info, true);
        }
    }

    // ---- stop ----

    /** Finishes the file. Safe to call more than once. */
    void stop() {
        if (!running && muxer == null) return;
        long durMs = elapsedMs();
        running = false;
        synchronized (muxLock) { muxLock.notifyAll(); }
        join(audioThread);
        join(videoThread);
        audioThread = null;
        videoThread = null;

        boolean ok = false;
        try {
            if (muxStarted && videoSamples > 0) {
                muxer.stop();
                ok = true;
            }
        } catch (Exception e) {
            log.log("Finishing the video failed: " + e.getMessage());
        }
        try { muxer.release(); } catch (Exception ignored) { }
        muxer = null;
        muxStarted = false;
        vTrack = aTrack = -1;
        try { venc.stop(); } catch (Exception ignored) { }
        try { venc.release(); } catch (Exception ignored) { }
        try { inSurface.release(); } catch (Exception ignored) { }
        venc = null;
        releaseAudio();
        try { pfd.close(); } catch (Exception ignored) { }

        if (ok) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            log.log(String.format(Locale.US, "Saved video Movies/EyeToyCam/%s (%d s, %d frames).",
                    fileName, durMs / 1000, videoSamples));
        } else {
            try { cr.delete(uri, null, null); } catch (Exception ignored) { }
            log.log("Recording had no frames, so nothing was saved.");
        }
    }

    private void releaseAudio() {
        if (audio != null) {
            try { audio.stop(); } catch (Exception ignored) { }
            audio.release();
            audio = null;
        }
        if (aenc != null) {
            try { aenc.stop(); } catch (Exception ignored) { }
            aenc.release();
            aenc = null;
        }
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(3000); } catch (InterruptedException ignored) { }
    }
}
