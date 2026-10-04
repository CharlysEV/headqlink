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
 * terminar si se han recorrido más de 300 m.
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
    }

    private static TripLog instance;
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

    private TripLog(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sensors = CarSensors.start(ctx);
    }

    static synchronized void start(Context ctx) {
        if (instance != null) return;
        instance = new TripLog(ctx);
        instance.running = true;
        new Thread(instance::loop, "trip-log").start();
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

    private void save() {
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
                out.add(t);
            } catch (Exception e) {
                L.w("viaje ilegible " + files[i].getName() + ": " + e);
            }
        }
        return out;
    }
}
