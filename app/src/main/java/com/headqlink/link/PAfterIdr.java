package com.headqlink.link;

import java.util.Locale;

/**
 * El primer P-frame tras cada IDR (docs §23). En el C10 (viajes del 2026-10-07 y la prueba de radio floja en casa) el
 * 86 % de los P-frames que siguen a un IDR pesaban más que el propio IDR (mediana 2,3 veces: IDR de 60 KB y P-frame de
 * 168 KB 33 ms después, con la imagen casi igual): el c2.qti.avc.encoder deja el IDR en el QP-I mínimo con que se
 * configuró y el siguiente P-frame, que podía bajar hasta QP 1, «afinaba» la imagen entera. El arreglo es el suelo de
 * QP de los P-frames al configurar (VideoEncoder.Params.qpPMin); esta clase solo lo vigila para el log y la traza.
 *
 * Puro (lo prueban los tests) y thread-safe: IDR y P-frames llegan del hilo enc-drain; el resumen, de hql-video.
 */
final class PAfterIdr {
    /** Una línea de log por P-frame más grande que su IDR, como mucho cada esto (las demás se cuentan). */
    static final long LOG_EVERY_MS = 10_000;

    /** El primer P-frame tras un IDR: su tamaño, el del IDR y la línea de log (o null). */
    static final class Seen {
        final int bytes;
        final int idrBytes;
        final String line;

        Seen(int bytes, int idrBytes, String line) {
            this.bytes = bytes;
            this.idrBytes = idrBytes;
            this.line = line;
        }

        /** Pesa más que el IDR al que sigue. */
        boolean biggerThanIdr() {
            return bytes > idrBytes;
        }
    }

    private int idrBytes;
    private boolean waiting;
    private long lastLogMs = Long.MIN_VALUE / 4;
    private int quiet;

    // Sesión: P-frames tras un IDR, cuántos más grandes que su IDR, la mayor proporción y el mayor tamaño.
    private int count;
    private int bigger;
    private double maxRatio;
    private int maxBytes;

    /** Ha salido un IDR de bytes: el siguiente P-frame es «el de tras el IDR». */
    synchronized void onIdr(int bytes) {
        if (bytes <= 0) return;
        idrBytes = bytes;
        waiting = true;
    }

    /** Un P-frame: si es el primero tras un IDR, lo que se sabe de él; si no, null. */
    synchronized Seen onP(int bytes, long nowMs) {
        if (!waiting) return null;
        waiting = false;
        count++;
        if (bytes > maxBytes) maxBytes = bytes;
        double ratio = bytes / (double) idrBytes;
        if (ratio > maxRatio) maxRatio = ratio;
        String line = null;
        if (bytes > idrBytes) {
            bigger++;
            if (nowMs - lastLogMs >= LOG_EVERY_MS) {
                line = String.format(Locale.US, "P-frame tras IDR: %d KB, %.1f veces el IDR (%d KB)%s", IdrSizeController.kb(bytes),
                        ratio, IdrSizeController.kb(idrBytes), quiet > 0 ? " · +" + quiet + " más desde la línea anterior" : "");
                lastLogMs = nowMs;
                quiet = 0;
            } else {
                quiet++;
            }
        }
        return new Seen(bytes, idrBytes, line);
    }

    /** Sesión nueva con el coche: estadísticas a cero (el IDR pendiente, si lo hay, sigue valiendo). */
    synchronized void beginSession() {
        count = 0;
        bigger = 0;
        maxRatio = 0;
        maxBytes = 0;
        quiet = 0;
        lastLogMs = Long.MIN_VALUE / 4;
    }

    synchronized int count() {
        return count;
    }

    synchronized int bigger() {
        return bigger;
    }

    synchronized double maxRatio() {
        return maxRatio;
    }

    /** «P-frames tras IDR 13 · más grandes que su IDR 0 · hasta 0.4 veces el IDR · el mayor 31 KB», o null sin ninguno. */
    synchronized String summary() {
        if (count == 0) return null;
        return String.format(Locale.US, "P-frames tras IDR %d · más grandes que su IDR %d · hasta %.1f veces el IDR · el mayor %d KB",
                count, bigger, maxRatio, IdrSizeController.kb(maxBytes));
    }
}
