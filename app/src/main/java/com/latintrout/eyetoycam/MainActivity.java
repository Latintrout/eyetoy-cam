package com.latintrout.eyetoycam;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "EyeToyCam";
    private static final String ACTION_PERMISSION = "com.latintrout.eyetoycam.USB_PERMISSION";
    private static final int SONY_VID = 0x054c;

    private UsbManager usb;
    private UsbDeviceConnection conn;
    private UsbInterface intfAlt0;
    private EyeToyDriver driver;
    private volatile boolean streaming;
    private Thread frameThread;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logText = new StringBuilder();

    private TextView logView, statusView;
    private ScrollView logScroll;
    private ImageView preview;

    private volatile Recorder recorder;
    private volatile int fps = 15;
    private UsbDevice currentDevice;
    private Button recordBtn, fpsBtn;

    private volatile byte[] lastGoodJpeg;
    private volatile byte[] lastRawFrame;
    private volatile int framesShown, framesBad;

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = getSystemService(UsbManager.class);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);

        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        log("EyeToy Cam test build " + ver + " on Android " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")");
        log("Plug the EyeToy in with the adapter, then tap Connect.");

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            log("EyeToy plugged in.");
            connect();
        }
    }

    private void buildUi() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(18, 18, 22));
        root.setPadding(pad, pad, pad, pad);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars());
                top = in.top; bottom = in.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(pad, pad + top, pad, pad + bottom);
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("EyeToy Cam · test");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        preview = new ImageView(this);
        preview.setBackgroundColor(Color.BLACK);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5f);
        plp.topMargin = dp(8);
        root.addView(preview, plp);

        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(160, 220, 160));
        statusView.setTextSize(13);
        statusView.setText("Not connected");
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        row1.addView(button("Connect", v -> connect()), weight());
        row1.addView(button("Stop", v -> worker.execute(this::stopAll)), weight());
        fpsBtn = button("15 fps", v -> toggleFps());
        row1.addView(fpsBtn, weight());
        root.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        recordBtn = button("⏺ Record", v -> toggleRecord());
        row2.addView(recordBtn, weight());
        row2.addView(button("Photo", v -> worker.execute(this::savePhoto)), weight());
        row2.addView(button("Copy log", v -> copyLog()), weight());
        root.addView(row2);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Color.rgb(210, 210, 210));
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f));

        setContentView(root);
    }

    private Button button(String text, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void log(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n";
        ui.post(() -> {
            logText.append(line);
            logView.setText(logText);
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void status(String s) {
        ui.post(() -> statusView.setText(s));
    }

    private void copyLog() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("EyeToy Cam log", logText.toString()));
        Toast.makeText(this, "Log copied. Paste it to Claude.", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- USB setup

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            UsbDevice d = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_PERMISSION.equals(a)) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    log("Permission granted.");
                    if (d == null) d = findEyeToy(false);
                    UsbDevice dev = d;
                    if (dev != null) worker.execute(() -> openAndStart(dev));
                } else {
                    log("Permission was refused. Tap Connect and choose Allow.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (d != null && d.getVendorId() == SONY_VID) {
                    log("EyeToy unplugged.");
                    worker.execute(MainActivity.this::stopAll);
                }
            }
        }
    };

    private UsbDevice findEyeToy(boolean verbose) {
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (verbose) log(String.format("  USB device found: %04x:%04x %s", d.getVendorId(), d.getProductId(),
                    d.getProductName() == null ? "" : d.getProductName()));
            if (d.getVendorId() == SONY_VID && (d.getProductId() == 0x0155 || d.getProductId() == 0x0154)) return d;
        }
        return null;
    }

    private void connect() {
        if (streaming) { log("Already streaming."); return; }
        log("Looking for the EyeToy...");
        if (usb.getDeviceList().isEmpty()) {
            log("No USB devices at all. Check the adapter is plugged in fully, and that the phone isn't in charge-only mode.");
            return;
        }
        UsbDevice d = findEyeToy(true);
        if (d == null) {
            log("Didn't find an EyeToy (054c:0155) in the list above.");
            return;
        }
        if (usb.hasPermission(d)) {
            worker.execute(() -> openAndStart(d));
        } else {
            log("Asking for permission to use the camera...");
            Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
        }
    }

    private static String epType(int t) {
        switch (t) {
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "iso";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "int";
            default: return "ctrl";
        }
    }

    /** Runs on the worker thread. */
    private void openAndStart(UsbDevice d) {
        if (streaming) return;
        try {
            log("Opening camera...");
            conn = usb.openDevice(d);
            if (conn == null) { log("Couldn't open the camera (openDevice returned nothing)."); return; }

            UsbInterface best = null;
            UsbEndpoint bestEp = null;
            intfAlt0 = null;
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface it = d.getInterface(i);
                StringBuilder sb = new StringBuilder(String.format("  interface %d alt %d class %d:", it.getId(),
                        it.getAlternateSetting(), it.getInterfaceClass()));
                for (int e = 0; e < it.getEndpointCount(); e++) {
                    UsbEndpoint ep = it.getEndpoint(e);
                    sb.append(String.format(" [ep 0x%02x %s %s %d]", ep.getAddress(), epType(ep.getType()),
                            ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out", ep.getMaxPacketSize()));
                    if (it.getId() == 0 && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                            && ep.getDirection() == UsbConstants.USB_DIR_IN
                            && (bestEp == null || (ep.getMaxPacketSize() & 0x7ff) > (bestEp.getMaxPacketSize() & 0x7ff))) {
                        best = it;
                        bestEp = ep;
                    }
                }
                log(sb.toString());
                if (it.getId() == 0 && it.getAlternateSetting() == 0) intfAlt0 = it;
            }
            if (intfAlt0 == null || best == null) {
                log("Couldn't find the video stream on interface 0.");
                closeConn();
                return;
            }
            if (!conn.claimInterface(intfAlt0, true)) {
                log("Couldn't claim the video interface.");
                closeConn();
                return;
            }

            driver = new EyeToyDriver(conn, this::log);
            if (!driver.init()) {
                log("Camera setup stopped. Tap Copy log and send it to Claude.");
                closeConn();
                return;
            }

            int psize = bestEp.getMaxPacketSize() & 0x7ff;
            log(String.format("Selecting stream setting alt %d (packet size %d).", best.getAlternateSetting(), psize));
            if (!conn.setInterface(best)) {
                log("Android refused to switch to that stream setting.");
                closeConn();
                return;
            }

            currentDevice = d;
            driver.start(fps);

            int r = IsoStream.nativeStart(conn.getFileDescriptor(), bestEp.getAddress(), psize);
            if (r != 0) {
                log("Couldn't start the USB stream (error " + r + ").");
                driver.stop();
                closeConn();
                return;
            }
            log("USB stream running. Waiting for pictures...");
            framesShown = 0;
            framesBad = 0;
            streaming = true;
            frameThread = new Thread(this::frameLoop, "frames");
            frameThread.start();
        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
            Log.e(TAG, "openAndStart", e);
            stopAll();
        }
    }

    // ------------------------------------------------------------ frames

    private void frameLoop() {
        long lastStatus = 0, started = System.currentTimeMillis();
        int lastShown = 0;
        boolean warnedNoData = false, loggedFirst = false;
        while (streaming) {
            byte[] f = IsoStream.nativeGetFrame();
            long now = System.currentTimeMillis();
            if (f != null) {
                lastRawFrame = f;
                Bitmap bmp = decode(f);
                byte[] used = f;
                if (bmp == null && JpegFix.looksLikeJpeg(f)) {
                    byte[] fixed = JpegFix.addHuffmanTables(f);
                    if (fixed != f) {
                        bmp = decode(fixed);
                        used = fixed;
                    }
                }
                if (bmp != null) {
                    framesShown++;
                    lastGoodJpeg = used;
                    if (!loggedFirst) {
                        loggedFirst = true;
                        log("First picture received! " + bmp.getWidth() + "x" + bmp.getHeight()
                                + (used != f ? " (needed Huffman tables added)" : ""));
                    }
                    Recorder rec = recorder;
                    if (rec != null && rec.isRunning()) rec.drawFrame(bmp);
                    Bitmap show = bmp;
                    ui.post(() -> preview.setImageBitmap(show));
                } else {
                    framesBad++;
                    if (framesBad <= 3)
                        log("Frame " + f.length + " bytes couldn't be decoded. Starts: " + JpegFix.hex(f, 24));
                }
            } else {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }

            if (now - lastStatus >= 1000) {
                long[] s = IsoStream.nativeStats();
                int fps = framesShown - lastShown;
                lastShown = framesShown;
                lastStatus = now;
                Recorder rec = recorder;
                String recText = "";
                if (rec != null && rec.isRunning()) {
                    long sec = rec.elapsedMs() / 1000;
                    recText = String.format(Locale.US, "● REC %d:%02d · ", sec / 60, sec % 60);
                }
                status(recText + String.format(Locale.US,
                        "Streaming · %d fps · shown %d · bad %d · frames %d · dropped %d · data packets %d · KB %d · pkt err %d",
                        fps, framesShown, framesBad, s[4], s[5], s[2], s[3] / 1024, s[6]));
                if (!warnedNoData && now - started > 5000 && framesShown == 0) {
                    warnedNoData = true;
                    log(String.format(Locale.US, "No picture after 5s. urbs %d, packets %d, data packets %d, bytes %d, frames %d, dropped %d, pkt errors %d, reap errors %d, submit errors %d, last errno %d",
                            s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]));
                    log(IsoStream.nativeDebug());
                    log("Tap Copy log and send it to Claude.");
                }
                if (!IsoStream.nativeIsRunning()) {
                    log("USB stream stopped by itself (errno " + s[9] + ").");
                    worker.execute(this::stopAll);
                    break;
                }
            }
        }
    }

    private static Bitmap decode(byte[] f) {
        try {
            return BitmapFactory.decodeByteArray(f, 0, f.length);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------- saving

    private void savePhoto() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        ContentResolver cr = getContentResolver();
        byte[] good = lastGoodJpeg;
        try {
            if (good != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "eyetoy_" + stamp + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EyeToyCam");
                Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                write(cr, u, good);
                log("Saved photo to Pictures/EyeToyCam/eyetoy_" + stamp + ".jpg");
                return;
            }
            byte[] raw = lastRawFrame;
            if (raw != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, "eyetoy_raw_" + stamp + ".bin");
                v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                write(cr, u, raw);
                log("No good picture yet, so saved the raw frame to Downloads/eyetoy_raw_" + stamp + ".bin (send it to Claude).");
                return;
            }
            log("Nothing to save yet.");
        } catch (Exception e) {
            log("Saving failed: " + e.getMessage());
        }
    }

    private static void write(ContentResolver cr, Uri u, byte[] data) throws Exception {
        if (u == null) throw new Exception("couldn't create the file");
        try (OutputStream os = cr.openOutputStream(u)) {
            if (os == null) throw new Exception("couldn't open the file");
            os.write(data);
        }
    }

    // ------------------------------------------------------------ teardown

    private void closeConn() {
        if (conn != null) {
            try {
                if (intfAlt0 != null) {
                    conn.setInterface(intfAlt0);
                    conn.releaseInterface(intfAlt0);
                }
            } catch (Exception ignored) { }
            conn.close();
            conn = null;
        }
    }

    // ------------------------------------------------------------ recording

    private void toggleRecord() {
        Recorder rec = recorder;
        if (rec != null && rec.isRunning()) {
            worker.execute(this::stopRecording);
            return;
        }
        if (!streaming) {
            log("Connect the camera first, then tap Record.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Asking for microphone permission (for sound)...");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        worker.execute(() -> startRecording(true));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) log("No microphone permission, so recording without sound.");
        if (streaming) worker.execute(() -> startRecording(ok));
    }

    /** Runs on the worker thread. */
    private void startRecording(boolean withSound) {
        if (!streaming || (recorder != null && recorder.isRunning())) return;
        Recorder r = new Recorder(this::log);
        try {
            r.start(this, 640, 480, fps, withSound);
            recorder = r;
            ui.post(() -> recordBtn.setText("⏹ Stop rec"));
        } catch (Exception e) {
            log("Couldn't start recording: " + e.getMessage());
            Log.e(TAG, "startRecording", e);
            r.stop();
        }
    }

    /** Runs on the worker thread. */
    private void stopRecording() {
        Recorder r = recorder;
        recorder = null;
        if (r != null) r.stop();
        ui.post(() -> recordBtn.setText("⏺ Record"));
    }

    private void toggleFps() {
        Recorder rec = recorder;
        if (rec != null && rec.isRunning()) {
            log("Stop recording before changing the frame rate.");
            return;
        }
        fps = fps == 15 ? 30 : 15;
        fpsBtn.setText(fps + " fps");
        log("Frame rate set to " + fps + " fps.");
        if (streaming && currentDevice != null) {
            UsbDevice d = currentDevice;
            worker.execute(() -> {
                stopAll();
                openAndStart(d);
            });
        }
    }

    /** Safe to call any time, from the worker thread. */
    private void stopAll() {
        stopRecording();
        boolean was = streaming;
        streaming = false;
        if (frameThread != null) {
            try { frameThread.join(1000); } catch (InterruptedException ignored) { }
            frameThread = null;
        }
        IsoStream.nativeStop();
        if (driver != null) { driver.stop(); driver = null; }
        closeConn();
        if (was) log("Stopped.");
        status("Not connected");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        worker.execute(this::stopAll);
        worker.shutdown();
        super.onDestroy();
    }
}
