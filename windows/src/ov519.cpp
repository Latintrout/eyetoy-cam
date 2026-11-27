// See ov519.h. Kept as close as possible to EyeToyDriver.java so fixes can be
// carried between the Android and Windows apps.
#include "ov519.h"
#include <libusb.h>
#include <windows.h>
#include <cstdio>

namespace {
// Bridge registers
const int R51x_SYS_RESET = 0x50;
const int OV519_R10_H_SIZE = 0x10, OV519_R11_V_SIZE = 0x11,
          OV519_R12_X_OFFSETL = 0x12, OV519_R13_X_OFFSETH = 0x13,
          OV519_R14_Y_OFFSETL = 0x14, OV519_R15_Y_OFFSETH = 0x15,
          OV519_R16_DIVIDER = 0x16, OV519_R20_DFR = 0x20, OV519_R25_FORMAT = 0x25,
          OV519_R51_RESET1 = 0x51, OV519_R54_EN_CLK1 = 0x54, OV519_R57_SNAPSHOT = 0x57,
          OV519_GPIO_DATA_OUT0 = 0x71, OV519_GPIO_IO_CTRL0 = 0x72;
// I2C bridge registers
const int R51x_I2C_W_SID = 0x41, R51x_I2C_SADDR_3 = 0x42, R51x_I2C_SADDR_2 = 0x43,
          R51x_I2C_R_SID = 0x44, R51x_I2C_DATA = 0x45, R518_I2C_CTL = 0x47;
// Sensor I2C addresses
const int OV7xx0_SID = 0x42, OV_HIRES_SID = 0x60, OV8xx0_SID = 0xa0, OV6xx0_SID = 0xc0;

struct RV { int r, v; };

const RV INIT_519[] = {
    {0x5a, 0x6d},               // EnableSystem
    {0x53, 0x9b},               // don't enable the microcontroller
    {OV519_R54_EN_CLK1, 0xff},  // bit2 enables jpeg
    {0x5d, 0x03},
    {0x49, 0x01},
    {0x48, 0x00},
    {OV519_GPIO_IO_CTRL0, 0xee},// LED pin output; bit4 must be clear
    {OV519_R51_RESET1, 0x0f},
    {OV519_R51_RESET1, 0x00},
    {0x22, 0x00},
};

const RV NORM_7610[] = {
    {0x10, 0xff}, {0x16, 0x06}, {0x28, 0x24}, {0x2b, 0xac}, {0x12, 0x00},
    {0x38, 0x81}, {0x28, 0x24}, {0x0f, 0x85}, {0x15, 0x01}, {0x20, 0x1c},
    {0x23, 0x2a}, {0x24, 0x10}, {0x25, 0x8a}, {0x26, 0xa2}, {0x27, 0xc2},
    {0x2a, 0x04}, {0x2c, 0xfe}, {0x2d, 0x93}, {0x30, 0x71}, {0x31, 0x60},
    {0x32, 0x26}, {0x33, 0x20}, {0x34, 0x48}, {0x12, 0x24}, {0x11, 0x01},
    {0x0c, 0x24}, {0x0d, 0x24},
};

const RV NORM_7620[] = {
    {0x12, 0x80}, {0x00, 0x00}, {0x01, 0x80}, {0x02, 0x80}, {0x03, 0xc0},
    {0x06, 0x60}, {0x07, 0x00}, {0x0c, 0x24}, {0x0c, 0x24}, {0x0d, 0x24},
    {0x11, 0x01}, {0x12, 0x24}, {0x13, 0x01}, {0x14, 0x84}, {0x15, 0x01},
    {0x16, 0x03}, {0x17, 0x2f}, {0x18, 0xcf}, {0x19, 0x06}, {0x1a, 0xf5},
    {0x1b, 0x00}, {0x20, 0x18}, {0x21, 0x80}, {0x22, 0x80}, {0x23, 0x00},
    {0x26, 0xa2}, {0x27, 0xea}, {0x28, 0x22}, {0x29, 0x00}, {0x2a, 0x10},
    {0x2b, 0x00}, {0x2c, 0x88}, {0x2d, 0x91}, {0x2e, 0x80}, {0x2f, 0x44},
    {0x60, 0x27}, {0x61, 0x02}, {0x62, 0x5f}, {0x63, 0xd5}, {0x64, 0x57},
    {0x65, 0x83}, {0x66, 0x55}, {0x67, 0x92}, {0x68, 0xcf}, {0x69, 0x76},
    {0x6a, 0x22}, {0x6b, 0x00}, {0x6c, 0x02}, {0x6d, 0x44}, {0x6e, 0x80},
    {0x6f, 0x1d}, {0x70, 0x8b}, {0x71, 0x00}, {0x72, 0x14}, {0x73, 0x54},
    {0x74, 0x00}, {0x75, 0x8e}, {0x76, 0x00}, {0x77, 0xff}, {0x78, 0x80},
    {0x79, 0x80}, {0x7a, 0x80}, {0x7b, 0xe2}, {0x7c, 0x00},
};

const RV NORM_7640[] = {{0x12, 0x80}, {0x12, 0x14}};

const RV MODE_INIT_519[] = {
    {0x5d, 0x03},               // turn off suspend mode
    {0x53, 0x9f},
    {OV519_R54_EN_CLK1, 0x0f},  // bit2 jpeg enable
    {0xa2, 0x20}, {0xa3, 0x18}, {0xa4, 0x04}, {0xa5, 0x28},
    {0x37, 0x00},               // SetUsbInit
    {0x55, 0x02},               // 4.096 MHz audio clock
    {0x22, 0x1d},
    {0x17, 0x50},
    {0x37, 0x00},
    {0x40, 0xff},               // I2C timeout counter
    {0x46, 0x00},               // I2C clock prescaler
    {0x59, 0x04},
    {0xff, 0x00},
};

std::string fmt(const char *f, int a) {
    char b[160];
    snprintf(b, sizeof b, f, a);
    return b;
}
} // namespace

Ov519::Ov519(libusb_device_handle *h, Logger log) : h_(h), log_(std::move(log)) { clearCache(); }

void Ov519::clearCache() { for (int &c : cache_) c = -1; }

// ---------------------------------------------------------------------------
// Low-level USB register access (vendor control transfers, request 1)
// ---------------------------------------------------------------------------

void Ov519::regW(int index, int value) {
    unsigned char b = (unsigned char) value;
    int r = libusb_control_transfer(h_, 0x40, 1, 0, (uint16_t) index, &b, 1, 500);
    if (r < 0) throw std::runtime_error(fmt("write to camera register 0x%02x failed", index) +
                                        " (" + libusb_error_name(r) + ")");
}

int Ov519::regR(int index) {
    unsigned char b = 0;
    int r = libusb_control_transfer(h_, 0xC0, 1, 0, (uint16_t) index, &b, 1, 500);
    if (r < 0) throw std::runtime_error(fmt("read of camera register 0x%02x failed", index) +
                                        " (" + libusb_error_name(r) + ")");
    return b;
}

int Ov519::regR8(int index) {
    unsigned char b[8] = {0};
    int r = libusb_control_transfer(h_, 0xC0, 1, 0, (uint16_t) index, b, 8, 500);
    if (r < 0) throw std::runtime_error(fmt("read8 of camera register 0x%02x failed", index) +
                                        " (" + libusb_error_name(r) + ")");
    return b[0];
}

void Ov519::regWMask(int index, int value, int mask) {
    if (mask != 0xff) {
        value &= mask;
        int old = regR(index);
        value |= old & ~mask;
    }
    regW(index, value & 0xff);
}

// ---------------------------------------------------------------------------
// Sensor access over the bridge's I2C master
// ---------------------------------------------------------------------------

void Ov519::i2cWRaw(int reg, int value) {
    regW(R51x_I2C_SADDR_3, reg);
    regW(R51x_I2C_DATA, value);
    regW(R518_I2C_CTL, 0x01);   // 3-byte write cycle
    Sleep(4);
    regR8(R518_I2C_CTL);
}

int Ov519::i2cRRaw(int reg) {
    regW(R51x_I2C_SADDR_2, reg);
    regW(R518_I2C_CTL, 0x03);   // 2-byte write cycle
    regR8(R518_I2C_CTL);
    regW(R518_I2C_CTL, 0x05);   // 2-byte read cycle
    regR8(R518_I2C_CTL);
    return regR(R51x_I2C_DATA);
}

void Ov519::i2cW(int reg, int value) {
    value &= 0xff;
    if (cache_[reg] == value) return;
    i2cWRaw(reg, value);
    if (reg == 0x12 && (value & 0x80)) clearCache(); // sensor reset
    else cache_[reg] = value;
}

int Ov519::i2cR(int reg) {
    if (cache_[reg] != -1) return cache_[reg];
    int r = i2cRRaw(reg);
    cache_[reg] = r;
    return r;
}

void Ov519::i2cWMask(int reg, int value, int mask) {
    value &= mask;
    int old = i2cR(reg);
    i2cW(reg, (old & ~mask) | value);
}

// ---------------------------------------------------------------------------
// Initialisation (sd_init in the Linux driver)
// ---------------------------------------------------------------------------

bool Ov519::initOvSensor(int slave) {
    regW(R51x_I2C_W_SID, slave);
    regW(R51x_I2C_R_SID, slave + 1);
    clearCache();
    i2cW(0x12, 0x80);          // reset sensor
    Sleep(150);
    for (int i = 0; i < 10; i++) {
        int hi = i2cRRaw(0x1c), lo = i2cRRaw(0x1d);
        if (hi == 0x7f && lo == 0xa2) {
            char b[96];
            snprintf(b, sizeof b, "  sensor answered at address 0x%02x (try %d)", slave, i + 1);
            log_(b);
            cache_[0x1c] = hi;
            cache_[0x1d] = lo;
            return true;
        }
        i2cWRaw(0x12, 0x80);
        clearCache();
        Sleep(150);
    }
    return false;
}

void Ov519::detectOv7xx0() {
    int rc = i2cR(0x29);  // COM_I
    int hi, lo;
    switch (rc & 3) {
    case 3:
        hi = i2cR(0x0a);
        lo = i2cR(0x0b);
        if (hi == 0x76 && (lo & 0xf0) == 0x70) { sensor = SEN_OV7670; sensorName = "OV7670"; }
        else { sensor = SEN_OV7610; sensorName = "OV7610"; }
        break;
    case 1:
        if (i2cR(0x15) & 1) { sensor = SEN_OV7620AE; sensorName = "OV7620AE"; }
        else { sensor = SEN_OV76BE; sensorName = "OV76BE"; }
        break;
    case 0:
        hi = i2cR(0x0a);
        lo = i2cR(0x0b);
        if (hi == 0x76) {
            switch (lo) {
            case 0x40: sensor = SEN_OV7640; sensorName = "OV7645"; break;
            case 0x45: sensor = SEN_OV7640; sensorName = "OV7645B"; break;
            case 0x48: sensor = SEN_OV7648; sensorName = "OV7648"; break;
            case 0x60: sensor = SEN_OV7660; sensorName = "OV7660"; break;
            case 0x30: sensorName = "OV7630 (not supported)"; break;
            default: sensorName = fmt("unknown 0x76%02x", lo);
            }
        } else {
            sensor = SEN_OV7620;
            sensorName = "OV7620";
        }
        break;
    default:
        sensorName = fmt("unknown version %d", rc & 3);
    }
}

void Ov519::ledControl(bool on) { regWMask(OV519_GPIO_DATA_OUT0, on ? 1 : 0, 1); }

bool Ov519::init() {
    log_("Waking up the OV519 chip...");
    for (auto &rv : INIT_519) regW(rv.r, rv.v);

    log_("Looking for the image sensor...");
    if (initOvSensor(OV7xx0_SID)) {
        detectOv7xx0();
    } else {
        for (int sid : {OV6xx0_SID, OV8xx0_SID, OV_HIRES_SID}) {
            if (initOvSensor(sid)) {
                sensorName = fmt("family at address 0x%02x", sid);
                log_("Found a sensor this app doesn't support yet: " + sensorName);
                return false;
            }
        }
        log_("No image sensor answered. The camera chip responded, but the sensor didn't.");
        return false;
    }
    log_("Image sensor: " + sensorName);

    switch (sensor) {
    case SEN_OV7640:
    case SEN_OV7648:
        for (auto &rv : NORM_7640) i2cW(rv.r, rv.v);
        break;
    case SEN_OV7620:
    case SEN_OV7620AE:
        for (auto &rv : NORM_7620) i2cW(rv.r, rv.v);
        break;
    case SEN_OV7610:
    case SEN_OV76BE:
        for (auto &rv : NORM_7610) i2cW(rv.r, rv.v);
        i2cWMask(0x0e, 0x00, 0x40);
        break;
    default:
        log_("Sensor " + sensorName + " isn't supported yet.");
        return false;
    }
    ledControl(false);
    return true;
}

// ---------------------------------------------------------------------------
// Start / stop streaming at 640x480 (sd_start / sd_stopN)
// ---------------------------------------------------------------------------

void Ov519::start(int fps) {
    const int width = 640, height = 480;
    log_(fmt("Configuring 640x480 @ %d fps...", fps));

    // ---- bridge (ov519_mode_init_regs) ----
    for (auto &rv : MODE_INIT_519) regW(rv.r, rv.v);
    if (sensor == SEN_OV7640 || sensor == SEN_OV7648)
        regWMask(OV519_R20_DFR, 0x10, 0x10);   // 8-bit input mode
    regW(OV519_R10_H_SIZE, width >> 4);
    regW(OV519_R11_V_SIZE, height >> 3);
    regW(OV519_R12_X_OFFSETL, 0x00);
    regW(OV519_R13_X_OFFSETH, 0x00);
    regW(OV519_R14_Y_OFFSETL, 0x00);
    regW(OV519_R15_Y_OFFSETH, 0x00);
    regW(OV519_R16_DIVIDER, 0x00);
    regW(OV519_R25_FORMAT, 0x03);               // YUV422
    regW(0x26, 0x00);

    clockdiv_ = 0;
    if (sensor == SEN_OV7640 || sensor == SEN_OV7648) {
        if (fps >= 30) {
            regW(0xa4, 0x0c);
            regW(0x23, 0xff);
        } else if (fps >= 15) {
            regW(0xa4, 0x04);
            regW(0x23, 0xff);
            clockdiv_ = 1;
        } else if (fps >= 10) {
            regW(0xa4, 0x04);
            regW(0x23, 0x1f);
            clockdiv_ = 1;
        } else {
            regW(0xa4, 0x04);
            regW(0x23, 0x1b);
            clockdiv_ = 1;
        }
    }

    // ---- sensor (set_ov_sensor_window + mode_init_ov_sensor_regs), VGA ----
    int hwsbase, hwebase, vwsbase, vwebase;
    switch (sensor) {
    case SEN_OV7610:
    case SEN_OV76BE:
        hwsbase = 0x38; hwebase = 0x3a; vwsbase = vwebase = 0x05;
        i2cWMask(0x14, 0x00, 0x20);
        if (sensor == SEN_OV7610) {
            i2cW(0x35, 0x9e);
            i2cWMask(0x13, 0x00, 0x20);
            i2cWMask(0x12, 0x04, 0x06);
        } else {
            sensorVga7620Style();
            i2cW(0x35, 0x9e);
        }
        break;
    case SEN_OV7620:
    case SEN_OV7620AE:
        hwsbase = 0x2f; hwebase = 0x2f; vwsbase = vwebase = 0x05;
        i2cWMask(0x14, 0x00, 0x20);
        sensorVga7620Style();
        break;
    default: // OV7640 / OV7648
        hwsbase = 0x1a; hwebase = 0x1a; vwsbase = vwebase = 0x03;
        i2cWMask(0x14, 0x00, 0x20);
        i2cWMask(0x28, 0x20, 0x20);
        i2cWMask(0x2d, 0x00, 0x40);
        i2cWMask(0x67, 0x90, 0xf0);
        i2cWMask(0x74, 0x00, 0x20);
        i2cWMask(0x12, 0x04, 0x04);   // auto white balance on
        break;
    }
    i2cW(0x11, clockdiv_);
    i2cW(0x17, hwsbase);
    i2cW(0x18, hwebase + (width >> 2));
    i2cW(0x19, vwsbase);
    i2cW(0x1a, vwebase + (height >> 1));

    // ---- reset snapshot state, restart the stream, LED on ----
    regW(R51x_SYS_RESET, 0x40);
    regW(R51x_SYS_RESET, 0x00);
    regW(OV519_R51_RESET1, 0x0f);
    regW(OV519_R51_RESET1, 0x00);
    regW(0x22, 0x1d);
    ledControl(true);
    log_("Camera told to start streaming (the light should be on).");
}

void Ov519::sensorVga7620Style() {
    i2cWMask(0x28, 0x20, 0x20);
    i2cW(0x24, 0x3a);
    i2cW(0x25, 0x60);
    i2cWMask(0x2d, 0x00, 0x40);
    i2cWMask(0x67, 0x90, 0xf0);
    i2cWMask(0x74, 0x00, 0x20);
    i2cWMask(0x13, 0x00, 0x20);
    i2cWMask(0x12, 0x04, 0x06);
}

bool Ov519::setLed(bool on) {
    try { ledControl(on); return true; } catch (...) { return false; }
}

void Ov519::stop() {
    try {
        regW(OV519_R51_RESET1, 0x0f);
        regW(OV519_R51_RESET1, 0x00);
        regW(0x22, 0x00);
        ledControl(false);
        regW(OV519_R57_SNAPSHOT, 0x23);
    } catch (...) {
        // camera probably unplugged; nothing to do
    }
}
