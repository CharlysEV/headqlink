package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Pantalla de carga en el coche mientras Android Auto arranca: el coche de frente con un destello
 * cian → verde que recorre su tira de luz (como su animación de bienvenida), la marca de la app
 * (la Q con rayo y el nombre) y el estado.
 */
final class SplashView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long t0 = System.nanoTime();
    private final int bg;
    private final Drawable logo;
    private boolean stopped;

    SplashView(Context c, int bg) {
        super(c);
        this.bg = bg;
        this.logo = c.getDrawable(R.drawable.hql_logo);
    }

    /** Deja de animar (AA ya se ve encima). */
    void stop() {
        stopped = true;
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth();
        float h = getHeight();
        float t = (System.nanoTime() - t0) / 1e9f;
        cv.drawColor(bg);
        float side = Math.min(w * 0.42f, h * 0.52f);
        RectF car = new RectF(w / 2 - side / 2, h * 0.40f - side / 2, w / 2 + side / 2, h * 0.40f + side / 2);
        CarArt.front(cv, car, p);

        // Destello que recorre la tira de luz de lado a lado, ida y vuelta.
        RectF bar = CarArt.frontLightBar(car);
        float phase = (float) (0.5 - 0.5 * Math.cos(t * Math.PI / 1.1));
        float cx = bar.left + bar.width() * phase;
        float glow = bar.width() * 0.22f;
        p.setShader(new LinearGradient(cx - glow, 0, cx + glow, 0,
                new int[]{CarStyle.ACCENT & 0x00FFFFFF, CarStyle.ACCENT, CarStyle.ACCENT_2, CarStyle.ACCENT_2 & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));
        RectF lit = new RectF(bar.left, bar.top - bar.height() * 0.6f, bar.right, bar.bottom + bar.height() * 0.6f);
        cv.drawRoundRect(lit, lit.height() / 2, lit.height() / 2, p);
        p.setShader(null);

        // Marca: la Q con rayo y el nombre, centrados juntos.
        p.setTextAlign(Paint.Align.LEFT);
        p.setColor(CarStyle.TEXT);
        p.setTextSize(h * 0.07f);
        p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        p.setLetterSpacing(0.14f);
        String name = "HEADQLINK";
        float tw = p.measureText(name);
        float ls = h * 0.095f;
        float gap = ls * 0.32f;
        float x0 = w / 2 - (ls + gap + tw) / 2;
        float base = h * 0.80f;
        if (logo != null) {
            int top = Math.round(base - p.getTextSize() * 0.36f - ls / 2);
            logo.setBounds(Math.round(x0), top, Math.round(x0 + ls), Math.round(top + ls));
            logo.draw(cv);
        }
        cv.drawText(name, x0 + ls + gap, base, p);
        p.setLetterSpacing(0f);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(CarStyle.TEXT_DIM);
        p.setTextSize(h * 0.034f);
        int dots = (int) (t * 2) % 4;
        cv.drawText(Str.get(R.string.hql_starting_auto) + "...".substring(0, dots) + "   ".substring(dots), w / 2, h * 0.88f, p);
        if (!stopped && isAttachedToWindow()) postInvalidateOnAnimation();
    }
}
