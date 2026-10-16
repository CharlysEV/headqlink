package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapMessage;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.aap.protocol.Channel;
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent;
import com.andrerinas.openheadunit.aap.protocol.proto.Control;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.VideoTap;

/**
 * Android Auto «aparcado»: nuestra head unit sigue conectada al servidor de AA (que atiende una conexión a la vez), sin
 * vídeo y con el foco de vídeo en «nativo», así que AA no codifica imagen. Sin vista en el móvil (VideoTap.headless). Lo
 * usan:
 * - LinkService mientras espera al coche (fase AA EN PAUSA de LinkLifecycle): si vuelve, AA sale de la pausa al instante,
 *   sin arrancar el servidor ni desbloquear;
 * - AaGuardService cuando todo se cerró con el móvil bloqueado (el servidor no se puede apagar hasta desbloquear).
 *
 * Mientras está aparcado se manda un ping a AA cada 5 s: sin vídeo, AA se queda en silencio y Open Headunit daría la
 * conexión por perdida a los 15 s sin recibir nada. Hilo principal.
 */
public final class AaPark {
    private static final long PING_MS = 5_000;
    /** Hasta cuándo se espera al coche (hora de pared; 0: sin hora, el guardián con el móvil bloqueado). */
    private static volatile long untilWallMs;

    /** AA aparcado (lo mira AaPassthroughSource al parar el vídeo: entonces no vuelve la vista del móvil). */
    static volatile boolean parked;
    /** AA salió de la pausa y el vídeo nuevo aún no le ha devuelto el foco (ver takeFocusOwed). */
    private static volatile boolean focusOwed;
    /** Hilo principal, creado al usarse (la notificación de AA mira notificationText también en pruebas sin Android). */
    private static Handler main;

    private static Handler main() {
        if (main == null) main = new Handler(Looper.getMainLooper());
        return main;
    }
    private static volatile Context app;

    private static final Runnable PING = new Runnable() {
        @Override
        public void run() {
            Context c = app;
            if (!parked || c == null) return;
            CommManager cm = App.Companion.provide(c).getCommManager();
            if (!cm.isConnected()) return;
            cm.send(new AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE,
                    Control.PingRequest.newBuilder().setTimestamp(System.nanoTime()).build()));
            main().postDelayed(this, PING_MS);
        }
    };

    private AaPark() {
    }

    /** Un ping a AA (sin vídeo se cierra solo si no oye nada: así sigue conectado). */
    static void ping(CommManager cm) {
        if (!cm.isConnected()) return;
        cm.send(new AapMessage(Channel.ID_CTR, Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE,
                Control.PingRequest.newBuilder().setTimestamp(System.nanoTime()).build()));
    }

    static void park(Context ctx, String why) {
        park(ctx, why, 0);
    }

    /**
     * Aparca AA (si está conectado): foco nativo, sin vista en el móvil y ping. Idempotente. untilWallMs: fin de la
     * espera del coche (lo dice la notificación de Android Auto), 0 si no se sabe.
     */
    static void park(Context ctx, String why, long untilWallMs) {
        app = ctx.getApplicationContext();
        AaPark.untilWallMs = untilWallMs;
        boolean was = parked;
        parked = true;
        focusOwed = false;
        VideoTap.setHeadless(true);
        CommManager cm = App.Companion.provide(ctx).getCommManager();
        if (cm.isConnected()) cm.send(new VideoFocusEvent(false, true));
        main().removeCallbacks(PING);
        main().postDelayed(PING, PING_MS);
        if (!was) L.life("Android Auto aparcado (" + why + "): sin vídeo, foco en nativo, ping cada " + PING_MS / 1000 + " s");
        AapService.refreshNotification();
    }

    /**
     * El coche ha vuelto: AA sale de la pausa. Sigue conectado; el vídeo de la sesión nueva le devuelve el foco (su ciclo
     * de IDR o, si no puede, un foco directo: takeFocusOwed). Nada de vista en el móvil: la fuente pone headless al
     * arrancar. false si no estaba aparcado.
     */
    static boolean resume(String why) {
        main().removeCallbacks(PING);
        if (!parked) return false;
        parked = false;
        focusOwed = true;
        L.life("Android Auto sale de la pausa (" + why + ")");
        AapService.refreshNotification();
        return true;
    }

    /** AA se cierra (apagado del servidor, Desconectar): fuera la pausa y la vista del móvil vuelve a lo normal. */
    static void release(String why) {
        main().removeCallbacks(PING);
        focusOwed = false;
        if (!parked) return;
        parked = false;
        VideoTap.setHeadless(false);
        L.life("Android Auto deja de estar aparcado (" + why + ")");
        AapService.refreshNotification();
    }

    /**
     * Texto de la notificación de Android Auto mientras está aparcado (null si no lo está): «en pausa, esperando al coche
     * hasta las 16:20», no «Android Auto está proyectado» (el 16-10 lo decía sin coche ni coche virtual).
     */
    public static String notificationText() {
        Context c = app;
        if (!parked || c == null) return null;
        long u = untilWallMs;
        if (u <= System.currentTimeMillis()) return Str.get(R.string.hql_aa_paused);
        return Str.get(R.string.hql_aa_paused_until, android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(u)));
    }

    /** Deja de mandar pings (apagado del servidor en curso: la sesión cae sola). */
    static void stopPing() {
        main().removeCallbacks(PING);
    }

    /** El vídeo nuevo tras salir de la pausa: true una vez, si aún hay que devolver el foco de vídeo a AA. */
    static boolean takeFocusOwed() {
        boolean f = focusOwed;
        focusOwed = false;
        return f;
    }
}
