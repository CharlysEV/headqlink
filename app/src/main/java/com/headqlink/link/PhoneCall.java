package com.headqlink.link;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;

import com.andrerinas.openheadunit.decoder.audio.CallState;
import com.andrerinas.openheadunit.decoder.audio.MicRecorder;

/**
 * ¿Hay una llamada en curso? Por el modo de audio (sin permiso de telefonía), descontando nuestro propio micrófono.
 * Con el enlace en marcha (Android 12+) se apunta en el log cuándo suena, empieza y acaba cada llamada, sin números ni
 * nombres: el log de Android que se exporta solo guarda los últimos minutos, y los cortes «en una llamada» que cuentan
 * los usuarios no se podían situar en el viaje.
 */
final class PhoneCall {
    private PhoneCall() {
    }

    static boolean active(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return false;
            return isCall(am.getMode());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isCall(int mode) {
        return CallState.INSTANCE.isCallActive(mode, MicRecorder.Companion.getHoldsCommunicationMode())
                || CallState.INSTANCE.isCallStarting(mode);
    }

    /** Puro: la línea del log para un cambio de modo de audio (null si no tiene que ver con llamadas). */
    static String describe(int from, int to, long callMs) {
        if (from == to) return null;
        if (to == AudioManager.MODE_RINGTONE) return "llamada: suena";
        boolean was = from == AudioManager.MODE_IN_CALL || from == AudioManager.MODE_IN_COMMUNICATION;
        boolean is = to == AudioManager.MODE_IN_CALL || to == AudioManager.MODE_IN_COMMUNICATION;
        if (is && !was) return "llamada: en curso" + (to == AudioManager.MODE_IN_COMMUNICATION ? " (por internet)" : "");
        if (was && !is) return "llamada: acaba" + (callMs >= 0 ? " tras " + (callMs + 500) / 1000 + " s" : "");
        if (from == AudioManager.MODE_RINGTONE && to == AudioManager.MODE_NORMAL) return "llamada: deja de sonar sin cogerla";
        return null;
    }

    private static Object listener;
    private static int lastMode = AudioManager.MODE_NORMAL;
    private static long callSinceMs = -1;

    /** Desde que arranca el enlace (Android 12+): apunta las llamadas en el log. */
    static synchronized void watch(Context ctx) {
        if (listener != null || android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
            if (am == null) return;
            lastMode = am.getMode();
            AudioManager.OnModeChangedListener l = PhoneCall::onMode;
            am.addOnModeChangedListener(ctx.getApplicationContext().getMainExecutor(), l);
            listener = l;
        } catch (RuntimeException e) {
            L.w("llamadas: no se pueden vigilar: " + e);
        }
    }

    static synchronized void unwatch(Context ctx) {
        if (listener == null || android.os.Build.VERSION.SDK_INT < 31) return;
        try {
            AudioManager am = ctx.getApplicationContext().getSystemService(AudioManager.class);
            if (am != null) am.removeOnModeChangedListener((AudioManager.OnModeChangedListener) listener);
        } catch (RuntimeException ignored) {
        }
        listener = null;
        callSinceMs = -1;
    }

    private static synchronized void onMode(int mode) {
        // Nuestro micrófono (asistente) pone MODE_IN_COMMUNICATION: no es una llamada.
        if (mode == AudioManager.MODE_IN_COMMUNICATION && MicRecorder.Companion.getHoldsCommunicationMode()) return;
        long now = SystemClock.elapsedRealtime();
        boolean inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        String line = describe(lastMode, mode, callSinceMs >= 0 ? now - callSinceMs : -1);
        if (inCall && callSinceMs < 0) callSinceMs = now;
        if (!inCall && mode != AudioManager.MODE_RINGTONE) callSinceMs = -1;
        lastMode = mode;
        if (line != null) L.i(line + " · Android Auto " + (AaPassthroughSource.aaConnectedNow() ? "conectado" : "sin conectar"));
    }
}
