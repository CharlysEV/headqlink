package com.headqlink.link;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import com.andrerinas.openheadunit.aap.NavTap;

/**
 * Iconos de los paneles dibujados en código (sin imágenes nuevas): tiempo, rayo de carga, chincheta, bandera,
 * montaña, hoja, trofeo, bombilla, radar, sol, flecha de maniobra… Todos centrados en (cx, cy) con tamaño s (alto
 * aproximado). Comparten un Path y un RectF: solo desde el hilo de la interfaz.
 */
final class CarIcons {
    private static final Path PATH = new Path();
    private static final Path PATH2 = new Path();
    private static final RectF R = new RectF();

    private CarIcons() {
    }

    private static void fill(Paint p, int color) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
    }

    private static void stroke(Paint p, int color, float w) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private static void done(Paint p) {
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    // ------------------------------------------------------------------ tiempo

    static void sun(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        cv.drawCircle(cx, cy, s * 0.22f, p);
        stroke(p, color, Math.max(2, s * 0.07f));
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float c = (float) Math.cos(a);
            float n = (float) Math.sin(a);
            cv.drawLine(cx + c * s * 0.34f, cy + n * s * 0.34f, cx + c * s * 0.46f, cy + n * s * 0.46f, p);
        }
        done(p);
    }

    static void cloud(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        float w = s;
        cv.drawCircle(cx - w * 0.18f, cy + s * 0.02f, s * 0.2f, p);
        cv.drawCircle(cx + w * 0.06f, cy - s * 0.1f, s * 0.26f, p);
        cv.drawCircle(cx + w * 0.28f, cy + s * 0.04f, s * 0.18f, p);
        R.set(cx - w * 0.38f, cy + s * 0.02f, cx + w * 0.46f, cy + s * 0.22f);
        cv.drawRoundRect(R, s * 0.1f, s * 0.1f, p);
    }

    /** Icono del código de tiempo WMO (Open-Meteo). */
    static void weather(Canvas cv, int code, float cx, float cy, float s, Paint p) {
        int sunC = CarKit.AMBER;
        // De día, nubes más oscuras (sobre blanco no se verían).
        int cloudC = CarTheme.night() ? 0xFFC9D6E3 : 0xFF8FA0B3;
        if (code == 0) {
            sun(cv, cx, cy, s, sunC, p);
            return;
        }
        if (code <= 3) {
            sun(cv, cx - s * 0.16f, cy - s * 0.16f, s * 0.8f, sunC, p);
            cloud(cv, cx + s * 0.06f, cy + s * 0.12f, s * 0.82f, cloudC, p);
            return;
        }
        if (code <= 48) {
            cloud(cv, cx, cy - s * 0.12f, s * 0.8f, cloudC, p);
            stroke(p, CarKit.DIM, Math.max(2, s * 0.06f));
            cv.drawLine(cx - s * 0.36f, cy + s * 0.22f, cx + s * 0.36f, cy + s * 0.22f, p);
            cv.drawLine(cx - s * 0.26f, cy + s * 0.36f, cx + s * 0.3f, cy + s * 0.36f, p);
            done(p);
            return;
        }
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean storm = code >= 95;
        cloud(cv, cx, cy - s * 0.14f, s * 0.85f, storm ? (CarTheme.night() ? 0xFF8FA0B3 : 0xFF5E6E80) : cloudC, p);
        if (storm) {
            fill(p, CarKit.AMBER);
            PATH.rewind();
            PATH.moveTo(cx + s * 0.02f, cy + s * 0.06f);
            PATH.lineTo(cx - s * 0.12f, cy + s * 0.3f);
            PATH.lineTo(cx, cy + s * 0.3f);
            PATH.lineTo(cx - s * 0.08f, cy + s * 0.48f);
            PATH.lineTo(cx + s * 0.14f, cy + s * 0.2f);
            PATH.lineTo(cx + s * 0.02f, cy + s * 0.2f);
            PATH.close();
            cv.drawPath(PATH, p);
            return;
        }
        if (snow) {
            fill(p, 0xFFE8F1FA);
            for (int i = -1; i <= 1; i++) cv.drawCircle(cx + i * s * 0.2f, cy + s * 0.3f + (i == 0 ? s * 0.08f : 0), s * 0.05f, p);
            return;
        }
        stroke(p, CarKit.BLUE, Math.max(2, s * 0.06f));
        for (int i = -1; i <= 1; i++) {
            float x = cx + i * s * 0.2f;
            cv.drawLine(x + s * 0.04f, cy + s * 0.18f, x - s * 0.04f, cy + s * 0.36f, p);
        }
        done(p);
    }

    // ------------------------------------------------------------------ objetos

    /** Rayo (carga, energía). */
    static void bolt(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx + s * 0.08f, cy - s * 0.5f);
        PATH.lineTo(cx - s * 0.28f, cy + s * 0.06f);
        PATH.lineTo(cx - s * 0.02f, cy + s * 0.06f);
        PATH.lineTo(cx - s * 0.1f, cy + s * 0.5f);
        PATH.lineTo(cx + s * 0.28f, cy - s * 0.08f);
        PATH.lineTo(cx + s * 0.02f, cy - s * 0.08f);
        PATH.close();
        cv.drawPath(PATH, p);
    }

    /** Surtidor de gasolina (REEV). */
    static void fuel(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        // Cuerpo con la ventanilla hueca.
        R.set(cx - s * 0.36f, cy - s * 0.44f, cx + s * 0.12f, cy + s * 0.44f);
        cv.drawRoundRect(R, s * 0.06f, s * 0.06f, p);
        cv.drawRect(cx - s * 0.46f, cy + s * 0.36f, cx + s * 0.22f, cy + s * 0.48f, p);
        p.setColor(CarKit.SURFACE);
        R.set(cx - s * 0.27f, cy - s * 0.34f, cx + s * 0.03f, cy - s * 0.1f);
        cv.drawRoundRect(R, s * 0.03f, s * 0.03f, p);
        // Manguera.
        stroke(p, color, Math.max(2, s * 0.07f));
        PATH.rewind();
        PATH.moveTo(cx + s * 0.12f, cy - s * 0.12f);
        PATH.lineTo(cx + s * 0.3f, cy - s * 0.12f);
        PATH.lineTo(cx + s * 0.3f, cy + s * 0.22f);
        PATH.cubicTo(cx + s * 0.3f, cy + s * 0.32f, cx + s * 0.44f, cy + s * 0.32f, cx + s * 0.44f, cy + s * 0.22f);
        PATH.lineTo(cx + s * 0.44f, cy - s * 0.2f);
        PATH.lineTo(cx + s * 0.32f, cy - s * 0.36f);
        cv.drawPath(PATH, p);
        done(p);
    }

    /** Micrófono (buscar hablando). */
    static void mic(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        R.set(cx - s * 0.16f, cy - s * 0.48f, cx + s * 0.16f, cy + s * 0.12f);
        cv.drawRoundRect(R, s * 0.16f, s * 0.16f, p);
        stroke(p, color, Math.max(2, s * 0.08f));
        R.set(cx - s * 0.3f, cy - s * 0.2f, cx + s * 0.3f, cy + s * 0.3f);
        cv.drawArc(R, 0, 180, false, p);
        cv.drawLine(cx, cy + s * 0.3f, cx, cy + s * 0.46f, p);
        cv.drawLine(cx - s * 0.18f, cy + s * 0.46f, cx + s * 0.18f, cy + s * 0.46f, p);
        done(p);
    }

    /** Chincheta de destino. */
    static void pin(Canvas cv, float cx, float cy, float s, int color, int hole, Paint p) {
        fill(p, color);
        float r = s * 0.3f;
        float top = cy - s * 0.5f;
        PATH.rewind();
        R.set(cx - r, top, cx + r, top + 2 * r);
        PATH.arcTo(R, 150, 240);
        PATH.lineTo(cx, cy + s * 0.5f);
        PATH.close();
        cv.drawPath(PATH, p);
        p.setColor(hole);
        cv.drawCircle(cx, top + r, r * 0.42f, p);
    }

    /** Bandera de meta. */
    static void flag(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        stroke(p, color, Math.max(2, s * 0.08f));
        cv.drawLine(cx - s * 0.28f, cy - s * 0.46f, cx - s * 0.28f, cy + s * 0.5f, p);
        done(p);
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.26f, cy - s * 0.46f);
        PATH.cubicTo(cx, cy - s * 0.56f, cx + s * 0.12f, cy - s * 0.3f, cx + s * 0.38f, cy - s * 0.4f);
        PATH.lineTo(cx + s * 0.38f, cy - s * 0.02f);
        PATH.cubicTo(cx + s * 0.12f, cy + s * 0.08f, cx, cy - s * 0.18f, cx - s * 0.26f, cy - s * 0.08f);
        PATH.close();
        cv.drawPath(PATH, p);
    }

    static void mountain(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.5f, cy + s * 0.36f);
        PATH.lineTo(cx - s * 0.12f, cy - s * 0.36f);
        PATH.lineTo(cx + s * 0.08f, cy);
        PATH.lineTo(cx + s * 0.2f, cy - s * 0.16f);
        PATH.lineTo(cx + s * 0.5f, cy + s * 0.36f);
        PATH.close();
        cv.drawPath(PATH, p);
    }

    static void leaf(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.36f, cy + s * 0.36f);
        PATH.cubicTo(cx - s * 0.46f, cy - s * 0.2f, cx - s * 0.04f, cy - s * 0.46f, cx + s * 0.42f, cy - s * 0.44f);
        PATH.cubicTo(cx + s * 0.44f, cy + s * 0.04f, cx + s * 0.12f, cy + s * 0.42f, cx - s * 0.36f, cy + s * 0.36f);
        PATH.close();
        cv.drawPath(PATH, p);
        stroke(p, CarKit.SURFACE, Math.max(2, s * 0.06f));
        cv.drawLine(cx - s * 0.3f, cy + s * 0.3f, cx + s * 0.22f, cy - s * 0.22f, p);
        done(p);
    }

    static void trophy(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx - s * 0.3f, cy - s * 0.44f);
        PATH.lineTo(cx + s * 0.3f, cy - s * 0.44f);
        PATH.cubicTo(cx + s * 0.3f, cy, cx + s * 0.12f, cy + s * 0.12f, cx, cy + s * 0.12f);
        PATH.cubicTo(cx - s * 0.12f, cy + s * 0.12f, cx - s * 0.3f, cy, cx - s * 0.3f, cy - s * 0.44f);
        PATH.close();
        cv.drawPath(PATH, p);
        cv.drawRect(cx - s * 0.05f, cy + s * 0.1f, cx + s * 0.05f, cy + s * 0.32f, p);
        R.set(cx - s * 0.22f, cy + s * 0.3f, cx + s * 0.22f, cy + s * 0.44f);
        cv.drawRoundRect(R, s * 0.04f, s * 0.04f, p);
        stroke(p, color, Math.max(2, s * 0.07f));
        R.set(cx - s * 0.46f, cy - s * 0.38f, cx - s * 0.18f, cy - s * 0.06f);
        cv.drawArc(R, 90, 180, false, p);
        R.set(cx + s * 0.18f, cy - s * 0.38f, cx + s * 0.46f, cy - s * 0.06f);
        cv.drawArc(R, -90, 180, false, p);
        done(p);
    }

    static void bulb(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        cv.drawCircle(cx, cy - s * 0.12f, s * 0.3f, p);
        R.set(cx - s * 0.15f, cy + s * 0.08f, cx + s * 0.15f, cy + s * 0.3f);
        cv.drawRect(R, p);
        R.set(cx - s * 0.13f, cy + s * 0.34f, cx + s * 0.13f, cy + s * 0.44f);
        cv.drawRoundRect(R, s * 0.04f, s * 0.04f, p);
    }

    /** Radar fijo: cámara en un poste. */
    static void camera(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        fill(p, color);
        R.set(cx - s * 0.42f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.12f);
        cv.drawRoundRect(R, s * 0.08f, s * 0.08f, p);
        cv.drawRect(cx - s * 0.06f, cy + s * 0.1f, cx + s * 0.06f, cy + s * 0.48f, p);
        PATH.rewind();
        PATH.moveTo(cx + s * 0.3f, cy - s * 0.2f);
        PATH.lineTo(cx + s * 0.5f, cy - s * 0.3f);
        PATH.lineTo(cx + s * 0.5f, cy + s * 0.04f);
        PATH.lineTo(cx + s * 0.3f, cy - s * 0.04f);
        PATH.close();
        cv.drawPath(PATH, p);
    }

    /** Reloj. */
    static void clock(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        stroke(p, color, Math.max(2, s * 0.09f));
        cv.drawCircle(cx, cy, s * 0.42f, p);
        cv.drawLine(cx, cy, cx, cy - s * 0.24f, p);
        cv.drawLine(cx, cy, cx + s * 0.18f, cy + s * 0.1f, p);
        done(p);
    }

    /** Ruta (dos puntos unidos por una curva). */
    static void route(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        stroke(p, color, Math.max(2, s * 0.09f));
        PATH.rewind();
        PATH.moveTo(cx - s * 0.32f, cy + s * 0.34f);
        PATH.cubicTo(cx + s * 0.5f, cy + s * 0.3f, cx - s * 0.5f, cy - s * 0.3f, cx + s * 0.32f, cy - s * 0.34f);
        cv.drawPath(PATH, p);
        done(p);
        fill(p, color);
        cv.drawCircle(cx - s * 0.32f, cy + s * 0.34f, s * 0.11f, p);
        cv.drawCircle(cx + s * 0.32f, cy - s * 0.34f, s * 0.11f, p);
    }

    /** Termómetro. */
    static void thermo(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        stroke(p, color, Math.max(2, s * 0.08f));
        R.set(cx - s * 0.1f, cy - s * 0.46f, cx + s * 0.1f, cy + s * 0.2f);
        cv.drawRoundRect(R, s * 0.1f, s * 0.1f, p);
        done(p);
        fill(p, color);
        cv.drawCircle(cx, cy + s * 0.3f, s * 0.17f, p);
        cv.drawRect(cx - s * 0.035f, cy - s * 0.18f, cx + s * 0.035f, cy + s * 0.2f, p);
    }

    /** Flecha de viento: hacia donde sopla (0 = hacia arriba, grados en sentido horario). */
    static void windArrow(Canvas cv, float cx, float cy, float s, float deg, int color, Paint p) {
        cv.save();
        cv.rotate(deg, cx, cy);
        stroke(p, color, Math.max(3, s * 0.1f));
        cv.drawLine(cx, cy + s * 0.46f, cx, cy - s * 0.3f, p);
        done(p);
        fill(p, color);
        PATH.rewind();
        PATH.moveTo(cx, cy - s * 0.5f);
        PATH.lineTo(cx - s * 0.2f, cy - s * 0.18f);
        PATH.lineTo(cx + s * 0.2f, cy - s * 0.18f);
        PATH.close();
        cv.drawPath(PATH, p);
        cv.restore();
    }

    // ------------------------------------------------------------------ maniobras

    /**
     * Flecha de maniobra de Android Auto (tipo y ángulo de NavTap; ángulo negativo a la izquierda) en una caja de
     * tamaño s centrada en (cx, cy).
     */
    static void maneuver(Canvas cv, int kind, int angle, float cx, float cy, float s, int color, Paint p) {
        float w = Math.max(4, s * 0.12f);
        stroke(p, color, w);
        float base = cy + s * 0.46f;
        float mid = cy + s * 0.04f;
        if (kind == NavTap.KIND_ROUNDABOUT) {
            cv.drawCircle(cx, cy - s * 0.12f, s * 0.22f, p);
            cv.drawLine(cx, base, cx, cy + s * 0.1f, p);
            // Salida: hacia arriba a la derecha.
            cv.drawLine(cx + s * 0.16f, cy - s * 0.28f, cx + s * 0.3f, cy - s * 0.42f, p);
            done(p);
            arrowHead(cv, cx + s * 0.3f, cy - s * 0.42f, Math.toRadians(45), s * 0.22f, color, p);
            return;
        }
        if (kind == NavTap.KIND_DESTINATION) {
            done(p);
            pin(cv, cx + angle * s * 0.002f, cy, s * 0.9f, color, CarKit.SURFACE_TOP, p);
            return;
        }
        double dir;
        float ex;
        float ey;
        PATH2.rewind();
        if (kind == NavTap.KIND_UTURN) {
            float side = angle < 0 ? -1 : 1;
            PATH2.moveTo(cx + side * s * 0.2f, base);
            PATH2.lineTo(cx + side * s * 0.2f, cy - s * 0.1f);
            PATH2.cubicTo(cx + side * s * 0.2f, cy - s * 0.5f, cx - side * s * 0.2f, cy - s * 0.5f, cx - side * s * 0.2f, cy - s * 0.1f);
            PATH2.lineTo(cx - side * s * 0.2f, cy + s * 0.1f);
            ex = cx - side * s * 0.2f;
            ey = cy + s * 0.14f;
            dir = Math.PI;
        } else {
            float a = kind == NavTap.KIND_DEPART ? 0 : angle;
            dir = Math.toRadians(a);
            float len = s * 0.42f;
            PATH2.moveTo(cx, base);
            PATH2.lineTo(cx, mid);
            ex = (float) (cx + Math.sin(dir) * len);
            ey = (float) (mid - Math.cos(dir) * len);
            // Curva suave en el giro.
            PATH2.quadTo(cx, mid - len * 0.35f, ex, ey);
        }
        cv.drawPath(PATH2, p);
        done(p);
        arrowHead(cv, ex, ey, dir, s * 0.28f, color, p);
    }

    private static void arrowHead(Canvas cv, float x, float y, double dir, float hs, int color, Paint p) {
        fill(p, color);
        PATH.rewind();
        PATH.moveTo((float) (x + Math.sin(dir) * hs * 0.55), (float) (y - Math.cos(dir) * hs * 0.55));
        double a1 = dir + Math.toRadians(140);
        double a2 = dir - Math.toRadians(140);
        PATH.lineTo((float) (x + Math.sin(a1) * hs * 0.75), (float) (y - Math.cos(a1) * hs * 0.75));
        PATH.lineTo((float) (x + Math.sin(a2) * hs * 0.75), (float) (y - Math.cos(a2) * hs * 0.75));
        PATH.close();
        cv.drawPath(PATH, p);
    }

    /** Flecha de carril (recto, o desviada si el carril recomendado gira). */
    static void lane(Canvas cv, float cx, float cy, float s, int color, Paint p) {
        stroke(p, color, Math.max(3, s * 0.12f));
        cv.drawLine(cx, cy + s * 0.4f, cx, cy - s * 0.2f, p);
        done(p);
        arrowHead(cv, cx, cy - s * 0.24f, 0, s * 0.5f, color, p);
    }
}
