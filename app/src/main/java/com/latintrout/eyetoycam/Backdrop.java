package com.latintrout.eyetoycam;

/** A full-screen background behind the app. */
interface Backdrop {
    /** Pausing any animation saves battery/CPU, e.g. while recording. */
    void setAnimated(boolean on);
}
