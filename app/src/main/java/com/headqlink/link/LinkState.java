package com.headqlink.link;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Estado visible del enlace, para la pantalla principal. Se notifica en el hilo principal. */
public final class LinkState {
    /** RECONNECTING: la sesión con el coche cayó y el vídeo (Android Auto) sigue vivo esperándolo (qdauto §6.2). */
    public enum Car { OFF, SEARCHING, SEEN, CONNECTED, RECONNECTING }
    public enum Level { IDLE, BUSY, OK, ERROR }

    public interface Listener {
        void onLinkStateChanged();
    }

    static volatile boolean running;
    static volatile Car car = Car.OFF;
    static volatile String carDetail = "";
    static volatile String video = "";
    static volatile Level sourceLevel = Level.IDLE;
    static volatile String source = "";
    /** Red con el coche (qdauto §5.2): zona Wi-Fi activa o apagada, o el grupo Wi-Fi Direct. */
    static volatile Level networkLevel = Level.IDLE;
    static volatile String network = "";
    /** Sesión actual del motor QDAuto: IP del coche · interfaz e IP local (vacío sin sesión). */
    static volatile String linkDetail = "";
    /** Sesión que puso linkDetail (0 = ninguna): una vieja que se cierra tarde no borra el de la nueva (con LinkState.class). */
    private static int linkDetailOwner;
    /**
     * Conexión (Config.LINK_*) y motor (Config.ENGINE_*) con los que arrancó el servicio, vacíos sin él: cambiarlos en
     * marcha se aplica al volver a conectar, así que lo que se ve y se registra es esto, no lo configurado.
     */
    static volatile String activeLinkMode = "";
    static volatile String activeEngine = "";
    /** El motor no pudo abrir el UDP 18463: otra app (QDLink abierto) lo tiene. Lo enseña la comprobación de requisitos. */
    static volatile boolean udpBusy;

    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LinkState() {
    }

    static void addListener(Listener l) {
        LISTENERS.addIfAbsent(l);
    }

    static void removeListener(Listener l) {
        LISTENERS.remove(l);
    }

    static void setRunning(boolean r) {
        running = r;
        if (!r) {
            car = Car.OFF;
            carDetail = "";
            video = "";
            sourceLevel = Level.IDLE;
            source = "";
            networkLevel = Level.IDLE;
            network = "";
            synchronized (LinkState.class) {
                linkDetail = "";
                linkDetailOwner = 0;
            }
            activeLinkMode = "";
            activeEngine = "";
            udpBusy = false;
        }
        changed();
    }

    /** El motor abrió (false) o no pudo abrir por estar ocupado (true) el UDP 18463. */
    static void setUdpBusy(boolean busy) {
        if (udpBusy == busy) return;
        udpBusy = busy;
        changed();
    }

    /** Al arrancar el transporte (LinkService.startTransport): la conexión y el motor de este servicio. */
    static void setActiveTransport(String linkMode, String engine) {
        activeLinkMode = linkMode != null ? linkMode : "";
        activeEngine = engine != null ? engine : "";
        changed();
    }

    /** La conexión con la que va el servicio o, parado, la configurada (la del próximo «Conectar»). */
    static String linkModeFor(Config cfg) {
        String a = activeLinkMode;
        return running && !a.isEmpty() ? a : cfg.linkMode();
    }

    /** El motor con el que va el servicio o, parado, el configurado. */
    static String engineFor(Config cfg) {
        String a = activeEngine;
        return running && !a.isEmpty() ? a : cfg.linkEngine();
    }

    /** En marcha con otra conexión u otro motor configurados: se aplican al volver a conectar. */
    static boolean transportChangePending(Config cfg) {
        return running && !activeLinkMode.isEmpty()
                && (!activeLinkMode.equals(cfg.linkMode()) || !activeEngine.equals(cfg.linkEngine()));
    }

    static void setCar(Car c, String detail) {
        car = c;
        carDetail = detail != null ? detail : "";
        if (c != Car.CONNECTED) video = "";
        changed();
    }

    static void setVideo(String v) {
        video = v;
        changed();
    }

    static void setNetwork(Level level, String text) {
        networkLevel = level;
        network = text != null ? text : "";
        changed();
    }

    /** Detalle de la sesión owner (su id), que pasa a ser la dueña. */
    static void setLinkDetail(String detail, int owner) {
        synchronized (LinkState.class) {
            linkDetail = detail != null ? detail : "";
            linkDetailOwner = owner;
        }
        changed();
    }

    /** Borra el detalle solo si sigue siendo de la sesión owner (dos sesiones seguidas con el coche lo ponen igual). */
    static void clearLinkDetail(int owner) {
        synchronized (LinkState.class) {
            if (linkDetailOwner != owner) return;
            linkDetail = "";
            linkDetailOwner = 0;
        }
        changed();
    }

    static void setSource(Level level, String text) {
        sourceLevel = level;
        source = text;
        changed();
    }

    private static void changed() {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onLinkStateChanged();
        });
    }
}
