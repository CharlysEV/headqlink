package com.c10link.link;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode;
import com.andrerinas.openheadunit.aap.protocol.messages.TouchEvent;
import com.andrerinas.openheadunit.aap.protocol.proto.Input;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.HeadlessDriver;
import com.andrerinas.openheadunit.decoder.video.VideoTap;
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig;
import com.andrerinas.openheadunit.utils.Settings;

import java.util.Collections;

import kotlin.Triple;

/**
 * Modo Android Auto: reenvía al coche, sin recodificar, el H.264 que Android Auto entrega a
 * Open Headunit (VideoTap), y devuelve a AA los toques del coche.
 *
 * Coordenadas: el coche informa los toques en su espacio de pantalla (CAR_INFO, 1920x882) y
 * mostramos el frame completo de AA (negociado, p. ej. 1920x1080). Se escala de uno a otro;
 * si el coche recortara en vez de escalar, habrá que ajustar aquí (se registra cada toque).
 */
final class AaPassthroughSource implements VideoSource {
    /**
     * Hueco entre soltar y recuperar el foco de vídeo para forzar un IDR. Con 300 ms la imagen se
     * paraba ~0,9 s en total (300 + ~600 ms que tarda AA en volver a codificar).
     */
    private static final long FOCUS_CYCLE_GAP_MS = 100;

    private final Context ctx;
    private final int dpi;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int carW = 1920;
    private int carH = 882;
    private int logTouches = 20;
    private int logCrops = 3;
    private int targetFps = 30;
    private final boolean brake;
    /** Modo "último frame": AA se decodifica aquí y GlFrameRelay alimenta nuestro encoder. */
    private final boolean reencode;
    private GlFrameRelay relay;
    /** Modo ampliado: panel propio a la derecha y pantallas nuestras (CarUi). */
    private final boolean extended;
    /** Ancho del panel propio en la pantalla del coche (modo ampliado). */
    static final int PANEL_W = 320;
    private CarUi carUi;
    /** Gesto en curso dirigido a nuestra interfaz (se decide en el DOWN). */
    private boolean touchToUi;
    private Surface relayInput;
    private volatile GlFrameRelay.Gate gate;
    private final int brakeWindow;
    /** Retardo base (µs) entre la marca de tiempo de AA y nuestra recepción; el exceso es cola en AA. */
    private long aaBaseUs = Long.MAX_VALUE;
    private int logTimestamps = 3;
    /** Superficie invisible para el decodificador de Open Headunit (no se ve nada en el móvil). */
    private ImageReader offscreen;
    private android.os.HandlerThread offscreenThread;

    AaPassthroughSource(Context ctx, int dpi) {
        this(ctx, dpi, false);
    }

    AaPassthroughSource(Context ctx, int dpi, boolean reencode) {
        this(ctx, dpi, reencode, false);
    }

    /** extended: modo "Android Auto ampliado" (siempre con "último frame"). */
    AaPassthroughSource(Context ctx, int dpi, boolean reencode, boolean extended) {
        this.ctx = ctx.getApplicationContext();
        this.dpi = dpi;
        this.extended = extended;
        this.reencode = reencode || extended;
        Config cfg = new Config(ctx);
        brake = cfg.aaBrake();
        brakeWindow = cfg.aaWindow();
    }

    private CommManager comm() {
        return App.Companion.provide(ctx).getCommManager();
    }

    @Override
    public boolean usesEncoder() {
        return reencode;
    }

    @Override
    public void setLinkGate(GlFrameRelay.Gate g) {
        gate = g;
        GlFrameRelay r = relay;
        if (r != null) r.setGate(g);
    }

    /**
     * Tamaño que ve el coche. AA emite un frame estándar (1920x1080) con su interfaz en la franja
     * central del tamaño del coche; reescribimos el recorte del SPS para que el coche decodifique
     * exactamente esa franja (1920x882), que llena su pantalla.
     */
    @Override
    public int[] passthroughSize() {
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int w = sc.getNegotiatedWidth();
        int h = sc.getNegotiatedHeight();
        return cropActive(w, h) ? new int[]{carW, carH} : new int[]{w, h};
    }

    private boolean cropActive(int negW, int negH) {
        return negW >= carW && negH >= carH && (negW != carW || negH != carH);
    }

    /** Modo día/noche del coche → sensor de noche de Android Auto. */
    @Override
    public void onCarDarkMode(boolean dark) {
        Settings settings = App.Companion.provide(ctx).getSettings();
        settings.setNightMode(dark ? Settings.NightMode.NIGHT : Settings.NightMode.DAY);
        L.i("AA: modo " + (dark ? "noche" : "día") + " del coche");
        if (comm().isConnected()) {
            ctx.startForegroundService(new Intent(ctx, AapService.class).setAction(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE));
        }
    }

    @Override
    public void setTargetFps(int fps) {
        if (fps > 0) targetFps = fps;
    }

    @Override
    public void setCarSize(int width, int height) {
        if (width > 0 && height > 0) {
            carW = width;
            carH = height;
        }
    }

    @Override
    public void startPassthrough(EncodedSink sink) {
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        L.i("AA: vídeo negociado " + sc.getNegotiatedWidth() + "x" + sc.getNegotiatedHeight() + " márgenes "
                + sc.getWidthMargin() + "x" + sc.getHeightMargin() + "; abre Android Auto (Self-Mode) si no está conectado");
        VideoTap.setHeadless(true);
        // La ventana solo llega a AA al configurar su canal de vídeo (al conectar AA).
        VideoTap.setVideoWindow(brake ? brakeWindow : 0);
        L.i("AA: freno " + (brake ? "SÍ (ventana " + brakeWindow + ")" : "no"));
        PerfTrace.event("aa_brake", brake ? brakeWindow : 0);
        LinkState.setSource(LinkState.Level.BUSY, "Arrancando Android Auto");
        boolean[] first = {true};
        VideoTap.setSink((buf, off, len) -> {
            if (first[0]) {
                first[0] = false;
                int[] sz = passthroughSize();
                LinkState.setSource(LinkState.Level.OK, "En marcha · " + sz[0] + "×" + sz[1]);
                announceTopAlignedMargins();
            }
            noteAaTimestamp();
            int nw = HeadUnitScreenConfig.INSTANCE.getNegotiatedWidth();
            int nh = HeadUnitScreenConfig.INSTANCE.getNegotiatedHeight();
            if (cropActive(nw, nh)) {
                // Anclado arriba: AA dibuja su interfaz en las filas de arriba (UpdateUiConfig con todo
                // el margen abajo), así da igual que el coche respete o no el desplazamiento del recorte.
                byte[] cropped = H264SpsCrop.rewrite(buf, off, len, carW, carH, true);
                if (cropped != null) {
                    if (logCrops > 0) {
                        logCrops--;
                        L.i("AA: SPS recortado " + nw + "x" + nh + " -> " + carW + "x" + carH + " · " + H264SpsCrop.lastInfo);
                        L.i("AA: unidad original " + L.hex(buf, off, Math.min(len, 48)));
                        L.i("AA: unidad enviada  " + L.hex(cropped, 0, Math.min(cropped.length, 48)));
                    }
                    sink.onAccessUnit(cropped, 0, cropped.length);
                    return;
                }
            }
            sink.onAccessUnit(buf, off, len);
        });
        attachOffscreenSurface();
        HeadlessDriver.start(ctx);
        ensureAaConnected();
        requestKeyFrame();
    }

    /**
     * Cola dentro de AA: su marca de tiempo (µs) frente a nuestro reloj. El reloj de AA puede no ser
     * el nuestro, así que se mide contra el mínimo visto: si crece, AA tiene frames esperando
     * (no respeta el freno, o lo acumula). Traza: aa_q, ms por encima del mínimo.
     */
    private void noteAaTimestamp() {
        long ts = VideoTap.getFrameTimestampUs();
        if (ts <= 0) return;
        long nowUs = System.nanoTime() / 1000;
        long d = nowUs - ts;
        if (logTimestamps > 0) {
            logTimestamps--;
            L.i("AA: marca de tiempo " + ts + " µs, diferencia con System.nanoTime " + d / 1000 + " ms");
        }
        if (d < aaBaseUs) aaBaseUs = d;
        PerfTrace.event("aa_q", (d - aaBaseUs) / 1000);
    }

    /**
     * Sin intervención del usuario: conexión WiFi de Open Headunit en manual (su modo "AA nativo"
     * crearía su propio grupo WiFi Direct y nos echaría del grupo del coche) y, si AA no está
     * conectado, arranque del Self-Mode (que a su vez arranca el servidor de AA si hace falta).
     */
    private void ensureAaConnected() {
        configureAa();
        if (comm().isConnected()) {
            L.i("AA: ya conectado");
            return;
        }
        L.i("AA: lanzando Self-Mode");
        Intent i = new Intent(ctx, AapService.class).setAction(AapService.ACTION_START_SELF_MODE);
        ctx.startForegroundService(i);
    }

    /** Geometría y ajustes de Open Headunit para el coche (antes de conectar AA). */
    private void configureAa() {
        Settings settings = App.Companion.provide(ctx).getSettings();
        settings.setWifiConnectionMode(WifiLauncherMode.MANUAL);
        // Geometría del coche en lugar de la del móvil. CONTAIN: píxeles cuadrados y márgenes, así AA
        // pone su interfaz en la franja central de 1920x882 de un frame 1920x1080. El coche no estira
        // el frame (lo encaja con bandas), así que le enviamos solo esa franja recortando el SPS.
        settings.setVideoFitMode(Settings.VideoFitMode.CONTAIN);
        // AA a los fps que pide el coche (30): a 60 fps y 14 Mbps el coche acumula frames en los
        // movimientos rápidos (medido en la traza de rendimiento del 2026-10-03).
        settings.setFpsLimit(targetFps <= 30 ? 30 : 60);
        HeadUnitScreenConfig.setExternalCanvas(new Size(carW, carH));
        HeadUnitScreenConfig.setExternalDpi(dpi);
        L.i("AA: geometría del coche " + carW + "x" + carH + " dpi=" + dpi + " (modo CONTAIN + recorte SPS), "
                + (targetFps <= 30 ? 30 : 60) + " fps");
    }

    /**
     * Márgenes por lado: todo el margen abajo/derecha, para que AA ponga su interfaz en la esquina
     * superior izquierda del frame (las filas/columnas que muestra el coche).
     */
    private void announceTopAlignedMargins() {
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int right = Math.max(0, sc.getNegotiatedWidth() - aaRegionW());
        int bottom = Math.max(0, sc.getNegotiatedHeight() - carH);
        if (right == 0 && bottom == 0) return;
        main.post(() -> {
            L.i("AA: márgenes por lado L0 T0 R" + right + " B" + bottom + " (interfaz arriba)");
            comm().sendUpdateUiConfigRequest(0, 0, right, bottom);
        });
    }

    @Override
    public void requestKeyFrame() {
        main.post(() -> {
            CommManager cm = comm();
            PerfTrace.event("aa_idr", 0);
            if (cm.releaseVideoFocusForKeyframe()) {
                main.postDelayed(cm::retakeVideoFocusForKeyframe, FOCUS_CYCLE_GAP_MS);
                L.i("AA: ciclo de foco de vídeo para forzar IDR");
            } else {
                L.i("AA: no se pudo pedir IDR (AA no conectado o ciclo en curso)");
            }
        });
    }

    /**
     * Modo "último frame": AA pinta (decodificador de Open Headunit) en la SurfaceTexture de
     * GlFrameRelay, que copia la franja de la interfaz a la Surface de nuestro encoder cuando el
     * enlace tiene sitio. El IDR que pide el coche lo da nuestro encoder al instante.
     */
    @Override
    public void start(Surface surface, int width, int height, int fps, String info) {
        if (!reencode) return;
        configureAa();
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int nw = sc.getNegotiatedWidth();
        int nh = sc.getNegotiatedHeight();
        L.i("AA: modo último frame · AA " + nw + "x" + nh + " -> encoder " + width + "x" + height + " · " + info);
        VideoTap.setHeadless(true);
        VideoTap.setVideoWindow(0);
        LinkState.setSource(LinkState.Level.BUSY, "Arrancando Android Auto");
        boolean[] first = {true};
        VideoTap.setSink((buf, off, len) -> {
            noteAaTimestamp();
            GlFrameRelay r = relay;
            if (r != null) {
                HeadUnitScreenConfig c = HeadUnitScreenConfig.INSTANCE;
                r.setSourceSize(c.getNegotiatedWidth(), c.getNegotiatedHeight());
            }
            if (first[0]) {
                first[0] = false;
                LinkState.setSource(LinkState.Level.OK, "En marcha · " + width + "×" + height + " (último frame)");
                announceTopAlignedMargins();
            }
        });
        relay = new GlFrameRelay(surface, width, height, nw, nh, fps, aaOffsetX(), aaRegionW(), extended);
        relay.setGate(gate);
        relayInput = relay.start();
        if (extended) {
            GlFrameRelay r = relay;
            carUi = new CarUi(ctx, width, height, PANEL_W, dpi, r::setAaVisible);
            carUi.start(r.overlayInput());
        }
        App.Companion.provide(ctx).getVideoDecoder().setSurface(relayInput);
        HeadlessDriver.start(ctx);
        ensureAaConnected();
        requestKeyFrame();
    }

    /** Ancho de la pantalla del coche que ocupa AA (en modo ampliado, sin el panel propio). */
    private int aaRegionW() {
        return extended ? carW - PANEL_W : carW;
    }

    /** x de la zona de AA en la pantalla del coche (en modo ampliado el panel va a la izquierda). */
    private int aaOffsetX() {
        return extended ? PANEL_W : 0;
    }

    @Override
    public void touch(int action, float x, float y) {
        CarUi ui = carUi;
        if (ui != null) {
            // El gesto entero va a quien recibió el DOWN: panel/pantalla propia o AA.
            if (action == 1) touchToUi = ui.ownScreenActive() || x < aaOffsetX();
            if (touchToUi) {
                ui.touch(action, x, y);
                return;
            }
            x -= aaOffsetX();
        }
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int w = sc.getNegotiatedWidth();
        int h = sc.getNegotiatedHeight();
        // AA dibuja su interfaz píxel a píxel en la esquina superior izquierda del frame, en una
        // zona de aaRegionW x carH (márgenes de announceTopAlignedMargins), así que el toque va 1:1.
        // No se escala por el margen negociado (getWidthMargin): en modo ampliado el margen derecho
        // real es mayor y escalar desplazaba los toques hasta un 20 % (2026-10-03).
        int ax = Math.round(Math.max(0, Math.min(x, Math.min(aaRegionW(), w) - 1)));
        int ay = Math.round(Math.max(0, Math.min(y, Math.min(carH, h) - 1)));
        Input.TouchEvent.PointerAction pa;
        switch (action) {
            case 1:
                pa = Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN;
                break;
            case 2:
                pa = Input.TouchEvent.PointerAction.TOUCH_ACTION_UP;
                break;
            default:
                pa = Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE;
        }
        if (logTouches > 0) {
            logTouches--;
            L.i("AA touch coche " + x + "," + y + " (" + carW + "x" + carH + ") -> AA " + ax + "," + ay + " (" + w + "x" + h + ")");
        }
        PerfTrace.touch("aa_touch", action, ax, ay);
        comm().send(new TouchEvent(SystemClock.elapsedRealtime(), pa, 0,
                Collections.singletonList(new Triple<>(0, ax, ay))));
    }

    @Override
    public void setStatus(String status) {
    }

    @Override
    public String takeJitterSummary() {
        return null;
    }

    /**
     * El decodificador de Open Headunit sigue funcionando (su recuperación de fotogramas clave se
     * basa en lo que decodifica), pero pinta en un ImageReader cuyas imágenes se descartan.
     */
    private void attachOffscreenSurface() {
        offscreen = ImageReader.newInstance(1920, 1080, ImageFormat.PRIVATE, 4);
        // Hilo propio: si el hilo principal se ocupa, el decodificador no debe quedarse sin buffers
        // (llenaría su cola y Open Headunit frenaría todo el flujo de AA: "Feed queue full").
        offscreenThread = new android.os.HandlerThread("aa-offscreen");
        offscreenThread.start();
        offscreen.setOnImageAvailableListener(r -> {
            Image img = r.acquireLatestImage();
            if (img != null) img.close();
        }, new Handler(offscreenThread.getLooper()));
        App.Companion.provide(ctx).getVideoDecoder().setSurface(offscreen.getSurface());
        L.i("AA: decodificador local en superficie invisible (sin vista en el móvil)");
    }

    @Override
    public void stop() {
        HeadlessDriver.stop();
        App.Companion.provide(ctx).getSettings().setNightMode(Settings.NightMode.AUTO);
        LinkState.setSource(LinkState.Level.IDLE, "");
        VideoTap.setSink(null);
        VideoTap.setHeadless(false);
        HeadUnitScreenConfig.setExternalCanvas(null);
        HeadUnitScreenConfig.setExternalDpi(0);
        if (offscreen != null) {
            App.Companion.provide(ctx).getVideoDecoder().stopIfCurrentSurface(offscreen.getSurface(), "c10link stop");
            offscreen.close();
            offscreen = null;
        }
        if (offscreenThread != null) {
            offscreenThread.quitSafely();
            offscreenThread = null;
        }
        if (carUi != null) {
            carUi.stop();
            carUi = null;
        }
        if (relay != null) {
            App.Companion.provide(ctx).getVideoDecoder().stopIfCurrentSurface(relayInput, "c10link stop");
            relay.release();
            relay = null;
            relayInput = null;
        }
    }
}
