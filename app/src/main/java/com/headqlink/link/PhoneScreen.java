package com.headqlink.link;

import android.app.UiModeManager;
import android.content.Context;

/**
 * Pantalla del móvil mientras se proyecta (viaje del 2026-10-05: no se apagaba).
 *
 * Quién activa el modo coche: AapService.setupCarMode (Open Headunit), al crearse el servicio de Android Auto, con
 * UiModeManager.enableCarMode(0) (dumpsys uimode: mCarModeEnabled=true carModeApps=com.headqlink.app). Open Headunit
 * está pensado para una radio de coche con Android, donde el modo coche da al sistema sus pantallas de coche; aquí el
 * «head unit» es el propio móvil.
 *
 * Qué hace Android con él (AOSP, UiModeManagerService.updateLocked): con el modo coche activo, el móvil enchufado
 * (mCharging) y config_carDockKeepsScreenOn = 1 (mCarModeKeepsScreenOn, lo normal), si al activarlo no se pasó
 * ENABLE_CAR_MODE_ALLOW_SLEEP, guarda un FULL_WAKE_LOCK («UiModeManager»): la pantalla no se apaga por tiempo, y en
 * casa (enchufado por USB) tampoco con KEYCODE_SLEEP ni KEYCODE_POWER.
 *
 * Qué se hace: el modo coche se sigue activando igual (Android Auto y el sistema ven lo mismo), pero con
 * ENABLE_CAR_MODE_ALLOW_SLEEP, que es justo lo que quita ese bloqueo; salvo con el ajuste «Mantener la pantalla del
 * móvil encendida» (lo de antes). La proyección no necesita la pantalla: va por nuestro head unit sin vista
 * (VideoTap.headless), LinkService solo guarda un PARTIAL_WAKE_LOCK y el candado de Wi-Fi, y con la pantalla apagada
 * AapService ya no para el decodificador ni el audio de AA (eso es para una radio que se duerme).
 */
public final class PhoneScreen {
    private PhoneScreen() {
    }

    /** Indicadores para UiModeManager.enableCarMode según el ajuste (puro). */
    static int flagsFor(boolean keepScreenOn) {
        return keepScreenOn ? 0 : UiModeManager.ENABLE_CAR_MODE_ALLOW_SLEEP;
    }

    /** Indicadores para UiModeManager.enableCarMode (los usa AapService); la decisión queda en el log. */
    public static int carModeFlags(Context ctx) {
        boolean keepOn = new Config(ctx).keepScreenOn();
        L.i(keepOn
                ? "pantalla: modo coche de Android SIN ENABLE_CAR_MODE_ALLOW_SLEEP (ajuste «Mantener la pantalla del móvil"
                + " encendida»): enchufado, Android no deja apagar la pantalla"
                : "pantalla: modo coche de Android con ENABLE_CAR_MODE_ALLOW_SLEEP: la pantalla del móvil se apaga con el"
                + " botón y por tiempo, y la proyección sigue");
        return flagsFor(keepOn);
    }

    /** Para el log de encendido y apagado de la pantalla: ¿modo coche de Android activo, y con qué indicador? */
    static String describe(Context ctx) {
        UiModeManager um = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
        boolean car = um != null && um.getCurrentModeType() == android.content.res.Configuration.UI_MODE_TYPE_CAR;
        if (!car) return "sin modo coche de Android";
        return "modo coche de Android " + (new Config(ctx).keepScreenOn() ? "sin ALLOW_SLEEP (pantalla encendida)" : "con ALLOW_SLEEP");
    }
}
