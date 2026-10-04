package com.headqlink.link;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Estado visible del enlace, para la pantalla principal. Se notifica en el hilo principal. */
public final class LinkState {
    public enum Car { OFF, SEARCHING, SEEN, CONNECTED }
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
        }
        changed();
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
