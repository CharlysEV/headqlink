package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

import java.util.Locale;

/**
 * Conexión automática: cuando el móvil se conecta por Bluetooth al coche (por su nombre, ajustable
 * en la app), HeadQLink se pone a esperar al coche como si se pulsara Conectar.
 * Si el Bluetooth del coche se va sin haber llegado a conectar, se para (no se queda buscando).
 *
 * Android solo deja arrancar un servicio en primer plano desde segundo plano en algunos casos; si
 * no lo permite, queda una notificación «Toca para conectar». Quitar la optimización de batería
 * de HeadQLink lo evita (se ofrece en la app al activar la opción).
 */
public final class CarBtReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "auto";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(action) && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) return;
        // Antes del primer desbloqueo no hay ajustes que leer.
        UserManager um = ctx.getSystemService(UserManager.class);
        if (um != null && !um.isUserUnlocked()) return;
        Config cfg = new Config(ctx);
        if (!cfg.btAutoConnect()) return;
        BluetoothDevice dev;
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }
        String name = deviceName(dev);
        if (name == null || !matches(name, cfg.btAutoName())) return;
        L.init(ctx);
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        L.i("Bluetooth del coche " + (connected ? "conectado" : "desconectado") + ": " + name);
        Intent i = new Intent(ctx, LinkService.class)
                .setAction(connected ? LinkService.ACTION_BT_CAR : LinkService.ACTION_BT_CAR_GONE);
        if (!connected) {
            // Solo si está en marcha: startService a un servicio parado lo arrancaría.
            if (LinkState.running) {
                try {
                    ctx.startService(i);
                } catch (RuntimeException e) {
                    L.w("Bluetooth: no se pudo avisar al servicio: " + e.getMessage());
                }
            }
            return;
        }
        if (LinkState.running) return;
        try {
            ctx.startForegroundService(i);
        } catch (RuntimeException e) {
            // ForegroundServiceStartNotAllowedException (Android 12+) u otra restricción.
            L.w("Bluetooth: Android no deja arrancar en segundo plano (" + e.getClass().getSimpleName() + "): aviso con notificación");
            notifyTapToConnect(ctx);
        }
    }

    static boolean matches(String deviceName, String wanted) {
        String w = wanted == null ? "" : wanted.trim().toLowerCase(Locale.ROOT);
        return !w.isEmpty() && deviceName.toLowerCase(Locale.ROOT).contains(w);
    }

    private static String deviceName(BluetoothDevice dev) {
        if (dev == null) return null;
        try {
            return dev.getName();
        } catch (SecurityException e) {
            // Falta el permiso BLUETOOTH_CONNECT (se pide al activar la opción).
            L.w("Bluetooth: sin permiso para leer el nombre del dispositivo");
            return null;
        }
    }

    private static void notifyTapToConnect(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_bt_auto_title), NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, new Intent(ctx, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(Str.get(R.string.hql_car_detected))
                .setContentText(Str.get(R.string.hql_tap_to_connect))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(2, n);
    }
}
