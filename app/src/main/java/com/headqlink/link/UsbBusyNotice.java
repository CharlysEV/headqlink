package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * Aviso en el móvil: el cable del coche está puesto pero no se puede abrir porque lo tiene otra app (UsbLink, a la
 * segunda apertura fallida). Con la app QDLink original instalada, al tocarlo se abre su ficha para «Forzar detención».
 */
final class UsbBusyNotice {
    private static final String CHANNEL = "hql_usb";
    private static final int ID = 7741;

    private UsbBusyNotice() {
    }

    static void show(Context c) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_usb_busy_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            boolean qd = installed(c);
            Intent open = qd
                    ? new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + Checklist.QDLINK_PKG))
                    : new Intent(c, HomeActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = Str.get(qd ? R.string.hql_usb_busy_qdlink : R.string.hql_usb_busy_other);
            Notification n = new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setContentTitle(Str.get(R.string.hql_usb_busy_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(ID, n);
            L.i("USB: aviso en el móvil: el cable lo tiene otra app" + (qd ? " (QDLink instalada: el aviso abre su ficha)" : ""));
        } catch (RuntimeException e) {
            L.w("USB: no se pudo avisar del cable ocupado: " + e);
        }
    }

    private static final int ID_DIAGNOSIS = 7742;

    /** Un diagnóstico del cable (UsbDiagnosis): aviso normal, que abre la app. */
    static void showDiagnosis(Context c, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_usb_busy_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            PendingIntent pi = PendingIntent.getActivity(c, ID_DIAGNOSIS, HomeActivity.openIntent(c),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(ID_DIAGNOSIS, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException e) {
            L.w("USB: no se pudo avisar del diagnóstico: " + e);
        }
    }

    static void clear(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID);
    }

    private static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(Checklist.QDLINK_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
