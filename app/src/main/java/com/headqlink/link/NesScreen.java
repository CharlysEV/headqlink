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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

        Entry(String docId, String name, boolean dir) {
            this.docId = docId;
            this.name = name;
            this.dir = dir;
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
        TextView title = CarStyle.text(c, where, 30, CarStyle.TEXT);
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
        List<Entry> out = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = host.context().getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cur == null) return null;
            while (cur.moveToNext()) {
                String id = cur.getString(0);
                String name = cur.getString(1);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(cur.getString(2));
                if (name == null) continue;
                if (dir || console.accepts(name) || (GameConsole.isZip(name) && zipConsole(tree, id) == console)) out.add(new Entry(id, name, dir));
            }
        } catch (Exception e) {
            L.w("Juegos: no se pudo leer la carpeta: " + e);
            return null;
        }
        Collections.sort(out);
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
                    if (g != null) {
                        k = g.name();
                        break;
                    }
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
        try (FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private void play(Uri tree, Entry e) {
        Context c = host.context();
        File rom;
        try {
            rom = copyRom(tree, e);
        } catch (Exception ex) {
            L.w(console.title + ": no se pudo copiar la ROM: " + ex);
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, Http.safeError(ex))));
            return;
        }
        stopEngine();
        try {
            engine = GameCore.open(c, console, rom.getAbsolutePath());
        } catch (Exception ex) {
            engine = null;
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, ex.getMessage())));
            return;
        }
        L.i(console.title + ": " + e.name + " · " + engine.romInfo());
        NesInput.newGame(engine.pad(), console);
        CarUi.gameDrawing(true);
        host.setContent(game(GameConsole.title(e.name)));
        engine.start();
        // El mando Bluetooth: las teclas llegan por la accesibilidad; la cruceta y el stick (ejes) solo con una pantalla de
        // HeadQLink delante en el móvil. En el coche virtual ya lo está (CarSimActivity los pasa); con el coche de verdad,
        // la pantalla del mando (y una notificación para volver a ella).
        if (!RoutePlanner.testMode()) NesPadActivity.notify(c.getApplicationContext(), e.name);
    }

    private View game(String name) {
        Context c = host.context();
        // Sin cabecera: el juego usa todo el alto (×4 en el C10) y los botones van a los lados, sobre el negro.
        FrameLayout stage = new FrameLayout(c);
        stage.setBackgroundColor(0xFF000000);
        view = new NesView(c);
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

    /**
     * Botones táctiles a los lados del juego (que ocupa el centro a todo el alto): cruceta abajo a la izquierda, B y A (NES),
     * A, B y C (Mega Drive) o el rombo X, A, B, Y con L y R encima (Super Nintendo) abajo a la derecha, Select a la
     * izquierda y Start a la derecha, a media altura. Varios dedos a la vez.
     */
    private View touchOverlay(Context c) {
        FrameLayout f = new FrameLayout(c);
        // La Mega Drive ocupa 1280 de ancho a ×4: en el C10 quedan 272 a cada lado, así que sus botones son más pequeños.
        boolean md = console == GameConsole.MD;
        boolean snes = console == GameConsole.SNES;
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
        if (console == GameConsole.SNES) {
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

    void stopEngine() {
        CarUi.gameDrawing(false);
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

        @Override
        protected void onDraw(Canvas cv) {
            Bitmap b = frame;
            if (b == null) return;
            int w = getWidth();
            int h = getHeight();
            int fw = b.getWidth();
            int fh = b.getHeight();
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
