package com.c10link.link;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Estilo de la interfaz propia en la pantalla del coche. Las medidas son píxeles de esa pantalla
 * (1920x882, ~150 ppp): se diseña directamente para ella.
 */
final class CarStyle {
    static final int BG = 0xFF0E1013;
    static final int TEXT = 0xFFE8EAED;
    static final int TEXT_DIM = 0xFF9AA0A6;
    static final int ACCENT = 0xFF8AB4F8;
    static final int ACCENT_BG = 0xFF2B3A55;
    static final int ITEM_BG = 0xFF23272E;
    static final int PILL_BG = 0xDD2B2F36;

    private CarStyle() {
    }

    static TextView text(Context c, String s, int sizePx, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        return t;
    }

    /** Botón redondeado con texto. */
    static TextView pill(Context c, String s) {
        TextView t = text(c, s, 24, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(22, 12, 22, 12);
        t.setBackground(round(PILL_BG, 28));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 10;
        t.setLayoutParams(lp);
        return t;
    }

    /** Mensaje centrado (vacío, sin permiso, error). */
    static View message(Context c, String s) {
        TextView t = text(c, s, 24, TEXT_DIM);
        t.setGravity(Gravity.CENTER);
        t.setPadding(40, 40, 40, 40);
        return t;
    }

    static GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(radius);
        d.setColor(color);
        return d;
    }

    /** "‹ Volver" arriba a la izquierda dentro de un FrameLayout. */
    static View back(Context c, Runnable onBack) {
        TextView b = pill(c, "‹  Volver");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        lp.setMargins(16, 16, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onBack.run());
        return b;
    }

    /** Barra horizontal de botones. */
    static LinearLayout bar(Context c) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 12, 16, 12);
        bar.setBackgroundColor(0xFF15181C);
        return bar;
    }

    static int lighter(int color, int amount) {
        return Color.rgb(Math.min(255, Color.red(color) + amount), Math.min(255, Color.green(color) + amount),
                Math.min(255, Color.blue(color) + amount));
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
