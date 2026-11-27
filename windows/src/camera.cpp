#include "camera.h"
#include "jpeg.h"
#include <libusb.h>
#include <windows.h>
#include <wincodec.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#undef interface  // windows.h defines it as a macro; libusb uses it as a field name

namespace {
const int NXFERS = 8;          // transfers kept in flight
const int NPKTS = 32;          // packets per transfer (32 ms at full speed)
const size_t MAX_FRAME = 1024 * 1024;

std::string sfmt(const char *f, ...) {
    char b[512];
    va_list a;
    va_start(a, f);
    vsnprintf(b, sizeof b, f, a);
    va_end(a);
    return b;
}

// The OV519 sends 640x480 JPEG; a smaller USB packet means less data per
// second, so the camera is slowed down to match (same rule as Android).
int fpsFor(int psize, int want) {
    if (psize >= 896) return want;
    if (psize >= 768) return std::min(want, 15);
    return std::min(want, 10);
}
} // namespace

void transferDone(libusb_transfer *t) {
    static_cast<Camera *>(t->user_data)->handle(t);
}

static void LIBUSB_CALL transferCb(libusb_transfer *t) { transferDone(t); }

Camera::Camera(int number, std::string key, Logger log)
    : number_(number), key_(std::move(key)), log_(std::move(log)) {}

Camera::~Camera() { close(); }

std::string Camera::label() const { return sfmt("Cam %d", number_); }

// ---------------------------------------------------------------------------
// Open / close
// ---------------------------------------------------------------------------

Camera::OpenResult Camera::open(libusb_device *dev, int wantFps) {
    auto L = [this](const std::string &s) { log_(label() + ": " + s); };

    int r = libusb_open(dev, &h_);
    if (r != 0) {
        h_ = nullptr;
        L(std::string("Windows wouldn't open the camera (") + libusb_error_name(r) + ").");
        return (r == LIBUSB_ERROR_NOT_SUPPORTED || r == LIBUSB_ERROR_NOT_FOUND ||
                r == LIBUSB_ERROR_ACCESS) ? OPEN_NO_DRIVER : OPEN_FAILED;
    }

    // List the stream settings of the video interface (interface 0).
    libusb_config_descriptor *cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != 0 &&
        libusb_get_config_descriptor(dev, 0, &cfg) != 0) {
        L("Couldn't read the camera's USB description.");
        close();
        return OPEN_FAILED;
    }
    struct Alt { int alt, ep, psize; };
    std::vector<Alt> alts;
    for (int i = 0; i < cfg->bNumInterfaces; i++) {
        const libusb_interface &it = cfg->interface[i];
        for (int a = 0; a < it.num_altsetting; a++) {
            const libusb_interface_descriptor &d = it.altsetting[a];
            std::string line = sfmt("  interface %d alt %d class %d:", d.bInterfaceNumber,
                                    d.bAlternateSetting, d.bInterfaceClass);
            for (int e = 0; e < d.bNumEndpoints; e++) {
                const libusb_endpoint_descriptor &ep = d.endpoint[e];
                int type = ep.bmAttributes & 3;
                int ps = ep.wMaxPacketSize & 0x7ff;
                line += sfmt(" [ep 0x%02x %s %s %d]", ep.bEndpointAddress,
                             type == 1 ? "iso" : type == 2 ? "bulk" : type == 3 ? "int" : "ctrl",
                             (ep.bEndpointAddress & 0x80) ? "in" : "out", ps);
                if (d.bInterfaceNumber == 0 && type == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS &&
                    (ep.bEndpointAddress & 0x80) && ps > 0)
                    alts.push_back({d.bAlternateSetting, ep.bEndpointAddress, ps});
            }
            if (number_ == 1) L(line);
        }
    }
    libusb_free_config_descriptor(cfg);
    if (alts.empty()) {
        L("Couldn't find the video stream on interface 0.");
        close();
        return OPEN_FAILED;
    }
    std::sort(alts.begin(), alts.end(), [](const Alt &a, const Alt &b) { return a.psize > b.psize; });

    r = libusb_claim_interface(h_, 0);
    if (r != 0) {
        L(std::string("Couldn't take the video part of the camera (") + libusb_error_name(r) + ").");
        close();
        if (r == LIBUSB_ERROR_BUSY) {
            L("Another program is using it. Close other copies of EyeToy Cam and try again.");
            return OPEN_FAILED;
        }
        return OPEN_NO_DRIVER;
    }
    claimed_ = true;

    try {
        drv_ = std::make_unique<Ov519>(h_, L);
        if (!drv_->init()) {
            L("Camera setup stopped. Click Log, then Copy log, and send it to Claude.");
            close();
            return OPEN_FAILED;
        }
        sensorName_ = drv_->sensorName;

        for (size_t k = 0; k < alts.size(); k++) {
            const Alt &a = alts[k];
            int f = fpsFor(a.psize, wantFps);
            L(sfmt("Selecting stream setting alt %d (packet size %d).", a.alt, a.psize));
            r = libusb_set_interface_alt_setting(h_, 0, a.alt);
            if (r != 0) {
                L(std::string("  Windows refused that stream setting (") + libusb_error_name(r) +
                  "), usually not enough USB bandwidth left.");
                continue;
            }
            ep_ = a.ep;
            psize_ = a.psize;
            alt_ = a.alt;
            drv_->start(f);
            if (startStream()) {
                fps_ = f;
                if (k > 0 || f < wantFps)
                    L(sfmt("  Running on a smaller stream: %d bytes per packet, %d fps.", a.psize, f));
                decRun_ = true;
                decoder_ = std::thread(&Camera::decodeLoop, this);
                return OPEN_OK;
            }
            L("  Couldn't start the USB stream on that setting.");
            libusb_set_interface_alt_setting(h_, 0, 0);
        }
        L("No stream setting fitted. If other cameras are running on the same USB port or hub, "
          "try plugging this one into a different USB port on the PC.");
    } catch (const std::exception &e) {
        L(std::string("Camera stopped answering: ") + e.what());
    }
    close();
    return OPEN_FAILED;
}

void Camera::close() {
    if (decoder_.joinable()) {
        {
            std::lock_guard<std::mutex> g(fm_);
            decRun_ = false;
        }
        fcv_.notify_all();
        decoder_.join();
    }
    stopStream();
    if (drv_) {
        if (!dead_) drv_->stop();
        drv_.reset();
    }
    if (h_) {
        if (claimed_) {
            libusb_set_interface_alt_setting(h_, 0, 0);
            libusb_release_interface(h_, 0);
            claimed_ = false;
        }
        libusb_close(h_);
        h_ = nullptr;
    }
}

// ---------------------------------------------------------------------------
// Isochronous streaming
// ---------------------------------------------------------------------------

bool Camera::startStream() {
    stopping_ = false;
    dead_ = false;
    collecting_ = false;
    cur_.clear();
    cur_.reserve(MAX_FRAME);
    xfers_.assign(NXFERS, nullptr);
    bufs_.assign(NXFERS, std::vector<uint8_t>((size_t) NPKTS * psize_));
    int ok = 0;
    for (int i = 0; i < NXFERS; i++) {
        libusb_transfer *t = libusb_alloc_transfer(NPKTS);
        if (!t) break;
        xfers_[i] = t;
        libusb_fill_iso_transfer(t, h_, (unsigned char) ep_, bufs_[i].data(), NPKTS * psize_, NPKTS,
                                 transferCb, this, 1000);
        libusb_set_iso_packet_lengths(t, (unsigned int) psize_);
        active_++;
        int r = libusb_submit_transfer(t);
        if (r != 0) {
            active_--;
            log_(label() + std::string(": USB stream request refused (") + libusb_error_name(r) + ").");
            break;
        }
        ok++;
    }
    if (ok == NXFERS) return true;
    stopStream();
    return false;
}

void Camera::stopStream() {
    if (xfers_.empty()) return;
    stopping_ = true;
    for (auto *t : xfers_) if (t) libusb_cancel_transfer(t);
    for (int i = 0; i < 300 && active_ > 0; i++) Sleep(10);
    if (active_ > 0) {
        // Should never happen; leaking is safer than freeing a transfer Windows still owns.
        log_(label() + ": USB requests didn't finish stopping; leaving them.");
        for (auto &b : bufs_) new std::vector<uint8_t>(std::move(b));
    } else {
        for (auto *t : xfers_) if (t) libusb_free_transfer(t);
    }
    xfers_.clear();
    bufs_.clear();
}

void Camera::handle(libusb_transfer *t) {
    lastXferStatus_ = t->status;
    if (t->status == LIBUSB_TRANSFER_CANCELLED || stopping_) {
        active_--;
        return;
    }
    if (t->status == LIBUSB_TRANSFER_NO_DEVICE) {
        active_--;
        dead_ = true;
        return;
    }
    if (t->status == LIBUSB_TRANSFER_COMPLETED) {
        stXfers_++;
        for (int k = 0; k < t->num_iso_packets; k++) {
            const libusb_iso_packet_descriptor &pd = t->iso_packet_desc[k];
            stPkts_++;
            if (pd.status != LIBUSB_TRANSFER_COMPLETED) { stPktErr_++; continue; }
            int len = (int) pd.actual_length;
            if (len > 0) {
                stBytes_ += len;
                scan(libusb_get_iso_packet_buffer_simple(t, (unsigned int) k), len);
            }
        }
    } else {
        stXferErr_++;
    }
    if (libusb_submit_transfer(t) != 0) {
        active_--;
        if (active_ <= 0) dead_ = true;
    }
}

// Packet framing follows ov519_pkt_scan() in the Linux gspca ov519 driver:
// a frame starts with a packet beginning ff ff ff 50 (16-byte header, then
// JPEG data) and ends with one beginning ff ff ff 51 (byte 9 != 0 = discard).
void Camera::scan(const uint8_t *d, int len) {
    if (len >= 4 && d[0] == 0xff && d[1] == 0xff && d[2] == 0xff) {
        if (d[3] == 0x50) {
            if (collecting_) stDropped_++;
            cur_.clear();
            const uint8_t *p = d + 16;
            int n = len - 16;
            if (n < 2 || p[0] == 0xff || p[1] == 0xd8) {
                collecting_ = true;
                append(p, n);
            } else {
                collecting_ = false;
                stDropped_++;
            }
            return;
        }
        if (d[3] == 0x51) {
            if (collecting_ && len > 9 && d[9] == 0 && !cur_.empty()) publish();
            else if (collecting_) stDropped_++;
            collecting_ = false;
            cur_.clear();
            return;
        }
    }
    if (collecting_) append(d, len);
}

void Camera::append(const uint8_t *d, int len) {
    if (len <= 0) return;
    if (cur_.size() + (size_t) len > MAX_FRAME) {
        collecting_ = false;
        stDropped_++;
        return;
    }
    cur_.insert(cur_.end(), d, d + len);
}

void Camera::publish() {
    stFrames_++;
    {
        std::lock_guard<std::mutex> g(fm_);
        ready_.swap(cur_);
        readyNew_ = true;
    }
    fcv_.notify_one();
    cur_.clear();
}

// ---------------------------------------------------------------------------
// Decoding
// ---------------------------------------------------------------------------

void Camera::decodeLoop() {
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    IWICImagingFactory *wic = nullptr;
    CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                     IID_PPV_ARGS(&wic));
    if (!wic) log_(label() + ": Windows picture decoder (WIC) isn't available.");
    std::vector<uint8_t> frame, px;
    auto secStart = std::chrono::steady_clock::now();
    int secFrames = 0;
    while (true) {
        {
            std::unique_lock<std::mutex> g(fm_);
            fcv_.wait_for(g, std::chrono::milliseconds(500), [this] { return readyNew_ || !decRun_; });
            if (!decRun_) break;
            if (readyNew_) {
                frame.swap(ready_);
                readyNew_ = false;
            } else {
                frame.clear();
            }
        }
        auto now = std::chrono::steady_clock::now();
        if (now - secStart >= std::chrono::seconds(1)) {
            liveFps_ = secFrames;
            secFrames = 0;
            secStart = now;
        }
        if (frame.empty()) continue;

        int w = 0, h = 0;
        bool ok = false;
        std::vector<uint8_t> fixed;
        if (!needFix_) ok = jpeg::decode(wic, frame, px, w, h);
        if (!ok && jpeg::looksLikeJpeg(frame)) {
            fixed = jpeg::addHuffmanTables(frame);
            ok = jpeg::decode(wic, fixed, px, w, h);
            if (ok && !needFix_) {
                needFix_ = true;
                log_(label() + ": frames need the standard Huffman tables added (normal for this chip).");
            }
        }
        if (!ok) {
            stBad_++;
            if (!loggedBadFrame_) {
                loggedBadFrame_ = true;
                log_(label() + sfmt(": a frame of %d bytes couldn't be decoded. Starts: ", (int) frame.size()) +
                     jpeg::hex(frame, 24));
            }
            continue;
        }
        stShown_++;
        secFrames++;
        {
            std::lock_guard<std::mutex> g(pm_);
            pixels_.swap(px);
            pw_ = w;
            ph_ = h;
            serial_++;
            if (fixed.empty()) lastJpeg_ = frame; else lastJpeg_.swap(fixed);
        }
        if (onPicture) onPicture();
    }
    if (wic) wic->Release();
    CoUninitialize();
}

bool Camera::copyPicture(std::vector<uint8_t> &px, int &w, int &h, uint64_t &serial) {
    std::lock_guard<std::mutex> g(pm_);
    if (pixels_.empty()) return false;
    if (serial != serial_) {
        px = pixels_;
        w = pw_;
        h = ph_;
        serial = serial_;
    }
    return true;
}

std::vector<uint8_t> Camera::lastJpeg() {
    std::lock_guard<std::mutex> g(pm_);
    return lastJpeg_;
}

std::string Camera::stats() {
    return label() + sfmt(": alt %d, %d-byte packets, %d fps set, %d fps shown | transfers %ld, "
                          "packets %ld (errors %ld), %ld KB, frames %ld, dropped %ld, undecodable %ld, "
                          "transfer errors %ld, last status %d",
                          alt_, psize_, fps_, (int) liveFps_, (long) stXfers_, (long) stPkts_,
                          (long) stPktErr_, (long) (stBytes_ / 1024), (long) stFrames_,
                          (long) stDropped_, (long) stBad_, (long) stXferErr_, lastXferStatus_);
}
