package com.headqlink.link;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Activa o apaga el log en directo (LiveLog) desde el PC del desarrollador, por adb: solo la shell de adb tiene el
 * permiso que exige este receptor (android.permission.DUMP), así que ninguna otra app ni nadie desde fuera puede
 * activarlo.
 *
 * adb shell am broadcast -n com.headqlink.app/com.headqlink.link.LiveLogReceiver --ez on true --es key CLAVE --ei hours 12
 * adb shell am broadcast -n com.headqlink.app/com.headqlink.link.LiveLogReceiver --ez on false
 */
public final class LiveLogReceiver extends BroadcastReceiver {
    static final int MAX_HOURS = 24;

    @Override
    public void onReceive(Context c, Intent i) {
        L.init(c);
        if (!i.getBooleanExtra("on", false)) {
            LiveLog.disable(c);
            return;
        }
        String key = i.getStringExtra("key");
        if (key == null || key.length() < 16) {
            L.w("log en directo: clave demasiado corta (16 caracteres o más)");
            return;
        }
        int hours = Math.max(1, Math.min(MAX_HOURS, i.getIntExtra("hours", 2)));
        LiveLog.enable(c, key, System.currentTimeMillis() + hours * 3600_000L);
    }
}
