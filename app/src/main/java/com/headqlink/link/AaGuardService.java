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
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.VideoTap;

/**
 * Guardián del servidor de head unit de Android Auto cuando todo se cerró con el móvil bloqueado (tras «Esperar al
 * coche»). El servidor escucha en toda la red (también la WiFi) y solo se puede apagar desde los ajustes de Android Auto,
 * que no se pueden manejar con el móvil bloqueado. Mientras tanto:
 * - nuestra head unit sigue conectada, ocupando el servidor (Android Auto atiende una conexión a la vez), aparcada
 *   (AaPark: foco de vídeo en "nativo" y ping);
 * - al desbloquear, se apaga el servidor (tras la capa) y se suelta la conexión;
 * - si el enlace vuelve a arrancar (Bluetooth del coche, Conectar), le pasa AA aparcado (handOver) y se va sin
 *   apagar nada: el coche lo usa al instante;
 * - si la conexión se pierde antes, una notificación avisa de que el servidor ha quedado abierto.
 *
 * Con el arranque manual del servidor (sin accesibilidad) el enlace no lo usa al cerrar: no hay apagado que esperar
 * (LinkLifecycle.ShutdownPlan.LEAVE_SERVER_ON). Si tenía AA aparcado cuando se eligió el manual, hace lo mismo que con el
 * automático salvo apagar el servidor: al desbloquear suelta AA con orden (ByeBye) y su servidor sigue atendiendo.
 */
public class AaGuardService extends Service {
    private static final String CHANNEL = "aa_guard";
    private static final String CHANNEL_ALERT = "aa_guard_alert";
    private static final int NOTIF_ID = 3;
    private static final int ALERT_ID = 4;
    private static final long CHECK_MS = 15_000;
    /** Tras desbloquear: tiempo máximo para que el apagado del servidor cierre la sesión. */
    private static final long RELEASE_MAX_MS = 20_000;

    /** El guardián tiene AA aparcado (servicio en marcha y sin soltarlo). */
    static volatile boolean active;
    /** handOver: al destruirse no se toca AA (sigue aparcado para el enlace). */
    private static volatile boolean handingOver;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started;
    private boolean alerted;
    private boolean releasing;
    /**
     * La última orden recibida: se para con stopSelf(lastStartId), que Android no hace si hay otra en cola (otro park()
     * con startForegroundService): esa pasa a primer plano y sigue. Pararlo con una en cola cerraría la app.
     */
    private int lastStartId;
    private long releaseStart;

    /** Aparca la sesión de AA hasta que el usuario desbloquee (llamar con AA conectado y el móvil bloqueado). */
    static void park(Context ctx) {
        L.life("AA guardián: sesión aparcada hasta desbloquear (servidor ocupado por nuestra head unit)");
        // Ya, antes de que se cierre la sesión con el coche: sin vista en el móvil y sin vídeo de AA.
        AaPark.park(ctx, "todo cerrado con el móvil bloqueado");
        active = true;
        handingOver = false;
        try {
            ctx.startForegroundService(new Intent(ctx, AaGuardService.class));
        } catch (RuntimeException e) {
            // Android no deja arrancarlo ahora: AA sigue aparcado (ping) y el apagado pendiente se hace al desbloquear.
            active = false;
            L.lifeWarn("AA guardián: Android no deja arrancarlo (" + e.getClass().getSimpleName()
                    + "); el servidor se apagará al desbloquear");
        }
    }

    /**
     * El enlace vuelve a arrancar (Bluetooth del coche, Conectar, coche anunciado): el guardián se va sin apagar el
     * servidor ni soltar AA, que sigue aparcado y pasa al enlace (que lo reanuda en cuanto llegue el coche).
     */
    static void handOver(Context ctx, String why) {
        if (!active) return;
        L.life("AA guardián: paso Android Auto aparcado al enlace (" + why + "); no apago el servidor");
        handingOver = true;
        active = false;
        AaServerStarter.cancelPendingStop(ctx);
        ctx.getSystemService(NotificationManager.class).cancel(ALERT_ID);
        // Por una orden y no con stopService: si aún no hubiera pasado a primer plano, pararlo cerraría la app.
        try {
            ctx.startService(new Intent(ctx, AaGuardService.class).setAction(ACTION_HAND_OVER));
        } catch (RuntimeException e) {
            ctx.stopService(new Intent(ctx, AaGuardService.class));
        }
    }

    private static final String ACTION_HAND_OVER = "com.headqlink.link.GUARD_HAND_OVER";

    private final BroadcastReceiver unlock = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) beginRelease();
        }
    };

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            // Relevo al enlace en curso: AA ya no es cosa del guardián (tampoco si estaba soltándolo).
            if (!active) return;
            CommManager cm = comm();
            if (releasing) {
                // Apagado en curso: la sesión cae cuando Android Auto para el servidor.
                if (!cm.isConnected() || System.currentTimeMillis() - releaseStart > RELEASE_MAX_MS) finish();
                else main.postDelayed(this, 1000);
                return;
            }
            if (!cm.isConnected() && AaServerStarter.manual(AaGuardService.this)) {
                // Arranque manual: no hay apagado pendiente que esperar al desbloquear (ni nada que avisar: desbloquear
                // no cerraría el servidor). El guardián ya no guarda nada.
                L.lifeWarn("AA guardián: se perdió la conexión con Android Auto aparcado (arranque manual: no hay apagado"
                        + " que esperar); me voy");
                finish();
                return;
            }
            if (!cm.isConnected() && !alerted) {
                alerted = true;
                L.lifeWarn("AA guardián: se perdió la conexión con el servidor aparcado; aviso al usuario");
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
            active = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        lastStartId = startId;
        if (handingOver) {
            // Se pidió el relevo antes de llegar aquí: nada que guardar.
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!started) {
            started = true;
            registerReceiver(unlock, new IntentFilter(Intent.ACTION_USER_PRESENT));
            // Tras cerrar la sesión con el coche (que deja VideoTap sin modo sin pantalla): sin vista en el móvil y sin
            // vídeo de AA, como un coche que vuelve a su propia pantalla. Solo si sigue aparcado (no hubo relevo).
            main.postDelayed(() -> {
                if (!AaPark.parked || releasing) return;
                VideoTap.setHeadless(true);
                VideoTap.setSink(null);
                CommManager cm = comm();
                if (cm.isConnected()) {
                    cm.send(new VideoFocusEvent(false, true));
                    L.i("AA guardián: foco de vídeo en nativo (AA deja de enviar imagen)");
                }
            }, 800);
            main.postDelayed(check, CHECK_MS);
        } else if (active && !releasing) {
            // Otro park() que llegó mientras se liberaba el anterior (su stopSelf no se hizo): se vuelve a vigilar.
            main.removeCallbacks(check);
            main.postDelayed(check, CHECK_MS);
        }
        return START_NOT_STICKY;
    }

    /** Desbloqueado: se apaga el servidor (TouchService, tras la capa) y se suelta la sesión. */
    private void beginRelease() {
        if (releasing || !active) return;
        releasing = true;
        releaseStart = System.currentTimeMillis();
        if (AaServerStarter.manual(this)) {
            // Se eligió el arranque manual con Android Auto aparcado: lo mismo que el automático salvo el apagado (no se
            // pulsa nada). Se suelta AA con orden (ByeBye) y su servidor sigue encendido, atendiendo.
            L.life("AA guardián: desbloqueado con el arranque manual: suelto Android Auto con orden y no apago su"
                    + " servidor (sigue atendiendo)");
            AaServerStarter.cancelPendingStop(this);
            finish();
            return;
        }
        L.life("AA guardián: desbloqueado, apago el servidor y suelto la sesión");
        AaPark.stopPing();
        AaServerStarter.runPendingStop(this);
        main.removeCallbacks(check);
        main.postDelayed(check, 1000);
    }

    private void finish() {
        L.life("AA guardián: liberado");
        active = false;
        releasing = false;
        AaPark.release("guardián liberado");
        // Con orden (ByeBye), como todo cierre de nuestra conexión con AA: así su servidor sigue atendiendo después.
        AaClose.stopAa(this, "guardián liberado");
        VideoTap.setHeadless(false);
        getSystemService(NotificationManager.class).cancel(ALERT_ID);
        stopSelf(lastStartId);
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
        if (handingOver) {
            // Relevo al enlace: AA sigue aparcado (AaPark), sin tocar nada.
            handingOver = false;
        } else if (active) {
            // Cerrado por otro camino (el sistema): nadie hará ping; AA caerá solo. Sin vista en el móvil mientras tanto.
            active = false;
            AaPark.stopPing();
        }
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
