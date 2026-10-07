package com.headqlink.link;

/**
 * Arranque manual del servidor de Android Auto (sin accesibilidad): los intentos de conexión de verdad. Puro (sin
 * Android) y con el reloj inyectable: lo prueban los tests. Lo usa {@link AaServerManual}, que mira el móvil cada
 * {@link #TICK_MS} mientras hay un intento o un reintento pendiente y hace lo que dice cada {@link Step}.
 *
 * Por qué no se sondea nunca (S25 Ultra, Android Auto 17.7, 2026-10-06, docs §15): el servidor de head unit de
 * desarrollador de AA vuelve a atender después de una sesión cerrada con orden (ByeBye), pero una conexión cortada a
 * medias lo bloquea: una sonda que abre el TCP y lo cierra sin mandar nada (AA anota «Head unit connected» y luego
 * «end of stream, dataReceived=false») lo deja aceptando el TCP en el socket que escucha sin contestar a nadie, hasta
 * que se para y se vuelve a iniciar. Que el TCP conecte no dice nada: lo único que dice que AA atiende es que conteste.
 * Así que el único intento es el del Self-Mode cuando una sesión con el coche necesita Android Auto, y cuenta como
 * «servido» en cuanto AA manda sus primeros bytes (lo primero del handshake, la respuesta de versión; o, por si se
 * pierde ese aviso, el handshake terminado).
 *
 * <pre>
 *  REPOSO ──la sesión necesita AA──▶ INTENTO ──AA contesta──▶ REPOSO (servido; fuera el aviso)
 *     ▲                                 │ rechazado · cerrado sin respuesta · 6 s con el TCP abierto y sin respuesta
 *     │                                 │ · 30 s sin llegar a conectar
 *     │                                 ▼
 *     └──la sesión ya no necesita AA── ESPERA ──5 s──▶ INTENTO   (aviso «Arranca (o vuelve a arrancar)…» al primer fallo)
 * </pre>
 *
 * Con AA servido y la sesión usándolo, se mira cada {@link #WATCH_MS} que siga conectado: si desaparece más de
 * {@link #DROP_GRACE_MS} (su servidor parado, AA actualizado o cerrado solo), un intento nuevo, que avisará si hace falta.
 *
 * Nunca se cierra un intento al que AA ya ha contestado: el plazo de 6 s se mira contra el contador de respuestas, que
 * solo crece, y {@link Step#answersAtDecision} deja comprobarlo otra vez justo antes de cerrar. Lo único que se cierra
 * sin ByeBye es un intento al que AA no ha dicho nada en 6 s: su servidor ya estaba bloqueado y no hay sesión que cerrar
 * con orden (el ByeBye va cifrado, tras el handshake).
 */
final class AaServeAttempts {
    /** Reloj monótono en milisegundos (en el móvil, SystemClock.elapsedRealtime). */
    interface Clock {
        long nowMs();
    }

    /** Con el TCP abierto, lo que tiene AA para contestar (en local contesta en milisegundos). */
    static final long SERVE_TIMEOUT_MS = 6_000;
    /** Entre un intento fallido y el siguiente, mientras la sesión con el coche siga necesitando AA. */
    static final long RETRY_MS = 5_000;
    /** Sin llegar a abrir el TCP (el Self-Mode no marcó, o se entretuvo en otras vías): intento fallido. */
    static final long DIAL_TIMEOUT_MS = 30_000;
    /** La sesión deja de necesitar AA al menos esto (no un parpadeo al rehacer el vídeo): se deja de reintentar. */
    static final long STOP_GRACE_MS = 3_000;
    /** De vuelta en HeadQLink (quizá de los ajustes de AA) con un reintento pendiente: se adelanta a esto. */
    static final long SOON_MS = 500;
    /** Cada cuánto se mira el móvil con un intento o un reintento pendiente. */
    static final long TICK_MS = 250;
    /** Con AA servido y la sesión usándolo, cada cuánto se mira si sigue conectado. */
    static final long WATCH_MS = 2_000;
    /** AA desaparece con la sesión en marcha al menos esto (no el hueco de un cambio de ajustes): intento nuevo. */
    static final long DROP_GRACE_MS = 3_000;

    enum Phase {
        /** Nada pendiente: AA servido, o ninguna sesión lo necesita. */
        IDLE,
        /** Self-Mode lanzado: esperando a que AA conteste. */
        ATTEMPT,
        /** Intento fallido: el siguiente a los {@link #RETRY_MS} (aviso puesto). */
        RETRY_WAIT,
    }

    /** Por qué un intento no fue servido. */
    enum Miss {
        /** 127.0.0.1:5277 rechaza la conexión: el servidor está apagado. */
        REFUSED,
        /** El TCP se abrió y se cerró sin que AA contestara (servidor parado o reiniciándose). */
        DROPPED,
        /** TCP abierto y {@link #SERVE_TIMEOUT_MS} sin respuesta: el servidor está bloqueado (una conexión se cortó a medias). */
        NO_ANSWER,
        /** {@link #DIAL_TIMEOUT_MS} sin llegar a abrir el TCP. */
        NO_DIAL,
    }

    enum Kind {
        /** Nada que hacer (quizá un motivo para el log). */
        NONE,
        /** Lanzar el Self-Mode: intento nuevo. */
        LAUNCH,
        /** AA contestó: servido. */
        SERVED,
        /** Intento no servido: cerrarlo si quedó abierto, avisar si es el primer fallo y reintentar si hace falta. */
        MISSED,
        /** Se deja de reintentar (sin sesión que necesite AA, el enlace se cierra o cambia el ajuste). */
        STOP,
    }

    /** Lo que se ve del móvil ahora. */
    static final class Seen {
        /** El enlace está en marcha y el vídeo de una sesión con el coche espera a Android Auto. */
        boolean sessionWantsAa;
        /** Hay conexión TCP con AA (la nuestra: CommManager.isConnected). */
        boolean tcpUp;
        /** CommManager está abriendo la conexión. */
        boolean connecting;
        /** Handshake terminado (SSL) o transporte en marcha: servido aunque se perdiera el aviso de la respuesta. */
        boolean handshakeDone;
        /** Respuestas de AA vistas desde que arrancó el proceso (solo crece: CommManager.peerAnswers). */
        long answers;
        /** Marcaciones del Self-Mode a 127.0.0.1:5277 con el TCP aceptado (solo crece). */
        long dials;
        /** Marcaciones rechazadas (solo crece). */
        long refusals;
        /**
         * AA se corta solo a los pocos segundos de conectar (AaFlapWatch): lo que debe esperar aún un intento nuevo tras
         * un corte en marcha (0: ya puede). Así nunca hay un bucle de relanzamientos más rápido que cada 10 s.
         */
        long relaunchHoldMs;

        Seen wants(boolean v) {
            sessionWantsAa = v;
            return this;
        }

        Seen tcp(boolean v) {
            tcpUp = v;
            return this;
        }

        Seen connecting(boolean v) {
            connecting = v;
            return this;
        }

        Seen handshake(boolean v) {
            handshakeDone = v;
            return this;
        }

        Seen answers(long v) {
            answers = v;
            return this;
        }

        Seen dials(long v) {
            dials = v;
            return this;
        }

        Seen refusals(long v) {
            refusals = v;
            return this;
        }

        Seen hold(long v) {
            relaunchHoldMs = v;
            return this;
        }
    }

    /** Qué hacer ahora, con el motivo para el log (null si no hay nada que contar). */
    static final class Step {
        final Kind kind;
        /** Número del intento desde el último servido (o desde el reposo). */
        final int attempt;
        final Miss miss;
        /** MISSED con el TCP abierto: cerrar esa conexión (si AA no ha contestado mientras tanto). */
        final boolean tearDown;
        /** El contador de respuestas cuando se decidió: si ha cambiado al ir a cerrar, AA contestó y no se cierra. */
        final long answersAtDecision;
        /** MISSED, primer fallo: poner el aviso «Arranca (o vuelve a arrancar) el servidor de Android Auto». */
        final boolean notice;
        /** SERVED o STOP (o MISSED sin reintento) con el aviso puesto: quitarlo. */
        final boolean dismiss;
        /** MISSED: habrá otro intento a los {@link #RETRY_MS}. */
        final boolean retry;
        final String reason;

        private Step(Kind kind, int attempt, Miss miss, boolean tearDown, long answersAtDecision, boolean notice,
                     boolean dismiss, boolean retry, String reason) {
            this.kind = kind;
            this.attempt = attempt;
            this.miss = miss;
            this.tearDown = tearDown;
            this.answersAtDecision = answersAtDecision;
            this.notice = notice;
            this.dismiss = dismiss;
            this.retry = retry;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return kind + (attempt > 0 ? "#" + attempt : "") + (miss != null ? "/" + miss : "")
                    + (tearDown ? " cerrar" : "") + (notice ? " avisar" : "") + (dismiss ? " quitar-aviso" : "")
                    + (retry ? " reintentar" : "") + (reason != null ? " [" + reason + "]" : "");
        }
    }

    private static final Step QUIET = new Step(Kind.NONE, 0, null, false, 0, false, false, false, null);

    private final Clock clock;
    private Phase phase = Phase.IDLE;
    private int attempt;
    private long launchedAt = -1;
    private long tcpAt = -1;
    private long retryAt = -1;
    private long unwantedSince = -1;
    private long answersBase;
    private long dialsBase;
    private long refusalsBase;
    /** El aviso está puesto: intentos fallando desde el último servido. */
    private boolean waiting;
    /** REPOSO con AA servido y una sesión que lo usa: se mira cada {@link #WATCH_MS} que siga conectado. */
    private boolean watching;
    private long lostSince = -1;
    /** Ya se dijo en el log que el intento nuevo espera por los cortes cortos ({@link Seen#relaunchHoldMs}). */
    private boolean holdNoted;

    AaServeAttempts(Clock clock) {
        this.clock = clock;
    }

    Phase phase() {
        return phase;
    }

    /** Aviso puesto: los intentos fallan (la fila «Auto» y el widget dicen «Esperando al servidor de Android Auto»). */
    boolean waiting() {
        return waiting;
    }

    /** Hay algo pendiente: hay que llamar a {@link #tick} cada {@link #TICK_MS}. */
    boolean active() {
        return phase != Phase.IDLE;
    }

    /**
     * Cuándo volver a llamar a {@link #tick}: {@link #TICK_MS} con un intento o un reintento pendiente,
     * {@link #WATCH_MS} vigilando que AA siga conectado con la sesión en marcha, o -1 (nada que mirar).
     */
    long tickDelayMs() {
        if (phase != Phase.IDLE) return TICK_MS;
        return watching ? WATCH_MS : -1;
    }

    /** Intentos desde el último servido. */
    int attempt() {
        return attempt;
    }

    /** Cuándo toca el próximo reintento (reloj del {@link Clock}), o -1. */
    long retryAtMs() {
        return phase == Phase.RETRY_WAIT ? retryAt : -1;
    }

    /**
     * Una sesión con el coche necesita Android Auto (arranca o vuelve el vídeo de AA). Si AA no está conectado, un
     * intento; si lo está, solo se vigila que siga.
     */
    Step need(Seen s, String why) {
        long now = clock.nowMs();
        switch (phase) {
            case ATTEMPT:
                return none(why + ": el intento " + attempt + " sigue en curso; no lanzo otro");
            case RETRY_WAIT:
                unwantedSince = -1;
                return none(why + ": ya lo reintento cada " + RETRY_MS / 1000 + " s (el próximo, en "
                        + secs(retryAt - now) + ")");
            default:
                if (s.tcpUp || s.connecting) {
                    watching = true;
                    lostSince = -1;
                    return QUIET;
                }
                return launch(now, s, why);
        }
    }

    /** Lo que toca ahora (cada {@link #tickDelayMs}, y cuando el Self-Mode avisa de su marcación). */
    Step tick(Seen s) {
        long now = clock.nowMs();
        switch (phase) {
            case ATTEMPT:
                return tickAttempt(now, s);
            case RETRY_WAIT:
                return tickRetry(now, s);
            default:
                return tickWatch(now, s);
        }
    }

    /** De vuelta en HeadQLink (o al desbloquear) con un reintento pendiente: se adelanta. */
    Step soon() {
        if (phase != Phase.RETRY_WAIT) return QUIET;
        long now = clock.nowMs();
        if (retryAt - now <= SOON_MS) return QUIET;
        retryAt = now + SOON_MS;
        return none("de vuelta en HeadQLink: adelanto el reintento");
    }

    /** Se deja todo (el enlace se cierra, cambia el ajuste, modo sin AA): quitar el aviso si estaba. */
    Step reset(String why) {
        if (phase == Phase.IDLE && !waiting) {
            watching = false;
            lostSince = -1;
            return QUIET;
        }
        return stop(why);
    }

    // ---------------------------------------------------------------- transiciones

    private Step tickAttempt(long now, Seen s) {
        if (s.answers > answersBase || s.handshakeDone) return served(now);
        if (s.tcpUp) {
            if (tcpAt < 0) {
                tcpAt = now;
                return none("intento " + attempt + ": 127.0.0.1:5277 acepta el TCP a los " + ms(now - launchedAt)
                        + "; espero a que Android Auto conteste (como mucho " + SERVE_TIMEOUT_MS / 1000 + " s)");
            }
            if (now - tcpAt >= SERVE_TIMEOUT_MS) return missed(now, s, Miss.NO_ANSWER, true);
            return QUIET;
        }
        if (s.refusals > refusalsBase) return missed(now, s, Miss.REFUSED, false);
        if ((tcpAt >= 0 || s.dials > dialsBase) && !s.connecting) return missed(now, s, Miss.DROPPED, false);
        if (now - launchedAt >= DIAL_TIMEOUT_MS) return missed(now, s, Miss.NO_DIAL, s.connecting);
        return QUIET;
    }

    /**
     * AA servido y la sesión usándolo: si AA desaparece (se paró su servidor, se actualizó o se cerró solo), un intento
     * nuevo, que avisará si el servidor ya no atiende. Sin sesión que lo use, se deja de mirar.
     */
    private Step tickWatch(long now, Seen s) {
        if (!watching) return QUIET;
        if (!s.sessionWantsAa) {
            watching = false;
            lostSince = -1;
            return QUIET;
        }
        if (s.tcpUp || s.connecting) {
            lostSince = -1;
            return QUIET;
        }
        if (lostSince < 0) lostSince = now;
        if (now - lostSince < DROP_GRACE_MS) return QUIET;
        if (s.relaunchHoldMs > 0) {
            if (holdNoted) return QUIET;
            holdNoted = true;
            return none("Android Auto se corta a los pocos segundos de conectar: el intento nuevo espera "
                    + secs(s.relaunchHoldMs) + " (como mucho uno cada " + AaFlapDetector.MIN_RELAUNCH_GAP_MS / 1000 + " s)");
        }
        return launch(now, s, "Android Auto se desconectó con la sesión en marcha");
    }

    private Step tickRetry(long now, Seen s) {
        // AA contestó tarde (tras darlo por perdido, sin llegar a cerrarlo) o lo conectó otro camino: servido.
        if (s.tcpUp && (s.handshakeDone || s.answers > answersBase)) return served(now);
        if (!s.sessionWantsAa) {
            if (unwantedSince < 0) unwantedSince = now;
            if (now - unwantedSince >= STOP_GRACE_MS) return stop("la sesión con el coche ya no necesita Android Auto");
            return QUIET;
        }
        unwantedSince = -1;
        if (now >= retryAt && !s.tcpUp && !s.connecting) return launch(now, s, "reintento");
        return QUIET;
    }

    private Step launch(long now, Seen s, String why) {
        holdNoted = false;
        phase = Phase.ATTEMPT;
        attempt++;
        launchedAt = now;
        tcpAt = -1;
        retryAt = -1;
        unwantedSince = -1;
        answersBase = s.answers;
        dialsBase = s.dials;
        refusalsBase = s.refusals;
        return new Step(Kind.LAUNCH, attempt, null, false, s.answers, false, false, false,
                "intento " + attempt + " (" + why + "): lanzo Android Auto (Self-Mode) contra 127.0.0.1:5277; cuenta como"
                        + " servido cuando Android Auto conteste (aceptar el TCP no basta)");
    }

    private Step served(long now) {
        int n = attempt;
        boolean wasWaiting = waiting;
        long since = now - launchedAt;
        toIdle();
        watching = true;
        return new Step(Kind.SERVED, n, null, false, 0, false, wasWaiting, false,
                "intento " + n + ": servido: Android Auto contestó (intento lanzado hace " + ms(since) + ")"
                        + (wasWaiting ? "; quito el aviso" : ""));
    }

    private Step missed(long now, Seen s, Miss miss, boolean tearDown) {
        int n = attempt;
        long since = now - launchedAt;
        boolean retry = s.sessionWantsAa;
        boolean notice = retry && !waiting;
        boolean dismiss = !retry && waiting;
        String what = "intento " + n + ": no servido (a los " + ms(since) + "): " + missText(miss)
                + (tearDown ? "; cierro esa conexión" : "");
        if (retry) {
            phase = Phase.RETRY_WAIT;
            retryAt = now + RETRY_MS;
            unwantedSince = -1;
            waiting = true;
            what += "; reintento en " + RETRY_MS / 1000 + " s"
                    + (notice ? "; aviso «Arranca (o vuelve a arrancar) el servidor de Android Auto»" : "");
        } else {
            toIdle();
            what += "; no reintento: la sesión con el coche ya no necesita Android Auto" + (dismiss ? "; quito el aviso" : "");
        }
        return new Step(Kind.MISSED, n, miss, tearDown, s.answers, notice, dismiss, retry, what);
    }

    private Step stop(String why) {
        int n = attempt;
        boolean wasWaiting = waiting;
        toIdle();
        return new Step(Kind.STOP, n, null, false, 0, false, wasWaiting, false,
                "dejo de intentarlo: " + why + (wasWaiting ? "; quito el aviso" : ""));
    }

    private void toIdle() {
        phase = Phase.IDLE;
        attempt = 0;
        waiting = false;
        watching = false;
        lostSince = -1;
        holdNoted = false;
        launchedAt = -1;
        tcpAt = -1;
        retryAt = -1;
        unwantedSince = -1;
    }

    private static Step none(String reason) {
        return new Step(Kind.NONE, 0, null, false, 0, false, false, false, reason);
    }

    static String missText(Miss m) {
        switch (m) {
            case REFUSED:
                return "127.0.0.1:5277 rechaza la conexión (el servidor de Android Auto está apagado)";
            case DROPPED:
                return "la conexión se cerró sin respuesta de Android Auto (servidor parado o reiniciándose)";
            case NO_ANSWER:
                return "127.0.0.1:5277 aceptó el TCP y en " + SERVE_TIMEOUT_MS / 1000 + " s no llegó respuesta: Android"
                        + " Auto no contesta: su servidor está bloqueado (pasa si una conexión se cortó a medias); páralo y"
                        + " vuelve a iniciarlo";
            default:
                return "el Self-Mode no llegó a conectar en " + DIAL_TIMEOUT_MS / 1000 + " s";
        }
    }

    private static String ms(long v) {
        return Math.max(0, v) + " ms";
    }

    private static String secs(long v) {
        return Math.max(0, (v + 999) / 1000) + " s";
    }
}
