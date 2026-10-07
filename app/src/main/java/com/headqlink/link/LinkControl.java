package com.headqlink.link;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * «Conectar» y «Desconectar», iguales desde la pantalla principal, el widget y el botón de los ajustes rápidos.
 *
 * Conectar: primero la comprobación de requisitos (fuera del hilo principal, sin tocar el servidor de Android Auto);
 * si falta algo obligatorio, la pantalla «Comprobación» con «Conectar igualmente» (startActivityForResult: con
 * RESULT_OK, {@link #start}). Después, en modo Android Auto con el arranque automático, su servidor (accesibilidad, tras
 * la capa: el móvil está desbloqueado porque alguien acaba de tocar el botón) y luego el servicio en primer plano.
 *
 * Se llama desde una actividad que está delante (HomeActivity o el puente invisible QuickToggleActivity): Android 12+
 * solo deja arrancar un servicio en primer plano desde una app visible, y la comprobación necesita una actividad.
 */
final class LinkControl {
    /** Comprobando los requisitos: otro toque (de donde sea) mientras tanto se ignora. */
    private static final AtomicBoolean CHECKING = new AtomicBoolean();

    private LinkControl() {
    }

    /** Hay un Conectar en curso (comprobación o servidor de Android Auto). */
    static boolean busy() {
        return CHECKING.get() || LinkState.preparing;
    }

    /**
     * Desconectar: cierra todo, como el botón de la pantalla principal. El servicio está en marcha y en primer plano, así
     * que startService vale también desde el botón de los ajustes rápidos.
     */
    static void stop(Context c, String from) {
        L.life("Desconectar (" + from + ")");
        try {
            c.startService(new Intent(c, LinkService.class).setAction(LinkService.ACTION_STOP));
        } catch (RuntimeException e) {
            L.w("Desconectar (" + from + "): no se pudo avisar al servicio: " + e);
        }
    }

    /**
     * Conectar desde una actividad que está delante: requisitos y, si falta algo obligatorio, la comprobación con
     * reqChecklist (sin llamar a done: sigue en onActivityResult). done (puede ser null) se llama en el hilo principal
     * cuando el intento termina sin comprobación: arrancado, o nada que hacer (ya en marcha o en curso).
     */
    static void connect(Activity a, int reqChecklist, String from, Runnable done) {
        if (LinkState.running || LinkState.preparing || !CHECKING.compareAndSet(false, true)) {
            run(done);
            return;
        }
        new Thread(() -> {
            List<Requirements.Item> blocking;
            try {
                blocking = Requirements.blocking(Checklist.evaluateNow(a));
            } catch (RuntimeException e) {
                L.e("conectar (" + from + "): no se pudieron comprobar los requisitos; conecto igualmente", e);
                blocking = Collections.emptyList();
            }
            List<Requirements.Item> missing = blocking;
            a.runOnUiThread(() -> {
                CHECKING.set(false);
                if (a.isFinishing() || a.isDestroyed() || LinkState.running) {
                    run(done);
                    return;
                }
                if (missing.isEmpty()) {
                    start(a, from, done);
                    return;
                }
                L.i("conectar (" + from + "): faltan requisitos obligatorios " + missing + "; abro la comprobación");
                a.startActivityForResult(new Intent(a, ChecklistActivity.class).putExtra(ChecklistActivity.EXTRA_GATE, true),
                        reqChecklist);
            });
        }, "hql-req-connect").start();
    }

    /**
     * Arranca el enlace (sin comprobar requisitos). En modo Android Auto, antes se asegura de que el servidor de AA está
     * en marcha: mientras tanto LinkState.preparing («Preparando…»). done (puede ser null), al terminar, en el hilo
     * principal.
     */
    static void start(Activity a, String from, Runnable done) {
        if (LinkState.running || LinkState.preparing) {
            run(done);
            return;
        }
        Context app = a.getApplicationContext();
        Config cfg = new Config(a);
        Intent link = new Intent(app, LinkService.class).setAction(LinkService.ACTION_APPLY);
        boolean manual = cfg.aaServerManual();
        if (!Config.isAa(cfg.mode()) || (!manual && AaServerPolicy.onNeed(AaServerPolicy.Need.CONNECT, connectState(a))
                != AaServerPolicy.Action.AUTOMATE)) {
            // Sin Android Auto, o el automático sin poder pulsar ahora (sin accesibilidad o bloqueado): sin arrancarlo.
            launch(app, link, from);
            run(done);
            return;
        }
        boolean aaConnected = com.andrerinas.openheadunit.App.Companion.provide(app).getCommManager().isConnected();
        if (manual && !AaPark.parked && !aaConnected) {
            // Arranque manual: no se pulsa nada ni se mira el puerto (una sonda bloquearía el servidor). Lo dice el intento
            // real del Self-Mode con el coche: si AA no contesta, aviso, la fila «Auto» lo dice y reintento cada 5 s.
            L.life("conectar (" + from + "): arranque manual del servidor de Android Auto (sin accesibilidad): sin"
                    + " comprobarlo antes; lo dirá el intento real con el coche");
            launch(app, link, from);
            run(done);
            return;
        }
        if (AaPark.parked || aaConnected) {
            // AA sigue conectado (en pausa, esperando al coche): su servidor está encendido. Nada que arrancar ni apagar:
            // el enlace lo reanuda en cuanto llegue el coche.
            L.life("conectar (" + from + "): Android Auto sigue conectado (en pausa); no hace falta arrancar su servidor");
            AaServerStarter.cancelPendingStop(app);
            launch(app, link, from);
            run(done);
            return;
        }
        LinkState.setPreparing(true);
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = AaServerStarter.startAndWait(a);
            } catch (RuntimeException e) {
                L.e("conectar (" + from + "): arranque del servidor de Android Auto", e);
            }
            L.i("conectar (" + from + "): servidor de Android Auto " + (ok ? "listo" : "no confirmado"));
            a.runOnUiThread(() -> {
                LinkState.setPreparing(false);
                launch(app, link, from);
                run(done);
            });
        }, "aa-connect").start();
    }

    /** Para AaServerPolicy al pulsar Conectar con el arranque automático: accesibilidad y bloqueo (como siempre). */
    private static AaServerPolicy.State connectState(Context c) {
        KeyguardManager km = c.getSystemService(KeyguardManager.class);
        return new AaServerPolicy.State().manual(false).automate(TouchService.instance != null)
                .locked(km != null && km.isKeyguardLocked());
    }

    /** El servicio en primer plano. Si Android no lo deja (la app ya no está delante), se registra en vez de cerrar la app. */
    private static void launch(Context app, Intent link, String from) {
        try {
            // Si HeadQLink ya no está delante (la automatización de Android Auto la tapó), Android no le da al servicio
            // la ubicación «mientras se usa»: el servicio la recupera en cuanto vuelva a verse (LinkService.appShown).
            ContextCompat.startForegroundService(app, LinkService.fromApp(link));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+) u otra restricción.
            L.w("conectar (" + from + "): Android no deja arrancar el servicio ahora (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "); pulsa Conectar en la app");
        }
    }

    private static void run(Runnable r) {
        if (r != null) r.run();
    }
}
