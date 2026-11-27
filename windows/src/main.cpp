// EyeToy Cam for Windows: shows every plugged-in PS2 EyeToy (USB 054c:0155)
// in one window and saves photos. The window can be captured in OBS
// ("Window Capture"); a virtual camera comes in a later build.
#include "camera.h"
#include "driver_setup.h"
#include <libusb.h>
#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <shellapi.h>
#include <shlobj.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#ifndef BUILD_NUMBER
#define BUILD_NUMBER "dev"
#endif
#define APP_VERSION "0.1"

namespace {

const UINT WM_APP_LOG = WM_APP + 1;
const UINT WM_APP_PICTURE = WM_APP + 2;
const UINT WM_APP_STATE = WM_APP + 3;
const UINT WM_APP_DRIVER_DONE = WM_APP + 4;
const UINT WM_APP_OFFER_DRIVER = WM_APP + 5;

enum { ID_PHOTO = 101, ID_FOLDER, ID_MIRROR, ID_CLEAN, ID_DRIVER, ID_LOG, ID_COPYLOG = 201, ID_LOGEDIT };
const int MAX_CAMS = 4;
const int WANT_FPS = 30;

HINSTANCE g_inst;
HWND g_wnd, g_logWnd, g_logEdit;
HWND g_btn[6];
HFONT g_font, g_bigFont;
int g_dpi = 96;

bool g_mirror = false, g_clean = false;
std::wstring g_flash;          // short message in the status line
DWORD g_flashUntil = 0;
DWORD g_shutterUntil = 0;

// log
std::mutex g_logMx;
std::wstring g_logText, g_logPending;

// cameras
libusb_context *g_usb = nullptr;
std::atomic<bool> g_run{true}, g_events{true};
std::thread g_eventThread, g_managerThread;
std::mutex g_camMx;
std::vector<std::shared_ptr<Camera>> g_cams;
std::atomic<bool> g_picturePosted{false};
std::atomic<bool> g_rescanNow{false};
std::atomic<bool> g_driverBusy{false};

// what the manager found, for the status line
std::mutex g_stateMx;
std::wstring g_state = L"Looking for EyeToy cameras...";
bool g_needDriver = false;
bool g_offeredDriver = false;

struct Tile { std::vector<uint8_t> px; int w = 0, h = 0; uint64_t serial = 0; };
std::map<std::string, Tile> g_tiles;  // UI thread only

int S(int v) { return MulDiv(v, g_dpi, 96); }

std::wstring widen(const std::string &s) {
    if (s.empty()) return L"";
    int n = MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), nullptr, 0);
    std::wstring w(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, s.data(), (int) s.size(), &w[0], n);
    return w;
}

void logW(const std::wstring &line) {
    SYSTEMTIME t;
    GetLocalTime(&t);
    wchar_t ts[32];
    swprintf(ts, 32, L"%02d:%02d:%02d  ", t.wHour, t.wMinute, t.wSecond);
    {
        std::lock_guard<std::mutex> g(g_logMx);
        std::wstring l = ts + line + L"\r\n";
        g_logText += l;
        g_logPending += l;
        if (g_logText.size() > 400000) g_logText.erase(0, 100000);
    }
    if (g_wnd) PostMessageW(g_wnd, WM_APP_LOG, 0, 0);
}

void logA(const std::string &line) { logW(widen(line)); }

void setState(const std::wstring &s, bool needDriver) {
    bool changed;
    {
        std::lock_guard<std::mutex> g(g_stateMx);
        changed = s != g_state || needDriver != g_needDriver;
        g_state = s;
        g_needDriver = needDriver;
    }
    if (changed && g_wnd) PostMessageW(g_wnd, WM_APP_STATE, 0, 0);
}

void flash(const std::wstring &s) {
    g_flash = s;
    g_flashUntil = GetTickCount() + 5000;
    InvalidateRect(g_wnd, nullptr, FALSE);
}

std::vector<std::shared_ptr<Camera>> camsSnapshot() {
    std::lock_guard<std::mutex> g(g_camMx);
    return g_cams;
}

// ---------------------------------------------------------------------------
// USB threads
// ---------------------------------------------------------------------------

void LIBUSB_CALL libusbLog(libusb_context *, enum libusb_log_level level, const char *str) {
    std::string s = str ? str : "";
    while (!s.empty() && (s.back() == '\n' || s.back() == '\r')) s.pop_back();
    logA(std::string(level <= LIBUSB_LOG_LEVEL_ERROR ? "[usb error] " : "[usb] ") + s);
}

void eventLoop() {
    while (g_events) {
        timeval tv = {0, 100000};
        libusb_handle_events_timeout_completed(g_usb, &tv, nullptr);
    }
}

std::string portKey(libusb_device *d) {
    uint8_t ports[8];
    int n = libusb_get_port_numbers(d, ports, 8);
    std::string k = "bus" + std::to_string(libusb_get_bus_number(d));
    for (int i = 0; i < n; i++) k += "-" + std::to_string(ports[i]);
    if (n <= 0) k += "-addr" + std::to_string(libusb_get_device_address(d));
    return k;
}

void managerLoop() {
    std::map<std::string, DWORD> retryAfter;   // key -> tick when we may try again
    bool firstScan = true;
    while (g_run) {
        // 1. forget cameras that stopped (unplugged)
        {
            std::vector<std::shared_ptr<Camera>> gone;
            {
                std::lock_guard<std::mutex> g(g_camMx);
                for (auto it = g_cams.begin(); it != g_cams.end();) {
                    if ((*it)->dead()) { gone.push_back(*it); it = g_cams.erase(it); }
                    else ++it;
                }
            }
            for (auto &c : gone) {
                logA(c->label() + ": stopped (unplugged?). Final " + c->stats());
                c->close();
                retryAfter[c->key()] = GetTickCount() + 1500;
            }
        }

        // 2. look for EyeToys that aren't running yet
        libusb_device **list = nullptr;
        ssize_t n = libusb_get_device_list(g_usb, &list);
        int seen = 0;
        bool noDriver = false;
        for (ssize_t i = 0; i < n && g_run; i++) {
            libusb_device_descriptor dd;
            if (libusb_get_device_descriptor(list[i], &dd) != 0) continue;
            if (dd.idVendor != 0x054c || dd.idProduct != 0x0155) continue;
            seen++;
            std::string key = portKey(list[i]);
            bool running = false;
            int used[MAX_CAMS + 1] = {0};
            {
                std::lock_guard<std::mutex> g(g_camMx);
                for (auto &c : g_cams) {
                    if (c->key() == key) running = true;
                    if (c->number() <= MAX_CAMS) used[c->number()] = 1;
                }
            }
            if (running) continue;
            auto ra = retryAfter.find(key);
            if (ra != retryAfter.end() && (LONG) (GetTickCount() - ra->second) < 0 && !g_rescanNow)
                continue;
            int num = 0;
            for (int k = 1; k <= MAX_CAMS; k++) if (!used[k]) { num = k; break; }
            if (!num) continue;

            auto cam = std::make_shared<Camera>(num, key, logA);
            cam->onPicture = [] {
                if (!g_picturePosted.exchange(true)) PostMessageW(g_wnd, WM_APP_PICTURE, 0, 0);
            };
            logA("Found an EyeToy at USB " + key + "; starting it as Cam " + std::to_string(num) + ".");
            Camera::OpenResult r = cam->open(list[i], WANT_FPS);
            if (r == Camera::OPEN_OK) {
                logA(cam->label() + ": running (sensor " + cam->sensorName() + ").");
                std::lock_guard<std::mutex> g(g_camMx);
                g_cams.push_back(cam);
                std::sort(g_cams.begin(), g_cams.end(),
                          [](const std::shared_ptr<Camera> &a, const std::shared_ptr<Camera> &b) {
                              return a->number() < b->number();
                          });
                retryAfter.erase(key);
            } else {
                if (r == Camera::OPEN_NO_DRIVER) noDriver = true;
                retryAfter[key] = GetTickCount() + (r == Camera::OPEN_NO_DRIVER ? 4000 : 6000);
            }
        }
        if (list) libusb_free_device_list(list, 1);
        g_rescanNow = false;

        // 3. ask Windows directly whether the video part still lacks a driver
        auto parts = driver_setup::findVideoParts();
        for (auto &p : parts) {
            std::wstring svc = p.service;
            std::transform(svc.begin(), svc.end(), svc.begin(), ::towupper);
            if (p.problem == 28 || svc.empty()) noDriver = true;
            if (firstScan)
                logW(L"Windows sees EyeToy video part " + p.instanceId + L", driver: " +
                     (p.service.empty() ? L"none" : p.service) +
                     (p.problem ? L", problem code " + std::to_wstring(p.problem) : L""));
        }
        firstScan = false;

        size_t running = camsSnapshot().size();
        std::wstring st;
        if (running > 0) {
            st = std::to_wstring(running) + (running == 1 ? L" camera running." : L" cameras running.");
            if ((int) running < std::max(seen, (int) parts.size()))
                st += noDriver ? L" Another EyeToy needs the driver set up." : L" Starting another EyeToy...";
        } else if (seen == 0 && parts.empty()) {
            st = L"No EyeToy found. Plug one into a USB port on the PC.";
        } else if (noDriver) {
            st = L"EyeToy found. Windows needs a one-time driver setup for its video: click \"Set up driver\".";
        } else {
            st = L"EyeToy found, starting it... (if this stays, click Log)";
        }
        setState(st, noDriver);
        if (noDriver && !g_offeredDriver && !g_driverBusy) {
            g_offeredDriver = true;
            PostMessageW(g_wnd, WM_APP_OFFER_DRIVER, 0, 0);
        }

        for (int i = 0; i < 15 && g_run && !g_rescanNow; i++) Sleep(100);
    }
}

// ---------------------------------------------------------------------------
// Actions
// ---------------------------------------------------------------------------

std::wstring photosFolder() {
    PWSTR pics = nullptr;
    std::wstring dir;
    if (SUCCEEDED(SHGetKnownFolderPath(FOLDERID_Pictures, 0, nullptr, &pics))) dir = pics;
    if (pics) CoTaskMemFree(pics);
    if (dir.empty()) dir = L".";
    dir += L"\\EyeToy Cam";
    CreateDirectoryW(dir.c_str(), nullptr);
    return dir;
}

void takePhoto() {
    auto cams = camsSnapshot();
    if (cams.empty()) { flash(L"No camera is running yet."); return; }
    SYSTEMTIME t;
    GetLocalTime(&t);
    wchar_t stamp[64];
    swprintf(stamp, 64, L"eyetoy_%04d%02d%02d_%02d%02d%02d", t.wYear, t.wMonth, t.wDay, t.wHour,
             t.wMinute, t.wSecond);
    std::wstring dir = photosFolder();
    int saved = 0;
    for (auto &c : cams) {
        std::vector<uint8_t> j = c->lastJpeg();
        if (j.empty()) continue;
        std::wstring name = dir + L"\\" + stamp + (cams.size() > 1 ? L"_cam" + std::to_wstring(c->number()) : L"") + L".jpg";
        HANDLE f = CreateFileW(name.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (f == INVALID_HANDLE_VALUE) { logW(L"Couldn't save " + name); continue; }
        DWORD wr = 0;
        WriteFile(f, j.data(), (DWORD) j.size(), &wr, nullptr);
        CloseHandle(f);
        if (wr == j.size()) { saved++; logW(L"Saved photo " + name); }
    }
    g_shutterUntil = GetTickCount() + 120;
    SetTimer(g_wnd, 2, 130, nullptr);
    flash(saved ? (L"Saved " + std::to_wstring(saved) + (saved == 1 ? L" photo" : L" photos") + L" to Pictures\\EyeToy Cam")
                : L"No picture to save yet.");
}

void openFolder() {
    ShellExecuteW(g_wnd, L"open", photosFolder().c_str(), nullptr, nullptr, SW_SHOWNORMAL);
}

void setClean(bool on);

void runDriverSetup() {
    if (g_driverBusy.exchange(true)) return;
    EnableWindow(g_btn[4], FALSE);
    flash(L"Setting up the driver... Windows will ask for permission.");
    logA("Starting driver setup (Windows will ask for permission)...");
    std::thread([] {
        // close cameras first so nothing holds the device during the change
        wchar_t tmp[MAX_PATH] = {0};
        GetTempPathW(MAX_PATH, tmp);
        std::wstring logPath = std::wstring(tmp) + L"EyeToyCam-driver-setup.txt";
        DeleteFileW(logPath.c_str());
        int code = driver_setup::runElevatedInstall(g_wnd);
        std::ifstream in(logPath.c_str(), std::ios::binary);
        std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
        size_t p = 0;
        while (p < data.size()) {
            size_t e = data.find('\n', p);
            if (e == std::string::npos) e = data.size();
            std::string line = data.substr(p, e - p);
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (!line.empty()) logA("  setup: " + line);
            p = e + 1;
        }
        PostMessageW(g_wnd, WM_APP_DRIVER_DONE, (WPARAM) code, 0);
    }).detach();
}

// ---------------------------------------------------------------------------
// Elevated helper mode: EyeToyCam.exe --install-driver
// ---------------------------------------------------------------------------

FILE *g_setupLog = nullptr;
void setupLog(const std::wstring &s) {
    if (!g_setupLog) return;
    std::string u;
    int n = WideCharToMultiByte(CP_UTF8, 0, s.c_str(), (int) s.size(), nullptr, 0, nullptr, nullptr);
    u.resize(n);
    WideCharToMultiByte(CP_UTF8, 0, s.c_str(), (int) s.size(), &u[0], n, nullptr, nullptr);
    fprintf(g_setupLog, "%s\r\n", u.c_str());
    fflush(g_setupLog);
}

int installDriverMode() {
    wchar_t tmp[MAX_PATH] = {0};
    GetTempPathW(MAX_PATH, tmp);
    std::wstring logPath = std::wstring(tmp) + L"EyeToyCam-driver-setup.txt";
    g_setupLog = _wfopen(logPath.c_str(), L"wb");
    bool ok = driver_setup::installWinUsb(setupLog);
    setupLog(ok ? L"Driver setup finished." : L"Driver setup did not finish.");
    if (g_setupLog) fclose(g_setupLog);
    return ok ? 0 : 1;
}

// ---------------------------------------------------------------------------
// Drawing
// ---------------------------------------------------------------------------

const COLORREF BG = RGB(18, 20, 26), PANEL = RGB(28, 31, 40), TEXT = RGB(225, 230, 240),
               DIM = RGB(140, 150, 170), ACCENT = RGB(80, 170, 255), WARN = RGB(255, 200, 90);

int toolbarHeight() { return g_clean ? 0 : S(44); }
int statusHeight() { return g_clean ? 0 : S(26); }

void layoutTiles(RECT area, int n, std::vector<RECT> &out) {
    out.clear();
    if (n <= 0) return;
    int aw = area.right - area.left, ah = area.bottom - area.top;
    int gap = g_clean ? 0 : S(6);
    int bestCols = 1;
    double bestW = 0;
    for (int cols = 1; cols <= n; cols++) {
        int rows = (n + cols - 1) / cols;
        double cw = (aw - gap * (cols + 1)) / (double) cols;
        double ch = (ah - gap * (rows + 1)) / (double) rows;
        double w = std::min(cw, ch * 4.0 / 3.0);
        if (w > bestW) { bestW = w; bestCols = cols; }
    }
    int cols = bestCols, rows = (n + cols - 1) / cols;
    int tw = (int) bestW, th = (int) (bestW * 3.0 / 4.0);
    int totalW = cols * tw + (cols - 1) * gap, totalH = rows * th + (rows - 1) * gap;
    int x0 = area.left + (aw - totalW) / 2, y0 = area.top + (ah - totalH) / 2;
    for (int i = 0; i < n; i++) {
        int r = i / cols, c = i % cols;
        int inRow = (r == rows - 1) ? n - r * cols : cols;  // centre a short last row
        int rowX = x0 + (cols - inRow) * (tw + gap) / 2;
        RECT t = {rowX + c * (tw + gap), y0 + r * (th + gap), 0, 0};
        t.right = t.left + tw;
        t.bottom = t.top + th;
        out.push_back(t);
    }
}

void drawText(HDC dc, const std::wstring &s, RECT r, COLORREF col, UINT fmt, HFONT f = nullptr) {
    SelectObject(dc, f ? f : g_font);
    SetTextColor(dc, col);
    SetBkMode(dc, TRANSPARENT);
    DrawTextW(dc, s.c_str(), (int) s.size(), &r, fmt);
}

void paint(HWND hwnd) {
    PAINTSTRUCT ps;
    HDC wdc = BeginPaint(hwnd, &ps);
    RECT cr;
    GetClientRect(hwnd, &cr);
    int W = cr.right, H = cr.bottom;
    HDC dc = CreateCompatibleDC(wdc);
    HBITMAP bmp = CreateCompatibleBitmap(wdc, std::max(W, 1), std::max(H, 1));
    HGDIOBJ oldBmp = SelectObject(dc, bmp);

    HBRUSH bg = CreateSolidBrush(g_clean ? RGB(0, 0, 0) : BG);
    FillRect(dc, &cr, bg);
    DeleteObject(bg);

    if (!g_clean) {
        RECT tb = {0, 0, W, toolbarHeight()};
        HBRUSH pb = CreateSolidBrush(PANEL);
        FillRect(dc, &tb, pb);
        DeleteObject(pb);
    }

    RECT area = {0, toolbarHeight(), W, H - statusHeight()};
    auto cams = camsSnapshot();
    std::vector<RECT> tiles;
    layoutTiles(area, (int) cams.size(), tiles);
    SetStretchBltMode(dc, HALFTONE);
    SetBrushOrgEx(dc, 0, 0, nullptr);
    for (size_t i = 0; i < cams.size(); i++) {
        Tile &t = g_tiles[cams[i]->key()];
        cams[i]->copyPicture(t.px, t.w, t.h, t.serial);
        RECT r = tiles[i];
        int tw = r.right - r.left, th = r.bottom - r.top;
        if (!t.px.empty() && t.w > 0) {
            BITMAPINFO bi = {};
            bi.bmiHeader.biSize = sizeof bi.bmiHeader;
            bi.bmiHeader.biWidth = t.w;
            bi.bmiHeader.biHeight = -t.h;   // top-down
            bi.bmiHeader.biPlanes = 1;
            bi.bmiHeader.biBitCount = 32;
            bi.bmiHeader.biCompression = BI_RGB;
            if (g_mirror)
                StretchDIBits(dc, r.right - 1, r.top, -tw, th, 0, 0, t.w, t.h, t.px.data(), &bi,
                              DIB_RGB_COLORS, SRCCOPY);
            else
                StretchDIBits(dc, r.left, r.top, tw, th, 0, 0, t.w, t.h, t.px.data(), &bi,
                              DIB_RGB_COLORS, SRCCOPY);
        } else {
            HBRUSH b = CreateSolidBrush(PANEL);
            FillRect(dc, &r, b);
            DeleteObject(b);
            drawText(dc, L"Waiting for the picture...", r, DIM, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        }
        if (!g_clean) {
            wchar_t lab[96];
            swprintf(lab, 96, L" %ls  \x00b7  %d fps ", widen(cams[i]->label()).c_str(), cams[i]->liveFps());
            RECT lr = {r.left + S(8), r.top + S(8), r.left + S(200), r.top + S(30)};
            SelectObject(dc, g_font);
            SIZE sz;
            GetTextExtentPoint32W(dc, lab, (int) wcslen(lab), &sz);
            lr.right = lr.left + sz.cx + S(4);
            HBRUSH b = CreateSolidBrush(RGB(0, 0, 0));
            FillRect(dc, &lr, b);
            DeleteObject(b);
            drawText(dc, lab, lr, TEXT, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
        }
    }

    std::wstring state;
    bool needDriver;
    {
        std::lock_guard<std::mutex> g(g_stateMx);
        state = g_state;
        needDriver = g_needDriver;
    }
    if (cams.empty()) {
        RECT r = area;
        InflateRect(&r, -S(40), -S(20));
        RECT t1 = r, t2 = r;
        t1.bottom = r.top + (r.bottom - r.top) / 2 - S(6);
        t2.top = t1.bottom + S(12);
        drawText(dc, L"EyeToy Cam", t1, ACCENT, DT_CENTER | DT_BOTTOM | DT_SINGLELINE, g_bigFont);
        drawText(dc, state, t2, needDriver ? WARN : DIM, DT_CENTER | DT_TOP | DT_WORDBREAK);
    }

    if (GetTickCount() < g_shutterUntil) {
        HBRUSH w = CreateSolidBrush(RGB(255, 255, 255));
        FillRect(dc, &area, w);
        DeleteObject(w);
    }

    if (!g_clean) {
        RECT sr = {0, H - statusHeight(), W, H};
        HBRUSH pb = CreateSolidBrush(PANEL);
        FillRect(dc, &sr, pb);
        DeleteObject(pb);
        sr.left += S(10);
        sr.right -= S(10);
        std::wstring msg = GetTickCount() < g_flashUntil ? g_flash : state;
        drawText(dc, msg, sr, (GetTickCount() >= g_flashUntil && needDriver) ? WARN : DIM,
                 DT_LEFT | DT_VCENTER | DT_SINGLELINE | DT_END_ELLIPSIS);
        drawText(dc, L"Space photo \x00b7 M mirror \x00b7 C clean view", sr, DIM,
                 DT_RIGHT | DT_VCENTER | DT_SINGLELINE);
    }

    BitBlt(wdc, 0, 0, W, H, dc, 0, 0, SRCCOPY);
    SelectObject(dc, oldBmp);
    DeleteObject(bmp);
    DeleteDC(dc);
    EndPaint(hwnd, &ps);
}

// ---------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------

void makeFonts() {
    if (g_font) DeleteObject(g_font);
    if (g_bigFont) DeleteObject(g_bigFont);
    g_font = CreateFontW(-S(14), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
    g_bigFont = CreateFontW(-S(30), 0, 0, 0, FW_SEMIBOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Segoe UI");
}

void layoutButtons() {
    int x = S(8), y = S(7), h = S(30);
    const int widths[6] = {100, 112, 76, 92, 110, 56};
    for (int i = 0; i < 6; i++) {
        int w = S(widths[i]);
        if (i == 4) {   // keep "Set up driver" and "Log" on the right
            RECT cr;
            GetClientRect(g_wnd, &cr);
            int right = cr.right - S(8) - S(widths[5]) - S(6);
            x = std::max(x, right - w);
        }
        MoveWindow(g_btn[i], x, y, w, h, TRUE);
        SendMessageW(g_btn[i], WM_SETFONT, (WPARAM) g_font, TRUE);
        ShowWindow(g_btn[i], g_clean ? SW_HIDE : SW_SHOW);
        x += w + S(6);
    }
}

void setClean(bool on) {
    g_clean = on;
    layoutButtons();
    if (on) flash(L"Clean view: press Esc or double-click to get the buttons back.");
    InvalidateRect(g_wnd, nullptr, FALSE);
}

void updateTitle() {
    std::wstring t = L"EyeToy Cam " + widen(APP_VERSION) + L" (build " + widen(BUILD_NUMBER) + L")";
    SetWindowTextW(g_wnd, t.c_str());
}

void showLog() {
    if (!g_logWnd) return;
    ShowWindow(g_logWnd, SW_SHOWNORMAL);
    SetForegroundWindow(g_logWnd);
    std::lock_guard<std::mutex> g(g_logMx);
    SetWindowTextW(g_logEdit, g_logText.c_str());
    g_logPending.clear();
    SendMessageW(g_logEdit, EM_SETSEL, (WPARAM) -1, (LPARAM) -1);
    SendMessageW(g_logEdit, EM_SCROLLCARET, 0, 0);
}

void copyLog() {
    std::wstring all;
    {
        std::lock_guard<std::mutex> g(g_logMx);
        all = g_logText;
    }
    for (auto &c : camsSnapshot()) all += widen(c->stats()) + L"\r\n";
    if (!OpenClipboard(g_logWnd)) return;
    EmptyClipboard();
    HGLOBAL m = GlobalAlloc(GMEM_MOVEABLE, (all.size() + 1) * sizeof(wchar_t));
    if (m) {
        memcpy(GlobalLock(m), all.c_str(), (all.size() + 1) * sizeof(wchar_t));
        GlobalUnlock(m);
        SetClipboardData(CF_UNICODETEXT, m);
    }
    CloseClipboard();
    MessageBoxW(g_logWnd, L"The log is copied. Paste it into the chat with Claude.", L"EyeToy Cam", MB_OK);
}

LRESULT CALLBACK logProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
    case WM_CREATE: {
        g_logEdit = CreateWindowExW(WS_EX_CLIENTEDGE, L"EDIT", L"",
                                    WS_CHILD | WS_VISIBLE | WS_VSCROLL | ES_MULTILINE | ES_READONLY | ES_AUTOVSCROLL,
                                    0, 0, 0, 0, h, (HMENU) ID_LOGEDIT, g_inst, nullptr);
        SendMessageW(g_logEdit, EM_SETLIMITTEXT, 0, 0);
        HWND b = CreateWindowW(L"BUTTON", L"Copy log", WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                               0, 0, 0, 0, h, (HMENU) ID_COPYLOG, g_inst, nullptr);
        SendMessageW(b, WM_SETFONT, (WPARAM) g_font, TRUE);
        HFONT mono = CreateFontW(-S(13), 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, CLEARTYPE_QUALITY, 0, L"Consolas");
        SendMessageW(g_logEdit, WM_SETFONT, (WPARAM) mono, TRUE);
        return 0;
    }
    case WM_SIZE: {
        int W = LOWORD(l), H = HIWORD(l);
        MoveWindow(g_logEdit, 0, 0, W, H - S(44), TRUE);
        MoveWindow(GetDlgItem(h, ID_COPYLOG), S(8), H - S(38), S(110), S(30), TRUE);
        return 0;
    }
    case WM_COMMAND:
        if (LOWORD(w) == ID_COPYLOG) copyLog();
        return 0;
    case WM_CLOSE:
        ShowWindow(h, SW_HIDE);
        return 0;
    }
    return DefWindowProcW(h, m, w, l);
}

LRESULT CALLBACK wndProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
    case WM_CREATE: {
        g_wnd = h;
        const wchar_t *labels[6] = {L"Take photo", L"Photos folder", L"Mirror", L"Clean view", L"Set up driver", L"Log"};
        for (int i = 0; i < 6; i++)
            g_btn[i] = CreateWindowW(L"BUTTON", labels[i], WS_CHILD | WS_VISIBLE | BS_PUSHBUTTON,
                                     0, 0, 10, 10, h, (HMENU) (INT_PTR) (ID_PHOTO + i), g_inst, nullptr);
        layoutButtons();
        SetTimer(h, 1, 1000, nullptr);   // refresh fps labels
        return 0;
    }
    case WM_SIZE:
        layoutButtons();
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    case WM_DPICHANGED: {
        g_dpi = HIWORD(w);
        makeFonts();
        RECT *r = (RECT *) l;
        SetWindowPos(h, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER);
        layoutButtons();
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT:
        paint(h);
        return 0;
    case WM_TIMER:
        if (w == 2) KillTimer(h, 2);
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    case WM_APP_PICTURE:
        g_picturePosted = false;
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    case WM_APP_STATE:
        InvalidateRect(h, nullptr, FALSE);
        return 0;
    case WM_APP_LOG:
        if (g_logWnd && IsWindowVisible(g_logWnd)) {
            std::wstring add;
            {
                std::lock_guard<std::mutex> g(g_logMx);
                add.swap(g_logPending);
            }
            int len = GetWindowTextLengthW(g_logEdit);
            SendMessageW(g_logEdit, EM_SETSEL, len, len);
            SendMessageW(g_logEdit, EM_REPLACESEL, FALSE, (LPARAM) add.c_str());
        } else {
            std::lock_guard<std::mutex> g(g_logMx);
            g_logPending.clear();
        }
        return 0;
    case WM_APP_OFFER_DRIVER:
        if (MessageBoxW(h,
                        L"Windows doesn't have a driver for the EyeToy's video yet (the microphone already works).\n\n"
                        L"EyeToy Cam can set this up now using Windows' own built-in WinUSB driver. "
                        L"Windows will ask for permission once.\n\nSet it up now?",
                        L"EyeToy Cam: one-time setup", MB_YESNO | MB_ICONQUESTION) == IDYES)
            runDriverSetup();
        return 0;
    case WM_APP_DRIVER_DONE: {
        g_driverBusy = false;
        EnableWindow(g_btn[4], TRUE);
        int code = (int) w;
        if (code == 0) {
            logA("Driver setup finished.");
            flash(L"Driver set up. The picture should appear in a few seconds; if not, unplug the EyeToy and plug it back in.");
        } else if (code == -1) {
            logA("Driver setup was cancelled (Windows permission wasn't given).");
            flash(L"Driver setup cancelled. Click \"Set up driver\" to try again.");
        } else {
            logA("Driver setup didn't finish (code " + std::to_string(code) + ").");
            MessageBoxW(h,
                        L"The automatic driver setup didn't work on this PC.\n\n"
                        L"Click Log, then Copy log, and paste it to Claude.\n\n"
                        L"Manual way (2 minutes): download Zadig from zadig.akeo.ie, open it, choose "
                        L"Options > List All Devices, pick \"EyeToy USB camera Namtai (Interface 0)\", "
                        L"choose WinUSB and click Install Driver. Then reopen EyeToy Cam.",
                        L"EyeToy Cam", MB_OK | MB_ICONWARNING);
        }
        g_rescanNow = true;
        return 0;
    }
    case WM_COMMAND:
        switch (LOWORD(w)) {
        case ID_PHOTO: takePhoto(); break;
        case ID_FOLDER: openFolder(); break;
        case ID_MIRROR: g_mirror = !g_mirror; flash(g_mirror ? L"Mirror on" : L"Mirror off"); break;
        case ID_CLEAN: setClean(true); break;
        case ID_DRIVER: runDriverSetup(); break;
        case ID_LOG: showLog(); break;
        }
        SetFocus(h);
        return 0;
    case WM_KEYDOWN:
        if (w == VK_SPACE) takePhoto();
        else if (w == 'M') SendMessageW(h, WM_COMMAND, ID_MIRROR, 0);
        else if (w == 'C' || w == VK_F11) setClean(!g_clean);
        else if (w == VK_ESCAPE && g_clean) setClean(false);
        return 0;
    case WM_LBUTTONDBLCLK:
        setClean(!g_clean);
        return 0;
    case WM_GETMINMAXINFO: {
        MINMAXINFO *mm = (MINMAXINFO *) l;
        mm->ptMinTrackSize.x = S(560);
        mm->ptMinTrackSize.y = S(360);
        return 0;
    }
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(h, m, w, l);
}

} // namespace

int WINAPI wWinMain(HINSTANCE inst, HINSTANCE, PWSTR cmdLine, int show) {
    g_inst = inst;
    if (cmdLine && wcsstr(cmdLine, L"--install-driver")) return installDriverMode();

    SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    INITCOMMONCONTROLSEX icc = {sizeof icc, ICC_STANDARD_CLASSES};
    InitCommonControlsEx(&icc);

    HDC sdc = GetDC(nullptr);
    g_dpi = GetDeviceCaps(sdc, LOGPIXELSX);
    ReleaseDC(nullptr, sdc);
    makeFonts();

    HICON icon = LoadIconW(inst, MAKEINTRESOURCEW(1));
    WNDCLASSEXW wc = {sizeof wc};
    wc.style = CS_DBLCLKS | CS_HREDRAW | CS_VREDRAW;
    wc.lpfnWndProc = wndProc;
    wc.hInstance = inst;
    wc.hCursor = LoadCursor(nullptr, IDC_ARROW);
    wc.hIcon = icon;
    wc.hIconSm = icon;
    wc.lpszClassName = L"EyeToyCamWindow";
    RegisterClassExW(&wc);

    WNDCLASSEXW lc = wc;
    lc.style = 0;
    lc.lpfnWndProc = logProc;
    lc.hbrBackground = (HBRUSH) (COLOR_WINDOW + 1);
    lc.lpszClassName = L"EyeToyCamLog";
    RegisterClassExW(&lc);

    HWND wnd = CreateWindowExW(0, L"EyeToyCamWindow", L"EyeToy Cam", WS_OVERLAPPEDWINDOW,
                               CW_USEDEFAULT, CW_USEDEFAULT, S(900), S(720), nullptr, nullptr, inst, nullptr);
    g_dpi = GetDpiForWindow(wnd);
    makeFonts();
    layoutButtons();
    updateTitle();
    g_logWnd = CreateWindowExW(0, L"EyeToyCamLog", L"EyeToy Cam log", WS_OVERLAPPEDWINDOW,
                               CW_USEDEFAULT, CW_USEDEFAULT, S(820), S(520), nullptr, nullptr, inst, nullptr);
    ShowWindow(wnd, show);
    UpdateWindow(wnd);

    logA("EyeToy Cam for Windows " APP_VERSION " (build " BUILD_NUMBER ") started.");
    OSVERSIONINFOW ov = {sizeof ov};
    typedef LONG(WINAPI * RtlGetVersionFn)(OSVERSIONINFOW *);
    if (auto rgv = (RtlGetVersionFn) GetProcAddress(GetModuleHandleW(L"ntdll.dll"), "RtlGetVersion")) {
        rgv(&ov);
        logA("Windows version " + std::to_string(ov.dwMajorVersion) + "." + std::to_string(ov.dwMinorVersion) +
             " build " + std::to_string(ov.dwBuildNumber));
    }

    int r = libusb_init(&g_usb);
    if (r != 0) {
        logA(std::string("USB library couldn't start: ") + libusb_error_name(r));
        setState(L"The USB library couldn't start. Click Log and send it to Claude.", false);
    } else {
        const libusb_version *v = libusb_get_version();
        logA("libusb " + std::to_string(v->major) + "." + std::to_string(v->minor) + "." + std::to_string(v->micro));
        libusb_set_log_cb(g_usb, libusbLog, LIBUSB_LOG_CB_CONTEXT);
        libusb_set_option(g_usb, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
        g_eventThread = std::thread(eventLoop);
        g_managerThread = std::thread(managerLoop);
    }

    MSG msg;
    while (GetMessageW(&msg, nullptr, 0, 0) > 0) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }

    g_run = false;
    if (g_managerThread.joinable()) g_managerThread.join();
    for (auto &c : camsSnapshot()) c->close();
    {
        std::lock_guard<std::mutex> g(g_camMx);
        g_cams.clear();
    }
    g_events = false;
    if (g_eventThread.joinable()) g_eventThread.join();
    if (g_usb) libusb_exit(g_usb);
    CoUninitialize();
    return 0;
}
