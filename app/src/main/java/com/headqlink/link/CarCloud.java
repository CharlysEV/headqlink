package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Datos reales del coche (cuenta Leapmotor) para las pantallas del modo extendido, de solo lectura.
 *
 * Sondea el estado del coche mientras dura el modo extendido con el coche (CarUi) o la vista previa: cada 60 s, cada
 * 30 s con la sección Coche en pantalla, y si falla, a los 2, 5 y 10 minutos. Se para con el servicio. Las pantallas
 * leen una foto inmutable (snapshot()) con la hora del dato y la de la lectura. Una línea en el log por sondeo, sin
 * secretos (ni VIN, ni correo, ni posición).
 *
 * Además, el historial oficial (CloudHistory): los viajes con los kWh y los litros que mide el coche, cada 30 min como
 * mucho (antes si el coche acaba de terminar un viaje) y el consumo de las últimas semanas cada 12 h. Cuenta para el
 * tope diario y se guarda cifrado.
 */
final class CarCloud {
    /** Por qué hay o no hay datos. */
    enum State {
        /** Android 5 o anterior (el Keystore con AES-GCM es de Android 6). */
        UNSUPPORTED,
        /** Sin certificado, sin sesión o sin coche elegido. */
        NO_ACCOUNT,
        /** Configurado pero desactivado en el móvil. */
        OFF,
        /** Leyendo por primera vez. */
        WAITING,
        OK,
        /** El último intento falló (red, la nube…): se reintenta más tarde; puede haber datos de antes. */
        ERROR,
        /** La sesión caducó: hay que volver a entrar en el móvil. */
        EXPIRED,
        /** El servidor presenta otra clave: se decide en el móvil. */
        SERVER_KEY
    }

    /** Foto inmutable de los datos de la nube. */
    static final class Snapshot {
        final State state;
        /** El último estado leído (también tras un error), o null. */
        final LeapStatus status;
        /** Hora (de pared) de la última lectura buena, o 0. */
        final long fetchedAtMs;
        final long latencyMs;
        /** Capacidad de la batería del perfil elegido (kWh). */
        final double capacityKwh;
        final String carType;
        /** Hora (de pared) del próximo intento tras un error, o 0. */
        final long nextTryMs;
        /** Datos inventados del modo demostración. */
        final boolean demo;

        Snapshot(State state, LeapStatus status, long fetchedAtMs, long latencyMs, double capacityKwh, String carType,
                 long nextTryMs, boolean demo) {
            this.state = state;
            this.status = status;
            this.fetchedAtMs = fetchedAtMs;
            this.latencyMs = latencyMs;
            this.capacityKwh = capacityKwh;
            this.carType = carType == null ? "" : carType;
            this.nextTryMs = nextTryMs;
            this.demo = demo;
        }

        static Snapshot of(State s) {
            return new Snapshot(s, null, 0, 0, CarCloudStore.KWH_C10_LIFE, "", 0, false);
        }

        boolean hasData() {
            return status != null;
        }

        /** Hora del dato: la que pone el coche o, si no la da, la de la lectura. */
        long dataTimeMs() {
            return status != null && status.carTimeMs > 0 ? status.carTimeMs : fetchedAtMs;
        }

        long ageMs(long nowMs) {
            return Math.max(0, nowMs - dataTimeMs());
        }

        /** % de batería real si hay datos y no son más viejos que maxAgeMs; NaN si no. */
        double soc(long nowMs, long maxAgeMs) {
            if (status == null || ageMs(nowMs) > maxAgeMs) return Double.NaN;
            return status.socBest();
        }

        /** Copia con otro estado (los datos de antes se conservan). */
        Snapshot with(State s, long nextTry) {
            return new Snapshot(s, status, fetchedAtMs, latencyMs, capacityKwh, carType, nextTry, demo);
        }
    }

    /** Batería real en la Ruta: datos de hasta 6 h (más viejos, mejor el % indicado a mano). */
    static final long SOC_MAX_AGE_MS = 6 * 3600_000L;
    /** Potencia real en Eficiencia: solo si el dato es de hace 2 min o menos. */
    static final long POWER_MAX_AGE_MS = 120_000L;

    /** Cuándo vuelve a leer. Pura (sin Android): se prueba en el PC. */
    static final class Policy {
        /**
         * Discreto con la API (no oficial: Leapmotor podría limitar o bloquear una cuenta que consulte demasiado): 2 min de
         * base, 90 s con la sección Coche a la vista (lo mismo que LMB10 con su app abierta); si el coche no ha subido un
         * dato nuevo en las dos últimas lecturas (aparcado o dormido), 5 min y luego 15 min; tope diario de lecturas.
         */
        static final long NORMAL_MS = 120_000;
        static final long HUB_MS = 90_000;
        static final long[] STALE_MS = {300_000, 900_000};
        static final int DAILY_CAP = 400;
        static final long[] BACKOFF_MS = {120_000, 300_000, 600_000};
        /** Sin cuenta o desactivado: se mira cada tanto si eso cambia (sin red). */
        static final long IDLE_MS = 30_000;

        private Policy() {
        }

        /** Espera hasta la siguiente lectura: 60 s (30 s con la sección Coche a la vista) o 2, 5 y 10 min tras errores. */
        static long delayMs(int failures, boolean hubVisible) {
            return delayMs(failures, hubVisible, 0);
        }

        /** Con stale = lecturas seguidas sin dato nuevo del coche (a partir de 2, el coche está dormido: se espacia). */
        static long delayMs(int failures, boolean hubVisible, int stale) {
            if (failures > 0) return BACKOFF_MS[Math.min(failures, BACKOFF_MS.length) - 1];
            if (stale >= 2) return STALE_MS[Math.min(stale - 2, STALE_MS.length - 1)];
            return hubVisible ? HUB_MS : NORMAL_MS;
        }
    }

    /** Cuándo vuelve a leer el historial. Pura: se prueba en el PC. */
    static final class HistoryPolicy {
        static final long EVERY_MS = 30 * 60_000L;
        /** Tras un viaje (sube el cuentakilómetros y el coche está parado): a los 3 min y otra vez a los 12. */
        static final long AFTER_TRIP_MS = 3 * 60_000L;
        static final long FOLLOW_UP_MS = 12 * 60_000L;
        static final long WEEKLY_MS = 12 * 3600_000L;
        /** Si la nube no lo da (o falla): no se insiste en 2 h. */
        static final long FAIL_MS = 2 * 3600_000L;
        static final int MAX_PAGES = 5;

        private HistoryPolicy() {
        }

        /**
         * ¿Toca leer el historial? lastAtMs: la última lectura (0 = nunca); odoNow y odoAtLast: el cuentakilómetros ahora
         * y en esa lectura; parked: el coche está parado; followUpAtMs: lectura de repaso pendiente (0 = ninguna).
         */
        static boolean due(long nowMs, long lastAtMs, double odoNow, double odoAtLast, boolean parked, long followUpAtMs,
                           long failUntilMs) {
            if (nowMs < failUntilMs) return false;
            if (lastAtMs == 0) return true;
            long since = nowMs - lastAtMs;
            if (since >= EVERY_MS) return true;
            if (followUpAtMs > 0 && nowMs >= followUpAtMs) return true;
            boolean drove = !Double.isNaN(odoNow) && !Double.isNaN(odoAtLast) && odoNow >= odoAtLast + 1;
            return drove && parked && since >= AFTER_TRIP_MS;
        }
    }

    private static volatile Snapshot current = Snapshot.of(State.NO_ACCOUNT);
    private static volatile java.util.List<CloudHistory.Trip> history = java.util.Collections.emptyList();
    private static volatile CloudHistory.Weekly weekly;
    private static long historyAtMs;
    private static long weeklyAtMs;
    private static long historyFailUntilMs;
    private static long weeklyFailUntilMs;
    private static long followUpAtMs;
    private static double odoAtHistory = Double.NaN;
    private static boolean historyLoaded;
    private static volatile boolean running;
    /** Lecturas seguidas en las que el coche no había subido un dato nuevo (aparcado o dormido). */
    private static volatile int staleReads;
    private static volatile long lastDataTime;
    /** Tope diario de lecturas (Policy.DAILY_CAP), por día local. */
    private static int readsToday;
    private static long readsDay = -1;
    private static volatile boolean hubVisible;
    /** EXPIRED / SERVER_KEY / NO_ACCOUNT: no se vuelve a intentar hasta que cambie algo en el móvil. */
    private static volatile boolean blocked;
    /** Leer en cuanto se pueda (algo cambió en el móvil). */
    private static volatile boolean pollNow;
    private static final Object WAKE = new Object();
    /** Sube con cada start(): un hilo viejo que aún termina su petición no sigue en bucle junto al nuevo. */
    private static volatile int generation;
    private static Context app;

    private CarCloud() {
    }

    /** Quién lo necesita: «ui» (CarUi: la sesión con el coche) y «charge» (ChargeWatchService: una carga en directo). */
    private static final java.util.Set<String> owners = new java.util.HashSet<>();
    /** Vigilando una carga: se lee como con la sección Coche a la vista. */
    private static volatile boolean chargeWatch;

    /** Lo pide alguien (el sondeo sigue mientras alguien lo tenga). */
    static synchronized void acquire(Context ctx, String who) {
        owners.add(who);
        start(ctx);
    }

    /** Ya no lo necesita; sin nadie, se para. */
    static synchronized void release(String who) {
        owners.remove(who);
        if (owners.isEmpty()) stop();
    }

    /** Vigilando una carga en directo (lecturas cada HUB_MS). */
    static void setChargeWatch(boolean on) {
        if (chargeWatch == on) return;
        chargeWatch = on;
        wake();
    }

    /** Arranca el sondeo (CarUi.start: modo extendido con el coche o vista previa). */
    static synchronized void start(Context ctx) {
        if (running) return;
        app = ctx.getApplicationContext();
        running = true;
        blocked = false;
        pollNow = false;
        int gen = ++generation;
        // Capturas de la vista previa (reloj quieto): sin red, con los datos inventados de DemoMode.
        if (DemoMode.active() && !DemoMode.live()) return;
        Thread t = new Thread(() -> loop(gen), "carcloud");
        t.setDaemon(true);
        t.start();
    }

    /** Para el sondeo (CarUi.stop: se acaba el modo extendido o el servicio). */
    static synchronized void stop() {
        if (!running) return;
        running = false;
        generation++;
        wake();
    }

    static boolean running() {
        return running;
    }

    /** La sección Coche entra o sale de la pantalla (cada 30 s mientras se vea). */
    static void setHubVisible(boolean on) {
        if (hubVisible == on) return;
        hubVisible = on;
        wake();
    }

    /** Algo cambió en el móvil (sesión, coche, certificado, activado): se vuelve a mirar ya. */
    static void settingsChanged() {
        blocked = false;
        pollNow = true;
        CarCloudSession.invalidate();
        wake();
    }

    private static void wake() {
        synchronized (WAKE) {
            WAKE.notifyAll();
        }
    }

    /** Lo último que se sabe (en la demostración, sus datos inventados si no hay reales). */
    static Snapshot snapshot() {
        Snapshot s = current;
        if (DemoMode.active() && (!s.hasData() || DemoMode.overridesCloud())) return DemoMode.cloudSnapshot();
        return s;
    }

    // ------------------------------------------------------------------ bucle

    private static State gate(CarCloudStore st) {
        if (!CarCloudStore.supported()) return State.UNSUPPORTED;
        if (!st.hasIdentity() || !st.hasSession()) return State.NO_ACCOUNT;
        if (!st.enabled()) return State.OFF;
        return null;
    }

    private static boolean alive(int gen) {
        return running && gen == generation;
    }

    private static void loop(int gen) {
        L.i("nube Leapmotor: sondeo en marcha (cada 2 min; 90 s con la sección Coche en pantalla; 5-15 min si el coche no sube "
                + "datos nuevos; como mucho " + Policy.DAILY_CAP + " lecturas al día)");
        int failures = 0;
        long lastAttempt = 0;
        while (alive(gen)) {
            CarCloudStore st = new CarCloudStore(app);
            State g = gate(st);
            if (g != null) {
                if (current.state != g) current = Snapshot.of(g);
                failures = 0;
                lastAttempt = 0; // en cuanto se pueda, se lee
                pollNow = false;
                sleep(Policy.IDLE_MS);
                continue;
            }
            if (blocked) {
                pollNow = false;
                sleep(Policy.IDLE_MS);
                continue;
            }
            if (pollNow) {
                pollNow = false;
                lastAttempt = 0;
                failures = 0;
            }
            // Espera hasta que toque (se recalcula si la sección Coche aparece o desaparece).
            long due = lastAttempt == 0 ? 0 : lastAttempt + Policy.delayMs(failures, hubVisible || chargeWatch, staleReads);
            long now = SystemClock.elapsedRealtime();
            if (now < due) {
                sleep(due - now);
                continue;
            }
            if (!takeDailyQuota()) {
                sleep(Policy.STALE_MS[Policy.STALE_MS.length - 1]);
                continue;
            }
            lastAttempt = SystemClock.elapsedRealtime();
            if (!current.hasData() && current.state != State.ERROR) {
                current = new Snapshot(State.WAITING, null, 0, 0, st.capacityKwh(), st.carType(), 0, false);
            }
            failures = pollOnce(st, failures, gen);
            if (failures == 0 && current.state == State.OK && alive(gen)) pollHistory(st, gen);
        }
        L.i("nube Leapmotor: sondeo parado");
    }

    /** Un sondeo: actualiza la foto y el log; devuelve los fallos seguidos. */
    private static int pollOnce(CarCloudStore st, int failures, int gen) {
        long t0 = SystemClock.elapsedRealtime();
        try {
            LeapStatus s = CarCloudSession.readStatus(app);
            if (!alive(gen)) return failures;
            if (s.reev() && st.adoptReev()) {
                L.i(String.format(Locale.US, "nube Leapmotor: el coche tiene depósito de gasolina (REEV): batería de %.1f kWh y "
                        + "consumo de gasolina", CarCloudStore.KWH_C10_REEV));
            }
            long lat = SystemClock.elapsedRealtime() - t0;
            long wall = System.currentTimeMillis();
            Snapshot snap = new Snapshot(State.OK, s, wall, lat, st.capacityKwh(), st.carType(), 0, false);
            current = snap;
            long dataTime = snap.dataTimeMs();
            staleReads = dataTime > 0 && dataTime == lastDataTime ? staleReads + 1 : 0;
            lastDataTime = dataTime;
            L.i("nube Leapmotor: " + s.logLine() + " · dato del coche de hace " + agoLog(snap.ageMs(wall)) + " · " + lat + " ms"
                    + (staleReads >= 2 ? " · sin dato nuevo " + staleReads + " veces: la siguiente en "
                    + Policy.delayMs(0, hubVisible, staleReads) / 60_000 + " min" : "") + " · lecturas hoy " + readsToday);
            return 0;
        } catch (LeapApi.SessionExpiredException e) {
            current = current.with(State.EXPIRED, 0);
            blocked = true;
            L.w("nube Leapmotor: la sesión caducó (" + e.getMessage() + "); vuelve a entrar en el móvil (Datos del coche)");
            return failures;
        } catch (LeapHttps.ServerKeyChangedException e) {
            current = current.with(State.SERVER_KEY, 0);
            blocked = true;
            L.w("nube Leapmotor: el servidor presenta otra clave (huella " + e.detail.fingerprint
                    + "); no me conecto hasta que lo aceptes en el móvil (Datos del coche)");
            return failures;
        } catch (CarCloudSession.NotConfiguredException e) {
            current = Snapshot.of(State.NO_ACCOUNT);
            blocked = true;
            L.i("nube Leapmotor: sin configurar del todo (" + e.getMessage() + ")");
            return 0;
        } catch (Exception e) {
            int n = failures + 1;
            long wait = Policy.delayMs(n, hubVisible);
            current = current.with(State.ERROR, System.currentTimeMillis() + wait);
            L.w("nube Leapmotor: sin datos (" + safeError(e) + ", " + (SystemClock.elapsedRealtime() - t0) + " ms); fallo "
                    + n + ", reintento en " + wait / 60_000 + " min");
            return n;
        }
    }

    // ------------------------------------------------------------------ historial

    /** Viajes según el coche (del más reciente al más antiguo); vacío si aún no hay (en la demostración, los suyos). */
    static java.util.List<CloudHistory.Trip> history() {
        if (DemoMode.active() && history.isEmpty()) return DemoMode.cloudHistory();
        return history;
    }

    /** Consumo de las últimas semanas según el coche, o null (en la demostración, el suyo). */
    static CloudHistory.Weekly weekly() {
        CloudHistory.Weekly w = weekly;
        if (DemoMode.active() && w == null) return DemoMode.cloudWeekly();
        return w;
    }

    private static boolean hasFuel(java.util.List<CloudHistory.Trip> trips) {
        for (CloudHistory.Trip t : trips) if (!Double.isNaN(t.fuelL)) return true;
        return false;
    }

    /** El historial y el consumo semanal, cuando tocan (HistoryPolicy); nunca tumba el sondeo del estado. */
    private static void pollHistory(CarCloudStore st, int gen) {
        long now = System.currentTimeMillis();
        if (!historyLoaded) {
            historyLoaded = true;
            org.json.JSONObject o = st.history();
            if (o != null) {
                history = CloudHistory.merge(CloudHistory.tripsFromJson(o), null, now - CloudHistory.KEEP_MS);
                weekly = CloudHistory.weeklyFromJson(o);
                historyAtMs = o.optLong("tripsAt");
                weeklyAtMs = o.optLong("weeklyAt");
            }
        }
        Snapshot snap = current;
        LeapStatus s = snap.status;
        // Un eléctrico puro también manda la gasolina (a 0): fuera también de lo guardado (la caché de antes la tenía).
        if (s != null && !s.reev() && hasFuel(history)) history = CloudHistory.withoutFuel(history);
        double odo = s == null ? Double.NaN : s.odometerKm;
        boolean parked = s != null && (Double.isNaN(s.speedKmh) || s.speedKmh < 1);
        boolean changed = false;
        if (HistoryPolicy.due(now, historyAtMs, odo, odoAtHistory, parked, followUpAtMs, historyFailUntilMs)) {
            boolean afterTrip = historyAtMs > 0 && !Double.isNaN(odo) && !Double.isNaN(odoAtHistory) && odo >= odoAtHistory + 1;
            long from = now - CloudHistory.KEEP_MS;
            java.util.List<CloudHistory.Trip> old = history;
            if (!old.isEmpty() && historyAtMs > 0) from = Math.max(from, old.get(0).startMs - 2 * 86_400_000L);
            try {
                java.util.List<CloudHistory.Trip> fresh = CarCloudSession.readTrips(app, from / 1000, now / 1000,
                        HistoryPolicy.MAX_PAGES, CarCloud::takeDailyQuota);
                if (!alive(gen)) return;
                // Un eléctrico puro manda la gasolina a 0: fuera, o saldría «0 L» en cada viaje.
                if (s != null && !s.reev()) {
                    fresh = CloudHistory.withoutFuel(fresh);
                    old = CloudHistory.withoutFuel(old);
                }
                history = CloudHistory.merge(old, fresh, now - CloudHistory.KEEP_MS);
                historyAtMs = now;
                odoAtHistory = odo;
                // Tras un viaje, un repaso a los 12 min: el coche puede subir el resumen del viaje con retraso.
                followUpAtMs = afterTrip ? now + HistoryPolicy.FOLLOW_UP_MS : 0;
                changed = true;
                L.i("nube Leapmotor: historial de viajes según el coche: " + CloudHistory.logLine(history, now - 30 * 86_400_000L)
                        + " en 30 días" + (afterTrip ? " (tras un viaje)" : ""));
            } catch (Exception e) {
                historyFailUntilMs = now + HistoryPolicy.FAIL_MS;
                followUpAtMs = 0;
                L.w("nube Leapmotor: historial de viajes no disponible (" + safeError(e) + "); se reintenta en "
                        + HistoryPolicy.FAIL_MS / 3_600_000L + " h");
            }
        }
        if (now >= weeklyFailUntilMs && (weeklyAtMs == 0 || now - weeklyAtMs >= HistoryPolicy.WEEKLY_MS) && takeDailyQuota()) {
            try {
                CloudHistory.Weekly w = CarCloudSession.readWeekly(app);
                if (!alive(gen)) return;
                weeklyAtMs = now;
                if (w != null) weekly = w;
                changed = true;
                L.i("nube Leapmotor: consumo según el coche: " + (w == null || Double.isNaN(w.avgKwhPer100) ? "sin cifra"
                        : String.format(Locale.US, "%.1f kWh/100 km de media (%d semanas)", w.avgKwhPer100, w.weeks.length)));
            } catch (Exception e) {
                weeklyFailUntilMs = now + HistoryPolicy.FAIL_MS;
                L.w("nube Leapmotor: consumo semanal no disponible (" + safeError(e) + ")");
            }
        }
        if (changed) {
            try {
                st.saveHistory(CloudHistory.toJson(history, weekly, historyAtMs, weeklyAtMs));
            } catch (Exception e) {
                L.w("nube Leapmotor: no se pudo guardar el historial: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Publica una lectura hecha fuera del sondeo («Leer estado ahora» en el móvil): la ven las pantallas del coche. */
    static void publish(Context ctx, LeapStatus s, long latencyMs) {
        CarCloudStore st = new CarCloudStore(ctx);
        current = new Snapshot(State.OK, s, System.currentTimeMillis(), latencyMs, st.capacityKwh(), st.carType(), 0, false);
    }

    /** Cuenta una lectura del día; false si ya se llegó al tope (se registra una vez). */
    private static synchronized boolean takeDailyQuota() {
        long day = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()));
        if (day != readsDay) {
            readsDay = day;
            readsToday = 0;
        }
        if (readsToday >= Policy.DAILY_CAP) {
            if (readsToday == Policy.DAILY_CAP) {
                readsToday++;
                L.w("nube Leapmotor: tope de " + Policy.DAILY_CAP + " lecturas hoy: no leo más hasta mañana (para no abusar de la API)");
            }
            return false;
        }
        readsToday++;
        return true;
    }

    private static void sleep(long ms) {
        synchronized (WAKE) {
            if (!running || pollNow) return;
            try {
                WAKE.wait(Math.max(1, ms));
            } catch (InterruptedException ignored) {
                // Hilo propio: nadie lo interrumpe; se sigue y el bucle mira si debe parar.
            }
        }
    }

    /** El error para el log: el tipo y el mensaje, sin URLs con datos (el de la API no lleva el cuerpo). */
    static String safeError(Throwable e) {
        return Http.safeError(e);
    }

    private static String agoLog(long ms) {
        long s = ms / 1000;
        if (s < 120) return s + " s";
        if (s < 7200) return s / 60 + " min";
        return String.format(Locale.US, "%.1f h", s / 3600.0);
    }

    // ------------------------------------------------------------------ textos para las pantallas

    /** «hace 40 s», «hace 3 min», «hace 2 h», «hace 1 d». */
    static String ago(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return Str.get(R.string.hql_cloud_ago_s, s);
        long m = s / 60;
        if (m < 60) return Str.get(R.string.hql_cloud_ago_min, m);
        long h = m / 60;
        if (h < 48) return Str.get(R.string.hql_cloud_ago_h, h);
        return Str.get(R.string.hql_cloud_ago_d, h / 24);
    }

    /** «real · hace 40 s» (la edad del dato del coche). */
    static String realLabel(Snapshot s, long nowMs) {
        return Str.get(R.string.hql_cloud_real_ago, ago(s.ageMs(nowMs)));
    }
}
