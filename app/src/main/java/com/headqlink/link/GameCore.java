package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Un núcleo de emulador: NES (Nestopia, nativo, el de RetroArch; halfNES en Java de reserva), Mega Drive (ClownMDEmu,
 * nativo) o Super Nintendo (bsnes, nativo, en su versión libretro). NesScreen solo ve esto.
 */
interface GameCore {
    interface Listener {
        /** Fotograma nuevo (hilo del emulador). Su tamaño es el de la consola (256x224 la NES, 320x224 o 256x224 la MD). */
        void onFrame(Bitmap frame);

        void onMessage(String text);
    }

    void setListener(Listener l);

    /** El mando del jugador 1 (NesInput lo mueve). */
    RetroPad pad();

    /** Carga la ROM (ruta real). Devuelve el error, o null si arranca. */
    String load(String path);

    void start();

    void reset();

    /** Para el emulador y guarda la partida del cartucho, si la tiene. */
    void stop();

    String romInfo();

    /**
     * Abre la ROM con el núcleo de su consola. NES: Nestopia y, si no hay librería o no la lee, halfNES; las NSF (música)
     * van a halfNES, que las reproduce solas. Devuelve el núcleo ya cargado; si ninguno puede, lanza el error del último.
     */
    static GameCore open(Context c, GameConsole console, String path) throws Exception {
        if (console == GameConsole.SNES) {
            SnesEngine s = new SnesEngine(c);
            String err = s.load(path);
            if (err == null) return s;
            s.stop();
            throw new Exception(err);
        }
        if (console == GameConsole.MD) {
            MdEngine m = new MdEngine(c);
            String err = m.load(path);
            if (err == null) return m;
            m.stop();
            throw new Exception(err);
        }
        String err = null;
        boolean nsf = path.toLowerCase(java.util.Locale.ROOT).endsWith(".nsf");
        if (!nsf && NestopiaEngine.available()) {
            NestopiaEngine n = new NestopiaEngine(c);
            err = n.load(path);
            if (err == null) return n;
            n.stop();
            L.w("NES: Nestopia no abre la ROM (" + err + "); se prueba con halfNES");
        }
        NesEngine h = new NesEngine(c);
        String e2 = h.load(path);
        if (e2 == null) return h;
        h.stop();
        throw new Exception(err != null ? err : e2);
    }
}
