package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Vigía térmico del servicio (lo crea LinkService al arrancar el transporte): escucha el estado térmico de Android
 * (PowerManager.addThermalStatusListener, API 29+; antes no hay estado y no se adapta nada), decide con ThermalPolicy
 * y pasa el nivel al vídeo vivo (VideoHub: bitrate del encoder y ritmo del relay GL, sin reiniciar la sesión ni AA).
 * Cada minuto deja en el log unificado la temperatura de la batería, el estado térmico y el nivel. Todo en su hilo.
 */
final class ThermalGuard {
    /** Recibe cada cambio de nivel (hilo hql-thermal). */
    interface Sink {
        void onThermalLevel(int level, int status);
    }

    private static final long BATTERY_LOG_MS = 60_000;

    /** Último estado térmico (-1 = no se sabe) y nivel, para los resúmenes de sesión. */
    private static volatile int currentStatus = -1;
    private static volatile int currentLevel = ThermalPolicy.NORMAL;
    /** El vigía en marcha (para volver a aplicar el nivel al cambiar la «Protección térmica»). */
    private static volatile ThermalGuard active;

    /** Cambió la «Protección térmica» en los ajustes: el nivel actual se vuelve a aplicar con el modo nuevo. */
    static void onModeChanged() {
        ThermalGuard g = active;
        if (g == null || g.h == null) return;
        g.h.post(() -> {
            if (currentStatus >= 0) g.apply(currentStatus);
        });
    }

    private final Context ctx;
    /** El vídeo del motor QDAuto; puede llegar después (cable USB con el motor original: setSink). */
    private volatile Sink sink;
    private final ThermalPolicy policy = new ThermalPolicy();
    private final HandlerThread thread = new HandlerThread("hql-thermal");
    private Handler h;
    /** PowerManager.OnThermalStatusChangedListener (API 29); Object para no cargar la clase en Android 9 o menos. */
    private Object listener;

    private final Runnable check = () -> evaluate(policy.status());

    private final Runnable batteryLog = new Runnable() {
        @Override
        public void run() {
            logBattery();
            h.postDelayed(this, BATTERY_LOG_MS);
        }
    };

    /** sink: el vídeo del motor QDAuto, o null (motor original: solo se registra). */
    ThermalGuard(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }

    /**
     * El vídeo del motor QDAuto llega con el servicio ya en marcha (el cable USB con el motor original): desde ahora se
     * le aplica cada nivel, y ya el actual si no es el normal.
     */
    void setSink(Sink s) {
        sink = s;
        int status = currentStatus;
        if (s != null && currentLevel != ThermalPolicy.NORMAL && status >= 0) s.onThermalLevel(currentLevel, status);
    }

    static int status() {
        return currentStatus;
    }

    static int level() {
        return currentLevel;
    }

    void start() {
        thread.start();
        h = new Handler(thread.getLooper());
        active = this;
        QdTrace.i("HQL/Térmico", "protección térmica: " + ThermalPolicy.modeName(new Config(ctx).thermalMode()));
        if (Build.VERSION.SDK_INT >= 29) {
            startListener();
        } else {
            QdTrace.i("HQL/Térmico", "Android " + Build.VERSION.SDK_INT + ": sin estado térmico, sin adaptación");
        }
        h.post(batteryLog);
    }

    @android.annotation.TargetApi(29)
    private void startListener() {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm == null) return;
        PowerManager.OnThermalStatusChangedListener l = this::onStatus;
        try {
            pm.addThermalStatusListener(h::post, l);
            listener = l;
        } catch (RuntimeException e) {
            L.w("térmico: sin aviso del estado térmico: " + e.getMessage());
        }
        // Android avisa con el estado actual al registrarse; por si no, se lee una vez.
        h.post(() -> onStatus(pm.getCurrentThermalStatus()));
    }

    void stop() {
        if (h == null) return;
        if (Build.VERSION.SDK_INT >= 29 && listener != null) {
            try {
                ctx.getSystemService(PowerManager.class)
                        .removeThermalStatusListener((PowerManager.OnThermalStatusChangedListener) listener);
            } catch (RuntimeException ignored) {
            }
        }
        h.removeCallbacksAndMessages(null);
        thread.quitSafely();
        if (active == this) active = null;
        currentStatus = -1;
        currentLevel = ThermalPolicy.NORMAL;
    }

    /** Hilo hql-thermal. */
    private void onStatus(int status) {
        if (status != currentStatus) {
            currentStatus = status;
            PerfTrace.event("thermal", status);
            QdTrace.i("HQL/Térmico", "estado térmico " + status + " (" + statusName(status) + ")");
        }
        evaluate(status);
    }

    private void evaluate(int status) {
        if (status < 0) return;
        long now = SystemClock.elapsedRealtime();
        if (policy.update(status, now)) apply(status);
        h.removeCallbacks(check);
        long wait = policy.msUntilCheck(now);
        // Para bajar de nivel sin que cambie el estado: se vuelve a mirar al vencer la espera.
        if (wait >= 0) h.postDelayed(check, wait + 50);
    }

    private void apply(int status) {
        int level = policy.level();
        currentLevel = level;
        PerfTrace.event("thermal_level", level);
        String mode = new Config(ctx).thermalMode();
        String line = "térmico " + status + " → perfil " + ThermalPolicy.name(level) + " (protección " + ThermalPolicy.modeName(mode)
                + ": " + ThermalPolicy.describe(mode, level, 0) + ")";
        Sink s = sink;
        if (s == null) {
            L.i(line + "; motor original: solo se registra");
            return;
        }
        L.i(line);
        s.onThermalLevel(level, status);
    }

    /** Temperatura de la batería (difusión fija ACTION_BATTERY_CHANGED), estado térmico, margen y nivel. */
    private void logBattery() {
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return;
            int tenths = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            StringBuilder sb = new StringBuilder("batería ");
            if (tenths != Integer.MIN_VALUE) {
                sb.append(String.format(Locale.US, "%.1f °C", tenths / 10.0));
                PerfTrace.event("battery_temp", tenths);
            } else {
                sb.append("? °C");
            }
            if (lvl >= 0 && scale > 0) sb.append(" · ").append(lvl * 100 / scale).append(" %");
            sb.append(plugged != 0 ? " · enchufado" : " · sin enchufar");
            sb.append(" · estado térmico ").append(currentStatus < 0 ? "?" : String.valueOf(currentStatus));
            String headroom = headroom();
            if (!headroom.isEmpty()) sb.append(" · margen ").append(headroom);
            sb.append(" · nivel ").append(ThermalPolicy.name(currentLevel));
            QdTrace.i("HQL/Térmico", sb.toString());
        } catch (RuntimeException e) {
            L.w("térmico: no se pudo leer la batería: " + e.getMessage());
        }
    }

    /** Previsión de margen térmico a 10 s (API 30; 1,0 = límite de estrangulamiento), o "". */
    private String headroom() {
        if (Build.VERSION.SDK_INT < 30) return "";
        try {
            float v = ctx.getSystemService(PowerManager.class).getThermalHeadroom(10);
            return Float.isNaN(v) ? "" : String.format(Locale.US, "%.2f", v);
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String statusName(int status) {
        switch (status) {
            case 0:
                return "nada";
            case 1:
                return "ligero";
            case 2:
                return "moderado";
            case 3:
                return "grave";
            case 4:
                return "crítico";
            case 5:
                return "emergencia";
            case 6:
                return "apagado";
            default:
                return "?";
        }
    }
}
