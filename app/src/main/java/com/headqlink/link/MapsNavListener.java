package com.headqlink.link;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lee la notificación de navegación de Google Maps en el móvil (con el permiso «Acceso a notificaciones»): Android Auto
 * no nos manda ni el destino ni lo que falta (solo «navegando sí o no»), pero la notificación de Maps sí trae la ruta
 * en metros (barra de progreso: total y recorrido), la hora de llegada con el tráfico («Llegada: 21:19») y la próxima
 * indicación («hacia Av. …»). Con eso el plan elige la misma ruta que Google, usa sus tiempos y se rehace si Google
 * cambia de ruta. No se guarda ni se registra nada de su contenido (lleva calles).
 */
public class MapsNavListener extends NotificationListenerService {
    static final String MAPS = "com.google.android.apps.maps";

    /** Lo último que dijo Google Maps. */
    static final class Info {
        boolean active;
        /** Metros que faltan (total − recorrido) y total de la ruta; -1 si no se sabe. */
        int remainingM = -1;
        int totalM = -1;
        /** Hora de llegada (ms de pared); -1 si no se sabe. */
        long etaMs = -1;
        /** Cuándo empezó esta navegación (reloj de arranque; la primera notificación vista). */
        long startedMs;
        /** Texto de la próxima indicación (calle). */
        String instruction;
        long updatedMs;
    }

    private static volatile Info info = new Info();
    private static volatile boolean loggedFirst;

    private static volatile MapsNavListener instance;

    /**
     * ¿Hay ruta? Al acabar o al quitar la navegación, Maps deja a veces su notificación con 0 km: eso cuenta como
     * terminada.
     */
    static boolean routing(Info i) {
        return i != null && i.remainingM > 50;
    }

    static boolean routing() {
        return routing(ongoing());
    }

    /** Puro: ¿la misma ruta? (su longitud total, con margen: 3 % o 1 km; Maps la recalcula un poco al desviarse). */
    static boolean sameRoute(int totalA, int totalB) {
        if (totalA <= 0 || totalB <= 0) return totalA == totalB;
        return Math.abs(totalA - totalB) <= Math.max(1000, Math.max(totalA, totalB) * 0.03);
    }

    /**
     * La navegación de Maps mientras su notificación siga puesta, aunque Maps lleve rato sin actualizarla (con el coche
     * parado apenas la toca); si no, null. Para el planificador: con current() (que caduca a los 2 min) una navegación
     * parada parecía acabada y la siguiente actualización, nueva.
     */
    static Info ongoing() {
        Info i = info;
        if (!i.active) return null;
        MapsNavListener l = instance;
        if (l != null && !l.mapsNavigating()) {
            info = new Info();
            return null;
        }
        return i;
    }

    /** Lo último de Google Maps si es reciente (2 min), está navegando y su notificación sigue puesta; si no, null. */
    static Info current() {
        Info i = info;
        if (!i.active || SystemClock.elapsedRealtime() - i.updatedMs > 120_000) return null;
        // Si Maps se cerró de golpe (sin avisar de que quitaba la notificación), su ruta ya no vale.
        MapsNavListener l = instance;
        if (l != null && !l.mapsNavigating()) {
            info = new Info();
            return null;
        }
        return i;
    }

    private boolean mapsNavigating() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) return true;
            for (StatusBarNotification n : all) {
                if (MAPS.equals(n.getPackageName()) && n.getNotification() != null
                        && Notification.CATEGORY_NAVIGATION.equals(n.getNotification().category)) return true;
            }
            return false;
        } catch (RuntimeException e) {
            return true;
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    /** ¿Tiene HeadQLink el acceso a notificaciones? */
    static boolean granted(Context c) {
        String flat = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(c, MapsNavListener.class).flattenToString());
    }

    @Override
    public void onListenerConnected() {
        instance = this;
        L.i("Google Maps: leo su notificación de navegación (acceso a notificaciones concedido)");
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification n : all) onNotificationPosted(n);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || !Notification.CATEGORY_NAVIGATION.equals(n.category)) return;
        Bundle x = n.extras;
        Info i = parse(x.getInt(Notification.EXTRA_PROGRESS_MAX, -1), x.getInt(Notification.EXTRA_PROGRESS, -1),
                String.valueOf(x.getCharSequence(Notification.EXTRA_SUB_TEXT)), String.valueOf(x.getCharSequence(Notification.EXTRA_TITLE)),
                System.currentTimeMillis());
        i.updatedMs = SystemClock.elapsedRealtime();
        // La navegación empieza ahora si antes no había (o lo de antes era viejo): para saber si ya estaba al entrar.
        Info prev = info;
        // Solo cuenta lo que tiene km por delante: Maps deja a veces su notificación con 0 km tras acabar o quitar la ruta.
        // La misma navegación mientras su notificación siga puesta y la ruta mida lo mismo, aunque Maps tarde en
        // actualizarla (parado, minutos); otra ruta (otro destino) es otra navegación.
        boolean prevRoute = prev.active && prev.remainingM > 0 && sameRoute(prev.totalM, i.totalM);
        i.startedMs = prevRoute ? prev.startedMs : i.updatedMs;
        info = i;
        if (!loggedFirst && i.remainingM >= 0) {
            loggedFirst = true;
            // Sin calles ni horas: el log se exporta (qdauto §7.5).
            L.i(String.format(java.util.Locale.US, "Google Maps: navegando; ruta de %.1f km, quedan %.1f km%s", i.totalM / 1000.0,
                    i.remainingM / 1000.0, i.etaMs > 0 ? " · con hora de llegada" : ""));
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n != null && Notification.CATEGORY_NAVIGATION.equals(n.category)) {
            info = new Info();
            loggedFirst = false;
        }
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:.h](\\d{2})\\s*([AaPp]\\.?\\s?[Mm]\\.?)?");

    /** Puro: de los datos de la notificación (barra en metros, «Llegada: 21:19», indicación) a Info. */
    static Info parse(int max, int progress, String subText, String title, long nowMs) {
        Info i = new Info();
        i.active = true;
        if (max > 0 && progress >= 0 && progress <= max) {
            i.totalM = max;
            i.remainingM = max - progress;
        }
        i.etaMs = eta(subText, nowMs);
        i.instruction = title != null && !"null".equals(title) ? title : null;
        return i;
    }

    /** Puro: la hora de «Llegada: 21:19» (o «9:19 PM») como ms de pared, la próxima vez que llegue esa hora. */
    static long eta(String text, long nowMs) {
        if (text == null) return -1;
        Matcher m = TIME.matcher(text);
        if (!m.find()) return -1;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        String ampm = m.group(3);
        if (ampm != null) {
            boolean pm = Character.toLowerCase(ampm.charAt(0)) == 'p';
            if (h == 12) h = 0;
            if (pm) h += 12;
        }
        if (h > 23 || min > 59) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(nowMs);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        // Pasada la medianoche: si esa hora ya pasó (más de 10 min), es la de mañana.
        if (c.getTimeInMillis() < nowMs - 600_000) c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }
}
