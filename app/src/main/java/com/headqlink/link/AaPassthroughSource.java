package com.headqlink.link;

import com.andrerinas.openheadunit.R;

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
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent;
import com.andrerinas.openheadunit.aap.protocol.proto.Input;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.video.HeadlessDriver;
import com.andrerinas.openheadunit.decoder.video.VideoTap;
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig;
import com.andrerinas.openheadunit.utils.Settings;

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
    /** Arranques de Android Auto (Self-Mode) desde que arrancó el proceso; el resumen del viaje resta los de antes. */
    static final java.util.concurrent.atomic.AtomicInteger AA_LAUNCHES = new java.util.concurrent.atomic.AtomicInteger();

    private final Context ctx;
    private final int dpi;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Pantalla del coche (CAR_INFO): en ella llegan los toques y se diseña la interfaz propia. */
    private int carW = 1920;
    private int carH = 882;
    /** Vídeo que se envía al coche: igual que la pantalla o 720p con su proporción (perfiles). */
    private int videoW = 1920;
    private int videoH = 882;
    /** Ancho actual del panel propio en píxeles del coche (modo ampliado; menor si está oculto). */
    private volatile int panelCarW = PANEL_W;
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
    private SplashUi splashUi;
    /** Gesto en curso dirigido a nuestra interfaz (se decide en el DOWN). */
    private boolean touchToUi;
    private Surface relayInput;
    private volatile GlFrameRelay.Gate gate;
    private final int brakeWindow;
    private final boolean fixedRate;
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
        fixedRate = cfg.aaFixedRate();
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
        return cropActive(w, h) ? new int[]{videoW, videoH} : new int[]{w, h};
    }

    private boolean cropActive(int negW, int negH) {
        return negW >= videoW && negH >= videoH && (negW != videoW || negH != videoH);
    }

    @Override
    public void setVideoSize(int width, int height) {
        if (width > 0 && height > 0) {
            videoW = width;
            videoH = height;
        }
    }

    /** Escala de la pantalla del coche al vídeo (1 a resolución completa, 2/3 a 720p). */
    private float scale() {
        return videoW / (float) carW;
    }

    /** x de la zona de AA en la pantalla del coche (a la derecha del panel en el modo ampliado). */
    private int aaCarX() {
        return extended ? panelCarW : 0;
    }

    /** x y ancho de la zona de AA en el vídeo (y en el frame de AA, que va a la misma escala). */
    private int aaVideoX() {
        return Math.round(aaCarX() * scale());
    }

    private int aaVideoW() {
        return videoW - aaVideoX();
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

    /**
     * "Último frame": tope de fps del relay GL en marcha (adaptación térmica). Por debajo de los de la sesión (AA sigue
     * a los suyos), en rejilla para que salgan a intervalos iguales; con los de la sesión, como al empezar.
     */
    @Override
    public void setMaxFps(int fps) {
        GlFrameRelay r = relay;
        if (r == null || fps <= 0) return;
        r.setMaxFps(fps, fixedRate || fps < targetFps);
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
        LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_starting_auto));
        boolean[] first = {true};
        VideoTap.setSink((buf, off, len) -> {
            if (first[0]) {
                first[0] = false;
                int[] sz = passthroughSize();
                LinkState.setSource(LinkState.Level.OK, Str.get(R.string.hql_running_size, sz[0], sz[1]));
                announceTopAlignedMargins();
            }
            noteAaTimestamp();
            int nw = HeadUnitScreenConfig.INSTANCE.getNegotiatedWidth();
            int nh = HeadUnitScreenConfig.INSTANCE.getNegotiatedHeight();
            if (cropActive(nw, nh)) {
                // Anclado arriba: AA dibuja su interfaz en las filas de arriba (UpdateUiConfig con todo
                // el margen abajo), así da igual que el coche respete o no el desplazamiento del recorte.
                byte[] cropped = H264SpsCrop.rewrite(buf, off, len, videoW, videoH, true);
                if (cropped != null) {
                    if (logCrops > 0) {
                        logCrops--;
                        L.i("AA: SPS recortado " + nw + "x" + nh + " -> " + videoW + "x" + videoH + " · " + H264SpsCrop.lastInfo);
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
        requestKeyFrameAfterStart();
    }

    /**
     * IDR del vídeo recién creado (ciclo de foco). Si AA venía de la pausa (AaPark) y el ciclo no pudo empezar, se le
     * devuelve el foco directamente: con el foco en nativo no mandaría imagen. Ya hay superficie que la reciba.
     */
    private void requestKeyFrameAfterStart() {
        requestKeyFrame(started -> {
            if (AaPark.takeFocusOwed() && !started && comm().isConnected()) {
                L.life("Android Auto vuelve de la pausa: le devuelvo el foco de vídeo");
                comm().send(new VideoFocusEvent(true, true));
            }
        });
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
     * Con el arranque manual del servidor, el Self-Mode lo lanza AaServerManual: cuenta el intento como servido solo
     * si AA contesta y, si no, avisa y lo reintenta cada 5 s (sin sondear nunca el puerto).
     */
    private void ensureAaConnected() {
        configureAa();
        boolean manual = AaServerManual.applies(ctx);
        if (comm().isConnected()) {
            L.i("AA: ya conectado");
            // Arranque manual: se vigila que siga conectado mientras la sesión lo use (si cae, intento y aviso).
            if (manual) AaServerManual.sessionNeedsAa(ctx, "la sesión con el coche usa Android Auto");
            return;
        }
        if (manual) {
            AaServerManual.sessionNeedsAa(ctx, "la sesión con el coche necesita Android Auto");
            return;
        }
        L.i("AA: lanzando Self-Mode");
        AA_LAUNCHES.incrementAndGet();
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
        // AA a 30 o 60 fps según el perfil: en reenvío directo, a 60 fps y bitrate alto el coche
        // acumula frames en los movimientos rápidos.
        settings.setFpsLimit(targetFps <= 30 ? 30 : 60);
        // Decodificador en modo baja latencia (KEY_LOW_LATENCY y extensiones) con el ajuste encendido.
        settings.setDebugVideoLowLatency(LowLatency.enabled);
        // Resolución de AA según el perfil: 720p o 1080p (el lienzo es el tamaño del vídeo, así AA
        // pone su interfaz en la franja que enviamos). Con AA ya conectado, se aplica al reconectarlo.
        boolean hd720 = videoW <= 1280;
        int resId = hd720 ? Settings.Resolution._1280x720.getId() : Settings.Resolution._1920x1080.getId();
        if (settings.getResolutionId() != resId) {
            settings.setResolutionId(resId);
            if (!comm().isConnected()) HeadUnitScreenConfig.INSTANCE.unlockResolution();
        }
        HeadUnitScreenConfig.setExternalCanvas(new Size(videoW, videoH));
        // Los dpi van con la escala, para que la interfaz de AA tenga el mismo tamaño aparente a 720p.
        int effDpi = Math.max(80, Math.round(dpi * scale()));
        HeadUnitScreenConfig.setExternalDpi(effDpi);
        L.i("AA: pantalla del coche " + carW + "x" + carH + " · vídeo " + videoW + "x" + videoH + " (" + (hd720 ? "720p" : "1080p")
                + ") dpi=" + effDpi + " (modo CONTAIN + recorte SPS), " + (targetFps <= 30 ? 30 : 60) + " fps");
    }

    /**
     * Márgenes por lado: todo el margen abajo/derecha, para que AA ponga su interfaz en la esquina
     * superior izquierda del frame (las filas/columnas que muestra el coche).
     */
    private void announceTopAlignedMargins() {
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int right = Math.max(0, sc.getNegotiatedWidth() - aaVideoW());
        int bottom = Math.max(0, sc.getNegotiatedHeight() - videoH);
        if (right == 0 && bottom == 0) return;
        main.post(() -> {
            L.i("AA: márgenes por lado L0 T0 R" + right + " B" + bottom + " (interfaz arriba)");
            comm().sendUpdateUiConfigRequest(0, 0, right, bottom);
        });
    }

    @Override
    public void requestKeyFrame() {
        requestKeyFrame(null);
    }

    /** Ciclo de foco de vídeo de AA para forzar un IDR; cb (hilo principal) dice si empezó. */
    @Override
    public void requestKeyFrame(KeyframeCallback cb) {
        main.post(() -> {
            CommManager cm = comm();
            PerfTrace.event("aa_idr", 0);
            boolean started = cm.releaseVideoFocusForKeyframe();
            if (started) {
                main.postDelayed(cm::retakeVideoFocusForKeyframe, FOCUS_CYCLE_GAP_MS);
                L.i("AA: ciclo de foco de vídeo para forzar IDR");
            } else {
                L.i("AA: no se pudo pedir IDR (AA no conectado o ciclo en curso)");
            }
            if (cb != null) cb.onResult(started);
        });
    }

    /** Modo «último frame»: vuelve a dibujar el último frame de AA (el encoder da el IDR pedido al momento). */
    @Override
    public void redraw() {
        GlFrameRelay r = relay;
        if (r != null) r.redraw();
    }

    /** Vuelve el coche sin haber parado el vídeo: si AA se desconectó mientras tanto, se relanza. */
    @Override
    public void onReattached() {
        if (!comm().isConnected()) {
            L.w("AA: no está conectado al volver el coche; lo relanzo");
            ensureAaConnected();
        } else if (AaPark.takeFocusOwed()) {
            // Volvía de la pausa con este mismo vídeo vivo: foco de vuelta (el IDR lo pide la sesión al enganchar).
            L.life("Android Auto vuelve de la pausa: le devuelvo el foco de vídeo");
            comm().send(new VideoFocusEvent(true, true));
        }
        redraw();
    }

    /** AA conectado o conectándose (para saber si el vídeo vivo sigue sirviendo). */
    boolean aaAlive() {
        CommManager cm = comm();
        return cm.isConnected() || cm.getConnectionState().getValue() instanceof CommManager.ConnectionState.Connecting;
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
        L.i("AA: modo último frame · AA " + nw + "x" + nh + " -> encoder " + width + "x" + height + " · " + info
                + (fixedRate ? " · cadencia fija " + fps + " fps" : ""));
        VideoTap.setHeadless(true);
        VideoTap.setVideoWindow(0);
        LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_starting_auto));
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
                LinkState.setSource(LinkState.Level.OK, Str.get(R.string.hql_running_size, width, height));
                announceTopAlignedMargins();
            }
        });
        // Siempre con capa: en el modo ampliado es nuestra interfaz; sin panel, solo la animación de carga.
        relay = new GlFrameRelay(surface, width, height, nw, nh, fps, aaVideoX(), aaVideoW(), true);
        relay.setGate(gate);
        relay.setFixedRate(fixedRate);
        relay.setSplashOnly(!extended);
        // La capa se diseña a la resolución de la pantalla del coche; el relay la escala al vídeo.
        relay.setOverlaySize(carW, carH);
        relay.setBackgroundColor(new Config(ctx).panelColor());
        relay.setOnFirstAaFrame(() -> {
            CarUi ui = carUi;
            if (ui != null) ui.onAaReady();
            SplashUi sp = splashUi;
            if (sp != null) {
                sp.stop();
                splashUi = null;
            }
        });
        relayInput = relay.start();
        if (!extended) {
            splashUi = new SplashUi(ctx, carW, carH, dpi);
            splashUi.start(relay.overlayInput());
        }
        if (extended) {
            GlFrameRelay r = relay;
            carUi = new CarUi(ctx, carW, carH, PANEL_W, dpi, new CarUi.Listener() {
                @Override
                public void onAaVisible(boolean visible) {
                    r.setAaVisible(visible);
                }

                @Override
                public void onPanelWidth(int w) {
                    setPanelWidth(w);
                }

                @Override
                public void onPanelColor(int color) {
                    r.setBackgroundColor(color);
                }
            });
            carUi.setRefreshRate(fps);
            carUi.start(r.overlayInput());
        }
        App.Companion.provide(ctx).getVideoDecoder().setSurface(relayInput);
        HeadlessDriver.start(ctx);
        ensureAaConnected();
        requestKeyFrameAfterStart();
    }

    /**
     * El panel propio cambia de ancho (se oculta solo o vuelve): AA se recoloca sobre la marcha con
     * nuevos márgenes (UpdateUiConfigRequest) y el relay mueve su zona; los toques usan el nuevo origen.
     */
    private void setPanelWidth(int w) {
        if (w == panelCarW) return;
        panelCarW = w;
        GlFrameRelay r = relay;
        if (r != null) r.setAaRegion(aaVideoX(), aaVideoW());
        announceTopAlignedMargins();
        L.i("AA: panel " + w + " px -> AA en x=" + aaVideoX() + " ancho " + aaVideoW() + " (vídeo)");
    }

    @Override
    public void touch(int action, float x, float y) {
        Proto.Finger f = new Proto.Finger();
        f.id = 0;
        f.action = action;
        f.x = x;
        f.y = y;
        touchMulti(action == 1 ? 0 : action == 2 ? 1 : 2, new Proto.Finger[]{f});
    }

    /** Dedos ahora mismo en contacto con AA (id del coche -> x, y en AA), en orden de llegada. */
    private final java.util.LinkedHashMap<Integer, int[]> aaDown = new java.util.LinkedHashMap<>();

    /**
     * Multitáctil (el coche manda hasta 3 dedos). La acción de AA se deduce de la de cada dedo (1
     * down, 2 up, 3 move), sin depender del formato de la acción global: el primer dedo es DOWN, los
     * siguientes POINTER_DOWN con su índice, y al soltar POINTER_UP hasta el último (UP). A AA van
     * siempre todos los dedos en contacto (con su última posición si el coche no los repite). El gesto
     * entero va a quien recibió el primer DOWN; nuestra interfaz solo recibe el primer dedo.
     */
    @Override
    public void touchMulti(int globalAction, Proto.Finger[] fingers) {
        Proto.Finger first = null;
        int count = 0;
        for (Proto.Finger f : fingers) {
            if (f == null) continue;
            if (first == null) first = f;
            count++;
        }
        if (first == null) return;
        // Un DOWN de un solo dedo empieza gesto nuevo (por si se perdió el UP del anterior).
        if (count == 1 && first.action == 1) aaDown.clear();
        CarUi ui = carUi;
        if (ui != null) {
            if (aaDown.isEmpty() && first.action == 1) {
                touchToUi = ui.ownScreenActive() || first.x < aaCarX();
                ui.noteTouch(touchToUi);
            }
            if (touchToUi) {
                if (first.id == 0 || count == 1) ui.touch(first.action, first.x, first.y);
                return;
            }
        }
        HeadUnitScreenConfig sc = HeadUnitScreenConfig.INSTANCE;
        int w = sc.getNegotiatedWidth();
        int h = sc.getNegotiatedHeight();
        // AA dibuja su interfaz píxel a píxel en la esquina superior izquierda del frame, en una zona
        // de aaVideoW x videoH (márgenes de announceTopAlignedMargins): el toque se pasa al vídeo
        // (escala del perfil) sin más. No se escala por el margen negociado: en modo extendido
        // desplazaría los toques (el margen derecho real es mayor).
        float s = scale();
        int ox = aaCarX();
        int maxX = Math.min(aaVideoW(), w) - 1;
        int maxY = Math.min(videoH, h) - 1;
        int changed = -1;
        int changedAction = 3;
        for (Proto.Finger f : fingers) {
            if (f == null) continue;
            int ax = Math.round(Math.max(0, Math.min((f.x - ox) * s, maxX)));
            int ay = Math.round(Math.max(0, Math.min(f.y * s, maxY)));
            if (f.action == 1 || aaDown.containsKey(f.id)) aaDown.put(f.id, new int[]{ax, ay});
            if (f.action != 3 && changed < 0) {
                changed = f.id;
                changedAction = f.action;
            }
        }
        if (aaDown.isEmpty()) return;
        java.util.ArrayList<Triple<Integer, Integer, Integer>> pts = new java.util.ArrayList<>(aaDown.size());
        int index = 0;
        for (java.util.Map.Entry<Integer, int[]> e : aaDown.entrySet()) {
            if (e.getKey() == changed) index = pts.size();
            pts.add(new Triple<>(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        Input.TouchEvent.PointerAction pa;
        if (changedAction == 1) {
            pa = pts.size() <= 1 ? Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                    : Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN;
        } else if (changedAction == 2) {
            pa = pts.size() <= 1 ? Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                    : Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP;
            aaDown.remove(changed);
        } else {
            pa = Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE;
        }
        Triple<Integer, Integer, Integer> p0 = pts.get(0);
        if (logTouches > 0 && (changedAction != 3 || pts.size() > 1)) {
            logTouches--;
            L.i("AA touch coche " + first.x + "," + first.y + " (" + carW + "x" + carH + ", global 0x"
                    + Integer.toHexString(globalAction) + ", " + pts.size() + " dedos) -> AA " + pa + " idx " + index + " "
                    + p0.getSecond() + "," + p0.getThird() + " (" + w + "x" + h + ")");
        }
        PerfTrace.touch("aa_touch", changedAction, p0.getSecond(), p0.getThird());
        comm().send(new TouchEvent(SystemClock.elapsedRealtime(), pa, index, pts));
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
        VideoTap.setSink(null);
        // Con AA aparcado (esperando al coche, o AaGuardService), sigue conectado: nada de vista en el móvil.
        if (AaPark.parked) {
            LinkState.setSource(LinkState.Level.BUSY, Str.get(R.string.hql_auto_paused));
        } else {
            LinkState.setSource(LinkState.Level.IDLE, "");
            VideoTap.setHeadless(false);
        }
        HeadUnitScreenConfig.setExternalCanvas(null);
        HeadUnitScreenConfig.setExternalDpi(0);
        if (offscreen != null) {
            App.Companion.provide(ctx).getVideoDecoder().stopIfCurrentSurface(offscreen.getSurface(), "headqlink stop");
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
        if (splashUi != null) {
            splashUi.stop();
            splashUi = null;
        }
        if (relay != null) {
            App.Companion.provide(ctx).getVideoDecoder().stopIfCurrentSurface(relayInput, "headqlink stop");
            relay.release();
            relay = null;
            relayInput = null;
        }
    }
}
