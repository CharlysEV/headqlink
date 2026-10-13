package com.headqlink.link;

import android.content.Context;

import java.util.EnumSet;

/**
 * Diagnóstico del cable con lo que se ve en el registro (puro, lo prueban los tests): qué le pasa al cable, para decirlo
 * en el móvil en vez de dejarlo solo en el log. Con el T03 (2026-10-13) se vio que el coche corta la corriente del USB
 * unos 30 s en mitad del viaje y la app vuelve sola; y que nada más enchufar a veces reinicia el puerto a los 2-3 s.
 * Cada aviso sale una vez por viaje (reset al arrancar el servicio).
 */
final class UsbDiagnosis {
    enum Kind { NONE, POWER_CUT, SILENT, EARLY_CUTS }

    /** Un corte de lectura (EIO/ENODEV) y perder la corriente, con menos de esto entre medias: el coche cortó el USB. */
    static final long POWER_WINDOW_MS = 5_000;
    /** Tras el corte, si el coche vuelve antes de esto, se avisa de que fue él (si no, lo desenchufó el conductor). */
    static final long RETURN_WINDOW_MS = 120_000;
    /** Sesión con CAR_INFO que muere antes de esto: corte nada más conectar. */
    static final long EARLY_MS = 5_000;
    /** Una sesión de al menos esto borra los cortes tempranos acumulados. */
    static final long LONG_MS = 60_000;
    static final int SILENT_AFTER = 3;
    static final int EARLY_AFTER = 3;

    private long lastGoneMs = Long.MIN_VALUE / 2;
    private long lastPowerLostMs = Long.MIN_VALUE / 2;
    /** Hora del corte de corriente del coche confirmado (EIO + corriente fuera), a la espera de que vuelva. */
    private long pendingCutMs = Long.MIN_VALUE / 2;
    private int earlyCuts;
    private final EnumSet<Kind> shown = EnumSet.noneOf(Kind.class);
    /** Segundos que duró el último corte de corriente (para el texto). */
    long lastCutSeconds;

    /** Fin de una sesión por cable. gone: el accesorio desapareció (EIO/ENODEV). */
    Kind onSessionEnd(long nowMs, boolean gone, boolean gotCarInfo, long livedMs, int consecutiveFailures) {
        if (gone) {
            lastGoneMs = nowMs;
            if (nowMs - lastPowerLostMs <= POWER_WINDOW_MS) pendingCutMs = nowMs;
        }
        if (gotCarInfo && livedMs >= LONG_MS) earlyCuts = 0;
        if (gone && gotCarInfo && livedMs < EARLY_MS) {
            earlyCuts++;
            if (earlyCuts >= EARLY_AFTER) return once(Kind.EARLY_CUTS);
        }
        if (!gotCarInfo && consecutiveFailures >= SILENT_AFTER) return once(Kind.SILENT);
        return Kind.NONE;
    }

    /** El cable dejó de alimentar el móvil. */
    Kind onPowerLost(long nowMs) {
        lastPowerLostMs = nowMs;
        if (nowMs - lastGoneMs <= POWER_WINDOW_MS) pendingCutMs = nowMs;
        return Kind.NONE;
    }

    /** El coche vuelve a hablar (CAR_INFO): si venía de un corte de corriente reciente, fue el coche. */
    Kind onCarInfo(long nowMs) {
        if (pendingCutMs > Long.MIN_VALUE / 4 && nowMs - pendingCutMs <= RETURN_WINDOW_MS) {
            lastCutSeconds = Math.max(1, (nowMs - pendingCutMs) / 1000);
            pendingCutMs = Long.MIN_VALUE / 2;
            return once(Kind.POWER_CUT);
        }
        pendingCutMs = Long.MIN_VALUE / 2;
        return Kind.NONE;
    }

    private Kind once(Kind k) {
        return shown.add(k) ? k : Kind.NONE;
    }

    // ------------------------------------------------------------------ el de la app (con aviso en el móvil)

    private static final UsbDiagnosis CURRENT = new UsbDiagnosis();

    /** Al arrancar el servicio: avisos otra vez disponibles. */
    static synchronized void reset() {
        CURRENT.shown.clear();
        CURRENT.earlyCuts = 0;
        CURRENT.pendingCutMs = Long.MIN_VALUE / 2;
    }

    static synchronized void sessionEnd(Context c, boolean gone, boolean gotCarInfo, long livedMs, int failures) {
        notice(c, CURRENT.onSessionEnd(System.currentTimeMillis(), gone, gotCarInfo, livedMs, failures));
    }

    static synchronized void powerLost(Context c) {
        notice(c, CURRENT.onPowerLost(System.currentTimeMillis()));
    }

    static synchronized void carInfo(Context c) {
        notice(c, CURRENT.onCarInfo(System.currentTimeMillis()));
    }

    private static void notice(Context c, Kind k) {
        if (k == Kind.NONE) return;
        String title;
        String text;
        switch (k) {
            case POWER_CUT:
                title = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_cut_title);
                text = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_cut_text, CURRENT.lastCutSeconds);
                break;
            case SILENT:
                title = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_silent_title);
                text = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_silent_text);
                break;
            default:
                title = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_early_title);
                text = Str.get(com.andrerinas.openheadunit.R.string.hql_usb_early_text);
        }
        L.i("USB: diagnóstico " + k + ": " + text);
        UsbBusyNotice.showDiagnosis(c, title, text);
    }
}
