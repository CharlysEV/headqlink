package com.headqlink.link;

import android.content.Context;

import java.io.File;

/**
 * Emulador de PlayStation con PCSX ReARMed (en su versión libretro: libhqlpsx, app/src/main/cpp/pcsx_rearmed, GPL v2 o
 * posterior) y un frontend libretro propio (hql_psx.cpp). En arm64, con su recompilador y la GPU NEON. Imagen de 256 a
 * 640 de ancho y 240 o 480 líneas, sonido estéreo a 44,1 kHz; el resto lo hace NativeCore. El núcleo lee el disco por su
 * ruta (.cue con sus .bin, .chd, .pbp, .iso o .m3u). Sin BIOS de Sony usa la suya (HLE); con una en files/psx/bios
 * (scph1001.bin…), esa. La tarjeta de memoria 1 se guarda en files/psx/saves/«juego».srm.
 */
final class PsxEngine extends NativeCore {
    private static final boolean LIB;

    static {
        boolean ok;
        try {
            System.loadLibrary("hqlpsx");
            ok = true;
        } catch (Throwable t) {
            L.w("PlayStation: la librería de PCSX ReARMed no carga: " + t);
            ok = false;
        }
        LIB = ok;
    }

    private static native String nLoad(String path, String saveDir, String systemDir, String srmPath);

    /** Imagen mejorada (resolución interna al doble y sin el tramado), para el próximo nLoad. */
    private static native void nSetEnhanced(boolean on);

    private static native int nFrame(int[] argb, short[] audio, int buttons);

    private static native int nSize();

    private static native int nFps100();

    private static native void nReset();

    private static native void nUnload();

    private static native byte[] nSaveState();

    private static native boolean nLoadState(byte[] state);

    private final File base;
    private int fps = 60;

    private final Context appCtx;

    PsxEngine(Context ctx) {
        super("PlayStation", 2, 1f);
        appCtx = ctx.getApplicationContext();
        base = new File(ctx.getExternalFilesDir(null), GameConsole.PSX.dir);
    }

    static File saveDir(File base) {
        return new File(base, "saves");
    }

    static File biosDir(File base) {
        return new File(base, "bios");
    }

    @Override
    boolean loadsByPath() {
        return true;
    }

    @Override
    String nativeLoadPath(String path) {
        if (!LIB) return "sin librería";
        File saves = saveDir(base);
        File bios = biosDir(base);
        saves.mkdirs();
        bios.mkdirs();
        String title = GameConsole.title(new File(path).getName());
        boolean enhanced = new Config(appCtx).psxEnhanced();
        nSetEnhanced(enhanced);
        L.i("PlayStation: imagen " + (enhanced ? "mejorada (resolución al doble y sin tramado)" : "original"));
        String err = nLoad(path, saves.getAbsolutePath(), bios.getAbsolutePath(), new File(saves, title + ".srm").getAbsolutePath());
        if (err == null) fps = Math.max(1, Math.round(nFps100() / 100f));
        return err;
    }

    @Override
    String nativeLoad(byte[] rom, byte[] sav) {
        return "PCSX ReARMed carga el disco por su ruta";
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

    /** La tarjeta de memoria la guarda hql_psx.cpp al cerrar el juego; aquí no hay nada que escribir. */
    @Override
    byte[] nativeUnload() {
        if (LIB) nUnload();
        return null;
    }

    @Override
    int padBits(int bits) {
        return PsxPad.toLibretro(bits);
    }

    @Override
    int consoleFps() {
        return fps;
    }

    @Override
    public String romInfo() {
        return "PCSX ReARMed · " + (fps <= 50 ? "PAL" : "NTSC");
    }

    @Override
    byte[] nativeSaveState() {
        return LIB ? nSaveState() : null;
    }

    @Override
    boolean nativeLoadState(byte[] state) {
        return LIB && nLoadState(state);
    }
}
