package com.c10link.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Lector de PDF: los documentos se eligen en el móvil (pantalla principal → Documentos PDF) y aquí
 * se leen página a página, ajustados al ancho, con desplazamiento vertical.
 */
final class PdfScreen implements CarScreen {
    private Host host;
    private View listView;
    private PdfRenderer renderer;
    private ParcelFileDescriptor pfd;
    private Thread worker;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        List<String[]> docs = new Config(c).pdfDocuments();
        if (docs.isEmpty()) return CarStyle.message(c, "Añade documentos desde el móvil: pantalla principal → Documentos PDF.");
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(30, 30, 30, 30);
        for (String[] d : docs) {
            TextView t = CarStyle.text(c, d[1], 28, CarStyle.TEXT);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(28, 0, 28, 0);
            t.setBackground(CarStyle.round(CarStyle.ITEM_BG, 18));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100);
            lp.bottomMargin = 12;
            col.addView(t, lp);
            t.setOnClickListener(v -> open(Uri.parse(d[0]), d[1]));
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        listView = sv;
        return sv;
    }

    @Override
    public void destroy() {
        close();
    }

    private void open(Uri uri, String title) {
        Context c = host.context();
        close();
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            if (pfd == null) throw new IllegalStateException("sin acceso");
            renderer = new PdfRenderer(pfd);
        } catch (Exception e) {
            L.w("PDF: " + title + ": " + e);
            close();
            host.setContent(withBack(c, CarStyle.message(c, "No se puede abrir " + title + ". Vuelve a añadirlo desde el móvil.")));
            return;
        }
        int pages = renderer.getPageCount();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF2A2D31);
        int w = host.width() - 40;
        ImageView[] views = new ImageView[pages];
        for (int i = 0; i < pages; i++) {
            ImageView iv = new ImageView(c);
            iv.setAdjustViewBounds(true);
            iv.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, (int) (w * 1.414f));
            lp.setMargins(20, 20, 20, 0);
            col.addView(iv, lp);
            views[i] = iv;
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        FrameLayout f = withBack(c, sv);
        TextView name = CarStyle.text(c, title + " · " + pages + " págs.", 22, CarStyle.TEXT);
        name.setPadding(16, 24, 24, 16);
        name.setShadowLayer(6, 0, 0, Color.BLACK);
        f.addView(name, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        host.setContent(f);

        // Renderizado en segundo plano, página a página (PdfRenderer no admite hilos simultáneos).
        PdfRenderer r = renderer;
        worker = new Thread(() -> {
            for (int i = 0; i < pages && renderer == r; i++) {
                Bitmap bmp;
                synchronized (r) {
                    if (renderer != r) return;
                    try (PdfRenderer.Page p = r.openPage(i)) {
                        int bw = w;
                        int bh = Math.max(1, Math.round(bw * p.getHeight() / (float) p.getWidth()));
                        bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    } catch (RuntimeException e) {
                        L.w("PDF página " + i + ": " + e);
                        continue;
                    }
                }
                int idx = i;
                Bitmap b = bmp;
                host.post(() -> {
                    ImageView iv = views[idx];
                    iv.setImageBitmap(b);
                    ViewGroup.LayoutParams lp = iv.getLayoutParams();
                    lp.height = b.getHeight();
                    iv.setLayoutParams(lp);
                });
            }
        }, "pdf-render");
        worker.start();
    }

    private FrameLayout withBack(Context c, View content) {
        FrameLayout f = new FrameLayout(c);
        f.addView(content, CarStyle.match());
        f.addView(CarStyle.back(c, () -> {
            close();
            host.setContent(listView);
        }));
        return f;
    }

    private void close() {
        PdfRenderer r = renderer;
        renderer = null;
        if (r != null) {
            synchronized (r) {
                r.close();
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
            pfd = null;
        }
    }
}
