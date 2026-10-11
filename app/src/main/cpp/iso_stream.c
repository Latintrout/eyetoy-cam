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
 *
 * Up to MAX_STREAMS cameras can stream at once, each in its own slot with
 * its own file descriptor, URB queue and frame buffers.
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
#define MAX_STREAMS 4   /* one per camera; slot numbers come from Java */

typedef struct {
    int fd, ep, psize;
    volatile int running;
    pthread_t thread;
    struct usbdevfs_urb *urbs[NURBS];
    volatile int inflight[NURBS];

    /* frame assembly (only touched by the streaming thread) */
    unsigned char *cur;
    int curlen, collecting;

    /* last finished frame (shared with Java, guarded by mtx) */
    pthread_mutex_t mtx;
    unsigned char *ready;
    int readylen, readynew;

    /* statistics for the on-screen log */
    volatile long st_urbs, st_pkts, st_datapkts, st_bytes, st_frames,
            st_dropped, st_pkterr, st_reaperr, st_submiterr;
    volatile int st_errno;
    char hex_first[160];
    char hex_sof[160];
} stream_t;

static stream_t g_s[MAX_STREAMS];
static pthread_once_t g_once = PTHREAD_ONCE_INIT;

static void init_mutexes(void) {
    for (int i = 0; i < MAX_STREAMS; i++) pthread_mutex_init(&g_s[i].mtx, NULL);
}

static stream_t *slot_of(int slot) {
    if (slot < 0 || slot >= MAX_STREAMS) return NULL;
    pthread_once(&g_once, init_mutexes);
    return &g_s[slot];
}

static void to_hex(char *out, size_t outsz, const unsigned char *d, int len) {
    size_t pos = 0;
    out[0] = 0;
    for (int i = 0; i < len && pos + 4 < outsz; i++)
        pos += (size_t) snprintf(out + pos, outsz - pos, "%02x ", d[i]);
}

static void append(stream_t *s, const unsigned char *d, int len) {
    if (len <= 0) return;
    if (s->curlen + len > MAX_FRAME) {   /* runaway frame, throw it away */
        s->collecting = 0;
        s->st_dropped++;
        return;
    }
    memcpy(s->cur + s->curlen, d, (size_t) len);
    s->curlen += len;
}

static void publish(stream_t *s) {
    pthread_mutex_lock(&s->mtx);
    unsigned char *t = s->ready;
    s->ready = s->cur;
    s->readylen = s->curlen;
    s->readynew = 1;
    s->cur = t;
    pthread_mutex_unlock(&s->mtx);
    s->curlen = 0;
    s->st_frames++;
}

static void scan(stream_t *s, const unsigned char *d, int len) {
    if (!s->hex_first[0]) to_hex(s->hex_first, sizeof s->hex_first, d, len < 32 ? len : 32);

    if (len >= 4 && d[0] == 0xff && d[1] == 0xff && d[2] == 0xff) {
        if (d[3] == 0x50) {                 /* start of frame */
            if (!s->hex_sof[0]) to_hex(s->hex_sof, sizeof s->hex_sof, d, len < 40 ? len : 40);
            if (s->collecting) s->st_dropped++; /* previous frame never ended */
            s->curlen = 0;
            const unsigned char *p = d + 16;
            int n = len - 16;
            if (n < 2 || p[0] == 0xff || p[1] == 0xd8) {
                s->collecting = 1;
                append(s, p, n);
            } else {
                s->collecting = 0;
                s->st_dropped++;
            }
            return;
        }
        if (d[3] == 0x51) {                 /* end of frame */
            if (s->collecting && len > 9 && d[9] == 0 && s->curlen > 0)
                publish(s);
            else if (s->collecting)
                s->st_dropped++;
            s->collecting = 0;
            s->curlen = 0;
            return;
        }
    }
    if (s->collecting) append(s, d, len);
}

static int submit(stream_t *s, int i) {
    struct usbdevfs_urb *u = s->urbs[i];
    u->type = USBDEVFS_URB_TYPE_ISO;
    u->endpoint = (unsigned char) s->ep;
    u->status = 0;
    u->flags = USBDEVFS_URB_ISO_ASAP;
    u->buffer_length = NPKTS * s->psize;
    u->actual_length = 0;
    u->start_frame = 0;
    u->number_of_packets = NPKTS;
    u->error_count = 0;
    u->signr = 0;
    u->usercontext = (void *) (intptr_t) i;
    for (int k = 0; k < NPKTS; k++) {
        u->iso_frame_desc[k].length = (unsigned int) s->psize;
        u->iso_frame_desc[k].actual_length = 0;
        u->iso_frame_desc[k].status = 0;
    }
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, u) < 0) {
        s->st_errno = errno;
        s->st_submiterr++;
        s->inflight[i] = 0;
        return -errno;
    }
    s->inflight[i] = 1;
    return 0;
}

static int any_inflight(stream_t *s) {
    for (int i = 0; i < NURBS; i++) if (s->inflight[i]) return 1;
    return 0;
}

static void *loop(void *arg) {
    stream_t *s = (stream_t *) arg;
    while (s->running) {
        struct usbdevfs_urb *u = NULL;
        int r = ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u);
        if (r < 0) {
            if (errno == EAGAIN) { usleep(500); continue; }
            s->st_errno = errno;
            s->st_reaperr++;
            if (errno == ENODEV || errno == ESHUTDOWN) { s->running = 0; break; }
            usleep(2000);
            continue;
        }
        int i = (int) (intptr_t) u->usercontext;
        s->inflight[i] = 0;
        s->st_urbs++;
        for (int k = 0; k < u->number_of_packets; k++) {
            struct usbdevfs_iso_packet_desc *pd = &u->iso_frame_desc[k];
            s->st_pkts++;
            if (pd->status != 0) { s->st_pkterr++; continue; }
            int len = (int) pd->actual_length;
            if (len > 0) {
                s->st_datapkts++;
                s->st_bytes += len;
                scan(s, (unsigned char *) u->buffer + (size_t) k * (size_t) s->psize, len);
            }
        }
        if (s->running) submit(s, i);
    }
    /* cancel and collect everything still queued */
    for (int i = 0; i < NURBS; i++)
        if (s->inflight[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
    for (int tries = 0; tries < 300 && any_inflight(s); tries++) {
        struct usbdevfs_urb *u = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) == 0 && u)
            s->inflight[(int) (intptr_t) u->usercontext] = 0;
        else
            usleep(1000);
    }
    return NULL;
}

static void free_all(stream_t *s) {
    for (int i = 0; i < NURBS; i++) {
        if (s->urbs[i]) {
            free(s->urbs[i]->buffer);
            free(s->urbs[i]);
            s->urbs[i] = NULL;
        }
        s->inflight[i] = 0;
    }
    free(s->cur); s->cur = NULL;
    pthread_mutex_lock(&s->mtx);
    free(s->ready); s->ready = NULL; s->readylen = 0; s->readynew = 0;
    pthread_mutex_unlock(&s->mtx);
}

/* returns 0 on success, negative errno on failure */
static int stream_start(stream_t *s, int fd, int ep, int psize) {
    if (s->running || s->urbs[0]) return -EBUSY;
    if (psize <= 0) return -EINVAL;
    s->fd = fd; s->ep = ep; s->psize = psize;
    s->st_urbs = s->st_pkts = s->st_datapkts = s->st_bytes = s->st_frames = 0;
    s->st_dropped = s->st_pkterr = s->st_reaperr = s->st_submiterr = 0;
    s->st_errno = 0;
    s->hex_first[0] = 0; s->hex_sof[0] = 0;
    s->curlen = 0; s->collecting = 0;
    s->cur = malloc(MAX_FRAME);
    pthread_mutex_lock(&s->mtx);
    s->ready = malloc(MAX_FRAME); s->readylen = 0; s->readynew = 0;
    pthread_mutex_unlock(&s->mtx);
    if (!s->cur || !s->ready) { free_all(s); return -ENOMEM; }

    int ok = 0, lasterr = 0;
    for (int i = 0; i < NURBS; i++) {
        s->urbs[i] = calloc(1, sizeof(struct usbdevfs_urb) +
                               NPKTS * sizeof(struct usbdevfs_iso_packet_desc));
        if (!s->urbs[i]) { free_all(s); return -ENOMEM; }
        s->urbs[i]->buffer = malloc((size_t) NPKTS * (size_t) psize);
        if (!s->urbs[i]->buffer) { free_all(s); return -ENOMEM; }
    }
    for (int i = 0; i < NURBS; i++) {
        int r = submit(s, i);
        if (r == 0) ok++; else lasterr = r;
    }
    if (ok == 0) { free_all(s); return lasterr ? lasterr : -EIO; }
    if (lasterr == -ENOSPC) {
        /* only part of the queue fitted (not enough USB bandwidth):
           give up cleanly so Java can try a smaller stream setting */
        for (int i = 0; i < NURBS; i++)
            if (s->inflight[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
        for (int tries = 0; tries < 300 && any_inflight(s); tries++) {
            struct usbdevfs_urb *u = NULL;
            if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &u) == 0 && u)
                s->inflight[(int) (intptr_t) u->usercontext] = 0;
            else
                usleep(1000);
        }
        free_all(s);
        return lasterr;
    }
    s->running = 1;
    if (pthread_create(&s->thread, NULL, loop, s) != 0) {
        s->running = 0;
        for (int i = 0; i < NURBS; i++)
            if (s->inflight[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
        free_all(s);
        return -EAGAIN;
    }
    return 0;
}

static void stream_stop(stream_t *s) {
    if (!s->urbs[0]) return;
    s->running = 0;
    pthread_join(s->thread, NULL);
    free_all(s);
}

/* ------------------------------ JNI glue ------------------------------ */

JNIEXPORT jint JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStart(JNIEnv *env, jclass cls, jint slot,
                                                    jint fd, jint ep, jint psize) {
    (void) env; (void) cls;
    stream_t *s = slot_of(slot);
    return s ? stream_start(s, fd, ep, psize) : -EINVAL;
}

JNIEXPORT void JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStop(JNIEnv *env, jclass cls, jint slot) {
    (void) env; (void) cls;
    stream_t *s = slot_of(slot);
    if (s) stream_stop(s);
}

JNIEXPORT jboolean JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeIsRunning(JNIEnv *env, jclass cls, jint slot) {
    (void) env; (void) cls;
    stream_t *s = slot_of(slot);
    return s && s->running ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeGetFrame(JNIEnv *env, jclass cls, jint slot) {
    (void) cls;
    stream_t *s = slot_of(slot);
    if (!s) return NULL;
    jbyteArray out = NULL;
    pthread_mutex_lock(&s->mtx);
    if (s->readynew && s->ready && s->readylen > 0) {
        out = (*env)->NewByteArray(env, s->readylen);
        if (out)
            (*env)->SetByteArrayRegion(env, out, 0, s->readylen, (const jbyte *) s->ready);
        s->readynew = 0;
    }
    pthread_mutex_unlock(&s->mtx);
    return out;
}

JNIEXPORT jlongArray JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeStats(JNIEnv *env, jclass cls, jint slot) {
    (void) cls;
    stream_t *s = slot_of(slot);
    jlong v[10] = {0};
    if (s) {
        v[0] = s->st_urbs; v[1] = s->st_pkts; v[2] = s->st_datapkts; v[3] = s->st_bytes;
        v[4] = s->st_frames; v[5] = s->st_dropped; v[6] = s->st_pkterr; v[7] = s->st_reaperr;
        v[8] = s->st_submiterr; v[9] = s->st_errno;
    }
    jlongArray out = (*env)->NewLongArray(env, 10);
    if (out) (*env)->SetLongArrayRegion(env, out, 0, 10, v);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_latintrout_eyetoycam_IsoStream_nativeDebug(JNIEnv *env, jclass cls, jint slot) {
    (void) cls;
    stream_t *s = slot_of(slot);
    char buf[400];
    snprintf(buf, sizeof buf, "first packet: %s\nfirst frame-start packet: %s",
             s && s->hex_first[0] ? s->hex_first : "(none yet)",
             s && s->hex_sof[0] ? s->hex_sof : "(none yet)");
    return (*env)->NewStringUTF(env, buf);
}
