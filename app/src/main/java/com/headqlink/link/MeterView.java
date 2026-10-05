package com.headqlink.link;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import com.andrerinas.openheadunit.R;

/**
 * Medidor de barras del panel «En directo» (fps, Mbps): segmentos que crecen de izquierda a derecha, los encendidos en
 * degradado cian → verde y el resto apagados. El cambio de nivel se anima. Solo dibuja; el valor lo pone
 * HomeActivity.
 */
public final class MeterView extends View {
    private static final int SEGMENTS = 14;

    private final Paint on = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint off = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float gap;
    private final float radius;
    private float level;
    private float target = -1;
    private ValueAnimator anim;

    public MeterView(Context c) {
        this(c, null);
    }

    public MeterView(Context c, AttributeSet attrs) {
        super(c, attrs);
        float d = getResources().getDisplayMetrics().density;
        gap = 3 * d;
        radius = 1.5f * d;
        off.setColor(c.getColor(R.color.hql_outline));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Nivel de 0 a 1 (animado desde el actual). */
    void setLevel(float l) {
        float t = Math.max(0f, Math.min(1f, l));
        if (t == target) return;
        target = t;
        if (anim != null) anim.cancel();
        if (!isAttachedToWindow()) {
            level = t;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(level, t);
        anim.setDuration(450);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            level = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        if (target >= 0) level = target;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        on.setShader(new LinearGradient(0, 0, w, 0, getContext().getColor(R.color.hql_accent),
                getContext().getColor(R.color.hql_accent_2), Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0) return;
        float seg = (w - gap * (SEGMENTS - 1)) / SEGMENTS;
        float lit = level * SEGMENTS;
        float bottom = getPaddingTop() + h;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < SEGMENTS; i++) {
            // Más altas hacia la derecha, como un medidor de señal.
            float sh = h * (0.38f + 0.62f * (i + 1) / SEGMENTS);
            float x = rtl ? getPaddingLeft() + w - (i + 1) * seg - i * gap : getPaddingLeft() + i * (seg + gap);
            r.set(x, bottom - sh, x + seg, bottom);
            cv.drawRoundRect(r, radius, radius, off);
            float f = Math.max(0f, Math.min(1f, lit - i));
            if (f > 0f) {
                on.setAlpha(Math.round(255 * (0.35f + 0.65f * f)));
                cv.drawRoundRect(r, radius, radius, on);
            }
        }
    }
}
