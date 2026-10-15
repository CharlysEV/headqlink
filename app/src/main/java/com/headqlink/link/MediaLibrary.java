package com.headqlink.link;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Las fotos o los vídeos para la pantalla del coche: los del móvil (MediaStore, con su álbum, fecha, duración y si son
 * favoritos de la galería), los de un USB enchufado (otro volumen de MediaStore) y los de la carpeta elegida en la app
 * (vídeos). Los álbumes y las secciones por día son puros (los prueban los tests).
 */
final class MediaLibrary {
    /** Como mucho tantos elementos (las más recientes). */
    static final int MAX = 3000;

    static final String ALL = "all";
    static final String FAV = "fav";
    static final String CAMERA = "camera";
    static final String WHATSAPP = "whatsapp";
    static final String DOWNLOAD = "download";
    static final String SCREENSHOTS = "screenshots";
    static final String USB = "usb";
    static final String FOLDER = "folder";
    /** Prefijo de un álbum por su nombre de carpeta (los que no son de los de arriba). */
    static final String BUCKET = "b:";

    private MediaLibrary() {
    }

    /** Una foto o un vídeo. */
    static final class Item {
        final Uri uri;
        final String name;
        final String bucket;
        final long dateMs;
        final long durMs;
        final boolean fav;
        /** "ms" (el móvil), USB o FOLDER. */
        final String source;
        /** Carpeta de la app: el árbol y el id del documento padre (para buscar sus subtítulos). */
        final Uri tree;
        final String parentDoc;

        Item(Uri uri, String name, String bucket, long dateMs, long durMs, boolean fav, String source, Uri tree, String parentDoc) {
            this.uri = uri;
            this.name = name == null ? "" : name;
            this.bucket = bucket == null ? "" : bucket;
            this.dateMs = dateMs;
            this.durMs = durMs;
            this.fav = fav;
            this.source = source;
            this.tree = tree;
            this.parentDoc = parentDoc;
        }
    }

    /** Un álbum para las pastillas: su clave, su nombre (o null: lo pone la pantalla por la clave) y cuántos tiene. */
    static final class Album {
        final String key;
        final String name;
        final int count;

        Album(String key, String name, int count) {
            this.key = key;
            this.name = name;
            this.count = count;
        }
    }

    /** Puro: la clave del álbum de una carpeta de la galería. */
    static String bucketKey(String bucket) {
        String b = bucket == null ? "" : bucket.trim().toLowerCase(Locale.ROOT);
        if (b.equals("camera") || b.equals("dcim") || b.equals("cámara") || b.equals("camara")) return CAMERA;
        if (b.startsWith("whatsapp")) return WHATSAPP;
        if (b.equals("download") || b.equals("downloads") || b.equals("descargas")) return DOWNLOAD;
        if (b.startsWith("screenshot") || b.equals("capturas de pantalla") || b.equals("capturas")) return SCREENSHOTS;
        return BUCKET + (bucket == null ? "" : bucket);
    }

    /** Puro: ¿está el elemento en el álbum? */
    static boolean inAlbum(Item it, String key) {
        if (key == null || key.equals(ALL)) return true;
        if (key.equals(FAV)) return it.fav;
        if (key.equals(USB) || key.equals(FOLDER)) return key.equals(it.source);
        if (!"ms".equals(it.source)) return false;
        return bucketKey(it.bucket).equals(key);
    }

    /**
     * Puro: las pastillas de álbum, en este orden: todas, cámara, WhatsApp, descargas, favoritos, capturas, USB, la
     * carpeta de la app y el resto de carpetas por número de elementos; solo las que tienen algo.
     */
    static List<Album> albums(List<Item> items) {
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        int fav = 0;
        for (Item it : items) {
            if (it.fav) fav++;
            String k = "ms".equals(it.source) ? bucketKey(it.bucket) : it.source;
            count.merge(k, 1, Integer::sum);
            if (k.startsWith(BUCKET)) names.put(k, it.bucket);
        }
        List<Album> out = new ArrayList<>();
        out.add(new Album(ALL, null, items.size()));
        for (String k : new String[]{CAMERA, WHATSAPP, DOWNLOAD}) {
            Integer n = count.remove(k);
            if (n != null) out.add(new Album(k, null, n));
        }
        if (fav > 0) out.add(new Album(FAV, null, fav));
        for (String k : new String[]{SCREENSHOTS, USB, FOLDER}) {
            Integer n = count.remove(k);
            if (n != null) out.add(new Album(k, null, n));
        }
        List<Map.Entry<String, Integer>> rest = new ArrayList<>(count.entrySet());
        rest.sort((a, b) -> b.getValue() != a.getValue().intValue() ? Integer.compare(b.getValue(), a.getValue())
                : String.valueOf(names.get(a.getKey())).compareToIgnoreCase(String.valueOf(names.get(b.getKey()))));
        for (Map.Entry<String, Integer> e : rest) {
            String n = names.get(e.getKey());
            if (n == null || n.isEmpty()) continue;
            out.add(new Album(e.getKey(), n, e.getValue()));
        }
        return out;
    }

    /** Puro: el día (año·10000 + mes·100 + día) de una fecha en esa zona. */
    static int dayKey(long ms, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(ms);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Puro: el mes (año·100 + mes) de una fecha en esa zona. */
    static int monthKey(long ms, TimeZone tz) {
        return dayKey(ms, tz) / 100;
    }

    /**
     * Puro: la lista de la cuadrícula con un encabezado por día: cada fila es un encabezado (header ≥ 0: el día, como
     * dayKey) o un elemento (index en items). items ya va del más reciente al más antiguo.
     */
    static List<int[]> sections(List<Item> items, TimeZone tz) {
        List<int[]> rows = new ArrayList<>();
        int last = -1;
        for (int i = 0; i < items.size(); i++) {
            int d = dayKey(items.get(i).dateMs, tz);
            if (d != last) {
                rows.add(new int[]{d, -1});
                last = d;
            }
            rows.add(new int[]{-1, i});
        }
        return rows;
    }

    /** Puro: los meses de la barra lateral: {mes como monthKey, fila del primer encabezado de ese mes}. */
    static List<int[]> months(List<int[]> rows) {
        List<int[]> out = new ArrayList<>();
        int last = -1;
        for (int r = 0; r < rows.size(); r++) {
            int d = rows.get(r)[0];
            if (d < 0) continue;
            int m = d / 100;
            if (m != last) {
                out.add(new int[]{m, r});
                last = m;
            }
        }
        return out;
    }

    /** Puro: hoy (0), ayer (1) u otro día (2) respecto a nowMs. */
    static int relativeDay(int dayKey, long nowMs, TimeZone tz) {
        if (dayKey == dayKey(nowMs, tz)) return 0;
        if (dayKey == dayKey(nowMs - 86_400_000L, tz)) return 1;
        return 2;
    }

    // ------------------------------------------------------------------ lectura

    /** Lo del móvil y lo de los USB (todos los volúmenes de MediaStore), del más reciente al más antiguo. */
    static List<Item> query(Context c, boolean videos) {
        List<Item> out = new ArrayList<>();
        Set<String> volumes = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                volumes = MediaStore.getExternalVolumeNames(c);
            } catch (RuntimeException ignored) {
            }
        }
        if (volumes == null || volumes.isEmpty()) {
            queryVolume(c, videos, videos ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "ms", out);
        } else {
            for (String v : volumes) {
                Uri base = videos ? MediaStore.Video.Media.getContentUri(v) : MediaStore.Images.Media.getContentUri(v);
                // El almacenamiento del móvil es «external_primary»; los demás, tarjetas o USB.
                queryVolume(c, videos, base, MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(v) ? "ms" : USB, out);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    private static void queryVolume(Context c, boolean videos, Uri base, String source, List<Item> out) {
        List<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME);
        cols.add(MediaStore.Images.ImageColumns.DATE_TAKEN);
        cols.add(MediaStore.MediaColumns.DATE_ADDED);
        if (videos) cols.add(MediaStore.Video.VideoColumns.DURATION);
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.MediaColumns.IS_FAVORITE);
        try (Cursor cur = c.getContentResolver().query(base, cols.toArray(new String[0]), null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return;
            int iDur = videos ? cur.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) : -1;
            int iFav = Build.VERSION.SDK_INT >= 30 ? cur.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE) : -1;
            int n = 0;
            while (cur.moveToNext() && n++ < MAX) {
                long taken = cur.getLong(3);
                long date = taken > 0 ? taken : cur.getLong(4) * 1000L;
                out.add(new Item(ContentUris.withAppendedId(base, cur.getLong(0)), cur.getString(1), cur.getString(2), date,
                        iDur >= 0 ? cur.getLong(iDur) : 0, iFav >= 0 && cur.getInt(iFav) == 1, source, null, null));
            }
        } catch (RuntimeException e) {
            L.w("galería: " + e.getClass().getSimpleName());
        }
    }

    private static final String[] VIDEO_EXT = {".mp4", ".mkv", ".webm", ".mov", ".m4v", ".3gp", ".avi", ".ts"};

    /** Puro: ¿un vídeo por su nombre? */
    static boolean isVideoName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String e : VIDEO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** Los vídeos de la carpeta elegida en la app (y sus subcarpetas, hasta 3 niveles). */
    static List<Item> folder(Context c, String tree) {
        List<Item> out = new ArrayList<>();
        if (tree == null || tree.isEmpty()) return out;
        try {
            Uri t = Uri.parse(tree);
            walk(c.getContentResolver(), t, DocumentsContract.getTreeDocumentId(t), 0, out);
        } catch (RuntimeException e) {
            L.w("galería: carpeta de vídeos: " + e.getClass().getSimpleName());
        }
        Collections.sort(out, (a, b) -> Long.compare(b.dateMs, a.dateMs));
        return out;
    }

    private static void walk(ContentResolver cr, Uri tree, String doc, int depth, List<Item> out) {
        if (depth > 3 || out.size() >= MAX) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, doc);
        List<String> dirs = new ArrayList<>();
        try (Cursor cur = cr.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                String mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(id);
                } else if ((mime != null && mime.startsWith("video/")) || isVideoName(name)) {
                    out.add(new Item(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, null, cur.getLong(3), 0,
                            false, FOLDER, tree, doc));
                }
            }
        }
        for (String d : dirs) walk(cr, tree, d, depth + 1, out);
    }

    /** Puro: los nombres de subtítulos que valen para un vídeo («Peli.mkv» → «Peli.srt», «Peli.es.srt»…). */
    static boolean subtitleFor(String video, String candidate) {
        if (video == null || candidate == null) return false;
        String v = video.toLowerCase(Locale.ROOT);
        String s = candidate.toLowerCase(Locale.ROOT);
        if (!s.endsWith(".srt")) return false;
        int dot = v.lastIndexOf('.');
        String base = dot > 0 ? v.substring(0, dot) : v;
        return s.equals(base + ".srt") || (s.startsWith(base + ".") && s.length() > base.length() + 5);
    }

    /** Los subtítulos .srt junto al vídeo (carpeta de la app), o null. */
    static Uri subtitles(Context c, Item it) {
        if (it.tree == null || it.parentDoc == null) return null;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(it.tree, it.parentDoc);
        Map<String, String> found = new LinkedHashMap<>();
        try (Cursor cur = c.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) if (subtitleFor(it.name, cur.getString(1))) found.put(cur.getString(1), cur.getString(0));
        } catch (RuntimeException e) {
            return null;
        }
        if (found.isEmpty()) return null;
        // El del idioma del móvil si lo hay («.es.srt»); si no, el primero.
        String lang = "." + Locale.getDefault().getLanguage() + ".srt";
        String id = found.values().iterator().next();
        for (Map.Entry<String, String> e : found.entrySet()) if (e.getKey().toLowerCase(Locale.ROOT).endsWith(lang)) id = e.getValue();
        return DocumentsContract.buildDocumentUriUsingTree(it.tree, id);
    }
}
