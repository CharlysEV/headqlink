package com.headqlink.link;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Una consola en el coche (Juegos › NES o Mega Drive): navegador de la carpeta de juegos elegida en el móvil (subcarpetas
 * y los archivos de esa consola, también dentro de un .zip) y el juego a pantalla grande con botones táctiles o el mando
 * Bluetooth (NesInput). Solo con el coche parado, como el resto de Juegos. La ROM se copia (o se saca del .zip) a
 * files/<consola>/ para que el núcleo lea por ruta y guarde la partida al lado.
 */
final class NesScreen {
    private final CarScreen.Host host;
    private final Runnable onBack;
    private final Config cfg;
    private final List<String> dirStack = new ArrayList<>();
    private final List<String> dirNames = new ArrayList<>();
    private final GameConsole console;
    private GameCore engine;
    private NesView view;
    private boolean touchPad = true;
    /** Indicador «Mando 1 · Mando 2» y avisos de los mandos de la partida en marcha. */
    private NesInput.PadsListener padsListener;
    /** Consola de cada .zip ya mirado (por documento), para no abrirlo cada vez que se lista la carpeta. */
    private static final Map<String, String> zipKind = new ConcurrentHashMap<>();

    NesScreen(CarScreen.Host host, Runnable onBack, GameConsole console) {
        this.host = host;
        this.onBack = onBack;
        this.console = console;
        this.cfg = new Config(host.context());
    }

    /** Puro: ¿archivo de juego de alguna consola (o .zip, que se mira por dentro)? */
    static boolean isRom(String name) {
        return GameConsole.of(name) != null || GameConsole.isZip(name);
    }

    /** Una entrada del navegador. */
    static final class Entry implements Comparable<Entry> {
        final String docId;
        final String name;
        final boolean dir;
        /** Bytes, o -1 si el proveedor no lo dice. */
        final long size;

        Entry(String docId, String name, boolean dir, long size) {
            this.docId = docId;
            this.name = name;
            this.dir = dir;
            this.size = size;
        }

        @Override
        public int compareTo(Entry o) {
            if (dir != o.dir) return dir ? -1 : 1;
            return name.compareToIgnoreCase(o.name);
        }
    }

    View browser() {
        Context c = host.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(24, 18, 24, 6);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_games));
        back.setOnClickListener(v -> {
            if (dirStack.size() > 1) {
                dirStack.remove(dirStack.size() - 1);
                dirNames.remove(dirNames.size() - 1);
                host.setContent(browser());
            } else {
                onBack.run();
            }
        });
        header.addView(back);
        String tree = cfg.nesTree();
        String where = dirNames.isEmpty() ? console.title : console.title + " › " + String.join(" › ", dirNames);
        // Directamente sobre el fondo de la pantalla (el color del panel, que puede ser fijo): el color que contrasta con él.
        TextView title = CarStyle.text(c, where, 30, CarTheme.navText());
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(16, 0, 24, 0);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(header);
        if (tree.isEmpty()) {
            col.addView(CarStyle.message(c, Str.get(R.string.hql_nes_no_folder)));
            return col;
        }
        Uri treeUri = Uri.parse(tree);
        if (dirStack.isEmpty()) dirStack.add(DocumentsContract.getTreeDocumentId(treeUri));
        List<Entry> entries = list(treeUri, dirStack.get(dirStack.size() - 1));
        if (entries == null) {
            col.addView(CarStyle.message(c, Str.get(R.string.hql_nes_no_folder)));
            return col;
        }
        if (entries.isEmpty()) {
            col.addView(CarStyle.message(c, Str.get(R.string.hql_nes_empty, console.title)));
            return col;
        }
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 6, 24, 24);
        for (Entry e : entries) {
            TextView t = CarStyle.text(c, (e.dir ? "▸  " : "🎮  ") + (e.dir ? e.name : GameConsole.title(e.name)), 28, CarStyle.TEXT);
            t.setPadding(24, 20, 24, 20);
            t.setBackground(CarStyle.round(CarStyle.ITEM_BG, 20));
            t.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = 10;
            t.setOnClickListener(v -> {
                if (e.dir) {
                    dirStack.add(e.docId);
                    dirNames.add(e.name);
                    host.setContent(browser());
                } else {
                    play(treeUri, e);
                }
            });
            list.addView(t, lp);
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    /** Subcarpetas y juegos de esta consola de una carpeta del árbol elegido (null si el permiso se perdió). */
    private List<Entry> list(Uri tree, String docId) {
        List<Entry> all = children(tree, docId);
        if (all == null) return null;
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            // Las pistas .bin de un disco de PlayStation no son ROM de Mega Drive.
            boolean bigBin = e.size > GameConsole.MD_MAX_BYTES && e.name.toLowerCase(Locale.ROOT).endsWith(".bin");
            boolean m3u = e.name.toLowerCase(Locale.ROOT).endsWith(".m3u");
            if (e.dir || (console.accepts(e.name) && !(console == GameConsole.MD && bigBin) && (!m3u || discList(tree, e)))
                    || (GameConsole.isZip(e.name) && zipConsole(tree, e.docId) == console)) {
                out.add(e);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** ¿Un .m3u de discos (y no una lista de canales)? Se lee una vez (es texto corto) y se recuerda por documento. */
    private boolean discList(Uri tree, Entry e) {
        String k = zipKind.get("m3u:" + e.docId);
        if (k == null) {
            k = "";
            if (e.size >= 0 && e.size < 64 * 1024) {
                try (InputStream in = host.context().getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId))) {
                    if (in != null) {
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0 && b.size() < 64 * 1024) b.write(buf, 0, n);
                        if (PsxDisc.isDiscList(b.toString("UTF-8"))) k = "si";
                    }
                } catch (Exception ex) {
                    L.w("Juegos: no se pudo leer un .m3u: " + ex);
                }
            }
            zipKind.put("m3u:" + e.docId, k);
        }
        return !k.isEmpty();
    }

    /** Todo lo que hay en una carpeta del árbol elegido (null si el permiso se perdió). */
    private List<Entry> children(Uri tree, String docId) {
        List<Entry> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = host.context().getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String name = cur.getString(1);
                if (name == null) continue;
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2));
                long size = cur.isNull(3) ? -1 : cur.getLong(3);
                out.add(new Entry(cur.getString(0), name, dir, size));
            }
        } catch (Exception e) {
            L.w("Juegos: no se pudo leer la carpeta: " + e);
            return null;
        }
        return out;
    }

    /**
     * La consola de un .zip: la del primer archivo de dentro que sea de alguna (solo se leen las cabeceras hasta dar con
     * él), o null. Se recuerda por documento.
     */
    private GameConsole zipConsole(Uri tree, String docId) {
        String k = zipKind.get(docId);
        if (k == null) {
            k = "";
            try (InputStream in = host.context().getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, docId));
                 ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry ze;
                int seen = 0;
                while ((ze = z.getNextEntry()) != null && seen++ < 8) {
                    GameConsole g = ze.isDirectory() ? null : GameConsole.of(ze.getName());
                    // Un disco de PlayStation comprimido trae sus .bin, que también parecen de Mega Drive: si hay un
                    // .cue (o un .chd…), manda la PlayStation.
                    if (g == GameConsole.PSX) {
                        k = g.name();
                        break;
                    }
                    if (g != null && k.isEmpty()) k = g.name();
                }
            } catch (Exception ex) {
                L.w("Juegos: no se pudo mirar dentro de un .zip: " + ex);
            }
            zipKind.put(docId, k);
        }
        return k.isEmpty() ? null : GameConsole.valueOf(k);
    }

    /** Copia el juego a files/<consola>/ (sacándolo del .zip si viene así) y devuelve la ruta. */
    private File copyRom(Uri tree, Entry e) throws Exception {
        Context c = host.context();
        File dir = new File(c.getExternalFilesDir(null), console.dir);
        dir.mkdirs();
        try (InputStream in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId))) {
            if (!GameConsole.isZip(e.name)) {
                File rom = new File(dir, e.name);
                copy(in, rom);
                return rom;
            }
            try (ZipInputStream z = new ZipInputStream(in)) {
                ZipEntry ze;
                while ((ze = z.getNextEntry()) != null) {
                    if (ze.isDirectory() || !console.accepts(ze.getName())) continue;
                    // Solo el nombre del archivo, sin carpetas del .zip.
                    File rom = new File(dir, new File(ze.getName()).getName());
                    copy(z, rom);
                    return rom;
                }
            }
        }
        throw new java.io.FileNotFoundException(e.name);
    }

    private static void copy(InputStream in, File to) throws Exception {
        copy(in, to, null);
    }

    private static void copy(InputStream in, File to, Copied progress) throws Exception {
        try (FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (progress != null) progress.bytes(n);
            }
        }
    }

    private void play(Uri tree, Entry e) {
        Context c = host.context();
        if (console == GameConsole.PSX) {
            playDisc(tree, e);
            return;
        }
        File rom;
        try {
            rom = copyRom(tree, e);
        } catch (Exception ex) {
            L.w(console.title + ": no se pudo copiar la ROM: " + ex);
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, Http.safeError(ex))));
            return;
        }
        start(rom, e);
    }

    /** La partida que se está preparando (copiando el disco); otra o volver atrás la anula. */
    private final AtomicInteger playSeq = new AtomicInteger();

    /**
     * PlayStation: el disco (cientos de MB, a veces un .cue con varios .bin) se copia en otro hilo con su aviso; solo la
     * primera vez, las siguientes ya está y arranca al momento.
     */
    private void playDisc(Uri tree, Entry e) {
        Context c = host.context();
        int seq = playSeq.incrementAndGet();
        stopEngine();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        TextView msg = CarStyle.text(c, Str.get(R.string.hql_psx_copying, 0), 28, CarStyle.TEXT);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(40, 40, 40, 30);
        col.addView(msg);
        TextView back = CarStyle.pill(c, "‹  " + console.title);
        back.setOnClickListener(v -> {
            playSeq.incrementAndGet();
            host.setContent(browser());
        });
        col.addView(back);
        host.setContent(col);
        String parent = dirStack.get(dirStack.size() - 1);
        String root = dirStack.get(0);
        long[] lastPost = {0};
        new Thread(() -> {
            File rom;
            try {
                rom = copyDisc(tree, root, parent, e, seq, n -> {
                    long now = System.currentTimeMillis();
                    if (now - lastPost[0] < 300) return;
                    lastPost[0] = now;
                    int mb = (int) (n / (1024 * 1024));
                    host.post(() -> msg.setText(Str.get(R.string.hql_psx_copying, mb)));
                });
            } catch (Exception ex) {
                if (seq != playSeq.get()) return;
                L.w(console.title + ": no se pudo copiar el disco: " + ex);
                host.post(() -> {
                    if (seq == playSeq.get()) host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, Http.safeError(ex))));
                });
                return;
            }
            host.post(() -> {
                if (seq == playSeq.get()) start(rom, e);
            });
        }, "hql-copia-disco").start();
    }

    /** Lo copiado hasta ahora (bytes). */
    private interface Copied {
        void bytes(long n);
    }

    /**
     * Copia el disco a files/psx/«juego»/ con todo lo que necesita (las pistas de un .cue, los discos de un .m3u, o todo lo
     * de un .zip) y devuelve el archivo que se abre. Lo ya copiado con el mismo tamaño no se vuelve a copiar. Si junto al
     * disco hay una BIOS (scph1001.bin…), va a files/psx/bios. Se quedan las PsxDisc.KEEP copias más recientes.
     */
    private File copyDisc(Uri tree, String rootDoc, String parentDoc, Entry e, int seq, Copied progress) throws Exception {
        File base = new File(host.context().getExternalFilesDir(null), console.dir);
        File dir = new File(base, PsxDisc.folder(GameConsole.title(e.name)));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("no se pudo crear la carpeta del disco");
        List<Entry> kids = children(tree, parentDoc);
        if (kids == null) throw new java.io.FileNotFoundException(e.name);
        Map<String, Entry> byName = new java.util.HashMap<>();
        for (Entry k : kids) if (!k.dir) byName.put(k.name.toLowerCase(Locale.ROOT), k);
        long[] done = {0};
        Copied total = n -> {
            if (seq != playSeq.get()) throw new java.util.concurrent.CancellationException();
            done[0] += n;
            progress.bytes(done[0]);
        };
        File bios = PsxEngine.biosDir(base);
        // La BIOS puede estar junto al juego o en la carpeta principal de juegos.
        List<Entry> biosPlaces = new ArrayList<>(kids);
        if (!rootDoc.equals(parentDoc)) {
            List<Entry> root = children(tree, rootDoc);
            if (root != null) biosPlaces.addAll(root);
        }
        for (Entry k : biosPlaces) {
            if (k.dir || !PsxDisc.isBios(k.name) || k.size > 4 * 1024 * 1024) continue;
            File to = new File(bios, k.name.toLowerCase(Locale.ROOT));
            if (to.isFile()) continue;
            bios.mkdirs();
            copyCached(tree, k, to, total);
            L.i("PlayStation: BIOS copiada (" + to.getName() + ")");
        }
        File main;
        if (GameConsole.isZip(e.name)) {
            main = unzipDisc(tree, e, dir, total);
        } else {
            main = copyCached(tree, e, new File(dir, e.name), total);
            List<String> tracks = new ArrayList<>();
            String low = e.name.toLowerCase(Locale.ROOT);
            if (low.endsWith(".cue")) {
                tracks.addAll(PsxDisc.cueFiles(readText(main)));
            } else if (low.endsWith(".m3u")) {
                for (String d : PsxDisc.m3uFiles(readText(main))) {
                    File df = copyCached(tree, need(byName, d), new File(dir, d), total);
                    if (d.toLowerCase(Locale.ROOT).endsWith(".cue")) tracks.addAll(PsxDisc.cueFiles(readText(df)));
                }
            }
            for (String t : tracks) copyCached(tree, need(byName, t), new File(dir, t), total);
        }
        //noinspection ResultOfMethodCallIgnored
        dir.setLastModified(System.currentTimeMillis());
        evictDiscs(base);
        return main;
    }

    private static Entry need(Map<String, Entry> byName, String name) throws java.io.FileNotFoundException {
        Entry e = byName.get(name.toLowerCase(Locale.ROOT));
        if (e == null) throw new java.io.FileNotFoundException(name);
        return e;
    }

    /** Copia un documento a «to» si no está ya (mismo tamaño); por un .tmp, para no dejar copias a medias. */
    private File copyCached(Uri tree, Entry e, File to, Copied progress) throws Exception {
        if (to.isFile() && e.size > 0 && to.length() == e.size) return to;
        File tmp = new File(to.getPath() + ".tmp");
        try (InputStream in = host.context().getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId))) {
            if (in == null) throw new java.io.FileNotFoundException(e.name);
            copy(in, tmp, progress);
        }
        if (!tmp.renameTo(to)) throw new java.io.IOException("no se pudo guardar " + to.getName());
        return to;
    }

    /** Saca todo el .zip a la carpeta del disco (una vez: se apunta su tamaño) y devuelve el archivo que se abre. */
    private File unzipDisc(Uri tree, Entry e, File dir, Copied progress) throws Exception {
        File mark = new File(dir, ".zip");
        if (mark.isFile() && readText(mark).trim().equals(String.valueOf(e.size))) {
            String[] names = dir.list();
            String m = names == null ? null : PsxDisc.mainFile(java.util.Arrays.asList(names));
            if (m != null) return new File(dir, m);
        }
        List<String> names = new ArrayList<>();
        try (InputStream in = host.context().getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId));
             ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry ze;
            while ((ze = z.getNextEntry()) != null) {
                if (ze.isDirectory()) continue;
                String n = PsxDisc.baseName(ze.getName());
                if (n.isEmpty() || n.startsWith(".")) continue;
                File tmp = new File(dir, n + ".tmp");
                copy(z, tmp, progress);
                if (!tmp.renameTo(new File(dir, n))) throw new java.io.IOException("no se pudo guardar " + n);
                names.add(n);
            }
        }
        String m = PsxDisc.mainFile(names);
        if (m == null) throw new java.io.FileNotFoundException(e.name + ": sin .cue, .chd, .pbp, .iso ni .m3u");
        try (FileOutputStream out = new FileOutputStream(mark)) {
            out.write(String.valueOf(e.size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return new File(dir, m);
    }

    /** Borra las copias de discos más viejas (quedan PsxDisc.KEEP). */
    private static void evictDiscs(File base) {
        File[] dirs = base.listFiles(File::isDirectory);
        if (dirs == null) return;
        List<String> names = new ArrayList<>();
        List<Long> used = new ArrayList<>();
        for (File d : dirs) {
            names.add(d.getName());
            used.add(d.lastModified());
        }
        for (String n : PsxDisc.evict(names, used, PsxDisc.KEEP)) {
            L.i("PlayStation: se borra la copia de un disco antiguo para no llenar el móvil");
            PsxDisc.deleteTree(new File(base, n));
        }
    }

    /** Un .cue, .m3u o la marca del .zip (texto corto). */
    private static String readText(File f) throws Exception {
        if (f.length() > 256 * 1024) throw new java.io.IOException(f.getName() + ": demasiado grande");
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private void start(File rom, Entry e) {
        Context c = host.context();
        stopEngine();
        try {
            engine = GameCore.open(c, console, rom.getAbsolutePath());
        } catch (Exception ex) {
            engine = null;
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, ex.getMessage())));
            return;
        }
        L.i(console.title + ": " + e.name + " · " + engine.romInfo());
        // Estados del juego: «Guardar» / «Cargar» (una ranura) y el que se guarda solo al salir, en files/<consola>/estados.
        File states = new File(new File(c.getExternalFilesDir(null), console.dir), "estados");
        String base = PsxDisc.folder(GameConsole.title(e.name));
        slotState = new File(states, base + ".state");
        autoState = new File(states, base + ".auto.state");
        boolean resume = engine.supportsStates() && autoState.isFile();
        if (engine.supportsStates()) engine.setAutoState(autoState.getAbsolutePath());
        NesInput.newGame(engine.pad(), engine.pad2(), console);
        NesInput.watch(c);
        CarUi.gameDrawing(true);
        host.setContent(game(GameConsole.title(e.name), resume));
        playing = this;
        engine.start();
        // El mando Bluetooth: las teclas llegan por la accesibilidad; la cruceta y el stick (ejes) solo con una pantalla de
        // HeadQLink delante en el móvil. En el coche virtual ya lo está (CarSimActivity los pasa); con el coche de verdad,
        // la pantalla del mando (y una notificación para volver a ella).
        if (!RoutePlanner.testMode()) NesPadActivity.notify(c.getApplicationContext(), e.name);
    }

    /** Estados del juego en marcha: la ranura de «Guardar» / «Cargar» y el que se guarda solo al salir. */
    private File slotState;
    private File autoState;

    private View game(String name, boolean resume) {
        Context c = host.context();
        // Sin cabecera: el juego usa todo el alto (×4 en el C10) y los botones van a los lados, sobre el negro.
        FrameLayout stage = new FrameLayout(c);
        stage.setBackgroundColor(0xFF000000);
        view = new NesView(c);
        view.tv43 = console == GameConsole.PSX;
        stage.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View overlay = touchOverlay(c);
        stage.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView back = CarStyle.pill(c, "‹  " + console.title);
        back.setOnClickListener(v -> {
            stopEngine();
            host.setContent(browser());
        });
        left.addView(back);
        TextView title = CarStyle.text(c, name, 22, CarStyle.TEXT_DIM);
        title.setPadding(6, 12, 0, 0);
        title.setMaxLines(3);
        // Ancho fijo: con «wrap_content» la columna tomaba el de la píldora y el título se cortaba en la primera palabra.
        left.addView(title, new LinearLayout.LayoutParams(260, ViewGroup.LayoutParams.WRAP_CONTENT));
        // «Mando 1 · Mando 2»: los mandos Bluetooth con jugador en esta partida (nada con solo los botones táctiles).
        TextView pads = CarStyle.text(c, NesInput.padsLabel(), 20, CarStyle.TEXT_DIM);
        pads.setPadding(6, 8, 0, 0);
        pads.setVisibility(pads.getText().length() > 0 ? View.VISIBLE : View.GONE);
        left.addView(pads, new LinearLayout.LayoutParams(260, ViewGroup.LayoutParams.WRAP_CONTENT));
        stage.addView(left, place(Gravity.TOP | Gravity.LEFT, 24, 18, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        TextView reset = CarStyle.pill(c, Str.get(R.string.hql_nes_reset));
        reset.setOnClickListener(v -> {
            if (engine != null) engine.reset();
        });
        right.addView(reset);
        TextView pad = CarStyle.pill(c, Str.get(R.string.hql_nes_touch));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = 12;
        right.addView(pad, plp);
        stage.addView(right, place(Gravity.TOP | Gravity.RIGHT, 0, 18, 14, 0, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Aviso corto arriba en el centro («Partida guardada»…).
        TextView toast = CarStyle.text(c, "", 24, CarStyle.TEXT);
        toast.setPadding(28, 12, 28, 12);
        toast.setBackground(CarStyle.round(0xE6202124, 28));
        toast.setVisibility(View.GONE);
        stage.addView(toast, place(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 24, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Runnable hideToast = () -> toast.setVisibility(View.GONE);
        Say say = text -> {
            toast.setText(text);
            toast.setVisibility(View.VISIBLE);
            toast.removeCallbacks(hideToast);
            toast.postDelayed(hideToast, 2500);
        };
        NesInput.removeListener(padsListener);
        padsListener = notice -> host.post(() -> {
            String l = NesInput.padsLabel();
            pads.setText(l);
            pads.setVisibility(l.isEmpty() ? View.GONE : View.VISIBLE);
            if (notice != null) say.say(notice);
        });
        NesInput.addListener(padsListener);
        if (engine.supportsStates()) {
            TextView save = CarStyle.pill(c, Str.get(R.string.hql_nes_save));
            save.setOnClickListener(v -> {
                GameCore g = engine;
                if (g != null) g.saveState(slotState.getAbsolutePath(), ok -> host.post(() ->
                        say.say(Str.get(ok ? R.string.hql_nes_saved : R.string.hql_nes_save_failed))));
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = 12;
            right.addView(save, slp);
            TextView load = CarStyle.pill(c, Str.get(R.string.hql_nes_load));
            load.setOnClickListener(v -> {
                GameCore g = engine;
                if (g == null) return;
                if (!slotState.isFile()) {
                    say.say(Str.get(R.string.hql_nes_no_save));
                    return;
                }
                g.loadState(slotState.getAbsolutePath(), ok -> host.post(() ->
                        say.say(Str.get(ok ? R.string.hql_nes_loaded : R.string.hql_nes_load_failed))));
            });
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = 12;
            right.addView(load, llp);
        }
        if (resume) stage.addView(resumeCard(c, say), place(Gravity.CENTER, 0, 0, 0, 0, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pad.setOnClickListener(v -> {
            touchPad = !touchPad;
            overlay.setVisibility(touchPad ? View.VISIBLE : View.GONE);
            pad.setBackground(touchPad ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
            pad.setTextColor(touchPad ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        });
        pad.setBackground(touchPad ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
        pad.setTextColor(touchPad ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        overlay.setVisibility(touchPad ? View.VISIBLE : View.GONE);
        engine.setListener(new GameCore.Listener() {
            @Override
            public void onFrame(Bitmap frame) {
                NesView v = view;
                if (v != null) v.show(frame);
            }

            @Override
            public void onMessage(String text) {
                host.post(() -> host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, text))));
            }
        });
        return stage;
    }

    /** El aviso corto de la pantalla del juego. */
    private interface Say {
        void say(String text);
    }

    /**
     * «¿Seguir donde lo dejaste?» al abrir un juego con estado de la última vez: «Seguir» lo carga; «Empezar de nuevo» (o
     * no tocar nada en 30 s) lo deja empezar desde el principio. El juego ya corre por detrás.
     */
    private View resumeCard(Context c, Say say) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(40, 30, 40, 30);
        card.setBackground(CarStyle.round(0xF0202124, 32));
        TextView q = CarStyle.text(c, Str.get(R.string.hql_nes_resume), 30, CarStyle.TEXT);
        q.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        q.setPadding(0, 0, 0, 24);
        card.addView(q);
        LinearLayout row = new LinearLayout(c);
        TextView yes = CarStyle.pill(c, Str.get(R.string.hql_nes_resume_yes));
        yes.setBackground(CarStyle.accent(28));
        yes.setTextColor(CarStyle.ON_ACCENT);
        TextView no = CarStyle.pill(c, Str.get(R.string.hql_nes_resume_no));
        row.addView(yes);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.leftMargin = 20;
        row.addView(no, nlp);
        card.addView(row);
        Runnable hide = () -> card.setVisibility(View.GONE);
        yes.setOnClickListener(v -> {
            hide.run();
            GameCore g = engine;
            if (g != null) g.loadState(autoState.getAbsolutePath(), ok -> host.post(() ->
                    say.say(Str.get(ok ? R.string.hql_nes_loaded : R.string.hql_nes_load_failed))));
        });
        no.setOnClickListener(v -> hide.run());
        card.postDelayed(hide, 30000);
        return card;
    }

    /**
     * Botones táctiles a los lados del juego (que ocupa el centro a todo el alto): cruceta abajo a la izquierda, B y A (NES),
     * A, B y C (Mega Drive) o el rombo X, A, B, Y con L y R encima (Super Nintendo) abajo a la derecha, Select a la
     * izquierda y Start a la derecha, a media altura. Varios dedos a la vez.
     */
    private View touchOverlay(Context c) {
        FrameLayout f = new FrameLayout(c);
        // La Mega Drive ocupa 1280 de ancho a ×4: en el C10 quedan 272 a cada lado, así que sus botones son más pequeños.
        boolean md = console == GameConsole.MD;
        boolean snes = console == GameConsole.SNES || console == GameConsole.PSX;
        // La SNES cabe a ×4 como la NES, pero con cuatro botones y L y R: algo más pequeños para que L y R no toquen
        // Select y Start.
        int s = md ? 84 : snes ? 100 : 116;
        int m = md ? 14 : snes ? 24 : 26;
        int bl = Gravity.BOTTOM | Gravity.LEFT;
        int br = Gravity.BOTTOM | Gravity.RIGHT;
        f.addView(padButton(c, "▲", RetroPad.Btn.UP), place(bl, m + s, 0, 0, m + 2 * s, s, s));
        f.addView(padButton(c, "▼", RetroPad.Btn.DOWN), place(bl, m + s, 0, 0, m, s, s));
        f.addView(padButton(c, "◀", RetroPad.Btn.LEFT), place(bl, m, 0, 0, m + s, s, s));
        f.addView(padButton(c, "▶", RetroPad.Btn.RIGHT), place(bl, m + 2 * s, 0, 0, m + s, s, s));
        if (console == GameConsole.PSX) {
            // Rombo como en el mando de la PlayStation (△ arriba, ○ a la derecha, ✕ abajo, □ a la izquierda); L1 y R1
            // encima, con L2 y R2 por fuera en la misma fila (más arriba tocarían Select y Start).
            int half = (3 * s - 10) / 2;
            f.addView(padButton(c, "△", RetroPad.Btn.X), place(br, 0, 0, m + s, m + 2 * s, s, s));
            f.addView(padButton(c, "○", RetroPad.Btn.A), place(br, 0, 0, m, m + s, s, s));
            f.addView(padButton(c, "✕", RetroPad.Btn.B), place(br, 0, 0, m + s, m, s, s));
            f.addView(padButton(c, "□", RetroPad.Btn.Y), place(br, 0, 0, m + 2 * s, m + s, s, s));
            f.addView(padButton(c, "L2", RetroPad.Btn.L2), place(bl, m, 0, 0, m + 3 * s + 20, half, 60));
            f.addView(padButton(c, "L1", RetroPad.Btn.L), place(bl, m + half + 10, 0, 0, m + 3 * s + 20, half, 60));
            f.addView(padButton(c, "R1", RetroPad.Btn.R), place(br, 0, 0, m + half + 10, m + 3 * s + 20, half, 60));
            f.addView(padButton(c, "R2", RetroPad.Btn.R2), place(br, 0, 0, m, m + 3 * s + 20, half, 60));
        } else if (console == GameConsole.SNES) {
            // Rombo como en el mando de la SNES (X arriba, A a la derecha, B abajo, Y a la izquierda) y L y R encima.
            f.addView(padButton(c, "X", RetroPad.Btn.X), place(br, 0, 0, m + s, m + 2 * s, s, s));
            f.addView(padButton(c, "A", RetroPad.Btn.A), place(br, 0, 0, m, m + s, s, s));
            f.addView(padButton(c, "B", RetroPad.Btn.B), place(br, 0, 0, m + s, m, s, s));
            f.addView(padButton(c, "Y", RetroPad.Btn.Y), place(br, 0, 0, m + 2 * s, m + s, s, s));
            f.addView(padButton(c, "L", RetroPad.Btn.L), place(bl, m, 0, 0, m + 3 * s + 20, 3 * s, 60));
            f.addView(padButton(c, "R", RetroPad.Btn.R), place(br, 0, 0, m, m + 3 * s + 20, 3 * s, 60));
        } else if (console == GameConsole.MD) {
            // A, B y C en diagonal ascendente, como en el mando de la Mega Drive.
            int g = s + 6;
            f.addView(padButton(c, "A", RetroPad.Btn.A), place(br, 0, 0, m + 2 * g, m + 10, s, s));
            f.addView(padButton(c, "B", RetroPad.Btn.B), place(br, 0, 0, m + g, m + 10 + s / 3, s, s));
            f.addView(padButton(c, "C", RetroPad.Btn.C), place(br, 0, 0, m, m + 10 + 2 * s / 3, s, s));
        } else {
            f.addView(padButton(c, "B", RetroPad.Btn.B), place(br, 0, 0, m + s + 20, m + 30, s, s));
            f.addView(padButton(c, "A", RetroPad.Btn.A), place(br, 0, 0, m, m + 30 + s / 2 + 20, s, s));
        }
        f.addView(padButton(c, console == GameConsole.MD ? "MODE" : "SELECT", RetroPad.Btn.SELECT),
                place(Gravity.LEFT | Gravity.CENTER_VERTICAL, m, 0, 0, 0, 150, 56));
        f.addView(padButton(c, "START", RetroPad.Btn.START), place(Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, m, 0, 150, 56));
        return f;
    }

    private static FrameLayout.LayoutParams place(int gravity, int left, int top, int right, int bottom, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = right;
        lp.bottomMargin = bottom;
        return lp;
    }

    private View padButton(Context c, String label, RetroPad.Btn b) {
        TextView t = CarStyle.text(c, label, label.length() > 1 ? 18 : 34, 0xFFEAF2F7);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(CarStyle.round(0x88333B46, label.length() > 1 ? 28 : 60));
        t.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                    NesInput.press(b);
                    t.setBackground(CarStyle.round(0xCC00E5C7, label.length() > 1 ? 28 : 60));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    NesInput.release(b);
                    t.setBackground(CarStyle.round(0x88333B46, label.length() > 1 ? 28 : 60));
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    /** El juego que está en marcha (para pausarlo sin coche). */
    private static volatile NesScreen playing;

    /**
     * El coche se ha ido (se apagó, se cortó la conexión) o ha vuelto: el juego se pausa (imagen y sonido) y sigue al
     * volver. Si el coche no vuelve, al cerrarse todo se guarda el estado como siempre.
     */
    static void carPresent(boolean present) {
        NesScreen s = playing;
        GameCore e = s != null ? s.engine : null;
        if (e == null) return;
        L.i("Juegos: " + (present ? "vuelve el coche: sigue el juego" : "sin coche: juego en pausa"));
        e.setPaused(!present);
    }

    void stopEngine() {
        if (playing == this) playing = null;
        CarUi.gameDrawing(false);
        NesInput.removeListener(padsListener);
        padsListener = null;
        NesInput.endGame();
        NesPadActivity.close();
        NesPadActivity.cancelNotification(host.context());
        GameCore e = engine;
        engine = null;
        view = null;
        if (e != null) e.stop();
    }

    /** El fotograma del emulador, escalado a lo que cabe (aumento entero) y centrado. */
    static final class NesView extends View {
        private volatile Bitmap frame;
        // Sin suavizado: píxeles nítidos, que el codificador de vídeo comprime mucho mejor.
        private final Paint p = new Paint();
        private final Rect src = new Rect();
        private final RectF dst = new RectF();

        NesView(Context c) {
            super(c);
        }

        /** Líneas que se pueden recortar arriba y abajo para ganar un aumento (el borde: las teles no lo enseñaban). */
        static final int MAX_CROP = 2;

        /**
         * Puro: aumento entero que cabe (×4 en el C10, recortando 2 líneas arriba y abajo). Con un aumento no entero, cada
         * paso del desplazamiento remuestrea los píxeles y el codificador no encuentra el fotograma anterior: se pixela.
         * Con uno entero, un paso de la NES son k píxeles exactos y se comprime casi gratis.
         */
        static int scale(int w, int h) {
            return scale(w, h, NesEngine.W, NesEngine.H);
        }

        /** Puro: igual para un fotograma de fw x fh (la Mega Drive: 320x224, ×4 en el C10 como la NES). */
        static int scale(int w, int h, int fw, int fh) {
            return Math.max(1, Math.min(w / fw, h / (fh - 2 * MAX_CROP)));
        }

        /** Puro: ancho lógico de un fotograma (la alta resolución de la SNES, 512, cuenta como 256). */
        static int logicalW(int fw) {
            return fw >= 480 ? fw / 2 : fw;
        }

        /** Puro: alto lógico (el entrelazado, 448 o más líneas, cuenta como la mitad). */
        static int logicalH(int fh) {
            return fh >= 400 ? fh / 2 : fh;
        }

        /** Puro: líneas que se recortan arriba (y abajo) con ese aumento: las justas para caber, como mucho MAX_CROP. */
        static int crop(int k, int h) {
            return crop(k, h, NesEngine.H);
        }

        static int crop(int k, int h, int fh) {
            int lines = Math.min(fh, h / k);
            return Math.min(MAX_CROP, (fh - lines + 1) / 2);
        }

        private volatile boolean pending;

        /**
         * Guarda el último fotograma y lo dibuja ya en el hilo principal: sin esperar al ritmo de la pantalla del móvil
         * (CarUi.gameFrame dibuja la interfaz directamente en el vídeo del coche).
         */
        void show(Bitmap b) {
            frame = b;
            if (!pending) {
                pending = true;
                post(() -> {
                    pending = false;
                    invalidate();
                    CarUi.gameFrame();
                });
            }
        }

        /**
         * PlayStation: la imagen a todo el alto en 4:3, como en la tele, sea cual sea su ancho (256, 320, 368, 512 o 640
         * columnas). Con aumento entero quedaba a ×3 (768x720 en el C10); así, 1176x882. Con suavizado, porque el aumento
         * no es entero.
         */
        boolean tv43;

        /** Puro: ancho y alto de la imagen 4:3 más grande que cabe en w x h. */
        static int[] fit43(int w, int h) {
            int dh = h;
            int dw = Math.round(h * 4f / 3f);
            if (dw > w) {
                dw = w;
                dh = Math.round(w * 3f / 4f);
            }
            return new int[]{dw, dh};
        }

        @Override
        protected void onDraw(Canvas cv) {
            Bitmap b = frame;
            if (b == null) return;
            int w = getWidth();
            int h = getHeight();
            int fw = b.getWidth();
            int fh = b.getHeight();
            if (tv43) {
                int[] d = fit43(w, h);
                int left = (w - d[0]) / 2;
                int top = (h - d[1]) / 2;
                src.set(0, 0, fw, fh);
                dst.set(left, top, left + d[0], top + d[1]);
                p.setFilterBitmap(true);
                cv.drawBitmap(b, src, dst, p);
                return;
            }
            // Tamaño lógico: 512 de ancho (alta resolución de la SNES) es 256; 448 o más líneas (entrelazado), la mitad.
            int lw = logicalW(fw);
            int lh = logicalH(fh);
            int k = scale(w, h, lw, lh);
            int c = crop(k, h, lh);
            int sc = c * fh / lh;
            src.set(0, sc, fw, fh - sc);
            int dw = lw * k;
            int dh = (lh - 2 * c) * k;
            int left = (w - dw) / 2;
            int top = (h - dh) / 2;
            dst.set(left, top, left + dw, top + dh);
            cv.drawBitmap(b, src, dst, p);
        }
    }
}
