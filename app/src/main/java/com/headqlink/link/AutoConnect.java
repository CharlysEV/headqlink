package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Conexión automática, la comparten la pantalla principal y Ajustes (SettingsActivity): al detectar el Bluetooth del
 * coche (CarBtReceiver) y al enchufar el cable del coche (UsbAccessoryActivity).
 */
final class AutoConnect {
    private AutoConnect() {
    }

    /**
     * Conexión automática por Bluetooth: necesita leer el nombre de los dispositivos (permiso «Dispositivos cercanos») y,
     * para que Android deje arrancar en segundo plano, que Qonecta+ no tenga la optimización de batería. Se puede cambiar
     * el texto que se busca en el nombre. done (puede ser null): al cerrar el diálogo con «Guardar» o «Cancelar» (este lo
     * deja apagado), para que quien llama repinte su interruptor y el nombre con lo guardado.
     */
    static void setBt(Activity a, Config cfg, boolean on, Runnable done) {
        cfg.putBool(Config.BT_AUTO, on);
        if (!on) return;
        if (a.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Checklist.markAsked(a, Checklist.KEY_BT);
            a.requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 3);
        }
        android.os.PowerManager pm = a.getSystemService(android.os.PowerManager.class);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(24 * a.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        TextView info = new TextView(a);
        info.setText(Str.get(R.string.hql_bt_auto_info)
                + (pm.isIgnoringBatteryOptimizations(a.getPackageName()) ? "" : "\n\n" + Str.get(R.string.hql_bt_auto_battery)));
        EditText name = M3.field(a);
        name.setSingleLine(true);
        name.setText(cfg.btAutoName());
        box.addView(info);
        box.addView(M3.outlined(name));
        new MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_bt_auto_title))
                .setView(box)
                .setPositiveButton(Str.get(R.string.hql_save), (d, w) -> {
                    String n = name.getText().toString().trim();
                    cfg.setBtAutoName(n.isEmpty() ? "Leapmotor_BT" : n);
                    if (!pm.isIgnoringBatteryOptimizations(a.getPackageName())) {
                        try {
                            a.startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:" + a.getPackageName())));
                        } catch (RuntimeException e) {
                            L.w("no se pudo abrir el ajuste de batería: " + e.getMessage());
                        }
                    }
                    if (done != null) done.run();
                })
                .setNegativeButton(Str.get(R.string.hql_cancel), (d, w) -> {
                    cfg.putBool(Config.BT_AUTO, false);
                    if (done != null) done.run();
                })
                .show();
    }

    /** Arrancar solo al enchufar el cable del coche (Config.USB_AUTO); al apagarlo, un aviso de lo que pasará. */
    static void setUsb(Activity a, Config cfg, boolean on) {
        cfg.putBool(Config.USB_AUTO, on);
        L.i("conexión automática por cable: " + (on ? "sí" : "no"));
        if (!on) android.widget.Toast.makeText(a, Str.get(R.string.hql_usb_auto_off), android.widget.Toast.LENGTH_LONG).show();
    }
}
