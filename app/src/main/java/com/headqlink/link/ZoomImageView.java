package com.headqlink.link;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Foto que se amplía con dos dedos o con doble toque y se mueve con un dedo mientras está ampliada; sin ampliar, un
 * toque en el tercio izquierdo o derecho pasa de foto y el deslizamiento lo hace la lista que la contiene (no se le
 * quita el gesto).
 */
final class ZoomImageView extends ImageView {
    static final float MAX = 4f;

    interface Listener {
        /** Toque sin ampliar: -1 tercio izquierdo, 1 derecho, 0 centro. */
        void onTapSide(int side);
    }

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;
    private Listener listener;
    private float scale = 1f;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.FIT_CENTER);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                setZoom(scale * d.getScaleFactor());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (scale > 1.05f) {
                    reset();
                } else {
                    setZoom(2.5f);
                    // Hacia donde se tocó.
                    setTranslationX((getWidth() / 2f - e.getX()) * (scale - 1));
                    setTranslationY((getHeight() / 2f - e.getY()) * (scale - 1));
                    clampPan();
                }
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (listener == null || scale > 1.05f) return false;
                float x = e.getX() / Math.max(1, getWidth());
                listener.onTapSide(x < 1 / 3f ? -1 : x > 2 / 3f ? 1 : 0);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale <= 1.05f) return false;
                setTranslationX(getTranslationX() - dx);
                setTranslationY(getTranslationY() - dy);
                clampPan();
                return true;
            }
        });
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean zoomed() {
        return scale > 1.05f;
    }

    void reset() {
        scale = 1f;
        setScaleX(1f);
        setScaleY(1f);
        setTranslationX(0);
        setTranslationY(0);
    }

    private void setZoom(float s) {
        scale = Math.max(1f, Math.min(MAX, s));
        setScaleX(scale);
        setScaleY(scale);
        if (scale <= 1.05f) {
            setTranslationX(0);
            setTranslationY(0);
        } else {
            clampPan();
        }
    }

    private void clampPan() {
        float mx = getWidth() * (scale - 1) / 2f;
        float my = getHeight() * (scale - 1) / 2f;
        setTranslationX(Math.max(-mx, Math.min(mx, getTranslationX())));
        setTranslationY(Math.max(-my, Math.min(my, getTranslationY())));
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        scaler.onTouchEvent(ev);
        gestures.onTouchEvent(ev);
        // Ampliada (o pellizcando), los gestos son de la foto, no de la lista que pasa de una a otra.
        if (zoomed() || ev.getPointerCount() > 1) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }
}
