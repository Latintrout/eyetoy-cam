package com.latintrout.eyetoycam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wi-Fi webcam: a tiny web server on the phone that sends the EyeToy pictures
 * (MJPEG, the camera's own JPEG frames, so no re-encoding) and the mic sound
 * (a never-ending WAV) to any PC on the same network, e.g. OBS.
 *
 * Addresses:
 *   /           page with every camera and the sound, for a normal web browser
 *   /view       the main camera alone, filling the page (OBS Browser source)
 *   /viewN      camera N alone (1 to 4)
 *   /cam, /camN the raw MJPEG stream (OBS Media source, VLC)
 *   /audio      the sound as a WAV stream (OBS Media source)
 *   /snapN.jpg  one picture
 */
final class WifiServer {
    interface Cams {
        /** The main camera, or null. */
        Cam main();
        /** Camera N (1 to 4), or null when that one isn't running. */
        Cam cam(int number);
        /** Name shown for camera N. */
        String label(int number);
    }

    static final int PORT = 8080;
    static final int SAMPLE_RATE = 44100;
    private static final String BOUNDARY = "eyetoyframe";
    private static final int MAX_CLIENTS = 12;

    /** The running server, so the recorder can share its mic sound with it. */
    private static volatile WifiServer current;
    /** True while a recording holds the microphone (it then feeds us its sound). */
    static volatile boolean micBusy;

    private final Context ctx;
    private final Cams cams;
    private final EyeToyDriver.Logger log;
    private volatile boolean running;
    private volatile boolean sound;
    private ServerSocket server;
    private Thread acceptThread, micThread;
    private int port;
    private WifiManager.WifiLock wifiLock;
    private final List<Socket> sockets = Collections.synchronizedList(new ArrayList<>());
    private final List<ArrayBlockingQueue<byte[]>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger videoViewers = new AtomicInteger();
    private boolean warnedMic;

    WifiServer(Context ctx, Cams cams, EyeToyDriver.Logger log) {
        this.ctx = ctx.getApplicationContext();
        this.cams = cams;
        this.log = log;
    }

    boolean isRunning() { return running; }
    int port() { return port; }
    int videoViewers() { return videoViewers.get(); }
    int soundListeners() { return listeners.size(); }
    void setSound(boolean on) { sound = on; }

    /** Opens the server on port 8080 (or the next free one). Returns false if no port could be opened. */
    boolean start(boolean withSound) {
        if (running) return true;
        sound = withSound;
        for (int p = PORT; p < PORT + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                break;
            } catch (Exception ignored) { }
        }
        if (server == null) {
            log.log("Wi-Fi webcam couldn't open a network port.");
            return false;
        }
        running = true;
        current = this;
        try {
            WifiManager wm = ctx.getSystemService(WifiManager.class);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "eyetoycam:wifi");
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
        acceptThread = new Thread(this::acceptLoop, "wifi-accept");
        acceptThread.start();
        micThread = new Thread(this::micLoop, "wifi-mic");
        micThread.start();
        log.log("Wi-Fi webcam on: " + baseUrl());
        return true;
    }

    void stop() {
        if (!running) return;
        running = false;
        if (current == this) current = null;
        try { server.close(); } catch (Exception ignored) { }
        synchronized (sockets) {
            for (Socket s : sockets) try { s.close(); } catch (Exception ignored) { }
            sockets.clear();
        }
        listeners.clear();
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wifiLock = null;
        server = null;
        log.log("Wi-Fi webcam off.");
    }

    /** e.g. http://192.168.1.23:8080, or null when the phone has no local network address. */
    String baseUrl() {
        String ip = ipAddress();
        return ip == null ? null : "http://" + ip + ":" + port;
    }

    /** The phone's address on the local network (Wi-Fi first, then hotspot or anything else local). */
    static String ipAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    if (!a.isSiteLocalAddress()) continue;   // skips mobile data addresses
                    String ip = a.getHostAddress();
                    if (ni.getName().startsWith("wlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    // ------------------------------------------------------------------ sound

    /** Called by the recorder with every piece of mic sound while it holds the mic. */
    static void feedPcm(byte[] pcm, int n) {
        WifiServer w = current;
        if (w != null && w.sound && n > 0) w.broadcast(pcm, n);
    }

    private void broadcast(byte[] pcm, int n) {
        if (listeners.isEmpty()) return;
        byte[] copy = java.util.Arrays.copyOf(pcm, n);
        // a listener that falls behind just misses a bit of sound instead of slowing everyone down
        for (ArrayBlockingQueue<byte[]> q : listeners) q.offer(copy);
    }

    /** Owns the mic only while someone is listening and no recording is using it. */
    @SuppressWarnings("MissingPermission")
    private void micLoop() {
        AudioRecord rec = null;
        byte[] buf = new byte[2048];
        while (running) {
            boolean want = sound && !listeners.isEmpty() && !micBusy
                    && ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (want && rec == null) {
                rec = openMic();
                if (rec == null) {
                    sleep(1000);
                    continue;
                }
            }
            if (!want && rec != null) {
                releaseMic(rec);
                rec = null;
            }
            if (rec != null) {
                int n = rec.read(buf, 0, buf.length);
                if (n > 0 && !micBusy) broadcast(buf, n);
            } else {
                sleep(40);
            }
        }
        if (rec != null) releaseMic(rec);
    }

    @SuppressWarnings("MissingPermission")
    private AudioRecord openMic() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) throw new Exception("audio not available");
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, 16384));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                throw new Exception("microphone couldn't start");
            }
            AudioManager am = ctx.getSystemService(AudioManager.class);
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || d.getType() == AudioDeviceInfo.TYPE_USB_HEADSET) {
                    r.setPreferredDevice(d);
                    break;
                }
            }
            r.startRecording();
            warnedMic = false;
            return r;
        } catch (Exception e) {
            if (!warnedMic) log.log("Wi-Fi sound: " + e.getMessage());
            warnedMic = true;
            return null;
        }
    }

    private static void releaseMic(AudioRecord r) {
        try { r.stop(); } catch (Exception ignored) { }
        r.release();
    }

    // ------------------------------------------------------------------ web server

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                if (sockets.size() >= MAX_CLIENTS) {
                    s.close();
                    continue;
                }
                s.setTcpNoDelay(true);
                sockets.add(s);
                Thread t = new Thread(() -> serve(s), "wifi-client");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) sleep(100);
            }
        }
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(5000);
            String path = readRequestPath(s.getInputStream());
            s.setSoTimeout(0);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            if (path == null) return;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (path.equals("/") || path.equals("/index.html")) {
                sendText(out, "text/html; charset=utf-8", indexPage());
            } else if (path.equals("/audio") || path.equals("/audio.wav")) {
                streamAudio(out);
            } else if (path.startsWith("/view")) {
                sendText(out, "text/html; charset=utf-8", viewPage(camNumber(path.substring(5))));
            } else if (path.startsWith("/snap")) {
                sendSnapshot(out, camNumber(path.substring(5).replace(".jpg", "")));
            } else if (path.startsWith("/cam")) {
                streamVideo(out, camNumber(path.substring(4).replace(".mjpg", "")));
            } else {
                sendError(out, "404 Not Found");
            }
        } catch (Exception ignored) {
            // the PC went away; nothing to do
        } finally {
            sockets.remove(s);
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** "" means the main camera (0); "2" means camera 2. */
    private static int camNumber(String s) {
        try {
            int n = Integer.parseInt(s.trim());
            return n >= 1 && n <= IsoStream.MAX_SLOTS ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Cam pick(int n) { return n == 0 ? cams.main() : cams.cam(n); }

    private static String readRequestPath(InputStream in) throws Exception {
        StringBuilder line = new StringBuilder();
        String first = null;
        int c, total = 0;
        while ((c = in.read()) >= 0 && total++ < 16384) {
            if (c == '\n') {
                String l = line.toString().trim();
                if (first == null) first = l;
                else if (l.isEmpty()) break;   // end of headers
                line.setLength(0);
            } else {
                line.append((char) c);
            }
        }
        if (first == null) return null;
        String[] parts = first.split(" ");
        return parts.length >= 2 ? parts[1] : null;
    }

    private static void writeAscii(OutputStream out, String s) throws Exception {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void sendText(OutputStream out, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        writeAscii(out, "HTTP/1.0 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
        out.write(b);
        out.flush();
    }

    private static void sendError(OutputStream out, String status) throws Exception {
        writeAscii(out, "HTTP/1.0 " + status + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + status + "\n");
        out.flush();
    }

    private void sendSnapshot(OutputStream out, int n) throws Exception {
        Cam c = pick(n);
        byte[] j = c == null ? null : c.lastJpeg;
        if (j == null) {
            sendError(out, "503 Service Unavailable");
            return;
        }
        writeAscii(out, "HTTP/1.0 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n");
        out.write(j);
        out.flush();
    }

    /** Sends each new camera picture as soon as it arrives, until the PC disconnects. */
    private void streamVideo(OutputStream out, int n) throws Exception {
        writeAscii(out, "HTTP/1.0 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY
                + "\r\nCache-Control: no-cache, no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n");
        out.flush();
        videoViewers.incrementAndGet();
        try {
            byte[] last = null;
            while (running) {
                Cam c = pick(n);
                byte[] j = c == null ? null : c.lastJpeg;
                if (j == null || j == last) {
                    sleep(5);
                    continue;
                }
                last = j;
                writeAscii(out, "--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + j.length + "\r\n\r\n");
                out.write(j);
                writeAscii(out, "\r\n");
                out.flush();
            }
        } finally {
            videoViewers.decrementAndGet();
        }
    }

    /** A WAV that never ends: 44.1 kHz, mono, 16-bit. */
    private void streamAudio(OutputStream out) throws Exception {
        writeAscii(out, "HTTP/1.0 200 OK\r\nContent-Type: audio/wav\r\nCache-Control: no-cache, no-store\r\nConnection: close\r\n\r\n");
        out.write(wavHeader());
        out.flush();
        ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<>(48);
        listeners.add(q);
        try {
            while (running) {
                byte[] b = q.poll(500, TimeUnit.MILLISECONDS);
                if (b == null) {
                    // no sound for half a second (sound off, or the mic is busy starting):
                    // send a little silence so the PC keeps the stream open
                    out.write(new byte[SAMPLE_RATE / 10 * 2]);
                    out.flush();
                    continue;
                }
                out.write(b);
                if (q.isEmpty()) out.flush();
            }
        } finally {
            listeners.remove(q);
        }
    }

    private static byte[] wavHeader() {
        int byteRate = SAMPLE_RATE * 2;
        int big = 0x7FFFFFF0;   // "very long": players treat it as live
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(big + 36);
        b.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(SAMPLE_RATE).putInt(byteRate).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(big);
        return b.array();
    }

    // ------------------------------------------------------------------ pages

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String viewPage(int n) {
        String src = n == 0 ? "/cam" : "/cam" + n;
        return "<!doctype html><html><head><meta charset=utf-8><title>EyeToy " + (n == 0 ? "" : n) + "</title>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}"
                + "img{width:100vw;height:100vh;object-fit:contain;display:block}</style></head>"
                + "<body><img src='" + src + "' alt=''></body></html>";
    }

    private String indexPage() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=utf-8><title>EyeToy Cam</title>")
                .append("<meta name=viewport content='width=device-width,initial-scale=1'>")
                .append("<style>body{margin:0;padding:16px;background:#0b1020;color:#e8eefc;font:15px system-ui,sans-serif}")
                .append("h1{font-size:20px;margin:0 0 12px}.grid{display:flex;flex-wrap:wrap;gap:16px}")
                .append(".cam{background:#141b33;border:1px solid #2b3a66;border-radius:10px;padding:10px;max-width:660px;flex:1 1 320px}")
                .append(".cam img{width:100%;aspect-ratio:4/3;background:#000;border-radius:6px;display:block}")
                .append("code{background:#000;padding:2px 6px;border-radius:4px;color:#7fd4ff;user-select:all}")
                .append("p{margin:6px 0}</style></head><body><h1>EyeToy Cam</h1><div class=grid>");
        int shown = 0;
        for (int n = 1; n <= IsoStream.MAX_SLOTS; n++) {
            if (cams.cam(n) == null) continue;
            shown++;
            sb.append("<div class=cam><p><b>CAM ").append(n).append("</b> ").append(esc(cams.label(n))).append("</p>")
                    .append("<img src='/cam").append(n).append("' alt=''>")
                    .append("<p>OBS Browser source: <code>").append("/view").append(n).append("</code></p>")
                    .append("<p>OBS Media source: <code>").append("/cam").append(n).append("</code></p></div>");
        }
        if (shown == 0) sb.append("<p>No camera is running on the phone yet. Connect one, then reload this page.</p>");
        sb.append("</div><h1 style='margin-top:20px'>Sound</h1>");
        sb.append(sound ? "<audio controls preload=none src='/audio'></audio><p>OBS Media source: <code>/audio</code></p>"
                : "<p>Sound is off on the phone (Settings &gt; Wi-Fi webcam).</p>");
        sb.append("<p style='color:#8a97bd'>Put the phone's address in front of each path, e.g. ")
                .append(esc(String.valueOf(baseUrl()))).append("/view1</p>");
        sb.append("<script>document.querySelectorAll('code').forEach(function(c){c.textContent=location.origin+c.textContent})</script>");
        sb.append("</body></html>");
        return sb.toString();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
