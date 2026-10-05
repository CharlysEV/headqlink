package com.headqlink.link;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Ahorro de batería: la exención estándar de Android y, en capas de fabricante que cierran apps en
 * segundo plano por su cuenta (Honor/Huawei, Xiaomi, Samsung, Oppo/Realme, OnePlus, Vivo, Asus), su
 * pantalla de inicio automático o de batería. Sin ellas, la conexión automática por Bluetooth y la
 * sesión con la pantalla apagada pueden cortarse.
 */
final class PowerHelper {
    /** Pantallas conocidas de cada fabricante (la primera que exista en el móvil). */
    private static final String[][] OEM = {
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings"},
    };

    private PowerHelper() {
    }

    static boolean unrestricted(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
    }

    /**
     * Pide a Android la exención de la optimización de batería (diálogo del sistema); si no hay diálogo, la lista de
     * apps sin optimizar y, si tampoco, la información de la app.
     */
    static void requestUnrestricted(Context ctx) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        } catch (RuntimeException e) {
            L.w("batería: sin el diálogo de la exención (" + e.getMessage() + "); abro la lista");
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            openAppDetails(ctx);
        }
    }

    /**
     * Batería de HeadQLink (Información de la app › Batería: «Sin restricciones»; en Samsung, también «Límites de uso en
     * segundo plano»). Si este Android no la abre directamente, el gestor del fabricante o la información de la app.
     */
    static void openAppBattery(Context ctx) {
        try {
            ctx.startActivity(new Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL",
                    Uri.parse("package:" + ctx.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            L.w("batería: sin la página de batería de la app (" + e.getMessage() + ")");
            openOem(ctx);
        }
    }

    /** Pantalla del gestor de energía del fabricante, o null si este móvil no tiene una conocida. */
    static Intent oemIntent(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String[] c : OEM) {
            Intent i = new Intent().setComponent(new ComponentName(c[0], c[1])).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(i, 0) != null) return i;
        }
        return null;
    }

    /** Nombre del fabricante para mostrar (Honor, Xiaomi…). */
    static String brand() {
        String b = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        return b.isEmpty() ? "" : b.substring(0, 1).toUpperCase(Locale.ROOT) + b.substring(1);
    }

    static void openOem(Context ctx) {
        Intent i = oemIntent(ctx);
        if (i == null) {
            openAppDetails(ctx);
            return;
        }
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            // Algunas no se dejan abrir desde otra app: la información de la app tiene la batería.
            L.w("batería: no se pudo abrir " + i.getComponent() + ": " + e.getMessage());
            openAppDetails(ctx);
        }
    }

    private static void openAppDetails(Context ctx) {
        openAppDetails(ctx, ctx.getPackageName());
    }

    /** Información de una app (permisos, batería, «Forzar detención»…). */
    static void openAppDetails(Context ctx, String pkg) {
        try {
            ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            L.w("no se pudo abrir la información de " + pkg + ": " + e.getMessage());
            ctx.startActivity(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    /** Fabricante Samsung (One UI: «Límites de uso en segundo plano»). */
    static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim());
    }
}
