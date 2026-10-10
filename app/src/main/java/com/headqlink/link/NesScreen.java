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
        NesInput.pad = engine.pad1();
        host.setContent(game(e.name));
        engine.start();
        // El mando Bluetooth: las teclas llegan por la accesibilidad; la cruceta (ejes) solo con HeadQLink o la pantalla
        // del mando delante en el móvil. Android no deja abrirla desde segundo plano: una notificación la abre de un toque.
        NesPadActivity.notify(c, e.name);
    }

    private View game(String name) {
        Context c = host.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF000000);
        LinearLayout header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(24, 12, 24, 6);
        TextView back = CarStyle.pill(c, "‹  NES");
        back.setOnClickListener(v -> {
            stopEngine();
            host.setContent(browser());
        });
        header.addView(back);
        TextView title = CarStyle.text(c, name.replaceAll("(?i)\\.(nes|nsf)$", ""), 26, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(16, 0, 24, 0);
        title.setSingleLine(true);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView reset = CarStyle.pill(c, Str.get(R.string.hql_nes_reset));
        reset.setOnClickListener(v -> {
            if (engine != null) engine.reset();
        });
        header.addView(reset);
        TextView pad = CarStyle.pill(c, Str.get(R.string.hql_nes_touch));
        header.addView(pad);
        col.addView(header);
        FrameLayout stage = new FrameLayout(c);
        view = new NesView(c);
        stage.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View overlay = touchOverlay(c);
        stage.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pad.setOnClickListener(v -> {
            touchPad = !touchPad;
            overlay.setVisibility(touchPad ? View.VISIBLE : View.GONE);
            pad.setBackground(touchPad ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
            pad.setTextColor(touchPad ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        });
        pad.setBackground(CarStyle.accent(28));
        pad.setTextColor(CarStyle.ON_ACCENT);
        col.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
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
        return col;
    }

    /** Botones táctiles: cruceta a la izquierda, B y A a la derecha, Select y Start abajo. Varios dedos a la vez. */
    private View touchOverlay(Context c) {
        FrameLayout f = new FrameLayout(c);
        int s = 120;
        int m = 30;
        f.addView(padButton(c, "▲", PuppetController.Button.UP), at(m + s, m, s, s, Gravity.BOTTOM | Gravity.LEFT, 2 * s + 2 * m));
        f.addView(padButton(c, "▼", PuppetController.Button.DOWN), at(m + s, m, s, s, Gravity.BOTTOM | Gravity.LEFT, m));
        f.addView(padButton(c, "◀", PuppetController.Button.LEFT), at(m, m, s, s, Gravity.BOTTOM | Gravity.LEFT, s + m + m / 2));
        f.addView(padButton(c, "▶", PuppetController.Button.RIGHT), at(m + 2 * s, m, s, s, Gravity.BOTTOM | Gravity.LEFT, s + m + m / 2));
        f.addView(padButton(c, "B", PuppetController.Button.B), at(0, 2 * s + m + 20, s, s, Gravity.BOTTOM | Gravity.RIGHT, s + m));
        f.addView(padButton(c, "A", PuppetController.Button.A), at(0, s + 20 - 60, s, s, Gravity.BOTTOM | Gravity.RIGHT, 2 * s + m - 40));
        f.addView(padButton(c, "SELECT", PuppetController.Button.SELECT), at(0, 0, 150, 56, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, m, -90));
        f.addView(padButton(c, "START", PuppetController.Button.START), at(0, 0, 150, 56, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, m, 90));
        return f;
    }

    private static FrameLayout.LayoutParams at(int left, int right, int w, int h, int gravity, int bottom) {
        return at(left, right, w, h, gravity, bottom, 0);
    }

    private static FrameLayout.LayoutParams at(int left, int right, int w, int h, int gravity, int bottom, int shiftX) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, gravity);
        lp.leftMargin = left + Math.max(0, shiftX);
        lp.rightMargin = right + Math.max(0, -shiftX);
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

        /**
         * Puro: aumento entero que cabe (×3 en el C10). Con un aumento no entero, cada paso del desplazamiento remuestrea
         * los píxeles y el codificador no encuentra el fotograma anterior: se pixela. Con uno entero, un paso de la NES son
         * k píxeles exactos y se comprime casi gratis.
         */
        static int scale(int w, int h) {
            return Math.max(1, Math.min(w / NesEngine.W, h / NesEngine.H));
        }

        private volatile boolean pending;

        /** Guarda el último fotograma y pide un redibujado por tic de la pantalla (no uno por fotograma emulado). */
        void show(Bitmap b) {
            frame = b;
            if (!pending) {
                pending = true;
                postOnAnimation(() -> {
                    pending = false;
                    invalidate();
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
            int dw = NesEngine.W * k;
            int dh = NesEngine.H * k;
            int left = (w - dw) / 2;
            int top = (h - dh) / 2;
            dst.set(left, top, left + dw, top + dh);
            cv.drawBitmap(b, src, dst, p);
        }
    }
}
