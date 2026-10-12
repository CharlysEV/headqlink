package com.headqlink.link;

import android.content.Context;

import java.io.File;

/**
 * Emulador de Super Nintendo con bsnes (el de byuu, en su versión libretro: libhqlsnes, app/src/main/cpp/bsnes, GPL v3)
 * y un frontend libretro propio (hql_snes.cpp). Imagen de 256x224 (512 de ancho en alta resolución; 239 líneas en
 * PAL) y sonido estéreo a 44,1 kHz; el resto lo hace NativeCore. bsnes lee la ROM por su ruta y guarda él la partida
 * del cartucho («nombre.srm», junto a la ROM copiada). Chips de los cartuchos (Super FX, SA-1, DSP…) incluidos.
 */
final class SnesEngine extends NativeCore {
    private static final boolean LIB;

    static {
        boolean ok;
        try {
            System.loadLibrary("hqlsnes");
            ok = true;
        } catch (Throwable t) {
            L.w("Super Nintendo: la librería de bsnes no carga: " + t);
            ok = false;
        }
        LIB = ok;
    }

    private static native String nLoad(String path, String saveDir);

    private static native int nFrame(int[] argb, short[] audio, int buttons);

    private static native int nSize();

    private static native int nFps100();

    private static native void nReset();

    private static native void nUnload();


    private int fps = 60;

    SnesEngine(Context ctx) {
        super("Super Nintendo", 2, 1f);
    }

    @Override
    boolean loadsByPath() {
        return true;
    }

    @Override
    String nativeLoadPath(String path) {
        if (!LIB) return "sin librería";
        File dir = new File(path).getParentFile();
        String err = nLoad(path, dir != null ? dir.getAbsolutePath() : "");
        if (err == null) fps = Math.max(1, Math.round(nFps100() / 100f));
        return err;
    }

    @Override
    String nativeLoad(byte[] rom, byte[] sav) {
        return "bsnes carga la ROM por su ruta";
    }

    @Override
    int nativeFrame(int[] argb, short[] samples, int padBits) {
        return nFrame(argb, samples, padBits);
    }

    @Override
    int nativeSize() {
        return nSize();
    }

    @Override
    void nativeReset() {
        nReset();
    }

    /** bsnes guarda la partida él mismo al descargar el juego; aquí no hay nada que escribir. */
    @Override
    byte[] nativeUnload() {
        if (LIB) nUnload();
        return null;
    }

    @Override
    int padBits(int bits) {
        return SnesPad.toLibretro(bits);
    }

    @Override
    int consoleFps() {
        return fps;
    }

    @Override
    public String romInfo() {
        return "bsnes · " + (fps <= 50 ? "PAL" : "NTSC");
    }
}
