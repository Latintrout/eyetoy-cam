#!/bin/sh
# Builds EyeToyCam.exe with MinGW-w64 (cross-compiles on Linux).
# Needs: g++-mingw-w64-x86-64, and the libusb Windows release unpacked at $LIBUSB
# (https://github.com/libusb/libusb/releases, libusb-1.0.27.7z).
set -e
cd "$(dirname "$0")"
LIBUSB=${LIBUSB:-third_party/libusb}
BUILD_NUMBER=${BUILD_NUMBER:-dev}
CXX=${CXX:-x86_64-w64-mingw32-g++-posix}
WINDRES=${WINDRES:-x86_64-w64-mingw32-windres}
mkdir -p out
$WINDRES -I res res/app.rc -O coff -o out/app.res.o
$CXX -std=c++17 -O2 -Wall -Wno-unknown-pragmas -municode -mwindows \
  -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN -DNOMINMAX -D_WIN32_WINNT=0x0A00 -DWINVER=0x0A00 \
  -DBUILD_NUMBER="\"$BUILD_NUMBER\"" \
  -I"$LIBUSB/include" -Isrc \
  src/main.cpp src/camera.cpp src/ov519.cpp src/jpeg.cpp src/driver_setup.cpp out/app.res.o \
  "$LIBUSB/MinGW64/static/libusb-1.0.a" \
  -static -static-libgcc -static-libstdc++ \
  -lsetupapi -lcfgmgr32 -lole32 -lwindowscodecs -luuid -lshell32 -lcomctl32 -lgdi32 -luser32 -ladvapi32 \
  -o out/EyeToyCam.exe
x86_64-w64-mingw32-strip out/EyeToyCam.exe 2>/dev/null || true
ls -l out/EyeToyCam.exe
