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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TV por internet: listas M3U/M3U8 o la JSON de TDTChannels (tv.json) (URL o archivo elegido en el móvil, ver
 * Config.m3uLists; arriba se elige cuál), canales por grupos y reproducción con ExoPlayer (HLS, TS…). Un canal repetido en
 * la lista sale una vez, con sus fuentes de reserva (si la primera falla, la siguiente). Los canales que solo están en
 * YouTube o en una web (los de tv.json) se ven con su reproductor en una WebView.
 * Pendiente (fases finales): bloquear el vídeo con el coche en marcha.
 */
final class IptvScreen implements CarScreen {
    static final class Channel {
        String name;
        String logo;
        String group;
        String url;
        /** Todas sus fuentes, la principal primero; las de YouTube o web llevan delante WEB. */
        final List<String> urls = new ArrayList<>();
    }

    /** Delante de una fuente que no es un vídeo directo (YouTube, Twitch, la web de la cadena). */
    static final String WEB = "web:";
    /**
     * Origen con el que se cargan los reproductores incrustados (YouTube pide uno; Twitch, que coincida con «parent»).
     */
    static final String EMBED_BASE = "https://charlysev.github.io/";
    private static final String EMBED_HOST = "charlysev.github.io";

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z-]+)=\"([^\"]*)\"");
    private static final int COLUMNS = 4;

    private Host host;
    private final List<Channel> all = new ArrayList<>();
    private final List<Channel> shown = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private View listView;
    private ExoPlayer player;
    private MediaFocus mediaFocus;
    private PlaybackMetadata metadata;
    /** Reproductor web (canales de YouTube o de una web), si hay uno abierto. */
    private android.webkit.WebView webPlayer;
    private volatile boolean destroyed;
    /** Las listas guardadas (Config.m3uLists) y la que se ve; arriba se cambia de una a otra si hay varias. */
    private String[][] lists;
    private int sel;
    private int loadSeq;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        Config cfg = new Config(c);
        lists = cfg.m3uLists(Config.IPTV);
        favs = Favorites.load(cfg, Favorites.TV);
        if (lists.length == 0) {
            return CarStyle.message(c, Str.get(R.string.hql_tv_setup_list));
        }
        return load(c, cfg.m3uSelected(Config.IPTV));
    }

    /** Lee la lista i (en segundo plano) y la pone; devuelve la vista de «cargando». */
    private View load(Context c, int i) {
        sel = i;
        int seq = ++loadSeq;
        String src = lists[i][1];
        all.clear();
        shown.clear();
        FrameLayout loading = new FrameLayout(c);
        loading.addView(CarStyle.message(c, Str.get(R.string.hql_tv_loading)), CarStyle.match());
        View first = withLists(c, loading);
        Host h = host;
        new Thread(() -> {
            List<Channel> list;
            String error = null;
            try {
                list = read(open(c, src));
            } catch (Exception e) {
                list = new ArrayList<>();
                error = e.getMessage();
                L.w("IPTV: no se pudo leer la lista: " + e);
            }
            List<Channel> fl = list;
            String fe = error;
            h.post(() -> {
                if (destroyed || seq != loadSeq) return;
                if (fl.isEmpty()) {
                    host.setContent(withLists(c, CarStyle.message(c, fe != null ? Str.get(R.string.hql_tv_read_failed, fe) : Str.get(R.string.hql_tv_empty))));
                    return;
                }
                all.addAll(fl);
                listView = withLists(c, buildList(c));
                host.setContent(listView);
            });
        }, "iptv-load").start();
        return first;
    }

    /** Encima de la vista, una fila con las listas para pasar de una a otra (solo si hay más de una). */
    private View withLists(Context c, View body) {
        if (lists.length < 2) return body;
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        String[] names = new String[lists.length];
        for (int i = 0; i < lists.length; i++) names[i] = lists[i][0];
        hs.addView(CarStyle.tabs(c, names, sel, i -> {
            if (i == sel) return;
            new Config(c).setM3uSelected(Config.IPTV, i);
            L.i("IPTV: lista " + (i + 1) + " de " + lists.length);
            host.setContent(load(c, i));
        }));
        col.addView(hs);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return col;
    }

    @Override
    public void destroy() {
        destroyed = true;
        releasePlayer();
    }

    /** Con un canal puesto, no se corta al pasar de día a noche. */
    @Override
    public boolean keepOnThemeChange() {
        return player != null || webPlayer != null;
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
        // ★ (favoritos, de cualquier lista), Todos y los grupos de la lista.
        names.add(FAV_GROUP);
        names.add(null);
        names.addAll(groups);
        for (String g : names) {
            TextView chip = CarStyle.pill(c, g == null ? Str.get(R.string.hql_all) : FAV_GROUP.equals(g) ? "★" : g);
            chipViews.add(chip);
            chip.setOnClickListener(v -> {
                for (TextView o : chipViews) o.setBackground(CarStyle.round(o == chip ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
                filter(g);
            });
            chips.addView(chip);
        }
        if (chipViews.size() > 1) chipViews.get(1).setBackground(CarStyle.round(CarStyle.ACCENT_BG, 28));
        col.addView(hs);

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
                boolean fav = Favorites.contains(favs, ch.url);
                ((TextView) item.getChildAt(1)).setText(fav ? "★ " + ch.name : ch.name);
                if (ch.logo != null && !ch.logo.isEmpty()) {
                    Glide.with(c.getApplicationContext()).load(ch.logo).into(logo);
                } else {
                    logo.setImageDrawable(null);
                }
                item.setOnClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i >= 0) play(i);
                });
                // Mantener pulsado: entra o sale de favoritos.
                item.setOnLongClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i >= 0) toggleFavorite(c, shown.get(i));
                    return true;
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

    /** Grupo de la pestaña ★ (los favoritos, Favorites.TV). */
    static final String FAV_GROUP = "\u0000fav";
    private JSONArray favs = new JSONArray();
    private String currentGroup;

    @SuppressWarnings("NotifyDataSetChanged")
    private void filter(String group) {
        currentGroup = group;
        shown.clear();
        if (FAV_GROUP.equals(group)) {
            for (int i = 0; i < favs.length(); i++) {
                JSONObject o = favs.optJSONObject(i);
                if (o == null) continue;
                Channel ch = new Channel();
                ch.name = o.optString("name");
                ch.logo = o.optString("logo");
                ch.group = o.optString("group");
                ch.urls.addAll(Favorites.urls(o));
                ch.url = ch.urls.get(0);
                shown.add(ch);
            }
        } else {
            for (Channel ch : all) if (group == null || group.equals(ch.group)) shown.add(ch);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void toggleFavorite(Context c, Channel ch) {
        boolean in = Favorites.toggle(favs, Favorites.item(ch.name, ch.url, ch.logo, ch.group, ch.urls));
        Favorites.save(new Config(c), Favorites.TV, favs);
        L.i("TV: " + (in ? "favorito añadido" : "favorito quitado") + " (" + favs.length() + ")");
        if (FAV_GROUP.equals(currentGroup)) filter(currentGroup);
        else if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void play(int index) {
        play(index, 0);
    }

    /** source: cuál de sus fuentes (ch.urls); si falla, la siguiente. */
    private void play(int index, int source) {
        Context c = host.context();
        Channel ch = shown.get(index);
        releasePlayer();
        String src = ch.urls.isEmpty() ? ch.url : ch.urls.get(Math.min(source, ch.urls.size() - 1));
        if (src.startsWith(WEB)) {
            playWeb(index, source, src.substring(WEB.length()));
            return;
        }
        FrameLayout f = new FrameLayout(c);
        f.setBackgroundColor(Color.BLACK);
        player = new ExoPlayer.Builder(c.getApplicationContext()).build();
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), false);
        mediaFocus = MediaFocus.bind(c, player);
        metadata = new PlaybackMetadata(c, player, "TV", () -> {
            player.seekToDefaultPosition();
            player.prepare();
            player.play();
        }, () -> {
            releasePlayer();
            host.setFullscreen(false);
            host.setContent(listView);
        }, () -> play((index - 1 + shown.size()) % shown.size()),
                () -> play((index + 1) % shown.size()));
        metadata.setChannel(ch.name, "TV", ch.logo);
        PlayerView pv = CarStyle.player(c);
        pv.setPlayer(player);
        f.addView(pv, CarStyle.match());
        LinearLayout overlay = new LinearLayout(c);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(16, 16, 16, 8);
        TextView status = CarStyle.text(c, source > 0 ? ch.name + " · " + Str.get(R.string.hql_tv_backup) : ch.name, 26, Color.WHITE);
        status.setShadowLayer(6, 0, 0, Color.BLACK);
        status.setPadding(8, 12, 8, 8);
        status.setSingleLine(true);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);

        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_channels));
        TextView prev = CarStyle.pill(c, Str.get(R.string.hql_channel) + " −");
        TextView next = CarStyle.pill(c, Str.get(R.string.hql_channel) + " +");
        TextView fill = CarStyle.pill(c, Str.get(R.string.hql_tv_fill));
        top.addView(back);
        top.addView(prev);
        top.addView(next);
        top.addView(fill);
        HorizontalScrollView options = new HorizontalScrollView(c);
        options.setHorizontalScrollBarEnabled(false);
        options.addView(top);
        overlay.addView(options);
        overlay.addView(status);
        f.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        TvPlayerControls.bind(c, pv, overlay, fill);
        back.setOnClickListener(v -> {
            releasePlayer();
            host.setFullscreen(false);
            host.setContent(listView);
        });
        prev.setOnClickListener(v -> play((index - 1 + shown.size()) % shown.size()));
        next.setOnClickListener(v -> play((index + 1) % shown.size()));

        ExoPlayer p = player;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
                L.w("IPTV: " + ch.name + ": " + e);
                if (source + 1 < ch.urls.size()) {
                    // La lista trae otra fuente para este canal: se prueba sola.
                    L.i("IPTV: " + ch.name + ": pruebo la fuente " + (source + 2) + " de " + ch.urls.size());
                    host.post(() -> {
                        if (!destroyed && player == p) play(index, source + 1);
                    });
                    return;
                }
                status.setText(ch.name + " · " + Str.get(R.string.hql_cannot_play, e.getErrorCodeName()));
                pv.showController();
            }
        });
        player.setMediaItem(MediaItem.fromUri(src));
        player.prepare();
        player.play();
        host.setContent(f);
        host.setFullscreen(true);
        pv.showController();
    }

    /**
     * Canal de YouTube o de una web (tv.json de TDTChannels): su reproductor en una WebView, con la barra de canales
     * encima (no tapa el vídeo). YouTube y Twitch, incrustados (embedFor); el resto, la página tal cual.
     */
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private void playWeb(int index, int source, String url) {
        Context c = host.context();
        Channel ch = shown.get(index);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Color.BLACK);
        LinearLayout top = new LinearLayout(c);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(16, 10, 16, 10);
        TextView back = CarStyle.pill(c, "‹  " + Str.get(R.string.hql_channels));
        TextView prev = CarStyle.pill(c, Str.get(R.string.hql_channel) + " −");
        TextView next = CarStyle.pill(c, Str.get(R.string.hql_channel) + " +");
        top.addView(back);
        top.addView(prev);
        top.addView(next);
        if (source + 1 < ch.urls.size()) {
            TextView other = CarStyle.pill(c, Str.get(R.string.hql_tv_other_source));
            other.setOnClickListener(v -> play(index, source + 1));
            top.addView(other);
        }
        TextView name = CarStyle.text(c, ch.name, 24, Color.WHITE);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(16, 0, 0, 0);
        top.addView(name);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(top);
        col.addView(hs);
        FrameLayout box = new FrameLayout(c);
        android.webkit.WebView w = new android.webkit.WebView(c);
        w.setBackgroundColor(Color.BLACK);
        android.webkit.WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        w.setWebViewClient(new android.webkit.WebViewClient());
        View[] custom = new View[1];
        w.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Pantalla completa del reproductor: encima de la barra.
                custom[0] = view;
                box.addView(view, CarStyle.match());
            }

            @Override
            public void onHideCustomView() {
                if (custom[0] != null) box.removeView(custom[0]);
                custom[0] = null;
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest r) {
                // Solo el contenido protegido (Widevine, el de las webs de las cadenas); cámara y micrófono, no.
                for (String res : r.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        r.grant(new String[]{res});
                        return;
                    }
                }
                r.deny();
            }
        });
        box.addView(w, CarStyle.match());
        col.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        webPlayer = w;
        back.setOnClickListener(v -> {
            releasePlayer();
            host.setFullscreen(false);
            host.setContent(listView);
        });
        prev.setOnClickListener(v -> play((index - 1 + shown.size()) % shown.size()));
        next.setOnClickListener(v -> play((index + 1) % shown.size()));
        String embed = embedFor(url);
        if (embed != null) w.loadDataWithBaseURL(EMBED_BASE, embedHtml(embed), "text/html", "utf-8", null);
        else w.loadUrl(url);
        L.i("IPTV: " + ch.name + ": reproductor web" + (embed != null ? " (incrustado)" : ""));
        host.setContent(col);
        host.setFullscreen(true);
    }

    private static final Pattern YT_CHANNEL = Pattern.compile("youtube\\.com/channel/([\\w-]+)/live");
    private static final Pattern YT_VIDEO = Pattern.compile("youtube\\.com/(?:live/|watch\\?v=|embed/)([\\w-]{6,})");

    /** Puro: el reproductor incrustado de YouTube (directo de un canal o un vídeo) o de Twitch; null si no lo es. */
    static String embedFor(String url) {
        Matcher m = YT_CHANNEL.matcher(url);
        if (m.find()) return "https://www.youtube.com/embed/live_stream?channel=" + m.group(1) + "&autoplay=1&playsinline=1";
        m = YT_VIDEO.matcher(url);
        if (m.find()) return "https://www.youtube.com/embed/" + m.group(1) + "?autoplay=1&playsinline=1";
        if (url.startsWith("https://player.twitch.tv/") || url.startsWith("http://player.twitch.tv/")) {
            // Twitch solo se deja incrustar si «parent» es el sitio que lo incrusta: el nuestro (EMBED_BASE).
            String u = url.replaceAll("([?&])parent=[^&]*&?", "$1").replaceAll("[?&]$", "");
            return u + (u.contains("?") ? "&" : "?") + "parent=" + EMBED_HOST + "&autoplay=true&muted=false";
        }
        return null;
    }

    /** Puro: página negra con el reproductor incrustado a todo el tamaño. */
    static String embedHtml(String src) {
        String a = src.replace("&", "&amp;").replace("\"", "&quot;");
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}"
                + "</style></head><body><iframe src=\"" + a + "\" allow=\"autoplay; encrypted-media; fullscreen; picture-in-picture\""
                + " allowfullscreen referrerpolicy=\"strict-origin-when-cross-origin\"></iframe></body></html>";
    }

    private void releasePlayer() {
        if (webPlayer != null) {
            android.webkit.WebView w = webPlayer;
            webPlayer = null;
            w.loadUrl("about:blank");
            if (w.getParent() instanceof ViewGroup) ((ViewGroup) w.getParent()).removeView(w);
            w.destroy();
        }
        if (player != null) {
            metadata.close();
            metadata = null;
            mediaFocus.close();
            mediaFocus = null;
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

    /** La lista leída: M3U, o la JSON de TDTChannels (empieza por «{»), con los canales repetidos juntos (merge). */
    static List<Channel> read(InputStream in) throws Exception {
        byte[] b;
        try (InputStream i = in) {
            b = Http.readAll(i);
        }
        String t = new String(b, StandardCharsets.UTF_8).trim();
        if (t.startsWith("\uFEFF")) t = t.substring(1).trim();
        List<Channel> out = t.startsWith("{") ? parseTdtJson(t) : parse(new java.io.ByteArrayInputStream(b));
        return merge(out);
    }

    /**
     * TDTChannels (tv.json): países › ámbitos (el grupo) › canales con sus opciones. Primero los vídeos directos (m3u8),
     * luego YouTube y webs (con WEB delante). Los canales sin ninguna opción (no hay emisión abierta), con la web de su
     * directo si la trae (Mediaset: mitele); si tampoco, fuera.
     */
    static List<Channel> parseTdtJson(String json) throws org.json.JSONException {
        List<Channel> out = new ArrayList<>();
        org.json.JSONArray countries = new org.json.JSONObject(json).optJSONArray("countries");
        if (countries == null) return out;
        for (int i = 0; i < countries.length(); i++) {
            org.json.JSONObject country = countries.optJSONObject(i);
            org.json.JSONArray ambits = country == null ? null : country.optJSONArray("ambits");
            if (ambits == null) continue;
            for (int j = 0; j < ambits.length(); j++) {
                org.json.JSONObject ambit = ambits.optJSONObject(j);
                org.json.JSONArray chans = ambit == null ? null : ambit.optJSONArray("channels");
                if (chans == null) continue;
                String group = ambit.optString("name").trim();
                for (int k = 0; k < chans.length() && out.size() < 20_000; k++) {
                    org.json.JSONObject o = chans.optJSONObject(k);
                    if (o == null) continue;
                    Channel ch = new Channel();
                    ch.name = o.optString("name").trim();
                    ch.logo = o.isNull("logo") ? null : o.optString("logo").trim();
                    ch.group = group;
                    List<String> web = new ArrayList<>();
                    org.json.JSONArray opts = o.optJSONArray("options");
                    for (int n = 0; opts != null && n < opts.length(); n++) {
                        org.json.JSONObject op = opts.optJSONObject(n);
                        if (op == null || op.isNull("url")) continue;
                        String f = op.optString("format");
                        String u = op.optString("url").trim();
                        if (u.isEmpty()) continue;
                        if ("m3u8".equals(f) || looksLikeVideo(u)) ch.urls.add(u);
                        else if ("youtube".equals(f) || "stream".equals(f)) web.add(WEB + u);
                    }
                    ch.urls.addAll(web);
                    // Sin emisión abierta (Mediaset y otras): la web de su directo, si la trae.
                    String site = o.isNull("web") ? "" : o.optString("web").trim();
                    if (ch.urls.isEmpty() && site.startsWith("http")) ch.urls.add(WEB + site);
                    if (ch.name.isEmpty() || ch.urls.isEmpty()) continue;
                    ch.url = ch.urls.get(0);
                    out.add(ch);
                }
            }
        }
        return out;
    }

    /** Puro: un vídeo directo que ExoPlayer puede abrir (por la extensión). */
    static boolean looksLikeVideo(String url) {
        String p = url.toLowerCase(java.util.Locale.ROOT).replaceFirst("[?#].*$", "");
        return p.endsWith(".m3u8") || p.endsWith(".mpd") || p.endsWith(".ts") || p.endsWith(".mp4");
    }

    /** Puro: los canales con el mismo nombre y grupo, uno solo con todas sus fuentes (la primera, la principal). */
    static List<Channel> merge(List<Channel> in) {
        java.util.Map<String, Channel> by = new java.util.LinkedHashMap<>();
        for (Channel ch : in) {
            String key = (ch.group == null ? "" : ch.group) + "\u0000"
                    + (ch.name == null ? "" : ch.name.trim().toLowerCase(java.util.Locale.ROOT));
            Channel first = by.get(key);
            if (first == null) {
                by.put(key, ch);
                continue;
            }
            for (String u : ch.urls) if (!first.urls.contains(u)) first.urls.add(u);
            if ((first.logo == null || first.logo.isEmpty()) && ch.logo != null) first.logo = ch.logo;
        }
        return new ArrayList<>(by.values());
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
                    ch.urls.add(line);
                    out.add(ch);
                    pending = null;
                }
            }
        }
        return out;
    }
}
