package com.headqlink.link;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * El coche dibujado a mano para los guiños de los juegos, sin texto ni logotipos: lo reconocible es
 * su frontal liso de eléctrico, la tira de luz que cruza el morro de lado a lado (y la trasera),
 * los faros bajos en el paragolpes y el techo en negro.
 */
final class CarArt {
    static final int BODY = 0xFFDDE3E6;
    static final int BODY_SHADE = 0xFFB9C1C6;
    static final int ROOF = 0xFF2A2E33;
    static final int GLASS = 0xFF33465E;
    static final int LIGHT = 0xFFF4F8FF;
    static final int TAIL = 0xFFFF5A5F;
    static final int TYRE = 0xFF17191C;

    /** Colores oficiales del T03 (Leapmotor España): Blanco Lunar, Azul Caribe y Gris Oscuro. */
    static final int[] T03_PAINTS = {0xFFE9EBEA, 0xFF2E76BE, 0xFF4C5056};
    private static final int T03_LAMP = 0xFFD2E4F0;
    private static final int T03_LAMP_RING = 0xFF5A6E7D;
    private static final int T03_LAMP_EYE = 0xFF7896AA;
    private static final int T03_GLASS_HI = 0xFF3E4A58;
    private static final int T03_PANO = 0xFF1E242C;

    private CarArt() {
    }

    /** Puro: el color multiplicado por k (sombras y nervaduras de la carrocería). */
    static int shade(int color, float k) {
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) * k));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) * k));
        int b = Math.min(255, Math.round((color & 0xFF) * k));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Puro: el color guardado si es uno de los del T03; si no, el Blanco Lunar. */
    static int t03Paint(String hex) {
        if (hex != null && hex.matches("#[0-9a-fA-F]{6}")) {
            int c = 0xFF000000 | Integer.parseInt(hex.substring(1), 16);
            for (int p : T03_PAINTS) if (p == c) return c;
        }
        return T03_PAINTS[0];
    }

    /** Puro: el siguiente color del T03 tras el guardado (o el primero). */
    static int t03NextPaint(String hex) {
        int c = t03Paint(hex);
        for (int i = 0; i < T03_PAINTS.length; i++) if (T03_PAINTS[i] == c) return T03_PAINTS[(i + 1) % T03_PAINTS.length];
        return T03_PAINTS[0];
    }

    static String hex(int color) {
        return String.format(java.util.Locale.ROOT, "#%06x", color & 0xFFFFFF);
    }

    /**
     * El T03 visto desde arriba, morro hacia arriba, ocupando r, del color paint: un cubo con las esquinas suaves, los
     * faros redondos, el techo negro con el panel panorámico y la barra de luz roja detrás. Las ruedas asoman por los
     * lados a las mismas alturas que en top() (15 % y 70 %): las presiones de la pestaña Estado van a su lado.
     */
    static void topT03(Canvas cv, RectF r, int paint, Paint p) {
        float w = r.width();
        float h = r.height();
        float x0 = r.left;
        float y0 = r.top;
        p.setStyle(Paint.Style.FILL);
        // Ruedas.
        p.setColor(TYRE);
        float tw = 0.10f * w;
        float th = 0.16f * h;
        for (float ty : new float[]{0.15f, 0.70f}) {
            cv.drawRoundRect(new RectF(x0 - tw * 0.2f, y0 + ty * h, x0 + tw * 0.8f, y0 + ty * h + th), tw / 3, tw / 3, p);
            cv.drawRoundRect(new RectF(x0 + w - tw * 0.8f, y0 + ty * h, x0 + w + tw * 0.2f, y0 + ty * h + th), tw / 3, tw / 3, p);
        }
        // Carrocería con una sombra suave debajo (el lienzo del bitmap es por software: la sombra sí se pinta).
        p.setColor(paint);
        p.setShadowLayer(0.04f * w, 0, 0.012f * h, 0x66000000);
        RectF body = new RectF(x0 + 0.02f * w, y0, x0 + 0.98f * w, y0 + h);
        cv.drawRoundRect(body, 0.22f * w, 0.22f * w, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1.5f, 0.006f * w));
        p.setColor(shade(paint, 0.72f));
        cv.drawRoundRect(body, 0.22f * w, 0.22f * w, p);
        p.setStyle(Paint.Style.FILL);
        // Capó algo más claro con la nervadura central.
        p.setColor(shade(paint, 1.04f));
        cv.drawRoundRect(new RectF(x0 + 0.08f * w, y0 + 0.05f * h, x0 + 0.92f * w, y0 + 0.27f * h), 0.10f * w, 0.10f * w, p);
        p.setColor(shade(paint, 0.85f));
        p.setStrokeWidth(Math.max(1f, 0.004f * w));
        cv.drawLine(x0 + w / 2, y0 + 0.07f * h, x0 + w / 2, y0 + 0.26f * h, p);
        // Faros redondos, lo más característico del T03.
        float rr = 0.075f * w;
        for (float cx : new float[]{x0 + 0.19f * w, x0 + 0.81f * w}) {
            float cy = y0 + 0.075f * h;
            p.setColor(T03_LAMP_RING);
            cv.drawOval(new RectF(cx - rr, cy - rr * 0.8f, cx + rr, cy + rr * 0.8f), p);
            p.setColor(T03_LAMP);
            cv.drawOval(new RectF(cx - rr * 0.78f, cy - rr * 0.6f, cx + rr * 0.78f, cy + rr * 0.6f), p);
            p.setColor(T03_LAMP_EYE);
            cv.drawOval(new RectF(cx - rr * 0.35f, cy - rr * 0.28f, cx + rr * 0.35f, cy + rr * 0.28f), p);
        }
        // Logo delante.
        p.setColor(shade(paint, 0.8f));
        cv.drawOval(new RectF(x0 + 0.48f * w, y0 + 0.015f * h, x0 + 0.52f * w, y0 + 0.045f * h), p);
        // Parabrisas (trapecio con su reflejo), techo negro con el panel panorámico y luneta.
        Path ws = new Path();
        ws.moveTo(x0 + 0.15f * w, y0 + 0.40f * h);
        ws.lineTo(x0 + 0.85f * w, y0 + 0.40f * h);
        ws.lineTo(x0 + 0.81f * w, y0 + 0.28f * h);
        ws.lineTo(x0 + 0.19f * w, y0 + 0.28f * h);
        ws.close();
        p.setColor(GLASS);
        cv.drawPath(ws, p);
        Path hi = new Path();
        hi.moveTo(x0 + 0.18f * w, y0 + 0.31f * h);
        hi.lineTo(x0 + 0.82f * w, y0 + 0.31f * h);
        hi.lineTo(x0 + 0.83f * w, y0 + 0.345f * h);
        hi.lineTo(x0 + 0.17f * w, y0 + 0.345f * h);
        hi.close();
        p.setColor(T03_GLASS_HI);
        cv.drawPath(hi, p);
        p.setColor(ROOF);
        cv.drawRoundRect(new RectF(x0 + 0.14f * w, y0 + 0.38f * h, x0 + 0.86f * w, y0 + 0.86f * h), 0.08f * w, 0.08f * w, p);
        p.setColor(T03_PANO);
        cv.drawRoundRect(new RectF(x0 + 0.22f * w, y0 + 0.44f * h, x0 + 0.78f * w, y0 + 0.70f * h), 0.05f * w, 0.05f * w, p);
        Path rw = new Path();
        rw.moveTo(x0 + 0.17f * w, y0 + 0.76f * h);
        rw.lineTo(x0 + 0.83f * w, y0 + 0.76f * h);
        rw.lineTo(x0 + 0.85f * w, y0 + 0.87f * h);
        rw.lineTo(x0 + 0.15f * w, y0 + 0.87f * h);
        rw.close();
        p.setColor(GLASS);
        cv.drawPath(rw, p);
        // Barra de luz roja del portón.
        p.setColor(TAIL);
        cv.drawRoundRect(new RectF(x0 + 0.12f * w, y0 + 0.905f * h, x0 + 0.88f * w, y0 + 0.925f * h), 0.01f * w, 0.01f * w, p);
        // Juntas de las puertas.
        p.setColor(shade(paint, 0.7f));
        p.setStrokeWidth(Math.max(1f, 0.004f * w));
        for (float yy : new float[]{0.42f, 0.63f}) {
            cv.drawLine(x0 + 0.02f * w, y0 + yy * h, x0 + 0.08f * w, y0 + yy * h, p);
            cv.drawLine(x0 + 0.92f * w, y0 + yy * h, x0 + 0.98f * w, y0 + yy * h, p);
        }
        // Retrovisores.
        p.setColor(ROOF);
        cv.drawRoundRect(new RectF(x0 - 0.09f * w, y0 + 0.335f * h, x0 + 0.04f * w, y0 + 0.375f * h), 0.02f * w, 0.02f * w, p);
        cv.drawRoundRect(new RectF(x0 + 0.96f * w, y0 + 0.335f * h, x0 + 1.09f * w, y0 + 0.375f * h), 0.02f * w, 0.02f * w, p);
    }

    /** Tira de luz delantera de front() para el mismo r (la animación de carga la recorre). */
    static RectF frontLightBar(RectF r) {
        float w = Math.min(r.width(), r.height() * 1.25f) * 0.82f;
        float h = w * 0.9f;
        float x0 = r.centerX() - w / 2;
        float y0 = r.centerY() - h / 2;
        return new RectF(x0 + 0.11f * w, y0 + 0.505f * h, x0 + 0.89f * w, y0 + 0.535f * h);
    }

    /** Frontal del coche centrado en r (carta de memoria). */
    static void front(Canvas cv, RectF r, Paint p) {
        float w = Math.min(r.width(), r.height() * 1.25f) * 0.82f;
        float h = w * 0.9f; // proporción de SUV: más alto que un turismo
        float x0 = r.centerX() - w / 2;
        float y0 = r.centerY() - h / 2;
        p.setStyle(Paint.Style.FILL);

        // Ruedas asomando bajo la carrocería.
        p.setColor(TYRE);
        cv.drawRoundRect(new RectF(x0 + 0.10f * w, y0 + 0.78f * h, x0 + 0.25f * w, y0 + 0.98f * h), 0.03f * w, 0.03f * w, p);
        cv.drawRoundRect(new RectF(x0 + 0.75f * w, y0 + 0.78f * h, x0 + 0.90f * w, y0 + 0.98f * h), 0.03f * w, 0.03f * w, p);

        // Habitáculo: techo negro que baja en curva hasta la línea de cintura.
        Path cabin = new Path();
        cabin.moveTo(x0 + 0.13f * w, y0 + 0.44f * h);
        cabin.cubicTo(x0 + 0.17f * w, y0 + 0.22f * h, x0 + 0.22f * w, y0 + 0.08f * h, x0 + 0.34f * w, y0 + 0.06f * h);
        cabin.lineTo(x0 + 0.66f * w, y0 + 0.06f * h);
        cabin.cubicTo(x0 + 0.78f * w, y0 + 0.08f * h, x0 + 0.83f * w, y0 + 0.22f * h, x0 + 0.87f * w, y0 + 0.44f * h);
        cabin.close();
        p.setColor(ROOF);
        cv.drawPath(cabin, p);

        // Parabrisas.
        Path glass = new Path();
        glass.moveTo(x0 + 0.19f * w, y0 + 0.42f * h);
        glass.cubicTo(x0 + 0.22f * w, y0 + 0.27f * h, x0 + 0.26f * w, y0 + 0.15f * h, x0 + 0.36f * w, y0 + 0.13f * h);
        glass.lineTo(x0 + 0.64f * w, y0 + 0.13f * h);
        glass.cubicTo(x0 + 0.74f * w, y0 + 0.15f * h, x0 + 0.78f * w, y0 + 0.27f * h, x0 + 0.81f * w, y0 + 0.42f * h);
        glass.close();
        p.setColor(GLASS);
        cv.drawPath(glass, p);

        // Retrovisores.
        p.setColor(ROOF);
        cv.drawRoundRect(new RectF(x0 + 0.02f * w, y0 + 0.38f * h, x0 + 0.13f * w, y0 + 0.46f * h), 0.03f * w, 0.03f * w, p);
        cv.drawRoundRect(new RectF(x0 + 0.87f * w, y0 + 0.38f * h, x0 + 0.98f * w, y0 + 0.46f * h), 0.03f * w, 0.03f * w, p);

        // Carrocería: morro liso y redondeado, sin calandra.
        Path body = new Path();
        body.moveTo(x0 + 0.08f * w, y0 + 0.50f * h);
        body.cubicTo(x0 + 0.08f * w, y0 + 0.45f * h, x0 + 0.11f * w, y0 + 0.43f * h, x0 + 0.16f * w, y0 + 0.43f * h);
        body.lineTo(x0 + 0.84f * w, y0 + 0.43f * h);
        body.cubicTo(x0 + 0.89f * w, y0 + 0.43f * h, x0 + 0.92f * w, y0 + 0.45f * h, x0 + 0.92f * w, y0 + 0.50f * h);
        body.lineTo(x0 + 0.93f * w, y0 + 0.78f * h);
        body.cubicTo(x0 + 0.93f * w, y0 + 0.84f * h, x0 + 0.90f * w, y0 + 0.86f * h, x0 + 0.85f * w, y0 + 0.86f * h);
        body.lineTo(x0 + 0.15f * w, y0 + 0.86f * h);
        body.cubicTo(x0 + 0.10f * w, y0 + 0.86f * h, x0 + 0.07f * w, y0 + 0.84f * h, x0 + 0.07f * w, y0 + 0.78f * h);
        body.close();
        p.setColor(BODY);
        cv.drawPath(body, p);

        // Tira de luz de lado a lado: su sello.
        p.setColor(LIGHT);
        cv.drawRoundRect(new RectF(x0 + 0.11f * w, y0 + 0.505f * h, x0 + 0.89f * w, y0 + 0.535f * h), 0.015f * w, 0.015f * w, p);

        // Faros bajos en las esquinas del paragolpes.
        Path l = new Path();
        l.moveTo(x0 + 0.11f * w, y0 + 0.60f * h);
        l.lineTo(x0 + 0.27f * w, y0 + 0.62f * h);
        l.lineTo(x0 + 0.26f * w, y0 + 0.67f * h);
        l.lineTo(x0 + 0.12f * w, y0 + 0.66f * h);
        l.close();
        Path rr = new Path();
        rr.moveTo(x0 + 0.89f * w, y0 + 0.60f * h);
        rr.lineTo(x0 + 0.73f * w, y0 + 0.62f * h);
        rr.lineTo(x0 + 0.74f * w, y0 + 0.67f * h);
        rr.lineTo(x0 + 0.88f * w, y0 + 0.66f * h);
        rr.close();
        p.setColor(0xFF4A5560);
        cv.drawPath(l, p);
        cv.drawPath(rr, p);
        p.setColor(LIGHT);
        cv.drawRect(x0 + 0.13f * w, y0 + 0.625f * h, x0 + 0.24f * w, y0 + 0.645f * h, p);
        cv.drawRect(x0 + 0.76f * w, y0 + 0.625f * h, x0 + 0.87f * w, y0 + 0.645f * h, p);

        // Toma inferior y faldón.
        p.setColor(BODY_SHADE);
        cv.drawRoundRect(new RectF(x0 + 0.33f * w, y0 + 0.70f * h, x0 + 0.67f * w, y0 + 0.78f * h), 0.03f * w, 0.03f * w, p);
        p.setColor(0xFF3C4043);
        cv.drawRoundRect(new RectF(x0 + 0.36f * w, y0 + 0.72f * h, x0 + 0.64f * w, y0 + 0.76f * h), 0.02f * w, 0.02f * w, p);
    }

    /** El coche de perfil, morro a la derecha, ocupando r (juego del coche volador). */
    static void side(Canvas cv, RectF r, Paint p) {
        float w = r.width();
        float h = r.height();
        float x0 = r.left;
        float y0 = r.top;
        p.setStyle(Paint.Style.FILL);
        // Carrocería: trasera casi vertical, techo largo que cae suave, parabrisas inclinado y morro bajo redondeado.
        Path body = new Path();
        body.moveTo(x0 + 0.03f * w, y0 + 0.80f * h);
        body.lineTo(x0 + 0.02f * w, y0 + 0.42f * h);
        body.cubicTo(x0 + 0.03f * w, y0 + 0.22f * h, x0 + 0.08f * w, y0 + 0.08f * h, x0 + 0.20f * w, y0 + 0.06f * h);
        body.lineTo(x0 + 0.58f * w, y0 + 0.04f * h);
        body.cubicTo(x0 + 0.64f * w, y0 + 0.04f * h, x0 + 0.68f * w, y0 + 0.08f * h, x0 + 0.74f * w, y0 + 0.30f * h);
        body.cubicTo(x0 + 0.88f * w, y0 + 0.34f * h, x0 + 0.97f * w, y0 + 0.40f * h, x0 + 0.99f * w, y0 + 0.56f * h);
        body.lineTo(x0 + 0.98f * w, y0 + 0.80f * h);
        body.close();
        p.setColor(BODY);
        cv.drawPath(body, p);
        // Techo negro y lunas.
        Path roof = new Path();
        roof.moveTo(x0 + 0.07f * w, y0 + 0.36f * h);
        roof.cubicTo(x0 + 0.09f * w, y0 + 0.20f * h, x0 + 0.12f * w, y0 + 0.11f * h, x0 + 0.21f * w, y0 + 0.10f * h);
        roof.lineTo(x0 + 0.57f * w, y0 + 0.08f * h);
        roof.cubicTo(x0 + 0.62f * w, y0 + 0.09f * h, x0 + 0.66f * w, y0 + 0.16f * h, x0 + 0.71f * w, y0 + 0.36f * h);
        roof.close();
        p.setColor(ROOF);
        cv.drawPath(roof, p);
        p.setColor(GLASS);
        cv.drawRect(x0 + 0.13f * w, y0 + 0.15f * h, x0 + 0.37f * w, y0 + 0.34f * h, p);
        Path front = new Path();
        front.moveTo(x0 + 0.40f * w, y0 + 0.14f * h);
        front.lineTo(x0 + 0.58f * w, y0 + 0.13f * h);
        front.cubicTo(x0 + 0.61f * w, y0 + 0.14f * h, x0 + 0.64f * w, y0 + 0.22f * h, x0 + 0.67f * w, y0 + 0.34f * h);
        front.lineTo(x0 + 0.40f * w, y0 + 0.34f * h);
        front.close();
        cv.drawPath(front, p);
        // Tiradores enrasados y línea de puertas.
        p.setColor(BODY_SHADE);
        cv.drawRect(x0 + 0.39f * w, y0 + 0.36f * h, x0 + 0.395f * w, y0 + 0.74f * h, p);
        cv.drawRect(x0 + 0.24f * w, y0 + 0.44f * h, x0 + 0.30f * w, y0 + 0.47f * h, p);
        cv.drawRect(x0 + 0.47f * w, y0 + 0.44f * h, x0 + 0.53f * w, y0 + 0.47f * h, p);
        // Tira de luz delantera y piloto trasero.
        p.setColor(LIGHT);
        cv.drawRoundRect(new RectF(x0 + 0.93f * w, y0 + 0.44f * h, x0 + 0.995f * w, y0 + 0.49f * h), 0.01f * w, 0.01f * w, p);
        p.setColor(TAIL);
        cv.drawRoundRect(new RectF(x0 + 0.005f * w, y0 + 0.40f * h, x0 + 0.05f * w, y0 + 0.46f * h), 0.01f * w, 0.01f * w, p);
        // Ruedas grandes con llanta aerodinámica.
        float wr = 0.115f * w;
        for (float wx : new float[]{0.20f, 0.79f}) {
            p.setColor(TYRE);
            cv.drawCircle(x0 + wx * w, y0 + 0.80f * h, wr, p);
            p.setColor(0xFF9AA0A6);
            cv.drawCircle(x0 + wx * w, y0 + 0.80f * h, wr * 0.62f, p);
            p.setColor(0xFF5F6368);
            cv.drawCircle(x0 + wx * w, y0 + 0.80f * h, wr * 0.2f, p);
        }
    }

    /** El coche visto desde arriba, morro hacia arriba, ocupando r (juego Autonomía). */
    static void top(Canvas cv, RectF r, Paint p) {
        float w = r.width();
        float h = r.height();
        float x0 = r.left;
        float y0 = r.top;
        p.setStyle(Paint.Style.FILL);
        // Ruedas.
        p.setColor(TYRE);
        float tw = 0.10f * w;
        float th = 0.16f * h;
        for (float ty : new float[]{0.15f, 0.70f}) {
            cv.drawRoundRect(new RectF(x0 - tw * 0.2f, y0 + ty * h, x0 + tw * 0.8f, y0 + ty * h + th), tw / 3, tw / 3, p);
            cv.drawRoundRect(new RectF(x0 + w - tw * 0.8f, y0 + ty * h, x0 + w + tw * 0.2f, y0 + ty * h + th), tw / 3, tw / 3, p);
        }
        // Carrocería.
        p.setColor(BODY);
        cv.drawRoundRect(new RectF(x0 + 0.04f * w, y0, x0 + 0.96f * w, y0 + h), 0.30f * w, 0.14f * h, p);
        // Techo negro con parabrisas y luneta.
        p.setColor(ROOF);
        cv.drawRoundRect(new RectF(x0 + 0.16f * w, y0 + 0.24f * h, x0 + 0.84f * w, y0 + 0.86f * h), 0.16f * w, 0.08f * h, p);
        p.setColor(GLASS);
        cv.drawRoundRect(new RectF(x0 + 0.18f * w, y0 + 0.25f * h, x0 + 0.82f * w, y0 + 0.40f * h), 0.12f * w, 0.05f * h, p);
        cv.drawRoundRect(new RectF(x0 + 0.20f * w, y0 + 0.76f * h, x0 + 0.80f * w, y0 + 0.85f * h), 0.10f * w, 0.04f * h, p);
        // Retrovisores.
        p.setColor(ROOF);
        cv.drawRoundRect(new RectF(x0 - 0.04f * w, y0 + 0.30f * h, x0 + 0.08f * w, y0 + 0.34f * h), 0.02f * w, 0.02f * w, p);
        cv.drawRoundRect(new RectF(x0 + 0.92f * w, y0 + 0.30f * h, x0 + 1.04f * w, y0 + 0.34f * h), 0.02f * w, 0.02f * w, p);
        // Tira de luz delantera y trasera, de lado a lado.
        p.setColor(LIGHT);
        cv.drawRoundRect(new RectF(x0 + 0.12f * w, y0 + 0.015f * h, x0 + 0.88f * w, y0 + 0.035f * h), 0.01f * h, 0.01f * h, p);
        p.setColor(TAIL);
        cv.drawRoundRect(new RectF(x0 + 0.12f * w, y0 + 0.965f * h, x0 + 0.88f * w, y0 + 0.985f * h), 0.01f * h, 0.01f * h, p);
    }
}
