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
    private long minIntervalNs;
    private final long periodNs;
    /** Cadencia fija (perfil Medio, 45 fps con AA a 60): un frame por tic, siempre el último. */
    private boolean fixedRate;
    private long nextTickNs;
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
    private long lastDrawNs;
    private boolean scheduled;
    private boolean released;

    // Estadística por segundo (hilo GL).
    private int statDecoded;
    private int statDrawn;
    private long statMaxWaitMs;
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
        // Solo frena ráfagas: AA entrega a intervalos irregulares y con el 90 % del periodo el frame
        // que llegaba pronto esperaba y lo pisaba el siguiente (5-20 % descartados a 60 fps).
        this.periodNs = maxFps > 0 ? 1_000_000_000L / maxFps : 0;
        this.minIntervalNs = periodNs / 2;
    }

    /**
     * Cadencia fija a maxFps (llamar antes de start): se dibuja en una rejilla regular con el frame
     * más reciente de AA, en vez de al llegar cada uno. Con AA a 60 y salida a 45 los intervalos
     * quedan iguales (45 constantes es mejor que 50 y pico irregulares).
     */
    void setFixedRate(boolean on) {
        fixedRate = on && periodNs > 0;
        if (fixedRate) minIntervalNs = periodNs;
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
            hasNew = true;
            tryDraw();
        });
    }

    /** Color del exterior de las esquinas redondeadas (el gris del panel). */
    void setBackgroundColor(int argb) {
        bgColor = argb;
        if (h != null) h.post(() -> {
            hasNew = true;
            tryDraw();
        });
    }

    /** AA visible en su zona (false: nuestra interfaz ocupa toda la pantalla). */
    void setAaVisible(boolean v) {
        aaVisible = v;
        if (h != null) h.post(() -> {
            hasNew = true;
            tryDraw();
        });
    }

    void setGate(Gate g) {
        gate = g;
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

    /** Nuevo frame de nuestra interfaz: también hay que recomponer. */
    private void onOverlayFrame() {
        if (released) return;
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
        return periodNs > 0 && (fixedRate || ownScreenOnTop());
    }

    private final Runnable retry = () -> {
        scheduled = false;
        tryDraw();
    };

    private void tryDraw() {
        if (released || !hasNew) return;
        long now = System.nanoTime();
        boolean paced = paced();
        long wait = paced ? nextTickNs - now : lastDrawNs + minIntervalNs - now;
        Gate g = gate;
        boolean early = wait > 0;
        boolean closed = !early && g != null && !g.ready();
        if (early || closed) {
            if (early) statDenyInterval++;
            else statDenyGate++;
            if (!scheduled) {
                scheduled = true;
                // Con cadencia fija se espera justo al tic (redondeando hacia arriba, sin el mínimo de 4 ms).
                h.postDelayed(retry, early && paced ? Math.max(1, (wait + 999_999) / 1_000_000)
                        : Math.max(RETRY_MS, wait / 1_000_000));
            }
            return;
        }
        draw(now);
        if (g != null) g.submitted();
        long waitedMs = (now - frameNs) / 1_000_000;
        statMaxWaitMs = Math.max(statMaxWaitMs, waitedMs);
        PerfTrace.event("relay_draw", waitedMs);
        hasNew = false;
        lastDrawNs = now;
        if (paced) {
            // Siguiente tic de la rejilla; si nos hemos retrasado más de un periodo (enlace cerrado,
            // sin frames), la rejilla vuelve a empezar aquí.
            nextTickNs = now - nextTickNs > periodNs ? now + periodNs : nextTickNs + periodNs;
        }
        statDrawn++;
        maybeLogStats();
    }

    private void draw(long now) {
        GLES20.glUseProgram(program);
        if (withOverlay && splashOnly) {
            // Animación de carga hasta que AA da su primer frame; después, solo AA.
            if (aaHasFrame) drawAa(0, outW, 0f);
            else drawLayer(ovTex, ovM, 0, 0, outW, outH, 0f, 0f, 1f, 1f, 0f);
        } else if (withOverlay) {
            // Fondo: nuestra interfaz a pantalla completa (escalada a la salida); encima, AA en su
            // zona si está visible (y ya ha dado imagen: antes se ve la animación de carga), con
            // las esquinas redondeadas sobre el gris del panel.
            drawLayer(ovTex, ovM, 0, 0, outW, outH, 0f, 0f, 1f, 1f, 0f);
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
        L.i(String.format(java.util.Locale.US, "GL relay: AA %d frames, enviados %d (descartados %d por ir atrasados), espera máx %d ms · esperas por ritmo %d, por enlace %d",
                statDecoded, statDrawn, Math.max(0, statDecoded - statDrawn), statMaxWaitMs, statDenyInterval, statDenyGate));
        statDenyInterval = 0;
        statDenyGate = 0;
        statStart = now;
        statDecoded = 0;
        statDrawn = 0;
        statMaxWaitMs = 0;
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
