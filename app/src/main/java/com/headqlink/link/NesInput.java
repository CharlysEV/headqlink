package com.headqlink.link;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.andrerinas.openheadunit.R;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mando de los emuladores (NES y Mega Drive): las teclas de un mando Bluetooth llegan por el servicio de accesibilidad
 * (TouchService, estén donde estén) o por NesPadActivity (que también recibe la cruceta como eje), y los botones táctiles
 * de la pantalla del coche llaman a press/release. Cada consola reparte las teclas a su manera (GameConsole.map). Puro
 * salvo por los tipos de Android.
 * <p>
 * Dos jugadores: cada tecla y cada movimiento de ejes trae su dispositivo (deviceId; también por la accesibilidad, que
 * pasa una copia del evento con su id). PadSlots decide de qué jugador es cada mando: el primero que pulsa es el 1 y el
 * segundo, el 2; un tercero no hace nada. Los botones táctiles, un teclado o un evento sin dispositivo son del jugador 1.
 * Con un núcleo de un solo jugador (sin pad2), todo va al jugador 1, como siempre.
 */
final class NesInput {
    private NesInput() {
    }

    /** Mando del jugador 1 mientras hay un juego en marcha; null si no. */
    static volatile RetroPad pad;
    /** Mando del jugador 2 (el segundo mando Bluetooth); null si no hay juego o el núcleo solo tiene un jugador. */
    static volatile RetroPad pad2;
    static volatile GameConsole console = GameConsole.NES;
    /** Qué mando es cada jugador en esta partida. */
    static final PadSlots slots = new PadSlots();
    /** Para el log: el primer botón y el primer movimiento de cruceta de cada partida (por qué vía llegan). */
    static volatile boolean loggedKey;
    static volatile boolean loggedAxis;
    private static volatile boolean loggedThird;

    /** Cambia quién tiene mando: notice es el aviso corto para la pantalla («Mando 2: jugador 2»…), o null. */
    interface PadsListener {
        void changed(String notice);
    }

    private static final CopyOnWriteArrayList<PadsListener> listeners = new CopyOnWriteArrayList<>();
    private static InputManager inputManager;

    static void newGame(RetroPad p, GameConsole c) {
        newGame(p, null, c);
    }

    static void newGame(RetroPad p, RetroPad p2, GameConsole c) {
        console = c;
        slots.clear();
        pad2 = p2;
        pad = p;
        loggedKey = false;
        loggedAxis = false;
        loggedThird = false;
        TouchService.gamepadAxes(true);
        notifyPads(null);
    }

    /** Fin del juego: el mando vuelve a ser de las apps y nadie guarda puesto. */
    static void endGame() {
        if (pad == null) return;
        pad = null;
        pad2 = null;
        slots.clear();
        unwatch();
        TouchService.gamepadAxes(false);
        notifyPads(null);
    }

    /** Puro: tecla de Android → botón de la consola en juego, o null si no es del mando. */
    static RetroPad.Btn map(int keyCode) {
        return console.map(keyCode);
    }

    /** Una tecla de un mando (down o up), al jugador de su dispositivo. true si era del mando y hay juego: se consume. */
    static boolean key(KeyEvent e) {
        InputDevice d = e.getDevice();
        return key(e.getKeyCode(), e.getAction(), deviceKey(d, e.getDeviceId()), e.getDeviceId(), TouchService.fromGamepad(d),
                d != null ? d.getName() : null);
    }

    /** Una tecla sin dispositivo conocido: del jugador 1. */
    static boolean key(int keyCode, int action) {
        return key(keyCode, action, null, 0, false, null);
    }

    /**
     * Una tecla (down o up) del dispositivo con esa clave e id; gamepad dice si es un mando (si no, es del jugador 1).
     * true si era del mando y hay juego: entonces se consume (también la de un tercer mando, que no hace nada).
     */
    static boolean key(int keyCode, int action, String devKey, int devId, boolean gamepad, String name) {
        if (pad == null) return false;
        RetroPad.Btn b = map(keyCode);
        if (b == null) return false;
        if (!loggedKey && action == KeyEvent.ACTION_DOWN) {
            loggedKey = true;
            L.i("Juegos: mando: primer botón " + KeyEvent.keyCodeToString(keyCode) + " → " + b + " (" + console.title + ")");
        }
        RetroPad p = padFor(devKey, devId, gamepad, action == KeyEvent.ACTION_DOWN, name);
        if (p == null) return true;
        // Atrás solo cuenta como Select en el mando del coche; en el móvil lo gestiona la actividad.
        if (action == KeyEvent.ACTION_DOWN) p.press(b);
        else if (action == KeyEvent.ACTION_UP) p.release(b);
        return true;
    }

    /**
     * El RetroPad del jugador de ese dispositivo, o null si no tiene (tercer mando, o uno sin puesto que solo suelta o
     * deja el stick en el centro). claim: un botón pulsado o un eje movido, que le da puesto si no lo tenía.
     */
    private static RetroPad padFor(String devKey, int devId, boolean gamepad, boolean claim, String name) {
        RetroPad p1 = pad;
        RetroPad p2 = pad2;
        if (p2 == null || !gamepad || devKey == null) return p1;
        int before = slots.seatOf(devKey);
        boolean wasOn = before != PadSlots.NONE && slots.connected(before);
        int s = claim ? slots.claim(devKey, devId) : before;
        if (s == PadSlots.NONE) {
            if (claim && !loggedThird) {
                loggedThird = true;
                L.i("Juegos: un tercer mando (" + safeName(name) + ") no tiene puesto: se ignora");
            }
            return null;
        }
        if (claim && !wasOn) {
            boolean back = before == s;
            L.i("Juegos: mando " + (s + 1) + (back ? " de vuelta" : " asignado") + " (" + safeName(name) + ")");
            notifyPads(back ? Str.get(R.string.hql_games_pad_back, s + 1) : s == 1 ? Str.get(R.string.hql_games_pad_joined, s + 1) : null);
        }
        return s == 0 ? p1 : p2;
    }

    static void press(RetroPad.Btn b) {
        RetroPad p = pad;
        if (p != null) p.press(b);
    }

    static void release(RetroPad.Btn b) {
        RetroPad p = pad;
        if (p != null) p.release(b);
    }

    /** Cruceta y stick izquierdo como ejes (MotionEvent de un mando): se pasan a arriba, abajo, izquierda y derecha. */
    static boolean motion(MotionEvent e) {
        if (pad == null || (e.getSource() & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_DPAD)) == 0) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(MotionEvent.AXIS_X);
            y = e.getAxisValue(MotionEvent.AXIS_Y);
        }
        InputDevice d = e.getDevice();
        return axes(x, y, deviceKey(d, e.getDeviceId()), e.getDeviceId(), TouchService.fromGamepad(d), d != null ? d.getName() : null);
    }

    /** Ejes ya leídos (-1..1) del dispositivo con esa clave e id, al jugador de ese mando. */
    static boolean axes(float x, float y, String devKey, int devId, boolean gamepad, String name) {
        if (pad == null) return false;
        boolean moved = Math.abs(x) > 0.5f || Math.abs(y) > 0.5f;
        if (!loggedAxis && moved) {
            loggedAxis = true;
            L.i("Juegos: mando: cruceta o stick por ejes (" + safeName(name) + ")");
        }
        RetroPad p = padFor(devKey, devId, gamepad, moved, name);
        if (p != null) p.axes(x, y);
        return true;
    }

    /** La clave estable de un dispositivo: su descriptor (igual tras reconectarse), o su id si no lo da. */
    static String deviceKey(InputDevice d, int id) {
        String k = d != null ? d.getDescriptor() : null;
        return k != null && !k.isEmpty() ? k : "id:" + id;
    }

    /** Puro: el nombre del mando para el log (el modelo), sin nada que parezca una dirección MAC. */
    static String safeName(String name) {
        if (name == null || name.trim().isEmpty()) return "?";
        String s = name.replaceAll("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b", "").trim();
        if (s.length() > 40) s = s.substring(0, 40);
        return s.isEmpty() ? "?" : s;
    }

    // ------------------------------------------------------------------ mandos que se van y vuelven

    private static final InputManager.InputDeviceListener devices = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int deviceId) {
        }

        @Override
        public void onInputDeviceRemoved(int deviceId) {
            deviceRemoved(deviceId);
        }

        @Override
        public void onInputDeviceChanged(int deviceId) {
        }
    };

    /** Avisos de mandos conectados y desconectados durante la partida (hasta endGame). */
    static void watch(Context c) {
        try {
            InputManager im = c.getApplicationContext().getSystemService(InputManager.class);
            if (im == null) return;
            unwatch();
            im.registerInputDeviceListener(devices, new Handler(Looper.getMainLooper()));
            inputManager = im;
        } catch (RuntimeException e) {
            L.w("Juegos: sin avisos de mandos desconectados: " + e.getClass().getSimpleName());
        }
    }

    private static void unwatch() {
        InputManager im = inputManager;
        inputManager = null;
        if (im == null) return;
        try {
            im.unregisterInputDeviceListener(devices);
        } catch (RuntimeException ignored) {
        }
    }

    /** Un dispositivo se ha desconectado: si era el mando de un jugador, se sueltan sus botones y guarda el puesto. */
    static void deviceRemoved(int deviceId) {
        int s = slots.removed(deviceId);
        if (s == PadSlots.NONE) return;
        RetroPad p = s == 0 ? pad : pad2;
        if (p != null) p.releaseAll();
        L.i("Juegos: mando " + (s + 1) + " desconectado (guarda su puesto si vuelve)");
        notifyPads(Str.get(R.string.hql_games_pad_lost, s + 1));
    }

    // ------------------------------------------------------------------ indicador «Mando 1 · Mando 2»

    static void addListener(PadsListener l) {
        listeners.addIfAbsent(l);
    }

    static void removeListener(PadsListener l) {
        listeners.remove(l);
    }

    private static void notifyPads(String notice) {
        for (PadsListener l : listeners) {
            try {
                l.changed(notice);
            } catch (RuntimeException e) {
                L.w("Juegos: indicador de mandos: " + e.getClass().getSimpleName());
            }
        }
    }

    /** «Mando 1 · Mando 2» con los mandos que tienen puesto (el desconectado, marcado); vacío si no hay ninguno. */
    static String padsLabel() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PadSlots.PLAYERS; i++) {
            if (!slots.taken(i)) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(Str.get(slots.connected(i) ? R.string.hql_games_pad : R.string.hql_games_pad_off, i + 1));
        }
        return sb.toString();
    }
}
