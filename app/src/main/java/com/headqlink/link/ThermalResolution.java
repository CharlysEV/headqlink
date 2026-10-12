package com.headqlink.link;

import android.content.Context;
import android.content.Intent;

/**
 * La resolución que toca ahora con los perfiles «Coche» y «Automático» (ResolutionLadder): 720p al arrancar el
 * servicio y 1080p cuando el móvil va fresco. Config.videoProfile la aplica al perfil; al cambiar, se reconecta para que
 * Android Auto la renegocie (como al guardar otro perfil desde el coche). Con otro perfil elegido a mano, no se toca.
 */
final class ThermalResolution {
    private static volatile boolean high;
    /** Decide cuándo subir y bajar; vive lo que el servicio (las reconexiones no la reinician). */
    private static ResolutionLadder ladder = new ResolutionLadder(false);

    private ThermalResolution() {
    }

    /** ¿1080p? (si no, 720p). */
    static boolean high() {
        return high;
    }

    /** Al arrancar el servicio: se empieza por la más baja. */
    static synchronized void reset() {
        high = false;
        ladder = new ResolutionLadder(false);
    }

    /** Cada 10 s (ThermalGuard): estado térmico, margen previsto (NaN si no hay) y etapa de anticipación. */
    static void tick(Context ctx, int status, float headroom, int stage, long nowMs) {
        int step;
        synchronized (ThermalResolution.class) {
            step = ladder.update(status, headroom, stage, nowMs);
        }
        if (step == 0) return;
        String why = step > 0 ? String.format(java.util.Locale.US, "fresco: estado %d, margen %.2f", status, headroom)
                : String.format(java.util.Locale.US, "calor: estado %d, anticipación %d", status, stage);
        change(ctx, step > 0, why);
    }

    /** Puro: ¿la resolución de este perfil la decide el calor? Los que siguen al coche («Coche», y «Automático» con él). */
    static boolean applies(VideoProfile p) {
        return p != null && p.followsCar;
    }

    /** Cambio decidido por ResolutionLadder: se guarda y se reconecta para que AA vuelva con la resolución nueva. */
    static void change(Context ctx, boolean toHigh, String why) {
        Config cfg = new Config(ctx);
        if (!Config.isAa(cfg.mode()) || !applies(cfg.videoProfile())) {
            high = toHigh;
            return;
        }
        high = toHigh;
        L.i("resolución: " + (toHigh ? "sube a 1080p" : "baja a 720p") + " (" + why + "); reconecta para que Android Auto la renegocie");
        CarUi.videoInfoText(Str.get(toHigh ? R_UP : R_DOWN));
        if (DemoMode.active()) return;
        try {
            ctx.startForegroundService(LinkService.fromApp(new Intent(ctx, LinkService.class)
                    .setAction(LinkService.ACTION_APPLY).putExtra(LinkService.EXTRA_AA_RENEGOTIATE, true)));
        } catch (RuntimeException e) {
            L.w("resolución: no se pudo reconectar: " + e.getMessage() + " (se aplica en la próxima sesión)");
        }
    }

    private static final int R_UP = com.andrerinas.openheadunit.R.string.hql_res_going_up;
    private static final int R_DOWN = com.andrerinas.openheadunit.R.string.hql_res_going_down;
}
