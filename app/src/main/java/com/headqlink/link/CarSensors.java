package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Sensores del móvil para los paneles de conducción (sin datos del coche, nube ni OBD):
 * - Velocidad, rumbo y distancia: GPS.
 * - Aceleración longitudinal: derivada de la velocidad del GPS (filtrada).
 * - Aceleración lateral: velocidad × giro alrededor de la vertical (giroscopio proyectado sobre la
 *   gravedad), así que da igual cómo esté colocado el móvil.
 * - Altitud y pendiente: barómetro (relativo, anclado a la altitud del GPS) o GPS si no hay.
 * - Viento y temperatura: Open-Meteo (gratuito, sin cuenta) cada 5 min o 5 km.
 * Todo vive en un hilo propio; los paneles leen {@link #snapshot()}.
 */
final class CarSensors implements SensorEventListener, LocationListener {
    /** Estado para los paneles (copia; unidades SI salvo que se indique). */
    static final class Snapshot {
        boolean gps;
        double speedKmh;
        double maxSpeedKmh;
        double headingDeg;
        double longG;
        double latG;
        double maxAccelG;
        double maxBrakeG;
        double maxLeftG;
        double maxRightG;
        double altitudeM = Double.NaN;
        boolean baro;
        double gradePct;
        double climbM;
        double descentM;
        double tripKm;
        long tripSec;
        double windKmh = Double.NaN;
        double windFromDeg;
        double headwindKmh = Double.NaN;
        double tempC = Double.NaN;
        String timer = "";
        double lat = Double.NaN;
        double lon = Double.NaN;
        /** Energía estimada desde que arrancaron los sensores (kWh, modelo EnergyModel). */
        double kwhTotal;
        double kmTotal;
    }

    private static CarSensors instance;

    private final Context ctx;
    private final HandlerThread thread = new HandlerThread("car-sensors");
    private Handler h;
    private SensorManager sm;
    private final Snapshot s = new Snapshot();
    private int users;

    // Estado interno (hilo de sensores).
    private final float[] gravity = new float[3];
    private boolean hasGravity;
    private double yawRate;
    private double lastSpeed = -1;
    private long lastSpeedNs;
    private double longFilt;
    private Location lastLoc;
    private double baroAlt = Double.NaN;
    private double baroOffset = Double.NaN;
    private double gradeRefAlt = Double.NaN;
    private double gradeDist;
    private double lastAlt = Double.NaN;
    private long tripStartMs;
    private long lastWeatherMs;
    private Location lastWeatherLoc;
    // Cronómetro 0-50 / 0-100.
    private long launchStartMs;
    private boolean launchArmed;
    private String lastTimer = "";
    private final EnergyModel model = new EnergyModel();
    private long lastEnergyNs;

    private CarSensors(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Arranca (o comparte) los sensores; cada start() necesita su stop(). */
    static synchronized CarSensors start(Context ctx) {
        if (instance == null) instance = new CarSensors(ctx);
        if (instance.users++ == 0) instance.begin();
        return instance;
    }

    static synchronized void stop() {
        if (instance == null || --instance.users > 0) return;
        instance.end();
        instance = null;
    }

    synchronized Snapshot snapshot() {
        Snapshot c = new Snapshot();
        c.gps = s.gps;
        c.speedKmh = s.speedKmh;
        c.maxSpeedKmh = s.maxSpeedKmh;
        c.headingDeg = s.headingDeg;
        c.longG = s.longG;
        c.latG = s.latG;
        c.maxAccelG = s.maxAccelG;
        c.maxBrakeG = s.maxBrakeG;
        c.maxLeftG = s.maxLeftG;
        c.maxRightG = s.maxRightG;
        c.altitudeM = s.altitudeM;
        c.baro = s.baro;
        c.gradePct = s.gradePct;
        c.climbM = s.climbM;
        c.descentM = s.descentM;
        c.tripKm = s.tripKm;
        c.tripSec = tripStartMs > 0 ? (SystemClock.elapsedRealtime() - tripStartMs) / 1000 : 0;
        c.windKmh = s.windKmh;
        c.windFromDeg = s.windFromDeg;
        c.headwindKmh = s.headwindKmh;
        c.tempC = s.tempC;
        c.timer = s.timer;
        c.lat = s.lat;
        c.lon = s.lon;
        c.kwhTotal = s.kwhTotal;
        c.kmTotal = s.kmTotal;
        return c;
    }

    /** Pone a cero máximos, viaje y cronómetro. */
    synchronized void resetTrip() {
        s.maxSpeedKmh = s.maxAccelG = s.maxBrakeG = s.maxLeftG = s.maxRightG = 0;
        s.climbM = s.descentM = s.tripKm = 0;
        s.timer = "";
        tripStartMs = SystemClock.elapsedRealtime();
    }

    boolean hasLocationPermission() {
        return ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void begin() {
        thread.start();
        h = new Handler(thread.getLooper());
        tripStartMs = SystemClock.elapsedRealtime();
        sm = ctx.getSystemService(SensorManager.class);
        register(Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_GAME);
        register(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_GAME);
        s.baro = register(Sensor.TYPE_PRESSURE, SensorManager.SENSOR_DELAY_UI);
        if (hasLocationPermission()) {
            try {
                ctx.getSystemService(LocationManager.class)
                        .requestLocationUpdates(LocationManager.GPS_PROVIDER, 200, 0, this, thread.getLooper());
            } catch (RuntimeException e) {
                L.w("sensores: sin GPS: " + e);
            }
        } else {
            L.w("sensores: sin permiso de ubicación (velocidad, pendiente y viento no disponibles)");
        }
        L.i("sensores: en marcha (barómetro " + (s.baro ? "sí" : "no") + ")");
    }

    private boolean register(int type, int delay) {
        Sensor sensor = sm.getDefaultSensor(type);
        if (sensor == null) return false;
        return sm.registerListener(this, sensor, delay, h);
    }

    private void end() {
        sm.unregisterListener(this);
        try {
            ctx.getSystemService(LocationManager.class).removeUpdates(this);
        } catch (RuntimeException ignored) {
        }
        thread.quitSafely();
        L.i("sensores: parados");
    }

    // ------------------------------------------------------------------ sensores

    @Override
    public void onSensorChanged(SensorEvent e) {
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                System.arraycopy(e.values, 0, gravity, 0, 3);
                hasGravity = true;
                break;
            case Sensor.TYPE_GYROSCOPE:
                if (!hasGravity) return;
                double gn = Math.sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]);
                if (gn < 1) return;
                // Giro alrededor de la vertical (rad/s), positivo a la izquierda.
                double w = (e.values[0] * gravity[0] + e.values[1] * gravity[1] + e.values[2] * gravity[2]) / gn;
                yawRate = yawRate * 0.8 + w * 0.2;
                updateLateral();
                break;
            case Sensor.TYPE_PRESSURE:
                baroAlt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, e.values[0]);
                break;
            default:
                break;
        }
    }

    private synchronized void updateLateral() {
        double v = s.speedKmh / 3.6;
        double lat = v > 2 ? v * yawRate / 9.81 : 0;
        s.latG = lat;
        if (lat > s.maxLeftG) s.maxLeftG = lat;
        if (-lat > s.maxRightG) s.maxRightG = -lat;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public synchronized void onLocationChanged(Location loc) {
        s.gps = true;
        long now = SystemClock.elapsedRealtimeNanos();
        double v = loc.hasSpeed() ? loc.getSpeed() : 0;
        s.speedKmh = v * 3.6;
        if (s.speedKmh > s.maxSpeedKmh) s.maxSpeedKmh = s.speedKmh;
        if (loc.hasBearing() && v > 1.5) s.headingDeg = loc.getBearing();

        // Aceleración longitudinal: derivada de la velocidad, filtrada.
        if (lastSpeed >= 0) {
            double dt = (now - lastSpeedNs) / 1e9;
            if (dt > 0.05 && dt < 3) {
                double a = (v - lastSpeed) / dt / 9.81;
                longFilt = longFilt * 0.6 + a * 0.4;
                s.longG = longFilt;
                if (longFilt > s.maxAccelG) s.maxAccelG = longFilt;
                if (-longFilt > s.maxBrakeG) s.maxBrakeG = -longFilt;
            }
        }
        lastSpeed = v;
        lastSpeedNs = now;
        launchTimer(s.speedKmh);

        // Distancia del viaje.
        double step = 0;
        if (lastLoc != null) {
            step = lastLoc.distanceTo(loc);
            if (step < 200) s.tripKm += step / 1000;
        }
        lastLoc = loc;

        // Altitud: barómetro anclado al GPS (el GPS da el nivel; el barómetro, los cambios finos).
        double alt;
        if (!Double.isNaN(baroAlt)) {
            if (loc.hasAltitude() && loc.hasVerticalAccuracy() && loc.getVerticalAccuracyMeters() < 15) {
                double off = loc.getAltitude() - baroAlt;
                baroOffset = Double.isNaN(baroOffset) ? off : baroOffset * 0.995 + off * 0.005;
            }
            alt = baroAlt + (Double.isNaN(baroOffset) ? 0 : baroOffset);
        } else {
            alt = loc.hasAltitude() ? loc.getAltitude() : Double.NaN;
        }
        if (!Double.isNaN(alt)) {
            s.altitudeM = Double.isNaN(s.altitudeM) ? alt : s.altitudeM * 0.7 + alt * 0.3;
            if (!Double.isNaN(lastAlt) && step > 0 && step < 200) {
                double d = s.altitudeM - lastAlt;
                if (d > 0) s.climbM += d;
                else s.descentM -= d;
            }
            lastAlt = s.altitudeM;
            // Pendiente: cambio de altitud en los últimos ~40 m recorridos.
            if (Double.isNaN(gradeRefAlt)) gradeRefAlt = s.altitudeM;
            gradeDist += step;
            if (gradeDist >= 40) {
                double g = (s.altitudeM - gradeRefAlt) / gradeDist * 100;
                s.gradePct = Math.max(-30, Math.min(30, s.gradePct * 0.5 + g * 0.5));
                gradeRefAlt = s.altitudeM;
                gradeDist = 0;
            } else if (v < 0.5) {
                s.gradePct = s.gradePct * 0.95;
            }
        }
        updateHeadwind();
        s.lat = loc.getLatitude();
        s.lon = loc.getLongitude();
        // Energía estimada acumulada (para el % de batería y los viajes).
        if (lastEnergyNs != 0) {
            double dtH = (now - lastEnergyNs) / 3.6e12;
            if (dtH > 0 && dtH < 0.01 && s.speedKmh > 2) {
                s.kwhTotal += model.compute(s.speedKmh, s.gradePct, s.headwindKmh, s.tempC, s.longG) * dtH;
                s.kmTotal += s.speedKmh * dtH;
            }
        }
        lastEnergyNs = now;
        maybeFetchWeather(loc);
    }

    /** 0-50 y 0-100 km/h: se arma parado (< 2 km/h) y cuenta desde que pasa de 3 km/h. */
    private void launchTimer(double kmh) {
        long now = SystemClock.elapsedRealtime();
        if (kmh < 2) {
            launchArmed = true;
            launchStartMs = 0;
            return;
        }
        if (launchArmed && launchStartMs == 0 && kmh > 3) launchStartMs = now;
        if (launchStartMs == 0) return;
        double t = (now - launchStartMs) / 1000.0;
        if (t > 30) {
            launchArmed = false;
            launchStartMs = 0;
            return;
        }
        if (kmh >= 50 && !lastTimer.contains("0-50")) {
            lastTimer = String.format(Locale.getDefault(), "0-50 km/h: %.1f s", t);
            s.timer = lastTimer;
        }
        if (kmh >= 100) {
            s.timer = lastTimer + String.format(Locale.getDefault(), " · 0-100 km/h: %.1f s", t);
            launchArmed = false;
            launchStartMs = 0;
            lastTimer = "";
        }
    }

    private void updateHeadwind() {
        if (Double.isNaN(s.windKmh)) return;
        // Viento "desde" windFromDeg; vamos hacia headingDeg. De cara cuando vienen de frente.
        double rel = Math.toRadians(s.windFromDeg - s.headingDeg);
        s.headwindKmh = s.windKmh * Math.cos(rel);
    }

    private void maybeFetchWeather(Location loc) {
        long now = SystemClock.elapsedRealtime();
        boolean moved = lastWeatherLoc == null || lastWeatherLoc.distanceTo(loc) > 5000;
        if (!moved && now - lastWeatherMs < 5 * 60_000) return;
        if (now - lastWeatherMs < 60_000) return;
        lastWeatherMs = now;
        lastWeatherLoc = loc;
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        new Thread(() -> {
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,wind_speed_10m,wind_direction_10m&wind_speed_unit=kmh", lat, lon);
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(10_000);
                con.setReadTimeout(10_000);
                String body;
                try (InputStream in = con.getInputStream()) {
                    body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                JSONObject cur = new JSONObject(body).getJSONObject("current");
                synchronized (this) {
                    // A la altura del coche el viento es menor que a 10 m (perfil logarítmico, ~0,7).
                    s.windKmh = cur.getDouble("wind_speed_10m") * 0.7;
                    s.windFromDeg = cur.getDouble("wind_direction_10m");
                    s.tempC = cur.getDouble("temperature_2m");
                    updateHeadwind();
                }
            } catch (Exception e) {
                L.w("sensores: sin datos de viento: " + e.getMessage());
            }
        }, "weather").start();
    }
}
