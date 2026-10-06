package com.headqlink.link

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * Sondeo del cable USB (etiqueta `HQL/USB` en logcat y en el log unificado): qué dice Android del cable y del modo
 * accesorio, para saber si el C10 pone el móvil en modo accesorio (AOA) al enchufarlo y con qué nombres. No depende de
 * que el accesorio sea el de QDLink: se registra cualquiera, con fabricante, modelo, descripción, versión, URI y serie.
 *
 * Qué se mira:
 * - la difusión fija `android.hardware.usb.action.USB_STATE` (todos sus extras: `connected`, `configured`,
 *   `host_connected`, `accessory`, las funciones activas…);
 * - la alimentación (`ACTION_POWER_CONNECTED/DISCONNECTED` y `EXTRA_PLUGGED` de la batería);
 * - `UsbManager.getAccessoryList()`, con el permiso de HeadQLink para cada accesorio.
 */
internal object UsbProbe {
    const val TAG = "HQL/USB"

    /** Difusión fija del estado USB (oculta en el SDK: `UsbManager.ACTION_USB_STATE`). */
    const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"

    /** Lo que espera QDLink en su filtro de accesorio: fabricante «Neusoft», modelo «QDriveLink» (versión «1»). */
    const val CAR_MANUFACTURER = "Neusoft"
    const val CAR_MODEL = "QDriveLink"

    /** Línea del sondeo o del enlace por cable: logcat (`HQL/USB`) y log unificado. */
    @JvmStatic
    fun log(msg: String) {
        Log.i(TAG, msg)
        QdTrace.i(TAG, msg)
    }

    /** Ídem, y además al log de HeadQLink (Diagnóstico): los pasos que importan («conectado · abierto · sesión S…»). */
    @JvmStatic
    fun step(msg: String) {
        log(msg)
        L.fileOnly("I", "USB: $msg")
    }

    @JvmStatic
    fun warn(msg: String) {
        Log.w(TAG, msg)
        QdTrace.w(TAG, msg)
        L.fileOnly("W", "USB: $msg")
    }

    /** «Neusoft QDriveLink 1». */
    @JvmStatic
    fun shortName(acc: UsbAccessory?): String =
        if (acc == null) "?" else listOf(acc.manufacturer, acc.model, acc.version).joinToString(" ") { it?.trim().orEmpty().ifEmpty { "?" } }

    /** Todos los datos del accesorio (la serie solo con permiso, en Android 10+). */
    @JvmStatic
    fun describe(acc: UsbAccessory?): String {
        if (acc == null) return "ninguno"
        return "fabricante «${acc.manufacturer.orEmpty()}» · modelo «${acc.model.orEmpty()}» · descripción «${acc.description.orEmpty()}»" +
            " · versión «${acc.version.orEmpty()}» · uri «${acc.uri.orEmpty()}» · serie «${serial(acc)}»"
    }

    private fun serial(acc: UsbAccessory): String = try {
        acc.serial.orEmpty()
    } catch (_: SecurityException) {
        "sin permiso para leerla"
    } catch (_: RuntimeException) {
        "?"
    }

    /** El accesorio de la app de espejo del coche (el filtro de QDLink: Neusoft QDriveLink). */
    @JvmStatic
    fun isCarAccessory(acc: UsbAccessory?): Boolean =
        acc != null && same(CAR_MANUFACTURER, acc.manufacturer) && same(CAR_MODEL, acc.model)

    /** De Neusoft (el fabricante del espejo del C10), aunque el modelo no sea QDriveLink. */
    @JvmStatic
    fun isNeusoft(acc: UsbAccessory?): Boolean = acc != null && same(CAR_MANUFACTURER, acc.manufacturer)

    /** Sin distinguir mayúsculas ni espacios. El SDK los marca como no nulos, pero vienen del coche: por si acaso. */
    private fun same(want: String, got: String?): Boolean = want.equals(got?.trim(), ignoreCase = true)

    @JvmStatic
    fun accessoryFrom(intent: Intent?): UsbAccessory? = when {
        intent == null -> null
        Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        else -> @Suppress("DEPRECATION") intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
    }

    /** Los extras de USB_STATE en orden («accessory=true configured=true connected=true … »). */
    @JvmStatic
    fun describeState(i: Intent?): String {
        val b = i?.extras ?: return "sin datos"
        return b.keySet().sorted().joinToString(" ") { k ->
            @Suppress("DEPRECATION")
            "$k=${b.get(k)}"
        }
    }

    /** El modo accesorio según USB_STATE (extra `accessory`), o null si no lo dice. */
    @JvmStatic
    fun accessoryMode(i: Intent?): Boolean? = if (i?.hasExtra("accessory") == true) i.getBooleanExtra("accessory", false) else null

    @JvmStatic
    fun usbManager(ctx: Context): UsbManager? = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager

    /** `getAccessoryList()` (en Android solo hay uno a la vez), nunca null. */
    @JvmStatic
    fun accessories(ctx: Context): List<UsbAccessory> = try {
        usbManager(ctx)?.accessoryList?.filterNotNull() ?: emptyList()
    } catch (e: RuntimeException) {
        log("getAccessoryList falló: $e")
        emptyList()
    }

    @JvmStatic
    fun hasPermission(ctx: Context, acc: UsbAccessory): Boolean = try {
        usbManager(ctx)?.hasPermission(acc) == true
    } catch (_: RuntimeException) {
        false
    }

    @JvmStatic
    fun describeList(ctx: Context, list: List<UsbAccessory>): String =
        if (list.isEmpty()) "ninguno" else list.joinToString("; ") { "${describe(it)} (permiso de HeadQLink: ${if (hasPermission(ctx, it)) "sí" else "no"})" }

    /** Alimentación según la batería: «USB», «cargador», «sin enchufar»… */
    @JvmStatic
    fun power(ctx: Context): String {
        val plugged = try {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        } catch (_: RuntimeException) {
            -1
        }
        return when (plugged) {
            -1 -> "?"
            0 -> "sin enchufar"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "cargador"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "inalámbrica"
            else -> "enchufado ($plugged)"
        }
    }

    /** La difusión fija USB_STATE ahora, o null. */
    @JvmStatic
    fun stickyState(ctx: Context): Intent? = try {
        ctx.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
    } catch (_: RuntimeException) {
        null
    }

    /** Sondeo completo en una línea: USB_STATE, alimentación y accesorios. */
    @JvmStatic
    fun logState(ctx: Context, why: String) {
        log(
            "sondeo ($why): USB_STATE {${describeState(stickyState(ctx))}} · alimentación ${power(ctx)} · " +
                "accesorios: ${describeList(ctx, accessories(ctx))}",
        )
    }
}
