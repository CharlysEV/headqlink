package com.headqlink.link;

/**
 * Qué hacer con el servidor de head unit de Android Auto (127.0.0.1:5277, AA 17.4+) cuando hace falta y al terminar, sin
 * Android (lo prueban los tests): modo de arranque × estado × móvil bloqueado × motivo.
 *
 * - **Automático** (por defecto, recomendado): la accesibilidad pulsa el menú de desarrollador de AA para arrancarlo
 *   ({@link Action#AUTOMATE}) y, al terminar, para apagarlo. Con el móvil bloqueado no se pueden manejar los ajustes de
 *   AA: aviso «Desbloquea el móvil…» y arranque al desbloquear ({@link Action#AUTOMATE_ON_UNLOCK}). Es el comportamiento
 *   de siempre; LinkLifecycle, LinkControl y AaServerStarter lo siguen igual que antes.
 * - **Manual** (sin accesibilidad): nunca se pulsa nada y **nunca se sondea el puerto**: una conexión que abre y cierra
 *   sin hablar (una sonda) bloquea el servidor hasta pararlo y volver a iniciarlo (prueba real del 2026-10-06, docs §15),
 *   así que antes de la sesión no hay nada que hacer ({@link Action#AT_SESSION}): lo dice el intento de verdad del
 *   Self-Mode con el coche ({@link AaServeAttempts}). Al terminar, el mismo cierre que el automático salvo apagar el
 *   servidor, que sin accesibilidad no se puede ({@link End#LEAVE_SERVER_ON}): Android Auto se cierra con orden (ByeBye)
 *   y su servidor sigue encendido; tras un cierre limpio vuelve a atender, así que el próximo viaje lo usa sin reiniciarlo.
 */
final class AaServerPolicy {
    /** Lo que se sabe del servidor (automático: el último arranque o parada conocidos; UNKNOWN = no vale para decidir). */
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
        /** Vuelve el coche con Android Auto en pausa, pero AA ya no está conectado. */
        RESUME,
    }

    enum Action {
        /** Nada que hacer: AA está conectado, o el servidor consta encendido (automático). */
        NONE,
        /** Automático: arrancarlo ya con la accesibilidad (tras la capa «Arrancando Auto…»). */
        AUTOMATE,
        /** Automático con el móvil bloqueado: aviso «Desbloquea el móvil…» y arranque al desbloquear. */
        AUTOMATE_ON_UNLOCK,
        /** Automático sin la accesibilidad activa: no se puede arrancar (solo se registra). */
        NO_ACCESSIBILITY,
        /**
         * Manual: nada ahora (sin sondear: una sonda que abre y cierra sin hablar bloquea el servidor). Cuando la sesión
         * con el coche necesite AA, el Self-Mode lo intenta de verdad y, si AA no contesta, aviso y reintentos
         * (AaServerManual).
         */
        AT_SESSION,
    }

    /** Qué pasa con Android Auto y su servidor al cerrar el enlace. */
    enum End {
        /** Sin Android Auto: nada. */
        NOTHING,
        /** Automático: el cierre de siempre (apagarlo ya o al desbloquear; LinkLifecycle.shutdownPlan). */
        STOP_SERVER,
        /**
         * Manual: se cierra Android Auto con orden (ByeBye) y el servidor se queda encendido (sin accesibilidad no se
         * para): tras un cierre limpio vuelve a atender, así que el próximo viaje lo usa sin reiniciarlo. Sin aviso.
         */
        LEAVE_SERVER_ON,
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

    private AaServerPolicy() {
    }

    /** Qué hacer cuando el servidor hace falta por [need]. */
    static Action onNeed(Need need, State s) {
        // Con nuestra head unit conectada (o aparcada) el servidor está encendido, sea cual sea el modo.
        if (s.aaConnected) return Action.NONE;
        // Manual: sin sondeos (una sonda lo bloquea); lo dice el intento real del Self-Mode con el coche.
        if (s.manual) return Action.AT_SESSION;
        // Automático: lo de siempre.
        if (s.server == Server.UP) return Action.NONE;
        if (!s.canAutomate) return Action.NO_ACCESSIBILITY;
        return s.locked ? Action.AUTOMATE_ON_UNLOCK : Action.AUTOMATE;
    }

    /**
     * Qué hacer con Android Auto y su servidor al cerrar el enlace (Desconectar o fin del viaje, igual): el automático lo
     * apaga; el manual no puede, y no hace falta reiniciarlo: AA se cierra con orden y el servidor vuelve a atender.
     */
    static End onEnd(boolean aaMode, State s) {
        if (!aaMode) return End.NOTHING;
        return s.manual ? End.LEAVE_SERVER_ON : End.STOP_SERVER;
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
            default:
                return "vuelve el coche con Android Auto en pausa";
        }
    }
}
