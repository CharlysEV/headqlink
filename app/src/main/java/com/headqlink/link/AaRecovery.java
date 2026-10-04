package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;

import com.andrerinas.openheadunit.aap.AapService;

/**
 * Recupera un servidor de head unit de Android Auto "sordo" (acepta TCP pero no contesta al
 * handshake, típico tras cortar una sesión de golpe): lo reinicia con la automatización de
 * accesibilidad y relanza el Self-Mode. Como mucho un intento por minuto para no entrar en bucle.
 */
public final class AaRecovery {
    private static final long COOLDOWN_MS = 60_000;
    private static volatile long lastAttempt;

    private AaRecovery() {
    }

    public static void onServerDeaf(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - lastAttempt < COOLDOWN_MS) {
            L.i("AA recuperación: ya se intentó hace menos de un minuto");
            return;
        }
        lastAttempt = now;
        Context app = ctx.getApplicationContext();
        String why = AaServerStarter.cannotRunReason(app);
        if (why != null) {
            L.w("AA recuperación: el servidor de AA no responde y no se puede reiniciar ahora (" + why + ")");
            LinkState.setSource(LinkState.Level.ERROR, Str.get(R.string.hql_auto_not_responding, why));
            return;
        }
        new Thread(() -> {
            L.w("AA recuperación: el servidor de AA no responde; lo reinicio");
            if (AaServerStarter.restartAndWait(app)) {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                }
                app.startForegroundService(new Intent(app, AapService.class).setAction(AapService.ACTION_START_SELF_MODE));
            }
        }, "aa-recovery").start();
    }
}
