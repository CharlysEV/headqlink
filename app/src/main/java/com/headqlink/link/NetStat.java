package com.headqlink.link;

import android.os.ParcelFileDescriptor;

import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;

/** Estado del socket TCP con el coche (cola del kernel y TCP_INFO), vía la librería nativa hqlnet. */
final class NetStat implements Closeable {
    private static boolean loaded;

    static {
        try {
            System.loadLibrary("hqlnet");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            loaded = false;
        }
    }

    private static native int read(int fd, int[] out);

    private final ParcelFileDescriptor pfd;
    final int[] v = new int[12];

    private NetStat(ParcelFileDescriptor pfd) {
        this.pfd = pfd;
    }

    /** null si no se puede (librería ausente o socket sin descriptor). */
    static NetStat open(Socket s) {
        if (!loaded) return null;
        try {
            return new NetStat(ParcelFileDescriptor.fromSocket(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Rellena v: cola_bytes, rtt_us, rttvar_us, sin_confirmar, retrans_total, cwnd, perdidos,
     *  caudal_kbps, ocupado_ms, limitado_receptor_ms, limitado_buffer_ms, ventana_coche_bytes. */
    boolean sample() {
        return read(pfd.getFd(), v) == 0;
    }

    @Override
    public void close() throws IOException {
        pfd.close();
    }
}
