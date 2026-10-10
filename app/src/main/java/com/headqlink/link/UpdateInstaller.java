package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.function.Consumer;

/**
 * «Descargar e instalar»: baja el APK de la release (files/apk/, se borra el anterior) y abre el instalador de Android
 * con él (FileProvider). La primera vez Android pide permitir a HeadQLink instalar apps desconocidas: se abre ese ajuste y
 * al volver hay que pulsar otra vez. La instalación la confirma Android; la firma es la misma, así que va encima.
 */
final class UpdateInstaller {
    private UpdateInstaller() {
    }

    /** Progreso (0-100) y resultado: el fichero bajado, o null con el error (hilo principal). */
    interface Listener {
        void onProgress(int pct);

        void onDone(File apk, String error);
    }

    static File dir(Context ctx) {
        return new File(ctx.getExternalFilesDir(null), "apk");
    }

    /** Baja el APK en un hilo aparte. */
    static void download(Context ctx, UpdateCheck.Release r, Listener l) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            File dir = dir(app);
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            dir.mkdirs();
            File out = new File(dir, "HeadQLink-" + r.version + ".apk");
            try {
                HttpURLConnection con = (HttpURLConnection) new URL(r.apkUrl).openConnection();
                con.setConnectTimeout(15_000);
                con.setReadTimeout(60_000);
                con.setInstanceFollowRedirects(true);
                con.setRequestProperty("User-Agent", Http.USER_AGENT);
                int code = con.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                long total = con.getContentLengthLong();
                try (InputStream in = con.getInputStream(); FileOutputStream fo = new FileOutputStream(out)) {
                    byte[] buf = new byte[64 * 1024];
                    long got = 0;
                    int lastPct = -1;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        fo.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct) {
                            lastPct = pct;
                            main.post(() -> l.onProgress(pct));
                        }
                    }
                }
                L.i("versiones: APK de la " + r.version + " bajado (" + (out.length() / 1024) + " KB)");
                main.post(() -> l.onDone(out, null));
            } catch (Exception e) {
                out.delete();
                String err = Http.safeError(e);
                L.w("versiones: no se pudo bajar el APK: " + err);
                main.post(() -> l.onDone(null, err));
            }
        }, "hql-apk-download").start();
    }

    /** ¿Puede HeadQLink abrir el instalador con un APK? Si no, se lleva al ajuste de apps desconocidas. */
    static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    static void openUnknownSourcesSetting(Context ctx) {
        ctx.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.getPackageName())));
    }

    /** Abre el instalador de Android con el APK bajado. */
    static void install(Context ctx, File apk) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        L.i("versiones: abro el instalador con " + apk.getName());
        ctx.startActivity(i);
    }
}
