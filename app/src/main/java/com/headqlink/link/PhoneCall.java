package com.headqlink.link;

import android.content.Context;
import android.media.AudioManager;

import com.andrerinas.openheadunit.decoder.audio.CallState;
import com.andrerinas.openheadunit.decoder.audio.MicRecorder;

/** ¿Hay una llamada en curso? Por el modo de audio (sin permiso de telefonía), descontando nuestro propio micrófono. */
final class PhoneCall {
    private PhoneCall() {
    }

    static boolean active(Context ctx) {
        try {
            AudioManager am = ctx.getSystemService(AudioManager.class);
            if (am == null) return false;
            int mode = am.getMode();
            return CallState.INSTANCE.isCallActive(mode, MicRecorder.Companion.getHoldsCommunicationMode())
                    || CallState.INSTANCE.isCallStarting(mode);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
