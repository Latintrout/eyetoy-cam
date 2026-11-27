#include "driver_setup.h"
#include <windows.h>
#include <objbase.h>
#include <setupapi.h>
#include <cfgmgr32.h>
#include <shellapi.h>
#include <algorithm>
#include <cwctype>

namespace driver_setup {
namespace {

const wchar_t *VIDEO_ID = L"USB\\VID_054C&PID_0155&MI_00";
// Device class "Universal Serial Bus devices" (USBDevice), the class of winusb.inf.
const wchar_t *USBDEVICE_CLASS = L"{88BAE032-5A81-49F0-BC3D-A4FF138216D6}";
// Our own device interface GUID, so libusb can find the camera through WinUSB.
const wchar_t *EYETOY_IFACE_GUID = L"{6E1F0C3A-3B4D-4E8A-9C2B-0547C0155E70}";

std::wstring upper(std::wstring s) {
    std::transform(s.begin(), s.end(), s.begin(), [](wchar_t c) { return (wchar_t) towupper(c); });
    return s;
}

std::wstring regString(HDEVINFO devs, SP_DEVINFO_DATA &dev, DWORD prop) {
    wchar_t buf[2048] = {0};
    DWORD type = 0;
    if (!SetupDiGetDeviceRegistryPropertyW(devs, &dev, prop, &type, (BYTE *) buf, sizeof buf - 4, nullptr))
        return L"";
    if (type == REG_MULTI_SZ) {   // join with ';'
        std::wstring out;
        for (const wchar_t *p = buf; *p; p += wcslen(p) + 1) {
            if (!out.empty()) out += L";";
            out += p;
        }
        return out;
    }
    return buf;
}

bool isVideoPart(HDEVINFO devs, SP_DEVINFO_DATA &dev) {
    std::wstring ids = upper(regString(devs, dev, SPDRP_HARDWAREID));
    return ids.find(VIDEO_ID) != std::wstring::npos;
}

std::wstring instanceId(HDEVINFO devs, SP_DEVINFO_DATA &dev) {
    wchar_t buf[512] = {0};
    SetupDiGetDeviceInstanceIdW(devs, &dev, buf, 511, nullptr);
    return buf;
}

std::wstring lastError() {
    DWORD e = GetLastError();
    wchar_t *msg = nullptr;
    FormatMessageW(FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_IGNORE_INSERTS,
                   nullptr, e, 0, (LPWSTR) &msg, 0, nullptr);
    wchar_t code[32];
    swprintf(code, 32, L" (error 0x%08lx)", e);
    std::wstring s = msg ? msg : L"unknown error";
    if (msg) LocalFree(msg);
    while (!s.empty() && (s.back() == L'\n' || s.back() == L'\r' || s.back() == L' ')) s.pop_back();
    return s + code;
}

bool writeInterfaceGuid(HDEVINFO devs, SP_DEVINFO_DATA &dev, void (*log)(const std::wstring &)) {
    HKEY k = SetupDiCreateDevRegKeyW(devs, &dev, DICS_FLAG_GLOBAL, 0, DIREG_DEV, nullptr, nullptr);
    if (k == INVALID_HANDLE_VALUE) {
        log(L"  Couldn't open the camera's settings in the registry: " + lastError());
        return false;
    }
    std::wstring v = EYETOY_IFACE_GUID;
    v.push_back(L'\0');
    v.push_back(L'\0');
    LONG r = RegSetValueExW(k, L"DeviceInterfaceGUIDs", 0, REG_MULTI_SZ, (const BYTE *) v.data(),
                            (DWORD) (v.size() * sizeof(wchar_t)));
    RegCloseKey(k);
    if (r != ERROR_SUCCESS) {
        log(L"  Couldn't save the camera's interface ID in the registry.");
        return false;
    }
    return true;
}

bool restartDevice(HDEVINFO devs, SP_DEVINFO_DATA &dev, void (*log)(const std::wstring &)) {
    SP_PROPCHANGE_PARAMS p = {};
    p.ClassInstallHeader.cbSize = sizeof(SP_CLASSINSTALL_HEADER);
    p.ClassInstallHeader.InstallFunction = DIF_PROPERTYCHANGE;
    p.StateChange = DICS_PROPCHANGE;
    p.Scope = DICS_FLAG_CONFIGSPECIFIC;
    p.HwProfile = 0;
    if (!SetupDiSetClassInstallParamsW(devs, &dev, &p.ClassInstallHeader, sizeof p) ||
        !SetupDiCallClassInstaller(DIF_PROPERTYCHANGE, devs, &dev)) {
        log(L"  Couldn't restart the camera by itself: " + lastError() +
            L". Unplug it and plug it back in.");
        return false;
    }
    return true;
}

typedef BOOL(WINAPI *DiInstallDeviceFn)(HWND, HDEVINFO, PSP_DEVINFO_DATA, PSP_DRVINFO_DATA_W, DWORD, PBOOL);

bool installOne(HDEVINFO devs, SP_DEVINFO_DATA &dev, void (*log)(const std::wstring &)) {
    std::wstring service = upper(regString(devs, dev, SPDRP_SERVICE));

    // 1. Our interface ID first, so WinUSB announces the camera when it starts.
    writeInterfaceGuid(devs, dev, log);

    if (service == L"WINUSB") {
        log(L"  WinUSB is already on this camera. Restarting it so the app can find it.");
        restartDevice(devs, dev, log);
        return true;
    }
    if (!service.empty())
        log(L"  It currently uses the driver \"" + service + L"\"; replacing it with WinUSB.");

    // 2. Put the device in the USBDevice class, the class of Microsoft's winusb.inf.
    if (!SetupDiSetDeviceRegistryPropertyW(devs, &dev, SPDRP_CLASSGUID, (const BYTE *) USBDEVICE_CLASS,
                                           (DWORD) ((wcslen(USBDEVICE_CLASS) + 1) * sizeof(wchar_t))))
        log(L"  Note: couldn't set the device class: " + lastError());
    CLSIDFromString(USBDEVICE_CLASS, &dev.ClassGuid);

    // 3. Build the driver list from winusb.inf only.
    wchar_t win[MAX_PATH] = {0};
    GetWindowsDirectoryW(win, MAX_PATH);
    std::wstring inf = std::wstring(win) + L"\\INF\\winusb.inf";
    if (GetFileAttributesW(inf.c_str()) == INVALID_FILE_ATTRIBUTES) {
        log(L"  Windows' own WinUSB driver file wasn't found at " + inf + L".");
        return false;
    }
    SP_DEVINSTALL_PARAMS_W ip = {};
    ip.cbSize = sizeof ip;
    if (!SetupDiGetDeviceInstallParamsW(devs, &dev, &ip)) {
        log(L"  Couldn't read install settings: " + lastError());
        return false;
    }
    ip.Flags |= DI_ENUMSINGLEINF;
    ip.FlagsEx |= DI_FLAGSEX_ALLOWEXCLUDEDDRVS;
    wcsncpy(ip.DriverPath, inf.c_str(), MAX_PATH - 1);
    if (!SetupDiSetDeviceInstallParamsW(devs, &dev, &ip)) {
        log(L"  Couldn't change install settings: " + lastError());
        return false;
    }
    if (!SetupDiBuildDriverInfoList(devs, &dev, SPDIT_CLASSDRIVER)) {
        log(L"  Couldn't read winusb.inf: " + lastError());
        return false;
    }
    SP_DRVINFO_DATA_W drv = {};
    drv.cbSize = sizeof drv;
    bool found = false;
    for (DWORD i = 0; SetupDiEnumDriverInfoW(devs, &dev, SPDIT_CLASSDRIVER, i, &drv); i++) {
        log(std::wstring(L"  driver choice: ") + drv.Description + L" (" + drv.MfgName + L")");
        if (!found) found = true;
        break;   // winusb.inf has a single generic entry ("WinUsb Device")
    }
    if (!found) {
        log(L"  winusb.inf didn't offer a driver for this camera.");
        return false;
    }
    if (!SetupDiSetSelectedDriverW(devs, &dev, &drv)) {
        log(L"  Couldn't choose the WinUSB driver: " + lastError());
        return false;
    }

    // 4. Install it (what Device Manager does after "Let me pick").
    bool ok = false;
    BOOL reboot = FALSE;
    HMODULE nd = LoadLibraryW(L"newdev.dll");
    DiInstallDeviceFn diInstall = nd ? (DiInstallDeviceFn) GetProcAddress(nd, "DiInstallDevice") : nullptr;
    if (diInstall) {
        if (diInstall(nullptr, devs, &dev, &drv, 0, &reboot)) ok = true;
        else log(L"  First install method failed: " + lastError());
    }
    if (!ok) {
        SetupDiCallClassInstaller(DIF_REGISTER_COINSTALLERS, devs, &dev);
        SetupDiCallClassInstaller(DIF_INSTALLINTERFACES, devs, &dev);
        if (SetupDiCallClassInstaller(DIF_INSTALLDEVICE, devs, &dev)) ok = true;
        else log(L"  Second install method failed: " + lastError());
    }
    if (nd) FreeLibrary(nd);
    if (!ok) return false;
    log(L"  WinUSB installed.");

    // 5. Make sure our interface ID is there and restart the camera.
    writeInterfaceGuid(devs, dev, log);
    restartDevice(devs, dev, log);
    if (reboot) log(L"  Windows says a restart may be needed. Try unplugging the camera first.");
    return true;
}

} // namespace

std::vector<VideoPart> findVideoParts() {
    std::vector<VideoPart> out;
    HDEVINFO devs = SetupDiGetClassDevsW(nullptr, L"USB", nullptr, DIGCF_ALLCLASSES | DIGCF_PRESENT);
    if (devs == INVALID_HANDLE_VALUE) return out;
    SP_DEVINFO_DATA dev = {};
    dev.cbSize = sizeof dev;
    for (DWORD i = 0; SetupDiEnumDeviceInfo(devs, i, &dev); i++) {
        if (!isVideoPart(devs, dev)) continue;
        VideoPart p;
        p.instanceId = instanceId(devs, dev);
        p.service = regString(devs, dev, SPDRP_SERVICE);
        ULONG status = 0, problem = 0;
        if (CM_Get_DevNode_Status(&status, &problem, dev.DevInst, 0) == CR_SUCCESS) {
            p.present = true;
            p.problem = (status & DN_HAS_PROBLEM) ? problem : 0;
        }
        out.push_back(p);
    }
    SetupDiDestroyDeviceInfoList(devs);
    return out;
}

bool installWinUsb(void (*log)(const std::wstring &)) {
    HDEVINFO devs = SetupDiGetClassDevsW(nullptr, L"USB", nullptr, DIGCF_ALLCLASSES | DIGCF_PRESENT);
    if (devs == INVALID_HANDLE_VALUE) {
        log(L"Couldn't list USB devices: " + lastError());
        return false;
    }
    int seen = 0, done = 0;
    SP_DEVINFO_DATA dev = {};
    dev.cbSize = sizeof dev;
    for (DWORD i = 0; SetupDiEnumDeviceInfo(devs, i, &dev); i++) {
        if (!isVideoPart(devs, dev)) continue;
        seen++;
        log(L"EyeToy video part found: " + instanceId(devs, dev));
        if (installOne(devs, dev, log)) done++;
    }
    SetupDiDestroyDeviceInfoList(devs);
    if (seen == 0) {
        log(L"No EyeToy was found. Plug the camera in and try again.");
        return false;
    }
    return done == seen;
}

int runElevatedInstall(void *hwndOwner) {
    wchar_t exe[MAX_PATH] = {0};
    GetModuleFileNameW(nullptr, exe, MAX_PATH);
    SHELLEXECUTEINFOW sei = {};
    sei.cbSize = sizeof sei;
    sei.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC;
    sei.hwnd = (HWND) hwndOwner;
    sei.lpVerb = L"runas";
    sei.lpFile = exe;
    sei.lpParameters = L"--install-driver";
    sei.nShow = SW_HIDE;
    if (!ShellExecuteExW(&sei) || !sei.hProcess) return -1;
    WaitForSingleObject(sei.hProcess, 120000);
    DWORD code = (DWORD) -1;
    GetExitCodeProcess(sei.hProcess, &code);
    CloseHandle(sei.hProcess);
    return (int) code;
}

} // namespace driver_setup
