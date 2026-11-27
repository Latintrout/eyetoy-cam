# EyeToy Cam for Windows

A small Windows program (one .exe, nothing to install) that shows the picture
from PS2 EyeToy cameras (USB 054c:0155, OV519 chip) and saves photos.
It reuses the camera start-up recipe from the Android app (EyeToyDriver.java),
which comes from the Linux kernel's gspca ov519 driver.

## Download

Each push builds a new copy: GitHub > Releases > "EyeToy Cam for Windows, build N" >
EyeToyCam.exe. Windows SmartScreen may warn because the file isn't signed:
click "More info" then "Run anyway".

## First run: driver setup (once per PC)

Windows has no driver for the EyeToy's video (Device Manager shows Code 28);
the microphone already works. On first run EyeToy Cam offers to fix that: it
puts Windows' own built-in WinUSB driver on the camera's video part
("Interface 0"). Windows asks for permission once. The mic is not touched.

If that doesn't work, the manual way: download Zadig (zadig.akeo.ie),
Options > List All Devices, pick "EyeToy USB camera Namtai (Interface 0)",
choose WinUSB, click Install Driver.

## Use

- Take photo (or Space): saves one JPEG per camera to Pictures\EyeToy Cam.
- Mirror (or M), Clean view (or C / double-click; Esc to leave): hides the
  buttons and labels, for capturing the window in OBS.
- Several EyeToys: plug them into different USB ports. Each EyeToy uses
  almost a whole USB 1.1 lane, so cameras on the same hub may get a smaller,
  slower stream.
- Log: what the app did; "Copy log" to send it for help.

## OBS (until the virtual camera arrives)

Sources > + > Window Capture > window "[EyeToyCam.exe]: EyeToy Cam...".
Turn on Clean view in the app. For sound, add Audio Input Capture > the
EyeToy microphone.

## Building

`windows/build.sh` cross-compiles with MinGW-w64 on Linux; see
`.github/workflows/windows.yml`. libusb 1.0.27 (LGPL-2.1) is linked in.
