package com.headqlink.link;

/**
 * ¿Se puede dejar de llamar a startForeground en una orden que quizá llegó con startForegroundService? Solo si el
 * servicio ya está en primer plano de verdad: Android solo perdona esa llamada a un servicio que el sistema ya tiene
 * en primer plano; si no, a los pocos segundos cierra la app con ForegroundServiceDidNotStartInTimeException. Puro
 * (lo prueba ForegroundCheckTest).
 */
final class ForegroundCheck {
    private ForegroundCheck() {
    }

    /**
     * @param flag       lo que cree el servicio (pasó a primer plano en esta instancia y no ha salido)
     * @param systemType Service.getForegroundServiceType() (Android 10+): 0 si el sistema no lo tiene en primer plano
     * @param sdk        Build.VERSION.SDK_INT
     */
    static boolean alreadyForeground(boolean flag, int systemType, int sdk) {
        if (!flag) return false;
        // Android 10+: lo que dice el sistema manda (el servicio siempre pasa a primer plano con un tipo, nunca 0).
        return sdk < 29 || systemType != 0;
    }
}
