package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;

import java.util.Locale;

/**
 * Resumen del viaje en el móvil al desconectar: fps medios, reconexiones, cortes, calor y el acierto de la ruta. Lo mismo
 * que va al registro (SessionSummary.tripSummary), en una notificación, para no tener que pedir el registro para saber
 * cómo fue. Solo con un viaje de al menos MIN_MINUTES con vídeo y el ajuste «Resumen del viaje» puesto.
 */
final class TripNotice {
    private static final String CHANNEL = "hql_trip";
    private static final int ID = 7751;
    static final double MIN_MINUTES = 2;

    private TripNotice() {
    }

    /** Puro: el texto del aviso (líneas), o null si no toca. */
    static String text(SessionSummary.TripStats s, double peakC, double routePredictedKwh, double routeRealKwh) {
        if (s == null || s.getVideoMinutes() < MIN_MINUTES) return null;
        StringBuilder b = new StringBuilder();
        b.append(Str.get(R.string.hql_trip_notice_fps, s.getFps())).append(" · ")
                .append(Str.plural(R.plurals.hql_trip_notice_reconnects, s.getReconnects())).append(" · ")
                .append(Str.plural(R.plurals.hql_trip_notice_cuts, s.getStalls()));
        if (s.getThermalMax() >= 0 || !Double.isNaN(peakC)) {
            b.append('\n').append(Str.get(R.string.hql_trip_notice_heat, Math.max(0, s.getThermalMax()), Double.isNaN(peakC) ? 0.0 : peakC));
        }
        if (!Double.isNaN(routePredictedKwh) && !Double.isNaN(routeRealKwh)) {
            b.append('\n').append(Str.get(R.string.hql_trip_notice_route, routePredictedKwh, routeRealKwh));
        }
        return b.toString();
    }

    static void show(Context c, Config cfg, SessionSummary.TripStats s) {
        try {
            if (s == null || !cfg.tripNotice() || !Config.isAa(cfg.mode())) return;
            double predicted = Double.NaN;
            double real = Double.NaN;
            RoutePlanner rp = RoutePlanner.get();
            RoutePlanner.Plan p = rp == null ? null : rp.plan();
            if (p != null) {
                predicted = p.arrivalPredictedKwh;
                real = p.arrivalRealKwh;
            }
            String text = text(s, ThermalGuard.tripPeakC(), predicted, real);
            if (text == null) return;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_trip_notice_channel),
                    NotificationManager.IMPORTANCE_LOW));
            PendingIntent pi = PendingIntent.getActivity(c, ID, HomeActivity.openIntent(c),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String title = Str.get(R.string.hql_trip_notice_title, DriveTab.duration(Math.round(s.getMinutes() * 60)));
            nm.notify(ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setContentTitle(title)
                    .setContentText(text.split("\n")[0])
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            L.i(String.format(Locale.US, "viaje: resumen en el móvil (%.0f min, %.1f fps)", s.getMinutes(), s.getFps()));
        } catch (RuntimeException e) {
            L.w("viaje: no se pudo avisar del resumen: " + e);
        }
    }
}
