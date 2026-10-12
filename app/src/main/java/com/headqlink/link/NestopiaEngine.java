package com.headqlink.link;

import android.content.Context;

import java.io.InputStream;

/**
 * Emulador NES con el núcleo de Nestopia (el de RetroArch, en C++: libhqlnes, app/src/main/cpp/nestopia) y un puente JNI
 * propio. Lee muchos más cartuchos que halfNES (p. ej. el mapper 141 de Super Pang) y es más fiel. Imagen de 256x224 (sin
 * las 8 líneas de arriba y abajo, como halfNES) y sonido mono a 44,1 kHz; el resto lo hace NativeCore. Si la librería no
 * carga o Nestopia no lee la ROM, GameCore.open vuelve a halfNES.
 */
final class NestopiaEngine extends NativeCore {
    private static final boolean LIB;
    private static boolean initDone;

    static {
        boolean ok;
        try {
            System.loadLibrary("hqlnes");
            ok = true;
        } catch (Throwable t) {
            L.w("NES: la librería de Nestopia no carga: " + t);
            ok = false;
        }
        LIB = ok;
    }

    private static native boolean nInit(byte[] db);

    private static native String nLoad(byte[] rom, byte[] sav, int rate);

    private static native int nFrame(int[] argb, short[] audio, int buttons);

    private static native void nReset();

    private static native byte[] nUnload();

    private static native byte[] nSaveState();

    private static native boolean nLoadState(byte[] state);

    private static native String nInfo();

    private final Context ctx;

    NestopiaEngine(Context ctx) {
        // Mismo volumen que tenía halfNES.
        super("NES (Nestopia)", 1, 13107 / 16384f);
        this.ctx = ctx.getApplicationContext();
    }

    static boolean available() {
        return LIB;
    }

    @Override
    String nativeLoad(byte[] rom, byte[] sav) {
        if (!LIB) return "sin librería";
        if (!initDone) {
            long t0 = System.nanoTime();
            boolean db = nInit(asset("nes/NstDatabase.xml"));
            initDone = true;
            L.i("NES: Nestopia listo" + (db ? " con su base de datos de cartuchos" : " (sin base de datos)") + " en "
                    + (System.nanoTime() - t0) / 1_000_000 + " ms");
        }
        return nLoad(rom, sav, RATE);
    }

    @Override
    int nativeFrame(int[] argb, short[] samples, int padBits) {
        return nFrame(argb, samples, padBits);
    }

    @Override
    int nativeSize() {
        return (NesEngine.W << 16) | NesEngine.H;
    }

    @Override
    void nativeReset() {
        nReset();
    }

    @Override
    byte[] nativeUnload() {
        return LIB && initDone ? nUnload() : null;
    }

    @Override
    int padBits(int bits) {
        return RetroPad.nesBits(bits);
    }

    @Override
    int consoleFps() {
        return 60;
    }

    @Override
    public String romInfo() {
        return LIB && initDone ? nInfo() : "";
    }

    private byte[] asset(String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            return readAll(in);
        } catch (Exception e) {
            L.w("NES: sin " + name + ": " + e);
            return null;
        }
    }

    @Override
    byte[] nativeSaveState() {
        return nSaveState();
    }

    @Override
    boolean nativeLoadState(byte[] state) {
        return nLoadState(state);
    }
}
