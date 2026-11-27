// One-time driver setup for the EyeToy's video part on Windows.
//
// Windows splits the EyeToy into a microphone (works out of the box) and a
// video interface ("Interface 0", hardware ID USB\VID_054C&PID_0155&MI_00)
// that has no driver (Device Manager: Code 28). This puts Microsoft's own
// built-in, signed WinUSB driver (winusb.inf, already on every Windows 8.1+
// PC) on that interface, the same as picking "WinUSB Device" by hand in
// Device Manager, so the app can talk to the camera through libusb.
#pragma once
#include <string>
#include <vector>

namespace driver_setup {

struct VideoPart {
    std::wstring instanceId;    // e.g. USB\VID_054C&PID_0155&MI_00\6&1234&0&0000
    std::wstring service;       // driver now in use ("" = none, "WinUSB" = ready)
    unsigned long problem = 0;  // Device Manager problem code (28 = no driver)
    bool present = false;
};

// Lists the video parts of all plugged-in EyeToys. Works without admin rights.
std::vector<VideoPart> findVideoParts();

// Installs WinUSB on every EyeToy video part. Needs admin rights.
// Writes a plain-English log through `log`. Returns true if all succeeded.
bool installWinUsb(void (*log)(const std::wstring &));

// Relaunches this exe as admin with --install-driver and waits for it.
// Returns the child's exit code, or -1 if the user said no / it couldn't start.
int runElevatedInstall(void *hwndOwner);

} // namespace driver_setup
