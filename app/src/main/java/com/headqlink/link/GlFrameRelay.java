package com.headqlink.link;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * "Siempre el último frame": el decodificador de Android Auto pinta en
 * una SurfaceTexture que solo guarda el frame más reciente; este hilo lo copia (recortado) a la
 * Surface de nuestro encoder solo cuando el enlace con el coche tiene sitio. Lo que no da tiempo a
 * enviar no se codifica nunca, así que tras un corte de radio llega la imagen actual, no una cola
 * de frames viejos.
 */
final class GlFrameRelay {
    /** Enlace con el coche: ¿cabe un frame más ahora? Y aviso de que se ha dibujado uno. */
    interface Gate {
        boolean ready();

        void submitted();
    }

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    /** Con un frame nuevo esperando y el enlace ocupado, se vuelve a mirar cada esto. */
    private static final long RETRY_MS = 4;

    private static final String VS =
            "attribute vec4 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "uniform mat4 uTexM;\n" +
            "uniform vec4 uCrop;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = aPos;\n" +
            "  vec2 t = uCrop.xy + aTex * uCrop.zw;\n" +
            "  vTex = (uTexM * vec4(t, 0.0, 1.0)).xy;\n" +
            "}\n";
    /**
     * Con uRadius > 0 la capa se recorta en un rectángulo de esquinas redondeadas (uRect, en píxeles de
     * la salida) y fuera de él se pinta uBg (el gris del panel), con un borde suavizado de 1 px.
     */
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "uniform vec4 uRect;\n" +
            "uniform float uRadius;\n" +
            "uniform vec4 uBg;\n" +
            "void main() {\n" +
            "  vec4 c = texture2D(sTex, vTex);\n" +
            "  if (uRadius > 0.0) {\n" +
            "    vec2 hs = uRect.zw * 0.5;\n" +
            "    vec2 q = abs(gl_FragCoord.xy - uRect.xy - hs) - (hs - vec2(uRadius));\n" +
            "    float d = length(max(q, 0.0)) - uRadius;\n" +
            "    c = mix(uBg, c, clamp(0.5 - d, 0.0, 1.0));\n" +
            "  }\n" +
            "  gl_FragColor = c;\n" +
            "}\n";
    /** Radio de las esquinas de la zona de AA en el modo ampliado (píxeles de la pantalla del coche). */
    private static final float AA_CORNER_RADIUS = 28f;

    private final HandlerThread thread = new HandlerThread("c10-gl",
            LowLatency.enabled ? android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY : android.os.Process.THREAD_PRIORITY_DEFAULT);
    private Handler h;
    private final Surface output;
    private final int outW;
    private final int outH;
    /** Tamaño del frame de AA: se conoce de verdad al llegar el primero (ver setSourceSize). */
    private volatile int srcW;
    private volatile int srcH;
    /**
     * Ritmo de dibujo (hilo GL): como mucho los fps del vídeo de media; con cadencia fija (perfil Medio, 45 fps con AA
     * a 60), un frame por tic, siempre el último.
     */
    private final FramePacer pacer;
    /** Ancho de la zona de AA en la salida (cambia si el panel se oculta, ver setAaRegion). */
    private volatile int aaOutW;
    /** Posición x de la zona de AA en la salida (el panel propio va a su izquierda). */
    private volatile int aaOutX;
    /** Capa con nuestra interfaz (modo ampliado): se dibuja de fondo y AA encima, en su zona. */
    private final boolean withOverlay;
    /** Tamaño de la capa de nuestra interfaz (la pantalla del coche); se escala a la salida. */
    private int ovW;
    private int ovH;
    /** Gris del panel, para el exterior de las esquinas redondeadas de AA. */
    private volatile int bgColor = 0xFF1E1E1E;
    /** Ya ha llegado algún frame de AA (hasta entonces se ve la animación de carga de la capa). */
    private volatile boolean aaHasFrame;
    private Runnable onFirstAaFrame;
    /** La capa es solo la animación de carga: se deja de dibujar con el primer frame de AA. */
    private boolean splashOnly;
    private volatile boolean aaVisible = true;
    private int ovTex;
    private SurfaceTexture ovSt;
    private Surface ovInput;
    private final float[] ovM = new float[16];
    /**
     * Segunda entrada de la capa, para la interfaz dibujada por HeadQLink con la pantalla del móvil apagada
     * (PhoneOffRenderer): Android deja de componer la pantalla virtual y la interfaz llega por aquí. Superficie propia
     * (nadie más la usa), así que no hay que quitársela a la pantalla virtual.
     */
    private int ov2Tex;
    private SurfaceTexture ov2St;
    private Surface ov2Input;
    private final float[] ov2M = new float[16];
    private volatile boolean manualOverlay;
    private volatile Gate gate;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglCtx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int tex;
    private SurfaceTexture st;
    private Surface input;
    private final float[] texM = new float[16];
    private FloatBuffer quad;

    private boolean hasNew;
    private long frameNs;
    /**
     * Desde cuándo la puerta (el enlace) retiene un frame pendiente, o 0. Al dibujar va a la traza como gate_hold_ms
     * (docs §23: para ver si un P-frame grande sigue a un rato con la puerta cerrada).
     */
    private long gateHoldSinceNs;
    private boolean scheduled;
    private boolean released;
    /** Último dibujo (ns), para el flujo constante del modo extendido. */
    private long lastDrawNs;

    /**
     * Modo extendido: como mucho a esto se manda la imagen aunque no cambie nada (fps). Con una pantalla nuestra
     * delante (Coche, Web, TV…) solo se dibujaba al cambiar algo; el codificador repite el último frame 10 veces (1 s)
     * y luego se calla, y el C10 por USB (que pide 60 fps) se quedaba con la imagen congelada y tardaba en enseñar cada
     * toque (en el viaje del 2026-10-08, 6,8 s sin un solo frame tocando la pantalla). En modo Auto, AA ya dibuja
     * siempre a 30.
     */
    static final int KEEPALIVE_MAX_FPS = 30;

    /** Periodo del flujo constante (ns) para unos fps máximos (0: sin límite → KEEPALIVE_MAX_FPS). */
    static long keepAlivePeriodNs(int fps) {
        int f = fps <= 0 ? KEEPALIVE_MAX_FPS : Math.min(fps, KEEPALIVE_MAX_FPS);
        return 1_000_000_000L / f;
    }

    private final Runnable keepAlive = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            long period = keepAlivePeriodNs(pacer.fps());
            long now = System.nanoTime();
            // Con AA a la vista sus frames ya marcan el ritmo: solo se rellenan huecos de verdad (tres periodos), para no
            // meter un frame repetido justo antes del siguiente de AA (saldría a saltos).
            long gap = ownScreenOnTop() || !aaHasFrame ? period : 3 * period;
            if (!hasNew && now - lastDrawNs >= gap) {
                frameNs = now;
                hasNew = true;
                statKeep++;
                tryDraw();
            }
            // La siguiente comprobación, justo cuando toque (comprobando cada medio periodo salía uno de cada periodo y
            // medio: 21 fps en vez de 30).
            long due = lastDrawNs + gap - System.nanoTime();
            h.postDelayed(this, Math.max(2, Math.min(gap, due) / 1_000_000 + 1));
        }
    };

    // Estadística por segundo (hilo GL).
    private int statDecoded;
    private int statDrawn;
    /** Frames de nuestra interfaz recibidos (capa propia). */
    private int statOverlay;
    /** Dibujos sin nada nuevo (flujo constante del modo extendido). */
    private int statKeep;
    private long statMaxWaitMs;
    private long statMaxHoldMs;
    private int statDenyInterval;
    private int statDenyGate;
    private long statStart = SystemClock.elapsedRealtime();

    /**
     * output: Surface del encoder (outW x outH). src: tamaño del frame de AA; se toma la franja
     * superior izquierda de outW x outH (AA pone su interfaz arriba, ver announceTopAlignedMargins).
     */
    GlFrameRelay(Surface output, int outW, int outH, int srcW, int srcH, int maxFps) {
        this(output, outW, outH, srcW, srcH, maxFps, 0, outW, false);
    }

    /** aaOutX/aaOutW: zona de AA en la salida; withOverlay: crear la capa de nuestra interfaz (overlayInput). */
    GlFrameRelay(Surface output, int outW, int outH, int srcW, int srcH, int maxFps, int aaOutX, int aaOutW, boolean withOverlay) {
        this.output = output;
        this.aaOutX = aaOutX;
        this.aaOutW = aaOutW;
        this.withOverlay = withOverlay;
        this.outW = outW;
        this.outH = outH;
        this.ovW = outW;
        this.ovH = outH;
        this.srcW = srcW;
        this.srcH = srcH;
        // Sin rejilla, medio periodo de tolerancia: AA entrega a intervalos irregulares y con el 90 % del periodo
        // el frame que llegaba pronto esperaba y lo pisaba el siguiente (5-20 % descartados a 60 fps).
        this.pacer = new FramePacer(maxFps);
    }

    /**
     * Cadencia fija a maxFps (llamar antes de start): se dibuja en una rejilla regular con el frame
     * más reciente de AA, en vez de al llegar cada uno. Con AA a 60 y salida a 45 los intervalos
     * quedan iguales (45 constantes es mejor que 50 y pico irregulares).
     */
    void setFixedRate(boolean on) {
        pacer.setFps(pacer.fps(), on);
    }

    /** fps máximos en marcha (adaptación térmica) y si van en rejilla; se aplica en el hilo GL sin parar nada. */
    void setMaxFps(int fps, boolean fixed) {
        Handler hh = h;
        if (hh == null) {
            pacer.setFps(fps, fixed);
            return;
        }
        hh.post(() -> {
            pacer.setFps(fps, fixed);
            L.i("GL relay: ritmo " + fps + " fps" + (pacer.fixed() ? " en rejilla" : ""));
        });
    }

    /** El tamaño negociado con AA puede cambiar tras crear el relay (al arrancar en frío vale 800x480). */
    void setSourceSize(int w, int h) {
        if (w <= 0 || h <= 0 || (w == srcW && h == srcH)) return;
        L.i("GL relay: frame de AA " + srcW + "x" + srcH + " -> " + w + "x" + h);
        srcW = w;
        srcH = h;
    }

    /** Surface de la capa de nuestra interfaz, o null sin capa. */
    Surface overlayInput() {
        return ovInput;
    }

    /** Surface de la capa dibujada por HeadQLink con la pantalla del móvil apagada (PhoneOffRenderer), o null sin capa. */
    Surface manualOverlayInput() {
        return ov2Input;
    }

    /** true: la capa sale de manualOverlayInput (pantalla del móvil apagada); false: de la pantalla virtual. */
    void setManualOverlay(boolean on) {
        Handler hh = h;
        if (hh == null) return;
        hh.post(() -> {
            if (manualOverlay == on) return;
            manualOverlay = on;
            L.i("GL relay: capa de la interfaz " + (on ? "dibujada por HeadQLink (pantalla del móvil apagada)" : "de la pantalla virtual"));
            if (!hasNew) frameNs = System.nanoTime();
            hasNew = true;
            tryDraw();
        });
    }

    /** Tamaño de la capa de nuestra interfaz (llamar antes de start; por defecto, el de la salida). */
    void setOverlaySize(int w, int h) {
        ovW = w;
        ovH = h;
    }

    /** La capa es solo la pantalla de carga (modo AA sin panel); llamar antes de start. */
    void setSplashOnly(boolean on) {
        splashOnly = on;
    }

    /** Se llama (hilo GL) con el primer frame de AA ya decodificado. */
    void setOnFirstAaFrame(Runnable r) {
        onFirstAaFrame = r;
    }

    /** Zona de AA en la salida (el panel se oculta o se muestra sobre la marcha). */
    void setAaRegion(int x, int w) {
        aaOutX = x;
        aaOutW = w;
        if (h != null) h.post(() -> {
            // La espera se mide desde aquí (si no, desde el último frame de AA, quizá de hace segundos).
            if (!hasNew) frameNs = System.nanoTime();
            hasNew = true;
            tryDraw();
        });
    }

    /** Color del exterior de las esquinas redondeadas (el gris del panel). */
    void setBackgroundColor(int argb) {
        bgColor = argb;
        if (h != null) h.post(() -> {
            // La espera se mide desde aquí (si no, desde el último frame de AA, quizá de hace segundos).
            if (!hasNew) frameNs = System.nanoTime();
            hasNew = true;
            tryDraw();
        });
    }

    /** AA visible en su zona (false: nuestra interfaz ocupa toda la pantalla). */
    void setAaVisible(boolean v) {
        aaVisible = v;
        if (h != null) h.post(() -> {
            // La espera se mide desde aquí (si no, desde el último frame de AA, quizá de hace segundos).
            if (!hasNew) frameNs = System.nanoTime();
            hasNew = true;
            tryDraw();
        });
    }

    void setGate(Gate g) {
        gate = g;
    }

    /**
     * Vuelve a dibujar el último frame aunque no haya llegado otro (qdauto §4.10): al enganchar una sesión nueva, el
     * IDR pedido al encoder sale con el siguiente frame dibujado, aunque AA no cambie la imagen.
     */
    void redraw() {
        Handler hh = h;
        if (hh == null) return;
        hh.post(() -> {
            if (!hasNew) frameNs = System.nanoTime();
            hasNew = true;
            tryDraw();
        });
    }

    /** Arranca el hilo GL y devuelve la Surface donde debe pintar el decodificador de AA. */
    Surface start() {
        thread.start();
        h = new Handler(thread.getLooper());
        Surface[] out = new Surface[1];
        RuntimeException[] err = new RuntimeException[1];
        Object lock = new Object();
        synchronized (lock) {
            h.post(() -> {
                try {
                    setupGl();
                    out[0] = input;
                } catch (RuntimeException e) {
                    err[0] = e;
                }
                synchronized (lock) {
                    lock.notifyAll();
                }
            });
            try {
                lock.wait(3000);
            } catch (InterruptedException ignored) {
            }
        }
        if (err[0] != null) throw err[0];
        if (out[0] == null) throw new IllegalStateException("GL sin arrancar");
        L.i("GL relay: AA " + srcW + "x" + srcH + " -> encoder " + outW + "x" + outH + " (franja superior)");
        return out[0];
    }

    void release() {
        if (h == null) return;
        h.post(() -> {
            released = true;
            if (st != null) st.release();
            if (input != null) input.release();
            if (ovSt != null) ovSt.release();
            if (ovInput != null) ovInput.release();
            if (ov2St != null) ov2St.release();
            if (ov2Input != null) ov2Input.release();
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, eglSurface);
                if (eglCtx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, eglCtx);
                EGL14.eglTerminate(dpy);
            }
        });
        thread.quitSafely();
    }

    // ------------------------------------------------------------------ hilo GL

    private void setupGl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new IllegalStateException("eglInitialize");
        int[] attrs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0) || n[0] == 0) {
            throw new IllegalStateException("eglChooseConfig");
        }
        eglCtx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        eglSurface = EGL14.eglCreateWindowSurface(dpy, cfgs[0], output, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface");
        EGL14.eglMakeCurrent(dpy, eglSurface, eglSurface, eglCtx);

        program = link(compile(GLES20.GL_VERTEX_SHADER, VS), compile(GLES20.GL_FRAGMENT_SHADER, FS));
        tex = newOesTexture();
        android.opengl.Matrix.setIdentityM(texM, 0);

        // x, y, u, v (tira de dos triángulos a pantalla completa).
        float[] q = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);

        st = new SurfaceTexture(tex);
        st.setDefaultBufferSize(Math.max(srcW, 1920), Math.max(srcH, 1080));
        st.setOnFrameAvailableListener(s -> onFrameAvailable(), h);
        input = new Surface(st);

        if (withOverlay) {
            ovTex = newOesTexture();
            android.opengl.Matrix.setIdentityM(ovM, 0);
            ovSt = new SurfaceTexture(ovTex);
            ovSt.setDefaultBufferSize(ovW, ovH);
            ovSt.setOnFrameAvailableListener(s -> onOverlayFrame(), h);
            ovInput = new Surface(ovSt);
            ov2Tex = newOesTexture();
            android.opengl.Matrix.setIdentityM(ov2M, 0);
            ov2St = new SurfaceTexture(ov2Tex);
            ov2St.setDefaultBufferSize(ovW, ovH);
            ov2St.setOnFrameAvailableListener(s -> onManualOverlayFrame(), h);
            ov2Input = new Surface(ov2St);
            // Modo extendido: flujo constante hacia el coche (ver KEEPALIVE_MAX_FPS).
            h.postDelayed(keepAlive, 100);
        }
    }

    private static int newOesTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    /** Nuevo frame de nuestra interfaz dibujado por HeadQLink (pantalla del móvil apagada). */
    private void onManualOverlayFrame() {
        if (released) return;
        ov2St.updateTexImage();
        if (!manualOverlay) return;
        statOverlay++;
        if (splashOnly && aaHasFrame) return;
        ov2St.getTransformMatrix(ov2M);
        if (!hasNew) frameNs = System.nanoTime();
        hasNew = true;
        tryDraw();
    }

    /** Nuevo frame de nuestra interfaz: también hay que recomponer. */
    private void onOverlayFrame() {
        if (released) return;
        if (manualOverlay) {
            // La pantalla virtual vuelve a componer antes de que se deje de dibujar a mano: se consume sin usarlo.
            ovSt.updateTexImage();
            ovSt.getTransformMatrix(ovM);
            return;
        }
        statOverlay++;
        ovSt.updateTexImage();
        // La animación de carga ya no se ve: sus últimos frames no provocan dibujos.
        if (splashOnly && aaHasFrame) return;
        ovSt.getTransformMatrix(ovM);
        if (!hasNew) frameNs = System.nanoTime();
        hasNew = true;
        tryDraw();
    }

    private void onFrameAvailable() {
        if (released) return;
        // Siempre el más reciente: si había uno sin dibujar, se pierde aquí (es lo que queremos).
        st.updateTexImage();
        st.getTransformMatrix(texM);
        if (!aaHasFrame) {
            aaHasFrame = true;
            L.i("GL relay: primer frame de AA");
            if (onFirstAaFrame != null) onFirstAaFrame.run();
        }
        statDecoded++;
        // Con una pantalla nuestra delante, AA sigue mandando frames pero no se ven: no deben
        // provocar dibujos (se mezclarían su ritmo y el de nuestra interfaz y saldrían frames
        // a intervalos irregulares).
        if (ownScreenOnTop()) return;
        hasNew = true;
        frameNs = System.nanoTime();
        tryDraw();
    }

    /** Nuestra interfaz tapa AA (fotos, juegos, TV…). */
    private boolean ownScreenOnTop() {
        return withOverlay && !splashOnly && !aaVisible;
    }

    /**
     * Rejilla fija: en el perfil Medio siempre; en los demás, mientras se ve una pantalla nuestra (sus
     * animaciones salen a intervalos iguales en vez de al ritmo del móvil, que puede ser 120 Hz).
     */
    private boolean paced() {
        return pacer.periodNs() > 0 && (pacer.fixed() || ownScreenOnTop());
    }

    private final Runnable retry = () -> {
        scheduled = false;
        tryDraw();
    };

    private void tryDraw() {
        if (released || !hasNew) return;
        long now = System.nanoTime();
        boolean paced = paced();
        long wait = pacer.waitNs(now, paced);
        Gate g = gate;
        boolean early = wait > 0;
        boolean closed = !early && g != null && !g.ready();
        if (early || closed) {
            if (early) {
                statDenyInterval++;
            } else {
                statDenyGate++;
                if (gateHoldSinceNs == 0) gateHoldSinceNs = now;
            }
            if (!scheduled) {
                scheduled = true;
                // Con cadencia fija se espera justo al tic (redondeando hacia arriba, sin el mínimo de 4 ms).
                h.postDelayed(retry, early && paced ? Math.max(1, (wait + 999_999) / 1_000_000)
                        : Math.max(RETRY_MS, wait / 1_000_000));
            }
            return;
        }
        draw(now);
        lastDrawNs = now;
        if (g != null) g.submitted();
        long waitedMs = (now - frameNs) / 1_000_000;
        statMaxWaitMs = Math.max(statMaxWaitMs, waitedMs);
        PerfTrace.event("relay_draw", waitedMs);
        if (gateHoldSinceNs != 0) {
            long heldMs = (now - gateHoldSinceNs) / 1_000_000;
            gateHoldSinceNs = 0;
            statMaxHoldMs = Math.max(statMaxHoldMs, heldMs);
            PerfTrace.event("gate_hold_ms", heldMs);
        }
        hasNew = false;
        // Siguiente dibujo: en rejilla, el tic siguiente (si nos hemos retrasado más de un periodo, por el enlace
        // cerrado o sin frames, la rejilla vuelve a empezar aquí); si no, el ritmo medio.
        pacer.onDraw(now, paced);
        statDrawn++;
        maybeLogStats();
    }

    private void draw(long now) {
        GLES20.glUseProgram(program);
        int layer = manualOverlay ? ov2Tex : ovTex;
        float[] layerM = manualOverlay ? ov2M : ovM;
        if (withOverlay && splashOnly) {
            // Animación de carga hasta que AA da su primer frame; después, solo AA.
            if (aaHasFrame) drawAa(0, outW, 0f);
            else drawLayer(layer, layerM, 0, 0, outW, outH, 0f, 0f, 1f, 1f, 0f);
        } else if (withOverlay) {
            // Fondo: nuestra interfaz a pantalla completa (escalada a la salida); encima, AA en su
            // zona si está visible (y ya ha dado imagen: antes se ve la animación de carga), con
            // las esquinas redondeadas sobre el gris del panel.
            drawLayer(layer, layerM, 0, 0, outW, outH, 0f, 0f, 1f, 1f, 0f);
            if (aaVisible && aaHasFrame) drawAa(aaOutX, aaOutW, AA_CORNER_RADIUS * outW / (float) ovW);
        } else {
            drawAa(0, outW, 0f);
        }
        EGLExt.eglPresentationTimeANDROID(dpy, eglSurface, now);
        long t0 = System.nanoTime();
        EGL14.eglSwapBuffers(dpy, eglSurface);
        // Dibujar + entregar al encoder (el swap se bloquea si el encoder no ha soltado un búfer).
        PerfTrace.event("swap_ms", (System.nanoTime() - now) / 1_000_000);
        if (t0 - now > 0) PerfTrace.event("gl_ms", (t0 - now) / 1_000_000);
    }

    /** AA: franja superior izquierda de regionW x outH de su frame, en la zona (x, regionW) de la salida. */
    private void drawAa(int x, int regionW, float radius) {
        float sw = Math.min(1f, regionW / (float) srcW);
        float sh = Math.min(1f, outH / (float) srcH);
        drawLayer(tex, texM, x, 0, regionW, outH, 0f, 1f - sh, sw, sh, radius);
    }

    /**
     * Dibuja una textura externa en el rectángulo (x, y, w, h) de la salida con el recorte dado;
     * con radius > 0, con esquinas redondeadas sobre bgColor.
     */
    private void drawLayer(int texture, float[] m, int x, int y, int w, int hh, float cu, float cv, float cw, float ch, float radius) {
        GLES20.glViewport(x, y, w, hh);
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uRect"), x, y, w, hh);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRadius"), radius);
        int bg = bgColor;
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uBg"), ((bg >> 16) & 0xff) / 255f,
                ((bg >> 8) & 0xff) / 255f, (bg & 0xff) / 255f, 1f);
        int aPos = GLES20.glGetAttribLocation(program, "aPos");
        int aTex = GLES20.glGetAttribLocation(program, "aTex");
        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexM"), 1, false, m, 0);
        // En coordenadas de textura (antes de la matriz) v=1 es arriba.
        GLES20.glUniform4f(GLES20.glGetUniformLocation(program, "uCrop"), cu, cv, cw, ch);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private void maybeLogStats() {
        long now = SystemClock.elapsedRealtime();
        if (now - statStart < 5000) return;
        L.i(String.format(java.util.Locale.US, "GL relay: AA %d frames, interfaz %d, enviados %d (%d sin cambios), descartados %d por ir atrasados, espera máx %d ms · esperas por ritmo %d, por enlace %d · ritmo %d fps%s%s",
                statDecoded, statOverlay, statDrawn, statKeep, Math.max(0, statDecoded - (statDrawn - statKeep)), statMaxWaitMs, statDenyInterval, statDenyGate,
                pacer.fps(), paced() ? " en rejilla" : "", statMaxHoldMs > 0 ? " (puerta cerrada máx " + statMaxHoldMs + " ms)" : ""));
        statDenyInterval = 0;
        statDenyGate = 0;
        statStart = now;
        statDecoded = 0;
        statDrawn = 0;
        statOverlay = 0;
        statKeep = 0;
        statMaxWaitMs = 0;
        statMaxHoldMs = 0;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int link(int vs, int fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("program: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }
}
