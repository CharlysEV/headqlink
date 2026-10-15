package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * Barra lateral de la cuadrícula para saltar por meses: los meses de arriba (los más recientes) abajo (los más
 * antiguos), repartidos en el alto; tocar o arrastrar salta a ese mes. Con más meses de los que caben se enseñan
 * algunos (siempre los cambios de año) y el dedo llega a todos.
 */
final class MonthScrubber extends View {
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String[] labels = new String[0];
    private boolean[] yearStart = new boolean[0];
    private IntConsumer onPick;
    private int current = -1;

    MonthScrubber(Context c) {
        super(c);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(CarStyle.textPx(18));
        mark.setColor(CarStyle.ACCENT);
    }

    /** labels[i]: «oct»; yearStart[i]: primer mes de su año en la lista (se enseña el año). onPick: el índice tocado. */
    void set(List<String> l, boolean[] years, IntConsumer pick) {
        labels = l.toArray(new String[0]);
        yearStart = years;
        onPick = pick;
        invalidate();
    }

    void setCurrent(int i) {
        if (i == current) return;
        current = i;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = labels.length;
        if (n == 0) return;
        float step = getHeight() / (float) n;
        float lineH = text.getTextSize() * 1.25f;
        int every = Math.max(1, (int) Math.ceil(lineH / step));
        text.setColor(CarTheme.navTextDim());
        for (int i = 0; i < n; i++) {
            boolean show = yearStart[i] || i % every == 0;
            if (!show) continue;
            float y = step * i + step / 2f + text.getTextSize() / 3f;
            if (i == current) {
                c.drawCircle(getWidth() / 2f, step * i + step / 2f, text.getTextSize(), mark);
                text.setColor(CarStyle.ON_ACCENT);
            } else {
                text.setColor(yearStart[i] ? CarTheme.navText() : CarTheme.navTextDim());
            }
            c.drawText(labels[i], getWidth() / 2f, y, text);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (labels.length == 0) return false;
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int i = (int) (ev.getY() / Math.max(1f, getHeight()) * labels.length);
            i = Math.max(0, Math.min(labels.length - 1, i));
            if (i != current && onPick != null) {
                setCurrent(i);
                onPick.accept(i);
            }
            return true;
        }
        return a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
    }
}
