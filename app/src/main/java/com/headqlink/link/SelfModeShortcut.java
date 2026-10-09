package com.headqlink.link;

import android.content.Context;

/**
 * Arranque de Android Auto en el Self-Mode (AA 17.4+, {@code SelfLauncherV17_4} de Open Headunit): primero manda el
 * aviso START_WIRELESS_PROJECTION (su camino 2) y espera 12 s a que AA conecte; si no, conecta con el servidor de head
 * unit de AA (127.0.0.1:5277, camino 3). En el S25 Ultra con AA 17.7, el camino 2 no contestó nunca (52 de 52 arranques
 * entre el 2026-10-04 y el 10-09) y el 3 entró a la primera: cada arranque de AA (al conectar, al volver tras un corte,
 * al cambiar de modo) tardaba 12 s de más, con el hueco de AA en negro en la pantalla del coche.
 *
 * Aquí se recuerda, por versión de AA: si en este móvil el camino 2 caducó y el 3 conectó, con esa misma versión se va
 * directo al 3. Con otra versión de AA (actualización) se vuelve a probar el 2, y si el 2 conecta alguna vez se olvida.
 * La decisión es pura ({@link #direct(long, long)}) y la prueban los tests.
 */
public final class SelfModeShortcut {
    private SelfModeShortcut() {
    }

    /** ¿Directo al servidor de head unit? Solo con la misma versión de AA con la que el camino 2 no contestó. */
    static boolean direct(long learnedAaCode, long aaCode) {
        return aaCode > 0 && learnedAaCode == aaCode;
    }

    private static long aaCode(Context ctx) {
        if (AaVersions.lastCode() <= 0) AaVersions.read(ctx);
        return AaVersions.lastCode();
    }

    /** Para el lanzador: ¿saltarse los avisos e ir directo al servidor de head unit? */
    public static boolean direct(Context ctx) {
        long aa = aaCode(ctx);
        boolean d = direct(new Config(ctx).selfModeDirectAa(), aa);
        if (d) L.i("AA: arranque directo al servidor de head unit (con AA " + aa + " el aviso de proyección no contestó nunca en este móvil: 12 s menos)");
        return d;
    }

    /** El aviso (camino 2) caducó y el servidor de head unit (camino 3) conectó: se recuerda para esta versión de AA. */
    public static void learn(Context ctx) {
        long aa = aaCode(ctx);
        if (aa <= 0) return;
        Config c = new Config(ctx);
        if (c.selfModeDirectAa() == aa) return;
        c.setSelfModeDirectAa(aa);
        L.i("AA: el aviso de proyección no contestó en 12 s y el servidor de head unit sí: los próximos arranques con AA " + aa + " van directos al servidor");
    }

    /** El aviso (camino 2) conectó: se olvida el atajo. */
    public static void forget(Context ctx) {
        Config c = new Config(ctx);
        if (c.selfModeDirectAa() <= 0) return;
        c.setSelfModeDirectAa(-1);
        L.i("AA: el aviso de proyección ha contestado: se vuelve a probar primero");
    }
}
