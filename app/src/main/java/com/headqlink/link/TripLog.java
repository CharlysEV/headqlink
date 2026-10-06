package com.headqlink.link;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Registro de viajes: cada sesión con el coche (modo ampliado) graba el recorrido GPS, km, tiempo,
 * desnivel, energía estimada (EnergyModel) y velocidad máxima, y lo guarda en files/trips/ al
 * terminar si se han recorrido más de 300 m. Con la cuenta de Leapmotor (CarCloud), además el % de
 * batería y el cuentakilómetros del coche al empezar y al terminar: de ahí sale el consumo real.
 */
final class TripLog {
    static final class Trip {
        long startMs;
        long minutes;
        double km;
        double kwh;
        double climb;
        double descent;
        double maxKmh;
        double[][] track = new double[0][];
        // Datos reales del coche (nube de Leapmotor) al empezar y al terminar; NaN si no los hubo.
        double socStart = Double.NaN;
        double socEnd = Double.NaN;
        double odoStart = Double.NaN;
        double odoEnd = Double.NaN;
        /** Capacidad de la batería del perfil (kWh) con la que se mide. */
        double capKwh = Double.NaN;
        /** Se vio cargando (o subir la batería) por el camino: la bajada no es el consumo. */
        boolean charged;
    }

    /** % de batería y cuentakilómetros del coche en un momento (de la nube). */
    static final class CloudMark {
        final double soc;
        final double odo;
        final long timeMs;

        CloudMark(double soc, double odo, long timeMs) {
            this.soc = soc;
            this.odo = odo;
            this.timeMs = timeMs;
        }
    }

    private static volatile TripLog instance;
    private final Context ctx;
    private final CarSensors sensors;
    private volatile boolean running;
    private final List<double[]> track = new ArrayList<>();
    private final long startMs = System.currentTimeMillis();
    private final long startElapsed = SystemClock.elapsedRealtime();
    private double startKwh = Double.NaN;
    private double startKm;
    private double startClimb;
    private double startDescent;
    private double maxKmh;
    // Nube de Leapmotor: la primera y la última lectura de este viaje (leídas después de empezar).
    private volatile CloudMark cloudStart;
    private volatile CloudMark cloudEnd;
    private volatile boolean cloudCharged;
    private double cloudCap = Double.NaN;
    private long cloudSeenFetch;
    /** Modo demostración: no se graba nada (los viajes que se ven son los de DemoMode). */
    private final boolean demo;

    private TripLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sensors = CarSensors.start(ctx);
        demo = DemoMode.active();
    }

    static synchronized void start(Context ctx) {
        if (instance != null) return;
        instance = new TripLog(ctx);
        instance.running = true;
        if (!instance.demo) new Thread(instance::loop, "trip-log").start();
    }

    static synchronized void stop() {
        if (instance == null) return;
        TripLog t = instance;
        instance = null;
        t.running = false;
        t.save();
        CarSensors.stop();
    }

    private void loop() {
        while (running) {
            CarSensors.Snapshot s = sensors.snapshot();
            if (Double.isNaN(startKwh)) {
                startKwh = s.kwhTotal;
                startKm = s.kmTotal;
                startClimb = s.climbM;
                startDescent = s.descentM;
            }
            maxKmh = Math.max(maxKmh, s.speedKmh);
            noteCloud(CarCloud.snapshot());
            if (!Double.isNaN(s.lat)) {
                synchronized (track) {
                    double[] last = track.isEmpty() ? null : track.get(track.size() - 1);
                    if (last == null || RoadInfo.dist(last[0], last[1], s.lat, s.lon) > 30) {
                        track.add(new double[]{s.lat, s.lon});
                    }
                }
            }
            SystemClock.sleep(5000);
        }
    }

    /**
     * Apunta el % y los km del coche de cada lectura nueva de la nube hecha después de empezar el viaje: la primera es
     * el inicio y la última, el final. Si se ve cargando o la batería sube más de un punto, el viaje no da consumo real.
     */
    private void noteCloud(CarCloud.Snapshot cs) {
        if (cs == null || !cs.hasData() || cs.demo || cs.fetchedAtMs < startMs || cs.fetchedAtMs == cloudSeenFetch) return;
        cloudSeenFetch = cs.fetchedAtMs;
        double soc = cs.status.socBest();
        double odo = cs.status.odometerKm;
        if (Double.isNaN(soc) || Double.isNaN(odo)) return;
        CloudMark m = new CloudMark(soc, odo, cs.dataTimeMs());
        CloudMark prev = cloudEnd;
        if (cloudStart == null) {
            cloudStart = m;
            L.i(String.format(Locale.US, "viaje: inicio con datos del coche (%.1f %%)", soc));
        } else if (cs.status.charging() || cs.status.pluggedIn() || (prev != null && soc > prev.soc + 1.0)) {
            if (!cloudCharged) L.i("viaje: el coche ha cargado por el camino (el consumo real del viaje no se mide)");
            cloudCharged = true;
        }
        cloudEnd = m;
        cloudCap = cs.capacityKwh;
    }

    /** Inicio del viaje en curso con datos de la nube, o null (en la demostración, el suyo). */
    static CloudMark cloudStart() {
        if (DemoMode.active()) return CarCloud.snapshot().demo ? DemoMode.cloudTripStart() : null;
        TripLog t = instance;
        return t == null ? null : t.cloudStart;
    }

    /** Consumo real del viaje en curso hasta la lectura now (de la nube). */
    static CloudEnergy.Result liveReal(CarCloud.Snapshot now) {
        CloudMark st = cloudStart();
        if (st == null || now == null || !now.hasData()) return CloudEnergy.NONE;
        TripLog t = instance;
        boolean charged = t != null && !DemoMode.active() && t.cloudCharged;
        return CloudEnergy.between(st.soc, st.odo, now.status.socBest(), now.status.odometerKm, now.capacityKwh, charged);
    }

    private void save() {
        if (demo) return;
        CarSensors.Snapshot s = sensors.snapshot();
        if (Double.isNaN(startKwh)) return;
        double km = s.kmTotal - startKm;
        if (km < 0.3) return;
        try {
            JSONObject o = new JSONObject();
            o.put("start", startMs);
            o.put("minutes", (SystemClock.elapsedRealtime() - startElapsed) / 60000);
            o.put("km", km);
            o.put("kwh", s.kwhTotal - startKwh);
            o.put("climb", s.climbM - startClimb);
            o.put("descent", s.descentM - startDescent);
            o.put("maxKmh", maxKmh);
            JSONArray t = new JSONArray();
            synchronized (track) {
                for (double[] p : track) t.put(new JSONArray().put(round(p[0])).put(round(p[1])));
            }
            o.put("track", t);
            CloudMark c0 = cloudStart;
            CloudMark c1 = cloudEnd;
            if (c0 != null && c1 != null && c1 != c0) {
                JSONObject c = new JSONObject();
                c.put("socStart", c0.soc);
                c.put("socEnd", c1.soc);
                c.put("odoStart", c0.odo);
                c.put("odoEnd", c1.odo);
                c.put("cap", cloudCap);
                c.put("charged", cloudCharged);
                o.put("cloud", c);
                CloudEnergy.Result r = CloudEnergy.between(c0.soc, c0.odo, c1.soc, c1.odo, cloudCap, cloudCharged);
                L.i(String.format(Locale.US, "viaje: datos del coche %.1f → %.1f %%, %.0f km de cuentakilómetros%s", c0.soc, c1.soc,
                        c1.odo - c0.odo, r.ok() ? String.format(Locale.US, ", %.1f kWh/100 km reales", r.kwhPer100)
                                : " (" + r.kind.name().toLowerCase(Locale.ROOT) + ")"));
            }
            File dir = new File(ctx.getExternalFilesDir(null), "trips");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            String name = "trip-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date(startMs)) + ".json";
            Files.write(new File(dir, name).toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
            L.i(String.format(Locale.US, "viaje guardado: %.1f km, %.1f kWh estimados", km, s.kwhTotal - startKwh));
        } catch (Exception e) {
            L.w("viaje: no se pudo guardar: " + e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 1e5) / 1e5;
    }

    /** Últimos viajes guardados, del más reciente al más antiguo. */
    static List<Trip> recent(Context ctx, int max) {
        if (DemoMode.active()) return DemoMode.trips(max);
        List<Trip> out = new ArrayList<>();
        File[] files = new File(ctx.getExternalFilesDir(null), "trips").listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = 0; i < files.length && out.size() < max; i++) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(files[i].toPath()), StandardCharsets.UTF_8));
                Trip t = new Trip();
                t.startMs = o.getLong("start");
                t.minutes = o.optLong("minutes");
                t.km = o.optDouble("km");
                t.kwh = o.optDouble("kwh");
                t.climb = o.optDouble("climb");
                t.descent = o.optDouble("descent");
                t.maxKmh = o.optDouble("maxKmh");
                JSONArray tr = o.optJSONArray("track");
                if (tr != null) {
                    t.track = new double[tr.length()][];
                    for (int k = 0; k < tr.length(); k++) {
                        t.track[k] = new double[]{tr.getJSONArray(k).getDouble(0), tr.getJSONArray(k).getDouble(1)};
                    }
                }
                JSONObject c = o.optJSONObject("cloud");
                if (c != null) {
                    t.socStart = c.optDouble("socStart");
                    t.socEnd = c.optDouble("socEnd");
                    t.odoStart = c.optDouble("odoStart");
                    t.odoEnd = c.optDouble("odoEnd");
                    t.capKwh = c.optDouble("cap");
                    t.charged = c.optBoolean("charged");
                }
                out.add(t);
            } catch (Exception e) {
                L.w("viaje ilegible " + files[i].getName() + ": " + e);
            }
        }
        return out;
    }
}
