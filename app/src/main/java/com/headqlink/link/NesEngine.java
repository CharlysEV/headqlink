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
 * Emulador NES (núcleo halfNES, módulo :nescore) dentro de HeadQLink: corre en su hilo a 60 fps, dibuja cada fotograma en
 * un Bitmap de 256x224 (sin las 8 líneas de arriba y abajo, como halfNES) y saca el sonido por un AudioTrack. Los mandos
 * son PuppetController: NesInput los mueve con las teclas del mando Bluetooth o los botones táctiles.
 */
final class NesEngine implements GUIInterface {
    interface Listener {
        /** Fotograma nuevo (hilo del emulador). */
        void onFrame(Bitmap frame);

        void onMessage(String text);
    }

    static final int W = 256;
    static final int H = 224;
    private static final int CLIP = 8;

    private final NES nes;
    private final PuppetController pad1 = new PuppetController();
    private final PuppetController pad2 = new PuppetController();
    /** Tres búferes: el que se escribe nunca es el que la pantalla puede estar dibujando. */
    private final Bitmap[] frames = {Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)};
    private final int[] argb = new int[W * H];
    private int frameIdx;
    /** Fotogramas emulados: la pantalla del coche va a 30 Hz, así que solo se entrega uno de cada dos (los pares). */
    private long emulated;
    private long shown;
    private long statsAtNs;
    private long statsEmulated;
    private long statsShown;
    private static volatile AudioTrack lastTrack;
    private Thread thread;
    private volatile Listener listener;
    private volatile String lastError;

    NesEngine(Context ctx) {
        NES.audioFactory = (n, rate, tv) -> new TrackAudio(rate);
        nes = new NES(this);
        nes.setControllers(pad1, pad2);
    }

    void setListener(Listener l) {
        listener = l;
    }

    PuppetController pad1() {
        return pad1;
    }

    /** Carga la ROM (ruta real; la copia de la carpeta elegida). Devuelve el error, o null si arranca. */
    String load(String path) {
        lastError = null;
        nes.loadROM(path);
        if (lastError != null) return lastError;
        return nes.runEmulation ? null : "no arranca";
    }

    void start() {
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

    void reset() {
        nes.reset();
    }

    /** Para el emulador y guarda la SRAM del juego (partidas de los juegos que la tienen). */
    void stop() {
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
    }

    String romInfo() {
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
        // La NES da 60 fotogramas por segundo y la pantalla del coche toma 30: entregar «el último» mezclaba saltos de
        // uno y de tres fotogramas (tirones al desplazarse). Solo los pares: siempre de dos en dos.
        if ((emulated & 1) != 0) return;
        shown++;
        int[][] col = NesColors.col;
        for (int i = 0; i < W * H; i++) {
            int p = frame[i + W * CLIP];
            argb[i] = col[(p & 0x1c0) >> 6][p & 0x3f];
        }
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

        @Override
        public void destroy() {
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
