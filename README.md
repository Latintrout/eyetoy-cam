# EyeToy Cam

An Android app that uses an original PlayStation 2 EyeToy (USB ID `054c:0155`, OV519 chip) as a camera on an Android phone over USB OTG.

**Status:** test build. It connects to the camera, starts it, shows the live picture and can save a photo. Video recording comes next.

## Install

On your phone, open:

**https://github.com/Latintrout/eyetoy-cam/releases/latest/download/eyetoy-cam.apk**

Tap the downloaded file to install. Android will ask you to allow installs from your browser the first time.

## How it works

- The camera driver logic is ported from the Linux kernel's gspca `ov519` driver.
- Android's Java USB API can't do the isochronous transfers the camera uses, so a small native helper (`app/src/main/cpp/iso_stream.c`) talks to the kernel's usbfs directly through the file descriptor Android hands out. No root needed.
- Frames arrive as JPEG and are decoded with Android's built-in decoder. Standard Huffman tables are added if the camera leaves them out.

Every push to `main` builds a new APK with GitHub Actions and publishes it as the latest release.
