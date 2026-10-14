package com.headqlink.link;

import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Lo común de los núcleos nativos (Nestopia, ClownMDEmu): el hilo del emulador, un fotograma por vuelta, la imagen en
 * tres Bitmap por tamaño (el que se escribe nunca es el que se dibuja), la mezcla de dos en dos cuando la sesión va a 30
 * fps (lo que parpadea se ve a medias en vez de desaparecer), el sonido por un AudioTrack que marca el ritmo (la
 * escritura bloquea con unos fotogramas de adelanto; un reloj de reserva por si no hay sonido) y la partida guardada en
 * «rom.sav». Las estadísticas van al log cada 5 s.
 */
abstract class NativeCore implements GameCore {
    static final int RATE = 44100;
    /** Lo más grande que da una consola: 1024 x 512 (lo que puede dar PCSX ReARMed; la PlayStation, hasta 640 x 480). */
    private static final int MAX_PX = 1024 * 512;

    private final String name;
    private final int channels;
    private final RetroPad pad = new RetroPad();
    private final RetroPad pad2 = new RetroPad();
    private final int[] argb = new int[MAX_PX];
    private final int[] prev = new int[MAX_PX];
    /** El último fotograma par tal cual salió (sin mezclar), para saber qué parpadea. */
    private final int[] lastEven = new int[MAX_PX];
    private final short[] samples = new short[(RATE / 50 + 64) * 2];
    private final float vol;
    private Bitmap[] frames;
    private int framesW, framesH;
    private int prevW, prevH;
    private int evenW, evenH;
    private int frameIdx;
    private int loggedRate;
    private long emulated, shown, statsAtNs, statsEmulated, statsShown;
    private String savPath;
    private boolean loaded;
    private AudioTrack track;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean resetPending;
    /** Guardado o carga de estado pendiente (lo hace el hilo del emulador entre dos fotogramas). */
    private final Object stateLock = new Object();
    private String statePath;
    private boolean stateSave;
    private StateDone stateDone;
    private volatile String autoStatePath;
    /** Para el log: los primeros cambios del mando de cada partida (si llegan y con qué bits). */
    private int lastBits, loggedPad;
    private volatile Listener listener;

    NativeCore(String name, int channels, float vol) {
        this.name = name;
        this.channels = channels;
        this.vol = vol;
    }

    /** Carga la ROM en el núcleo (con la partida guardada, si la hay). null si arranca; si no, el error. */
    abstract String nativeLoad(byte[] rom, byte[] sav);

    /** Para los núcleos que leen la ROM y guardan la partida ellos mismos (bsnes): true y la carga por ruta. */
    boolean loadsByPath() {
        return false;
    }

    /** Carga por ruta (solo con loadsByPath). */
    String nativeLoadPath(String path) {
        return "sin carga por ruta";
    }

    /**
     * Un fotograma: imagen en argb, sonido en samples; devuelve las muestras escritas, o < 0 si no hay juego. padBits trae
     * los dos mandos (twoPads): el jugador 1 en los 16 bits de abajo y el 2 en los de arriba.
     */
    abstract int nativeFrame(int[] argb, short[] samples, int padBits);

    /** Tamaño del último fotograma: (ancho << 16) | alto. */
    abstract int nativeSize();

    abstract void nativeReset();

    /** Descarga el juego; devuelve la partida del cartucho para guardarla, o null. */
    abstract byte[] nativeUnload();

    /** Los botones del mando en el formato del núcleo. */
    abstract int padBits(int bits);

    /** Fotogramas por segundo de la consola con el juego cargado (60 NTSC, 50 PAL). */
    abstract int consoleFps();

    /** Estado completo del juego, o null si el núcleo no puede. */
    byte[] nativeSaveState() {
        return null;
    }

    /** Vuelve a un estado de nativeSaveState. */
    boolean nativeLoadState(byte[] state) {
        return false;
    }

    @Override
    public boolean supportsStates() {
        return true;
    }

    @Override
    public void saveState(String path, StateDone done) {
        requestState(path, true, done);
    }

    @Override
    public void loadState(String path, StateDone done) {
        requestState(path, false, done);
    }

    @Override
    public void setAutoState(String path) {
        autoStatePath = path;
    }

    private void requestState(String path, boolean save, StateDone done) {
        synchronized (stateLock) {
            if (stateDone != null) {
                done.done(false);
                return;
            }
            statePath = path;
            stateSave = save;
            stateDone = done;
        }
    }

    /** En el hilo del emulador, entre dos fotogramas: el guardado o la carga pendiente. */
    private void runStateRequest() {
        String path;
        boolean save;
        StateDone done;
        synchronized (stateLock) {
            if (stateDone == null) return;
            path = statePath;
            save = stateSave;
            done = stateDone;
            stateDone = null;
        }
        done.done(save ? writeState(path) : readState(path));
    }

    private boolean writeState(String path) {
        byte[] s;
        try {
            s = nativeSaveState();
        } catch (Throwable t) {
            L.w(name + ": no se pudo guardar el estado: " + t);
            return false;
        }
        if (s == null || s.length == 0) {
            L.w(name + ": el núcleo no pudo guardar el estado");
            return false;
        }
        File f = new File(path);
        File tmp = new File(path + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(s);
        } catch (Exception e) {
            L.w(name + ": no se pudo guardar el estado: " + e);
            return false;
        }
        if (!tmp.renameTo(f)) return false;
        L.i(name + ": estado guardado (" + s.length / 1024 + " KB)");
        return true;
    }

    private boolean readState(String path) {
        File f = new File(path);
        if (!f.isFile()) return false;
        try {
            boolean ok = nativeLoadState(read(f));
            L.i(name + ": estado cargado: " + (ok ? "sí" : "no (de otra versión o roto)"));
            return ok;
        } catch (Throwable t) {
            L.w(name + ": no se pudo cargar el estado: " + t);
            return false;
        }
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
        return pad2;
    }

    /**
     * Puro: los dos mandos (ya en el formato del núcleo, 16 bits como mucho: la PlayStation usa 14) en un solo int para
     * el puente JNI, que no cambia de firma: el jugador 1 abajo, el 2 arriba. Los núcleos leen cada mitad en su puerto.
     */
    static int twoPads(int p1, int p2) {
        return (p1 & 0xFFFF) | ((p2 & 0xFFFF) << 16);
    }

    @Override
    public synchronized String load(String path) {
        try {
            if (loadsByPath()) {
                String err = nativeLoadPath(path);
                loaded = err == null;
                return err;
            }
            byte[] rom = read(new File(path));
            savPath = path.replaceAll("\\.[^./]*$", "") + ".sav";
            File sav = new File(savPath);
            String err = nativeLoad(rom, sav.isFile() ? read(sav) : null);
            loaded = err == null;
            return err;
        } catch (Throwable t) {
            return String.valueOf(t);
        }
    }

    @Override
    public void start() {
        if (thread != null) return;
        running = true;
        thread = new Thread(this::loop, "hql-" + name);
        thread.start();
    }

    @Override
    public void reset() {
        resetPending = true;
    }

    @Override
    public void stop() {
        listener = null;
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500);
            } catch (InterruptedException ignored) {
            }
        }
        // Si el hilo no llegó a arrancar, se descarga aquí (la partida se guarda igualmente); si sigue vivo, lo hará él
        // al salir del bucle, nunca a la vez que un fotograma.
        if (t == null || !t.isAlive()) unload();
    }

    private void loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
        openAudio();
        long frameNs = 1_000_000_000L / Math.max(1, consoleFps());
        long next = System.nanoTime();
        while (running) {
            if (resetPending) {
                resetPending = false;
                nativeReset();
            }
            runStateRequest();
            int bits = pad.read();
            if (bits != lastBits && loggedPad < 6) {
                loggedPad++;
                L.i(name + ": mando → " + Integer.toBinaryString(bits) + " (al núcleo " + Integer.toBinaryString(padBits(bits)) + ")");
            }
            lastBits = bits;
            int n = nativeFrame(argb, samples, twoPads(padBits(bits), padBits(pad2.read())));
            if (n < 0) break;
            int size = nativeSize();
            deliver(size >>> 16, size & 0xFFFF);
            writeAudio(n);
            // El audio bloquea y marca el ritmo; el reloj solo frena si el audio no lo hace (salida pausada o sin sonido).
            next += frameNs;
            long wait = next - System.nanoTime();
            if (wait > 1_000_000L) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            } else if (wait < -100_000_000L) {
                next = System.nanoTime();
            }
        }
        closeAudio();
        runStateRequest();
        // Al salir, el estado se guarda solo: la próxima vez se puede seguir donde se dejó.
        String auto = autoStatePath;
        if (auto != null && loaded) writeState(auto);
        unload();
    }

    private synchronized void unload() {
        if (!loaded) return;
        loaded = false;
        byte[] sav = nativeUnload();
        if (sav != null && savPath != null) {
            try (FileOutputStream out = new FileOutputStream(savPath)) {
                out.write(sav);
                L.i(name + ": partida guardada (" + sav.length + " bytes)");
            } catch (Exception e) {
                L.w(name + ": no se pudo guardar la partida: " + e);
            }
        }
    }

    private void deliver(int w, int h) {
        Listener l = listener;
        if (l == null || w <= 0 || h <= 0 || w * h > MAX_PX) return;
        emulated++;
        logStats(w, h);
        int rate = CarUi.frameRate();
        boolean full = rate >= 50;
        if (rate != loggedRate) {
            loggedRate = rate;
            L.i(name + ": sesión a " + rate + " fps: " + (full ? "todos los fotogramas" : "de dos en dos, mezclando solo lo que parpadea (sin estela en lo que se mueve)"));
        }
        if (!full) {
            if ((emulated & 1) != 0) {
                System.arraycopy(argb, 0, prev, 0, w * h);
                prevW = w;
                prevH = h;
                return;
            }
            if (prevW == w && prevH == h && evenW == w && evenH == h) {
                flickerBlend(prev, argb, lastEven, w * h);
            } else {
                System.arraycopy(argb, 0, lastEven, 0, w * h);
            }
            evenW = w;
            evenH = h;
        }
        if (frames == null || framesW != w || framesH != h) {
            frames = new Bitmap[]{Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888),
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)};
            framesW = w;
            framesH = h;
            L.i(name + ": imagen de " + w + " x " + h);
        }
        shown++;
        Bitmap b = frames[frameIdx];
        frameIdx = (frameIdx + 1) % frames.length;
        b.setPixels(argb, 0, w, 0, 0, w, h);
        l.onFrame(b);
    }

    /**
     * Puro: a 30 fps se enseña uno de cada dos fotogramas (los pares). Lo que la consola pinta en un fotograma sí y en otro
     * no (Mario tocado, enemigos que parpadean) desaparecería si cae siempre en los impares, y mezclarlo todo deja estela
     * en lo que se mueve. Así que solo se mezcla al 50 % el píxel que es igual en este par y en el anterior pero distinto
     * en el impar de en medio: eso es un parpadeo. Lo que se mueve o hace scroll cambia también entre pares y sale limpio.
     * cur pasa a ser lo que se enseña; lastEven, el par tal cual (para la próxima vez).
     */
    static void flickerBlend(int[] odd, int[] cur, int[] lastEven, int n) {
        for (int i = 0; i < n; i++) {
            int c = cur[i];
            int o = odd[i];
            if (c == lastEven[i] && o != c) cur[i] = 0xFF000000 | (((o & 0xFEFEFE) >>> 1) + ((c & 0xFEFEFE) >>> 1));
            lastEven[i] = c;
        }
    }

    private void logStats(int w, int h) {
        long now = System.nanoTime();
        if (statsAtNs == 0) {
            statsAtNs = now;
            return;
        }
        long dt = now - statsAtNs;
        if (dt < 5_000_000_000L) return;
        double s = dt / 1e9;
        int under = -1;
        try {
            if (track != null) under = track.getUnderrunCount();
        } catch (IllegalStateException ignored) {
        }
        L.i(String.format(java.util.Locale.US, "%s: %.1f fps emulados · %.1f entregados a la pantalla · %dx%d · audio sin datos %d veces en total",
                name, (emulated - statsEmulated) / s, (shown - statsShown) / s, w, h, under));
        statsAtNs = now;
        statsEmulated = emulated;
        statsShown = shown;
    }

    // ------------------------------------------------------------------ audio

    private void openAudio() {
        try {
            int mask = channels == 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
            int perFrame = RATE / 60 * channels;
            int min = AudioTrack.getMinBufferSize(RATE, mask, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(mask).build(),
                    Math.max(min, perFrame * 2 * 4), AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE);
            track.play();
        } catch (Exception e) {
            L.w(name + ": sin sonido: " + e);
            track = null;
        }
    }

    private void writeAudio(int n) {
        AudioTrack t = track;
        if (t == null || n <= 0) return;
        if (vol != 1f) {
            for (int i = 0; i < n; i++) {
                int v = Math.round(samples[i] * vol);
                samples[i] = (short) Math.max(-32768, Math.min(32767, v));
            }
        }
        try {
            t.write(samples, 0, n, AudioTrack.WRITE_BLOCKING);
        } catch (IllegalStateException ignored) {
        }
    }

    private void closeAudio() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try {
            t.stop();
        } catch (IllegalStateException ignored) {
        }
        t.release();
    }

    // ------------------------------------------------------------------ utilidades

    static byte[] read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
