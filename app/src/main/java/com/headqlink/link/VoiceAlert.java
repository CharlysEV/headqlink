package com.headqlink.link;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

/**
 * Avisos por voz (el plan de carga cambia durante el viaje), con la voz del sistema en el idioma de la app y como los
 * avisos del navegador: uso «guía de navegación» y la música baja un momento mientras habla (sale por donde sale el
 * audio del móvil: el Bluetooth del coche).
 */
final class VoiceAlert {
    private static TextToSpeech tts;
    private static boolean ready;
    private static String pending;
    private static AudioFocusRequest focus;
    private static int seq;

    private VoiceAlert() {
    }

    /** Dice el texto (el anterior, si sigue hablando, se corta). Se puede llamar desde cualquier hilo. */
    static synchronized void say(Context ctx, String text) {
        if (text == null || text.trim().isEmpty()) return;
        Context app = ctx.getApplicationContext();
        if (tts == null) {
            pending = text;
            ready = false;
            tts = new TextToSpeech(app, status -> onInit(app, status));
            return;
        }
        if (!ready) {
            pending = text;
            return;
        }
        speak(app, text);
    }

    private static synchronized void onInit(Context app, int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) {
            L.w("voz: el sistema no tiene voz (" + status + ")");
            shutdown();
            return;
        }
        int r = tts.setLanguage(Str.locale());
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            L.w("voz: sin voz para " + Str.locale() + "; con la del sistema");
        }
        if (Build.VERSION.SDK_INT >= 21) {
            tts.setAudioAttributes(attributes());
        }
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String id) {
            }

            @Override
            public void onDone(String id) {
                release(app);
            }

            @Override
            public void onError(String id) {
                release(app);
            }
        });
        ready = true;
        String p = pending;
        pending = null;
        if (p != null) speak(app, p);
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    private static void speak(Context app, String text) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            if (Build.VERSION.SDK_INT >= 26) {
                if (focus == null) {
                    focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes())
                            .build();
                }
                am.requestAudioFocus(focus);
            } else {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }
        }
        String id = "hql-" + (++seq);
        if (Build.VERSION.SDK_INT >= 21) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, new Bundle(), id);
        } else {
            java.util.HashMap<String, String> p = new java.util.HashMap<>();
            p.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, p);
        }
        L.i("voz: aviso dicho (" + text.length() + " caracteres)");
    }

    /** Terminó de hablar: la música vuelve a su volumen. */
    private static synchronized void release(Context app) {
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focus != null) am.abandonAudioFocusRequest(focus);
        } else {
            am.abandonAudioFocus(null);
        }
    }

    /** Al cerrar el modo ampliado. */
    static synchronized void shutdown() {
        if (tts != null) {
            try {
                tts.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        tts = null;
        ready = false;
        pending = null;
    }
}
