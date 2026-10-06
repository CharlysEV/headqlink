package com.headqlink.link;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Puente invisible del botón grande del widget y del botón de los ajustes rápidos: hace lo mismo que el botón de la
 * pantalla principal ({@link LinkControl}) y se cierra.
 * <ul>
 *   <li>En marcha: Desconectar.</li>
 *   <li>Parado: la comprobación de requisitos; si falta algo obligatorio se abre la «Comprobación» (con «Conectar
 *       igualmente») y, si no, Conectar (con el servidor de Android Auto si hace falta, tras la capa).</li>
 *   <li>Sin la configuración inicial: la app (que la abre).</li>
 * </ul>
 * Es una actividad (transparente, fuera de recientes, en su propia tarea) a propósito: un toque en un widget o en los
 * ajustes rápidos puede abrir una actividad, y con ella delante Android 12+ deja arrancar el servicio en primer plano
 * (y la comprobación puede abrirse). No exportada: solo la abren los PendingIntent inmutables de la propia app.
 */
public final class QuickToggleActivity extends Activity {
    static final String ACTION_TOGGLE = "com.headqlink.link.TOGGLE";
    /** Quién la abre, para el log: «widget» o «ajustes rápidos». */
    static final String EXTRA_FROM = "from";
    private static final int REQ_CHECKLIST = 1;
    private static final String STATE_WAITING = "waiting";

    /** Esperando a que termine Conectar o la comprobación: otro toque no hace nada. */
    private boolean waiting;
    private String from = "widget";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        L.init(this);
        if (state != null && state.getBoolean(STATE_WAITING)) {
            // Recreada (giro de pantalla) con la comprobación abierta: su resultado llega a onActivityResult.
            waiting = true;
            return;
        }
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!waiting) handle(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Delante otra vez sin nada en curso (la comprobación devuelve su resultado antes de esto; el proceso pudo morir
        // a medias): no se queda una ventana transparente tapando la pantalla de inicio.
        if (waiting && !LinkControl.busy()) done();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(STATE_WAITING, waiting);
    }

    private void handle(Intent i) {
        String f = i != null ? i.getStringExtra(EXTRA_FROM) : null;
        from = f != null ? f : "widget";
        if (!new Config(this).setupDone()) {
            // Sin configurar: la pantalla principal abre la configuración inicial.
            L.i(from + ": HeadQLink sin configurar; abro la app");
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            done();
            return;
        }
        if (LinkState.running) {
            LinkControl.stop(this, from);
            done();
            return;
        }
        if (LinkControl.busy()) {
            L.i(from + ": Conectar ya está en curso");
            done();
            return;
        }
        waiting = true;
        LinkControl.connect(this, REQ_CHECKLIST, from, this::done);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CHECKLIST) return;
        // «Conectar» o «Conectar igualmente» en la comprobación; si no, nada.
        if (res == RESULT_OK && !LinkState.running) {
            LinkControl.start(this, from, this::done);
        } else {
            done();
        }
    }

    /** Se cierra sin animación (es invisible). */
    @SuppressWarnings("deprecation")
    private void done() {
        waiting = false;
        if (!isFinishing()) {
            if (android.os.Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
            finish();
            if (android.os.Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
        }
    }
}
