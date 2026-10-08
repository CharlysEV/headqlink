package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Lenguaje visual de los paneles de la sección Coche, con la paleta «Eléctrico» de la app del móvil: fondo casi
 * negro, tarjetas redondeadas con un filo de 2 px (1 dp en el coche), acento cian → verde para lo importante, ámbar y
 * rojo para avisos, etiquetas pequeñas en mayúsculas espaciadas y cifras grandes con la unidad pequeña.
 *
 * Medidas en píxeles de la pantalla del coche (1920x882, ~200 ppp). Pensado para el codificador de vídeo: colores
 * planos, sin sombras ni desenfoques, y los objetos de dibujo se reutilizan (nada se crea en cada fotograma).
 */
final class CarKit {
    // Fondo y superficies.
    static final int BG = 0xFF0A0E14;
    static final int SURFACE = 0xFF111821;
    static final int SURFACE_HI = 0xFF17202B;
    static final int SURFACE_TOP = 0xFF1E2935;
    static final int OUTLINE = 0xFF243140;
    static final int EDGE = 0xFF2E4256;
    // Texto.
    static final int TEXT = 0xFFEAF2F7;
    static final int DIM = 0xFFA3B3C2;
    static final int FAINT = 0xFF8296A9;
    static final int MUTED = 0xFF5B6B7C;
    // Acento y estados.
    static final int ACCENT = 0xFF00E5C7;
    static final int ACCENT_2 = 0xFF4CFF9F;
    static final int ON_ACCENT = 0xFF00211C;
    static final int AMBER = 0xFFFFB547;
    static final int RED = 0xFFFF6B7A;
    static final int BLUE = 0xFF5AA9FF;
    static final int VIOLET = 0xFFB69CFF;
    static final int GREEN = 0xFF4CFF9F;

    static final int PAD = 24;
    static final int GAP = 18;
    static final int RADIUS = 24;
    static final float BORDER = 2f;

    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface CONDENSED = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private CarKit() {
    }

    // ------------------------------------------------------------------ dibujo

    /** Tarjeta: superficie con filo. */
    static void card(Canvas cv, RectF r, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(SURFACE);
        cv.drawRoundRect(r, RADIUS, RADIUS, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(BORDER);
        p.setColor(OUTLINE);
        cv.drawRoundRect(r, RADIUS, RADIUS, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** Etiqueta de sección: mayúsculas pequeñas y espaciadas. Devuelve su ancho. */
    static float label(Canvas cv, String s, float x, float y, int color, Paint p, Paint.Align align) {
        return label(cv, s, x, y, color, p, align, Float.MAX_VALUE);
    }

    /** Etiqueta que se estrecha (menos espacio entre letras y luego menos tamaño) si no cabe en maxW. */
    static float label(Canvas cv, String s, float x, float y, int color, Paint p, Paint.Align align, float maxW) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(MEDIUM);
        p.setTextSize(19);
        p.setLetterSpacing(0.12f);
        String up = s.toUpperCase(java.util.Locale.getDefault());
        if (p.measureText(up) > maxW) p.setLetterSpacing(0.04f);
        if (p.measureText(up) > maxW) p.setTextSize(Math.max(14, 19 * maxW / p.measureText(up)));
        p.setColor(color);
        p.setTextAlign(align);
        // Si ni así cabe (títulos largos en alemán u holandés), acaba en «…».
        String u = p.measureText(up) > maxW ? ellipsize(up, maxW, p) : up;
        cv.drawText(u, x, y, p);
        float w = p.measureText(u);
        p.setLetterSpacing(0);
        return w;
    }

    static float label(Canvas cv, String s, float x, float y, Paint p) {
        return label(cv, s, x, y, FAINT, p, Paint.Align.LEFT);
    }

    /** Texto simple. Devuelve su ancho. */
    static float text(Canvas cv, String s, float x, float y, float size, int color, Typeface tf, Paint p, Paint.Align align) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        p.setTextAlign(align);
        cv.drawText(s, x, y, p);
        return p.measureText(s);
    }

    /**
     * Cifra grande con su unidad pequeña a continuación, en la misma línea base. align: dónde queda el conjunto
     * respecto a x. Devuelve el ancho total.
     */
    static float number(Canvas cv, String value, String unit, float x, float y, float size, int color, Typeface tf,
                        Paint p, Paint.Align align) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setTextAlign(Paint.Align.LEFT);
        p.setTypeface(tf);
        p.setTextSize(size);
        float vw = p.measureText(value);
        float us = Math.max(20, Math.min(36, size * 0.36f));
        float gap = unit == null || unit.isEmpty() ? 0 : size * 0.12f;
        p.setTypeface(MEDIUM);
        p.setTextSize(us);
        float uw = unit == null || unit.isEmpty() ? 0 : p.measureText(unit);
        float total = vw + gap + uw;
        float x0 = align == Paint.Align.LEFT ? x : align == Paint.Align.CENTER ? x - total / 2 : x - total;
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        cv.drawText(value, x0, y, p);
        if (uw > 0) {
            p.setTypeface(MEDIUM);
            p.setTextSize(us);
            p.setColor(DIM);
            cv.drawText(unit, x0 + vw + gap, y, p);
        }
        return total;
    }

    /** Ficha: etiqueta arriba y cifra con unidad debajo, sobre una superficie algo más clara. */
    static void chip(Canvas cv, RectF r, String label, String value, String unit, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(SURFACE_HI);
        cv.drawRoundRect(r, 18, 18, p);
        label(cv, label, r.left + 18, r.top + 32, FAINT, p, Paint.Align.LEFT, r.width() - 30);
        number(cv, value, unit, r.left + 18, r.bottom - 18, Math.min(38, r.height() * 0.42f), color, MEDIUM, p, Paint.Align.LEFT);
    }

    /** Barra redondeada: pista y relleno (frac 0-1) con el color dado. */
    static void bar(Canvas cv, RectF r, float frac, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(SURFACE_TOP);
        float rad = r.height() / 2;
        cv.drawRoundRect(r, rad, rad, p);
        float f = Math.max(0, Math.min(1, frac));
        if (f <= 0) return;
        p.setColor(color);
        float right = r.left + Math.max(r.height(), r.width() * f);
        cv.drawRoundRect(r.left, r.top, right, r.bottom, rad, rad, p);
    }

    /** Arco de anillo: pista completa (sweep) y la parte llena (frac) con color; extremos redondeados. */
    static void ring(Canvas cv, RectF oval, float start, float sweep, float frac, float stroke, int track, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(stroke);
        p.setColor(track);
        cv.drawArc(oval, start, sweep, false, p);
        float f = Math.max(0, Math.min(1, frac));
        if (f > 0.002f) {
            p.setColor(color);
            cv.drawArc(oval, start, sweep * f, false, p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
    }

    /** Recorta s con «…» para que quepa en max px con el tamaño y la letra que tenga p. */
    static String ellipsize(String s, float max, Paint p) {
        if (s == null) return "";
        if (p.measureText(s) <= max) return s;
        String t = s;
        while (t.length() > 1 && p.measureText(t + "…") > max) t = t.substring(0, t.length() - 1);
        return t.trim() + "…";
    }

    static int alpha(int color, float a) {
        return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (color & 0xFFFFFF);
    }

    static int lerp(int a, int b, float f) {
        float t = Math.max(0, Math.min(1, f));
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Color del acento a lo largo de su degradado (0 cian, 1 verde). */
    static int accentAt(float f) {
        return lerp(ACCENT, ACCENT_2, f);
    }

    /** Texto en varias líneas (consejos, estados vacíos): se crea solo cuando cambia el texto o el ancho. */
    static final class Para {
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private StaticLayout layout;
        private String text = "";
        private int width = -1;
        private int maxLines = Integer.MAX_VALUE;

        Para(float size, int color, Typeface tf) {
            tp.setTextSize(size);
            tp.setColor(color);
            tp.setTypeface(tf);
        }

        void color(int c) {
            tp.setColor(c);
        }

        /** Como mucho n líneas (la última, con «…»). */
        void maxLines(int n) {
            if (n == maxLines) return;
            maxLines = n;
            layout = null;
        }

        /** Dibuja con la esquina superior izquierda en (x, y); alignCenter centra cada línea en el ancho. Devuelve el alto. */
        float draw(Canvas cv, String s, float x, float y, int w, boolean alignCenter) {
            if (s == null) s = "";
            if (layout == null || w != width || !s.equals(text)) {
                text = s;
                width = Math.max(10, w);
                layout = StaticLayout.Builder.obtain(s, 0, s.length(), tp, width)
                        .setAlignment(alignCenter ? Layout.Alignment.ALIGN_CENTER : Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(0, 1.12f)
                        .setMaxLines(maxLines)
                        .setEllipsize(maxLines == Integer.MAX_VALUE ? null : android.text.TextUtils.TruncateAt.END)
                        .build();
            }
            cv.save();
            cv.translate(x, y);
            layout.draw(cv);
            cv.restore();
            return layout.getHeight();
        }
    }

    // ------------------------------------------------------------------ vistas

    /** Tarjeta como vista: dibuja superficie, filo y etiqueta; el contenido lo pinta painter dentro del relleno. */
    static class Card extends FrameLayout {
        interface Painter {
            void paint(Canvas cv, RectF inner, Paint p);
        }

        static final int TITLE_H = 44;
        private final String title;
        private Painter painter;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final RectF inner = new RectF();

        Card(Context c, String title, Painter painter) {
            super(c);
            this.title = title;
            this.painter = painter;
            setWillNotDraw(false);
            setPadding(PAD, PAD + (title != null ? TITLE_H : 0), PAD, PAD);
        }

        void painter(Painter pt) {
            painter = pt;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            r.set(BORDER / 2, BORDER / 2, getWidth() - BORDER / 2, getHeight() - BORDER / 2);
            card(cv, r, p);
            if (title != null) label(cv, title, PAD, PAD + 20, FAINT, p, Paint.Align.LEFT, getWidth() - 2 * PAD);
            inner.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
            if (painter != null) painter.paint(cv, inner, p);
        }
    }

    /** Fila o columna con un hueco GAP entre hijos. */
    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    /** Añade v a l con peso (0: tamaño fijo size) y el hueco antes si no es el primero. */
    static <T extends View> T add(LinearLayout l, T v, float weight, int size) {
        boolean horiz = l.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams lp = horiz
                ? new LinearLayout.LayoutParams(weight > 0 ? 0 : size, ViewGroup.LayoutParams.MATCH_PARENT, weight)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, weight > 0 ? 0 : size, weight);
        if (l.getChildCount() > 0) {
            if (horiz) lp.leftMargin = GAP;
            else lp.topMargin = GAP;
        }
        l.addView(v, lp);
        return v;
    }

    /** Raíz de una pestaña: fondo y márgenes de la rejilla. */
    static FrameLayout page(Context c, View content) {
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(BG);
        f.setPadding(PAD, 6, PAD, PAD);
        f.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return f;
    }

    /** Píldora de acción (TextView) al estilo de la sección; primary: rellena con el acento. */
    static TextView pill(Context c, String s, boolean primary) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 23);
        t.setTypeface(MEDIUM);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setPadding(26, 13, 26, 13);
        t.setTextColor(primary ? ON_ACCENT : TEXT);
        t.setBackground(primary ? CarStyle.accent(30) : outlined(SURFACE_TOP, EDGE, 30));
        return t;
    }

    static android.graphics.drawable.GradientDrawable outlined(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        d.setStroke(2, stroke);
        return d;
    }

    /** Coloca v dentro de un FrameLayout con gravedad y márgenes. */
    static FrameLayout.LayoutParams at(int gravity, int left, int top, int right, int bottom) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(left, top, right, bottom);
        return lp;
    }
}
