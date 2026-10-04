package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * Diagnóstico sin coche: CarUi dibuja en un ImageReader del tamaño de la pantalla del coche y se
 * guarda una captura PNG (files/ui-preview-<pantalla>.png). Pantallas: aa, photos, videos.
 *   adb shell am start-foreground-service -n com.headqlink.app/com.headqlink.link.LinkService
 *       -a com.headqlink.link.UI_PREVIEW --es screen photos
 */
final class UiPreview {
    private static final int W = 1920;
    private static final int H = 882;

    private UiPreview() {
    }

    static void run(Context ctx, String screen, Runnable done) {
        String s = screen != null ? screen : "aa";
        ImageReader ir = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
        CarUi ui = new CarUi(ctx, W, H, AaPassthroughSource.PANEL_W, 200, v -> L.i("preview: AA visible " + v));
        ui.start(ir.getSurface());
        Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(() -> {
            if (!"aa".equals(s)) ui.showForPreview(s);
        }, 800);
        main.postDelayed(() -> {
            Image img = ir.acquireLatestImage();
            if (img == null) {
                L.w("preview: sin imagen");
            } else {
                save(ctx, img, s);
                img.close();
            }
            ui.stop();
            main.postDelayed(() -> {
                ir.close();
                done.run();
            }, 500);
        }, 3000);
    }

    private static void save(Context ctx, Image img, String s) {
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer buf = p.getBuffer();
        int rowPx = p.getRowStride() / p.getPixelStride();
        Bitmap b = Bitmap.createBitmap(rowPx, H, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(buf);
        Bitmap crop = Bitmap.createBitmap(b, 0, 0, W, H);
        File f = new File(ctx.getExternalFilesDir(null), "ui-preview-" + s + ".png");
        try (FileOutputStream o = new FileOutputStream(f)) {
            crop.compress(Bitmap.CompressFormat.PNG, 100, o);
            L.i("preview: guardada " + f);
        } catch (Exception e) {
            L.e("preview", e);
        }
    }
}
