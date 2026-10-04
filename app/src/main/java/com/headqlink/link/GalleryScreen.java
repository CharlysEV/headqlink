package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;

/** Fotos o vídeos del móvil: cuadrícula, visor de fotos y reproductor. */
final class GalleryScreen implements CarScreen {
    private static final int COLUMNS = 5;

    private final boolean videos;
    private final List<Uri> items = new ArrayList<>();
    private Host host;
    private View grid;
    private ExoPlayer player;

    GalleryScreen(boolean videos) {
        this.videos = videos;
    }

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        String perm = videos ? android.Manifest.permission.READ_MEDIA_VIDEO : android.Manifest.permission.READ_MEDIA_IMAGES;
        if (c.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            return CarStyle.message(c, videos ? Str.get(R.string.hql_gallery_perm_videos) : Str.get(R.string.hql_gallery_perm_photos));
        }
        items.addAll(query(c));
        if (items.isEmpty()) return CarStyle.message(c, videos ? Str.get(R.string.hql_gallery_no_videos) : Str.get(R.string.hql_gallery_no_photos));
        grid = buildGrid(c);
        return grid;
    }

    @Override
    public void destroy() {
        releasePlayer();
    }

    private View buildGrid(Context c) {
        RecyclerView rv = new RecyclerView(c);
        rv.setLayoutManager(new GridLayoutManager(c, COLUMNS));
        int cell = host.width() / COLUMNS;
        rv.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                FrameLayout f = new FrameLayout(parent.getContext());
                f.setLayoutParams(new RecyclerView.LayoutParams(cell, cell));
                ImageView iv = new ImageView(parent.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(CarStyle.ITEM_BG);
                f.addView(iv, new FrameLayout.LayoutParams(cell - 6, cell - 6, Gravity.CENTER));
                if (videos) {
                    TextView play = CarStyle.text(parent.getContext(), "▶", 44, Color.WHITE);
                    play.setShadowLayer(6, 0, 0, Color.BLACK);
                    f.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                }
                return new RecyclerView.ViewHolder(f) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                ImageView iv = (ImageView) ((FrameLayout) vh.itemView).getChildAt(0);
                Glide.with(c.getApplicationContext()).load(items.get(pos)).override(cell, cell).centerCrop().into(iv);
                vh.itemView.setOnClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i < 0) return;
                    if (videos) openVideo(i);
                    else openPhoto(i);
                });
            }

            @Override
            public int getItemCount() {
                return items.size();
            }
        });
        return rv;
    }

    private void openPhoto(int index) {
        Context c = host.context();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(iv, CarStyle.match());
        Glide.with(c.getApplicationContext()).load(items.get(index)).override(host.width(), host.height()).fitCenter().into(iv);
        // Tercio izquierdo: anterior; tercio derecho: siguiente.
        iv.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                float x = ev.getX();
                if (x < v.getWidth() / 3f && index > 0) openPhoto(index - 1);
                else if (x > v.getWidth() * 2 / 3f && index < items.size() - 1) openPhoto(index + 1);
            }
            return true;
        });
        f.addView(CarStyle.back(c, () -> host.setContent(grid)));
        TextView pos = CarStyle.text(c, (index + 1) + " / " + items.size(), 22, CarStyle.TEXT);
        pos.setPadding(16, 16, 16, 16);
        f.addView(pos, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        host.setContent(f);
    }

    private void openVideo(int index) {
        Context c = host.context();
        releasePlayer();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        player = new ExoPlayer.Builder(c.getApplicationContext()).build();
        PlayerView pv = new PlayerView(c);
        pv.setPlayer(player);
        f.addView(pv, CarStyle.match());
        f.addView(CarStyle.back(c, () -> {
            releasePlayer();
            host.setFullscreen(false);
            host.setContent(grid);
        }));
        player.setMediaItem(MediaItem.fromUri(items.get(index)));
        player.prepare();
        player.play();
        host.setContent(f);
        host.setFullscreen(true);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private List<Uri> query(Context c) {
        Uri base = videos ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        List<Uri> out = new ArrayList<>();
        try (Cursor cur = c.getContentResolver().query(base, new String[]{MediaStore.MediaColumns._ID},
                null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < 2000) out.add(ContentUris.withAppendedId(base, cur.getLong(0)));
        } catch (RuntimeException e) {
            L.w("galería: " + e);
        }
        return out;
    }
}
