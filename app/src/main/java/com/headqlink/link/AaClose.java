package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.connection.CommManager;

/**
 * Todo cierre de nuestra head unit con Android Auto que pide HeadQLink, limpio, para que el servidor de head unit de AA
 * (127.0.0.1:5277) siga atendiendo después (prueba real del 2026-10-06, docs §15): tras una sesión cerrada con orden
 * vuelve a atender sin reiniciarlo, pero una conexión cortada a medias lo bloquea hasta pararlo y volver a iniciarlo.
 *
 * - Con la sesión hecha (handshake terminado), las órdenes de AapService ya cierran con orden: ACTION_STOP_SERVICE y
 *   ACTION_DISCONNECT mandan el ByeBye, esperan su envío y luego cierran el socket.
 * - Con el handshake a medias (TCP abierto, versión o SSL en curso) o con un Self-Mode recién pedido que aún puede estar
 *   marcando, cerrar ya sería un corte a medias (el ByeBye va cifrado: antes del SSL no se puede mandar) o dejaría un
 *   socket abierto sin dueño que alguien cerraría después sin decir nada. Se espera a que termine, como mucho
 *   {@link #HANDSHAKE_WAIT_MS} (un servidor sano contesta en milisegundos; si en 6 s no ha dicho nada, ya estaba
 *   bloqueado y cerrar no cambia nada), y entonces la orden de siempre.
 *
 * Lo decide {@link #mustWait} (puro, lo prueban los tests). La espera va en su propio hilo: nunca bloquea al que llama.
 */
final class AaClose {
    /** Lo más que se espera a que termine un handshake en curso (el mismo plazo que da un intento por no servido). */
    static final long HANDSHAKE_WAIT_MS = AaServeAttempts.SERVE_TIMEOUT_MS;
    /** Un Self-Mode pedido hace menos de esto puede estar aún marcando (la orden tarda en llegar a AapService). */
    static final long LAUNCH_GRACE_MS = 1_500;
    private static final long POLL_MS = 50;

    /** Cómo está la conexión con Android Auto. */
    enum Link {
        /** Sin conexión. */
        NONE,
        /** Abriendo el TCP. */
        DIALING,
        /** TCP abierto y handshake sin terminar (versión o SSL). */
        HANDSHAKE,
        /** Handshake terminado: se cierra con ByeBye. */
        SESSION,
    }

    /** Cuándo se pidió el último Self-Mode (SystemClock.elapsedRealtime), o -1. */
    private static volatile long lastLaunchMs = -1;
    /** Sube con cada cierre y con cada anulación: un cierre en espera que la ve cambiada ya no se hace. */
    private static final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    private AaClose() {
    }

    /** Se acaba de pedir un Self-Mode (ACTION_START_SELF_MODE): durante un momento, un cierre lo deja marcar antes. */
    static void noteLaunch() {
        lastLaunchMs = SystemClock.elapsedRealtime();
    }

    /**
     * El enlace vuelve a arrancar: un cierre que aún esperaba a que terminara un handshake ya no se hace (esa conexión,
     * cuando termine, es la del enlace nuevo).
     */
    static void cancelPending(String why) {
        generation.incrementAndGet();
        boolean was = pending;
        pending = false;
        if (was) L.life("AA: anulo el cierre que esperaba a que terminara el handshake (" + why + ")");
    }

    /** Hay un cierre esperando a que termine un handshake. */
    private static volatile boolean pending;

    /** Para Android Auto y su servicio (ACTION_STOP_SERVICE: ByeBye y cierre ordenado), sin cortar un handshake. */
    static void stopAa(Context ctx, String why) {
        close(ctx, why, AapService.ACTION_STOP_SERVICE);
    }

    /**
     * Desconecta Android Auto para que se vuelva a conectar con otros ajustes (ACTION_DISCONNECT con ByeBye, y el
     * Self-Mode parado), sin cortar un handshake.
     */
    static void reconnectAa(Context ctx, String why) {
        close(ctx, why, AapService.ACTION_DISCONNECT, AapService.ACTION_STOP_SELF_MODE);
    }

    /** ¿Hay que esperar antes de cerrar? Nunca más de {@link #HANDSHAKE_WAIT_MS} en total. */
    static boolean mustWait(Link link, boolean launchRecent, long waitedMs) {
        if (waitedMs >= HANDSHAKE_WAIT_MS) return false;
        switch (link) {
            case DIALING:
            case HANDSHAKE:
                return true;
            case NONE:
                // Un Self-Mode recién pedido aún puede marcar: que lo haga, y se cierra su sesión con orden.
                return launchRecent;
            default:
                return false;
        }
    }

    /** El estado de CommManager, en lo que importa para cerrar. */
    static Link linkOf(Object state) {
        if (state instanceof CommManager.ConnectionState.Connecting) return Link.DIALING;
        if (state instanceof CommManager.ConnectionState.Connected
                || state instanceof CommManager.ConnectionState.StartingTransport) return Link.HANDSHAKE;
        if (state instanceof CommManager.ConnectionState.HandshakeComplete
                || state instanceof CommManager.ConnectionState.TransportStarted) return Link.SESSION;
        return Link.NONE;
    }

    // ---------------------------------------------------------------- con Android

    private static void close(Context ctx, String why, String... actions) {
        Context app = ctx.getApplicationContext();
        long gen = generation.incrementAndGet();
        if (!mustWait(link(app), launchRecent(), 0)) {
            pending = false;
            deliver(ctx, why, actions);
            return;
        }
        pending = true;
        L.life("AA: cierro Android Auto (" + why + ") en cuanto termine su handshake en curso: cortarlo a medias"
                + " bloquearía su servidor");
        new Thread(() -> {
            long start = SystemClock.elapsedRealtime();
            Link l = link(app);
            while (generation.get() == gen && mustWait(l, launchRecent(), SystemClock.elapsedRealtime() - start)) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                l = link(app);
            }
            if (generation.get() != gen) return;
            pending = false;
            long waited = SystemClock.elapsedRealtime() - start;
            if (l == Link.DIALING || l == Link.HANDSHAKE) {
                L.lifeWarn("AA: tras " + waited + " ms el handshake sigue a medias (Android Auto no contesta: su servidor"
                        + " ya estaba bloqueado); cierro igualmente (" + why + ")");
            } else {
                L.life("AA: " + (l == Link.SESSION ? "handshake terminado" : "sin conexión") + " a los " + waited
                        + " ms; cierro Android Auto (" + why + ")" + (l == Link.SESSION ? " con su ByeBye" : ""));
            }
            deliver(app, why, actions);
        }, "aa-clean-close").start();
    }

    private static void deliver(Context ctx, String why, String... actions) {
        for (String a : actions) {
            try {
                ctx.startService(new Intent(ctx, AapService.class).setAction(a));
            } catch (RuntimeException e) {
                L.e("AA: no se pudo cerrar Android Auto (" + why + ")", e);
            }
        }
    }

    private static Link link(Context app) {
        try {
            return linkOf(App.Companion.provide(app).getCommManager().getConnectionState().getValue());
        } catch (RuntimeException e) {
            return Link.NONE;
        }
    }

    private static boolean launchRecent() {
        long at = lastLaunchMs;
        return at >= 0 && SystemClock.elapsedRealtime() - at < LAUNCH_GRACE_MS;
    }
}
