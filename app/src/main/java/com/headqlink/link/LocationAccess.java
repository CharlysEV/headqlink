package com.headqlink.link;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;

/**
 * Acceso del móvil a la ubicación, para el GPS de los paneles del coche con el móvil bloqueado.
 *
 * Con la ubicación solo «mientras se usa», Android da el GPS a la app si está a la vista o si tiene un servicio en
 * primer plano de tipo «ubicación» que pasó a primer plano **con la app a la vista**. Si el servicio pasa a primer plano
 * con la app en segundo plano (Bluetooth del coche, el widget, la automatización de Android Auto delante…), o vuelve a
 * llamar a startForeground así (Android 12+ lo vuelve a evaluar), el proceso no tiene ubicación al bloquear el móvil:
 * las peticiones siguen registradas pero no llega ninguna posición. Con «Permitir todo el tiempo» no depende de nada.
 */
final class LocationAccess {
    private LocationAccess() {
    }

    static boolean fine(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** «Permitir todo el tiempo» (antes de Android 10 no existe: con la ubicación basta). */
    static boolean background(Context c) {
        if (!fine(c)) return false;
        if (Build.VERSION.SDK_INT < 29) return true;
        return c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Si Android deja ahora a la app leer la ubicación precisa (la operación FINE_LOCATION evaluada con el estado actual
     * del proceso: con «mientras se usa», permitida solo con la app a la vista o con el servicio de ubicación que la
     * conserva). null si no se puede saber (antes de Android 10 o sin AppOpsManager).
     */
    static Boolean allowedNow(Context c) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            AppOpsManager ops = c.getSystemService(AppOpsManager.class);
            if (ops == null) return null;
            int mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), c.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** «permitido», «denegado» o «sin saber», para el log. */
    static String allowedNowText(Context c) {
        Boolean a = allowedNow(c);
        return a == null ? "sin saber" : a ? "permitido" : "denegado";
    }

    /** Importancia del proceso ahora (RunningAppProcessInfo.IMPORTANCE_*). */
    static int importance() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        try {
            ActivityManager.getMyMemoryState(info);
        } catch (RuntimeException e) {
            return ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;
        }
        return info.importance;
    }

    /** HeadQLink está delante (una actividad, o la pantalla del coche con el móvil desbloqueado). */
    static boolean appVisible() {
        return importance() <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    static String importanceName(int imp) {
        switch (imp) {
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND:
                return "delante";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE:
                return "servicio en primer plano";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE:
                return "visible";
            case ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE:
                return "perceptible";
            default:
                return "segundo plano (" + imp + ")";
        }
    }
}
