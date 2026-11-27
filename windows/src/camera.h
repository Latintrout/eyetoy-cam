// One EyeToy: opens it through libusb (WinUSB driver), wakes it with Ov519,
// streams isochronous USB packets, cuts them into JPEG frames (same framing as
// iso_stream.c on Android) and decodes the newest frame on its own thread.
#pragma once
#include "ov519.h"
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct libusb_context;
struct libusb_device;
struct libusb_device_handle;
struct libusb_transfer;

class Camera {
public:
    using Logger = std::function<void(const std::string &)>;

    Camera(int number, std::string key, Logger log);
    ~Camera();

    // Result of open(): ok, or why it failed.
    enum OpenResult { OPEN_OK, OPEN_NO_DRIVER, OPEN_FAILED };

    // Opens the camera, wakes it and starts streaming on the biggest stream
    // setting that the USB bandwidth allows. Blocking (takes about a second).
    OpenResult open(libusb_device *dev, int wantFps);
    // Stops the stream and lets go of the camera. Safe to call more than once.
    void close();

    // True once the camera stopped sending (unplugged or USB error).
    bool dead() const { return dead_; }

    // Copies the newest decoded picture. Returns false if there isn't one yet.
    bool copyPicture(std::vector<uint8_t> &px, int &w, int &h, uint64_t &serial);
    // Newest good JPEG (with Huffman tables added), for saving photos.
    std::vector<uint8_t> lastJpeg();

    int number() const { return number_; }
    const std::string &key() const { return key_; }
    std::string label() const;
    std::string sensorName() const { return sensorName_; }
    int fps() const { return fps_; }
    int liveFps() const { return liveFps_; }
    int psize() const { return psize_; }
    std::string stats();

    // Called (from a worker thread) when a new picture is ready.
    std::function<void()> onPicture;

private:
    friend void transferDone(libusb_transfer *t);

    int number_;
    std::string key_;
    Logger log_;
    libusb_device_handle *h_ = nullptr;
    std::unique_ptr<Ov519> drv_;
    bool claimed_ = false;
    int ep_ = 0, psize_ = 0, fps_ = 0, alt_ = 0;
    std::string sensorName_;

    // USB transfers
    std::vector<libusb_transfer *> xfers_;
    std::vector<std::vector<uint8_t>> bufs_;
    std::atomic<int> active_{0};
    std::atomic<bool> stopping_{false};
    std::atomic<bool> dead_{false};

    // frame assembly (only touched by the libusb event thread)
    std::vector<uint8_t> cur_;
    bool collecting_ = false;
    void scan(const uint8_t *d, int len);
    void append(const uint8_t *d, int len);
    void publish();
    void handle(libusb_transfer *t);

    // newest finished frame, handed to the decoder thread
    std::mutex fm_;
    std::condition_variable fcv_;
    std::vector<uint8_t> ready_;
    bool readyNew_ = false;

    // decoder thread and its output
    std::thread decoder_;
    std::atomic<bool> decRun_{false};
    void decodeLoop();
    std::mutex pm_;
    std::vector<uint8_t> pixels_;
    int pw_ = 0, ph_ = 0;
    uint64_t serial_ = 0;
    std::vector<uint8_t> lastJpeg_;
    bool needFix_ = false;

    // statistics
    std::atomic<long> stXfers_{0}, stPkts_{0}, stPktErr_{0}, stBytes_{0}, stFrames_{0},
        stDropped_{0}, stBad_{0}, stShown_{0}, stXferErr_{0};
    std::atomic<int> liveFps_{0};
    int lastXferStatus_ = 0;
    bool loggedBadFrame_ = false;

    bool startStream();
    void stopStream();
};
