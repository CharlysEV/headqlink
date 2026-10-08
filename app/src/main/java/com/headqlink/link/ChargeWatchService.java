package com.headqlink.link;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;

/**
 * Vigila una carga en directo fuera del coche: con la pantalla del coche apagada (te vas a tomar algo) sigue leyendo la
 * nube de Leapmotor (cada HUB_MS) y avisa en el móvil cuando ya puedes seguir. Notificación fija con el %, los kW y la
 * hora de lista; se para sola al terminar la carga (desenchufar, completa, sin datos o 3 h).
 */
public class ChargeWatchService extends Service {
    /** Cada cuánto mira la última lectura de la nube y rehace la notificación. */
    static final long TICK_MS = 10_000;

    private volatile boolean running;
    private Thread thread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                startForeground(ChargeWatch.NOTIF_ID, ChargeWatch.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(ChargeWatch.NOTIF_ID, ChargeWatch.ongoing(this));
            }
        } catch (RuntimeException e) {
            L.e("carga en directo: no se pudo pasar a primer plano", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            CarCloud.acquire(this, "charge");
            CarCloud.setChargeWatch(true);
            thread = new Thread(this::loop, "hql-charge");
            thread.setDaemon(true);
            thread.start();
        }
        return START_NOT_STICKY;
    }

    private void loop() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        while (running) {
            ChargeWatch.tick(this, CarCloud.snapshot());
            if (ChargeWatch.session() == null) break;
            nm.notify(ChargeWatch.NOTIF_ID, ChargeWatch.ongoing(this));
            SystemClock.sleep(TICK_MS);
        }
        running = false;
        stopSelf();
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15: el tiempo de dataSync se agota (6 h al día); una carga no dura tanto.
        L.w("carga en directo: Android corta la vigilancia (tiempo agotado)");
        running = false;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        running = false;
        CarCloud.setChargeWatch(false);
        CarCloud.release("charge");
        getSystemService(NotificationManager.class).cancel(ChargeWatch.NOTIF_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
