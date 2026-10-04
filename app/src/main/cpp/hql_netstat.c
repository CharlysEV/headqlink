// headqlink: estado del socket TCP con el coche, para la traza de rendimiento.
// Lee los bytes pendientes en la cola de envío del kernel (SIOCOUTQ) y TCP_INFO.
#include <jni.h>
#include <linux/sockios.h>
#include <linux/tcp.h>
#include <netinet/in.h>
#include <sys/ioctl.h>
#include <sys/socket.h>

// out[0]=cola de envío (bytes), [1]=rtt (us), [2]=rttvar (us), [3]=sin confirmar (segmentos),
// [4]=retransmisiones totales, [5]=ventana de congestión (segmentos), [6]=perdidos,
// [7]=caudal entregado (kbit/s), [8]=tiempo ocupado (ms), [9]=limitado por el receptor (ms),
// [10]=limitado por nuestro buffer (ms), [11]=ventana anunciada por el coche (bytes). Devuelve 0 si ok.
JNIEXPORT jint JNICALL
Java_com_headqlink_link_NetStat_read(JNIEnv *env, jclass clazz, jint fd, jintArray out) {
    (void) clazz;
    jint v[12] = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
    int outq = 0;
    if (ioctl(fd, SIOCOUTQ, &outq) == 0) v[0] = outq;
    struct tcp_info ti;
    socklen_t len = sizeof(ti);
    if (getsockopt(fd, IPPROTO_TCP, TCP_INFO, &ti, &len) == 0) {
        v[1] = (jint) ti.tcpi_rtt;
        v[2] = (jint) ti.tcpi_rttvar;
        v[3] = (jint) ti.tcpi_unacked;
        v[4] = (jint) ti.tcpi_total_retrans;
        v[5] = (jint) ti.tcpi_snd_cwnd;
        v[6] = (jint) ti.tcpi_lost;
        v[7] = (jint) (ti.tcpi_delivery_rate * 8 / 1000);
        v[8] = (jint) (ti.tcpi_busy_time / 1000);
        v[9] = (jint) (ti.tcpi_rwnd_limited / 1000);
        v[10] = (jint) (ti.tcpi_sndbuf_limited / 1000);
        v[11] = (jint) ti.tcpi_snd_wnd;
    }
    (*env)->SetIntArrayRegion(env, out, 0, 12, v);
    return v[0] >= 0 ? 0 : -1;
}
