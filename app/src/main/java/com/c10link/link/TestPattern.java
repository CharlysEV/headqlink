package com.c10link.link;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Surface;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Patrón de prueba dibujado con Canvas directamente sobre la Surface del encoder.
 * No usa ningún display, así que no depende de que la pantalla del móvil esté encendida.
 * Muestra: barra móvil (fluidez), contador de frames, fps real, rejilla y los toques recibidos.
 */
final class TestPattern extends Thread {
    private static final int TRAIL = 60;

    private final Surface surface;
    private final int w;
    private final int h;
    private final int fps;
    private final String info;
    private volatile boolean running = true;
    private final ArrayDeque<float[]> touches = new ArrayDeque<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private volatile String status = "";
    final Jitter renderJitter;

    TestPattern(Surface surface, int w, int h, int fps, String info) {
        super("test-pattern");
        this.surface = surface;
        this.w = w;
        this.h = h;
        this.fps = fps;
        this.info = info;
        this.renderJitter = new Jitter("render", fps);
    }

    void addTouch(int action, float x, float y) {
        synchronized (touches) {
            touches.addLast(new float[]{x, y, action});
            while (touches.size() > TRAIL) touches.removeFirst();
        }
    }

    void setStatus(String s) {
        status = s;
    }

    @Override
    public void run() {
        long frameNs = 1_000_000_000L / fps;
        long next = System.nanoTime();
        long frame = 0;
        long fpsWindowStart = System.nanoTime();
        int fpsCount = 0;
        float measuredFps = 0;
        while (running) {
            Canvas c;
            try {
                c = surface.lockHardwareCanvas();
            } catch (Exception e) {
                L.e("lockHardwareCanvas", e);
                break;
            }
            try {
                draw(c, frame, measuredFps);
            } finally {
                surface.unlockCanvasAndPost(c);
                renderJitter.tick();
            }
            frame++;
            fpsCount++;
            long now = System.nanoTime();
            if (now - fpsWindowStart >= 1_000_000_000L) {
                measuredFps = fpsCount * 1e9f / (now - fpsWindowStart);
                fpsCount = 0;
                fpsWindowStart = now;
            }
            next += frameNs;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000, (int) (sleep % 1_000_000));
                } catch (InterruptedException e) {
                    break;
                }
            } else if (sleep < -frameNs * 5) {
                next = System.nanoTime();
            }
        }
    }

    private void draw(Canvas c, long frame, float measuredFps) {
        c.drawColor(Color.rgb(16, 20, 28));

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(Color.rgb(50, 60, 75));
        for (int x = 0; x < w; x += 100) c.drawLine(x, 0, x, h, paint);
        for (int y = 0; y < h; y += 100) c.drawLine(0, y, w, y, paint);
        paint.setColor(Color.rgb(255, 80, 80));
        paint.setStrokeWidth(4);
        c.drawRect(2, 2, w - 2, h - 2, paint);

        int[] bars = {Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE};
        paint.setStyle(Paint.Style.FILL);
        float bw = w / (float) bars.length;
        for (int i = 0; i < bars.length; i++) {
            paint.setColor(bars[i]);
            c.drawRect(i * bw, h - 60, (i + 1) * bw, h, paint);
        }

        float period = fps * 2f;
        float bx = (frame % (long) period) / period * w;
        paint.setColor(Color.rgb(255, 170, 0));
        c.drawRect(bx - 20, 0, bx + 20, h - 60, paint);

        paint.setColor(Color.WHITE);
        paint.setTextSize(64);
        c.drawText("C10Link proto", 60, 110, paint);
        paint.setTextSize(36);
        c.drawText(info, 60, 170, paint);
        c.drawText(String.format(Locale.US, "frame %d   render %.1f fps", frame, measuredFps), 60, 225, paint);
        // Reloj grande para medir la latencia de cristal a cristal: se graba junto al reloj de la
        // pantalla "Medir latencia" del móvil y la diferencia entre ambos es la latencia total.
        paint.setTextSize(150);
        paint.setColor(Color.WHITE);
        paint.setFakeBoldText(true);
        String now = clock.format(new Date());
        c.drawText(now, (w - paint.measureText(now)) / 2f, h * 0.62f, paint);
        paint.setFakeBoldText(false);
        paint.setColor(Color.rgb(140, 220, 255));
        c.drawText(status, 60, 280, paint);

        synchronized (touches) {
            int i = 0;
            int n = touches.size();
            for (float[] t : touches) {
                i++;
                int alpha = 60 + 195 * i / Math.max(1, n);
                int col = t[2] == 1 ? Color.GREEN : t[2] == 2 ? Color.RED : Color.YELLOW;
                paint.setColor(col);
                paint.setAlpha(alpha);
                c.drawCircle(t[0], t[1], i == n ? 36 : 14, paint);
            }
            paint.setAlpha(255);
            float[] last = touches.peekLast();
            if (last != null) {
                paint.setColor(Color.WHITE);
                c.drawText(String.format(Locale.US, "touch %.1f, %.1f  act=%d", last[0], last[1], (int) last[2]), 60, 335, paint);
            }
        }
    }

    void shutdown() {
        running = false;
        interrupt();
        try {
            join(500);
        } catch (InterruptedException ignored) {
        }
    }
}
