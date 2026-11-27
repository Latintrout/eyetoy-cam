package com.latintrout.eyetoycam;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Tidies a fragmented MP4 written by {@link Fmp4Writer}: cuts off a half-written
 * last piece (left by a crash) and fills in the total length so galleries show it.
 * Used both when a recording stops normally and when an unfinished one is rescued.
 * Plain Java so it can be tested on a computer.
 */
final class Fmp4Fixup {
    private Fmp4Fixup() { }

    /** What was found. durationMs is -1 when the file has no usable video at all. */
    static final class Result {
        long durationMs = -1;
        int fragments;
        long cutBytes;
    }

    private static final class Trk { int id; int timescale; long mdhdDurAt = -1, tkhdDurAt = -1; boolean mdhdV1, tkhdV1; }

    static Result finish(Fmp4Writer.Io io) throws IOException {
        Result r = new Result();
        long size = io.size();
        long p = 0, moovAt = -1, moovSize = 0, goodEnd = 0, pendingMoof = -1;
        Map<Integer, Long> ends = new HashMap<>();
        Map<Integer, Long> moofEnds = null;
        while (p + 8 <= size) {
            ByteBuffer h = read(io, p, 16);
            if (h.remaining() < 8) break;
            long bs = h.getInt() & 0xffffffffL;
            String type = type(h.getInt());
            int hdr = 8;
            if (bs == 1) {
                if (h.remaining() < 8) break;
                bs = h.getLong();
                hdr = 16;
            } else if (bs == 0) {
                bs = size - p;
            }
            if (bs < hdr || p + bs > size) break;        // half-written box: stop here
            if (type.equals("moov")) {
                moovAt = p;
                moovSize = bs;
                goodEnd = p + bs;
            } else if (type.equals("moof")) {
                moofEnds = parseMoof(read(io, p + hdr, (int) Math.min(bs - hdr, 1 << 20)));
                pendingMoof = p;
            } else if (type.equals("mdat")) {
                if (pendingMoof >= 0 && moofEnds != null) {
                    for (Map.Entry<Integer, Long> e : moofEnds.entrySet()) {
                        Long old = ends.get(e.getKey());
                        if (old == null || e.getValue() > old) ends.put(e.getKey(), e.getValue());
                    }
                    r.fragments++;
                }
                pendingMoof = -1;
                moofEnds = null;
                goodEnd = p + bs;
            } else if (pendingMoof < 0) {
                goodEnd = p + bs;     // any other complete box (ftyp, free...)
            }
            p += bs;
        }
        if (goodEnd < size) {
            r.cutBytes = size - goodEnd;
            io.truncate(goodEnd);
        }
        if (moovAt < 0 || r.fragments == 0) return r;

        // ---- fill in the lengths inside moov ----
        ByteBuffer moov = read(io, moovAt, (int) moovSize);
        long movieTs = 1000, mvhdDurAt = -1, mehdDurAt = -1;
        boolean mvhdV1 = false, mehdV1 = false;
        java.util.List<Trk> tracks = new java.util.ArrayList<>();
        int q = 8;
        while (q + 8 <= moovSize) {
            int bs = moov.getInt(q);
            String t = type(moov.getInt(q + 4));
            if (bs < 8) break;
            if (t.equals("mvhd")) {
                mvhdV1 = moov.get(q + 8) == 1;
                movieTs = moov.getInt(q + (mvhdV1 ? 28 : 20)) & 0xffffffffL;
                mvhdDurAt = q + (mvhdV1 ? 32 : 24);
            } else if (t.equals("trak")) {
                tracks.add(parseTrak(moov, q, bs));
            } else if (t.equals("mvex")) {
                int k = q + 8;
                while (k + 8 <= q + bs) {
                    int cs = moov.getInt(k);
                    if (cs < 8) break;
                    if (type(moov.getInt(k + 4)).equals("mehd")) {
                        mehdV1 = moov.get(k + 8) == 1;
                        mehdDurAt = k + 12;
                    }
                    k += cs;
                }
            }
            q += bs;
        }
        double longest = 0;
        for (Trk tr : tracks) {
            Long end = ends.get(tr.id);
            if (end == null || tr.timescale <= 0) continue;
            double sec = end / (double) tr.timescale;
            longest = Math.max(longest, sec);
            if (tr.mdhdDurAt >= 0) put(io, moovAt + tr.mdhdDurAt, tr.mdhdV1, end);
            if (tr.tkhdDurAt >= 0) put(io, moovAt + tr.tkhdDurAt, tr.tkhdV1, Math.round(sec * movieTs));
        }
        long movieDur = Math.round(longest * movieTs);
        if (mvhdDurAt >= 0) put(io, moovAt + mvhdDurAt, mvhdV1, movieDur);
        if (mehdDurAt >= 0) put(io, moovAt + mehdDurAt, mehdV1, movieDur);
        r.durationMs = Math.round(longest * 1000);
        return r;
    }

    private static Trk parseTrak(ByteBuffer m, int at, int size) {
        Trk tr = new Trk();
        int k = at + 8;
        while (k + 8 <= at + size) {
            int cs = m.getInt(k);
            String t = type(m.getInt(k + 4));
            if (cs < 8) break;
            if (t.equals("tkhd")) {
                tr.tkhdV1 = m.get(k + 8) == 1;
                tr.id = m.getInt(k + (tr.tkhdV1 ? 28 : 20));
                tr.tkhdDurAt = k + (tr.tkhdV1 ? 36 : 28);
            } else if (t.equals("mdia")) {
                int j = k + 8;
                while (j + 8 <= k + cs) {
                    int ds = m.getInt(j);
                    if (ds < 8) break;
                    if (type(m.getInt(j + 4)).equals("mdhd")) {
                        tr.mdhdV1 = m.get(j + 8) == 1;
                        tr.timescale = m.getInt(j + (tr.mdhdV1 ? 28 : 20));
                        tr.mdhdDurAt = j + (tr.mdhdV1 ? 32 : 24);
                    }
                    j += ds;
                }
            }
            k += cs;
        }
        return tr;
    }

    /** End time (track ticks) of each track in one moof's body. */
    private static Map<Integer, Long> parseMoof(ByteBuffer b) {
        Map<Integer, Long> out = new HashMap<>();
        int n = b.remaining();
        int k = 0;
        while (k + 8 <= n) {
            int cs = b.getInt(k);
            if (cs < 8 || k + cs > n) break;
            if (type(b.getInt(k + 4)).equals("traf")) {
                int id = -1;
                long base = 0, defDur = 0, total = 0;
                int j = k + 8;
                while (j + 8 <= k + cs) {
                    int ds = b.getInt(j);
                    if (ds < 8) break;
                    String t = type(b.getInt(j + 4));
                    int flags = b.getInt(j + 8) & 0xffffff;
                    int v = b.get(j + 8) & 0xff;
                    if (t.equals("tfhd")) {
                        id = b.getInt(j + 12);
                        int o = j + 16;
                        if ((flags & 0x01) != 0) o += 8;
                        if ((flags & 0x02) != 0) o += 4;
                        if ((flags & 0x08) != 0) defDur = b.getInt(o) & 0xffffffffL;
                    } else if (t.equals("tfdt")) {
                        base = v == 1 ? b.getLong(j + 12) : b.getInt(j + 12) & 0xffffffffL;
                    } else if (t.equals("trun")) {
                        int count = b.getInt(j + 12);
                        int o = j + 16;
                        if ((flags & 0x01) != 0) o += 4;
                        if ((flags & 0x04) != 0) o += 4;
                        int per = 0;
                        boolean hasDur = (flags & 0x100) != 0;
                        if (hasDur) per += 4;
                        if ((flags & 0x200) != 0) per += 4;
                        if ((flags & 0x400) != 0) per += 4;
                        if ((flags & 0x800) != 0) per += 4;
                        for (int s = 0; s < count && o + per <= j + ds; s++, o += per) {
                            total += hasDur ? (b.getInt(o) & 0xffffffffL) : defDur;
                        }
                    }
                    j += ds;
                }
                if (id >= 0) {
                    long end = base + total;
                    Long old = out.get(id);
                    if (old == null || end > old) out.put(id, end);
                }
            }
            k += cs;
        }
        return out;
    }

    private static void put(Fmp4Writer.Io io, long at, boolean v1, long value) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(v1 ? 8 : 4);
        if (v1) b.putLong(value); else b.putInt((int) Math.min(value, 0xffffffffL));
        b.flip();
        while (b.hasRemaining()) io.write(b, at + b.position());
    }

    private static ByteBuffer read(Fmp4Writer.Io io, long at, int len) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(len);
        while (b.hasRemaining()) {
            int n = io.read(b, at + b.position());
            if (n <= 0) break;
        }
        b.flip();
        return b;
    }

    private static String type(int t) {
        return new String(new char[]{(char) ((t >> 24) & 0xff), (char) ((t >> 16) & 0xff),
                (char) ((t >> 8) & 0xff), (char) (t & 0xff)});
    }
}
