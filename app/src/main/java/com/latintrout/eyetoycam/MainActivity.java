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
import android.hardware.usb.UsbDevice;
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
import android.util.Size;
import android.view.ScaleGestureDetector;
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
    private static final int[] TIMER_S = {0, 3, 5, 10};
    private static final String[] SPLIT_NAMES = {"Off", "5 min", "10 min", "30 min"};
    private static final int[] SPLIT_MIN = {0, 5, 10, 30};
    private static final long MIN_FREE_BYTES = 300L * 1024 * 1024;
    private static final int PAGE_CAMERA = 0, PAGE_SETTINGS = 1, PAGE_LOG = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    // ---- camera state ----
    private UsbManager usb;
    /** Running cameras by stream slot (guarded by itself). The main one records, takes photos and sees waves. */
    private final Cam[] cams = new Cam[IsoStream.MAX_SLOTS];
    private volatile Cam main;
    /** USB device names being opened or waiting for permission, so a camera is never opened twice. */
    private final java.util.Set<String> pending = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final java.util.ArrayDeque<UsbDevice> permQueue = new java.util.ArrayDeque<>();
    private boolean permAsking;
    private volatile boolean streaming;
    private volatile int startedFps;
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
    private Backdrop background;
    private LinearLayout content;
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];
    private final IconView[] tabIcons = new IconView[3];
    private final LinearLayout[] tabBoxes = new LinearLayout[3];
    private int currentPage = -1;
    private AspectLayout previewFrame;
    private CamGrid grid;
    private FrameLayout screen;
    private LinearLayout noCamera;
    private LinearLayout recBox;
    private View recDot;
    private TextView recSub, countdownView, hudMode, hudClock, hudStore;
    private String hudLive = "";
    private String lastStatus = "Not connected";
    private FocusBrackets brackets;
    private View flashView;
    private WaveRing waveRing;
    private ShutterView shutter;
    private final TextView[] modeTabs = new TextView[3];
    private LinearLayout mirrorBtn, flipBtn;
    private ImageView thumb;
    private Bitmap lastThumb;
    private TextView timerChip, zoomLabel;
    private SeekBar zoomBar;
    private ScaleGestureDetector pinch;
    private volatile Uri lastUri;
    private volatile boolean lastIsVideo;
    private volatile boolean rotating;
    private volatile boolean lastWithSound, pendingSound;
    private volatile int recPart = 1;
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
        look.zoom = Math.max(1f, Math.min(4f, prefs.i("zoom_pct", 100) / 100f));
        String lu = prefs.s("last_uri", "");
        if (!lu.isEmpty()) {
            lastUri = Uri.parse(lu);
            lastIsVideo = prefs.b("last_video", false);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        buildUi();
        applyLookToPreview();
        updateModeUi();
        showPage(PAGE_CAMERA);
        if (lastUri != null) {
            final Uri u = lastUri;
            worker.execute(() -> loadThumb(u));
        }

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
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
        Ps2Ui.apply(Theme.byId(prefs.s("theme", Theme.DEFAULT)));
        applySystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ps2Ui.theme.bgBottom);
        View bg = Ps2Ui.theme.light ? new RetroBackground(this) : new NeonBackground(this);
        background = (Backdrop) bg;
        root.addView(bg, new FrameLayout.LayoutParams(MATCH, MATCH));

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
        int[] icons = {IconView.CAMERA, IconView.GEAR, IconView.LOG};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(4), dp(11), dp(4), dp(11));
            box.setOnClickListener(v -> showPage(idx));
            IconView icon = new IconView(this, icons[i]);
            box.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
            TextView t = Ps2Ui.text(this, names[i], 13, Ps2Ui.DIM, true);
            t.setLetterSpacing(0.08f);
            t.setSingleLine(true);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
            tl.leftMargin = dp(7);
            box.addView(t, tl);
            tabs[i] = t;
            tabIcons[i] = icon;
            tabBoxes[i] = box;
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, WRAP, 1f);
            bl.leftMargin = dp(2);
            bl.rightMargin = dp(2);
            tabBar.addView(box, bl);
        }
        content.addView(tabBar, new LinearLayout.LayoutParams(MATCH, WRAP));

        FrameLayout pageFrame = new FrameLayout(this);
        LinearLayout.LayoutParams pfl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        pfl.topMargin = dp(6);
        content.addView(pageFrame, pfl);
        pages[PAGE_CAMERA] = buildCameraPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        pages[PAGE_LOG] = buildLogPage();
        for (View p : pages) pageFrame.addView(p, new FrameLayout.LayoutParams(MATCH, MATCH));

        saveOverlay = new SaveOverlay(this);
        root.addView(saveOverlay, new FrameLayout.LayoutParams(MATCH, MATCH));
        setContentView(root);
    }

    /** Dark status-bar icons on the light Retro look, light icons otherwise. */
    private void applySystemBars() {
        boolean light = Ps2Ui.theme.light;
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController wic = getWindow().getInsetsController();
            if (wic != null) {
                int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                wic.setSystemBarsAppearance(light ? mask : 0, mask);
            }
        } else {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            d.setSystemUiVisibility(light ? (f | mask) : (f & ~mask));
        }
        getWindow().getDecorView().setBackgroundColor(Ps2Ui.theme.bgBottom);
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, 0, weight));
        return v;
    }

    private View buildCameraPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.addView(spacer(0.5f));

        // ---- picture frame ----
        previewFrame = new AspectLayout(this);
        // a visible frame around the picture
        previewFrame.setBackground(Ps2Ui.panel(this, Ps2Ui.theme.light ? 0xFFB3B5BA : 0xCC040B22,
                Ps2Ui.theme.light ? 0xFF6F7278 : Ps2Ui.CYAN, 10));
        int fp = dp(Ps2Ui.theme.glow ? 6 : 5);
        previewFrame.setPadding(fp, fp, fp, fp);
        screen = new FrameLayout(this);
        screen.setBackgroundColor(0xFF000000);
        previewFrame.addView(screen, new FrameLayout.LayoutParams(MATCH, MATCH));

        // one picture per camera; tiles are added by layoutTiles()
        grid = new CamGrid(this, dp(3));
        screen.addView(grid, new FrameLayout.LayoutParams(MATCH, MATCH));

        noCamera = new LinearLayout(this);
        noCamera.setOrientation(LinearLayout.VERTICAL);
        noCamera.setGravity(Gravity.CENTER);
        TextView nc1 = Ps2Ui.glow(Ps2Ui.text(this, "No camera connected", 18, Ps2Ui.HUD, true), 0x806FB5FF);
        TextView nc2 = Ps2Ui.text(this, "Plug in your EyeToy with the USB adapter", 13, Ps2Ui.HUD_DIM, false);
        TextView connectBtn = Ps2Ui.text(this, "CONNECT", 14, Ps2Ui.SEL_TEXT, true);
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
        screen.addView(noCamera, new FrameLayout.LayoutParams(MATCH, MATCH));

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(look.zoom * d.getScaleFactor(), true);
                return true;
            }
        });
        // with several cameras, tapping a picture makes it the main camera
        final android.view.GestureDetector taps = new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(android.view.MotionEvent e) {
                        onTileTapped(grid.tileAt(e.getX(), e.getY()));
                        return true;
                    }
                });
        screen.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            if (!pinch.isInProgress()) taps.onTouchEvent(e);
            return true;
        });

        brackets = new FocusBrackets(this);
        brackets.setVisibility(View.GONE);
        screen.addView(brackets, new FrameLayout.LayoutParams(MATCH, MATCH));

        waveRing = new WaveRing(this);
        screen.addView(waveRing, new FrameLayout.LayoutParams(MATCH, MATCH));

        // ---- top-left: REC indicator, then mode / size / live info ----
        LinearLayout topLeft = new LinearLayout(this);
        topLeft.setOrientation(LinearLayout.VERTICAL);
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
        recSub = Ps2Ui.text(this, "0:00", 13, Ps2Ui.HUD, false);
        LinearLayout.LayoutParams rsl = new LinearLayout.LayoutParams(WRAP, WRAP);
        rsl.leftMargin = dp(8);
        recBox.addView(recSub, rsl);
        recBox.setVisibility(View.GONE);
        LinearLayout.LayoutParams rbl = new LinearLayout.LayoutParams(WRAP, WRAP);
        rbl.bottomMargin = dp(6);
        topLeft.addView(recBox, rbl);
        hudMode = Ps2Ui.hud(this, "", 13, Ps2Ui.HUD);
        hudMode.setLineSpacing(0, 1.1f);
        topLeft.addView(hudMode);
        FrameLayout.LayoutParams tll = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        tll.setMargins(dp(10), dp(10), 0, 0);
        screen.addView(topLeft, tll);

        // ---- bottom corners: date / time, free storage ----
        hudClock = Ps2Ui.hud(this, "", 13, Ps2Ui.HUD);
        hudClock.setLineSpacing(0, 1.1f);
        FrameLayout.LayoutParams hcl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.START);
        hcl.setMargins(dp(10), 0, 0, dp(10));
        screen.addView(hudClock, hcl);
        hudStore = Ps2Ui.hud(this, "", 12, Ps2Ui.HUD);
        hudStore.setPadding(dp(7), dp(3), dp(7), dp(3));
        hudStore.setBackground(Ps2Ui.panel(this, 0x66000000, 0xCCFFFFFF, 2));
        FrameLayout.LayoutParams hsl = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        hsl.setMargins(0, 0, dp(10), dp(10));
        screen.addView(hudStore, hsl);
        hudMode.setVisibility(View.GONE);
        hudClock.setVisibility(View.GONE);
        hudStore.setVisibility(View.GONE);

        countdownView = Ps2Ui.glow(Ps2Ui.text(this, "3", 96, Ps2Ui.HUD, true), 0xCC6FB5FF);
        countdownView.setGravity(Gravity.CENTER);
        countdownView.setVisibility(View.GONE);
        screen.addView(countdownView, new FrameLayout.LayoutParams(MATCH, MATCH));

        flashView = new View(this);
        flashView.setBackgroundColor(Color.WHITE);
        flashView.setVisibility(View.GONE);
        screen.addView(flashView, new FrameLayout.LayoutParams(MATCH, MATCH));

        page.addView(previewFrame, new LinearLayout.LayoutParams(MATCH, WRAP));
        page.addView(buildToolsRow());
        page.addView(spacer(1f));

        // ---- mode selector ----
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setGravity(Gravity.CENTER);
        modeRow.setBackground(Ps2Ui.panel(this, Ps2Ui.PANEL, Ps2Ui.EDGE, 8));
        modeRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        String[] mn = {"VIDEO", "PHOTO", "TIME-LAPSE"};
        final String[] ids = {Prefs.MODE_VIDEO, Prefs.MODE_PHOTO, Prefs.MODE_TIMELAPSE};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = Ps2Ui.text(this, mn[i], 13, Ps2Ui.DIM, true);
            t.setLetterSpacing(0.06f);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            t.setOnClickListener(v -> setMode(ids[idx]));
            modeTabs[i] = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, WRAP, 1f);
            lp.leftMargin = dp(1);
            lp.rightMargin = dp(1);
            modeRow.addView(t, lp);
        }
        page.addView(modeRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // ---- controls: two buttons either side of the shutter ----
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        mirrorBtn = squareButton(IconView.MIRROR, "MIRROR");
        mirrorBtn.setOnClickListener(v -> setMirror(!look.mirror));
        flipBtn = squareButton(IconView.FLIP, "FLIP");
        flipBtn.setOnClickListener(v -> setFlip(!look.flip));
        LinearLayout logBtn = squareButton(IconView.LOG, "LOG");
        logBtn.setOnClickListener(v -> showPage(PAGE_LOG));
        LinearLayout settingsBtn = squareButton(IconView.GEAR, "SETTINGS");
        settingsBtn.setOnClickListener(v -> showPage(PAGE_SETTINGS));
        shutter = new ShutterView(this);
        shutter.setOnClickListener(v -> onShutterClick());

        controls.addView(buttonColumn(mirrorBtn, flipBtn), new LinearLayout.LayoutParams(0, WRAP, 1f));
        controls.addView(shutter, new LinearLayout.LayoutParams(dp(112), dp(112)));
        controls.addView(buttonColumn(logBtn, settingsBtn), new LinearLayout.LayoutParams(0, WRAP, 1f));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(10);
        clp.bottomMargin = dp(Ps2Ui.theme.light ? 22 : 6);
        page.addView(controls, clp);
        return page;
    }

    /** Under the picture: last photo or video, zoom slider, and the photo self-timer. */
    private View buildToolsRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 6));
        thumb.setPadding(dp(3), dp(3), dp(3), dp(3));
        thumb.setImageBitmap(lastThumb);
        thumb.setOnClickListener(v -> openLast());
        row.addView(thumb, new LinearLayout.LayoutParams(dp(50), dp(50)));

        LinearLayout zoomCol = new LinearLayout(this);
        zoomCol.setOrientation(LinearLayout.VERTICAL);
        zoomLabel = Ps2Ui.text(this, "", 11, Ps2Ui.DIM, true);
        zoomLabel.setLetterSpacing(0.08f);
        zoomCol.addView(zoomLabel);
        zoomBar = new SeekBar(this);
        zoomBar.setMax(300);
        zoomBar.setProgress(Math.round((look.zoom - 1f) * 100));
        zoomBar.setProgressTintList(ColorStateList.valueOf(Ps2Ui.CYAN));
        zoomBar.setThumbTintList(ColorStateList.valueOf(Ps2Ui.WHITE));
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setZoom(1f + p / 100f, false);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        zoomCol.addView(zoomBar, new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout.LayoutParams zl = new LinearLayout.LayoutParams(0, WRAP, 1f);
        zl.leftMargin = dp(12);
        zl.rightMargin = dp(12);
        row.addView(zoomCol, zl);
        updateZoomLabel();

        timerChip = Ps2Ui.text(this, "", 12, Ps2Ui.WHITE, true);
        timerChip.setLetterSpacing(0.06f);
        timerChip.setGravity(Gravity.CENTER);
        timerChip.setSingleLine(true);
        timerChip.setPadding(dp(10), dp(8), dp(10), dp(8));
        timerChip.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8));
        timerChip.setMinWidth(dp(86));
        timerChip.setOnClickListener(v -> {
            prefs.putI("timer_idx", (prefs.i("timer_idx", 0) + 1) % TIMER_S.length);
            updateTimerChip();
        });
        row.addView(timerChip, new LinearLayout.LayoutParams(WRAP, WRAP));
        updateTimerChip();

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(MATCH, WRAP);
        rl.topMargin = dp(12);
        row.setLayoutParams(rl);
        return row;
    }

    private void updateTimerChip() {
        int s = TIMER_S[prefs.i("timer_idx", 0)];
        timerChip.setText(s == 0 ? "TIMER OFF" : "TIMER " + s + "S");
        timerChip.setTextColor(s == 0 ? Ps2Ui.DIM : Ps2Ui.WHITE);
    }

    private void updateZoomLabel() {
        zoomLabel.setText(String.format(Locale.US, "ZOOM %.1f\u00D7", look.zoom));
    }

    private void setZoom(float z, boolean fromPinch) {
        z = Math.max(1f, Math.min(4f, z));
        look.zoom = z;
        prefs.putI("zoom_pct", Math.round(z * 100));
        if (fromPinch) zoomBar.setProgress(Math.round((z - 1f) * 100));
        updateZoomLabel();
        applyLookToPreview();
    }

    /** A square button with an icon over a small label. */
    private LinearLayout squareButton(int iconType, String label) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setPadding(dp(4), dp(8), dp(4), dp(6));
        b.setBackground(buttonBg(false));
        IconView icon = new IconView(this, iconType);
        b.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));
        TextView t = Ps2Ui.text(this, label, 10, Ps2Ui.WHITE, true);
        t.setLetterSpacing(0.06f);
        t.setSingleLine(true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(WRAP, WRAP);
        tl.topMargin = dp(4);
        b.addView(t, tl);
        b.setTag(new View[]{icon, t});
        return b;
    }

    private View buttonColumn(View top, View bottom) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(78), dp(62));
        a.bottomMargin = dp(10);
        col.addView(top, a);
        col.addView(bottom, new LinearLayout.LayoutParams(dp(78), dp(62)));
        return col;
    }

    private android.graphics.drawable.Drawable buttonBg(boolean on) {
        return on ? Ps2Ui.selected(this, 8) : Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8);
    }

    private void paintButton(LinearLayout b, boolean on) {
        b.setBackground(buttonBg(on));
        View[] parts = (View[]) b.getTag();
        int col = on ? Ps2Ui.SEL_TEXT : Ps2Ui.WHITE;
        ((IconView) parts[0]).setColor(col);
        ((TextView) parts[1]).setTextColor(col);
    }

    /** Updates the date, time and free storage shown on the picture. */
    private final Runnable hudTick = new Runnable() {
        @Override
        public void run() {
            if (hudClock == null) return;
            Date now = new Date();
            hudClock.setText(new SimpleDateFormat("MMM dd yyyy", Locale.US).format(now).toUpperCase(Locale.US)
                    + "\n" + new SimpleDateFormat("hh:mm a", Locale.US).format(now));
            hudStore.setText(String.format(Locale.US, "▮ %.1f GB", freeBytes() / 1073741824.0));
            ui.removeCallbacks(this);
            ui.postDelayed(this, 5000);
        }
    };

    private void updateHudMode() {
        if (hudMode == null) return;
        String m = Prefs.MODE_PHOTO.equals(mode) ? "PHOTO" : Prefs.MODE_TIMELAPSE.equals(mode) ? "TIME-LAPSE" : "VIDEO";
        hudMode.setText(m + "\n640×480\n" + hudLive);
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

        // ---- look ----
        LinearLayout lookBox = Ps2Ui.section(this, settingsList, "Look");
        lookBox.addView(Ps2Ui.toggleRow(this, "Details on screen",
                "Mode, size, frame rate, date, time and free space over the picture. Turn off for a clearer frame.",
                prefs.b("hud_screen", true), on -> {
                    prefs.putB("hud_screen", on);
                    setHudVisible(streaming);
                }));
        lookBox.addView(Ps2Ui.toggleRow(this, "Details on video",
                "Burns size, frame rate, camera name, date and time into the video itself. Applies to the next recording.",
                prefs.b("hud_video", false), on -> prefs.putB("hud_video", on)));
        lookBox.addView(Ps2Ui.segmentRow(this, "Style", "Changes colours and buttons only. Everything works the same.",
                Theme.NAMES, Theme.index(prefs.s("theme", Theme.DEFAULT)), i -> {
                    if (Theme.IDS[i].equals(prefs.s("theme", Theme.DEFAULT))) return;
                    if (isRecording() || countingDown || saveOverlay.isShowing()) {
                        Toast.makeText(this, "Stop recording first, then change the style.", Toast.LENGTH_SHORT).show();
                        ui.post(this::fillSettings);
                        return;
                    }
                    prefs.putS("theme", Theme.IDS[i]);
                    ui.post(this::rebuildUi);
                }));

        // ---- camera name ----
        LinearLayout nameBox = Ps2Ui.section(this, settingsList, "Camera name");
        TextView nameHint = Ps2Ui.text(this,
                camList().size() > 1
                        ? "Name for the main camera (CAM " + (main == null ? 1 : main.number()) + "). Tap another picture on the Camera tab to name that one."
                        : cameraSerial.isEmpty()
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
            ch.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8));
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
        nameField.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8));
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

        // ---- several cameras ----
        LinearLayout multi = Ps2Ui.section(this, settingsList, "Cameras");
        multi.addView(Ps2Ui.segmentRow(this, "Layout with more than one camera",
                "Auto stacks two cameras and uses a 2\u00D72 grid for three or four. Tap a picture to make it the main camera, the one that records and takes photos.",
                new String[]{"Auto", "Stacked", "Grid"}, prefs.i("multi_layout", CamGrid.AUTO), i -> {
                    prefs.putI("multi_layout", i);
                    layoutTiles();
                }));
        multi.addView(Ps2Ui.toggleRow(this, "Share USB bandwidth",
                "Turn on if a second camera won't start through your hub. Every camera then uses a smaller stream at up to 10 fps. Restarts the cameras.",
                prefs.b("usb_share", false), on -> {
                    prefs.putB("usb_share", on);
                    if (streaming) {
                        if (isRecording()) log("Sharing applies after you stop recording and reconnect.");
                        else restartCamera();
                    }
                }));

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
        vid.addView(Ps2Ui.segmentRow(this, "Start a new file every",
                "Splits long videos into parts so one problem never loses everything. There can be a gap of about a second between parts.",
                SPLIT_NAMES, prefs.i("split_idx", 0), i -> prefs.putI("split_idx", i)));

        // ---- picture ----
        LinearLayout pic = Ps2Ui.section(this, settingsList, "Picture");
        pic.addView(buildBrightnessRow());
        pic.addView(Ps2Ui.toggleRow(this, "Mirror", "Flip left and right, like a selfie.", look.mirror, this::setMirror));
        pic.addView(Ps2Ui.toggleRow(this, "Flip upside down", null, look.flip, this::setFlip));

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
        disc.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8));
        disc.setText(camList().size() > 1 ? "DISCONNECT ALL CAMERAS" : "DISCONNECT CAMERA");
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
        java.util.List<Cam> list = camList();
        Cam m = main;
        if (m != null && streaming) {
            sb.append("Sony EyeToy USB camera (054c:0155)\n");
            sb.append("Chip: OV519    Sensor: ").append(sensorName.isEmpty() ? "?" : sensorName).append("\n");
            sb.append("Picture: 640 x 480 (").append(Metadata.megapixels(640, 480)).append("), JPEG\n");
            sb.append("Camera ID: ").append(cameraSerial.isEmpty() ? "none given by the camera" : cameraSerial).append("\n");
            if (list.size() > 1) {
                sb.append("\nCameras running: ").append(list.size()).append("\n");
                for (Cam c : list) {
                    sb.append(String.format(Locale.US, "Cam %d%s: %s, %d bytes/packet, %d fps set%s\n", c.number(),
                            c == m ? " (main)" : "", prefs.s("label_" + c.key(), "EyeToy"), c.psize, c.fps,
                            c.serial.isEmpty() ? "" : ", ID " + c.serial));
                }
            }
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

        statusView = Ps2Ui.text(this, lastStatus, 12, Ps2Ui.CYAN, false);
        page.addView(statusView);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextColor(Ps2Ui.LOG_TEXT);
        logView.setTextSize(12);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        logScroll.setBackground(Ps2Ui.panel(this, Ps2Ui.LOG_BG, Ps2Ui.EDGE, 8));
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

    private TextView logButton(String s) {
        TextView t = Ps2Ui.text(this, s, 13, Ps2Ui.WHITE, true);
        t.setLetterSpacing(0.1f);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(11), 0, dp(11));
        t.setBackground(Ps2Ui.panel(this, Ps2Ui.IDLE, Ps2Ui.EDGE, 8));
        return t;
    }

    // ------------------------------------------------------------------ UI state

    private void showPage(int p) {
        if (p == currentPage) return;
        currentPage = p;
        for (int i = 0; i < 3; i++) {
            pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
            boolean on = i == p;
            int col = on ? Ps2Ui.SEL_TEXT : Ps2Ui.DIM;
            tabs[i].setTextColor(col);
            tabIcons[i].setColor(col);
            tabBoxes[i].setBackground(on ? Ps2Ui.selected(this, 18) : Ps2Ui.idle(this, 8));
            if (on && !Ps2Ui.theme.light) Ps2Ui.glow(tabs[i], 0x806FB5FF); else tabs[i].setShadowLayer(0, 0, 0, 0);
        }
        if (p == PAGE_SETTINGS) fillSettings();
        if (p == PAGE_LOG) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Rebuilds the whole screen in the newly chosen style, keeping the camera running. */
    private void rebuildUi() {
        int page = currentPage;
        currentPage = -1;
        ui.removeCallbacks(hudTick);
        ui.removeCallbacks(recBlink);
        buildUi();
        applyLookToPreview();
        updateModeUi();
        logView.setText(logText);
        layoutTiles();
        if (streaming) onCameraLive(); else onCameraGone();
        showPage(page < 0 ? PAGE_CAMERA : page);
    }

    private void applyLookToPreview() {
        for (Cam c : camList()) if (c.view != null) applyLook(c.view);
        paintButton(mirrorBtn, look.mirror);
        paintButton(flipBtn, look.flip);
    }

    private void applyLook(ImageView v) {
        v.setScaleX((look.mirror ? -1f : 1f) * look.zoom);
        v.setScaleY((look.flip ? -1f : 1f) * look.zoom);
        v.setColorFilter(look.filter());
    }

    // ------------------------------------------------------------------ camera tiles

    /** Running cameras in slot order. */
    private java.util.List<Cam> camList() {
        java.util.List<Cam> l = new java.util.ArrayList<>();
        synchronized (cams) {
            for (Cam c : cams) if (c != null) l.add(c);
        }
        return l;
    }

    /**
     * (Re)builds one picture tile per running camera, shapes the frame for the chosen
     * layout, and puts the wave ring on the main camera. UI thread.
     */
    private void layoutTiles() {
        if (grid == null) return;
        java.util.List<Cam> list = camList();
        grid.removeAllViews();
        if (waveRing.getParent() != null) ((ViewGroup) waveRing.getParent()).removeView(waveRing);
        boolean multi = list.size() > 1;
        Cam m = main;
        for (Cam c : list) {
            FrameLayout tile = new FrameLayout(this);
            boolean isMain = c == m;
            int pad = multi ? dp(2) : 0;
            tile.setPadding(pad, pad, pad, pad);
            tile.setBackgroundColor(multi && isMain ? Ps2Ui.CYAN : 0xFF000000);
            FrameLayout inner = new FrameLayout(this);
            inner.setBackgroundColor(0xFF000000);
            inner.setClipChildren(true);
            tile.addView(inner, new FrameLayout.LayoutParams(MATCH, MATCH));
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            if (c.view != null && c.view.getDrawable() != null) v.setImageDrawable(c.view.getDrawable());
            applyLook(v);
            inner.addView(v, new FrameLayout.LayoutParams(MATCH, MATCH));
            TextView lb = Ps2Ui.hud(this, tileText(c, isMain), 11, Ps2Ui.HUD);
            lb.setPadding(dp(6), dp(2), dp(6), dp(2));
            lb.setBackground(Ps2Ui.panel(this, 0x88000000, 0, 4));
            lb.setVisibility(multi ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            ll.bottomMargin = dp(4);
            inner.addView(lb, ll);
            if (isMain) inner.addView(waveRing, new FrameLayout.LayoutParams(MATCH, MATCH));
            c.tile = tile;
            c.view = v;
            c.label = lb;
            grid.addView(tile);
        }
        if (waveRing.getParent() == null) screen.addView(waveRing, screen.indexOfChild(grid) + 1,
                new FrameLayout.LayoutParams(MATCH, MATCH));
        grid.setShape(list.size(), prefs.i("multi_layout", CamGrid.AUTO));
        previewFrame.setRatio(grid.ratio());
    }

    private String tileText(Cam c, boolean isMain) {
        return "CAM " + c.number() + (isMain ? " \u25CF MAIN" : "") + (c.live ? " \u00B7 " + c.liveFps + " FPS" : "");
    }

    /** Tapping a picture makes that camera the main one (the one that records and takes photos). */
    private void onTileTapped(int index) {
        java.util.List<Cam> list = camList();
        if (list.size() < 2 || index < 0 || index >= list.size()) return;
        Cam c = list.get(index);
        if (c == main) return;
        if (isRecording() || countingDown || saveOverlay.isShowing()) {
            Toast.makeText(this, "Stop recording first, then pick another camera.", Toast.LENGTH_SHORT).show();
            return;
        }
        setMain(c);
        log("Camera " + c.number() + " is now the main camera.");
        layoutTiles();
        if (currentPage == PAGE_SETTINGS) fillSettings();
    }

    /** Makes c the camera that records, takes photos and watches for waves (null when none is left). */
    private void setMain(Cam c) {
        main = c;
        lastGoodJpeg = null;
        framesShown = 0;
        framesBad = 0;
        wave.reset();
        if (c != null) {
            sensorName = c.sensorName;
            cameraSerial = c.serial;
            cameraKey = c.key();
        }
    }

    private void setFlip(boolean on) {
        look.flip = on;
        prefs.putB("flip", on);
        applyLookToPreview();
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
            modeTabs[i].setTextColor(on ? Ps2Ui.SEL_TEXT : Ps2Ui.DIM);
            modeTabs[i].setBackground(on ? Ps2Ui.selected(this, 18) : null);
        }
        updateHudMode();
        timerChip.setVisibility(Prefs.MODE_PHOTO.equals(mode) ? View.VISIBLE : View.INVISIBLE);
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
        setHudVisible(true);
        waveRing.setActive(prefs.b("wave", true));
        if (currentPage == PAGE_SETTINGS) fillSettings();
    }

    private void onCameraGone() {
        noCamera.setVisibility(View.VISIBLE);
        layoutTiles();
        waveRing.setActive(false);
        hudLive = "";
        setHudVisible(false);
        recBox.setVisibility(View.GONE);
        endCountdown();
        background.setAnimated(true);
        if (currentPage == PAGE_SETTINGS) fillSettings();
    }

    private void setHudVisible(boolean on) {
        on = on && prefs.b("hud_screen", true);
        int v = on ? View.VISIBLE : View.GONE;
        hudMode.setVisibility(v);
        hudClock.setVisibility(v);
        hudStore.setVisibility(v);
        brackets.setVisibility(v);
        ui.removeCallbacks(hudTick);
        if (on) hudTick.run();
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
        ui.post(() -> {
            lastStatus = s;
            statusView.setText(s);
        });
    }

    private void copyLog() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText("EyeToy Cam log", logText.toString()));
        Toast.makeText(this, "Log copied. Paste it to Claude.", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ USB setup

    private static boolean isEyeToy(UsbDevice d) {
        return d != null && d.getVendorId() == SONY_VID && (d.getProductId() == 0x0155 || d.getProductId() == 0x0154);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            UsbDevice d = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class)
                    : intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (ACTION_PERMISSION.equals(a)) {
                permAsking = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    log("Permission granted.");
                    if (d == null) d = findEyeToy(false);
                    UsbDevice dev = d;
                    if (dev != null) worker.execute(() -> openAndStart(dev));
                } else {
                    if (d != null) pending.remove(d.getDeviceName());
                    log("Permission was refused. Tap Connect and choose Allow.");
                }
                askNextPermission();
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                // another camera plugged in (into the hub) while the app is open
                if (isEyeToy(d) && findCam(d.getDeviceName()) == null && !pending.contains(d.getDeviceName())) {
                    log("EyeToy plugged in.");
                    connect();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (d != null && d.getVendorId() == SONY_VID) {
                    pending.remove(d.getDeviceName());
                    final Cam c = findCam(d.getDeviceName());
                    if (c != null) {
                        log(camList().size() > 1 ? "Camera " + c.number() + " unplugged." : "EyeToy unplugged.");
                        worker.execute(() -> stopCam(c));
                    }
                }
            }
        }
    };

    private Cam findCam(String devName) {
        synchronized (cams) {
            for (Cam c : cams) if (c != null && c.devName.equals(devName)) return c;
        }
        return null;
    }

    private UsbDevice findEyeToy(boolean verbose) {
        java.util.List<UsbDevice> all = findEyeToys(verbose);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Every EyeToy on the bus (directly or through a hub), in a stable order. */
    private java.util.List<UsbDevice> findEyeToys(boolean verbose) {
        java.util.List<UsbDevice> out = new java.util.ArrayList<>();
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (verbose) log(String.format("  USB device found: %04x:%04x %s", d.getVendorId(), d.getProductId(),
                    d.getProductName() == null ? "" : d.getProductName()));
            if (isEyeToy(d)) out.add(d);
        }
        java.util.Collections.sort(out, (x, y) -> x.getDeviceName().compareTo(y.getDeviceName()));
        return out;
    }

    /** Starts every EyeToy that isn't running yet, asking for permission one camera at a time. */
    private void connect() {
        log("Looking for EyeToys...");
        if (usb.getDeviceList().isEmpty()) {
            log("No USB devices at all. Check the adapter is plugged in fully, and that the phone isn't in charge-only mode.");
            Toast.makeText(this, "No camera found. Check the adapter.", Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<UsbDevice> found = findEyeToys(true);
        if (found.isEmpty()) {
            log("Didn't find an EyeToy (054c:0155) in the list above.");
            Toast.makeText(this, "No EyeToy found.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (found.size() > 1) log(found.size() + " EyeToys found.");
        int started = 0;
        for (UsbDevice d : found) {
            String name = d.getDeviceName();
            if (findCam(name) != null || !pending.add(name)) continue;
            started++;
            if (usb.hasPermission(d)) {
                worker.execute(() -> openAndStart(d));
            } else {
                permQueue.add(d);
            }
        }
        if (started == 0) log(found.size() > 1 ? "All cameras are already running." : "Already streaming (or starting).");
        askNextPermission();
    }

    /** Android shows one permission box at a time, so cameras are asked about in turn. UI thread. */
    private void askNextPermission() {
        if (permAsking) return;
        UsbDevice d = permQueue.poll();
        if (d == null) return;
        permAsking = true;
        log("Asking for permission to use the camera...");
        Intent i = new Intent(ACTION_PERMISSION).setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        usb.requestPermission(d, PendingIntent.getBroadcast(this, 0, i, flags));
    }

    /** The frame rate the camera itself should run at right now. */
    private int camFps() {
        return Prefs.MODE_TIMELAPSE.equals(mode) ? 5 : prefs.fps();
    }

    /** Stops and reopens every camera (main one first, so it gets the most USB bandwidth). */
    private void restartCamera() {
        worker.execute(() -> {
            java.util.List<UsbDevice> devs = new java.util.ArrayList<>();
            Cam m = main;
            if (m != null) devs.add(m.dev);
            for (Cam c : camList()) if (c != m) devs.add(c.dev);
            stopAll();
            for (UsbDevice d : devs) {
                pending.add(d.getDeviceName());
                openAndStart(d);
            }
            Cam first = m == null ? null : findCam(m.devName);
            if (first != null && first != main) {
                setMain(first);
                ui.post(this::layoutTiles);
            }
        });
    }

    private int freeSlot() {
        synchronized (cams) {
            for (int i = 0; i < cams.length; i++) if (cams[i] == null) return i;
        }
        return -1;
    }

    /** Runs on the worker thread. */
    private void openAndStart(UsbDevice d) {
        try {
            if (findCam(d.getDeviceName()) != null) return;
            int slot = freeSlot();
            if (slot < 0) {
                log("This app shows up to " + IsoStream.MAX_SLOTS + " cameras at once; this one is left off.");
                return;
            }
            final Cam c = new Cam(slot, d);
            final int n = c.number();
            EyeToyDriver.Logger clog = slot == 0 && camList().isEmpty() ? this::log : m -> log("Cam " + n + ": " + m);
            clog.log("Opening camera...");
            clog.log("Camera ID: " + (c.serial.isEmpty() ? "(none given)" : c.serial)
                    + " · " + d.getManufacturerName() + " / " + d.getProductName() + " · USB " + c.devName);
            int want = camFps();
            boolean ok = false;
            try {
                ok = c.open(usb, want, prefs.b("usb_share", false), clog);
            } catch (Exception e) {
                clog.log("ERROR: " + e.getMessage());
                Log.e(TAG, "open", e);
            }
            if (!ok) {
                c.close();
                return;
            }
            synchronized (cams) { cams[slot] = c; }
            if (main == null) {
                startedFps = want;
                setMain(c);
            }
            clog.log("USB stream running. Waiting for pictures...");
            streaming = true;
            ui.post(this::layoutTiles);
            c.running = true;
            c.thread = new Thread(() -> frameLoop(c), "frames" + n);
            c.thread.start();
        } finally {
            pending.remove(d.getDeviceName());
        }
    }

    // ------------------------------------------------------------------ frames

    private static Bitmap decode(byte[] f, int sample) {
        try {
            if (sample <= 1) return BitmapFactory.decodeByteArray(f, 0, f.length);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(f, 0, f.length, o);
        } catch (Throwable t) {
            return null;
        }
    }

    /** One per camera. Every camera feeds its own picture; only the main one records, takes photos and sees waves. */
    private void frameLoop(Cam c) {
        long lastStatus = 0, started = System.currentTimeMillis();
        int lastShown = 0;
        boolean warnedNoData = false;
        String tag = "Cam " + c.number() + ": ";
        while (c.running) {
            byte[] f = IsoStream.nativeGetFrame(c.slot);
            long now = System.currentTimeMillis();
            if (f != null) {
                boolean isMain = c == main;
                // side cameras are shown small, so half size is plenty and decodes faster
                int sample = isMain || camList().size() < 2 ? 1 : 2;
                Bitmap bmp = decode(f, sample);
                byte[] used = f;
                if (bmp == null && JpegFix.looksLikeJpeg(f)) {
                    byte[] fixed = JpegFix.addHuffmanTables(f);
                    if (fixed != f) {
                        bmp = decode(fixed, sample);
                        used = fixed;
                    }
                }
                if (bmp != null) {
                    c.framesShown++;
                    if (!c.live) {
                        c.live = true;
                        log((camList().size() > 1 ? tag : "") + "First picture received! " + bmp.getWidth() + "x" + bmp.getHeight()
                                + (used != f ? " (needed Huffman tables added)" : ""));
                        ui.post(() -> {
                            onCameraLive();
                            layoutTiles();
                        });
                    }
                    if (isMain) {
                        framesShown++;
                        lastGoodJpeg = used;
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
                    }
                    final Bitmap show = bmp;
                    ui.post(() -> {
                        if (c.view != null) c.view.setImageBitmap(show);
                    });
                } else {
                    c.framesBad++;
                    if (c == main) framesBad++;
                    if (c.framesBad <= 3)
                        log(tag + "Frame " + f.length + " bytes couldn't be decoded. Starts: " + JpegFix.hex(f, 24));
                }
            } else {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { }
            }

            if (now - lastStatus >= 1000) {
                long[] s = IsoStream.nativeStats(c.slot);
                final int fps = c.framesShown - lastShown;
                lastShown = c.framesShown;
                lastStatus = now;
                c.liveFps = fps;
                if (c == main) statusTick(fps, s);
                ui.post(() -> {
                    if (c.label != null) c.label.setText(tileText(c, c == main));
                });
                if (!warnedNoData && now - started > 5000 && c.framesShown == 0) {
                    warnedNoData = true;
                    log(String.format(Locale.US, tag + "No picture after 5s. urbs %d, packets %d, data packets %d, bytes %d, frames %d, dropped %d, pkt errors %d, reap errors %d, submit errors %d, last errno %d",
                            s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9]));
                    log(IsoStream.nativeDebug(c.slot));
                    log("Open the Log tab, tap Copy log and send it to Claude.");
                }
                if (!IsoStream.nativeIsRunning(c.slot)) {
                    log(tag + "USB stream stopped by itself (errno " + s[9] + ").");
                    worker.execute(() -> stopCam(c));
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
                recText = String.format(Locale.US, "%s%d:%02d", recPart > 1 ? "PART " + recPart + " \u00B7 " : "",
                        sec / 60, sec % 60);
                int splitMin = SPLIT_MIN[prefs.i("split_idx", 0)];
                if (splitMin > 0 && !rotating && rec.elapsedMs() >= splitMin * 60_000L) {
                    rotating = true;
                    worker.execute(this::rotateRecording);
                }
            }
            if (freeBytes() < MIN_FREE_BYTES) {
                log("Storage is almost full, so the recording is being saved and stopped.");
                worker.execute(this::stopRecording);
            }
        }
        final String label = currentLabel();
        final String live = String.format(Locale.US, "%d FPS · %s", fps, label.toUpperCase(Locale.US));
        final String rt = recText;
        ui.post(() -> {
            hudLive = live;
            updateHudMode();
            if (rt != null) recSub.setText(rt);
        });
        status(String.format(Locale.US,
                "Streaming · %d fps · shown %d · bad %d · frames %d · dropped %d · data packets %d · KB %d · pkt err %d",
                fps, framesShown, framesBad, s[4], s[5], s[2], s[3] / 1024, s[6]));
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
                // with zoom, the ring covers a smaller part of the camera's picture
                float uu = 0.5f + (u - 0.5f) / look.zoom, vv = 0.5f + (v - 0.5f) / look.zoom;
                int x = (int) ((mirror ? 1f - uu : uu) * (w - 1));
                int y = (int) ((flip ? 1f - vv : vv) * (h - 1));
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
        int t = TIMER_S[prefs.i("timer_idx", 0)];
        if (Prefs.MODE_PHOTO.equals(mode) && t > 0 && !countingDown) {
            log("Photo in " + t + " seconds.");
            startCountdown(t, this::doShutter);
            return;
        }
        doShutter();
    }

    /** Runs on the UI thread. Takes the photo or starts/stops the recording now. */
    private void doShutter() {
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
            startCountdown(3, this::doShutter);
        }
    }

    private void startCountdown(int seconds, Runnable then) {
        countingDown = true;
        countdownView.setVisibility(View.VISIBLE);
        final int[] n = {seconds};
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
                    then.run();
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
            setLast(u, false);
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
        if (isRecording() || rotating) {
            worker.execute(this::stopRecording);
            return;
        }
        if (!streaming) {
            log("Connect the camera first, then tap the shutter.");
            return;
        }
        final boolean tl = Prefs.MODE_TIMELAPSE.equals(mode);
        final boolean wantSound = !tl && prefs.b("sound", true);
        pendingSound = wantSound;
        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        if (wantSound && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("Asking for microphone permission (for sound)...");
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        // lets the "recording" notice show; recording works without it
        if (Build.VERSION.SDK_INT >= 33 && !prefs.b("asked_notif", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.putB("asked_notif", true);
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 1);
            return;
        }
        worker.execute(() -> startRecording(wantSound));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 1) return;
        final boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (pendingSound && !mic) log("No microphone permission, so recording without sound.");
        final boolean snd = pendingSound && mic;
        if (streaming) worker.execute(() -> startRecording(snd));
    }

    /** Runs on the worker thread. */
    private void startRecording(boolean withSound) {
        if (!streaming || isRecording()) return;
        if (freeBytes() < MIN_FREE_BYTES) {
            log("Not enough free storage to record.");
            ui.post(() -> Toast.makeText(this, "Not enough free storage.", Toast.LENGTH_LONG).show());
            return;
        }
        lastWithSound = withSound;
        recPart = 1;
        Recorder r = openRecorder(withSound);
        if (r == null) return;
        recorder = r;
        RecordService.start(this);
        startLedBlink();
        final boolean tl = r.isTimelapse();
        ui.post(() -> {
            recSub.setText(tl ? "TIME-LAPSE" : "0:00");
            updateRecUi(true);
        });
    }

    /** Opens a new video file and starts it recording. Returns null (and logs why) if it can't. */
    private Recorder openRecorder(boolean withSound) {
        boolean tl = Prefs.MODE_TIMELAPSE.equals(mode);
        String label = currentLabel();
        String base = Metadata.baseName(label, stamp());
        int fps = tl ? TL_OUT_FPS : prefs.fps();
        String desc = Metadata.description(label, sensorName, 640, 480, fps, tl);
        Recorder r = new Recorder(this::log);
        try {
            r.start(this, 640, 480, fps, withSound, look, base, desc, tl, TL_OUT_FPS,
                    prefs.b("hud_video", false), label);
            lastTlMs = 0;
            return r;
        } catch (Exception e) {
            log("Couldn't start recording: " + e.getMessage());
            Log.e(TAG, "startRecording", e);
            r.stop();
            return null;
        }
    }

    /** Runs on the worker thread. Finishes this part and carries straight on into a new file. */
    private void rotateRecording() {
        try {
            Recorder old = recorder;
            if (old == null || !old.isRunning()) return;
            recorder = null;
            old.stop();
            Uri saved = old.savedUri();
            if (saved != null) setLast(saved, true);
            Recorder next = (streaming && freeBytes() >= MIN_FREE_BYTES) ? openRecorder(lastWithSound) : null;
            if (next == null) {
                log("Couldn't carry on into a new file, so the recording has ended.");
                stopLedBlink();
                RecordService.stop(this);
                ui.post(() -> {
                    updateRecUi(false);
                    saveOverlay.showDone("Movies / EyeToyCam");
                });
                return;
            }
            recPart++;
            recorder = next;
        } finally {
            rotating = false;
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
        RecordService.stop(this);
        final String name = r.savedName();
        if (r.savedUri() != null) setLast(r.savedUri(), true);
        ui.post(() -> {
            if (name != null) saveOverlay.showDone("Movies / EyeToyCam");
            else saveOverlay.showFail("The recording had no pictures.");
        });
    }

    // ------------------------------------------------------------------ last photo / video

    /** Remembers the newest saved photo or video and shows its thumbnail. Any thread. */
    private void setLast(Uri u, boolean video) {
        lastUri = u;
        lastIsVideo = video;
        prefs.putS("last_uri", u.toString());
        prefs.putB("last_video", video);
        loadThumb(u);
    }

    /** Builds the thumbnail (do this off the UI thread) and puts it on screen. */
    private void loadThumb(Uri u) {
        Bitmap b = null;
        try {
            b = getContentResolver().loadThumbnail(u, new Size(200, 200), null);
        } catch (Exception ignored) { }
        final Bitmap bb = b;
        ui.post(() -> {
            lastThumb = bb;
            if (thumb != null) thumb.setImageBitmap(bb);
        });
    }

    private void openLast() {
        Uri u = lastUri;
        if (u == null || lastThumb == null) {
            Toast.makeText(this, "Nothing saved yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, lastIsVideo ? "video/mp4" : "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No app found to open it.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ camera light

    private void startLedBlink() {
        final Cam m = main;
        final EyeToyDriver d = m == null ? null : m.driver;
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

    /** Stops one camera; if it was the main one, the next camera takes over. Worker thread. */
    private void stopCam(Cam c) {
        if (c == main) {
            stopRecording();
            stopLedBlink();
        }
        c.running = false;
        if (c.thread != null && c.thread != Thread.currentThread()) {
            try { c.thread.join(1000); } catch (InterruptedException ignored) { }
        }
        c.thread = null;
        c.close();
        synchronized (cams) {
            if (cams[c.slot] == c) cams[c.slot] = null;
        }
        java.util.List<Cam> left = camList();
        if (c == main) {
            setMain(left.isEmpty() ? null : left.get(0));
            if (!left.isEmpty()) log("Camera " + left.get(0).number() + " is now the main camera.");
        }
        if (left.isEmpty()) {
            boolean was = streaming;
            streaming = false;
            if (was) log("Stopped.");
            status("Not connected");
            ui.post(this::onCameraGone);
        } else {
            ui.post(() -> {
                layoutTiles();
                if (currentPage == PAGE_SETTINGS) fillSettings();
            });
        }
    }

    /** Safe to call any time, from the worker thread. */
    private void stopAll() {
        stopRecording();
        stopLedBlink();
        boolean was = streaming;
        // side cameras first, so the main one is never handed over on the way out
        java.util.List<Cam> list = camList();
        Cam m = main;
        for (Cam c : list) if (c != m) stopCam(c);
        if (m != null) stopCam(m);
        streaming = false;
        if (was && list.isEmpty()) log("Stopped.");
        status("Not connected");
        ui.post(this::onCameraGone);
    }
}
