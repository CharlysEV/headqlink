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
        // Para avisar si una actualización la desactiva (UpdateReceiver).
        new Config(this).putBool(UpdateReceiver.ACC_WAS_ON, true);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        // Android la ha desconectado con la app viva (quitada en Ajustes o por el sistema): ¿sigue activa?
        AccessibilityGuard.checkLater(this, "desconectada con la app abierta");
        main.removeCallbacks(coverWatchdog);
        hideGameCover("accesibilidad parada");
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

    /** Teclas de un mando Bluetooth mientras hay un juego NES en el coche: van al emulador y no a la app de delante. */
    @Override
    protected boolean onKeyEvent(android.view.KeyEvent e) {
        if (NesInput.pad == null) return false;
        // El mando se ha conectado con el juego ya abierto (o la capa no se puso): con su primer botón.
        if (gameOn && gameCover == null && !gameCoverDismissed && fromGamepad(e.getDevice())) main.post(this::showGameCover);
        return NesInput.key(e);
    }

    // ------------------------------------------------------------------ capa negra durante los juegos

    /**
     * Con un mando o teclado Bluetooth, Android enciende la pantalla del móvil con cada botón: los dispositivos de entrada
     * externos «despiertan» el móvil antes de que la tecla llegue a nadie, y una app no lo puede evitar. Encendidos y
     * apagados seguidos, con la pantalla de bloqueo, en mitad de la partida (peor rendimiento). Durante un juego con mando,
     * esta capa deja la pantalla encendida pero negra y con el brillo al mínimo (en OLED se ve apagada): los botones ya no
     * la encienden y apagan cada vez. Tocar el móvil la quita hasta el siguiente juego.
     */
    private android.view.View gameCover;
    private volatile boolean gameOn;
    private boolean gameCoverDismissed;

    /** Juego en marcha o terminado (CarUi.gameDrawing). */
    static void gameCover(boolean on) {
        TouchService t = instance;
        if (t == null) return;
        t.gameOn = on;
        t.main.post(() -> {
            if (!on) {
                t.gameCoverDismissed = false;
                t.hideGameCover("fin del juego");
            } else if (gamepadConnected()) {
                t.showGameCover();
            }
        });
    }

    /** Un mando (o algo con cruceta de juego) externo conectado al móvil. */
    static boolean gamepadConnected() {
        for (int id : android.view.InputDevice.getDeviceIds()) {
            if (fromGamepad(android.view.InputDevice.getDevice(id))) return true;
        }
        return false;
    }

    static boolean fromGamepad(android.view.InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 29 && !d.isExternal()) return false;
        int s = d.getSources();
        return (s & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
                || (s & android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK;
    }

    private void showGameCover() {
        if (gameCover != null || !gameOn || gameCoverDismissed) return;
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0xFF000000);
        v.setOnTouchListener((x, ev) -> {
            if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                gameCoverDismissed = true;
                hideGameCover("tocada en el móvil");
            }
            return true;
        });
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.OPAQUE);
        lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // El mínimo de brillo (0 = lo más bajo que admita la pantalla).
        lp.screenBrightness = 0f;
        try {
            getSystemService(android.view.WindowManager.class).addView(v, lp);
            gameCover = v;
            L.i("Juegos: capa negra en el móvil (mando conectado): los botones ya no encienden la pantalla cada vez");
        } catch (RuntimeException e) {
            L.w("Juegos: no se pudo poner la capa negra: " + e.getClass().getSimpleName());
        }
    }

    private void hideGameCover(String why) {
        if (gameCover == null) return;
        try {
            getSystemService(android.view.WindowManager.class).removeView(gameCover);
        } catch (RuntimeException ignored) {
        }
        gameCover = null;
        L.i("Juegos: capa negra quitada (" + why + ")");
    }

    /**
     * Cruceta y stick del mando (MotionEvent de ejes) mientras hay un juego: Android solo los manda a la ventana que está
     * delante, así que antes hacía falta la pantalla «Mando conectado al coche» abierta en el móvil. Desde Android 14 el
     * servicio de accesibilidad puede pedirlos (setMotionEventSources) y llegan aquí estén donde estén. Solo durante el
     * juego: al acabar se devuelven a las apps.
     */
    static void gamepadAxes(boolean on) {
        TouchService t = instance;
        if (t == null || android.os.Build.VERSION.SDK_INT < 34) return;
        int src = on ? android.view.InputDevice.SOURCE_JOYSTICK | android.view.InputDevice.SOURCE_GAMEPAD : 0;
        t.main.post(() -> {
            try {
                android.accessibilityservice.AccessibilityServiceInfo info = t.getServiceInfo();
                info.setMotionEventSources(src);
                t.setServiceInfo(info);
                L.i("Juegos: ejes del mando " + (on ? "por la accesibilidad (sin pantalla del mando en el móvil)" : "devueltos a las apps"));
            } catch (RuntimeException e) {
                L.w("Juegos: el sistema no deja pedir los ejes del mando: " + e.getClass().getSimpleName());
            }
        });
    }

    /** Hasta dónde llega: en Android 14+ con los ejes pedidos no hace falta la pantalla del mando en el móvil. */
    static boolean axesWithoutActivity() {
        return instance != null && android.os.Build.VERSION.SDK_INT >= 34;
    }

    @Override
    public void onMotionEvent(android.view.MotionEvent e) {
        if (NesInput.pad != null) NesInput.motion(e);
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
