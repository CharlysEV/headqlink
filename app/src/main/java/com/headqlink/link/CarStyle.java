package com.headqlink.link;

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

import com.andrerinas.openheadunit.R;

/**
 * Estilo de la interfaz propia en la pantalla del coche. Las medidas son píxeles de esa pantalla
 * (1920x882, ~150 ppp): se diseña directamente para ella.
 */
/** Colores: los pone CarTheme (día o noche); no son constantes. */
final class CarStyle {
    static int BG = 0xFF0E1013;
    static int TEXT = 0xFFE8EAED;
    static int TEXT_DIM = 0xFF9AA0A6;
    /** Acento «Eléctrico» (el mismo que en el móvil): cian que pasa a verde eléctrico en los degradados. */
    static int ACCENT = 0xFF00E5C7;
    static int ACCENT_2 = 0xFF4CFF9F;
    /** Texto e iconos sobre el acento. */
    static int ON_ACCENT = 0xFF00211C;
    static int ACCENT_BG = 0xFF0F3B38;
    static int ITEM_BG = 0xFF23272E;
    /** Tarjetas al estilo de AA (superficie elevada sobre el fondo). */
    static int CARD = 0xFF28292C;
    static int GOOD = 0xFF81C995;
    static int WARN = 0xFFFDD663;
    static int BAD = 0xFFF28B82;
    /** Botones tonales al estilo de AA. */
    static int PILL_BG = 0xEE3C4043;

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
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(26, 14, 26, 14);
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

    /** Píldora de acento (lo elegido, la acción principal): degradado cian → verde. */
    static GradientDrawable accent(int radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{ACCENT, ACCENT_2});
        d.setCornerRadius(radius);
        return d;
    }

    /** "‹ Volver" (en el idioma de la app) arriba a la izquierda dentro de un FrameLayout. */
    static View back(Context c, Runnable onBack) {
        TextView b = pill(c, "‹  " + Str.get(R.string.hql_back));
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

    /** Tarjeta redondeada con relleno interior. */
    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(28, 24, 28, 24);
        l.setBackground(round(CARD, 28));
        return l;
    }

    /** Título pequeño de tarjeta (gris). */
    static TextView label(Context c, String s) {
        TextView t = text(c, s, 22, TEXT_DIM);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        return t;
    }

    /** Fila de pestañas en píldora (la elegida, rellena del acento). */
    static LinearLayout tabs(Context c, String[] names, int selected, java.util.function.IntConsumer onSelect) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(24, 16, 24, 12);
        TextView[] views = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            int idx = i;
            TextView t = text(c, names[i], 25, TEXT);
            t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            t.setPadding(32, 12, 32, 12);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = 10;
            row.addView(t, lp);
            views[i] = t;
            t.setOnClickListener(v -> {
                for (int k = 0; k < views.length; k++) styleTab(views[k], k == idx);
                onSelect.accept(idx);
            });
        }
        for (int k = 0; k < views.length; k++) styleTab(views[k], k == selected);
        return row;
    }

    private static void styleTab(TextView t, boolean on) {
        t.setBackground(on ? accent(30) : CarKit.outlined(CarKit.SURFACE_HI, CarKit.OUTLINE, 30));
        t.setTextColor(on ? ON_ACCENT : CarKit.DIM);
    }

    /**
     * Reproductor de vídeo con TextureView: el vídeo va dentro del árbol de vistas y también se ve cuando HeadQLink dibuja
     * la interfaz él mismo con la pantalla del móvil apagada (PhoneOffRenderer); una SurfaceView la compone Android aparte.
     */
    static androidx.media3.ui.PlayerView player(Context c) {
        return (androidx.media3.ui.PlayerView) android.view.LayoutInflater.from(c).inflate(R.layout.hql_player_texture, null, false);
    }

    static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
