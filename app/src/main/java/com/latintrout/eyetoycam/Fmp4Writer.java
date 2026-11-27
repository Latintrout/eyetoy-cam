package com.latintrout.eyetoycam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes a "fragmented" MP4: a small header first, then the video in pieces of
 * about one second (each a moof box saying what's in it, then an mdat box with the
 * pictures and sound). Every piece is complete on disk as soon as it is written,
 * so if the app is killed mid-recording, everything up to the last second or so
 * still plays. Android's own MediaMuxer only writes the index at the very end,
 * which is why a killed recording used to be unplayable.
 *
 * H.264 video (track 1) and optional AAC sound (track 2). Thread safe.
 * Plain Java (no Android classes) so it can be tested on a computer.
 */
final class Fmp4Writer {

    /** Where the bytes go: a file opened for reading and writing. */
    interface Io {
        void write(ByteBuffer b, long pos) throws IOException;
        int read(ByteBuffer b, long pos) throws IOException;
        long size() throws IOException;
        void truncate(long size) throws IOException;
        /** Pushes written data to the storage itself (survives even a phone crash). */
        void sync() throws IOException;
    }

    static final int VIDEO_TIMESCALE = 90_000;
    /** Start a new piece at the first key frame after this much video. */
    private static final long FRAGMENT_US = 900_000;
    /** ...or after this much, key frame or not (also covers sound while the picture stalls). */
    private static final long MAX_FRAGMENT_US = 2_000_000;

    private static final class Sample {
        final byte[] data;
        final long ptsUs;
        final boolean key;
        Sample(byte[] d, long p, boolean k) { data = d; ptsUs = p; key = k; }
    }

    private final Io io;
    /** Cleared if the sound never shows up, so the picture is still saved. */
    private boolean hasAudio;
    private long pos;
    private boolean headerWritten, closed;
    private int seq;
    private long fragments;

    // video config
    private int width, height;
    private byte[] sps, pps;
    // audio config
    private int sampleRate, channels;
    private byte[] asc;

    private final List<Sample> vq = new ArrayList<>();
    private final List<Sample> aq = new ArrayList<>();
    /** Decode time (in track ticks) where the next piece of each track starts; -1 until known. */
    private long vNextTicks = -1, aNextTicks = -1;
    private long lastVideoDurTicks = VIDEO_TIMESCALE / 30;

    Fmp4Writer(Io io, boolean hasAudio) {
        this.io = io;
        this.hasAudio = hasAudio;
    }

    synchronized long bytesWritten() { return pos; }
    synchronized long fragments() { return fragments; }

    /** SPS and PPS without start codes. */
    synchronized void setVideoConfig(int w, int h, byte[] sps, byte[] pps) {
        width = w;
        height = h;
        this.sps = sps;
        this.pps = pps;
    }

    /** AAC AudioSpecificConfig (MediaCodec's csd-0). */
    synchronized void setAudioConfig(int sampleRate, int channels, byte[] asc) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.asc = asc;
    }

    private boolean ready() { return sps != null && pps != null && (!hasAudio || asc != null); }

    /** One encoded picture, as MediaCodec gives it (Annex B start codes). */
    synchronized void addVideo(ByteBuffer annexB, long ptsUs, boolean key) throws IOException {
        if (closed) return;
        if (!headerWritten && hasAudio && asc == null && !vq.isEmpty() && ptsUs - vq.get(0).ptsUs > 3_000_000) {
            hasAudio = false;   // no sound after 3 s: carry on with picture only
            aq.clear();
        }
        if (!vq.isEmpty()) {
            long span = ptsUs - vq.get(0).ptsUs;
            if ((key && span >= FRAGMENT_US) || span >= MAX_FRAGMENT_US) flush(ptsUs);
        }
        vq.add(new Sample(toLengthPrefixed(annexB), ptsUs, key));
    }

    /** One encoded AAC frame. */
    synchronized void addAudio(ByteBuffer raw, long ptsUs) throws IOException {
        if (closed || !hasAudio) return;
        byte[] d = new byte[raw.remaining()];
        raw.get(d);
        aq.add(new Sample(d, ptsUs, true));
        // keep the sound flowing to disk even if the picture stops coming
        if (aq.size() > 1 && ptsUs - aq.get(0).ptsUs >= MAX_FRAGMENT_US
                && (vq.isEmpty() || ptsUs - vq.get(0).ptsUs >= MAX_FRAGMENT_US)) {
            flush(-1);
        }
    }

    /** Writes what's left and fills in the total length. The Io is left open. */
    synchronized void close() throws IOException {
        if (closed) return;
        if (!headerWritten && hasAudio && asc == null) {
            hasAudio = false;   // the sound never started: save the picture alone
            aq.clear();
        }
        flush(-1);
        closed = true;
        if (headerWritten) {
            io.sync();
            Fmp4Fixup.finish(io);
            io.sync();
        }
    }

    // ------------------------------------------------------------------ fragments

    /** nextVideoPts: the picture that will start the next piece, or -1 if not known. */
    private void flush(long nextVideoPts) throws IOException {
        if (!ready()) return;          // keep collecting until both formats are known
        if (vq.isEmpty() && aq.isEmpty()) return;
        if (!headerWritten) writeHeader();

        // durations, in each track's own ticks
        long[] vDur = new long[vq.size()];
        long vBase = -1;
        if (!vq.isEmpty()) {
            vBase = vNextTicks >= 0 ? vNextTicks : vTicks(vq.get(0).ptsUs);
            long t = vBase;
            for (int i = 0; i < vq.size(); i++) {
                long end;
                if (i + 1 < vq.size()) end = vTicks(vq.get(i + 1).ptsUs);
                else if (nextVideoPts >= 0) end = vTicks(nextVideoPts);
                else end = t + lastVideoDurTicks;
                long d = Math.max(1, end - t);
                vDur[i] = d;
                t += d;
            }
            lastVideoDurTicks = vDur[vDur.length - 1];
            vNextTicks = t;
        }
        long[] aDur = new long[aq.size()];
        long aBase = -1;
        if (!aq.isEmpty()) {
            aBase = aNextTicks >= 0 ? aNextTicks : aTicks(aq.get(0).ptsUs);
            long t = aBase;
            for (int i = 0; i < aq.size(); i++) {
                long d = i + 1 < aq.size() ? Math.max(1, aTicks(aq.get(i + 1).ptsUs) - t) : 1024;
                // AAC frames are always 1024 samples; snap tiny rounding wobble back to that
                if (Math.abs(d - 1024) <= 2) d = 1024;
                aDur[i] = d;
                t += d;
            }
            aNextTicks = t;
        }

        seq++;
        // moof first with data offsets left at 0, then patch once its size is known
        Box moof = new Box("moof");
        Box mfhd = new Box("mfhd").full(0, 0);
        mfhd.i32(seq);
        moof.add(mfhd);
        Box vtraf = null, atraf = null;
        if (!vq.isEmpty()) {
            vtraf = traf(1, vBase, vq, vDur, true);
            moof.add(vtraf);
        }
        if (!aq.isEmpty()) {
            atraf = traf(2, aBase, aq, aDur, false);
            moof.add(atraf);
        }
        byte[] moofBytes = moof.bytes();
        long vBytes = 0, aBytes = 0;
        for (Sample s : vq) vBytes += s.data.length;
        for (Sample s : aq) aBytes += s.data.length;
        // data_offset is counted from the start of moof
        int dataStart = moofBytes.length + 8;
        ByteBuffer mb = ByteBuffer.wrap(moofBytes);
        if (vtraf != null) mb.putInt(vtraf.dataOffsetAt, dataStart);
        if (atraf != null) mb.putInt(atraf.dataOffsetAt, (int) (dataStart + vBytes));

        long mdatSize = 8 + vBytes + aBytes;
        ByteBuffer out = ByteBuffer.allocate((int) (moofBytes.length + mdatSize));
        out.put(moofBytes);
        out.putInt((int) mdatSize);
        out.put(new byte[]{'m', 'd', 'a', 't'});
        for (Sample s : vq) out.put(s.data);
        for (Sample s : aq) out.put(s.data);
        out.flip();
        writeAll(out);
        vq.clear();
        aq.clear();
        fragments++;
        io.sync();
    }

    private Box traf(int trackId, long base, List<Sample> q, long[] dur, boolean video) {
        Box traf = new Box("traf");
        Box tfhd = new Box("tfhd").full(0, 0x020000);   // default-base-is-moof
        tfhd.i32(trackId);
        traf.add(tfhd);
        Box tfdt = new Box("tfdt").full(1, 0);
        tfdt.i64(base);
        traf.add(tfdt);
        int flags = 0x000001 | 0x000100 | 0x000200 | (video ? 0x000400 : 0);
        Box trun = new Box("trun").full(0, flags);
        trun.i32(q.size());
        int offAt = trun.size();
        trun.i32(0);   // data_offset, patched later
        for (int i = 0; i < q.size(); i++) {
            Sample s = q.get(i);
            trun.i32((int) dur[i]);
            trun.i32(s.data.length);
            if (video) trun.i32(s.key ? 0x02000000 : 0x01010000);
        }
        traf.add(trun);
        // where the data_offset field ends up inside the finished moof
        traf.pendingOffset = trun;
        traf.pendingOffsetIn = offAt;
        return traf;
    }

    private long vTicks(long us) { return Math.round(us * (VIDEO_TIMESCALE / 1_000_000.0)); }
    private long aTicks(long us) { return Math.round(us * (sampleRate / 1_000_000.0)); }

    private void writeAll(ByteBuffer b) throws IOException {
        while (b.hasRemaining()) {
            int n = b.remaining();
            io.write(b, pos);
            pos += n - b.remaining();
        }
    }

    // ------------------------------------------------------------------ header

    private void writeHeader() throws IOException {
        Box ftyp = new Box("ftyp");
        ftyp.str("isom").i32(0x200).str("isom").str("iso2").str("avc1").str("mp41").str("iso5").str("iso6");

        long now = System.currentTimeMillis() / 1000 + 2082844800L;   // seconds since 1904
        Box moov = new Box("moov");
        Box mvhd = new Box("mvhd").full(1, 0);
        mvhd.i64(now).i64(now).i32(1000).i64(0);
        mvhd.i32(0x00010000).i16(0x0100).zeros(10);
        matrix(mvhd);
        mvhd.zeros(24).i32(hasAudio ? 3 : 2);
        moov.add(mvhd);
        moov.add(videoTrak(now));
        if (hasAudio) moov.add(audioTrak(now));
        Box mvex = new Box("mvex");
        Box mehd = new Box("mehd").full(1, 0);
        mehd.i64(0);
        mvex.add(mehd);
        mvex.add(trex(1, 0x01010000));
        if (hasAudio) mvex.add(trex(2, 0x02000000));
        moov.add(mvex);

        ByteBuffer b = ByteBuffer.allocate(ftyp.total() + moov.total());
        b.put(ftyp.bytes()).put(moov.bytes()).flip();
        writeAll(b);
        headerWritten = true;
    }

    private static Box trex(int id, int flags) {
        Box t = new Box("trex").full(0, 0);
        t.i32(id).i32(1).i32(0).i32(0).i32(flags);
        return t;
    }

    private static void matrix(Box b) {
        b.i32(0x00010000).i32(0).i32(0).i32(0).i32(0x00010000).i32(0).i32(0).i32(0).i32(0x40000000);
    }

    private static Box tkhd(long now, int id, boolean audio, int w, int h) {
        Box t = new Box("tkhd").full(1, 7);
        t.i64(now).i64(now).i32(id).i32(0).i64(0).zeros(8);
        t.i16(0).i16(audio ? 1 : 0).i16(audio ? 0x0100 : 0).i16(0);
        matrix(t);
        t.i32(w << 16).i32(h << 16);
        return t;
    }

    private static Box mdhd(long now, int timescale) {
        Box m = new Box("mdhd").full(1, 0);
        m.i64(now).i64(now).i32(timescale).i64(0).i16(0x55C4).i16(0);   // language "und"
        return m;
    }

    private static Box hdlr(String type, String name) {
        Box h = new Box("hdlr").full(0, 0);
        h.i32(0).str(type).zeros(12);
        for (char c : name.toCharArray()) h.i8(c);
        h.i8(0);
        return h;
    }

    private static Box dinf() {
        Box dinf = new Box("dinf");
        Box dref = new Box("dref").full(0, 0);
        dref.i32(1);
        dref.add(new Box("url ").full(0, 1));
        dinf.add(dref);
        return dinf;
    }

    private static void emptyTables(Box stbl) {
        Box stts = new Box("stts").full(0, 0); stts.i32(0); stbl.add(stts);
        Box stsc = new Box("stsc").full(0, 0); stsc.i32(0); stbl.add(stsc);
        Box stsz = new Box("stsz").full(0, 0); stsz.i32(0).i32(0); stbl.add(stsz);
        Box stco = new Box("stco").full(0, 0); stco.i32(0); stbl.add(stco);
    }

    private Box videoTrak(long now) {
        Box trak = new Box("trak");
        trak.add(tkhd(now, 1, false, width, height));
        Box mdia = new Box("mdia");
        mdia.add(mdhd(now, VIDEO_TIMESCALE));
        mdia.add(hdlr("vide", "VideoHandler"));
        Box minf = new Box("minf");
        Box vmhd = new Box("vmhd").full(0, 1);
        vmhd.zeros(8);
        minf.add(vmhd);
        minf.add(dinf());
        Box stbl = new Box("stbl");
        Box stsd = new Box("stsd").full(0, 0);
        stsd.i32(1);
        Box avc1 = new Box("avc1");
        avc1.zeros(6).i16(1).zeros(16).i16(width).i16(height).i32(0x00480000).i32(0x00480000).i32(0).i16(1);
        avc1.zeros(32).i16(0x18).i16(0xFFFF);
        Box avcC = new Box("avcC");
        int profile = sps.length > 1 ? sps[1] & 0xff : 66;
        avcC.i8(1).i8(profile).i8(sps.length > 2 ? sps[2] & 0xff : 0).i8(sps.length > 3 ? sps[3] & 0xff : 30);
        avcC.i8(0xFF).i8(0xE1).i16(sps.length).raw(sps).i8(1).i16(pps.length).raw(pps);
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            avcC.i8(0xFD).i8(0xF8).i8(0xF8).i8(0);   // 4:2:0, 8-bit, no SPS extensions
        }
        avc1.add(avcC);
        stsd.add(avc1);
        stbl.add(stsd);
        emptyTables(stbl);
        minf.add(stbl);
        mdia.add(minf);
        trak.add(mdia);
        return trak;
    }

    private Box audioTrak(long now) {
        Box trak = new Box("trak");
        trak.add(tkhd(now, 2, true, 0, 0));
        Box mdia = new Box("mdia");
        mdia.add(mdhd(now, sampleRate));
        mdia.add(hdlr("soun", "SoundHandler"));
        Box minf = new Box("minf");
        Box smhd = new Box("smhd").full(0, 0);
        smhd.i32(0);
        minf.add(smhd);
        minf.add(dinf());
        Box stbl = new Box("stbl");
        Box stsd = new Box("stsd").full(0, 0);
        stsd.i32(1);
        Box mp4a = new Box("mp4a");
        mp4a.zeros(6).i16(1).zeros(8).i16(channels).i16(16).i16(0).i16(0).i32(sampleRate << 16);
        Box esds = new Box("esds").full(0, 0);
        // ES_Descriptor > DecoderConfigDescriptor > DecoderSpecificInfo (the ASC), then SLConfig
        int dsi = 2 + asc.length;
        int dcd = 2 + 13 + dsi;
        int es = 2 + 3 + dcd + 3;
        esds.i8(0x03).i8(es - 2).i16(0).i8(0);
        esds.i8(0x04).i8(dcd - 2).i8(0x40).i8(0x15).i8(0).i16(0).i32(128_000).i32(96_000);
        esds.i8(0x05).i8(asc.length).raw(asc);
        esds.i8(0x06).i8(1).i8(0x02);
        mp4a.add(esds);
        stsd.add(mp4a);
        stbl.add(stsd);
        emptyTables(stbl);
        minf.add(stbl);
        mdia.add(minf);
        trak.add(mdia);
        return trak;
    }

    // ------------------------------------------------------------------ helpers

    /** Annex B (00 00 01 start codes) to 4-byte length prefixes, as MP4 wants. */
    static byte[] toLengthPrefixed(ByteBuffer in) {
        byte[] a = new byte[in.remaining()];
        in.get(a);
        List<int[]> nals = new ArrayList<>();
        int i = 0, n = a.length, start = -1;
        while (i + 2 < n) {
            if (a[i] == 0 && a[i + 1] == 0 && a[i + 2] == 1) {
                if (start >= 0) nals.add(new int[]{start, trimEnd(a, start, i)});
                i += 3;
                start = i;
            } else {
                i++;
            }
        }
        if (start < 0) {
            // already length-prefixed or a bare NAL: treat the whole buffer as one NAL
            ByteBuffer out = ByteBuffer.allocate(4 + n);
            out.putInt(n).put(a);
            return out.array();
        }
        nals.add(new int[]{start, n});
        int total = 0;
        for (int[] r : nals) total += 4 + (r[1] - r[0]);
        ByteBuffer out = ByteBuffer.allocate(total);
        for (int[] r : nals) {
            out.putInt(r[1] - r[0]);
            out.put(a, r[0], r[1] - r[0]);
        }
        return out.array();
    }

    /** End of a NAL that is followed by a start code (drops the zero bytes before it). */
    private static int trimEnd(byte[] a, int start, int i) {
        int e = i;
        while (e > start && a[e - 1] == 0) e--;
        return e;
    }

    /** Strips a leading start code from MediaCodec's csd-0 / csd-1. */
    static byte[] stripStartCode(ByteBuffer b) {
        byte[] a = new byte[b.remaining()];
        b.duplicate().get(a);
        int s = 0;
        if (a.length >= 4 && a[0] == 0 && a[1] == 0 && a[2] == 0 && a[3] == 1) s = 4;
        else if (a.length >= 3 && a[0] == 0 && a[1] == 0 && a[2] == 1) s = 3;
        byte[] r = new byte[a.length - s];
        System.arraycopy(a, s, r, 0, r.length);
        return r;
    }

    /** A box being built: 8-byte header, then its own fields, then child boxes. */
    private static final class Box {
        private final String type;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private final List<Box> kids = new ArrayList<>();
        // for trun data_offset patching
        Box pendingOffset;
        int pendingOffsetIn = -1;
        int dataOffsetAt = -1;

        Box(String type) { this.type = type; }

        Box full(int version, int flags) { i8(version); i8(flags >> 16); i8(flags >> 8); i8(flags); return this; }
        Box i8(int v) { body.write(v & 0xff); return this; }
        Box i16(int v) { i8(v >> 8); return i8(v); }
        Box i32(int v) { i16(v >> 16); return i16(v); }
        Box i64(long v) { i32((int) (v >> 32)); return i32((int) v); }
        Box str(String s) { for (int k = 0; k < 4; k++) i8(s.charAt(k)); return this; }
        Box zeros(int n) { for (int k = 0; k < n; k++) i8(0); return this; }
        Box raw(byte[] d) { body.write(d, 0, d.length); return this; }
        Box add(Box b) { kids.add(b); return this; }
        int size() { return body.size(); }

        int total() {
            int t = 8 + body.size();
            for (Box k : kids) t += k.total();
            return t;
        }

        byte[] bytes() {
            ByteBuffer b = ByteBuffer.allocate(total());
            writeTo(b);
            return b.array();
        }

        private void writeTo(ByteBuffer b) {
            b.putInt(total());
            for (int k = 0; k < 4; k++) b.put((byte) type.charAt(k));
            b.put(body.toByteArray());
            for (Box k : kids) {
                int at = b.position();
                k.writeTo(b);
                // a traf remembers where its trun's data_offset field landed (moof is written at 0)
                if (k.pendingOffset != null) k.dataOffsetAt = k.locate(at);
            }
        }

        /** Absolute position of this traf's trun data_offset field, given where the traf starts. */
        private int locate(int trafAt) {
            int p = trafAt + 8 + body.size();
            for (Box k : kids) {
                if (k == pendingOffset) return p + 8 + pendingOffsetIn;
                p += k.total();
            }
            return -1;
        }
    }
}
