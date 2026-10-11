package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.aap.NavTap;

/**
 * Tarjeta flotante sobre Android Auto con la próxima parada del plan de carga (o, sin paradas, con qué % se llega al
 * destino). CarUi la coloca encima de la zona de AA y GlFrameRelay vuelve a pintar su rectángulo sobre la imagen de AA.
 * Un toque abre la ruta; mantenerla pulsada y arrastrar la mueve (la posición se guarda).
 */
final class FloatCard extends LinearLayout {
    static final int W = 470;
    static final int H = 112;
    private static final long LONG_PRESS_MS = 450;
    private static final float SLOP = 22;

    interface Listener {
        void onTap();

        /** Arrastrando: desplazamiento desde donde empezó (px de la pantalla del coche). */
        void onDrag(float dx, float dy);

        void onDrop();
    }

    private final Listener listener;
    private final Icon icon;
    private final TextView title;
    private final TextView sub;
    private float downX;
    private float downY;
    private boolean moved;
    private boolean dragging;
    private final Runnable longPress = () -> {
        dragging = true;
        paintBackground();
    };

    FloatCard(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setPadding(22, 14, 26, 14);
        icon = new Icon(c);
        addView(icon, new LayoutParams(64, 64));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        texts.setPadding(18, 0, 0, 0);
        title = CarStyle.text(c, "", 27, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title);
        sub = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(sub);
        addView(texts, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        paintBackground();
    }

    /** Lee el plan de carga. false si no hay nada que enseñar (sin ruta o sin plan). */
    boolean update() {
        RoutePlanner r = RoutePlanner.get();
        ChargePlanner.Result cp = r == null ? null : r.lastChargePlan();
        if (cp == null) return false;
        ChargePlanner.Stop st = cp.next();
        if (st != null) {
            icon.set(Icon.BOLT, CarKit.ACCENT);
            title.setText(RoutePlanner.chargerName(st.charger));
            sub.setText(Str.get(R.string.hql_float_stop_sub, Math.max(0, st.km - cp.fromKm), st.arrivePct));
        } else if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER) {
            icon.set(Icon.BOLT, CarKit.AMBER);
            title.setText(Str.get(R.string.hql_float_no_charger));
            sub.setText(Str.get(R.string.hql_float_dest, cp.arrivalPct));
        } else {
            icon.set(Icon.FLAG, CarKit.ACCENT);
            title.setText(Str.get(R.string.hql_float_dest, cp.arrivalPct));
            NavTap.Info nav = DemoMode.navInfo();
            sub.setText(nav != null && nav.eta != null ? Str.get(R.string.hql_drive_arrival) + " " + nav.eta
                    : Str.get(R.string.hql_float_no_stop));
        }
        return true;
    }

    private void paintBackground() {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(24);
        d.setColor(CarKit.alpha(CarKit.SURFACE, 1f));
        d.setStroke(dragging ? 4 : 2, dragging ? CarStyle.ACCENT : CarKit.OUTLINE);
        setBackground(d);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                moved = false;
                dragging = false;
                postDelayed(longPress, LONG_PRESS_MS);
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downX;
                float dy = e.getRawY() - downY;
                if (dragging) {
                    listener.onDrag(dx, dy);
                } else if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) {
                    // Arrastre sin mantener pulsado: ni toque ni mover (no se mueve por un roce).
                    moved = true;
                    removeCallbacks(longPress);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    paintBackground();
                    listener.onDrop();
                } else if (!moved) {
                    listener.onTap();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (dragging) {
                    dragging = false;
                    paintBackground();
                    listener.onDrop();
                }
                return true;
            default:
                return true;
        }
    }

    /** Rayo (próxima parada) o bandera (destino), en un círculo. */
    private static final class Icon extends View {
        static final int BOLT = 0;
        static final int FLAG = 1;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int kind = BOLT;
        private int color = CarKit.ACCENT;

        Icon(Context c) {
            super(c);
        }

        void set(int kind, int color) {
            if (kind == this.kind && color == this.color) return;
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(CarKit.alpha(color, 0.22f));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            if (kind == FLAG) CarIcons.flag(cv, getWidth() / 2f, getHeight() / 2f, r * 1.1f, color, p);
            else CarIcons.bolt(cv, getWidth() / 2f, getHeight() / 2f, r * 1.2f, color, p);
        }
    }
}
