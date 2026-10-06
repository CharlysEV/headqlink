package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

/**
 * Cable USB del coche (experimental): Android la lanza con USB_ACCESSORY_ATTACHED cuando el coche pone el móvil en modo
 * accesorio con «Neusoft / QDriveLink» (res/xml/hql_usb_accessory_filter.xml, el mismo filtro que QDLink) y, al lanzarla,
 * da a HeadQLink el permiso para ese accesorio. Transparente: registra el sondeo (HQL/USB), arranca LinkService por el
 * cable (sea cual sea la conexión elegida: el cable tiene prioridad sobre el Wi-Fi mientras está puesto) y se cierra.
 */
public final class UsbAccessoryActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        L.init(this);
        handle(getIntent());
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        UsbAccessory acc = UsbProbe.accessoryFrom(intent);
        String action = intent != null ? intent.getAction() : null;
        UsbProbe.step("aviso de Android " + action + ": accesorio " + UsbProbe.describe(acc));
        UsbProbe.logState(this, "actividad del accesorio");
        if (!UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action) || acc == null) return;
        if (!UsbProbe.isNeusoft(acc)) {
            UsbProbe.log("el accesorio no es el del coche (se espera " + UsbProbe.CAR_MANUFACTURER + " " + UsbProbe.CAR_MODEL + "): no hago nada");
            return;
        }
        // La actividad es pública: solo cuenta un accesorio de verdad (Android da el permiso antes de lanzarla).
        if (!UsbProbe.hasPermission(this, acc) && !UsbProbe.accessories(this).contains(acc)) {
            UsbProbe.warn("aviso de accesorio sin permiso y que no está conectado: lo ignoro");
            return;
        }
        Intent svc = new Intent(this, LinkService.class)
                .setAction(LinkService.ACTION_USB_ATTACHED)
                .putExtra(UsbManager.EXTRA_ACCESSORY, acc);
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, svc);
            UsbProbe.step("accesorio " + UsbProbe.shortName(acc) + " conectado: " + (LinkState.running
                    ? "se lo paso al enlace en marcha (el cable tiene prioridad)" : "arranco el enlace por cable"));
        } catch (RuntimeException e) {
            UsbProbe.warn("no se pudo arrancar el enlace por cable: " + e);
        }
    }
}
