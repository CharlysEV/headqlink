package com.headqlink.link;

/**
 * Qué hacer con el servidor de head unit de Android Auto (127.0.0.1:5277, AA 17.4+) cuando hace falta y al terminar, sin
 * Android (lo prueban los tests): modo de arranque × estado del servidor × móvil bloqueado × motivo.
 *
 * - **Automático** (por defecto, recomendado): la accesibilidad pulsa el menú de desarrollador de AA para arrancarlo
 *   ({@link Action#AUTOMATE}) y, al terminar, para apagarlo. Con el móvil bloqueado no se pueden manejar los ajustes de
 *   AA: aviso «Desbloquea el móvil…» y arranque al desbloquear ({@link Action#AUTOMATE_ON_UNLOCK}). Es el comportamiento
 *   de siempre; LinkLifecycle, HomeActivity y AaServerStarter lo siguen igual que antes.
 * - **Manual** (sin accesibilidad): nunca se pulsa nada. Se mira si el puerto contesta ({@link Action#PROBE}, fuera del
 *   hilo principal); si está apagado se le pide al usuario con una notificación y se sigue mirando cada 2 s
 *   ({@link Action#ASK_USER}). Bloqueado o no, es lo mismo: la notificación se ve en la pantalla de bloqueo y, si el
 *   servidor ya está encendido, el arranque por Bluetooth o por cable no necesita desbloquear. Al terminar, el servidor
 *   se queda encendido: se avisa una vez ({@link End#LEAVE_ON_NOTICE}).
 */
final class AaServerPolicy {
    /** Lo que se sabe del servidor: contesta, no contesta, o no se ha mirado (o el dato no vale para decidir). */
    enum Server { UP, DOWN, UNKNOWN }

    /** Por qué hace falta el servidor. */
    enum Need {
        /** «Conectar» en la pantalla principal (el usuario está delante, con el móvil desbloqueado). */
        CONNECT,
        /** El enlace arranca por el Bluetooth del coche (conexión automática). */
        BLUETOOTH,
        /** El enlace arranca por el cable USB del coche (modo accesorio). */
        USB,
        /** Primer anuncio del coche (Connect_Broadcast) en una búsqueda. */
        CAR_SEEN,
        /** Una sesión necesita Android Auto y 127.0.0.1:5277 no contestó (Self-Mode). */
        SESSION,
        /** Vuelve el coche con Android Auto en pausa, pero AA ya no está conectado. */
        RESUME,
        /** Vigilancia periódica mientras el enlace espera al coche (solo en el modo manual). */
        WATCH,
    }

    enum Action {
        /** Nada que hacer: el servidor contesta (o AA está conectado), o no toca mirarlo. */
        NONE,
        /** Automático: arrancarlo ya con la accesibilidad (tras la capa «Arrancando Auto…»). */
        AUTOMATE,
        /** Automático con el móvil bloqueado: aviso «Desbloquea el móvil…» y arranque al desbloquear. */
        AUTOMATE_ON_UNLOCK,
        /** Automático sin la accesibilidad activa: no se puede arrancar (solo se registra). */
        NO_ACCESSIBILITY,
        /** Manual sin dato fresco: mirar si 127.0.0.1:5277 contesta (fuera del hilo principal) y decidir otra vez. */
        PROBE,
        /** Manual con el servidor apagado: notificación «Arranca el servidor de Android Auto…» y mirar cada 2 s. */
        ASK_USER,
    }

    /** Al cerrar el enlace (Desconectar o fin del viaje). */
    enum End {
        /** Sin Android Auto: nada. Manual con el servidor ya apagado: nada que avisar. */
        NOTHING,
        /** Automático: el cierre de siempre (apagarlo ya o al desbloquear; LinkLifecycle.shutdownPlan). */
        STOP_SERVER,
        /** Manual: no se puede (ni se intenta) parar; un aviso «sigue encendido» con cómo pararlo. */
        LEAVE_ON_NOTICE,
    }

    /** Lo que se ve del móvil al decidir. */
    static final class State {
        /** «Arranque del servidor de Android Auto»: manual. */
        boolean manual;
        Server server = Server.UNKNOWN;
        /** Nuestra head unit está conectada a AA (o aparcada): el servidor está encendido. */
        boolean aaConnected;
        boolean locked;
        /** TouchService activo (solo cuenta en el automático). */
        boolean canAutomate = true;

        State manual(boolean v) {
            manual = v;
            return this;
        }

        State server(Server v) {
            server = v;
            return this;
        }

        State connected(boolean v) {
            aaConnected = v;
            return this;
        }

        State locked(boolean v) {
            locked = v;
            return this;
        }

        State automate(boolean v) {
            canAutomate = v;
            return this;
        }
    }

    /** Milisegundos entre comprobaciones con el servidor apagado y el usuario avisado. */
    static final long POLL_MS = 2_000;
    /** Milisegundos entre comprobaciones con el servidor encendido mientras se espera al coche (cada una toca a AA). */
    static final long WATCH_MS = 60_000;

    private AaServerPolicy() {
    }

    /** Qué hacer cuando el servidor hace falta por [need]. */
    static Action onNeed(Need need, State s) {
        // Con nuestra head unit conectada (o aparcada) el servidor está encendido, sea cual sea el modo.
        if (s.aaConnected) return Action.NONE;
        if (s.manual) {
            switch (s.server) {
                case UP:
                    return Action.NONE;
                case DOWN:
                    return Action.ASK_USER;
                default:
                    return Action.PROBE;
            }
        }
        // Automático: lo de siempre. La vigilancia periódica es solo del modo manual.
        if (need == Need.WATCH || s.server == Server.UP) return Action.NONE;
        if (!s.canAutomate) return Action.NO_ACCESSIBILITY;
        return s.locked ? Action.AUTOMATE_ON_UNLOCK : Action.AUTOMATE;
    }

    /** Qué hacer con el servidor al cerrar el enlace. */
    static End onEnd(boolean aaMode, State s) {
        if (!aaMode) return End.NOTHING;
        if (!s.manual) return End.STOP_SERVER;
        // Sin dato (no se pudo mirar) se avisa igualmente: mejor un aviso de más que un servidor abierto sin saberlo.
        return s.server == Server.DOWN ? End.NOTHING : End.LEAVE_ON_NOTICE;
    }

    /** Siguiente comprobación en el modo manual: cada 2 s esperando al usuario, cada minuto vigilando; -1 = ninguna. */
    static long nextCheckMs(boolean linkRunning, boolean manual, boolean aaMode, boolean waitingForUser) {
        if (!linkRunning || !manual || !aaMode) return -1;
        return waitingForUser ? POLL_MS : WATCH_MS;
    }

    static String needName(Need n) {
        switch (n) {
            case CONNECT:
                return "Conectar";
            case BLUETOOTH:
                return "Bluetooth del coche";
            case USB:
                return "cable USB del coche";
            case CAR_SEEN:
                return "coche anunciado";
            case SESSION:
                return "la sesión necesita Android Auto";
            case RESUME:
                return "vuelve el coche con Android Auto en pausa";
            default:
                return "vigilancia";
        }
    }
}
