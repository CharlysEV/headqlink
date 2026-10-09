package com.headqlink.link;

import android.content.Context;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * La interfaz del modo extendido con la pantalla del móvil apagada.
 *
 * Nuestra interfaz es una Presentation en una pantalla virtual privada (CarUi), y esa pantalla va en el grupo de la del
 * móvil. Con la pantalla del móvil en OFF (apagada y sin cargar; cargando, Samsung la deja en reposo para el AOD de la
 * carga), Android sigue dibujando la interfaz (gfxinfo: los fotogramas suben) pero deja de componer la pantalla virtual:
 * al coche no le llega nada, el panel se queda congelado y los toques no se ven. Prueba del 2026-10-09 con el coche
 * simulado y el móvil a batería: 0 fotogramas de la interfaz durante todo el apagado, mientras Android Auto, en su
 * propia pantalla de sistema, seguía a 30 fps. Una app no puede poner su pantalla virtual en otro grupo
 * (VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP pide ADD_TRUSTED_DISPLAY).
 *
 * Qué se hace: mientras la pantalla del móvil está en OFF, HeadQLink dibuja él mismo el árbol de vistas de la
 * Presentation, con un HardwareRenderer propio, cada vez que la Presentation dibuja, en una segunda entrada del relay que
 * solo usa él (GlFrameRelay.manualOverlayInput), y el relay toma la capa de ahí. La pantalla virtual sigue igual (no se
 * le quita su superficie: hacerlo con Android aún conectado a ella tumbaba la app en el RenderThread, «getFrame() called
 * on a context with no surface!»). Al volver la pantalla, el relay vuelve a la pantalla virtual. Lo que Android compone
 * aparte (SurfaceView) no saldría: los reproductores usan TextureView.
 */
final class PhoneOffRenderer {
    /** Cada cuánto se mira el estado de la pantalla del móvil, por si el aviso de cambio no llega. */
    private static final long POLL_MS = 1000;

    private final Context ctx;
    private final Handler main;
    private final Surface out;
    private final Consumer<Boolean> useManual;
    private final int width;
    private final int height;
    private final Supplier<View> root;

    private DisplayManager dm;
    private HardwareRenderer renderer;
    private RenderNode node;
    private View drawing;
    private boolean active;
    private boolean posted;
    private boolean stopped;
    private int frames;

    PhoneOffRenderer(Context ctx, Handler main, Surface out, Consumer<Boolean> useManual, int width, int height,
            Supplier<View> root) {
        this.ctx = ctx;
        this.main = main;
        this.out = out;
        this.useManual = useManual;
        this.width = width;
        this.height = height;
        this.root = root;
    }

    /** ¿Deja Android de componer las pantallas del grupo del móvil con la pantalla en este estado? (puro) */
    static boolean phoneOff(int state) {
        return state == Display.STATE_OFF || state == Display.STATE_DOZE_SUSPEND;
    }

    private final DisplayManager.DisplayListener listener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            if (id == Display.DEFAULT_DISPLAY) update();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (stopped) return;
            update();
            main.postDelayed(this, POLL_MS);
        }
    };

    private final ViewTreeObserver.OnDrawListener onDraw = this::scheduleDraw;

    /** La Presentation acaba de dibujar: se repite en la entrada del relay (una vez por vuelta del hilo). */
    private void scheduleDraw() {
        if (!active || posted) return;
        posted = true;
        main.post(this::drawPosted);
    }

    /** Hilo principal (el de la Presentation). */
    void start() {
        dm = ctx.getSystemService(DisplayManager.class);
        if (dm == null) return;
        dm.registerDisplayListener(listener, main);
        main.post(poll);
    }

    /** Hilo principal; antes de soltar la pantalla virtual. */
    void stop() {
        stopped = true;
        main.removeCallbacks(poll);
        if (dm != null) dm.unregisterDisplayListener(listener);
        leave();
    }

    private void update() {
        if (stopped || dm == null) return;
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        boolean off = d != null && phoneOff(d.getState());
        if (off && !active) {
            enter();
        } else if (!off && active) {
            leave();
        }
    }

    private void enter() {
        View r = root.get();
        if (r == null || out == null || !out.isValid()) return;
        try {
            node = new RenderNode("HeadQLink-ui");
            node.setPosition(0, 0, width, height);
            renderer = new HardwareRenderer();
            renderer.setContentRoot(node);
            renderer.setSurface(out);
            drawing = r;
            r.getViewTreeObserver().addOnDrawListener(onDraw);
            active = true;
            frames = 0;
            L.i("CarUi: pantalla del móvil apagada: dibujo yo la interfaz (Android no compone la pantalla virtual)");
            drawPosted();
            useManual.accept(true);
        } catch (RuntimeException e) {
            L.w("CarUi: no se pudo dibujar la interfaz con la pantalla del móvil apagada: " + e);
            leave();
        }
    }

    private void drawPosted() {
        posted = false;
        if (!active || !out.isValid()) return;
        View r = drawing;
        if (r == null) return;
        RecordingCanvas c = node.beginRecording(width, height);
        try {
            r.draw(c);
        } finally {
            node.endRecording();
        }
        renderer.createRenderRequest().setVsyncTime(System.nanoTime()).syncAndDraw();
        frames++;
    }

    private void leave() {
        boolean was = active;
        active = false;
        posted = false;
        View r = drawing;
        drawing = null;
        if (was) useManual.accept(false);
        if (r != null && r.getViewTreeObserver().isAlive()) r.getViewTreeObserver().removeOnDrawListener(onDraw);
        if (renderer != null) {
            renderer.destroy();
            renderer = null;
        }
        if (node != null) {
            node.discardDisplayList();
            node = null;
        }
        if (r != null) r.invalidate();
        if (was) L.i("CarUi: pantalla del móvil encendida: vuelve la composición de Android (" + frames + " fotogramas dibujados por HeadQLink)");
    }
}
