package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.Rotate;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visor de fotos del coche: se pasa deslizando o tocando los tercios, se amplía con dos dedos o doble toque, se gira, y
 * las fotos verticales llevan a los lados su propia imagen difuminada en vez de negro. Y el pase de diapositivas a
 * pantalla completa (cada SLIDE_MS, con fundido, la fecha y el sitio si la foto lo lleva y hay permiso).
 */
final class PhotoViewer {
    static final long SLIDE_MS = 7000;

    private final CarScreen.Host host;
    private final List<MediaLibrary.Item> items;
    private final Runnable onBack;
    /** Giro de cada foto (grados), mientras dura la pantalla. */
    private final Map<Uri, Integer> rotation = new HashMap<>();
    private static final Map<Uri, String> places = new HashMap<>();

    PhotoViewer(CarScreen.Host host, List<MediaLibrary.Item> items, Runnable onBack) {
        this.host = host;
        this.items = items;
        this.onBack = onBack;
    }

    /** Fondo: la propia foto en miniatura estirada (borrosa de por sí) y oscurecida. */
    private static ImageView blurredBg(Context c, Uri u) {
        ImageView bg = new ImageView(c);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.45f);
        Glide.with(c.getApplicationContext()).load(u).override(24, 14).centerCrop().into(bg);
        return bg;
    }

    // ------------------------------------------------------------------ visor

    void open(int start) {
        Context c = host.context();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        RecyclerView pager = new RecyclerView(c);
        LinearLayoutManager lm = new LinearLayoutManager(c, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(lm);
        new PagerSnapHelper().attachToRecyclerView(pager);
        TextView pos = CarStyle.text(c, "", 22, Color.WHITE);
        pos.setShadowLayer(6, 0, 0, Color.BLACK);
        int w = host.width();
        int h = host.height();
        pager.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout page = new FrameLayout(parent.getContext());
                page.setLayoutParams(new RecyclerView.LayoutParams(w, h));
                return new RecyclerView.ViewHolder(page) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int i) {
                FrameLayout page = (FrameLayout) vh.itemView;
                page.removeAllViews();
                Uri u = items.get(i).uri;
                page.addView(blurredBg(c, u), CarStyle.match());
                ZoomImageView iv = new ZoomImageView(c);
                iv.setListener(side -> {
                    int cur = lm.findFirstCompletelyVisibleItemPosition();
                    if (cur < 0) cur = vh.getBindingAdapterPosition();
                    int to = cur + side;
                    if (side != 0 && to >= 0 && to < items.size()) pager.smoothScrollToPosition(to);
                });
                page.addView(iv, CarStyle.match());
                int deg = rotation.containsKey(u) ? rotation.get(u) : 0;
                // Solo el giro (el encaje lo hace la vista: FIT_CENTER); un fitCenter() aquí sustituiría al giro.
                if (deg == 0) Glide.with(c.getApplicationContext()).load(u).override(w, h).into(iv);
                else Glide.with(c.getApplicationContext()).load(u).override(w, h).transform(new Rotate(deg)).into(iv);
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });
        pager.scrollToPosition(start);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) showPos(pos, lm.findFirstVisibleItemPosition());
            }
        });
        f.addView(pager, CarStyle.match());
        f.addView(CarStyle.back(c, () -> {
            host.setFullscreen(false);
            onBack.run();
        }));
        LinearLayout tools = new LinearLayout(c);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.addView(pos);
        pos.setPadding(16, 0, 16, 0);
        TextView rot = CarStyle.pill(c, "↻ 90°");
        rot.setOnClickListener(v -> {
            int cur = lm.findFirstVisibleItemPosition();
            if (cur < 0) return;
            Uri u = items.get(cur).uri;
            int deg = ((rotation.containsKey(u) ? rotation.get(u) : 0) + 90) % 360;
            rotation.put(u, deg);
            pager.getAdapter().notifyItemChanged(cur);
        });
        tools.addView(rot);
        TextView show = CarStyle.pill(c, "▶  " + Str.get(R.string.hql_gal_slideshow));
        show.setOnClickListener(v -> slideshow(Math.max(0, lm.findFirstVisibleItemPosition())));
        tools.addView(show);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        tl.setMargins(0, 16, 16, 0);
        f.addView(tools, tl);
        showPos(pos, start);
        host.setContent(f);
        host.setFullscreen(true);
    }

    private void showPos(TextView pos, int i) {
        if (i >= 0) pos.setText((i + 1) + " / " + items.size());
    }

    // ------------------------------------------------------------------ pase de diapositivas

    void slideshow(int start) {
        Context c = host.context();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        ImageView bg = new ImageView(c);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        bg.setAlpha(0.45f);
        f.addView(bg, CarStyle.match());
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, CarStyle.match());
        TextView info = CarStyle.text(c, "", 26, Color.WHITE);
        info.setShadowLayer(8, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        il.setMargins(32, 0, 0, 28);
        f.addView(info, il);
        int[] idx = {start};
        boolean[] running = {true};
        Runnable[] next = new Runnable[1];
        next[0] = () -> {
            if (!running[0] || items.isEmpty()) return;
            MediaLibrary.Item it = items.get(idx[0] % items.size());
            Glide.with(c.getApplicationContext()).load(it.uri).override(24, 14).centerCrop().into(bg);
            Glide.with(c.getApplicationContext()).load(it.uri).override(host.width(), host.height()).fitCenter()
                    .transition(DrawableTransitionOptions.withCrossFade(800)).into(iv);
            String date = DateFormat.getDateInstance(DateFormat.LONG).format(new Date(it.dateMs));
            info.setText(date);
            placeOf(c, it.uri, p -> {
                if (p != null && running[0]) info.post(() -> info.setText(date + "  ·  " + p));
            });
            idx[0] = (idx[0] + 1) % items.size();
            f.postDelayed(next[0], SLIDE_MS);
        };
        Runnable stop = () -> {
            running[0] = false;
            f.removeCallbacks(next[0]);
            host.setFullscreen(false);
        };
        // Un toque: fuera del pase, al visor en la foto que se veía.
        f.setOnClickListener(v -> {
            stop.run();
            open(Math.max(0, (idx[0] - 1 + items.size()) % items.size()));
        });
        f.addView(CarStyle.back(c, () -> {
            stop.run();
            onBack.run();
        }));
        f.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                running[0] = false;
                f.removeCallbacks(next[0]);
            }
        });
        host.setContent(f);
        host.setFullscreen(true);
        L.i("galería: pase de diapositivas (" + items.size() + " fotos)");
        next[0].run();
    }

    /**
     * El sitio de la foto (localidad), en otro hilo: con el permiso de ubicación de las fotos (Android 10+) se lee el GPS
     * de la foto y se busca su localidad; sin él, o sin GPS en la foto, null. Se recuerda mientras vive la app.
     */
    private static void placeOf(Context c, Uri u, java.util.function.Consumer<String> done) {
        synchronized (places) {
            if (places.containsKey(u)) {
                done.accept(places.get(u));
                return;
            }
        }
        if (Build.VERSION.SDK_INT < 29
                || c.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            done.accept(null);
            return;
        }
        new Thread(() -> {
            String place = null;
            try (InputStream in = c.getContentResolver().openInputStream(MediaStore.setRequireOriginal(u))) {
                if (in != null) {
                    float[] ll = new float[2];
                    if (new android.media.ExifInterface(in).getLatLong(ll) && Geocoder.isPresent()) {
                        @SuppressWarnings("deprecation")
                        List<Address> a = new Geocoder(c).getFromLocation(ll[0], ll[1], 1);
                        if (a != null && !a.isEmpty()) {
                            place = a.get(0).getLocality();
                            if (place == null) place = a.get(0).getSubAdminArea();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            synchronized (places) {
                places.put(u, place);
            }
            done.accept(place);
        }, "foto-sitio").start();
    }
}
