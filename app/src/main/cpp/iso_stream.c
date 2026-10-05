/*
 * EyeToy Cam - isochronous USB streaming for the OV519 bridge chip.
 *
 * Android's Java USB API cannot do isochronous transfers, so this small
 * native helper talks to the kernel's usbfs directly using the file
 * descriptor Android gives us (no root needed).
 *
 * Packet framing follows ov519_pkt_scan() from the Linux gspca ov519 driver:
 *   every frame starts with a packet whose first bytes are ff ff ff 50
 *   (16-byte header, then JPEG data) and ends with a packet starting
 *   ff ff ff 51 (byte 9 != 0 means "discard this frame").
 */
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

#define NURBS 10
#define NPKTS 32
#define MAX_FRAME (1024 * 1024)

static int g_fd = -1;
static int g_ep = 0;
static int g_psize = 0;
static volatile int g_running = 0;
static pthread_t g_thread;
static struct usbdevfs_urb *g_urbs[NURBS];
static volatile int g_inflight[NURBS];

/* frame assembly (only touched by the streaming thread) */
static unsigned char *g_cur = NULL;
static int g_curlen = 0;
static int g_collecting = 0;

/* last finished frame (shared with Java, guarded by g_mtx) */
static pthread_mutex_t g_mtx = PTHREAD_MUTEX_INITIALIZER;
static unsigned char *g_ready = NULL;
static int g_readylen = 0;
static int g_readynew = 0;

/* statistics for the on-screen log */
static volatile long st_urbs, st_pkts, st_datapkts, st_bytes, st_frames,
        st_dropped, st_pkterr, st_reaperr, st_submiterr;
static volatile int st_errno;
static char g_hex_first[160];
static char g_hex_sof[160];

static void to_hex(char *out, size_t outsz, const unsigned char *d, int len) {
    size_t pos = 0;
    out[0] = 0;
    for (int i = 0; i < len && pos + 4 < outsz; i++)
        pos += (size_t) snprintf(out + pos, outsz - pos, "%02x ", d[i]);
}

static void append(const unsigned char *d, int len) {
    if (len <= 0) return;
    if (g_curlen + len > MAX_FRAME) {   /* runaway frame, throw it away */
        g_collecting = 0;
        st_dropped++;
        return;
    }
    memcpy(g_cur + g_curlen, d, (size_t) len);
    g_curlen += len;
}

static void publish(void) {
    pthread_mutex_lock(&g_mtx);
    unsigned char *t = g_ready;
    g_ready = g_cur;
    g_readylen = g_curlen;
    g_readynew = 1;
    g_cur = t;
    pthread_mutex_unlock(&g_mtx);
    g_curlen = 0;
    st_frames++;
}

static void scan(const unsigned char *d, int len) {
    if (!g_hex_first[0]) to_hex(g_hex_first, sizeof g_hex_first, d, len < 32 ? len : 32);

    if (len >= 4 && d[0] == 0xff && d[1] == 0xff && d[2] == 0xff) {
        if (d[3] == 0x50) {                 /* start of frame */
            if (!g_hex_sof[0]) to_hex(g_hex_sof, sizeof g_hex_sof, d, len < 40 ? len : 40);
            if (g_collecting) st_dropped++; /* previous frame never ended */
            g_curlen = 0;
            const unsigned char *p = d + 16;
            int n = len - 16;
            if (n < 2 || p[0] == 0xff || p[1] == 0xd8) {
                g_collecting = 1;
                append(p, n);
            } else {
                g_collecting = 0;
                st_dropped++;
            }
            return;
        }
        if (d[3] == 0x51) {                 /* end of frame */
            if (g_collecting && len > 9 && d[9] == 0 && g_curlen > 0)
                publish();
            else if (g_collecting)
                st_dropped++;
            g_collecting = 0;
            g_curlen = 0;
            return;
        }
    }
    if (g_collecting) append(d, len);
}

static int submit(int i) {
    struct usbdevfs_urb *u = g_urbs[i];
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) g_ep;
    u->status = 0;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer_length = NPKTS * g_psize;
    u->actual_length = 0;
    u->start_frame = 0;
    u->number_of_packets = NPKTS;
    u->error_count = 0;
    u->signr = 0;
    u->usercontext = (void *) (intptr_t) i;
    for (int k = 0; k < NPKTS; k++) {
        u->iso_frame_desc[k].length = (unsigned int) g_psize;
        u->iso_frame_desc[k].actual_length = 0;
        u->iso_frame_desc[k].status = 0;
    }
    if (ioctl(g_fd, USBDEVFS_SUBMITURB, u) < 0) {
        st_errno = errno;
        st_submiterr++;
        g_inflight[i] = 0;
        return -errno;
    }
    g_inflight[i] = 1;
    return 0;
}

static int any_inflight(void) {
    for (int i = 0; i < NURBS; i++) if (g_inflight[i]) return 1;
    return 0;
}

static void *loop(void *arg) {
    (void) arg;
    while (g_running) {
        struct usbdevfs_urb *u = NULL;
        int r = ioctl(g_fd, USBDEVFS_REAPURBNDELAY, &u);
        if (r < 0) {
            if (errno == EAGAIN) { usleep(500); continue; }
            st_errno = errno;
            st_reaperr++;
            if (errno == ENODEV || errno == ESHUTDOWN) { g_running = 0; break; }
            usleep(2000);
            continue;
        }
        int i = (int) (intptr_t) u->usercontext;
        g_inflight[i] = 0;
        st_urbs++;
        for (int k = 0; k < u->number_of_packets; k++) {
            struct usbdevfs_iso_packet_desc *pd = &u->iso_frame_desc[k];
            st_pkts++;
            if (pd->status != 0) { st_pkterr++; continue; }
            int len = (int) pd->actual_length;
            if (len > 0) {
                st_datapkts++;
                st_bytes += len;
                scan((unsigned char *) u->buffer + (size_t) k * (size_t) g_psize, len);
            }
        }
        if (g_running) submit(i);
    }
    /* cancel and collect everything still queued */
    for (int i = 0; i < NURBS; i++)
        if (g_inflight[i]) ioctl(g_fd, USBDEVFS_DISCARDURB, g_urbs[i]);
    for (int tries = 0; tries < 300 && any_inflight(); tries++) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(g_fd, USBDEVFS_REAPURBNDELAY, &u) == 0 && u)
            g_inflight[(int) (intptr_t) u->usercontext] = 0;
        else
            usleep(1000);
    }
    return NULL;
}

static void free_all(void) {
    for (int i = 0; i < NURBS; i++) {
        if (g_urbs[i]) {
            free(g_urbs[i]->buffer);
            free(g_urbs[i]);
            g_urbs[i] = NULL;
        }
        g_inflight[i] = 0;
    }
    free(g_cur); g_cur = NULL;
    pthread_mutex_lock(&g_mtx);
    free(g_ready); g_ready = NULL; g_readylen = 0; g_readynew = 0;
    pthread_mutex_unlock(&g_mtx);
}

/* returns 0 on success, negative errno on failure */
static int stream_start(int fd, int ep, int psize) {
    if (g_running) return -EBUSY;
    if (psize <= 0) return -EINVAL;
    g_fd = fd; g_ep = ep; g_psize = psize;
    st_urbs = st_pkts = st_datapkts = st_bytes = st_frames = 0;
    st_dropped = st_pkterr = st_reaperr = st_submiterr = 0;
    st_errno = 0;
    g_hex_first[0] = 0; g_hex_sof[0] = 0;
    g_curlen = 0; g_collecting = 0;
    g_cur = malloc(MAX_FRAME);
    pthread_mutex_lock(&g_mtx);
    g_ready = malloc(MAX_FRAME); g_readylen = 0; g_readynew = 0;
    pthread_mutex_unlock(&g_mtx);
    if (!g_cur || !g_ready) { free_all(); return -ENOMEM; }

    int ok = 0, lasterr = 0;
    for (int i = 0; i < NURBS; i++) {
        g_urbs[i] = calloc(1, sizeof(struct usbdevfs_urb) +
                              NPKTS * sizeof(struct usbdevfs_iso_packet_desc));
        if (!g_urbs[i]) { free_all(); return -ENOMEM; }
        g_urbs[i]->buffer = malloc((size_t) NPKTS * (size_t) psize);
        if (!g_urbs[i]->buffer) { free_all(); return -ENOMEM; }
    }
    for (int i = 0; i < NURBS; i++) {
        int r = submit(i);
        if (r == 0) ok++; else lasterr = r;
    }
    if (ok == 0) { free_all(); return lasterr ? lasterr : -EIO; }
    g_running = 1;
    if (pthread_create(&g_thread, NULL, loop, NULL) != 0) {
        g_running = 0;
        for (int i = 0; i < NURBS; i++)
            if (g_inflight[i]) ioctl(g_fd, USBDEVFS_DISCARDURB, g_urbs[i]);
        free_all();
        return -EAGAIN;
    }
    return 0;
}

static void stream_stop(void) {
    if (!g_urbs[0]) return;
    g_running = 0;
    pthread_join(g_thread, NULL);
    free_all();
}

/* ------------------------------ JNI glue ------------------------------ */

JNIEXPORT jint JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStart(JNIEnv *env, jclass cls,
                                                    jint fd, jint ep, jint psize) {
    (void) env; (void) cls;
    return stream_start(fd, ep, psize);
}

JNIEXPORT void JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStop(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    stream_stop();
}

JNIEXPORT jboolean JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeIsRunning(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return g_running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeGetFrame(JNIEnv *env, jclass cls) {
    (void) cls;
    jbyteArray out = NULL;
    pthread_mutex_lock(&g_mtx);
    if (g_readynew && g_ready && g_readylen > 0) {
        out = (*env)->NewByteArray(env, g_readylen);
        if (out)
            (*env)->SetByteArrayRegion(env, out, 0, g_readylen, (const jbyte *) g_ready);
        g_readynew = 0;
    }
    pthread_mutex_unlock(&g_mtx);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStats(JNIEnv *env, jclass cls) {
    (void) cls;
    jlong v[10] = {st_urbs, st_pkts, st_datapkts, st_bytes, st_frames,
                   st_dropped, st_pkterr, st_reaperr, st_submiterr, st_errno};
    jlongArray out = (*env)->NewLongArray(env, 10);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 10, v);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeDebug(JNIEnv *env, jclass cls) {
    (void) cls;
    char buf[400];
    snprintf(buf, sizeof buf, "first packet: %s\nfirst frame-start packet: %s",
             g_hex_first[0] ? g_hex_first : "(none yet)",
             g_hex_sof[0] ? g_hex_sof : "(none yet)");
    return (*env)->NewStringUTF(env, buf);
}
