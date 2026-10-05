package com.headqlink.link;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Inyecta los toques del coche como gestos en el VirtualDisplay (servicio de accesibilidad).
 * Un trazo continuo por dedo: down → continueStroke(move…) → up.
 * Cada tramo se envía al terminar el anterior; los MOVE intermedios se agrupan.
 */
public class TouchService extends AccessibilityService {
    public static volatile TouchService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GestureDescription.StrokeDescription stroke;
    private float lastX;
    private float lastY;
    private long lastT;
    private boolean busy;
    private int displayId;
    // siguiente evento pendiente (los MOVE se sobrescriben entre sí)
    private int pendAction;
    private float pendX;
    private float pendY;
    private boolean pendUp;

    /** Al desbloquear: el arranque del servidor de AA pedido con el móvil bloqueado o, si no, su apagado pendiente. */
    private final android.content.BroadcastReceiver userPresent = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, android.content.Intent i) {
            main.postDelayed(() -> AaServerStarter.onUnlock(TouchService.this), 800);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        L.init(this);
        registerReceiver(userPresent, new android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT));
        L.i("TouchService conectado");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        main.removeCallbacks(coverWatchdog);
        try {
            unregisterReceiver(userPresent);
        } catch (IllegalArgumentException ignored) {
        }
        return super.onUnbind(intent);
    }

    // ---------------------------------------------------------------- capa que tapa la automatización

    /** La capa nunca se queda puesta: a los 15 s del último uso se quita sola (con aviso si era un apagado). */
    static final long COVER_MAX_MS = 15_000;

    private android.view.View cover;
    private int coverRefs;
    /** La capa tapa un apagado del servidor («Cerrando Auto…»): si vence, el servidor puede seguir encendido. */
    private boolean coverStopping;
    private final Runnable coverWatchdog = () -> {
        if (cover == null) return;
        boolean stopping = coverStopping;
        removeCover();
        coverRefs = 0;
        AaServerStarter.onCoverTimeout(this, stopping, COVER_MAX_MS);
    };

    private void removeCover() {
        main.removeCallbacks(coverWatchdog);
        if (cover == null) return;
        try {
            getSystemService(android.view.WindowManager.class).removeView(cover);
        } catch (RuntimeException ignored) {
        }
        cover = null;
        coverStopping = false;
    }

    /**
     * Muestra encima de todo una pantalla "Conectando con Android Auto…" mientras se pulsa el menú de
     * AA por debajo (las acciones de accesibilidad actúan sobre los nodos, no con toques, así que la
     * capa no las bloquea). Con contador: varias acciones encadenadas comparten la misma capa.
     * stopping: tapa el apagado del servidor. Cada uso vuelve a contar los COVER_MAX_MS de la capa.
     */
    void acquireCover(String text, boolean stopping) {
        main.post(() -> {
            coverRefs++;
            if (stopping) coverStopping = true;
            main.removeCallbacks(coverWatchdog);
            main.postDelayed(coverWatchdog, COVER_MAX_MS);
            if (cover != null) return;
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(text);
            tv.setTextSize(20);
            tv.setTextColor(0xFFFFFFFF);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setBackgroundColor(0xFF101418);
            android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    android.graphics.PixelFormat.OPAQUE);
            lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            try {
                getSystemService(android.view.WindowManager.class).addView(tv, lp);
                cover = tv;
                coverStopping = stopping;
            } catch (RuntimeException e) {
                L.e("no se pudo mostrar la capa", e);
                main.removeCallbacks(coverWatchdog);
            }
        });
    }

    /** Retira la capa tras [delayMs] (para tapar también las animaciones de vuelta). */
    void releaseCover(long delayMs) {
        main.postDelayed(() -> {
            if (coverRefs > 0) coverRefs--;
            if (coverRefs == 0) removeCover();
        }, delayMs);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Notificación de Android Auto: si es la del servidor de head unit, se guarda su botón
        // «Detener» para apagarlo sin abrir sus ajustes (también con el móvil bloqueado).
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                && event.getPackageName() != null && AaServerStarter.AA_PKG.contentEquals(event.getPackageName())
                && event.getParcelableData() instanceof android.app.Notification) {
            AaServerStarter.noteAaNotification((android.app.Notification) event.getParcelableData());
        }
    }

    @Override
    public void onInterrupt() {
    }

    /** action: 1 down, 2 up, 3 move (coordenadas en píxeles del display). */
    void inject(int display, int action, float x, float y) {
        main.post(() -> {
            displayId = display;
            if (action == 2) pendUp = true;
            if (pendAction == 0 || action != 3 || pendAction == 3) {
                pendAction = action;
                pendX = x;
                pendY = y;
            }
            pump();
        });
    }

    private void pump() {
        if (busy || pendAction == 0) return;
        int action = pendAction;
        float x = pendX;
        float y = pendY;
        boolean up = pendUp && action != 1;
        pendAction = 0;
        if (up) pendUp = false;
        long now = SystemClock.uptimeMillis();
        Path p = new Path();
        if (action == 1 || stroke == null) {
            p.moveTo(x, y);
            stroke = new GestureDescription.StrokeDescription(p, 0, 1, !up);
        } else {
            p.moveTo(lastX, lastY);
            p.lineTo(x, y);
            long dur = Math.max(1, Math.min(100, now - lastT));
            stroke = stroke.continueStroke(p, 0, dur, !up);
        }
        lastX = x;
        lastY = y;
        lastT = now;
        GestureDescription g = new GestureDescription.Builder().addStroke(stroke).setDisplayId(displayId).build();
        if (up) stroke = null;
        busy = true;
        boolean ok = dispatchGesture(g, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gd) {
                busy = false;
                pump();
            }

            @Override
            public void onCancelled(GestureDescription gd) {
                busy = false;
                stroke = null;
                pump();
            }
        }, main);
        if (!ok) {
            busy = false;
            stroke = null;
            L.w("dispatchGesture rechazado en display " + displayId);
        }
    }
}
