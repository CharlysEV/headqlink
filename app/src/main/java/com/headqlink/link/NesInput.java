package com.headqlink.link;

import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Mando de los emuladores (NES y Mega Drive): las teclas de un mando Bluetooth llegan por el servicio de accesibilidad
 * (TouchService, estén donde estén) o por NesPadActivity (que también recibe la cruceta como eje), y los botones táctiles
 * de la pantalla del coche llaman a press/release. Cada consola reparte las teclas a su manera (GameConsole.map). Puro
 * salvo por los tipos de Android.
 */
final class NesInput {
    private NesInput() {
    }

    /** Mando del jugador 1 mientras hay un juego en marcha; null si no. */
    static volatile RetroPad pad;
    static volatile GameConsole console = GameConsole.NES;
    /** Para el log: el primer botón y el primer movimiento de cruceta de cada partida (por qué vía llegan). */
    static volatile boolean loggedKey;
    static volatile boolean loggedAxis;

    static void newGame(RetroPad p, GameConsole c) {
        console = c;
        pad = p;
        loggedKey = false;
        loggedAxis = false;
        TouchService.gamepadAxes(true);
    }

    /** Fin del juego: el mando vuelve a ser de las apps. */
    static void endGame() {
        if (pad == null) return;
        pad = null;
        TouchService.gamepadAxes(false);
    }

    /** Puro: tecla de Android → botón de la consola en juego, o null si no es del mando. */
    static RetroPad.Btn map(int keyCode) {
        return console.map(keyCode);
    }

    /** Una tecla (down o up). true si era del mando y hay juego: entonces se consume. */
    static boolean key(int keyCode, int action) {
        RetroPad p = pad;
        if (p == null) return false;
        RetroPad.Btn b = map(keyCode);
        if (b == null) return false;
        if (!loggedKey && action == KeyEvent.ACTION_DOWN) {
            loggedKey = true;
            L.i("Juegos: mando: primer botón " + KeyEvent.keyCodeToString(keyCode) + " → " + b + " (" + console.title + ")");
        }
        // Atrás solo cuenta como Select en el mando del coche; en el móvil lo gestiona la actividad.
        if (action == KeyEvent.ACTION_DOWN) p.press(b);
        else if (action == KeyEvent.ACTION_UP) p.release(b);
        return true;
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
        RetroPad p = pad;
        if (p == null || (e.getSource() & (android.view.InputDevice.SOURCE_JOYSTICK | android.view.InputDevice.SOURCE_DPAD)) == 0) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(MotionEvent.AXIS_X);
            y = e.getAxisValue(MotionEvent.AXIS_Y);
        }
        if (!loggedAxis && (Math.abs(x) > 0.5f || Math.abs(y) > 0.5f)) {
            loggedAxis = true;
            L.i("Juegos: mando: cruceta o stick por ejes (" + (e.getDevice() != null ? e.getDevice().getName() : "?") + ")");
        }
        p.axes(x, y);
        return true;
    }
}
