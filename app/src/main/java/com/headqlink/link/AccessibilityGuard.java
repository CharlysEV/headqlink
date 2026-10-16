package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.Locale;

/**
 * Vigila la accesibilidad «Qonecta+ táctil». Android la quita cuando cierra la app a la fuerza (ahorro de batería o de
 * memoria del fabricante: Samsung, Honor, Xiaomi…, o «Forzar detención»), y a veces al actualizar. Si estuvo activa en
 * este móvil y ya no lo está:
 * <ul>
 * <li>con el permiso WRITE_SECURE_SETTINGS (solo se puede dar desde un PC, por adb), la vuelve a activar sola;</li>
 * <li>si no, un aviso en el móvil: «Activar» abre su página de accesibilidad y «Que no se repita», los pasos de este
 * fabricante con el botón a su gestor de batería.</li>
 * </ul>
 * Se mira al arrancar la app, tras una actualización y cuando Android desconecta el servicio con la app viva.
 */
public final class AccessibilityGuard {
    /** HomeActivity: abrir la guía «Que no se repita» (desde el aviso). */
    static final String EXTRA_GUIDE = "acc_guide";
    private static final String CHANNEL = "hql_acc";
    private static final int ID = 7762;
    /** Ya se avisó de esta pérdida (se borra al volver a estar activa). */
    private static final String K_NOTIFIED = "acc_lost_notified";
    private static final long CHECK_DELAY_MS = 4000;

    enum Brand { SAMSUNG, HONOR, XIAOMI, OTHER }

    private AccessibilityGuard() {
    }

    /** Puro: el fabricante por Build.MANUFACTURER / BRAND. */
    static Brand brand(String manufacturer, String brandName) {
        String m = ((manufacturer == null ? "" : manufacturer) + " " + (brandName == null ? "" : brandName)).toLowerCase(Locale.ROOT);
        if (m.contains("samsung")) return Brand.SAMSUNG;
        if (m.contains("honor") || m.contains("huawei")) return Brand.HONOR;
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return Brand.XIAOMI;
        return Brand.OTHER;
    }

    /** Puro: la lista de servicios con el nuestro añadido (sin repetirlo). */
    static String withService(String enabled, String component) {
        if (enabled == null || enabled.trim().isEmpty()) return component;
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(component)) return enabled;
        }
        return enabled + ":" + component;
    }

    /** Dentro de unos segundos (Android tarda en apuntar el cambio), en el hilo principal. */
    public static void checkLater(Context ctx, String when) {
        Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> check(app, when), CHECK_DELAY_MS);
    }

    /** ¿Se ha perdido? Si sí: la vuelve a activar (si puede) o avisa una vez. Devuelve si está activa al acabar. */
    static boolean check(Context c, String when) {
        try {
            L.init(c);
            Config cfg = new Config(c);
            if (Checklist.accessibilityEnabled(c)) {
                if (cfg.getBool(K_NOTIFIED)) {
                    cfg.putBool(K_NOTIFIED, false);
                    NotificationManager nm = c.getSystemService(NotificationManager.class);
                    if (nm != null) nm.cancel(ID);
                }
                return true;
            }
            if (!cfg.getBool(UpdateReceiver.ACC_WAS_ON)) return false;
            if (restore(c)) {
                L.i("accesibilidad: Android la había quitado (" + when + "); la vuelvo a activar sola (permiso dado desde el PC)");
                return true;
            }
            if (cfg.getBool(K_NOTIFIED)) return false;
            Brand b = brand(Build.MANUFACTURER, Build.BRAND);
            L.w("accesibilidad: Android la ha quitado (" + when + ") · " + b.name().toLowerCase(Locale.ROOT)
                    + " · batería " + (PowerHelper.unrestricted(c) ? "sin restricciones" : "optimizada")
                    + (Checklist.restrictedSettings(c) ? " · ajustes restringidos" : "") + ": aviso en el móvil");
            notifyLost(c);
            cfg.putBool(K_NOTIFIED, true);
            return false;
        } catch (RuntimeException e) {
            L.w("accesibilidad: no se pudo comprobar: " + e);
            return false;
        }
    }

    /** Con WRITE_SECURE_SETTINGS (adb shell pm grant): la vuelve a poner en la lista de Android. */
    static boolean restore(Context c) {
        if (c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            String comp = new ComponentName(c, TouchService.class).flattenToString();
            String cur = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withService(cur, comp));
            Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return Checklist.accessibilityEnabled(c);
        } catch (RuntimeException e) {
            L.w("accesibilidad: no se pudo volver a activar sola: " + e);
            return false;
        }
    }

    private static void notifyLost(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_acc_lost_channel),
                NotificationManager.IMPORTANCE_HIGH));
        PendingIntent open = PendingIntent.getActivity(c, ID, settingsIntent(c),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent guide = new Intent(c, HomeActivity.class).putExtra(EXTRA_GUIDE, true).putExtra(HomeActivity.EXTRA_NO_AUTOCONNECT, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent guidePi = PendingIntent.getActivity(c, ID + 1, guide, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = Str.get(R.string.hql_acc_lost_text);
        nm.notify(ID, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setContentTitle(Str.get(R.string.hql_acc_lost_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_enable), open).build())
                .addAction(new Notification.Action.Builder(null, Str.get(R.string.hql_acc_prevent), guidePi).build())
                .setAutoCancel(true)
                .build());
    }

    /** La página de «Qonecta+ táctil» (Android 13+) o la lista de servicios de accesibilidad. */
    static Intent settingsIntent(Context c) {
        Intent i = Build.VERSION.SDK_INT >= 33
                ? new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(c, TouchService.class).flattenToString())
                : new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (c.getPackageManager().resolveActivity(i, 0) == null) i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** «Que no se repita»: por qué pasa y qué tocar en este móvil, con los botones a cada sitio. */
    static void showGuide(Activity a) {
        Brand b = brand(Build.MANUFACTURER, Build.BRAND);
        String step;
        switch (b) {
            case SAMSUNG:
                step = Str.get(R.string.hql_acc_guide_samsung);
                break;
            case HONOR:
                step = Str.get(R.string.hql_acc_guide_honor, PowerHelper.brand());
                break;
            case XIAOMI:
                step = Str.get(R.string.hql_acc_guide_xiaomi);
                break;
            default:
                step = Str.get(R.string.hql_acc_guide_other);
        }
        boolean restricted = !Checklist.accessibilityEnabled(a) && Checklist.restrictedSettings(a);
        String msg = Str.get(R.string.hql_acc_guide, step) + (restricted ? "\n\n" + Str.get(R.string.hql_acc_guide_restricted) : "");
        L.i("accesibilidad: guía «que no se repita» (" + b.name().toLowerCase(Locale.ROOT) + ")");
        AlertDialog.Builder d = new AlertDialog.Builder(a)
                .setTitle(Str.get(R.string.hql_acc_guide_title))
                .setMessage(msg)
                .setPositiveButton(Str.get(R.string.hql_acc_guide_battery), (x, y) -> {
                    if (PowerHelper.unrestricted(a)) PowerHelper.openAppBattery(a);
                    else PowerHelper.requestUnrestricted(a);
                })
                .setNegativeButton(Str.get(R.string.hql_close), null);
        if (restricted) {
            d.setNeutralButton(Str.get(R.string.hql_req_app_info), (x, y) -> PowerHelper.openAppDetails(a, a.getPackageName()));
        } else if (b == Brand.SAMSUNG) {
            d.setNeutralButton(Str.get(R.string.hql_open) + " Samsung", (x, y) -> PowerHelper.openAppBattery(a));
        } else if (PowerHelper.oemIntent(a) != null) {
            d.setNeutralButton(Str.get(R.string.hql_battery_oem, PowerHelper.brand()), (x, y) -> PowerHelper.openOem(a));
        }
        d.show();
    }
}
