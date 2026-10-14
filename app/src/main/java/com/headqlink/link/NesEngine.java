package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import com.grapeshot.halfnes.NES;
import com.grapeshot.halfnes.audio.AudioOutInterface;
import com.grapeshot.halfnes.mappers.Mapper;
import com.grapeshot.halfnes.ui.GUIInterface;
import com.grapeshot.halfnes.ui.PuppetController;
import com.grapeshot.halfnes.video.NesColors;

/**
 * Emulador NES de reserva (núcleo halfNES, módulo :nescore; el principal es NestopiaEngine) dentro de HeadQLink: corre en su hilo a 60 fps, dibuja cada fotograma en
 * un Bitmap de 256x224 (sin las 8 líneas de arriba y abajo, como halfNES) y saca el sonido por un AudioTrack. Los mandos
 * son PuppetController (puertos 1 y 2): NesInput los mueve con las teclas del mando Bluetooth o los botones táctiles.
 */
final class NesEngine implements GUIInterface, GameCore {
    static final int W = 256;
    static final int H = 224;
    private static final int CLIP = 8;

    private final NES nes;
    private final PuppetController pad1 = new PuppetController();
    private final PuppetController pad2 = new PuppetController();
    private final RetroPad pad = new RetroPad(pad1);
    private final RetroPad padTwo = new RetroPad(pad2);
    /** Tres búferes: el que se escribe nunca es el que la pantalla puede estar dibujando. */
    private final Bitmap[] frames = {Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)};
    private final int[] argb = new int[W * H];
    /** El fotograma impar anterior, para mezclarlo con el par cuando la sesión va a 30 fps. */
    private final int[] prev = new int[W * H];
    private final int[] lastEven = new int[W * H];
    private int loggedRate;
    private int frameIdx;
    /** Fotogramas emulados: la pantalla del coche va a 30 Hz, así que solo se entrega uno de cada dos (los pares). */
    private long emulated;
    private long shown;
    private long statsAtNs;
    private long statsEmulated;
    private long statsShown;
    private static volatile AudioTrack lastTrack;
    /** La salida de audio de este juego: se cierra al pararlo (si no, quedaba abierta hasta que la recogía el sistema). */
    private volatile TrackAudio audio;
    private Thread thread;
    private volatile Listener listener;
    private volatile String lastError;

    NesEngine(Context ctx) {
        NES.audioFactory = (n, rate, tv) -> {
            TrackAudio t = new TrackAudio(rate);
            audio = t;
            return t;
        };
        nes = new NES(this);
        nes.setControllers(pad1, pad2);
    }

    @Override
    public void setListener(Listener l) {
        listener = l;
    }

    @Override
    public RetroPad pad() {
        return pad;
    }

    @Override
    public RetroPad pad2() {
        return padTwo;
    }

    /** Carga la ROM (ruta real; la copia de la carpeta elegida). Devuelve el error, o null si arranca. */
    @Override
    public String load(String path) {
        lastError = null;
        nes.loadROM(path);
        if (lastError != null) return lastError;
        return nes.runEmulation ? null : "no arranca";
    }

    @Override
    public void start() {
        if (thread != null) return;
        thread = new Thread(() -> {
            // Como el audio: que no lo desplacen los hilos de dibujo (los saltos de imagen venían de ahí y del redibujo a 60).
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            nes.run();
        }, "hql-nes");
        thread.start();
    }

    void pause() {
        nes.pause();
    }

    void resume() {
        nes.resume();
    }

    @Override
    public void reset() {
        nes.reset();
    }

    /** Para el emulador y guarda la SRAM del juego (partidas de los juegos que la tienen). */
    @Override
    public void stop() {
        listener = null;
        nes.quit();
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        TrackAudio a = audio;
        audio = null;
        if (a != null) a.destroy();
    }

    @Override
    public String romInfo() {
        return nes.getrominfo();
    }

    // ------------------------------------------------------------------ GUIInterface (hilo del emulador)

    @Override
    public NES getNes() {
        return nes;
    }

    @Override
    public void setNES(NES nes) {
    }

    @Override
    public void setFrame(int[] frame, int[] bgcolor, boolean dotcrawl) {
        Listener l = listener;
        if (l == null) return;
        emulated++;
        logStats();
        int[][] col = NesColors.col;
        for (int i = 0; i < W * H; i++) {
            int p = frame[i + W * CLIP];
            argb[i] = col[(p & 0x1c0) >> 6][p & 0x3f];
        }
        // Sesión a 60 fps (fluidez 60): todos los fotogramas, con el parpadeo de verdad. A 30: de dos en dos (entregar «el
        // último» mezclaba saltos de uno y de tres), y cada par mezclado como la persistencia de una tele: lo que la NES
        // pinta en un fotograma sí y en otro no (Mario tocado, enemigos que parpadean) se ve a medias en vez de
        // desaparecer si cae siempre en los descartados.
        int rate = CarUi.frameRate();
        boolean full = rate >= 50;
        if (rate != loggedRate) {
            loggedRate = rate;
            L.i("NES: sesión a " + rate + " fps: " + (full ? "todos los fotogramas" : "de dos en dos, mezclando solo lo que parpadea (sin estela en lo que se mueve)"));
        }
        if (!full) {
            if ((emulated & 1) != 0) {
                System.arraycopy(argb, 0, prev, 0, W * H);
                return;
            }
            NativeCore.flickerBlend(prev, argb, lastEven, W * H);
        }
        shown++;
        Bitmap b = frames[frameIdx];
        frameIdx = (frameIdx + 1) % frames.length;
        b.setPixels(argb, 0, W, 0, 0, W, H);
        l.onFrame(b);
    }

    /** Cada 5 s, al log: fotogramas emulados y entregados por segundo y veces que el audio se quedó sin datos. */
    private void logStats() {
        long now = System.nanoTime();
        if (statsAtNs == 0) {
            statsAtNs = now;
            return;
        }
        long dt = now - statsAtNs;
        if (dt < 5_000_000_000L) return;
        double s = dt / 1e9;
        AudioTrack t = lastTrack;
        int under = -1;
        try {
            if (t != null) under = t.getUnderrunCount();
        } catch (IllegalStateException ignored) {
        }
        L.i(String.format(java.util.Locale.US, "NES: %.1f fps emulados · %.1f entregados a la pantalla · audio sin datos %d veces en total",
                (emulated - statsEmulated) / s, (shown - statsShown) / s, under));
        statsAtNs = now;
        statsEmulated = emulated;
        statsShown = shown;
    }

    @Override
    public void messageBox(String message) {
        lastError = message;
        L.w("NES: " + message);
        Listener l = listener;
        if (l != null) l.onMessage(message);
    }

    @Override
    public void run() {
    }

    @Override
    public void render() {
    }

    @Override
    public void loadROMs(String path) {
    }

    // ------------------------------------------------------------------ audio

    /** Salida de audio: 16 bits estéreo por AudioTrack, con un búfer de 4 fotogramas (como la de halfNES). */
    private static final class TrackAudio implements AudioOutInterface {
        private final AudioTrack track;
        private final short[] buf;
        private int n;
        private long written;
        private final float vol = 13107 / 16384f;

        TrackAudio(int samplerate) {
            int perFrame = (int) Math.ceil(samplerate * 2 / 50.0);
            buf = new short[perFrame + 64];
            int min = AudioTrack.getMinBufferSize(samplerate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, perFrame * 2 * 4);
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(samplerate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                    size, AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
            lastTrack = track;
        }

        @Override
        public void outputSample(int sample) {
            if (n + 2 > buf.length) return;
            sample = Math.round(sample * vol);
            if (sample < -32768) sample = -32768;
            if (sample > 32767) sample = 32767;
            buf[n++] = (short) sample;
            buf[n++] = (short) sample;
        }

        @Override
        public void flushFrame(boolean waitIfBufferFull) {
            if (n > 0) {
                int w = track.write(buf, 0, n, waitIfBufferFull ? AudioTrack.WRITE_BLOCKING : AudioTrack.WRITE_NON_BLOCKING);
                if (w > 0) written += w / 2;
            }
            n = 0;
        }

        @Override
        public void pause() {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }
        }

        @Override
        public void resume() {
            try {
                track.play();
            } catch (IllegalStateException ignored) {
            }
        }

        private boolean released;

        @Override
        public synchronized void destroy() {
            if (released) return;
            released = true;
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
        }

        @Override
        public boolean bufferHasLessThan(int samples) {
            long queued = written - (track.getPlaybackHeadPosition() & 0xffffffffL);
            return queued < samples;
        }
    }

    /** TV del juego cargado (para el ritmo de los fotogramas en pantalla). */
    Mapper.TVType tv() {
        return Mapper.TVType.NTSC;
    }
}
