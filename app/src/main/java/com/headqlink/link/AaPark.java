package com.headqlink.link;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.aap.AapMessage;
import com.andrerinas.openheadunit.aap.protocol.Channel;
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent;
import com.andrerinas.openheadunit.aap.protocol.proto.Control;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.VideoTap;

/**
 * Android Auto «aparcado»: nuestra head unit sigue conectada al servidor de AA (que atiende una sola conexión), sin vídeo
 * y con el foco de vídeo en «nativo», así que AA no codifica imagen. Sin vista en el móvil (VideoTap.headless). Lo usan:
 * - LinkService mientras espera al coche (fase AA EN PAUSA de LinkLifecycle): si vuelve, AA sale de la pausa al instante,
 *   sin arrancar el servidor ni desbloquear;
 * - AaGuardService cuando todo se cerró con el móvil bloqueado (el servidor no se puede apagar hasta desbloquear).
 *
 * Mientras está aparcado se manda un ping a AA cada 5 s: sin vídeo, AA se queda en silencio y Open Headunit daría la
 * conexión por perdida a los 15 s sin recibir nada. Hilo principal.
 */
final class AaPark {
    private static final long PING_MS = 5_000;

    /** AA aparcado (lo mira AaPassthroughSource al parar el vídeo: entonces no vuelve la vista del móvil). */
    static volatile boolean parked;
    /** AA salió de la pausa y el vídeo nuevo aún no le ha devuelto el foco (ver takeFocusOwed). */
    private static volatile boolean focusOwed;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
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
            MAIN.postDelayed(this, PING_MS);
        }
    };

    private AaPark() {
    }

    /** Aparca AA (si está conectado): foco nativo, sin vista en el móvil y ping. Idempotente. */
    static void park(Context ctx, String why) {
        app = ctx.getApplicationContext();
        boolean was = parked;
        parked = true;
        focusOwed = false;
        VideoTap.setHeadless(true);
        CommManager cm = App.Companion.provide(ctx).getCommManager();
        if (cm.isConnected()) cm.send(new VideoFocusEvent(false, true));
        MAIN.removeCallbacks(PING);
        MAIN.postDelayed(PING, PING_MS);
        if (!was) L.life("Android Auto aparcado (" + why + "): sin vídeo, foco en nativo, ping cada " + PING_MS / 1000 + " s");
    }

    /**
     * El coche ha vuelto: AA sale de la pausa. Sigue conectado; el vídeo de la sesión nueva le devuelve el foco (su ciclo
     * de IDR o, si no puede, un foco directo: takeFocusOwed). Nada de vista en el móvil: la fuente pone headless al
     * arrancar. false si no estaba aparcado.
     */
    static boolean resume(String why) {
        MAIN.removeCallbacks(PING);
        if (!parked) return false;
        parked = false;
        focusOwed = true;
        L.life("Android Auto sale de la pausa (" + why + ")");
        return true;
    }

    /** AA se cierra (apagado del servidor, Desconectar): fuera la pausa y la vista del móvil vuelve a lo normal. */
    static void release(String why) {
        MAIN.removeCallbacks(PING);
        focusOwed = false;
        if (!parked) return;
        parked = false;
        VideoTap.setHeadless(false);
        L.life("Android Auto deja de estar aparcado (" + why + ")");
    }

    /** Deja de mandar pings (apagado del servidor en curso: la sesión cae sola). */
    static void stopPing() {
        MAIN.removeCallbacks(PING);
    }

    /** El vídeo nuevo tras salir de la pausa: true una vez, si aún hay que devolver el foco de vídeo a AA. */
    static boolean takeFocusOwed() {
        boolean f = focusOwed;
        focusOwed = false;
        return f;
    }
}
