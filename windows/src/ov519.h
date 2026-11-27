// Talks to the OV519 bridge chip inside the PS2 EyeToy (USB 054c:0155) and the
// OmniVision image sensor behind it. Windows port of the Android app's
// EyeToyDriver.java, which itself is a port of the Linux kernel gspca "ov519"
// driver (drivers/media/usb/gspca/ov519.c), BRIDGE_OV519 code paths only.
#pragma once
#include <functional>
#include <string>
#include <stdexcept>

struct libusb_device_handle;

class Ov519 {
public:
    using Logger = std::function<void(const std::string &)>;

    enum { SEN_UNKNOWN = -1, SEN_OV7610 = 1, SEN_OV7620, SEN_OV7620AE, SEN_OV76BE,
           SEN_OV7640, SEN_OV7648, SEN_OV7660, SEN_OV7670 };

    Ov519(libusb_device_handle *h, Logger log);

    // Wakes the bridge, finds the sensor and loads its defaults. Throws on USB errors.
    bool init();
    // Configures 640x480 at 5/10/15/30 fps and starts the image stream.
    void start(int fps);
    // Stops the stream and turns the light off. Never throws.
    void stop();
    bool setLed(bool on);

    int sensor = SEN_UNKNOWN;
    std::string sensorName = "unknown";

private:
    libusb_device_handle *h_;
    Logger log_;
    int cache_[256];
    int clockdiv_ = 0;

    void regW(int index, int value);
    int regR(int index);
    int regR8(int index);
    void regWMask(int index, int value, int mask);
    void i2cWRaw(int reg, int value);
    int i2cRRaw(int reg);
    void i2cW(int reg, int value);
    int i2cR(int reg);
    void i2cWMask(int reg, int value, int mask);
    bool initOvSensor(int slave);
    void detectOv7xx0();
    void ledControl(bool on);
    void sensorVga7620Style();
    void clearCache();
};
