package com.headqlink.link;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.button.MaterialButton;

/**
 * Pantalla «Comprobación»: todo lo que tiene que estar activado para los ajustes actuales, con «Todo listo» o «Faltan
 * N cosas» arriba y un botón por requisito que lleva a su ajuste. Se abre desde el menú de la pantalla principal y,
 * con {@link #EXTRA_GATE}, al pulsar Conectar con algo obligatorio pendiente: entonces termina con RESULT_OK si el
 * usuario pulsa «Conectar» / «Conectar igualmente».
 */
public class ChecklistActivity extends Activity {
    static final String EXTRA_GATE = "gate";
    /**
     * Desde el aviso «HeadQLink no encuentra el botón del servidor…» (AaServerStarter): abre el diálogo que explica el
     * arranque manual y lo pone si el usuario acepta.
     */
    static final String EXTRA_OFFER_MANUAL = "offer_manual";

    private Checklist checklist;
    private Config cfg;
    private boolean gate;
    private TextView title;
    private TextView body;
    private LinearLayout list;
    private TextView guideLabel;
    private TextView guide;
    private MaterialButton go;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        cfg = new Config(this);
        gate = getIntent().getBooleanExtra(EXTRA_GATE, false);
        setContentView(R.layout.hql_activity_checklist);
        title = findViewById(R.id.hql_check_title);
        body = findViewById(R.id.hql_check_body);
        list = findViewById(R.id.hql_check_list);
        guideLabel = findViewById(R.id.hql_check_guide_label);
        guide = findViewById(R.id.hql_check_guide);
        go = findViewById(R.id.hql_req_go);
        MaterialButton back = findViewById(R.id.hql_req_back);
        back.setVisibility(gate ? View.VISIBLE : View.GONE);
        back.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        go.setOnClickListener(v -> {
            if (gate) {
                L.i("requisitos: conectar " + (checklist.blocking().isEmpty() ? "con todo listo" : "igualmente; faltan " + checklist.blocking()));
                setResult(RESULT_OK);
            }
            finish();
        });
        checklist = new Checklist(this, this::render);
        if (b == null && getIntent().getBooleanExtra(EXTRA_OFFER_MANUAL, false) && !cfg.aaServerManual()) {
            L.i("requisitos: ofrezco el arranque manual (aviso «no encuentro el botón del servidor»)");
            checklist.confirmManual();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checklist.start();
    }

    @Override
    protected void onPause() {
        checklist.stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        checklist.onPermissionsResult();
    }

    private void render() {
        title.setText(checklist.summaryTitle());
        title.setTextColor(checklist.summaryColor());
        body.setText(Ui.modeTitle(cfg.mode()) + " · " + Ui.linkTitle(cfg.linkMode()) + "\n" + checklist.summaryDetail());
        checklist.render(list);
        String gt = checklist.guideTitle();
        guideLabel.setVisibility(gt != null ? View.VISIBLE : View.GONE);
        guide.setVisibility(gt != null ? View.VISIBLE : View.GONE);
        if (gt != null) {
            guideLabel.setText(gt);
            guide.setText(checklist.guideText());
        }
        go.setText(Str.get(!gate ? R.string.hql_close
                : checklist.blocking().isEmpty() ? R.string.hql_connect : R.string.hql_req_connect_anyway));
    }
}
