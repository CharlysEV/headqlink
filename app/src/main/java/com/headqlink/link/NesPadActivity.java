package com.headqlink.link;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

/**
 * Pantalla del móvil mientras se juega en el coche: recibe el mando Bluetooth entero (teclas y ejes de la cruceta y el
 * stick, que la accesibilidad no ve) y lo pasa al emulador. Se abre al empezar un juego y se cierra con él, o con atrás.
 */
public class NesPadActivity extends Activity {
    static volatile NesPadActivity instance;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(0xFF0A0E14);
        int pad = Math.round(32 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(Str.get(R.string.hql_nes_pad_title));
        title.setTextColor(0xFFEAF2F7);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        TextView text = new TextView(this);
        text.setText(Str.get(R.string.hql_nes_pad_text));
        text.setTextColor(0xFFA3B3C2);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, pad / 2, 0, 0);
        col.addView(text);
        setContentView(col);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Atrás en el móvil cierra esta pantalla (el juego sigue en el coche); el resto va al mando.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (NesInput.key(e.getKeyCode(), e.getAction())) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (NesInput.motion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    /** Cierra la pantalla del mando si está abierta (al acabar el juego). */
    static void close() {
        NesPadActivity a = instance;
        if (a != null) a.finish();
    }
}
