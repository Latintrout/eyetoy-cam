package com.latintrout.eyetoycam;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One connected EyeToy: its USB connection, driver and native stream slot.
 * Several can run at once (one per slot), each identified by its USB device.
 */
final class Cam {
    /** Linux errno for "not enough USB bandwidth". */
    static final int ENOSPC = 28;

    final int slot;
    final UsbDevice dev;
    final String devName;
    UsbDeviceConnection conn;
    private UsbInterface alt0;
    EyeToyDriver driver;
    String sensorName = "";
    String serial = "";
    /** Packet size and frame rate the stream actually got. */
    int psize, fps;
    volatile boolean running;
    Thread thread;
    volatile int framesShown, framesBad, liveFps;
    volatile boolean live;

    // on-screen tile (UI thread only)
    FrameLayout tile;
    ImageView view;
    TextView label;

    Cam(int slot, UsbDevice dev) {
        this.slot = slot;
        this.dev = dev;
        this.devName = dev.getDeviceName();
        String s = null;
        try { s = dev.getSerialNumber(); } catch (Exception ignored) { }
        serial = s == null ? "" : s;
    }

    /** Number shown on screen, 1 to 4. */
    int number() { return slot + 1; }

    /** Key for remembering this camera's name: its serial number, or its position when it has none. */
    String key() {
        if (!serial.isEmpty()) return serial;
        return slot == 0 ? "default" : "cam" + number();
    }

    /**
     * The OV519 sends 640x480 JPEG; a smaller USB packet means less data per second,
     * so the camera is slowed down to match instead of dropping half its frames.
     */
    static int fpsFor(int psize, int want) {
        if (psize >= 896) return want;
        if (psize >= 768) return Math.min(want, 15);
        return Math.min(want, 10);
    }

    /**
     * Opens the camera, wakes it, and starts the stream on the biggest stream setting
     * that fits the USB bandwidth left (or the smallest one when sharing).
     * Runs on the worker thread. On failure the caller must call {@link #close()}.
     */
    boolean open(UsbManager usb, int wantFps, boolean share, EyeToyDriver.Logger log) throws Exception {
        conn = usb.openDevice(dev);
        if (conn == null) { log.log("Couldn't open the camera (openDevice returned nothing)."); return false; }

        List<UsbInterface> alts = new ArrayList<>();
        List<UsbEndpoint> eps = new ArrayList<>();
        alt0 = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface it = dev.getInterface(i);
            StringBuilder sb = new StringBuilder(String.format(Locale.US, "  interface %d alt %d class %d:", it.getId(),
                    it.getAlternateSetting(), it.getInterfaceClass()));
            for (int e = 0; e < it.getEndpointCount(); e++) {
                UsbEndpoint ep = it.getEndpoint(e);
                sb.append(String.format(Locale.US, " [ep 0x%02x %s %s %d]", ep.getAddress(), epType(ep.getType()),
                        ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out", ep.getMaxPacketSize()));
                if (it.getId() == 0 && ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC
                        && ep.getDirection() == UsbConstants.USB_DIR_IN && (ep.getMaxPacketSize() & 0x7ff) > 0) {
                    alts.add(it);
                    eps.add(ep);
                }
            }
            if (slot == 0) log.log(sb.toString());
            if (it.getId() == 0 && it.getAlternateSetting() == 0) alt0 = it;
        }
        if (alt0 == null || alts.isEmpty()) {
            log.log("Couldn't find the video stream on interface 0.");
            return false;
        }
        // biggest first, or smallest first when sharing one USB lane between cameras
        Integer[] order = new Integer[alts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> {
            int pa = eps.get(a).getMaxPacketSize() & 0x7ff, pb = eps.get(b).getMaxPacketSize() & 0x7ff;
            return share ? Integer.compare(pa, pb) : Integer.compare(pb, pa);
        });

        if (!conn.claimInterface(alt0, true)) {
            log.log("Couldn't claim the video interface.");
            return false;
        }
        driver = new EyeToyDriver(conn, log);
        if (!driver.init()) {
            log.log("Camera setup stopped. Open the Log tab, tap Copy log and send it to Claude.");
            return false;
        }
        sensorName = driver.sensorName;

        for (int k = 0; k < order.length; k++) {
            UsbInterface it = alts.get(order[k]);
            UsbEndpoint ep = eps.get(order[k]);
            int ps = ep.getMaxPacketSize() & 0x7ff;
            int f = share ? Math.min(wantFps, 10) : fpsFor(ps, wantFps);
            log.log(String.format(Locale.US, "Selecting stream setting alt %d (packet size %d).", it.getAlternateSetting(), ps));
            if (!conn.setInterface(it)) {
                log.log("  Android refused that stream setting (usually not enough USB bandwidth left).");
                continue;
            }
            driver.start(f);
            int r = IsoStream.nativeStart(slot, conn.getFileDescriptor(), ep.getAddress(), ps);
            if (r == 0) {
                psize = ps;
                fps = f;
                if (k > 0 || f < wantFps)
                    log.log(String.format(Locale.US, "  Running on a smaller stream: %d bytes per packet, %d fps.", ps, f));
                return true;
            }
            log.log("  Couldn't start the USB stream (error " + r
                    + (r == -ENOSPC ? ", not enough USB bandwidth" : "") + ").");
            conn.setInterface(alt0);
        }
        log.log("No stream setting fitted. If other cameras are running, the hub can't feed one more: "
                + "try Settings > Cameras > Share USB bandwidth, or a hub with one TT per port (multi-TT).");
        return false;
    }

    /** Stops the stream and lets go of the camera. Safe to call more than once. Worker thread. */
    void close() {
        IsoStream.nativeStop(slot);
        if (driver != null) { driver.stop(); driver = null; }
        if (conn != null) {
            try {
                if (alt0 != null) {
                    conn.setInterface(alt0);
                    conn.releaseInterface(alt0);
                }
            } catch (Exception ignored) { }
            conn.close();
            conn = null;
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
}
