package com.headqlink.link;

import android.content.Context;

/**
 * Emulador de Mega Drive con el núcleo ClownMDEmu (el de RetroArch, en C: libhqlmd, app/src/main/cpp/clownmdemu, AGPL v3
 * como HeadQLink) y un puente JNI propio. Imagen de 320x224 (o 256x224 en los juegos de modo estrecho; 240 líneas en PAL)
 * y sonido estéreo a 44,1 kHz; el resto lo hace NativeCore. Mando de seis botones; sin Mega CD.
 */
final class MdEngine extends NativeCore {
    private static final boolean LIB;

    static {
        boolean ok;
        try {
            System.loadLibrary("hqlmd");
            ok = true;
        } catch (Throwable t) {
            L.w("Mega Drive: la librería de ClownMDEmu no carga: " + t);
            ok = false;
        }
        LIB = ok;
    }

    private static native String nLoad(byte[] rom, byte[] sav);

    private static native int nFrame(int[] argb, short[] audio, int buttons);

    private static native int nSize();

    private static native boolean nPal();

    private static native void nReset();

    private static native byte[] nUnload();

    private boolean pal;

    MdEngine(Context ctx) {
        super("Mega Drive", 2, 1f);
    }

    @Override
    String nativeLoad(byte[] rom, byte[] sav) {
        if (!LIB) return "sin librería";
        String err = nLoad(rom, sav);
        if (err == null) pal = nPal();
        return err;
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

    @Override
    byte[] nativeUnload() {
        return LIB ? nUnload() : null;
    }

    /** El orden de RetroPad es el de ClownMDEmu (arriba, abajo, izquierda, derecha, A, B, C, X, Y, Z, Start, Mode). */
    @Override
    int padBits(int bits) {
        return bits;
    }

    @Override
    int consoleFps() {
        return pal ? 50 : 60;
    }

    @Override
    public String romInfo() {
        return "ClownMDEmu · " + (pal ? "PAL" : "NTSC");
    }
}
