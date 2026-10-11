package com.headqlink.link;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/**
 * Gráficas del apartado Viajes del móvil (PhoneInsights), dibujadas a mano con los colores de la app: anillo de
 * batería, barras de km por día con la línea de consumo, consumo de cada viaje, curva de carga (kW frente a %) y la
 * silueta de un recorrido. Escalan con la densidad de la pantalla.
 */
final class PhoneCharts {
    static final int ACCENT = 0xFF00E5C7;
    static final int ACCENT_2 = 0xFF4CFF9F;
    static final int TEXT = 0xFFEAF2F7;
    static final int DIM = 0xFFA3B3C2;
    static final int FAINT = 0xFF8296A9;
    static final int GRID = 0xFF243140;
    static final int TRACK = 0xFF1E2935;
    static final int OK = 0xFF4CFF9F;
    static final int WARN = 0xFFFFB547;
    static final int BAD = 0xFFFF6B7A;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private PhoneCharts() {
    }

    static float dp(View v, float d) {
        return d * v.getResources().getDisplayMetrics().density;
    }

    /** Color de un consumo frente a la media: verde si gasta menos, ámbar cerca, rojo si bastante más. */
    static int consumptionColor(double v, double avg) {
        if (Double.isNaN(v) || Double.isNaN(avg) || avg <= 0) return FAINT;
        if (v <= avg * 0.95) return OK;
        if (v <= avg * 1.15) return WARN;
        return BAD;
    }

    /** Anillo de batería con el % en grande y una línea debajo. */
    static final class Ring extends View {
        private double pct = Double.NaN;
        private String sub = "";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        Ring(Context c) {
            super(c);
        }

        void set(double pct, String sub) {
            this.pct = pct;
            this.sub = sub == null ? "" : sub;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            float stroke = dp(this, 12);
            r.set((w - s) / 2 + stroke, (h - s) / 2 + stroke, (w + s) / 2 - stroke, (h + s) / 2 - stroke);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(stroke);
            p.setShader(null);
            p.setColor(TRACK);
            cv.drawArc(r, 135, 270, false, p);
            if (!Double.isNaN(pct)) {
                int col = pct < 15 ? BAD : pct < 30 ? WARN : ACCENT;
                p.setShader(new LinearGradient(r.left, r.bottom, r.right, r.top, col, pct < 30 ? col : ACCENT_2, Shader.TileMode.CLAMP));
                cv.drawArc(r, 135, (float) (270 * Math.max(0, Math.min(100, pct)) / 100), false, p);
                p.setShader(null);
            }
            p.setStyle(Paint.Style.FILL);
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(TEXT);
            p.setTypeface(MEDIUM);
            p.setTextSize(s * 0.26f);
            String big = Double.isNaN(pct) ? "—" : String.format(Locale.getDefault(), "%.0f", pct);
            float cy = h / 2 + s * 0.06f;
            cv.drawText(big, w / 2 - (Double.isNaN(pct) ? 0 : s * 0.05f), cy, p);
            if (!Double.isNaN(pct)) {
                p.setTextSize(s * 0.11f);
                p.setColor(DIM);
                cv.drawText("%", w / 2 + s * 0.17f, cy, p);
            }
            p.setTextSize(s * 0.085f);
            p.setColor(FAINT);
            p.setTypeface(Typeface.DEFAULT);
            cv.drawText(sub, w / 2, cy + s * 0.17f, p);
        }
    }

    /** Barras (km por día) con una línea encima (consumo de ese día) y etiquetas cada pocos días. */
    static final class DailyBars extends View {
        private double[] bars = new double[0];
        private double[] line = new double[0];
        private String[] labels = new String[0];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        DailyBars(Context c) {
            super(c);
        }

        void set(double[] bars, double[] line, String[] labels) {
            this.bars = bars;
            this.line = line;
            this.labels = labels;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = bars.length;
            if (n == 0) return;
            float w = getWidth();
            float h = getHeight();
            float bottom = h - dp(this, 18);
            float top = dp(this, 8);
            double max = 1;
            for (double b : bars) if (b > max) max = b;
            float slot = w / n;
            float bw = Math.max(dp(this, 2), slot * 0.62f);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < n; i++) {
                float x = i * slot + (slot - bw) / 2;
                float bh = (float) ((bottom - top) * bars[i] / max);
                p.setColor(bars[i] > 0 ? 0xFF14605A : TRACK);
                if (bars[i] > 0) {
                    p.setShader(new LinearGradient(0, bottom - bh, 0, bottom, ACCENT, 0xFF0E3B37, Shader.TileMode.CLAMP));
                }
                cv.drawRoundRect(x, bottom - Math.max(bh, dp(this, 2)), x + bw, bottom, dp(this, 3), dp(this, 3), p);
                p.setShader(null);
            }
            // La línea de consumo (kWh/100 km) en su propia escala, solo donde hay dato.
            double lmin = Double.MAX_VALUE;
            double lmax = -Double.MAX_VALUE;
            for (double v : line) {
                if (Double.isNaN(v)) continue;
                lmin = Math.min(lmin, v);
                lmax = Math.max(lmax, v);
            }
            if (lmax > -Double.MAX_VALUE) {
                if (lmax - lmin < 4) {
                    lmin -= 2;
                    lmax += 2;
                }
                path.reset();
                boolean started = false;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(this, 2));
                p.setColor(WARN);
                for (int i = 0; i < line.length && i < n; i++) {
                    if (Double.isNaN(line[i])) continue;
                    float x = i * slot + slot / 2;
                    float y = (float) (bottom - (bottom - top) * 0.15 - (bottom - top) * 0.7 * (line[i] - lmin) / (lmax - lmin));
                    if (!started) path.moveTo(x, y);
                    else path.lineTo(x, y);
                    started = true;
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(x, y, dp(this, 2.5f), p);
                    p.setStyle(Paint.Style.STROKE);
                }
                cv.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(FAINT);
            p.setTextSize(dp(this, 10));
            p.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < labels.length && i < n; i++) {
                if (labels[i] != null) cv.drawText(labels[i], i * slot + slot / 2, h - dp(this, 4), p);
            }
        }
    }

    /** Consumo de cada viaje (kWh/100 km), con la media en discontinua; verde, ámbar o rojo frente a ella. */
    static final class TripBars extends View {
        private double[] v = new double[0];
        private double avg = Double.NaN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TripBars(Context c) {
            super(c);
        }

        void set(double[] v, double avg) {
            this.v = v;
            this.avg = avg;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            int n = v.length;
            if (n == 0) return;
            float w = getWidth();
            float h = getHeight();
            float bottom = h - dp(this, 4);
            float top = dp(this, 16);
            double max = 1;
            for (double x : v) if (!Double.isNaN(x) && x > max) max = x;
            max = Math.max(max, Double.isNaN(avg) ? 0 : avg * 1.2);
            float slot = w / n;
            float bw = Math.max(dp(this, 3), slot * 0.66f);
            for (int i = 0; i < n; i++) {
                if (Double.isNaN(v[i])) continue;
                float x = i * slot + (slot - bw) / 2;
                float bh = (float) ((bottom - top) * v[i] / max);
                p.setStyle(Paint.Style.FILL);
                p.setColor(consumptionColor(v[i], avg));
                cv.drawRoundRect(x, bottom - bh, x + bw, bottom, dp(this, 3), dp(this, 3), p);
            }
            if (!Double.isNaN(avg)) {
                float y = (float) (bottom - (bottom - top) * avg / max);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(this, 1.5f));
                p.setColor(TEXT);
                p.setPathEffect(new DashPathEffect(new float[]{dp(this, 5), dp(this, 4)}, 0));
                cv.drawLine(0, y, w, y, p);
                p.setPathEffect(null);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(dp(this, 11));
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(String.format(Locale.getDefault(), "%.1f", avg), w, y - dp(this, 4), p);
            }
        }
    }

    /** Curva de una carga: kW (vertical) frente al % (horizontal), con relleno; pico marcado. */
    static final class Curve extends View {
        private double[] soc = new double[0];
        private double[] kw = new double[0];
        private boolean small;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        private final Path fill = new Path();

        Curve(Context c, boolean small) {
            super(c);
            this.small = small;
        }

        void set(double[] soc, double[] kw) {
            this.soc = soc;
            this.kw = kw;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float left = small ? 0 : dp(this, 30);
            float bottom = h - (small ? dp(this, 2) : dp(this, 18));
            float top = dp(this, small ? 4 : 10);
            double s0 = 100;
            double s1 = 0;
            double kmax = 10;
            int pts = 0;
            for (int i = 0; i < soc.length; i++) {
                if (Double.isNaN(soc[i]) || Double.isNaN(kw[i])) continue;
                s0 = Math.min(s0, soc[i]);
                s1 = Math.max(s1, soc[i]);
                kmax = Math.max(kmax, kw[i]);
                pts++;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(this, 1));
            p.setColor(GRID);
            if (!small) {
                for (int g = 0; g <= 2; g++) {
                    float y = bottom - (bottom - top) * g / 2f;
                    cv.drawLine(left, y, w, y, p);
                }
            }
            if (pts < 2) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(FAINT);
                p.setTextSize(dp(this, 11));
                p.setTextAlign(Paint.Align.CENTER);
                if (!small) cv.drawText("—", w / 2, h / 2, p);
                return;
            }
            if (s1 - s0 < 5) s1 = s0 + 5;
            kmax = Math.ceil(kmax / 10) * 10;
            line.reset();
            fill.reset();
            float firstX = 0;
            float lastX = 0;
            boolean started = false;
            double peak = 0;
            float px = 0;
            float py = 0;
            for (int i = 0; i < soc.length; i++) {
                if (Double.isNaN(soc[i]) || Double.isNaN(kw[i])) continue;
                float x = (float) (left + (w - left) * (soc[i] - s0) / (s1 - s0));
                float y = (float) (bottom - (bottom - top) * kw[i] / kmax);
                if (!started) {
                    line.moveTo(x, y);
                    fill.moveTo(x, bottom);
                    fill.lineTo(x, y);
                    firstX = x;
                    started = true;
                } else {
                    line.lineTo(x, y);
                    fill.lineTo(x, y);
                }
                lastX = x;
                if (kw[i] > peak) {
                    peak = kw[i];
                    px = x;
                    py = y;
                }
            }
            fill.lineTo(lastX, bottom);
            fill.lineTo(firstX, bottom);
            fill.close();
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(0, top, 0, bottom, 0x6600E5C7, 0x0000E5C7, Shader.TileMode.CLAMP));
            cv.drawPath(fill, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(this, small ? 2 : 2.5f));
            p.setColor(ACCENT);
            cv.drawPath(line, p);
            if (!small) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(TEXT);
                cv.drawCircle(px, py, dp(this, 4), p);
                p.setTextSize(dp(this, 12));
                p.setTypeface(MEDIUM);
                p.setTextAlign(px > w * 0.7f ? Paint.Align.RIGHT : Paint.Align.LEFT);
                cv.drawText(String.format(Locale.getDefault(), "%.0f kW", peak), px + (px > w * 0.7f ? -dp(this, 8) : dp(this, 8)),
                        py + dp(this, 4), p);
                p.setTypeface(Typeface.DEFAULT);
                p.setColor(FAINT);
                p.setTextSize(dp(this, 10));
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(String.format(Locale.getDefault(), "%.0f", kmax), left - dp(this, 4), top + dp(this, 8), p);
                cv.drawText("0", left - dp(this, 4), bottom, p);
                p.setTextAlign(Paint.Align.LEFT);
                cv.drawText(String.format(Locale.getDefault(), "%.0f %%", s0), left, h - dp(this, 3), p);
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(String.format(Locale.getDefault(), "%.0f %%", s1), w, h - dp(this, 3), p);
            }
        }
    }

    /** Silueta de un recorrido (lat, lon), encajada en la vista, con la salida y la llegada. */
    static final class TrackThumb extends View {
        private double[][] track = new double[0][];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        TrackThumb(Context c) {
            super(c);
        }

        void set(double[][] track) {
            this.track = track == null ? new double[0][] : track;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF111821);
            cv.drawRoundRect(0, 0, w, h, dp(this, 14), dp(this, 14), p);
            if (track.length < 2) return;
            double la0 = 90, la1 = -90, lo0 = 180, lo1 = -180;
            for (double[] q : track) {
                la0 = Math.min(la0, q[0]);
                la1 = Math.max(la1, q[0]);
                lo0 = Math.min(lo0, q[1]);
                lo1 = Math.max(lo1, q[1]);
            }
            double cos = Math.cos(Math.toRadians((la0 + la1) / 2));
            double spanX = Math.max(1e-6, (lo1 - lo0) * cos);
            double spanY = Math.max(1e-6, la1 - la0);
            float pad = dp(this, 10);
            double k = Math.min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY);
            float ox = (float) ((w - spanX * k) / 2);
            float oy = (float) ((h - spanY * k) / 2);
            path.reset();
            int step = Math.max(1, track.length / 400);
            float sx = 0, sy = 0, ex = 0, ey = 0;
            for (int i = 0; i < track.length; i += step) {
                float x = (float) (ox + (track[i][1] - lo0) * cos * k);
                float y = (float) (h - oy - (track[i][0] - la0) * k);
                if (i == 0) {
                    path.moveTo(x, y);
                    sx = x;
                    sy = y;
                } else {
                    path.lineTo(x, y);
                }
                ex = x;
                ey = y;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(this, 3));
            p.setShader(new LinearGradient(sx, sy, ex, ey, ACCENT, ACCENT_2, Shader.TileMode.CLAMP));
            cv.drawPath(path, p);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(TEXT);
            cv.drawCircle(sx, sy, dp(this, 3.5f), p);
            p.setColor(ACCENT_2);
            cv.drawCircle(ex, ey, dp(this, 4.5f), p);
        }
    }

    /** Barras horizontales con etiqueta a la izquierda y valor a la derecha (consumo por tipo de viaje). */
    static final class HBars extends View {
        private String[] labels = new String[0];
        private double[] values = new double[0];
        private String[] notes = new String[0];
        private double avg = Double.NaN;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HBars(Context c) {
            super(c);
        }

        void set(String[] labels, double[] values, String[] notes, double avg) {
            this.labels = labels;
            this.values = values;
            this.notes = notes;
            this.avg = avg;
            requestLayout();
            invalidate();
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            setMeasuredDimension(w, (int) (labels.length * dp(this, 40)));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float row = dp(this, 40);
            float labelW = dp(this, 92);
            float valueW = dp(this, 96);
            double max = 1;
            for (double v : values) if (!Double.isNaN(v) && v > max) max = v;
            for (int i = 0; i < labels.length; i++) {
                float cy = i * row + row / 2;
                p.setStyle(Paint.Style.FILL);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(dp(this, 13));
                p.setColor(DIM);
                p.setTextAlign(Paint.Align.LEFT);
                cv.drawText(labels[i], 0, cy + dp(this, 5), p);
                float x0 = labelW;
                float x1 = w - valueW;
                p.setColor(TRACK);
                cv.drawRoundRect(x0, cy - dp(this, 6), x1, cy + dp(this, 6), dp(this, 6), dp(this, 6), p);
                if (!Double.isNaN(values[i])) {
                    p.setColor(consumptionColor(values[i], avg));
                    cv.drawRoundRect(x0, cy - dp(this, 6), (float) (x0 + (x1 - x0) * values[i] / max), cy + dp(this, 6), dp(this, 6),
                            dp(this, 6), p);
                }
                p.setColor(TEXT);
                p.setTypeface(MEDIUM);
                p.setTextAlign(Paint.Align.RIGHT);
                cv.drawText(Double.isNaN(values[i]) ? "—" : String.format(Locale.getDefault(), "%.1f", values[i]), w - dp(this, 40), cy + dp(this, 5), p);
                p.setTypeface(Typeface.DEFAULT);
                p.setTextSize(dp(this, 10));
                p.setColor(FAINT);
                cv.drawText(notes.length > i && notes[i] != null ? notes[i] : "", w, cy + dp(this, 5), p);
            }
        }
    }
}
