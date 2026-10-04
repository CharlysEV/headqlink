package com.headqlink.link;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Reloj en milisegundos a pantalla completa, refrescado en cada vsync. Con HeadQLink en modo
 * Diagnóstico, el coche muestra el mismo reloj: grabando ambas pantallas en el mismo plano, la
 * diferencia entre los dos relojes en cada fotograma es la latencia total (móvil → coche).
 */
public class LatencyActivity extends Activity implements Choreographer.FrameCallback {
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private TextView clock;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        clock = new TextView(this);
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        TextView hint = new TextView(this);
        hint.setTextColor(0xFF99A3AA);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(48, 48, 48, 0);
        hint.setText("Con HeadQLink en modo Diagnóstico, graba a la vez esta pantalla y la del coche. "
                + "La diferencia entre los dos relojes es la latencia total.");
        root.addView(clock);
        root.addView(hint);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        clock.setText(fmt.format(new Date()));
        Choreographer.getInstance().postFrameCallback(this);
    }
}
