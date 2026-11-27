package com.latintrout.eyetoycam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Records the EyeToy picture to an MP4 in Movies/EyeToyCam.
 *
 * The file is a fragmented MP4 (see {@link Fmp4Writer}): it is written in pieces
 * of about a second, so a crash or a killed app loses at most the last second or
 * two, and {@link #recoverUnfinished} rescues such files the next time the app opens.
 *
 * Normal mode: H.264 video + AAC sound (from a shared {@link MicSource}), frames
 * timed by the real clock. Time-lapse mode: no sound; every frame handed in becomes
 * one frame of a fixed-speed video, however long it was since the last one.
 * A recorder can also draw several cameras into one picture (grid or picture-in-picture).
 */
final class Recorder implements MicSource.Listener {

    interface Logger { void log(String msg); }

    /** Video files being written right now, so the rescue never touches one in use. */
    private static final Set<String> ACTIVE = Collections.synchronizedSet(new HashSet<>());

    private final Logger log;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF dstF = new RectF();
    private final RectF cell = new RectF();

    private MediaCodec venc, aenc;
    private Surface inSurface;
    private Fmp4Writer writer;
    private ParcelFileDescriptor pfd;
    private ContentResolver cr;
    private Uri uri;
    private String fileName;
    private MicSource mic;
    private Thread videoThread;
    private final Object audioLock = new Object();
    private Look look;
    private boolean burn;
    private String burnLine1 = "", burnLine2 = "";
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameEdge = new Paint();
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("MMM dd yyyy", Locale.US);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("hh:mm:ss a", Locale.US);
    private long burnSecond = -1;
    private String burnDate = "", burnTime = "";

    private boolean withAudio;
    private volatile boolean running;
    private volatile boolean writeFailed;
    private boolean timelapse;
    private int outFps = 30;
    private long startNs;
    private int width, height;
    private volatile long videoSamples;
    private volatile long framesDrawn;
    private long audioSamples, audioStartUs = -1;
    private boolean audioEos;
    private boolean saved;

    Recorder(Logger logger) { log = logger; }

    boolean isRunning() { return running; }
    boolean isTimelapse() { return timelapse; }
    long framesDrawn() { return framesDrawn; }
    long elapsedMs() { return running ? (System.nanoTime() - startNs) / 1_000_000 : 0; }
    /** File name if the video was saved successfully, otherwise null. */
    String savedName() { return saved ? fileName : null; }
    /** Where the video was saved, or null if it wasn't. */
    Uri savedUri() { return saved ? uri : null; }
    boolean hasSound() { return withAudio; }

    /**
     * @param mic the shared microphone, or null for no sound (time-lapse never has sound)
     */
    void start(Context ctx, int w, int h, int fps, MicSource mic, Look lk, String baseName,
               String description, boolean tl, int timelapseFps,
               boolean burnDetails, String cameraName) throws Exception {
        width = w;
        height = h;
        look = lk;
        timelapse = tl;
        outFps = timelapseFps;
        dstF.set(0, 0, w, h);
        burn = burnDetails;
        float px = Math.min(w / 640f, h / 480f);
        if (burn) {
            // size, frame rate and camera name, top-left; date and time, bottom-left
            burnLine1 = w + "×" + h;
            burnLine2 = (tl ? "" : fps + " FPS · ") + cameraName.toUpperCase(Locale.US);
            textPaint.setColor(0xFFF4F6FA);
            textPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.BOLD));
            textPaint.setTextSize(20f * px);
            textShadow.setColor(0xCC000000);
            textShadow.setTypeface(textPaint.getTypeface());
            textShadow.setTextSize(20f * px);
        }
        smallText.set(textPaint);
        smallText.setColor(0xFFF4F6FA);
        smallText.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE,
                android.graphics.Typeface.BOLD));
        smallText.setTextSize(15f * px);
        smallShadow.set(smallText);
        smallShadow.setColor(0xCC000000);
        frameEdge.setColor(0xFF000000);
        frameEdge.setStyle(Paint.Style.STROKE);
        frameEdge.setStrokeWidth(Math.max(2f, 3f * px));

        cr = ctx.getContentResolver();
        fileName = baseName + ".mp4";

        ContentValues v = new ContentValues();
        v.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
        v.put(MediaStore.Video.Media.TITLE, baseName);
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EyeToyCam");
        v.put(MediaStore.Video.Media.IS_PENDING, 1);
        try {
            v.put(MediaStore.Video.Media.DESCRIPTION, description);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) {
            v.remove(MediaStore.Video.Media.DESCRIPTION);
            uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
        }
        if (uri == null) throw new Exception("couldn't create the video file");
        ACTIVE.add(uri.toString());
        pfd = cr.openFileDescriptor(uri, "rw");
        if (pfd == null) throw new Exception("couldn't open the video file");

        withAudio = mic != null && !timelapse;
        writer = new Fmp4Writer(new FdIo(pfd.getFileDescriptor()), withAudio);

        // ---- video encoder ----
        int base = timelapse ? 4_000_000 : 2_500_000;
        int bitrate = (int) Math.min(10_000_000L, (long) base * w * h / (640 * 480));
        MediaFormat vf = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        vf.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        vf.setInteger(MediaFormat.KEY_FRAME_RATE, timelapse ? outFps : fps);
        // a key frame every second: each one-second piece of the file can start with one
        vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        venc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inSurface = venc.createInputSurface();
        venc.start();

        // ---- sound encoder (fed by the shared mic) ----
        if (withAudio) {
            try {
                MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MicSource.SAMPLE_RATE, 1);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 96_000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                aenc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aenc.start();
            } catch (Exception e) {
                // can't happen after the writer was told to expect sound without breaking it, so fail the start
                throw new Exception("sound encoder couldn't start: " + e.getMessage());
            }
        }

        startNs = System.nanoTime();
        videoSamples = 0;
        framesDrawn = 0;
        running = true;
        videoThread = new Thread(this::videoLoop, "video-enc");
        videoThread.start();
        if (withAudio) {
            this.mic = mic;
            mic.add(this);
        }
        log.log((timelapse ? "Time-lapse" : "Recording") + " to Movies/EyeToyCam/" + fileName
                + (withAudio || timelapse ? "" : " (no sound)"));
    }

    // ------------------------------------------------------------------ drawing

    /** Draws one camera frame into the video. */
    void drawFrame(Bitmap bmp) {
        if (!running) return;
        try {
            Canvas c = inSurface.lockHardwareCanvas();
            try {
                c.drawColor(Color.BLACK);
                long t = System.currentTimeMillis();
                drawCell(c, bmp, dstF, t);
                Effects.drawOverlay(c, width, height, look.effect, t);
                if (burn) drawDetails(c);
            } finally {
                inSurface.unlockCanvasAndPost(c);
            }
            framesDrawn++;
        } catch (Exception e) {
            // a dropped frame is not worth stopping the recording for
        }
    }

    /**
     * Draws several cameras into one picture. bmps[0] is the main camera.
     * Grid: side by side for two, 2x2 for three or four. Picture-in-picture: the main
     * camera fills the frame and the others sit small in the bottom-right corner.
     * A null bitmap (camera not ready yet) leaves its place black.
     */
    void drawComposite(Bitmap[] bmps, String[] labels, boolean pip) {
        if (!running) return;
        try {
            Canvas c = inSurface.lockHardwareCanvas();
            try {
                c.drawColor(Color.BLACK);
                long t = System.currentTimeMillis();
                int n = bmps.length;
                if (pip) {
                    if (bmps[0] != null) drawCell(c, bmps[0], dstF, t);
                    if (burn) label(c, labels[0], dstF);
                    float m = height / 32f, cw = width / 4f, ch = height / 4f;
                    for (int i = 1; i < n; i++) {
                        float right = width - m - (i - 1) * (cw + m);
                        cell.set(right - cw, height - m - ch, right, height - m);
                        c.drawRect(cell, frameEdge);
                        if (bmps[i] != null) drawCell(c, bmps[i], cell, t);
                        if (burn) label(c, labels[i], cell);
                    }
                } else {
                    int cols = 2, rows = n <= 2 ? 1 : 2;
                    float cw = width / (float) cols, ch = height / (float) rows;
                    for (int i = 0; i < n; i++) {
                        int col = i % cols, row = i / cols;
                        cell.set(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch);
                        if (bmps[i] != null) drawCell(c, bmps[i], cell, t);
                        if (burn) label(c, labels[i], cell);
                    }
                }
                Effects.drawOverlay(c, width, height, look.effect, t);
                if (burn) drawDateTime(c);
            } finally {
                inSurface.unlockCanvasAndPost(c);
            }
            framesDrawn++;
        } catch (Exception e) {
            // a dropped frame is not worth stopping the recording for
        }
    }

    /** One camera picture with mirror / flip / zoom / colours / effect, kept inside dst. */
    private void drawCell(Canvas c, Bitmap bmp, RectF dst, long t) {
        c.save();
        c.clipRect(dst);
        float z = look.zoom;
        c.scale((look.mirror ? -1f : 1f) * z, (look.flip ? -1f : 1f) * z, dst.centerX(), dst.centerY());
        paint.setColorFilter(look.filter());
        Effects.drawPicture(c, bmp, dst, paint, look.effect, t);
        c.restore();
    }

    private void label(Canvas c, String s, RectF r) {
        if (s == null || s.isEmpty()) return;
        float m = Math.max(4f, r.height() / 24f);
        float o = Math.max(1f, smallText.getTextSize() / 12f);
        float y = r.top + m + smallText.getTextSize();
        c.drawText(s, r.left + m + o, y + o, smallShadow);
        c.drawText(s, r.left + m, y, smallText);
    }

    /** The on-picture details, drawn after mirror/flip so the words never come out backwards. */
    private void drawDetails(Canvas c) {
        float px = Math.min(width / 640f, height / 480f), m = 16f * px, line = 26f * px;
        text(c, burnLine1, m, m + line * 0.8f);
        text(c, burnLine2, m, m + line * 1.8f);
        drawDateTime(c);
    }

    private void drawDateTime(Canvas c) {
        long sec = System.currentTimeMillis() / 1000;
        if (sec != burnSecond) {
            Date now = new Date();
            burnDate = dateFmt.format(now).toUpperCase(Locale.US);
            burnTime = timeFmt.format(now);
            burnSecond = sec;
        }
        float px = Math.min(width / 640f, height / 480f), m = 16f * px, line = 26f * px;
        text(c, burnDate, m, height - m - line);
        text(c, burnTime, m, height - m);
    }

    private void text(Canvas c, String s, float x, float y) {
        float o = Math.max(1f, height / 240f);
        c.drawText(s, x + o, y + o, textShadow);
        c.drawText(s, x, y, textPaint);
    }

    // ------------------------------------------------------------------ video

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean eosSent = false, configured = false;
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
                MediaFormat f = venc.getOutputFormat();
                ByteBuffer c0 = f.getByteBuffer("csd-0"), c1 = f.getByteBuffer("csd-1");
                if (c0 != null && c1 != null) {
                    writer.setVideoConfig(width, height, Fmp4Writer.stripStartCode(c0), Fmp4Writer.stripStartCode(c1));
                    configured = true;
                }
            } else if (idx >= 0) {
                ByteBuffer out = venc.getOutputBuffer(idx);
                boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                if (config && !configured && out != null && info.size > 0) {
                    // some encoders only hand the SPS/PPS over as a buffer
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    configured = configFromBuffer(out);
                } else if (!config && info.size > 0 && out != null) {
                    if (timelapse) {
                        // every frame lasts exactly 1/outFps, whatever the real gap was
                        info.presentationTimeUs = videoSamples * 1_000_000L / outFps;
                    } else {
                        if (base == Long.MIN_VALUE) {
                            // surface timestamps normally share System.nanoTime's clock; if not, line them up
                            long d = info.presentationTimeUs - startNs / 1000;
                            base = (d >= 0 && d < 10_000_000L) ? startNs / 1000
                                    : info.presentationTimeUs - (System.nanoTime() - startNs) / 1000;
                        }
                        info.presentationTimeUs -= base;
                    }
                    if (info.presentationTimeUs > lastPts) {
                        lastPts = info.presentationTimeUs;
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        try {
                            writer.addVideo(out, info.presentationTimeUs,
                                    (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                            videoSamples++;
                        } catch (IOException e) {
                            writeError(e);
                        }
                    }
                }
                venc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            } else if (eosSent && idx == MediaCodec.INFO_TRY_AGAIN_LATER && videoSamples == 0) {
                break; // nothing was ever recorded
            }
        }
    }

    /** Reads SPS (NAL type 7) and PPS (type 8) out of an Annex B config buffer. */
    private boolean configFromBuffer(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.get(a);
        byte[] sps = null, pps = null;
        ByteBuffer lp = ByteBuffer.wrap(Fmp4Writer.toLengthPrefixed(ByteBuffer.wrap(a)));
        while (lp.remaining() >= 4) {
            int len = lp.getInt();
            if (len <= 0 || len > lp.remaining()) break;
            byte[] nal = new byte[len];
            lp.get(nal);
            int type = nal[0] & 0x1f;
            if (type == 7) sps = nal;
            else if (type == 8) pps = nal;
        }
        if (sps == null || pps == null) return false;
        writer.setVideoConfig(width, height, sps, pps);
        return true;
    }

    private void writeError(IOException e) {
        if (!writeFailed) {
            writeFailed = true;
            log.log("Writing the video failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sound

    /** Called on the mic thread with each chunk of sound. */
    @Override
    public void onPcm(byte[] pcm, int n, long capturedNs) {
        synchronized (audioLock) {
            if (!running || aenc == null || audioEos) return;
            if (audioStartUs < 0) audioStartUs = Math.max(0, (capturedNs - startNs) / 1000);
            // timestamps from the sample count keep the sound smooth and in step with the clock
            long pts = audioStartUs + audioSamples * 1_000_000L / MicSource.SAMPLE_RATE;
            int off = 0;
            while (off < n) {
                int in;
                try { in = aenc.dequeueInputBuffer(10_000); } catch (Exception e) { return; }
                if (in < 0) break;   // encoder is behind: drop the rest of this chunk
                ByteBuffer b = aenc.getInputBuffer(in);
                if (b == null) break;
                b.clear();
                int len = Math.min(n - off, b.remaining());
                b.put(pcm, off, len);
                aenc.queueInputBuffer(in, 0, len, pts, 0);
                off += len;
                audioSamples += len / 2;
                pts = audioStartUs + audioSamples * 1_000_000L / MicSource.SAMPLE_RATE;
            }
            drainAudio(false);
        }
    }

    private void drainAudio(boolean untilEos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int idle = 0;
        while (true) {
            int idx;
            try { idx = aenc.dequeueOutputBuffer(info, untilEos ? 10_000 : 0); } catch (Exception e) { return; }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!untilEos || ++idle > 100) return;
                continue;
            }
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = aenc.getOutputFormat();
                ByteBuffer c0 = f.getByteBuffer("csd-0");
                if (c0 != null) {
                    byte[] asc = new byte[c0.remaining()];
                    c0.duplicate().get(asc);
                    writer.setAudioConfig(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            f.getInteger(MediaFormat.KEY_CHANNEL_COUNT), asc);
                }
                continue;
            }
            if (idx < 0) continue;
            ByteBuffer out = aenc.getOutputBuffer(idx);
            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
            if (config && out != null && info.size > 0) {
                // AudioSpecificConfig handed over as a buffer instead of in the format
                byte[] asc = new byte[info.size];
                out.position(info.offset);
                out.get(asc, 0, info.size);
                writer.setAudioConfig(MicSource.SAMPLE_RATE, 1, asc);
            } else if (info.size > 0 && out != null) {
                out.position(info.offset);
                out.limit(info.offset + info.size);
                try {
                    writer.addAudio(out, info.presentationTimeUs);
                } catch (IOException e) {
                    writeError(e);
                }
            }
            aenc.releaseOutputBuffer(idx, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }

    /** Sends the end of the sound through the encoder so its last bit lands in the file. */
    private void finishAudio() {
        synchronized (audioLock) {
            if (aenc == null || audioEos) return;
            audioEos = true;
            try {
                int in = aenc.dequeueInputBuffer(50_000);
                if (in >= 0) {
                    long pts = audioStartUs < 0 ? 0 : audioStartUs + audioSamples * 1_000_000L / MicSource.SAMPLE_RATE;
                    aenc.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    drainAudio(true);
                }
            } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ stop

    /** Finishes the file. Safe to call more than once. */
    void stop() {
        if (!running && writer == null) return;
        long durMs = elapsedMs();
        running = false;
        if (mic != null) mic.remove(this);
        mic = null;
        join(videoThread);
        videoThread = null;
        finishAudio();

        boolean ok = false;
        try {
            if (writer != null && videoSamples > 0) {
                writer.close();
                ok = !writeFailed || writer.fragments() > 0;
            }
        } catch (Exception e) {
            log.log("Finishing the video failed: " + e.getMessage());
            ok = writer != null && writer.fragments() > 0;   // the pieces already written still play
        }
        long fragments = writer == null ? 0 : writer.fragments();
        writer = null;
        try { if (venc != null) venc.stop(); } catch (Exception ignored) { }
        try { if (venc != null) venc.release(); } catch (Exception ignored) { }
        try { if (inSurface != null) inSurface.release(); } catch (Exception ignored) { }
        venc = null;
        synchronized (audioLock) {
            if (aenc != null) {
                try { aenc.stop(); } catch (Exception ignored) { }
                try { aenc.release(); } catch (Exception ignored) { }
                aenc = null;
            }
        }
        try { if (pfd != null) pfd.close(); } catch (Exception ignored) { }
        pfd = null;

        if (ok && fragments > 0) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            try { cr.update(uri, v, null, null); } catch (Exception ignored) { }
            saved = true;
            if (timelapse) {
                log.log(String.format(Locale.US, "Saved time-lapse Movies/EyeToyCam/%s (%d frames, %.1f s of video).",
                        fileName, videoSamples, videoSamples / (double) outFps));
            } else {
                log.log(String.format(Locale.US, "Saved video Movies/EyeToyCam/%s (%d s, %d frames).",
                        fileName, durMs / 1000, videoSamples));
            }
        } else {
            try { if (uri != null) cr.delete(uri, null, null); } catch (Exception ignored) { }
            log.log("Recording " + fileName + " had no frames, so nothing was saved.");
        }
        if (uri != null) ACTIVE.remove(uri.toString());
    }

    private static void join(Thread t) {
        if (t == null) return;
        try { t.join(3000); } catch (InterruptedException ignored) { }
    }

    // ------------------------------------------------------------------ rescue after a crash

    /**
     * Finds videos that were still being recorded when the app was killed (Android keeps
     * them hidden as "pending"), tidies them and puts them in the gallery. Worker thread.
     * @return how many were rescued
     */
    static int recoverUnfinished(Context ctx, Logger log) {
        if (Build.VERSION.SDK_INT < 30) return 0;
        ContentResolver cr = ctx.getContentResolver();
        Bundle q = new Bundle();
        q.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
        q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?");
        q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{Environment.DIRECTORY_MOVIES + "/EyeToyCam%"});
        int rescued = 0;
        try (Cursor c = cr.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME}, q, null)) {
            if (c == null) return 0;
            while (c.moveToNext()) {
                Uri u = android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
                String name = c.getString(1);
                if (ACTIVE.contains(u.toString())) continue;
                Fmp4Fixup.Result r = null;
                try (ParcelFileDescriptor p = cr.openFileDescriptor(u, "rw")) {
                    if (p != null) {
                        FdIo io = new FdIo(p.getFileDescriptor());
                        r = Fmp4Fixup.finish(io);
                        io.sync();
                    }
                } catch (Exception e) {
                    log.log("Couldn't check unfinished video " + name + ": " + e.getMessage());
                }
                if (r != null && r.durationMs > 0) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Video.Media.IS_PENDING, 0);
                    try {
                        cr.update(u, v, null, null);
                        rescued++;
                        log.log(String.format(Locale.US, "Rescued unfinished video Movies/EyeToyCam/%s (%.1f s).",
                                name, r.durationMs / 1000.0));
                    } catch (Exception e) {
                        log.log("Couldn't publish rescued video " + name + ": " + e.getMessage());
                    }
                } else if (r != null) {
                    try { cr.delete(u, null, null); } catch (Exception ignored) { }
                    log.log("Unfinished video " + name + " had nothing playable, so it was removed.");
                }
            }
        } catch (Exception e) {
            log.log("Checking for unfinished videos failed: " + e.getMessage());
        }
        return rescued;
    }

    /** Reads and writes the file straight through its descriptor. */
    private static final class FdIo implements Fmp4Writer.Io {
        private final FileDescriptor fd;
        FdIo(FileDescriptor fd) { this.fd = fd; }

        @Override public void write(ByteBuffer b, long pos) throws IOException {
            int before = b.position();
            try {
                int n = Os.pwrite(fd, b, pos);
                if (b.position() == before) b.position(before + n);
            } catch (android.system.ErrnoException e) { throw new IOException(e.getMessage()); }
        }
        @Override public int read(ByteBuffer b, long pos) throws IOException {
            int before = b.position();
            try {
                int n = Os.pread(fd, b, pos);
                if (n > 0 && b.position() == before) b.position(before + n);
                return n;
            } catch (android.system.ErrnoException e) { throw new IOException(e.getMessage()); }
        }
        @Override public long size() throws IOException {
            try { return Os.fstat(fd).st_size; } catch (android.system.ErrnoException e) { throw new IOException(e.getMessage()); }
        }
        @Override public void truncate(long size) throws IOException {
            try { Os.ftruncate(fd, size); } catch (android.system.ErrnoException e) { throw new IOException(e.getMessage()); }
        }
        @Override public void sync() throws IOException {
            try { Os.fdatasync(fd); } catch (android.system.ErrnoException e) { throw new IOException(e.getMessage()); }
        }
    }
}
