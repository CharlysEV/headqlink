package com.headqlink.link;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapMessage;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.aap.protocol.Channel;
import com.andrerinas.openheadunit.aap.protocol.proto.Control;
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.VideoTap;

/**
 * Guardián del servidor de head unit de Android Auto cuando la sesión termina con el móvil
 * bloqueado. El servidor escucha en toda la red (también la WiFi) y solo se puede apagar desde los
 * ajustes de Android Auto, que no se pueden manejar con el móvil bloqueado. Mientras tanto:
 * - nuestra head unit sigue conectada, ocupando el servidor (Android Auto atiende una sola
 *   conexión), y con el foco de vídeo en "nativo" para que Android Auto no codifique imagen;
 * - al desbloquear, se apaga el servidor (tras la capa) y se suelta la conexión;
 * - si la conexión se pierde antes, una notificación avisa de que el servidor ha quedado abierto.
 */
public class AaGuardService extends Service {
    private static final String CHANNEL = "aa_guard";
    private static final String CHANNEL_ALERT = "aa_guard_alert";
    private static final int NOTIF_ID = 3;
    private static final int ALERT_ID = 4;
    private static final long CHECK_MS = 15_000;
    private static final long PING_MS = 5_000;
    /** Tras desbloquear: tiempo máximo para que el apagado del servidor cierre la sesión. */
    private static final long RELEASE_MAX_MS = 20_000;

    static volatile boolean parked;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean alerted;
    private boolean releasing;
    private long releaseStart;

    /** Aparca la sesión de AA hasta que el usuario desbloquee (llamar con AA conectado y el móvil bloqueado). */
    static void park(Context ctx) {
        L.i("AA guardián: sesión aparcada hasta desbloquear (servidor ocupado por nuestra head unit)");
        // Ya, antes de que se cierre la sesión con el coche: sin vista en el móvil y sin vídeo de AA.
        parked = true;
        VideoTap.setHeadless(true);
        CommManager cm = App.Companion.provide(ctx).getCommManager();
        if (cm.isConnected()) cm.send(new VideoFocusEvent(false, true));
        ctx.startForegroundService(new Intent(ctx, AaGuardService.class));
    }

    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) beginRelease();
        }
    };

    /**
     * Ping a Android Auto cada 5 s mientras está aparcado: sin vídeo, AA se queda en silencio y Open
     * Headunit da la conexión por perdida a los 15 s sin recibir nada. AA responde a cada ping.
     */
    private final Runnable ping = new Runnable() {
        @Override
        public void run() {
            CommManager cm = comm();
            if (releasing || !cm.isConnected()) return;
            cm.send(new AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE,
                    Control.PingRequest.newBuilder().setTimestamp(System.nanoTime()).build()));
            main.postDelayed(this, PING_MS);
        }
    };

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            CommManager cm = comm();
            if (releasing) {
                // Apagado en curso: la sesión cae cuando Android Auto para el servidor.
                if (!cm.isConnected() || System.currentTimeMillis() - releaseStart > RELEASE_MAX_MS) finish();
                else main.postDelayed(this, 1000);
                return;
            }
            if (!cm.isConnected() && !alerted) {
                alerted = true;
                L.w("AA guardián: se perdió la conexión con el servidor aparcado; aviso al usuario");
                alertOpenServer();
            }
            main.postDelayed(this, CHECK_MS);
        }
    };

    private CommManager comm() {
        return App.Companion.provide(this).getCommManager();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, Str.get(R.string.hql_guard_channel), NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.hql_ic_notif)
                .setColor(getColor(R.color.hql_accent))
                .setContentTitle("HeadQLink")
                .setContentText(Str.get(R.string.hql_guard_parked))
                .setOngoing(true)
                .build();
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } catch (RuntimeException e) {
            L.e("AA guardián: no se pudo pasar a primer plano", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_PRESENT));
            // Tras cerrar la sesión con el coche (que deja VideoTap sin modo sin pantalla): sin vista en
            // el móvil y sin vídeo de AA, como un coche que vuelve a su propia pantalla.
            main.postDelayed(() -> {
                VideoTap.setHeadless(true);
                VideoTap.setSink(null);
                CommManager cm = comm();
                if (cm.isConnected()) {
                    cm.send(new VideoFocusEvent(false, true));
                    L.i("AA guardián: foco de vídeo en nativo (AA deja de enviar imagen)");
                }
            }, 800);
            main.postDelayed(check, CHECK_MS);
            main.postDelayed(ping, PING_MS);
        }
        return START_NOT_STICKY;
    }

    /** Desbloqueado: se apaga el servidor (TouchService, tras la capa) y se suelta la sesión. */
    private void beginRelease() {
        if (releasing) return;
        releasing = true;
        releaseStart = System.currentTimeMillis();
        L.i("AA guardián: desbloqueado, apago el servidor y suelto la sesión");
        AaServerStarter.runPendingStop(this);
        main.removeCallbacks(check);
        main.postDelayed(check, 1000);
    }

    private void finish() {
        L.i("AA guardián: liberado");
        try {
            startService(new Intent(this, AapService.class).setAction(AapService.ACTION_STOP_SERVICE));
        } catch (RuntimeException e) {
            L.e("AA guardián: no se pudo parar Android Auto", e);
        }
        VideoTap.setHeadless(false);
        getSystemService(NotificationManager.class).cancel(ALERT_ID);
        stopSelf();
    }

    private void alertOpenServer() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, Str.get(R.string.hql_guard_alert_channel), NotificationManager.IMPORTANCE_HIGH));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        nm.notify(ALERT_ID, new Notification.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setColor(getColor(R.color.hql_warn))
                .setContentTitle(Str.get(R.string.hql_guard_alert_title))
                .setContentText(Str.get(R.string.hql_guard_alert_text))
                .setContentIntent(pi)
                .build());
    }

    @Override
    public void onDestroy() {
        parked = false;
        main.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(unlock);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
