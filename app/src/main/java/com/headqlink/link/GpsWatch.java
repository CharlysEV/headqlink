package com.headqlink.link;

/**
 * GPS del móvil para los paneles del coche (puro: lo prueban los tests). Tres cosas:
 *
 * - **Estado** ({@link #state}): con más de {@link #STALE_MS} sin posición, el GPS está parado y los paneles no pueden
 *   enseñar la última velocidad como si fuera la de ahora. Con el móvil bloqueado y la ubicación solo «mientras se usa»,
 *   lo más probable es que Android haya cortado el GPS a la app ({@link State#PAUSED_LOCKED}: el arreglo es «Permitir
 *   todo el tiempo»); si no, simplemente no hay GPS (túnel, garaje…).
 * - **Huecos** ({@link #bridgeKm}): cuando vuelven las posiciones tras un hueco, la distancia en línea recta entre la
 *   última posición y la nueva (lo mínimo que ha recorrido el coche) cuenta para el viaje, como si se hubiera ido a la
 *   velocidad media del hueco. Sin esto, el viaje y el consumo se quedaban como si el coche hubiera estado parado.
 * - **Sonda del bloqueo** ({@link LockProbe}): tras el primer bloqueo con el GPS funcionando, si siguen llegando
 *   posiciones. Lo dice el log una vez por sesión.
 */
final class GpsWatch {
    /** Sin posición más de esto: GPS parado (el GPS da una por segundo). */
    static final long STALE_MS = 5000;
    /** Media de un hueco por encima de esto: la distancia no es creíble (un salto de la posición) y no se suma. */
    static final double MAX_BRIDGE_KMH = 200;
    /** Huecos más largos no se puentean (el coche pudo parar y arrancar varias veces). */
    static final long MAX_BRIDGE_MS = 3 * 3_600_000L;

    enum State {
        /** Aún no ha llegado ninguna posición. */
        WAITING,
        /** Posiciones al día. */
        OK,
        /** Sin posiciones con el móvil bloqueado y la ubicación solo «mientras se usa»: Android corta el GPS a la app. */
        PAUSED_LOCKED,
        /** Sin posiciones por otra cosa (sin cobertura, GPS apagado…). */
        LOST
    }

    private GpsWatch() {
    }

    /**
     * everFix: ha llegado alguna posición; ageMs: desde la última (negativo si ninguna); locked: móvil bloqueado;
     * background: la app tiene la ubicación «todo el tiempo».
     */
    static State state(boolean everFix, long ageMs, boolean locked, boolean background) {
        if (everFix && ageMs >= 0 && ageMs <= STALE_MS) return State.OK;
        if (locked && !background) return State.PAUSED_LOCKED;
        return everFix ? State.LOST : State.WAITING;
    }

    /** Entre dos posiciones seguidas pasó tanto que fue un hueco (no se integra como un paso normal). */
    static boolean isGap(long gapMs) {
        return gapMs > STALE_MS;
    }

    /**
     * Km que se suman al viaje por un hueco de gapMs con distM metros en línea recta entre la posición de antes y la de
     * después: la línea recta (cota baja de lo recorrido) si la media sale creíble para un coche; si no, 0.
     */
    static double bridgeKm(double distM, long gapMs) {
        if (gapMs <= 0 || gapMs > MAX_BRIDGE_MS || !(distM > 0)) return 0;
        double kmh = distM / 1000 / (gapMs / 3_600_000.0);
        return kmh <= MAX_BRIDGE_KMH ? distM / 1000 : 0;
    }

    /** Velocidad media (km/h) de un hueco con km recorridos en gapMs. */
    static double bridgeKmh(double km, long gapMs) {
        return gapMs > 0 ? km / (gapMs / 3_600_000.0) : 0;
    }

    /**
     * ¿Siguen llegando posiciones con el móvil bloqueado? Se llama cada segundo ({@link #tick}) y decide una sola vez:
     * - Bloqueo con el GPS al día: si llega una posición pasados {@link #SETTLE_MS} (Android tarda unos segundos en quitar
     *   el acceso tras bloquear), llega; si en {@link #DECIDE_MS} no ha llegado ninguna, no llega.
     * - Bloqueo sin GPS al día (aún no había posición, o túnel): solo decide si llega una (que no llegue no dice nada:
     *   puede ser el GPS arrancando o sin cobertura); si no, espera al bloqueo siguiente.
     */
    static final class LockProbe {
        static final long SETTLE_MS = 8000;
        static final long DECIDE_MS = 25000;

        enum Result { PENDING, ARRIVES, STOPS }

        private long lockedAt = -1;
        private boolean freshAtLock;
        private Result result = Result.PENDING;

        /** now y lastFixMs en el mismo reloj (lastFixMs negativo si no ha llegado ninguna). Devuelve el resultado al decidir. */
        Result tick(long now, boolean locked, long lastFixMs) {
            if (result != Result.PENDING) return null;
            if (!locked) {
                lockedAt = -1;
                return null;
            }
            if (lockedAt < 0) {
                lockedAt = now;
                freshAtLock = lastFixMs >= 0 && now - lastFixMs <= STALE_MS;
                return null;
            }
            if (lastFixMs >= 0 && lastFixMs >= lockedAt + SETTLE_MS) {
                result = Result.ARRIVES;
                return result;
            }
            if (freshAtLock && now - lockedAt >= DECIDE_MS) {
                result = Result.STOPS;
                return result;
            }
            return null;
        }

        Result result() {
            return result;
        }

        /** Desde cuándo está bloqueado (en el reloj de tick), o -1. */
        long lockedAt() {
            return lockedAt;
        }
    }
}
