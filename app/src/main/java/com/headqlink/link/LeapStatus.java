/*
 * HeadQLink: datos reales del coche (cuenta Leapmotor), solo lectura.
 *
 * La tabla de señales (kSignalToNamed), su fusión con los campos con nombre (mergeSignalToNamed), las conversiones
 * (_asInt, _asDouble, _asBool) y las reglas de carga y presiones (isPluggedIn, isCharging, tirePressureAlerts,
 * batteryPowerKw) están portadas de LMB10 (lib/leapmotor_engine.dart y lib/sentry/sentry_adapter.dart), de txurtxil:
 * https://github.com/txurtxil/LPB10 (GPL-3.0). HeadQLink (AGPL-3.0) las incorpora según la sección 13 de ambas
 * licencias.
 *
 * A propósito NO se leen ni se guardan la posición del coche (latitud y longitud) ni nada que no enseñe HeadQLink.
 */
package com.headqlink.link;

import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Estado del coche leído de la nube, inmutable. NaN o null: el coche no lo ha mandado. */
final class LeapStatus {
    /** Señal numérica → nombre, solo las que usa HeadQLink (el subconjunto de kSignalToNamed de LMB10). */
    static final Map<String, String> SIGNALS;

    static {
        Map<String, String> m = new HashMap<>();
        m.put("1204", "soc");
        m.put("100003", "preciseSoc");
        m.put("2188", "liveRemainingRange");
        m.put("1149", "chargeState");
        m.put("47", "acInputSlowCharge");
        m.put("1197", "dcInputFastCharge");
        m.put("3736", "chargeCompleted");
        m.put("1200", "chargeRemainTime");
        m.put("1177", "batteryVoltage");
        m.put("1178", "batteryCurrent");
        m.put("1182", "minBatteryTemp");
        m.put("1186", "batteryThermalRequest");
        m.put("1318", "totalMileage");
        m.put("1319", "speed");
        m.put("1349", "interiorTemp");
        m.put("1298", "driverDoorLockStatus");
        m.put("1277", "lbcmDriverDoorStatus");
        m.put("1278", "rbcmDriverDoorStatus");
        m.put("1279", "lbcmLeftRearDoorStatus");
        m.put("1280", "rbcmRightRearDoorStatus");
        m.put("1281", "bbcmBackDoorStatus");
        m.put("1258", "bcmKeyPositionOn3");
        m.put("6048", "speedLimit");
        m.put("12054", "speedLimitActive");
        m.put("2667", "leftFrontTirePressure");
        m.put("2653", "rightFrontTirePressure");
        m.put("2646", "leftRearTirePressure");
        m.put("2660", "rightRearTirePressure");
        m.put("2641", "leftFrontTirePressureState");
        m.put("2648", "rightFrontTirePressureState");
        m.put("2655", "leftRearTirePressureState");
        m.put("2662", "rightRearTirePressureState");
        SIGNALS = java.util.Collections.unmodifiableMap(m);
    }

    /** Orden de las ruedas y de las puertas: delantera izquierda, delantera derecha, trasera izquierda, trasera derecha. */
    static final int FL = 0;
    static final int FR = 1;
    static final int RL = 2;
    static final int RR = 3;

    private static final String[] TYRE_KPA = {"leftFrontTirePressure", "rightFrontTirePressure", "leftRearTirePressure",
            "rightRearTirePressure"};
    private static final String[] TYRE_STATE = {"leftFrontTirePressureState", "rightFrontTirePressureState",
            "leftRearTirePressureState", "rightRearTirePressureState"};
    private static final String[] DOORS = {"lbcmDriverDoorStatus", "rbcmDriverDoorStatus", "lbcmLeftRearDoorStatus",
            "rbcmRightRearDoorStatus"};

    final double soc;
    final double preciseSoc;
    final double rangeKm;
    final Integer chargeState;
    final Boolean acCharge;
    final Boolean dcCharge;
    final Boolean chargeCompleted;
    /** Minutos que faltan para terminar la carga (lo que diga el coche). */
    final double chargeRemainMin;
    final double batteryVoltage;
    final double batteryCurrent;
    final double minBatteryTempC;
    final Integer batteryThermalRequest;
    final double odometerKm;
    final double speedKmh;
    final double interiorTempC;
    /** Cerrado con llave (señal 1298, 1 = cerrado). */
    final Boolean locked;
    /** Puertas abiertas (1 = abierta, como asume LMB10), en el orden FL, FR, RL, RR. */
    private final Boolean[] doorsOpen;
    final Boolean bootOpen;
    /** Coche encendido (posición de la llave «ON3», señal 1258). */
    final Boolean powerOn;
    final double speedLimitKmh;
    final Boolean speedLimitActive;
    private final double[] tyreKpa;
    private final int[] tyreState;
    /** Hora del dato en el coche (ms), o 0 si la nube no la da. */
    final long carTimeMs;

    private LeapStatus(Map<String, Object> m) {
        soc = asDouble(asIntObj(m.get("soc")));
        preciseSoc = asDouble(m.get("preciseSoc"));
        rangeKm = asDouble(asIntObj(m.get("liveRemainingRange")));
        chargeState = asIntObj(m.get("chargeState"));
        acCharge = asBool(m.get("acInputSlowCharge"));
        dcCharge = asBool(m.get("dcInputFastCharge"));
        chargeCompleted = asBool(m.get("chargeCompleted"));
        chargeRemainMin = asDouble(asIntObj(m.get("chargeRemainTime")));
        batteryVoltage = asDouble(m.get("batteryVoltage"));
        batteryCurrent = asDouble(m.get("batteryCurrent"));
        minBatteryTempC = asDouble(m.get("minBatteryTemp"));
        batteryThermalRequest = asIntObj(m.get("batteryThermalRequest"));
        odometerKm = asDouble(asIntObj(m.get("totalMileage")));
        speedKmh = asDouble(m.get("speed"));
        interiorTempC = asDouble(m.get("interiorTemp"));
        locked = asBool(m.get("driverDoorLockStatus"));
        doorsOpen = new Boolean[4];
        for (int i = 0; i < 4; i++) doorsOpen[i] = asBoolStrict(m.get(DOORS[i]));
        bootOpen = asBool(m.get("bbcmBackDoorStatus"));
        powerOn = asBoolStrict(m.get("bcmKeyPositionOn3"));
        speedLimitKmh = asDouble(m.get("speedLimit"));
        speedLimitActive = asBool(m.get("speedLimitActive"));
        tyreKpa = new double[4];
        tyreState = new int[4];
        for (int i = 0; i < 4; i++) {
            tyreKpa[i] = asDouble(asIntObj(m.get(TYRE_KPA[i])));
            Integer st = asIntObj(m.get(TYRE_STATE[i]));
            tyreState[i] = st == null ? -1 : st;
        }
        carTimeMs = dataTime(m);
    }

    /** Estado a partir del objeto «data» de la respuesta de /status/get/…, con sus señales ya fusionadas. */
    static LeapStatus parse(JSONObject data) {
        return new LeapStatus(merge(data));
    }

    /**
     * mergeSignalToNamed: los campos con nombre de primer nivel y, de «signal», los que falten (manda el de primer
     * nivel aunque sea null, igual que en LMB10). Las señales de posición se ignoran.
     */
    static Map<String, Object> merge(JSONObject data) {
        Map<String, Object> m = new HashMap<>();
        if (data == null) return m;
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, unwrap(data.opt(k)));
        }
        Object sig = data.opt("signal");
        if (sig instanceof JSONObject) {
            JSONObject s = (JSONObject) sig;
            for (Map.Entry<String, String> e : SIGNALS.entrySet()) {
                if (s.has(e.getKey()) && !m.containsKey(e.getValue())) m.put(e.getValue(), unwrap(s.opt(e.getKey())));
            }
        }
        return m;
    }

    private static Object unwrap(Object o) {
        return o == JSONObject.NULL ? null : o;
    }

    // ------------------------------------------------------------------ conversiones (las de LMB10)

    /** _asInt: un número se trunca; un texto solo si es un entero. */
    static Integer asIntObj(Object v) {
        if (v == null) return null;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            return (int) d;
        }
        try {
            String s = v.toString().trim();
            if (s.startsWith("+")) s = s.substring(1);
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** _asDouble: un número tal cual; un texto si se puede leer como número. NaN si no. */
    static double asDouble(Object v) {
        if (v == null) return Double.NaN;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** _asBool: verdadero si es true, un número distinto de 0, «1» o «true»; null si falta. */
    static Boolean asBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = v.toString();
        return s.equals("1") || s.toLowerCase(Locale.ROOT).equals("true");
    }

    /** _asBoolRaw del adaptador del Centinela: como asBool, pero un texto que no es 0/1/true/false es «no se sabe». */
    static Boolean asBoolStrict(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String s = v.toString().toLowerCase(Locale.ROOT);
        if (s.equals("1") || s.equals("true")) return Boolean.TRUE;
        if (s.equals("0") || s.equals("false")) return Boolean.FALSE;
        return null;
    }

    private static double asDouble(Integer v) {
        return v == null ? Double.NaN : v;
    }

    // ------------------------------------------------------------------ hora del dato

    private static final String[] TIME_KEYS = {"collectTime", "collect_time", "reportTime", "uploadTime", "sendTime"};

    /**
     * Hora del dato en el coche: collectTime y similares (como el Centinela de LMB10) o, si no, signal.sts o la señal 1
     * (milisegundos). Números de más de 1e11 son ms y de más de 1e8, segundos; también admite fechas ISO. 0 si no hay.
     */
    static long dataTime(Map<String, Object> m) {
        for (String k : TIME_KEYS) {
            long t = toEpochMs(m.get(k));
            if (t > 0) return t;
        }
        Object sig = m.get("signal");
        if (sig instanceof JSONObject) {
            JSONObject s = (JSONObject) sig;
            long t = toEpochMs(unwrap(s.opt("sts")));
            if (t > 0) return t;
            t = toEpochMs(unwrap(s.opt("1")));
            if (t > 0) return t;
        }
        return 0;
    }

    static long toEpochMs(Object v) {
        if (v == null) return 0;
        double n;
        if (v instanceof Number) {
            n = ((Number) v).doubleValue();
        } else {
            String s = v.toString().trim();
            try {
                n = Double.parseDouble(s);
            } catch (NumberFormatException e) {
                return parseIso(s);
            }
        }
        if (n > 1e11) return (long) n;
        if (n > 1e8) return (long) (n * 1000);
        return 0;
    }

    private static long parseIso(String s) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.ROOT);
                if (!p.endsWith("XXX")) f.setTimeZone(TimeZone.getDefault());
                f.setLenient(false);
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (ParseException | IllegalArgumentException ignored) {
                // siguiente formato
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ lo que se enseña

    /** % de batería: el preciso si lo hay, si no el entero. NaN sin dato. */
    double socBest() {
        return Double.isNaN(preciseSoc) ? soc : preciseSoc;
    }

    /** Enchufado: entrada de CA (lenta) o de CC (rápida) activa. */
    boolean pluggedIn() {
        return Boolean.TRUE.equals(acCharge) || Boolean.TRUE.equals(dcCharge);
    }

    /**
     * Cargando de verdad: chargeState activo, enchufado y sin la carga terminada (chargeState solo también salta con la
     * regeneración y con la carga terminada, como explica LMB10).
     */
    boolean charging() {
        return chargeState != null && chargeState != 0 && pluggedIn() && !Boolean.TRUE.equals(chargeCompleted);
    }

    /** Carga rápida (CC) en curso o enchufado a CC. */
    boolean dcPlugged() {
        return Boolean.TRUE.equals(dcCharge);
    }

    /** Potencia de la batería en kW (tensión × corriente / 1000) tal como la da el coche, o NaN. */
    double powerKw() {
        if (Double.isNaN(batteryVoltage) || Double.isNaN(batteryCurrent)) return Double.NaN;
        return batteryVoltage * batteryCurrent / 1000.0;
    }

    /** Presión de la rueda i en kPa (NaN sin dato). */
    double tyreKpa(int i) {
        return tyreKpa[i];
    }

    /** Presión de la rueda i en bar (kPa / 100). */
    double tyreBar(int i) {
        return tyreKpa[i] / 100.0;
    }

    /** Estado del TPMS de la rueda i (-1 sin dato). 0 y 1 son normales; más, aviso (convención de LMB10). */
    int tyreState(int i) {
        return tyreState[i];
    }

    boolean tyreStateAlert(int i) {
        return tyreState[i] > 1;
    }

    /** Una rueda está baja por debajo de esto (bar)… */
    static final double TYRE_LOW_BAR = 2.1;
    /** …o si va 0,3 bar (o más) por debajo de la media de las otras. */
    static final double TYRE_UNEVEN_BAR = 0.3;

    /** Rueda i baja: por debajo de TYRE_LOW_BAR o TYRE_UNEVEN_BAR por debajo de la media de las demás (con dato). */
    static boolean tyreLow(double[] bar, int i) {
        double v = bar[i];
        if (Double.isNaN(v)) return false;
        if (v < TYRE_LOW_BAR) return true;
        double sum = 0;
        int n = 0;
        for (int k = 0; k < bar.length; k++) {
            if (k == i || Double.isNaN(bar[k])) continue;
            sum += bar[k];
            n++;
        }
        return n > 0 && sum / n - v >= TYRE_UNEVEN_BAR - 1e-9;
    }

    boolean tyreLow(int i) {
        return tyreLow(new double[]{tyreBar(FL), tyreBar(FR), tyreBar(RL), tyreBar(RR)}, i);
    }

    /** Aviso en la rueda i: baja o con el aviso del propio coche (TPMS). */
    boolean tyreWarning(int i) {
        return tyreLow(i) || tyreStateAlert(i);
    }

    /** Puerta i abierta (null si no se sabe). */
    Boolean doorOpen(int i) {
        return doorsOpen[i];
    }

    /** Alguna puerta abierta (null si no llega ninguna). */
    Boolean anyDoorOpen() {
        boolean any = false;
        boolean known = false;
        for (Boolean d : doorsOpen) {
            if (d == null) continue;
            known = true;
            any |= d;
        }
        return known ? any : null;
    }

    /** Batería fría (menos carga rápida y menos regeneración): por debajo de 10 °C. */
    boolean coldBattery() {
        return !Double.isNaN(minBatteryTempC) && minBatteryTempC < COLD_BATTERY_C;
    }

    static final double COLD_BATTERY_C = 10;

    /** Resumen para el log: sin VIN, sin posición y sin nada personal. */
    String logLine() {
        StringBuilder b = new StringBuilder();
        b.append(String.format(Locale.US, "SoC %s %%, autonomía %s km", fmt(socBest(), 1), fmt(rangeKm, 0)));
        b.append(charging() ? (dcPlugged() ? ", cargando CC" : ", cargando CA")
                : Boolean.TRUE.equals(chargeCompleted) && pluggedIn() ? ", carga completa"
                : pluggedIn() ? ", enchufado" : ", sin enchufar");
        double kw = powerKw();
        if (!Double.isNaN(kw)) b.append(String.format(Locale.US, ", %.1f kW", kw));
        if (!Double.isNaN(odometerKm)) b.append(String.format(Locale.US, ", %.0f km", odometerKm));
        return b.toString();
    }

    private static String fmt(double v, int decimals) {
        return Double.isNaN(v) ? "?" : String.format(Locale.US, decimals == 0 ? "%.0f" : "%.1f", v);
    }
}
