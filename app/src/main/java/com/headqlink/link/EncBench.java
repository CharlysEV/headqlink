package com.headqlink.link;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.SystemClock;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Banco de pruebas del encoder sin coche (acción ENC_BENCH): alimenta el encoder por su Surface con
 * GL, como GlFrameRelay, y mide lo mismo que enc_ms (de la hora de dibujo a la salida codificada)
 * para varias configuraciones. Dos ritmos por variante:
 * - seguidos: un frame cada 16,7 ms (60 fps), como en una sesión;
 * - sueltos: un frame cada 100 ms. Si aquí la latencia baja mucho, el encoder retiene cada frame
 *   hasta recibir el siguiente (latencia de canalización, no de cálculo).
 * El contenido cambia en cada frame (rectángulos de colores) para que no sea trivial de codificar.
 */
final class EncBench {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private EncBench() {
    }

    private static final class Variant {
        final String name;
        final int w;
        final int h;
        final boolean lowLatency;
        final boolean maxClocks;
        final boolean noIr;
        final boolean noRepeat;

        Variant(String name, int w, int h, boolean lowLatency, boolean maxClocks, boolean noIr, boolean noRepeat) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.lowLatency = lowLatency;
            this.maxClocks = maxClocks;
            this.noIr = noIr;
            this.noRepeat = noRepeat;
        }
    }

    static void run() {
        Variant[] vs = {
                new Variant("1080 normal", 1920, 882, false, false, false, false),
                new Variant("1080 baja latencia", 1920, 882, true, false, false, false),
                new Variant("1080 baja lat + relojes máx", 1920, 882, true, true, false, false),
                new Variant("1080 baja lat + sin refresco intra", 1920, 882, true, false, true, false),
                new Variant("1080 baja lat + sin repetir", 1920, 882, true, false, false, true),
                new Variant("720 normal", 1280, 588, false, false, false, false),
                new Variant("720 baja latencia", 1280, 588, true, false, false, false),
                new Variant("720 baja lat + relojes máx", 1280, 588, true, true, false, false),
        };
        L.i("ENC_BENCH: " + vs.length + " variantes · 180 frames seguidos (60 fps) + 30 sueltos (cada 100 ms)");
        for (Variant v : vs) {
            try {
                bench(v);
            } catch (Exception e) {
                L.e("ENC_BENCH " + v.name, e);
            }
            SystemClock.sleep(500);
        }
        L.i("ENC_BENCH: fin");
    }

    private static void bench(Variant v) throws Exception {
        VideoEncoder.Params p = new VideoEncoder.Params();
        p.width = v.w;
        p.height = v.h;
        p.fps = 60;
        p.bitrate = v.w <= 1280 ? 5_000_000 : 8_000_000;
        p.iFrameIntervalSec = v.noIr ? 10 : 30;
        p.intraRefreshFrames = v.noIr ? 0 : 60;
        p.lowLatency = v.lowLatency;
        p.maxClocks = v.maxClocks;
        p.noRepeat = v.noRepeat;
        List<Long> lat = new ArrayList<>();
        long[] pending = new long[1];
        boolean[] collect = {false};
        VideoEncoder enc = new VideoEncoder(p, new VideoEncoder.Sink() {
            @Override
            public void onCodecConfig(byte[] spsPps) {
            }

            @Override
            public void onFrame(byte[] data, int len, boolean keyFrame, long ptsUs) {
                long d = System.nanoTime() / 1000 - ptsUs;
                synchronized (lat) {
                    if (collect[0]) lat.add(d);
                }
            }
        });
        Surface in = enc.start();
        EGLDisplay dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(dpy, ver, 0, ver, 1);
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, attrs, 0, cfgs, 0, 1, n, 0);
        EGLContext ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        EGLSurface surf = EGL14.eglCreateWindowSurface(dpy, cfgs[0], in, new int[]{EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
        Random rnd = new Random(1);
        try {
            // Calentamiento (el primer IDR y los relojes) sin medir.
            for (int i = 0; i < 30; i++) frame(dpy, surf, v, rnd, i, 16_667_000L);
            String steady = measure(dpy, surf, v, rnd, lat, collect, 180, 16_667_000L);
            String sparse = measure(dpy, surf, v, rnd, lat, collect, 30, 100_000_000L);
            L.i(String.format(Locale.US, "ENC_BENCH %-34s seguidos %s · sueltos %s", v.name, steady, sparse));
        } finally {
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(dpy, surf);
            EGL14.eglDestroyContext(dpy, ctx);
            enc.stop();
        }
    }

    private static String measure(EGLDisplay dpy, EGLSurface surf, Variant v, Random rnd, List<Long> lat,
                                  boolean[] collect, int frames, long periodNs) {
        synchronized (lat) {
            lat.clear();
            collect[0] = true;
        }
        long swapMax = 0;
        for (int i = 0; i < frames; i++) swapMax = Math.max(swapMax, frame(dpy, surf, v, rnd, i, periodNs));
        SystemClock.sleep(300);
        List<Long> copy;
        synchronized (lat) {
            collect[0] = false;
            copy = new ArrayList<>(lat);
        }
        if (copy.isEmpty()) return "sin salida";
        java.util.Collections.sort(copy);
        return String.format(Locale.US, "p50 %4.1f p90 %4.1f p99 %4.1f ms (n=%d, swap máx %d ms)",
                copy.get(copy.size() / 2) / 1000.0, copy.get(Math.min(copy.size() - 1, copy.size() * 9 / 10)) / 1000.0,
                copy.get(Math.min(copy.size() - 1, copy.size() * 99 / 100)) / 1000.0, copy.size(), swapMax);
    }

    /** Dibuja un frame distinto, lo entrega al encoder y espera al siguiente tic; devuelve ms en el swap. */
    private static long frame(EGLDisplay dpy, EGLSurface surf, Variant v, Random rnd, int i, long periodNs) {
        long t0 = System.nanoTime();
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
        GLES20.glClearColor(0.12f, 0.12f, 0.13f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        for (int k = 0; k < 24; k++) {
            int rw = 40 + rnd.nextInt(v.w / 4);
            int rh = 30 + rnd.nextInt(v.h / 4);
            GLES20.glScissor(rnd.nextInt(v.w - rw), rnd.nextInt(v.h - rh), rw, rh);
            GLES20.glClearColor(rnd.nextFloat(), rnd.nextFloat(), rnd.nextFloat(), 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }
        long now = System.nanoTime();
        EGLExt.eglPresentationTimeANDROID(dpy, surf, now);
        EGL14.eglSwapBuffers(dpy, surf);
        long swapMs = (System.nanoTime() - now) / 1_000_000;
        long left = periodNs - (System.nanoTime() - t0);
        if (left > 0) sleepNs(left);
        return swapMs;
    }

    private static void sleepNs(long ns) {
        try {
            Thread.sleep(ns / 1_000_000, (int) (ns % 1_000_000));
        } catch (InterruptedException ignored) {
        }
    }
}
