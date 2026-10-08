package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.andrerinas.openheadunit.R;

import java.util.List;
import java.util.Locale;

/**
 * La carga en directo de ahora (una a la vez, mientras viva el proceso): la empieza RoutePlanner al ver que el coche
 * carga, la alimentan las lecturas de la nube (RoutePlanner con la sesión del coche, ChargeWatchService sin ella) y
 * avisa de lo que pasa: «ya puedes seguir» (notificación con sonido y, con el coche, voz y tarjeta en el panel), carga
 * lenta y, al terminar, la guarda en el historial (ChargeLog).
 */
final class ChargeWatch {
    static final String CHANNEL = "hql_charge";
    static final String CHANNEL_ALERT = "hql_charge_alert";
    static final int NOTIF_ID = 21;
    static final int ALERT_ID = 22;

    private static volatile ChargeSession session;

    private ChargeWatch() {
    }

    /** La carga en curso, o null. */
    static ChargeSession session() {
        ChargeSession s = session;
        return s == null || s.ended ? null : s;
    }

    /**
     * Empieza una carga. background: también fuera del coche (ChargeWatchService con su notificación), en una parada de
     * viaje o en carga rápida; en casa sin ruta solo se ve en el coche.
     */
    static void begin(Context ctx, ChargeSession s, boolean background) {
        session = s;
        L.i(String.format(Locale.US, "carga en directo: empieza al %.1f %% (%s%s)%s", s.startSoc, s.dc ? "CC" : "CA",
                s.charger == null ? "" : ", " + s.charger.name + " " + Math.round(s.charger.maxKw) + " kW"
                        + (s.charger.maxVolts > 0 ? " " + Math.round(s.charger.maxVolts) + " V" : ""),
                background ? "; vigilada también fuera del coche" : ""));
        if (!background || android.os.Build.VERSION.SDK_INT < 26) return;
        try {
            ctx.startForegroundService(new Intent(ctx, ChargeWatchService.class));
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException u otra restricción: queda el aviso en el coche.
            L.w("carga en directo: Android no deja vigilarla fuera del coche (" + e.getClass().getSimpleName() + "); solo en el coche");
        }
    }

    /** Una lectura de la nube (repetidas no cuentan): avisa de lo que pase. Desde cualquier hilo. */
    static void tick(Context ctx, CarCloud.Snapshot cs) {
        ChargeSession s = session;
        if (s == null || s.ended || cs == null || !cs.hasData()) return;
        if (s.demo != cs.demo) {
            // Se acabó la demostración (o empezó con una carga real): esa carga no sigue con los otros datos.
            synchronized (s) {
                s.ended = true;
            }
            if (session == s) session = null;
            ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
            L.i("carga en directo: termina (" + (s.demo ? "fin de la demostración" : "empieza la demostración") + ")");
            return;
        }
        LeapStatus st = cs.status;
        List<ChargeSession.Event> ev = s.observe(cs.dataTimeMs(), st.socBest(), st.powerKw(), st.pluggedIn(),
                Boolean.TRUE.equals(st.chargeCompleted) && !st.charging(), st.chargeRemainMin, DemoMode.wallClockMs());
        for (ChargeSession.Event e : ev) handle(ctx.getApplicationContext(), s, e);
    }

    private static void handle(Context ctx, ChargeSession s, ChargeSession.Event e) {
        switch (e) {
            case READY: {
                String title = Str.get(R.string.hql_live_ready_title);
                String text = readyText(s);
                L.i(String.format(Locale.US, "carga en directo: lista al %.1f %% (objetivo %.1f %%)", s.soc(), s.targetPct));
                alert(ctx, title, text, RoutePlanner.ALERT_READY);
                break;
            }
            case SLOW: {
                String title = Str.get(R.string.hql_live_slow_title);
                String text = Str.get(R.string.hql_live_slow_text, s.slowKw, s.slowExpectedKw);
                L.w(String.format(Locale.US, "carga en directo: lenta (%.0f kW; esperaba ~%.0f)", s.slowKw, s.slowExpectedKw));
                alert(ctx, title, text, RoutePlanner.ALERT_WARN);
                break;
            }
            case ENDED: {
                L.i(String.format(Locale.US, "carga en directo: termina al %.1f %% (+%.1f kWh en %d min, %d lecturas)%s", s.endSoc,
                        s.kwhAdded(), (s.endMs - s.startMs) / 60_000L, s.samples.size(), s.readyFired ? "" : " sin llegar al objetivo"));
                ChargeLog.add(ctx, s);
                if (session == s) session = null;
                ((NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
                break;
            }
        }
    }

    /** «64 % · llegas a Zunder Lleida con 12 %» y, si se puede, «hasta 85 % te ahorras la parada de …». */
    static String readyText(ChargeSession s) {
        StringBuilder b = new StringBuilder();
        if (!Double.isNaN(s.nextArrivePct) && !s.nextName.isEmpty()) {
            b.append(Str.get(R.string.hql_live_ready_text, s.soc(), s.nextName, s.nextArrivePct));
        } else {
            b.append(Str.get(R.string.hql_live_ready_short, s.soc()));
        }
        // En el aviso (y por voz) solo si compensa; en la pantalla siempre (en gris si no).
        String skip = s.skipWorth() ? skipText(s) : "";
        if (!skip.isEmpty()) b.append(". ").append(skip);
        return b.toString();
    }

    /** «Hasta 85 % te ahorras la parada de Lleida (+7 min aquí, −23 min allí)», o vacío. */
    static String skipText(ChargeSession s) {
        if (Double.isNaN(s.skipPct) || s.skipName.isEmpty()) return "";
        return Str.get(R.string.hql_live_skip, s.skipPct, s.skipName, DriveTab.duration(Math.round(s.skipExtraMin * 60)),
                DriveTab.duration(Math.round(s.skipSavedMin * 60)));
    }

    /** Notificación con sonido; con la sesión del coche, también la tarjeta del panel y la voz (si está activada). */
    private static void alert(Context ctx, String title, String text, int kind) {
        RoutePlanner.ChargeAlertListener l = RoutePlanner.chargeAlerts;
        if (l != null) {
            l.onChargeAlert(title, text, kind);
            if (new Config(ctx).planVoice()) VoiceAlert.say(ctx, title + ". " + text);
        }
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, Str.get(R.string.hql_live_alert_channel),
                NotificationManager.IMPORTANCE_HIGH));
        nm.notify(ALERT_ID, new Notification.Builder(ctx, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(kind == RoutePlanner.ALERT_READY ? R.color.hql_accent : R.color.hql_warn))
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
    }

    /** La notificación fija de la carga (Android 8+): «Cargando · 54 % → 64 %», «85 kW · lista ≈ 14:32», con la barra. */
    @android.annotation.TargetApi(26)
    static Notification ongoing(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_live_channel), NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(ctx.getColor(R.color.hql_accent))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(ctx));
        ChargeSession s = session();
        if (s == null) {
            return b.setContentTitle(Str.get(R.string.hql_live_title_wait)).build();
        }
        b.setContentTitle(title(s)).setContentText(line(s));
        if (!Double.isNaN(s.targetPct) && s.targetPct > s.startSoc) {
            int p = (int) Math.round(Math.max(0, Math.min(1, (s.soc() - s.startSoc) / (s.targetPct - s.startSoc))) * 100);
            b.setProgress(100, p, false);
        }
        return b.build();
    }

    /** «Cargando · 54 % → 64 %», «Lista: puedes seguir · 66 %» o «Cargando · 54 %». */
    static String title(ChargeSession s) {
        if (s.readyFired) return Str.get(R.string.hql_live_title_ready, s.soc());
        if (!Double.isNaN(s.targetPct)) return Str.get(R.string.hql_live_title_target, s.soc(), s.targetPct);
        return Str.get(R.string.hql_live_title, s.soc());
    }

    /** «85 kW · lista ≈ 14:32 (12 min)» o, sin objetivo, «7 kW · llena ≈ 18:40 (según el coche)». */
    static String line(ChargeSession s) {
        ChargeSession.Sample l = s.last();
        StringBuilder b = new StringBuilder();
        if (l != null && !Double.isNaN(l.kw)) b.append(String.format(Locale.getDefault(), "%.0f kW", l.kw));
        String w = when(s);
        if (!w.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(w);
        return b.toString();
    }

    /** «lista ≈ 14:32 (12 min)», «llena ≈ 18:40 (según el coche)» o vacío (ya lista o sin saberlo). */
    static String when(ChargeSession s) {
        double at = s.readyAtMs();
        if (s.readyFired || Double.isNaN(at)) return "";
        String hhmm = time((long) at);
        if (Double.isNaN(s.targetPct)) return Str.get(R.string.hql_live_full_at, hhmm);
        long left = Math.max(0, Math.round((at - DemoMode.wallClockMs()) / 1000));
        return Str.get(R.string.hql_live_ready_at, hhmm, DriveTab.duration(left));
    }

    static String time(long ms) {
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, Str.locale()).format(new java.util.Date(ms));
    }
}
