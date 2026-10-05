package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TV por internet: lista M3U/M3U8 (URL o archivo elegido en el móvil, ver Config.iptvSource),
 * canales por grupos y reproducción con ExoPlayer (HLS, TS…).
 * Pendiente (fases finales): bloquear el vídeo con el coche en marcha.
 */
final class IptvScreen implements CarScreen {
    static final class Channel {
        String name;
        String logo;
        String group;
        String url;
    }

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");
    private static final int COLUMNS = 4;

    private Host host;
    private final List<Channel> all = new ArrayList<>();
    private final List<Channel> shown = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private View listView;
    private ExoPlayer player;
    private volatile boolean destroyed;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        String src = new Config(c).iptvSource();
        if (src == null || src.isEmpty()) {
            return CarStyle.message(c, Str.get(R.string.hql_tv_setup_list));
        }
        FrameLayout loading = new FrameLayout(c);
        loading.addView(CarStyle.message(c, Str.get(R.string.hql_tv_loading)), CarStyle.match());
        new Thread(() -> {
            List<Channel> list;
            String error = null;
            try {
                list = parse(open(c, src));
            } catch (Exception e) {
                list = new ArrayList<>();
                error = e.getMessage();
                L.w("IPTV: no se pudo leer la lista: " + e);
            }
            List<Channel> fl = list;
            String fe = error;
            h.post(() -> {
                if (destroyed) return;
                if (fl.isEmpty()) {
                    host.setContent(CarStyle.message(c, fe != null ? Str.get(R.string.hql_tv_read_failed, fe) : Str.get(R.string.hql_tv_empty)));
                    return;
                }
                all.addAll(fl);
                listView = buildList(c);
                host.setContent(listView);
            });
        }, "iptv-load").start();
        return loading;
    }

    @Override
    public void destroy() {
        destroyed = true;
        releasePlayer();
    }

    private View buildList(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);

        Set<String> groups = new LinkedHashSet<>();
        for (Channel ch : all) if (ch.group != null && !ch.group.isEmpty()) groups.add(ch.group);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        LinearLayout chips = CarStyle.bar(c);
        hs.addView(chips);
        List<TextView> chipViews = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add(null);
        names.addAll(groups);
        for (String g : names) {
            TextView chip = CarStyle.pill(c, g == null ? Str.get(R.string.hql_all) : g);
            chipViews.add(chip);
            chip.setOnClickListener(v -> {
                for (TextView o : chipViews) o.setBackground(CarStyle.round(o == chip ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
                filter(g);
            });
            chips.addView(chip);
        }
        if (!chipViews.isEmpty()) chipViews.get(0).setBackground(CarStyle.round(CarStyle.ACCENT_BG, 28));
        if (!groups.isEmpty()) col.addView(hs);

        RecyclerView rv = new RecyclerView(c);
        rv.setLayoutManager(new GridLayoutManager(c, COLUMNS));
        int cellW = host.width() / COLUMNS;
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout item = new LinearLayout(parent.getContext());
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(14, 10, 14, 10);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cellW - 12, 110);
                lp.setMargins(6, 6, 6, 6);
                item.setLayoutParams(lp);
                item.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
                ImageView logo = new ImageView(parent.getContext());
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                item.addView(logo, new LinearLayout.LayoutParams(110, 80));
                TextView name = CarStyle.text(parent.getContext(), "", 24, CarStyle.TEXT);
                name.setMaxLines(2);
                name.setPadding(14, 0, 0, 0);
                item.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return new RecyclerView.ViewHolder(item) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                Channel ch = shown.get(pos);
                LinearLayout item = (LinearLayout) vh.itemView;
                ImageView logo = (ImageView) item.getChildAt(0);
                ((TextView) item.getChildAt(1)).setText(ch.name);
                if (ch.logo != null && !ch.logo.isEmpty()) {
                    Glide.with(c.getApplicationContext()).load(ch.logo).into(logo);
                } else {
                    logo.setImageDrawable(null);
                }
                item.setOnClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i >= 0) play(i);
                });
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        rv.setAdapter(adapter);
        col.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        filter(null);
        return col;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void filter(String group) {
        shown.clear();
        for (Channel ch : all) if (group == null || group.equals(ch.group)) shown.add(ch);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void play(int index) {
        Context c = host.context();
        Channel ch = shown.get(index);
        releasePlayer();
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        player = new ExoPlayer.Builder(c.getApplicationContext()).build();
        PlayerView pv = new PlayerView(c);
        pv.setPlayer(player);
        f.addView(pv, CarStyle.match());
        TextView status = CarStyle.text(c, ch.name, 26, CarStyle.TEXT);
        status.setShadowLayer(6, 0, 0, Color.BLACK);
        status.setPadding(20, 24, 20, 20);
        f.addView(status, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setPadding(16, 16, 16, 16);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_channels));
        TextView prev = CarStyle.pill(c, Str.get(R.string.hql_channel) + " −");
        TextView next = CarStyle.pill(c, Str.get(R.string.hql_channel) + " +");
        top.addView(back);
        top.addView(prev);
        top.addView(next);
        f.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        back.setOnClickListener(v -> {
            releasePlayer();
            host.setFullscreen(false);
            host.setContent(listView);
        });
        prev.setOnClickListener(v -> play((index - 1 + shown.size()) % shown.size()));
        next.setOnClickListener(v -> play((index + 1) % shown.size()));

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                status.setText(ch.name + " · " + Str.get(R.string.hql_cannot_play, e.getErrorCodeName()));
                L.w("IPTV: " + ch.name + ": " + e);
            }
        });
        player.setMediaItem(MediaItem.fromUri(ch.url));
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

    static InputStream open(Context c, String src) throws Exception {
        if (src.startsWith("content://") || src.startsWith("file://")) {
            InputStream in = c.getContentResolver().openInputStream(Uri.parse(src));
            if (in == null) throw new IllegalStateException("archivo no disponible");
            return in;
        }
        HttpURLConnection con = (HttpURLConnection) new URL(src).openConnection();
        con.setConnectTimeout(15_000);
        con.setReadTimeout(30_000);
        con.setInstanceFollowRedirects(true);
        if (con.getResponseCode() >= 400) throw new IllegalStateException("HTTP " + con.getResponseCode());
        return con.getInputStream();
    }

    /** M3U extendido: #EXTINF:-1 tvg-logo="…" group-title="…",Nombre y en la línea siguiente la URL. */
    static List<Channel> parse(InputStream in) throws Exception {
        List<Channel> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Channel pending = null;
            String line;
            while ((line = r.readLine()) != null && out.size() < 20_000) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    pending = new Channel();
                    int comma = line.lastIndexOf(',');
                    pending.name = comma >= 0 ? line.substring(comma + 1).trim() : Str.get(R.string.hql_channel);
                    Matcher m = ATTR.matcher(comma >= 0 ? line.substring(0, comma) : line);
                    while (m.find()) {
                        if ("tvg-logo".equals(m.group(1))) pending.logo = m.group(2);
                        else if ("group-title".equals(m.group(1))) pending.group = m.group(2);
                    }
                } else if (!line.startsWith("#")) {
                    Channel ch = pending != null ? pending : new Channel();
                    if (ch.name == null) ch.name = line;
                    ch.url = line;
                    out.add(ch);
                    pending = null;
                }
            }
        }
        return out;
    }
}
