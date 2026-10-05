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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
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
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
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
    private static final int[] TL_SECONDS = {1, 2, 5, 10, 30, 60};
    private static final String[] TL_NAMES = {"1 s", "2 s", "5 s", "10 s", "30 s", "1 min"};
    private static final int TL_OUT_FPS = 30;
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    private static final int PAGE_CAMERA = 0, PAGE_SETTINGS = 1, PAGE_LOG = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---- camera state ----
    private UsbManager usb;
    private UsbDeviceConnection conn;
    private UsbInterface intfAlt0;
    private EyeToyDriver driver;
    private UsbDevice currentDevice;
    private volatile boolean streaming;
    private volatile int startedFps;
    private Thread frameThread;
    private volatile String sensorName = "";
    private volatile String cameraKey = "default";
    private volatile String cameraSerial = "";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder logText = new StringBuilder();

    // ---- settings / state ----
    private Prefs prefs;
    private final Look look = new Look();
    private volatile String mode = Prefs.MODE_VIDEO;
    private volatile Recorder recorder;
    private final WaveDetector wave = new WaveDetector();
    private final int[] zoneBuf = new int[WaveDetector.GX * WaveDetector.GY];
    private float lastRingCharge;
    private volatile byte[] lastGoodJpeg;
    private volatile int framesShown, framesBad;
    private volatile long lastTlMs;
    private volatile boolean countingDown;
    private volatile boolean ledBlinking;
    private Thread ledThread;
    private boolean recDotOn = true;
    private PowerManager.OnThermalStatusChangedListener thermalListener;

    // ---- views ----
    private Ps2Background background;
    private LinearLayout content;
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];
    private int currentPage = -1;
    private AspectLayout previewFrame;
    private ImageView preview;
    private LinearLayout noCamera;
    private LinearLayout recBox;
    private View recDot;
    private TextView recSub, chip, countdownView;
    private View flashView;
    private WaveRing waveRing;
    private ShutterView shutter;
    private final TextView[] modeTabs = new TextView[3];
    private FrameLayout mirrorBtn;
    private SaveOverlay saveOverlay;
    private TextView logView, statusView;
    private ScrollView logScroll;
    private LinearLayout settingsList;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        usb = getSystemService(UsbManager.class);
        prefs = new Prefs(this);
        mode = prefs.s("mode", Prefs.MODE_VIDEO);
        look.mirror = prefs.b("mirror", false);
        look.flip = prefs.b("flip", false);
        look.setBrightness(prefs.i("brightness", 0));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        applyLookToPreview();
        updateModeUi();
        showPage(PAGE_CAMERA);

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbReceiver, f);

        thermalListener = status -> {
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) log("Phone temperature warning (level " + status + ").");
            if (status >= PowerManager.THERMAL_STATUS_SEVERE && isRecording()) {
                log("The phone is getting too hot, so the recording is being saved and stopped.");
                worker.execute(this::stopRecording);
            }
        };
        getSystemService(PowerManager.class).addThermalStatusListener(getMainExecutor(), thermalListener);

        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        log("EyeToy Cam " + ver + " on Android " + Build.VERSION.RELEASE + " (" + Build.MODEL + ")");
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

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbReceiver); } catch (Exception ignored) { }
        try { getSystemService(PowerManager.class).removeThermalStatusListener(thermalListener); } catch (Exception ignored) { }
        worker.execute(this::stopAll);
        worker.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI building

    private int dp(float v) { return Ps2Ui.dp(this, v); }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF02040F);
        background = new Ps2Background(this);
        root.addView(background, new FrameLayout.LayoutParams(MATCH, MATCH));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, bt;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; bt = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); bt = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(dp(14) + l, t + dp(6), dp(14) + r, bt + dp(10));
            return insets;
        });
        root.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));

        // tab bar
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"CAMERA", "SETTINGS", "LOG"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ps2Ui.text(this, names[i], 13, Ps2Ui.DIM, true);
            t.setLetterSpacing(0.16f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, dp(12), 0, dp(12));
            t.setOnClickListener(v -> showPage(idx));
            tabs[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(0, WRAP, 1f));
        }
        content.addView(tabBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout pageFrame = new FrameLayout(this);
        content.addView(pageFrame, new LinearLayout.LayoutParams(MATCH, 0, 1f));
        pages[PAGE_CAMERA] = buildCameraPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        pages[PAGE_LOG] = buildLogPage();
        for (View p : pages) pageFrame.addView(p, new FrameLayout.LayoutParams(MATCH, MATCH));

        saveOverlay = new SaveOverlay(this);
        root.addView(saveOverlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, weight));
        return v;
    }

    private View buildCameraPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(spacer(1f));

        // ---- picture frame ----
        previewFrame = new AspectLayout(this);
        previewFrame.setBackground(Ps2Ui.panel(this, 0xFF000000, Ps2Ui.EDGE, 10));
        previewFrame.setClipToOutline(true);
        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_XY);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(MATCH, MATCH));

        noCamera = new LinearLayout(this);
        noCamera.setOrientation(LinearLayout.VERTICAL);
        noCamera.setGravity(Gravity.CENTER);
        TextView nc1 = Ps2Ui.glow(Ps2Ui.text(this, "No camera connected", 18, Ps2Ui.WHITE, true), 0x806FB5FF);
        TextView nc2 = Ps2Ui.text(this, "Plug in your EyeToy with the USB adapter", 13, Ps2Ui.DIM, false);
        TextView connectBtn = Ps2Ui.text(this, "CONNECT", 14, Ps2Ui.WHITE, true);
        connectBtn.setLetterSpacing(0.14f);
        connectBtn.setGravity(Gravity.CENTER);
        connectBtn.setPadding(dp(28), dp(11), dp(28), dp(11));
        connectBtn.setBackground(Ps2Ui.panel(this, Ps2Ui.SEL, Ps2Ui.CYAN, 22));
        connectBtn.setOnClickListener(v -> connect());
        noCamera.addView(nc1);
        noCamera.addView(nc2);
        LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(WRAP, WRAP);
        cbl.topMargin = dp(16);
        noCamera.addView(connectBtn, cbl);
        previewFrame.addView(noCamera, new FrameLayout.LayoutParams(MATCH, MATCH));

        waveRing = new WaveRing(this);
        previewFrame.addView(waveRing, new FrameLayout.LayoutParams(MATCH, MATCH));

        // REC indicator, top-left
        recBox = new LinearLayout(this);
        recBox.setOrientation(LinearLayout.HORIZONTAL);
        recBox.setGravity(Gravity.CENTER_VERTICAL);
        recBox.setPadding(dp(10), dp(6), dp(12), dp(6));
        recBox.setBackground(Ps2Ui.panel(this, 0x99000000, 0, 14));
        recDot = new View(this);
        recDot.setBackground(Ps2Ui.circle(Ps2Ui.RED, 0, 0));
        recBox.addView(recDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView recText = Ps2Ui.text(this, "REC", 14, Ps2Ui.RED, true);
        recText.setLetterSpacing(0.1f);
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(WRAP, WRAP);
        rtl.leftMargin = dp(7);
        recBox.addView(recText, rtl);
        recSub = Ps2Ui.text(this, "0:00", 13, Ps2Ui.WHITE, false);
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(WRAP, WRAP);
        rsl.leftMargin = dp(8);
        recBox.addView(recSub, rsl);
        recBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams rbl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        rbl.setMargins(dp(10), dp(10), 0, 0);
        previewFrame.addView(recBox, rbl);

        countdownView = Ps2Ui.glow(Ps2Ui.text(this, "3", 96, Ps2Ui.WHITE, true), 0xCC6FB5FF);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setVisibility(View.GONE);
        previewFrame.addView(countdownView, new FrameLayout.LayoutParams(MATCH, MATCH));

        flashView = new View(this);
        flashView.setBackgroundColor(Color.WHITE);
        flashView.setVisibility(View.GONE);
        previewFrame.addView(flashView, new FrameLayout.LayoutParams(MATCH, MATCH));

        page.addView(previewFrame, new LinearLayout.LayoutParams(MATCH, WRAP));

        chip = Ps2Ui.text(this, "", 12, Ps2Ui.CYAN, false);
        chip.setGravity(Gravity.CENTER);
        chip.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(MATCH, WRAP);
        chl.topMargin = dp(8);
        page.addView(chip, chl);
        page.addView(spacer(1f));

        // ---- mode selector ----
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        String[] mn = {"VIDEO", "PHOTO", "TIME-LAPSE"};
        final String[] ids = {Prefs.MODE_VIDEO, Prefs.MODE_PHOTO, Prefs.MODE_TIMELAPSE};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ps2Ui.text(this, mn[i], 13, Ps2Ui.DIM, true);
            t.setLetterSpacing(0.14f);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(16), dp(9), dp(16), dp(9));
            t.setOnClickListener(v -> setMode(ids[idx]));
            modeTabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
            lp.leftMargin = dp(4);
            lp.rightMargin = dp(4);
            modeRow.addView(t, lp);
        }
        page.addView(modeRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- controls ----
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout settingsBtn = roundButton(IconView.GEAR);
        settingsBtn.setOnClickListener(v -> showPage(PAGE_SETTINGS));
        shutter = new ShutterView(this);
        shutter.setOnClickListener(v -> onShutterClick());
        mirrorBtn = roundButton(IconView.MIRROR);
        mirrorBtn.setOnClickListener(v -> setMirror(!look.mirror));

        controls.addView(centered(settingsBtn), new LinearLayout.LayoutParams(0, WRAP, 1f));
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(88), dp(88)));
        controls.addView(centered(mirrorBtn), new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(8);
        clp.bottomMargin = dp(8);
        page.addView(controls, clp);
        return page;
    }

    private FrameLayout roundButton(int iconType) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Ps2Ui.circle(0x66071033, Ps2Ui.EDGE, Math.max(1, dp(1))));
        f.setClickable(true);
        IconView icon = new IconView(this, iconType);
        int p = dp(15);
        icon.setPadding(p, p, p, p);
        f.addView(icon, new FrameLayout.LayoutParams(MATCH, MATCH));
        f.setTag(icon);
        f.setLayoutParams(new FrameLayout.LayoutParams(dp(56), dp(56)));
        return f;
    }

    private View centered(View v) {
        FrameLayout box = new FrameLayout(this);
        box.addView(v, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        return box;
    }

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setPadding(0, dp(12), 0, dp(24));
        sv.addView(settingsList, new FrameLayout.LayoutParams(MATCH, WRAP));
        return sv;
    }

    /** (Re)fills the settings page so it always shows the current values. */
    private void fillSettings() {
        settingsList.removeAllViews();

        // ---- camera name ----
        LinearLayout nameBox = Ps2Ui.section(this, settingsList, "Camera name");
        TextView nameHint = Ps2Ui.text(this,
                cameraSerial.isEmpty()
                        ? "Used in file names and photo info. Both EyeToys look identical to the phone, so set this when you swap cameras."
                        : "Used in file names and photo info. Remembered for this camera.",
                12, Ps2Ui.DIM, false);
        nameBox.addView(nameHint);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        final EditText nameField = new EditText(this);
        for (String n : new String[]{"EyeToy", "Grey", "Black"}) {
            TextView ch = Ps2Ui.text(this, n, 13, Ps2Ui.WHITE, true);
            ch.setGravity(Gravity.CENTER);
            ch.setPadding(0, dp(9), 0, dp(9));
            ch.setBackground(Ps2Ui.panel(this, 0x40000000, Ps2Ui.EDGE, 8));
            ch.setOnClickListener(v -> nameField.setText(n));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.leftMargin = dp(3);
            lp.rightMargin = dp(3);
            chips.addView(ch, lp);
        }
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(MATCH, WRAP);
        chl.topMargin = dp(8);
        nameBox.addView(chips, chl);
        nameField.setText(currentLabel());
        nameField.setSingleLine(true);
        nameField.setTextColor(Ps2Ui.WHITE);
        nameField.setHintTextColor(Ps2Ui.DIM);
        nameField.setHint("Custom name");
        nameField.setTextSize(15);
        nameField.setBackground(Ps2Ui.panel(this, 0x40000000, Ps2Ui.EDGE, 8));
        nameField.setPadding(dp(12), dp(10), dp(12), dp(10));
        nameField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String t = s.toString().trim();
                if (t.length() > 20) t = t.substring(0, 20);
                prefs.putS("label_" + cameraKey, t.isEmpty() ? "EyeToy" : t);
            }
        });
        LinearLayout.LayoutParams nfl = new LinearLayout.LayoutParams(MATCH, WRAP);
        nfl.topMargin = dp(8);
        nameBox.addView(nameField, nfl);

        // ---- video ----
        LinearLayout vid = Ps2Ui.section(this, settingsList, "Video");
        vid.addView(Ps2Ui.segmentRow(this, "Frame rate", "30 is smoothest; 15 is gentler in dim rooms.",
                new String[]{"15 fps", "30 fps"}, prefs.fps() >= 30 ? 1 : 0, i -> {
                    prefs.putI("fps", i == 1 ? 30 : 15);
                    if (streaming && camFps() != startedFps) {
                        if (isRecording()) log("The new frame rate applies after you stop recording.");
                        else restartCamera();
                    }
                }));
        vid.addView(Ps2Ui.toggleRow(this, "Record sound", "Uses the EyeToy's built-in microphone.",
                prefs.b("sound", true), on -> prefs.putB("sound", on)));

        // ---- picture ----
        LinearLayout pic = Ps2Ui.section(this, settingsList, "Picture");
        pic.addView(buildBrightnessRow());
        pic.addView(Ps2Ui.toggleRow(this, "Mirror", "Flip left and right, like a selfie.", look.mirror, this::setMirror));
        pic.addView(Ps2Ui.toggleRow(this, "Flip upside down", null, look.flip, on -> {
            look.flip = on;
            prefs.putB("flip", on);
            applyLookToPreview();
        }));

        // ---- indicators ----
        LinearLayout ind = Ps2Ui.section(this, settingsList, "Recording lights");
        ind.addView(Ps2Ui.toggleRow(this, "Blink the REC dot", "Off keeps the red dot steady.",
                prefs.b("blink_dot", false), on -> prefs.putB("blink_dot", on)));
        ind.addView(Ps2Ui.toggleRow(this, "Blink the camera's light while recording", "The light on the EyeToy itself.",
                prefs.b("blink_led", false), on -> {
                    prefs.putB("blink_led", on);
                    if (isRecording()) {
                        if (on) startLedBlink(); else stopLedBlink();
                    }
                }));

        // ---- wave ----
        LinearLayout wv = Ps2Ui.section(this, settingsList, "Wave to record");
        wv.addView(Ps2Ui.toggleRow(this, "Wave control",
                "Wave at the ring in the top-right of the picture. A 3-2-1 countdown starts it; wave again to stop.",
                prefs.b("wave", true), on -> {
                    prefs.putB("wave", on);
                    wave.reset();
                    waveRing.setActive(on && streaming);
                }));

        // ---- time-lapse ----
        LinearLayout tl = Ps2Ui.section(this, settingsList, "Time-lapse");
        final TextView tlInfo = Ps2Ui.text(this, "", 12, Ps2Ui.DIM, false);
        final Runnable updateTl = () -> {
            int s = TL_SECONDS[prefs.i("tl_idx", 2)];
            double perHour = 3600.0 / s / TL_OUT_FPS;
            tlInfo.setText(String.format(Locale.US,
                    "One picture every %s. An hour of recording becomes about %s of video at %d fps.",
                    TL_NAMES[prefs.i("tl_idx", 2)], perHour < 90 ? String.format(Locale.US, "%.0f seconds", perHour)
                            : String.format(Locale.US, "%.1f minutes", perHour / 60), TL_OUT_FPS));
        };
        tl.addView(Ps2Ui.segmentRow(this, "Picture every", null, TL_NAMES, prefs.i("tl_idx", 2), i -> {
            prefs.putI("tl_idx", i);
            updateTl.run();
        }));
        updateTl.run();
        tl.addView(tlInfo);

        // ---- info ----
        LinearLayout info = Ps2Ui.section(this, settingsList, "Camera info");
        TextView infoText = Ps2Ui.text(this, cameraInfo(), 13, Ps2Ui.WHITE, false);
        infoText.setLineSpacing(0, 1.15f);
        info.addView(infoText);
        TextView disc = Ps2Ui.text(this, "DISCONNECT CAMERA", 13, Ps2Ui.WHITE, true);
        disc.setLetterSpacing(0.1f);
        disc.setGravity(Gravity.CENTER);
        disc.setPadding(0, dp(11), 0, dp(11));
        disc.setBackground(Ps2Ui.panel(this, 0x40000000, Ps2Ui.EDGE, 8));
        disc.setOnClickListener(v -> worker.execute(this::stopAll));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MATCH, WRAP);
        dl.topMargin = dp(10);
        info.addView(disc, dl);
    }

    private View buildBrightnessRow() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        final TextView title = Ps2Ui.text(this, "", 15, Ps2Ui.WHITE, false);
        box.addView(title);
        box.addView(Ps2Ui.text(this, "Brightens the picture on the phone only. The camera is not touched. Can look grainier.",
                12, Ps2Ui.DIM, false));
        SeekBar sb = new SeekBar(this);
        sb.setMax(100);
        sb.setProgress(look.brightness());
        sb.setProgressTintList(ColorStateList.valueOf(Ps2Ui.CYAN));
        sb.setThumbTintList(ColorStateList.valueOf(Ps2Ui.WHITE));
        title.setText("Brightness boost: " + (look.brightness() == 0 ? "off" : "+" + look.brightness() + "%"));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                look.setBrightness(p);
                prefs.putI("brightness", p);
                title.setText("Brightness boost: " + (p == 0 ? "off" : "+" + p + "%"));
                applyLookToPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(sb, new LinearLayout.LayoutParams(MATCH, WRAP));
        return box;
    }

    private String cameraInfo() {
        StringBuilder sb = new StringBuilder();
        if (currentDevice != null && streaming) {
            sb.append("Sony EyeToy USB camera (054c:0155)\n");
            sb.append("Chip: OV519    Sensor: ").append(sensorName.isEmpty() ? "?" : sensorName).append("\n");
            sb.append("Picture: 640 x 480 (").append(Metadata.megapixels(640, 480)).append("), JPEG\n");
            sb.append("Camera ID: ").append(cameraSerial.isEmpty() ? "none given by the camera" : cameraSerial).append("\n");
        } else {
            sb.append("No camera connected.\n");
        }
        sb.append(String.format(Locale.US, "Free storage: %.1f GB", freeBytes() / 1073741824.0));
        return sb.toString();
    }

    private View buildLogPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(10), 0, 0);

        statusView = Ps2Ui.text(this, "Not connected", 12, Ps2Ui.CYAN, false);
        page.addView(statusView);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(0xFFD5E2F5);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        logScroll.setBackground(Ps2Ui.panel(this, PANEL_DARK, Ps2Ui.EDGE, 8));
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        llp.topMargin = dp(8);
        llp.bottomMargin = dp(8);
        page.addView(logScroll, llp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = logButton("COPY LOG");
        copy.setOnClickListener(v -> copyLog());
        TextView clear = logButton("CLEAR");
        clear.setOnClickListener(v -> {
            logText.setLength(0);
            logView.setText("");
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        b1.rightMargin = dp(6);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, WRAP, 1f);
        b2.leftMargin = dp(6);
        row.addView(copy, b1);
        row.addView(clear, b2);
        page.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return page;
    }

    private static final int PANEL_DARK = 0xB0030A22;

    private TextView logButton(String s) {
        TextView t = Ps2Ui.text(this, s, 13, Ps2Ui.WHITE, true);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(11), 0, dp(11));
        t.setBackground(Ps2Ui.panel(this, 0x40000000, Ps2Ui.EDGE, 8));
        return t;
    }

    // ------------------------------------------------------------------ UI state

    private void showPage(int p) {
        if (p == currentPage) return;
        currentPage = p;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
            boolean on = i == p;
            tabs[i].setTextColor(on ? Ps2Ui.WHITE : Ps2Ui.DIM);
            tabs[i].setBackground(on ? Ps2Ui.panel(this, 0x552F6BFF, Ps2Ui.CYAN, 18) : null);
            if (on) Ps2Ui.glow(tabs[i], 0x806FB5FF); else tabs[i].setShadowLayer(0, 0, 0, 0);
        }
        if (p == PAGE_SETTINGS) fillSettings();
        if (p == PAGE_LOG) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void applyLookToPreview() {
        preview.setScaleX(look.mirror ? -1f : 1f);
        preview.setScaleY(look.flip ? -1f : 1f);
        preview.setColorFilter(look.filter());
        mirrorBtn.setBackground(Ps2Ui.circle(look.mirror ? Ps2Ui.SEL : 0x66071033,
                look.mirror ? Ps2Ui.CYAN : Ps2Ui.EDGE, Math.max(1, dp(1))));
    }

    private void setMirror(boolean on) {
        look.mirror = on;
        prefs.putB("mirror", on);
        applyLookToPreview();
    }

    private void setMode(String m) {
        if (isRecording()) {
            log("Stop recording first, then change the mode.");
            return;
        }
        if (m.equals(mode)) return;
        mode = m;
        prefs.putS("mode", m);
        wave.reset();
        updateModeUi();
        if (streaming && camFps() != startedFps) restartCamera();
    }

    private void updateModeUi() {
        String[] ids = {Prefs.MODE_VIDEO, Prefs.MODE_PHOTO, Prefs.MODE_TIMELAPSE};
        for (int i = 0; i < 3; i++) {
            boolean on = ids[i].equals(mode);
            modeTabs[i].setTextColor(on ? Ps2Ui.WHITE : Ps2Ui.DIM);
            modeTabs[i].setBackground(on ? Ps2Ui.panel(this, 0x552F6BFF, Ps2Ui.CYAN, 18) : null);
        }
        shutter.setState(Prefs.MODE_PHOTO.equals(mode), isRecording());
    }

    private void updateRecUi(boolean recording) {
        recBox.setVisibility(recording ? View.VISIBLE : View.GONE);
        recDot.setAlpha(1f);
        recDotOn = true;
        shutter.setState(Prefs.MODE_PHOTO.equals(mode), recording);
        background.setAnimated(!recording);
        ui.removeCallbacks(recBlink);
        if (recording && prefs.b("blink_dot", false)) ui.postDelayed(recBlink, 600);
    }

    private final Runnable recBlink = new Runnable() {
        @Override
        public void run() {
            if (!isRecording() || !prefs.b("blink_dot", false)) {
                recDot.setAlpha(1f);
                return;
            }
            recDotOn = !recDotOn;
            recDot.setAlpha(recDotOn ? 1f : 0.12f);
            ui.postDelayed(this, 600);
        }
    };

    private void onCameraLive() {
        noCamera.setVisibility(View.GONE);
        waveRing.setActive(prefs.b("wave", true));
        if (currentPage == PAGE_SETTINGS) fillSettings();
    }

    private void onCameraGone() {
        noCamera.setVisibility(View.VISIBLE);
        preview.setImageDrawable(null);
        waveRing.setActive(false);
        chip.setText("");
        recBox.setVisibility(View.GONE);
        endCountdown();
        background.setAnimated(true);
        if (currentPage == PAGE_SETTINGS) fillSettings();
    }

    private void flash() {
        flashView.setAlpha(0.85f);
        flashView.setVisibility(View.VISIBLE);
        flashView.animate().alpha(0f).setDuration(260).withEndAction(() -> flashView.setVisibility(View.GONE)).start();
    }

    // ------------------------------------------------------------------ log

    void log(String msg) {
        Log.i(TAG, msg);
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg + "\n";
        ui.post(() -> {
            logText.append(line);
            logView.setText(logText);
            if (currentPage == PAGE_LOG) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
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

    // ------------------------------------------------------------------ USB setup

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
            Toast.makeText(this, "No camera found. Check the adapter.", Toast.LENGTH_SHORT).show();
            return;
        }
        UsbDevice d = findEyeToy(true);
        if (d == null) {
            log("Didn't find an EyeToy (054c:0155) in the list above.");
            Toast.makeText(this, "No EyeToy found.", Toast.LENGTH_SHORT).show();
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

    /** The frame rate the camera itself should run at right now. */
    private int camFps() {
        return Prefs.MODE_TIMELAPSE.equals(mode) ? 5 : prefs.fps();
    }

    private void restartCamera() {
        final UsbDevice d = currentDevice;
        worker.execute(() -> {
            stopAll();
            if (d != null) openAndStart(d);
        });
    }

    /** Runs on the worker thread. */
    private void openAndStart(UsbDevice d) {
        if (streaming) return;
        try {
            log("Opening camera...");
            String serial = null;
            try { serial = d.getSerialNumber(); } catch (Exception ignored) { }
            cameraSerial = serial == null ? "" : serial;
            cameraKey = cameraSerial.isEmpty() ? "default" : cameraSerial;
            log("Camera ID: " + (cameraSerial.isEmpty() ? "(none given)" : cameraSerial)
                    + " · " + d.getManufacturerName() + " / " + d.getProductName());

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
                log("Camera setup stopped. Open the Log tab, tap Copy log and send it to Claude.");
                closeConn();
                return;
            }
            sensorName = driver.sensorName;

            int psize = bestEp.getMaxPacketSize() & 0x7ff;
            log(String.format("Selecting stream setting alt %d (packet size %d).", best.getAlternateSetting(), psize));
            if (!conn.setInterface(best)) {
                log("Android refused to switch to that stream setting.");
                closeConn();
                return;
            }

            currentDevice = d;
            startedFps = camFps();
            driver.start(startedFps);

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
            wave.reset();
            streaming = true;
            frameThread = new Thread(this::frameLoop, "frames");
            frameThread.start();
        } catch (Exception e) {
            log("ERROR: " + e.getMessage());
            Log.e(TAG, "openAndStart", e);
            stopAll();
        }
    }

    // ------------------------------------------------------------------ frames

    private void frameLoop() {
        long lastStatus = 0, started = System.currentTimeMillis();
        int lastShown = 0;
        boolean warnedNoData = false, loggedFirst = false;
        while (streaming) {
            byte[] f = IsoStream.nativeGetFrame();
            long now = System.currentTimeMillis();
            if (f != null) {
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
                        ui.post(this::onCameraLive);
                    }
                    Recorder rec = recorder;
                    if (rec != null && rec.isRunning()) {
                        if (rec.isTimelapse()) {
                            long every = TL_SECONDS[prefs.i("tl_idx", 2)] * 1000L;
                            if (now - lastTlMs >= every) {
                                lastTlMs = now;
                                rec.drawFrame(bmp);
                            }
                        } else {
                            rec.drawFrame(bmp);
                        }
                    }
                    if (prefs.b("wave", true) && !saveOverlay.isShowing() && !countingDown) detectWave(bmp, now);
                    final Bitmap show = bmp;
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
                final int fps = framesShown - lastShown;
                lastShown = framesShown;
                lastStatus = now;
                statusTick(fps, s);
                if (!warnedNoData && now - started > 5000 && framesShown == 0) {
                    warnedNoData = true;
                    log(String.format(Locale.US, "No picture after 5s. urbs %d, packets %d, data packets %d, bytes %d, frames %d, dropped %d, pkt errors %d, reap errors %d, submit errors %d, last errno %d",
                            s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]));
                    log(IsoStream.nativeDebug());
                    log("Open the Log tab, tap Copy log and send it to Claude.");
                }
                if (!IsoStream.nativeIsRunning()) {
                    log("USB stream stopped by itself (errno " + s[9] + ").");
                    worker.execute(this::stopAll);
                    break;
                }
            }
        }
    }

    private void statusTick(int fps, long[] s) {
        final Recorder rec = recorder;
        String recText = null;
        if (rec != null && rec.isRunning()) {
            if (rec.isTimelapse()) {
                long n = rec.framesDrawn();
                recText = String.format(Locale.US, "TIME-LAPSE · %d frames · %.1f s", n, n / (double) TL_OUT_FPS);
            } else {
                long sec = rec.elapsedMs() / 1000;
                recText = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
            }
            if (freeBytes() < MIN_FREE_BYTES) {
                log("Storage is almost full, so the recording is being saved and stopped.");
                worker.execute(this::stopRecording);
            }
        }
        final String label = currentLabel();
        final String chipText = String.format(Locale.US, "● LIVE · %d fps · %s", fps, label.toUpperCase(Locale.US));
        final String rt = recText;
        ui.post(() -> {
            chip.setText(chipText);
            if (rt != null) recSub.setText(rt);
        });
        status(String.format(Locale.US,
                "Streaming · %d fps · shown %d · bad %d · frames %d · dropped %d · data packets %d · KB %d · pkt err %d",
                fps, framesShown, framesBad, s[4], s[5], s[2], s[3] / 1024, s[6]));
    }

    private static Bitmap decode(byte[] f) {
        try {
            return BitmapFactory.decodeByteArray(f, 0, f.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int luma(int px) {
        return (((px >> 16) & 0xff) * 30 + ((px >> 8) & 0xff) * 59 + (px & 0xff) * 11) / 100;
    }

    private void detectWave(Bitmap bmp, long now) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        boolean mirror = look.mirror, flip = look.flip;
        int gsum = 0, gn = 0;
        for (int j = 0; j < 6; j++) {
            for (int i = 0; i < 8; i++) {
                gsum += luma(bmp.getPixel((i * 2 + 1) * (w - 1) / 16, (j * 2 + 1) * (h - 1) / 12));
                gn++;
            }
        }
        int idx = 0;
        for (int j = 0; j < WaveDetector.GY; j++) {
            for (int i = 0; i < WaveDetector.GX; i++) {
                float u = WaveDetector.ZX0 + (i + 0.5f) / WaveDetector.GX * (WaveDetector.ZX1 - WaveDetector.ZX0);
                float v = WaveDetector.ZY0 + (j + 0.5f) / WaveDetector.GY * (WaveDetector.ZY1 - WaveDetector.ZY0);
                int x = (int) ((mirror ? 1f - u : u) * (w - 1));
                int y = (int) ((flip ? 1f - v : v) * (h - 1));
                zoneBuf[idx++] = luma(bmp.getPixel(x, y));
            }
        }
        boolean trig = wave.update(zoneBuf, gsum / gn, now);
        final float c = wave.charge();
        if (trig || Math.abs(c - lastRingCharge) > 0.02f) {
            lastRingCharge = c;
            ui.post(() -> waveRing.setCharge(c));
        }
        if (trig) ui.post(this::onWaveTrigger);
    }

    // ------------------------------------------------------------------ shutter, wave, countdown

    private boolean isRecording() {
        Recorder r = recorder;
        return r != null && r.isRunning();
    }

    private void onShutterClick() {
        if (countingDown) {
            endCountdown();
            log("Countdown cancelled.");
            return;
        }
        fireShutter();
    }

    /** Runs on the UI thread. */
    private void fireShutter() {
        if (!streaming) {
            log("Connect the camera first.");
            Toast.makeText(this, "Connect the camera first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Prefs.MODE_PHOTO.equals(mode)) worker.execute(this::takePhoto);
        else toggleRecord();
    }

    private void onWaveTrigger() {
        if (!streaming || countingDown || saveOverlay.isShowing()) return;
        if (isRecording()) {
            log("Wave detected: stopping.");
            fireShutter();
        } else {
            log("Wave detected: starting in 3 seconds.");
            startCountdown();
        }
    }

    private void startCountdown() {
        countingDown = true;
        countdownView.setVisibility(View.VISIBLE);
        final int[] n = {3};
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!countingDown || !streaming) {
                    endCountdown();
                    return;
                }
                if (n[0] > 0) {
                    countdownView.setText(String.valueOf(n[0]--));
                    ui.postDelayed(this, 1000);
                } else {
                    endCountdown();
                    fireShutter();
                }
            }
        });
    }

    private void endCountdown() {
        countingDown = false;
        countdownView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ photos

    private String currentLabel() {
        return prefs.s("label_" + cameraKey, "EyeToy");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void takePhoto() {
        final byte[] jpg = lastGoodJpeg;
        if (jpg == null) {
            log("No picture yet.");
            return;
        }
        ui.post(() -> {
            flash();
            saveOverlay.showSaving("Saving data...", "Do not unplug the EyeToy.");
        });
        try {
            String label = currentLabel();
            String base = Metadata.baseName(label, stamp());
            Bitmap src = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            if (src == null) throw new Exception("couldn't read the picture");
            Bitmap out = look.apply(src);

            ContentResolver cr = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, base + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/EyeToyCam");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (u == null) throw new Exception("couldn't create the file");
            try (OutputStream os = cr.openOutputStream(u)) {
                if (os == null) throw new Exception("couldn't open the file");
                out.compress(Bitmap.CompressFormat.JPEG, 95, os);
            }
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "rw")) {
                Metadata.writePhotoExif(pfd.getFileDescriptor(), label, sensorName, out.getWidth(), out.getHeight(),
                        "EyeToy Cam");
            } catch (Exception e) {
                log("Photo saved, but the camera info couldn't be added: " + e.getMessage());
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(u, done, null, null);
            log("Saved photo to Pictures/EyeToyCam/" + base + ".jpg");
            ui.post(() -> saveOverlay.showDone("Pictures / EyeToyCam"));
        } catch (Exception e) {
            log("Saving failed: " + e.getMessage());
            final String m = String.valueOf(e.getMessage());
            ui.post(() -> saveOverlay.showFail(m));
        }
    }

    // ------------------------------------------------------------------ recording

    private long freeBytes() {
        try {
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Runs on the UI thread. */
    private void toggleRecord() {
        if (isRecording()) {
            worker.execute(this::stopRecording);
            return;
        }
        if (!streaming) {
            log("Connect the camera first, then tap the shutter.");
            return;
        }
        final boolean tl = Prefs.MODE_TIMELAPSE.equals(mode);
        final boolean wantSound = !tl && prefs.b("sound", true);
        if (wantSound && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Asking for microphone permission (for sound)...");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        worker.execute(() -> startRecording(wantSound));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        final boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) log("No microphone permission, so recording without sound.");
        if (streaming) worker.execute(() -> startRecording(ok));
    }

    /** Runs on the worker thread. */
    private void startRecording(boolean withSound) {
        if (!streaming || isRecording()) return;
        if (freeBytes() < MIN_FREE_BYTES) {
            log("Not enough free storage to record.");
            ui.post(() -> Toast.makeText(this, "Not enough free storage.", Toast.LENGTH_LONG).show());
            return;
        }
        boolean tl = Prefs.MODE_TIMELAPSE.equals(mode);
        String label = currentLabel();
        String base = Metadata.baseName(label, stamp());
        int fps = tl ? TL_OUT_FPS : prefs.fps();
        String desc = Metadata.description(label, sensorName, 640, 480, fps, tl);
        Recorder r = new Recorder(this::log);
        try {
            r.start(this, 640, 480, fps, withSound, look, base, desc, tl, TL_OUT_FPS);
            lastTlMs = 0;
            recorder = r;
            startLedBlink();
            ui.post(() -> {
                recSub.setText(tl ? "TIME-LAPSE" : "0:00");
                updateRecUi(true);
            });
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
        if (r == null) return;
        stopLedBlink();
        ui.post(() -> {
            saveOverlay.showSaving("Saving data...", "Do not unplug the EyeToy.");
            updateRecUi(false);
        });
        r.stop();
        final String name = r.savedName();
        ui.post(() -> {
            if (name != null) saveOverlay.showDone("Movies / EyeToyCam");
            else saveOverlay.showFail("The recording had no pictures.");
        });
    }

    // ------------------------------------------------------------------ camera light

    private void startLedBlink() {
        final EyeToyDriver d = driver;
        if (d == null || !prefs.b("blink_led", false) || ledBlinking) return;
        ledBlinking = true;
        ledThread = new Thread(() -> {
            boolean on = false;
            while (ledBlinking) {
                on = !on;
                if (!d.setLed(on)) break;
                try { Thread.sleep(600); } catch (InterruptedException e) { break; }
            }
            d.setLed(true);
        }, "led");
        ledThread.start();
    }

    private void stopLedBlink() {
        ledBlinking = false;
        Thread t = ledThread;
        ledThread = null;
        if (t != null) {
            t.interrupt();
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
    }

    // ------------------------------------------------------------------ teardown

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

    /** Safe to call any time, from the worker thread. */
    private void stopAll() {
        stopRecording();
        stopLedBlink();
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
        ui.post(this::onCameraGone);
    }
}
