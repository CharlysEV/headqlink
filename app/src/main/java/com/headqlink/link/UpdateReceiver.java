package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Tras instalar una actualización (ACTION_MY_PACKAGE_REPLACED): Android, y algunos fabricantes, desactivan la
 * accesibilidad de la app al actualizarla desde un APK, y hasta que no se abre la app y se mira la «Comprobación» nadie se
 * entera (el coche se queda en negro). Si estaba activa antes (TouchService la apunta) y ahora no lo está, un aviso en
 * el móvil que abre los ajustes de accesibilidad.
 */
public final class UpdateReceiver extends BroadcastReceiver {
    /** La accesibilidad llegó a estar activa en este móvil (TouchService.onServiceConnected). */
    static final String ACC_WAS_ON = "accessibility_was_on";
    private static final String CHANNEL = "hql_update";
    private static final int ID = 7761;
    /** Android apaga el servicio al actualizar antes de este aviso, pero por si tarda un poco. */
    private static final long CHECK_DELAY_MS = 3000;

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        Context app = c.getApplicationContext();
        PendingResult pr = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                check(app);
            } finally {
                pr.finish();
            }
        }, CHECK_DELAY_MS);
    }

    static void check(Context c) {
        try {
            L.init(c);
            boolean on = Checklist.accessibilityEnabled(c);
            boolean was = new Config(c).getBool(ACC_WAS_ON);
            L.i("actualización instalada: accesibilidad " + (on ? "activa" : "desactivada") + (was ? " (estaba activa)" : ""));
            if (on || !was) return;
            // La vuelve a activar sola si puede (permiso dado desde el PC); si no, el aviso de la actualización.
            if (AccessibilityGuard.restore(c)) {
                L.i("actualización instalada: accesibilidad vuelta a activar sola");
                return;
            }
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_update_acc_channel),
                    NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, ID, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String text = Str.get(R.string.hql_update_acc_text);
            nm.notify(ID, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_notif)
                    .setContentTitle(Str.get(R.string.hql_update_acc_title))
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (RuntimeException e) {
            L.w("actualización: no se pudo avisar de la accesibilidad: " + e);
        }
    }
}
