package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.andrerinas.openheadunit.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Aviso de carga con calor (ThermalGuard, cada minuto): en el coche el móvil carga por el cable mientras codifica el
 * vídeo, y por encima del 80 % la carga es la que más calienta (viaje del 2026-10-11: de 33 a 41 °C cargando del 51 al
 * 100 %, en estado moderado todo el viaje). Android no deja a una app cortar la carga, así que se propone «Proteger
 * batería» (carga hasta el 80 %) con un botón a los ajustes de batería. Una vez al día como mucho, y solo si está
 * cargando por encima del 80 %: con la protección puesta no pasa de ahí, así que no vuelve a salir.
 */
final class ChargeHeatTip {
    static final double HOT_C = 39.0;
    static final int ABOVE_PCT = 80;
    private static final String CHANNEL = "hql_tips";
    private static final int ID = 7402;
    private static final String PREFS = "hql_charge_tip";
    private static final String K_DAY = "day";

    private final Context ctx;
    private final SharedPreferences sp;

    ChargeHeatTip(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Puro: ¿toca avisar? Cargando por encima del 80 %, la batería a 39 °C o más, y hoy aún no. */
    static boolean shouldTip(boolean charging, int pct, double tempC, String today, String lastDay) {
        return charging && pct > ABOVE_PCT && tempC >= HOT_C && !today.equals(lastDay);
    }

    /** Con los datos de la batería de este minuto. */
    void check(boolean charging, int pct, double tempC) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!shouldTip(charging, pct, tempC, today, sp.getString(K_DAY, ""))) return;
        sp.edit().putString(K_DAY, today).apply();
        L.i(String.format(Locale.US, "térmico: cargando al %d %% con la batería a %.1f °C: se propone «Proteger batería»", pct, tempC));
        try {
            post(pct, tempC);
        } catch (RuntimeException e) {
            L.w("térmico: no se pudo avisar de la carga: " + e.getMessage());
        }
    }

    private void post(int pct, double tempC) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_tips_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        // Los ajustes de batería (en Samsung, ahí está «Proteger batería»).
        Intent battery = new Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (battery.resolveActivity(ctx.getPackageManager()) == null) {
            battery = new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, ID, battery, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Str.get(R.string.hql_charge_heat_text, pct, String.format(Locale.getDefault(), "%.0f", tempC));
        nm.notify(ID, new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setContentTitle(Str.get(R.string.hql_charge_heat_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_charge_heat_action), pi).build())
                .setAutoCancel(true)
                .build());
    }
}
