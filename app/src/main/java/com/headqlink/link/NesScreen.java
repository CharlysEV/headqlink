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
import com.grapeshot.halfnes.ui.PuppetController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NES en el coche (Juegos › NES): navegador de la carpeta de ROMs elegida en el móvil (subcarpetas y archivos .nes) y el
 * juego a pantalla grande con botones táctiles (cruceta, B, A, Select, Start) o el mando Bluetooth (NesInput). Solo con
 * el coche parado, como el resto de Juegos. La ROM se copia a files/nes/ para que el núcleo lea por ruta y guarde la
 * SRAM al lado.
 */
final class NesScreen {
    private final CarScreen.Host host;
    private final Runnable onBack;
    private final Config cfg;
    private final List<String> dirStack = new ArrayList<>();
    private final List<String> dirNames = new ArrayList<>();
    private NesEngine engine;
    private NesView view;
    private boolean touchPad = true;

    NesScreen(CarScreen.Host host, Runnable onBack) {
        this.host = host;
        this.onBack = onBack;
        this.cfg = new Config(host.context());
    }

    /** Puro: ¿archivo de NES? */
    static boolean isRom(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".nes") || n.endsWith(".nsf");
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
        String where = dirNames.isEmpty() ? "NES" : String.join(" › ", dirNames);
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
            col.addView(CarStyle.message(c, Str.get(R.string.hql_nes_empty)));
            return col;
        }
        LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(24, 6, 24, 24);
        for (Entry e : entries) {
            TextView t = CarStyle.text(c, (e.dir ? "▸  " : "🎮  ") + e.name, 28, CarStyle.TEXT);
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

    /** Subcarpetas y archivos .nes de una carpeta del árbol elegido (null si el permiso se perdió). */
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
                if (dir || isRom(name)) out.add(new Entry(id, name, dir));
            }
        } catch (Exception e) {
            L.w("NES: no se pudo leer la carpeta: " + e);
            return null;
        }
        Collections.sort(out);
        return out;
    }

    private void play(Uri tree, Entry e) {
        Context c = host.context();
        File dir = new File(c.getExternalFilesDir(null), "nes");
        dir.mkdirs();
        File rom = new File(dir, e.name);
        try (InputStream in = c.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, e.docId));
             FileOutputStream out = new FileOutputStream(rom)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ex) {
            L.w("NES: no se pudo copiar la ROM: " + ex);
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, Http.safeError(ex))));
            return;
        }
        stopEngine();
        engine = new NesEngine(c);
        String err = engine.load(rom.getAbsolutePath());
        if (err != null) {
            engine.stop();
            engine = null;
            host.setContent(CarStyle.message(c, Str.get(R.string.hql_nes_error, err)));
            return;
        }
        L.i("NES: " + e.name + " · " + engine.romInfo());
        NesInput.newGame(engine.pad1());
        CarUi.gameDrawing(true);
        host.setContent(game(e.name));
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
        TextView back = CarStyle.pill(c, "‹  NES");
        back.setOnClickListener(v -> {
            stopEngine();
            host.setContent(browser());
        });
        left.addView(back);
        TextView title = CarStyle.text(c, name.replaceAll("(?i)\\.(nes|nsf)$", ""), 22, CarStyle.TEXT_DIM);
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
        engine.setListener(new NesEngine.Listener() {
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
     * Botones táctiles a los lados del juego (que ocupa el centro a todo el alto): cruceta abajo a la izquierda, B y A
     * abajo a la derecha, Select a la izquierda y Start a la derecha, a media altura. Varios dedos a la vez.
     */
    private View touchOverlay(Context c) {
        FrameLayout f = new FrameLayout(c);
        int s = 116;
        int m = 26;
        int bl = Gravity.BOTTOM | Gravity.LEFT;
        int br = Gravity.BOTTOM | Gravity.RIGHT;
        f.addView(padButton(c, "▲", PuppetController.Button.UP), place(bl, m + s, 0, 0, m + 2 * s, s, s));
        f.addView(padButton(c, "▼", PuppetController.Button.DOWN), place(bl, m + s, 0, 0, m, s, s));
        f.addView(padButton(c, "◀", PuppetController.Button.LEFT), place(bl, m, 0, 0, m + s, s, s));
        f.addView(padButton(c, "▶", PuppetController.Button.RIGHT), place(bl, m + 2 * s, 0, 0, m + s, s, s));
        f.addView(padButton(c, "B", PuppetController.Button.B), place(br, 0, 0, m + s + 20, m + 30, s, s));
        f.addView(padButton(c, "A", PuppetController.Button.A), place(br, 0, 0, m, m + 30 + s / 2 + 20, s, s));
        f.addView(padButton(c, "SELECT", PuppetController.Button.SELECT), place(Gravity.LEFT | Gravity.CENTER_VERTICAL, m, 0, 0, 0, 150, 56));
        f.addView(padButton(c, "START", PuppetController.Button.START), place(Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, m, 0, 150, 56));
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

    private View padButton(Context c, String label, PuppetController.Button b) {
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
        NesInput.pad = null;
        NesPadActivity.close();
        NesPadActivity.cancelNotification(host.context());
        NesEngine e = engine;
        engine = null;
        view = null;
        if (e != null) e.stop();
    }

    /** El fotograma del emulador, escalado a lo que cabe (8:7 como la NES) y centrado. */
    static final class NesView extends View {
        private volatile Bitmap frame;
        // Sin suavizado: píxeles nítidos, que el codificador de vídeo comprime mucho mejor.
        private final Paint p = new Paint();
        private final Rect src = new Rect(0, 0, NesEngine.W, NesEngine.H);
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
            return Math.max(1, Math.min(w / NesEngine.W, h / (NesEngine.H - 2 * MAX_CROP)));
        }

        /** Puro: líneas que se recortan arriba (y abajo) con ese aumento: las justas para caber, como mucho MAX_CROP. */
        static int crop(int k, int h) {
            int lines = Math.min(NesEngine.H, h / k);
            return Math.min(MAX_CROP, (NesEngine.H - lines + 1) / 2);
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
            int k = scale(w, h);
            int c = crop(k, h);
            src.set(0, c, NesEngine.W, NesEngine.H - c);
            int dw = NesEngine.W * k;
            int dh = (NesEngine.H - 2 * c) * k;
            int left = (w - dw) / 2;
            int top = (h - dh) / 2;
            dst.set(left, top, left + dw, top + dh);
            cv.drawBitmap(b, src, dst, p);
        }
    }
}
