package com.headqlink.link;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.grapeshot.halfnes.ui.PuppetController;

/**
 * Mando del emulador NES: las teclas de un mando Bluetooth (cruceta, A, B, Start, Select) llegan por el servicio de
 * accesibilidad (TouchService, estén donde estén) o por NesPadActivity (que también recibe la cruceta como eje), y los
 * botones táctiles de la pantalla del coche llaman a press/release. Puro salvo por los tipos de Android.
 */
final class NesInput {
    private NesInput() {
    }

    /** Mando del jugador 1 mientras hay un juego en marcha; null si no. */
    static volatile PuppetController pad;

    /** Puro: tecla de Android → botón NES, o null si no es del mando. */
    static PuppetController.Button map(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return PuppetController.Button.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return PuppetController.Button.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return PuppetController.Button.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return PuppetController.Button.RIGHT;
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BUTTON_Y:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return PuppetController.Button.A;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BUTTON_X:
                return PuppetController.Button.B;
            case KeyEvent.KEYCODE_BUTTON_START:
            case KeyEvent.KEYCODE_MENU:
                return PuppetController.Button.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK:
                return PuppetController.Button.SELECT;
            default:
                return null;
        }
    }

    /** Una tecla (down o up). true si era del mando y hay juego: entonces se consume. */
    static boolean key(int keyCode, int action) {
        PuppetController p = pad;
        if (p == null) return false;
        PuppetController.Button b = map(keyCode);
        if (b == null) return false;
        // Atrás solo cuenta como Select en el mando del coche; en el móvil lo gestiona la actividad.
        if (action == KeyEvent.ACTION_DOWN) p.pressButton(b);
        else if (action == KeyEvent.ACTION_UP) p.releaseButton(b);
        return true;
    }

    static void press(PuppetController.Button b) {
        PuppetController p = pad;
        if (p != null) p.pressButton(b);
    }

    static void release(PuppetController.Button b) {
        PuppetController p = pad;
        if (p != null) p.releaseButton(b);
    }

    private static int hatX, hatY;

    /** Cruceta y stick izquierdo como ejes (MotionEvent de un mando): se pasan a UP/DOWN/LEFT/RIGHT. */
    static boolean motion(MotionEvent e) {
        PuppetController p = pad;
        if (p == null || (e.getSource() & (android.view.InputDevice.SOURCE_JOYSTICK | android.view.InputDevice.SOURCE_DPAD)) == 0) return false;
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X);
        float y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(MotionEvent.AXIS_X);
            y = e.getAxisValue(MotionEvent.AXIS_Y);
        }
        apply(p, x, y);
        return true;
    }

    /** Puro salvo por el mando: ejes (-1..1) → cruceta, soltando lo que ya no se pulsa. */
    static void apply(PuppetController p, float x, float y) {
        int nx = x < -0.5f ? -1 : x > 0.5f ? 1 : 0;
        int ny = y < -0.5f ? -1 : y > 0.5f ? 1 : 0;
        if (nx != hatX) {
            p.releaseButton(PuppetController.Button.LEFT);
            p.releaseButton(PuppetController.Button.RIGHT);
            if (nx < 0) p.pressButton(PuppetController.Button.LEFT);
            if (nx > 0) p.pressButton(PuppetController.Button.RIGHT);
            hatX = nx;
        }
        if (ny != hatY) {
            p.releaseButton(PuppetController.Button.UP);
            p.releaseButton(PuppetController.Button.DOWN);
            if (ny < 0) p.pressButton(PuppetController.Button.UP);
            if (ny > 0) p.pressButton(PuppetController.Button.DOWN);
            hatY = ny;
        }
    }
}
