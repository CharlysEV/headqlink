package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ciclo de vida del enlace con el coche y de Android Auto, sin Android (lo prueban los tests). LinkService le pasa cada
 * evento (arranque, coche anunciado, sesión, coche perdido, vencimiento del temporizador, Bluetooth del coche fuera) con
 * lo que ve del móvil ({@link Env}) y ejecuta, en orden, las acciones que devuelve. Cada decisión lleva sus motivos, que
 * van al log unificado («ciclo: …»). Los instantes son milisegundos de un reloj monótono; el temporizador lo pone
 * LinkService en {@link #deadlineMs()}.
 *
 * <pre>
 * BUSCANDO ──sesión──▶ CONECTADO ──coche perdido──▶ VÍDEO VIVO (car_gone_ms, 30 s)
 *                          ▲                              │ vence
 *                          │ sesión: se reanuda al        ▼
 *                          └──── instante ──────── AA EN PAUSA (sin vídeo; el enlace sigue escuchando)
 *                                                         │ vence «Esperar al coche» (5 min)
 *                                                         ▼
 *                                                      CERRADO (el cierre de siempre)
 * </pre>
 *
 * «Sin coche» es sin anuncios del coche: cada Connect_Broadcast (aunque no llegue a haber TCP) vuelve a contar la
 * búsqueda o «Esperar al coche» desde ese momento ({@link #carHeard}). Antes solo contaba la sesión, y con el coche
 * anunciándose sin conectar se cerraba todo igualmente al vencer (90 s después de verlo, en un caso real).
 *
 * Seguridad: sin coche, el servidor de head unit de AA sigue encendido como mucho lo que dure «Esperar al coche»; al
 * vencer, el cierre de siempre lo apaga (ya o, con el móvil bloqueado, al desbloquearlo, con AA aparcado mientras).
 */
final class LinkLifecycle {
    /** Sin coche desde el arranque, se busca al menos esto (o «Esperar al coche», si es más). */
    static final long SEARCH_MIN_MS = 5 * 60_000L;
    /** Al vencer una espera con una sesión o un intento en marcha, se vuelve a mirar en este tiempo. */
    static final long RECHECK_MS = 5_000L;
    /** «Coche anunciado: vuelvo a contar…» en el log como mucho una vez cada tanto (el coche se anuncia cada pocos s). */
    static final long HEARD_LOG_MS = 60_000L;

    enum Phase {
        /** Buscando al coche desde el arranque (aún no hubo sesión). */
        SEARCHING,
        CONNECTED,
        /** Coche perdido: el vídeo (y AA) siguen vivos durante car_gone_ms, para una reconexión sin cortes. */
        GRACE,
        /** Coche perdido más tiempo: sin vídeo, AA en pausa (aparcado) y el enlace escuchando hasta «Esperar al coche». */
        PARKED,
        CLOSED,
    }

    /** Quién arranca el servicio. USB: el coche conectó el cable y puso el móvil en modo accesorio (está delante). */
    enum Trigger { USER, BLUETOOTH, USB, OTHER }

    enum Action {
        /** AaGuardService tenía AA aparcado (servicio parado): pasa al enlace, que lo mantiene aparcado (ping). */
        ADOPT_PARK,
        /** Olvidar el apagado pendiente del servidor de AA (no se abrirán sus ajustes al desbloquear). */
        CANCEL_PENDING_STOP,
        /** AA sale de la pausa: sigue conectado y el vídeo de la sesión nueva le devuelve el foco. Sin arrancar nada. */
        RESUME_AA,
        /** AA conectado y sin vídeo: foco de vídeo nativo, ping y sin vista en el móvil. Va antes de STOP_VIDEO. */
        PARK_AA,
        /** Parar el vídeo (pipeline y encoder): sin coche no se codifica nada. */
        STOP_VIDEO,
        /** Arrancar ya el servidor de head unit de AA (móvil desbloqueado; automatización tras la capa). */
        START_SERVER,
        /** Móvil bloqueado: aviso «Desbloquea el móvil para iniciar Android Auto» y arranque al desbloquear. */
        START_SERVER_ON_UNLOCK,
        /** Cerrarlo todo por el camino de siempre (ver {@link #shutdownPlan}). */
        SHUTDOWN,
    }

    /** Cómo se cierra Android Auto al cerrarlo todo. */
    enum ShutdownPlan {
        /** Modo sin Android Auto: solo el enlace. */
        LINK_ONLY,
        /** Móvil bloqueado con AA conectado: AA aparcado (AaGuardService) y el servidor se apaga al desbloquear. */
        PARK_UNTIL_UNLOCK,
        /** Se para AA y se apaga el servidor: ya, o al desbloquear si ahora no se puede. */
        STOP_SERVER,
        /** Se para AA; el servidor solo si se puede ahora sin que se note (ajuste stop_aa_server desactivado). */
        STOP_SERVER_IF_UNLOCKED,
    }

    /** Lo que se ve del móvil en el momento del evento. */
    static final class Env {
        /** Modo con Android Auto (aa o aa_ext). */
        boolean aaMode;
        boolean locked;
        /** Nuestra head unit conectada a AA. */
        boolean aaConnected;
        /** AA aparcado: conectado, sin vídeo y con el foco en nativo. */
        boolean aaParked;
        /** AaGuardService tiene AA aparcado (el servicio del enlace estaba parado). */
        boolean guardActive;
        /** Apagado del servidor de AA pendiente (se haría al desbloquear). */
        boolean stopPending;
        /** El servidor de AA está encendido (AA conectado, o el último arranque/parada conocido). */
        boolean serverOn;
        /** Hay una sesión o un intento (ACK enviado, esperando el TCP) con el coche. */
        boolean linkBusy;
        /** La accesibilidad está activa (sin ella no se puede arrancar ni parar el servidor). */
        boolean canAutomate = true;
        /** Se guardó el botón «Detener» de la notificación del servidor: se puede apagar sin abrir sus ajustes. */
        boolean canStopWithoutUi;
        /** Ajuste «apagar el servidor al terminar» (por defecto, sí). */
        boolean stopServerOnExit = true;

        Env aa(boolean v) {
            aaMode = v;
            return this;
        }

        Env locked(boolean v) {
            locked = v;
            return this;
        }

        Env connected(boolean v) {
            aaConnected = v;
            return this;
        }

        Env parked(boolean v) {
            aaParked = v;
            return this;
        }

        Env guard(boolean v) {
            guardActive = v;
            return this;
        }

        Env stopPending(boolean v) {
            stopPending = v;
            return this;
        }

        Env serverOn(boolean v) {
            serverOn = v;
            return this;
        }

        Env busy(boolean v) {
            linkBusy = v;
            return this;
        }

        Env automate(boolean v) {
            canAutomate = v;
            return this;
        }

        Env stopWithoutUi(boolean v) {
            canStopWithoutUi = v;
            return this;
        }

        Env stopOnExit(boolean v) {
            stopServerOnExit = v;
            return this;
        }
    }

    /** Acciones (en orden, sin repetir) y motivos para el log. */
    static final class Decision {
        private final List<Action> actions = new ArrayList<>();
        private final List<String> reasons = new ArrayList<>();

        List<Action> actions() {
            return Collections.unmodifiableList(actions);
        }

        List<String> reasons() {
            return Collections.unmodifiableList(reasons);
        }

        boolean has(Action a) {
            return actions.contains(a);
        }

        boolean isEmpty() {
            return actions.isEmpty() && reasons.isEmpty();
        }

        private Decision add(Action a) {
            if (!actions.contains(a)) actions.add(a);
            return this;
        }

        private Decision why(String reason) {
            reasons.add(reason);
            return this;
        }

        @Override
        public String toString() {
            return actions + " " + reasons;
        }
    }

    private Phase phase = Phase.CLOSED;
    private long startedAt = -1;
    private long lostAt = -1;
    /** Próximo vencimiento (fin de la búsqueda, de VÍDEO VIVO o de la espera), o -1. */
    private long deadline = -1;
    /** Fin de la espera del coche (cierre), o -1. */
    private long closeAt = -1;
    /** Ya se pidió arrancar el servidor en esta búsqueda (un aviso, no uno por broadcast). */
    private boolean serverAsked;
    /** Último anuncio del coche (-1 = ninguno desde el arranque) y última vez que se dijo en el log. */
    private long heardAt = -1;
    private long heardLoggedAt = -1;
    /** «Esperar al coche» del último arranque o pérdida (para los anuncios sin el ajuste a mano). */
    private long lastWaitMs = SEARCH_MIN_MS;

    Phase phase() {
        return phase;
    }

    /** Cuándo hay que llamar a {@link #timer}, o -1 si no hay nada pendiente. */
    long deadlineMs() {
        return deadline;
    }

    /** Fin de la espera del coche (cierre), o -1. */
    long closeAtMs() {
        return closeAt;
    }

    /** Cómo se cierra Android Auto (el camino de siempre, con el móvil bloqueado o no). */
    static ShutdownPlan shutdownPlan(Env e) {
        if (!e.aaMode) return ShutdownPlan.LINK_ONLY;
        // Con el botón «Detener» de la notificación del servidor se apaga ya, bloqueado o no; sin él, con el móvil
        // bloqueado no se pueden manejar los ajustes de AA: se aparca la sesión hasta desbloquear.
        if (e.stopServerOnExit && e.locked && e.aaConnected && e.canAutomate && !e.canStopWithoutUi) {
            return ShutdownPlan.PARK_UNTIL_UNLOCK;
        }
        return e.stopServerOnExit ? ShutdownPlan.STOP_SERVER : ShutdownPlan.STOP_SERVER_IF_UNLOCKED;
    }

    /** El servicio arranca (Conectar, Bluetooth del coche…). waitMs: «Esperar al coche». */
    Decision start(long now, Trigger trigger, Env e, long waitMs) {
        Decision d = new Decision();
        if (phase != Phase.CLOSED) return d;
        long search = Math.max(SEARCH_MIN_MS, waitMs);
        phase = Phase.SEARCHING;
        startedAt = now;
        lostAt = -1;
        deadline = now + search;
        closeAt = deadline;
        serverAsked = false;
        heardAt = -1;
        heardLoggedAt = -1;
        lastWaitMs = Math.max(0, waitMs);
        d.why("arranque (" + triggerName(trigger) + "): busco al coche hasta " + dur(search));
        keepAaForTheCar(d, e, "arranque");
        if (trigger == Trigger.BLUETOOTH) askServer(d, e, "Bluetooth del coche");
        if (trigger == Trigger.USB) askServer(d, e, "cable USB del coche");
        return d;
    }

    /** Broadcast del coche (antes del ACK y del TCP), con «Esperar al coche» del último arranque o pérdida. */
    Decision carSeen(long now, Env e) {
        return carSeen(now, e, lastWaitMs);
    }

    /** Primer broadcast del coche (antes del ACK y del TCP): AA para él y, como cada anuncio, la espera desde ahora. */
    Decision carSeen(long now, Env e, long waitMs) {
        Decision d = carHeard(now, waitMs);
        if (phase == Phase.CLOSED) return d;
        keepAaForTheCar(d, e, "coche anunciado");
        askServer(d, e, "coche anunciado");
        return d;
    }

    /**
     * Cada broadcast del coche sin sesión (aunque no llegue a conectar): la búsqueda o «Esperar al coche» (waitMs)
     * vuelve a contar desde ahora; nunca acorta lo que quedaba. VÍDEO VIVO no se alarga (sin sesión no hay vídeo que
     * enviar), solo el cierre de después. Sin acciones: solo mueve el temporizador ({@link #deadlineMs()}).
     */
    Decision carHeard(long now, long waitMs) {
        Decision d = new Decision();
        if (phase == Phase.CLOSED || phase == Phase.CONNECTED) return d;
        heardAt = now;
        long wait = Math.max(0, waitMs);
        long until;
        switch (phase) {
            case SEARCHING:
                until = now + Math.max(SEARCH_MIN_MS, wait);
                if (until > closeAt) closeAt = until;
                deadline = closeAt;
                break;
            case GRACE:
                // Después de VÍDEO VIVO, la pausa sigue hasta el nuevo cierre (el temporizador del vídeo no cambia).
                until = now + wait;
                if (until > closeAt) closeAt = until;
                break;
            case PARKED:
                until = now + wait;
                if (until > closeAt) closeAt = until;
                deadline = closeAt;
                break;
            default:
                return d;
        }
        if (heardLoggedAt < 0 || now - heardLoggedAt >= HEARD_LOG_MS) {
            heardLoggedAt = now;
            d.why("coche anunciado sin sesión: vuelvo a contar " + (phase == Phase.SEARCHING ? "la búsqueda" : "la espera")
                    + " desde ahora; cierro todo si no se anuncia en " + dur(closeAt - now));
        }
        return d;
    }

    /** Sesión con el coche (TCP aceptado). */
    Decision carConnected(long now, Env e) {
        Decision d = new Decision();
        if (phase == Phase.CLOSED) return d;
        Phase was = phase;
        String after = lostAt >= 0 ? " tras " + dur(now - lostAt) + " sin coche" : "";
        phase = Phase.CONNECTED;
        deadline = -1;
        closeAt = -1;
        lostAt = -1;
        serverAsked = false;
        keepAaForTheCar(d, e, "sesión con el coche");
        if (e.aaParked || e.guardActive) {
            d.add(Action.RESUME_AA).why("sesión con el coche" + after
                    + ": Android Auto sale de la pausa al instante (sin arrancar el servidor ni desbloquear)");
        } else if (was == Phase.GRACE) {
            d.why("sesión con el coche" + after + ": el vídeo seguía vivo");
        } else {
            d.why("sesión con el coche" + after);
        }
        return d;
    }

    /** Terminó la sesión con el coche. graceMs: vídeo vivo (0 = no hay vídeo que mantener); waitMs: «Esperar al coche». */
    Decision carLost(long now, Env e, long graceMs, long waitMs) {
        Decision d = new Decision();
        // Solo la pérdida de una sesión cuenta; otra pérdida durante la espera no la alarga.
        if (phase != Phase.CONNECTED) return d;
        long grace = Math.max(0, graceMs);
        long total = Math.max(Math.max(0, waitMs), grace);
        lostAt = now;
        closeAt = now + total;
        serverAsked = false;
        lastWaitMs = Math.max(0, waitMs);
        if (grace > 0) {
            phase = Phase.GRACE;
            deadline = now + grace;
            d.why("coche perdido: vídeo vivo " + dur(grace) + (total > grace
                    ? "; después Android Auto en pausa y sigo esperando al coche hasta " + dur(total) + " en total"
                    : "; después cierro todo"));
        } else {
            phase = Phase.PARKED;
            deadline = closeAt;
            pause(d, e);
            d.why("coche perdido: " + (d.has(Action.PARK_AA) ? "Android Auto en pausa (sin vídeo)" : "sin vídeo")
                    + " y sigo esperando al coche " + dur(total));
        }
        return d;
    }

    /** Vencimiento del temporizador ({@link #deadlineMs()}). */
    Decision timer(long now, Env e) {
        Decision d = new Decision();
        if (deadline < 0 || now < deadline) return d;
        if (phase == Phase.CONNECTED || phase == Phase.CLOSED) {
            deadline = -1;
            return d;
        }
        if (e.linkBusy) {
            // El coche ha vuelto (o está conectando) justo ahora: no se toca nada.
            deadline = now + RECHECK_MS;
            return d.why(phaseName() + " vencida con una sesión o un intento en marcha: vuelvo a mirar en "
                    + dur(RECHECK_MS));
        }
        switch (phase) {
            case SEARCHING:
                close();
                return d.add(Action.SHUTDOWN).why((heardAt >= 0
                        ? "sin sesión en " + dur(now - startedAt) + " desde el arranque y sin anuncios del coche desde hace "
                        + dur(now - heardAt) : "sin coche en " + dur(now - startedAt) + " desde el arranque") + ": cierro todo");
            case GRACE:
                if (now < closeAt) {
                    phase = Phase.PARKED;
                    deadline = closeAt;
                    pause(d, e);
                    return d.why(dur(now - lostAt) + " sin coche: paro el vídeo"
                            + (d.has(Action.PARK_AA) ? " y dejo Android Auto en pausa" : "")
                            + "; sigo escuchando al coche " + dur(closeAt - now) + " más");
                }
                // VÍDEO VIVO cubría toda la espera.
                String g = goneText(now);
                close();
                return d.add(Action.SHUTDOWN).why("espera del coche vencida (" + g + "): cierro todo");
            case PARKED:
                String gone = goneText(now);
                close();
                return d.add(Action.SHUTDOWN).why("espera del coche vencida (" + gone + "): cierro todo");
            default:
                return d;
        }
    }

    /** El Bluetooth del coche se ha ido. */
    Decision btGone(long now, Env e) {
        Decision d = new Decision();
        switch (phase) {
            case SEARCHING:
                close();
                return d.add(Action.SHUTDOWN).why("Bluetooth del coche fuera sin haber conectado: cierro todo");
            case CONNECTED:
                return d.why("Bluetooth del coche fuera con la sesión en marcha: sigo (se cierra si el coche se va)");
            case GRACE:
            case PARKED:
                // Apagar el coche se lleva el Bluetooth y la sesión a la vez: justo lo que cubre «Esperar al coche».
                return d.why("Bluetooth del coche fuera: sigo esperando al coche " + dur(Math.max(0, closeAt - now)) + " más");
            default:
                return d;
        }
    }

    /** «5 min sin coche», o «7 min sin sesión, 5 min sin anuncios» si el coche se anunció después de perderlo. */
    private String goneText(long now) {
        if (heardAt > lostAt) return dur(now - lostAt) + " sin sesión, " + dur(now - heardAt) + " sin anuncios del coche";
        return dur(now - lostAt) + " sin coche";
    }

    /** El servicio se cierra (Desconectar, o el cierre de una espera vencida): nada más pendiente. */
    void close() {
        phase = Phase.CLOSED;
        deadline = -1;
        closeAt = -1;
    }

    /** Con el coche delante o recién arrancado: AA se queda para él (sin cerrar ni apagar su servidor). */
    private void keepAaForTheCar(Decision d, Env e, String why) {
        if (e.guardActive) {
            d.add(Action.ADOPT_PARK).why(why + ": Android Auto estaba aparcado por el guardián; lo mantengo para el coche");
        }
        if (e.stopPending) {
            d.add(Action.CANCEL_PENDING_STOP).why(why + ": anulo el apagado pendiente del servidor de Android Auto");
        }
    }

    /** Sin coche: AA en pausa (si está conectado) y el vídeo parado. */
    private static void pause(Decision d, Env e) {
        if (e.aaMode && e.aaConnected) d.add(Action.PARK_AA);
        d.add(Action.STOP_VIDEO);
    }

    /** Si el servidor de AA hará falta y no consta encendido: arrancarlo ya, o al desbloquear. Una vez por búsqueda. */
    private void askServer(Decision d, Env e, String why) {
        if (serverAsked || !e.aaMode || e.aaConnected || e.aaParked || e.guardActive || e.serverOn) return;
        serverAsked = true;
        if (!e.canAutomate) {
            d.why(why + ": el servidor de Android Auto puede estar apagado y falta la accesibilidad para arrancarlo");
        } else if (e.locked) {
            d.add(Action.START_SERVER_ON_UNLOCK).why(why + " con el móvil bloqueado: aviso «desbloquea el móvil» y arranco"
                    + " el servidor de Android Auto al desbloquear");
        } else {
            d.add(Action.START_SERVER).why(why + ": arranco ya el servidor de Android Auto");
        }
    }

    private String phaseName() {
        switch (phase) {
            case SEARCHING:
                return "búsqueda del coche";
            case GRACE:
                return "espera con el vídeo vivo";
            case PARKED:
                return "espera del coche";
            default:
                return phase.name();
        }
    }

    private static String triggerName(Trigger t) {
        switch (t) {
            case USER:
                return "Conectar";
            case BLUETOOTH:
                return "Bluetooth del coche";
            case USB:
                return "cable USB del coche";
            default:
                return "otro";
        }
    }

    /** «45 s» o «5 min». */
    static String dur(long ms) {
        if (ms < 120_000) return Math.max(0, (ms + 500) / 1000) + " s";
        return (ms + 30_000) / 60_000 + " min";
    }
}
