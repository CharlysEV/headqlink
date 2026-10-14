package com.headqlink.link;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.andrerinas.openheadunit.R;
import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Radio por internet: emisoras populares del país (directorio abierto radio-browser.info) o la
 * lista M3U del usuario (Config.radioSource, URL o archivo). Lo que suena sigue al salir de aquí
 * (RadioPlayer); arriba, la emisora actual con lo que emite y los controles.
 */
final class RadioScreen implements CarScreen {
    /** Servidores del directorio; "all" reparte entre ellos y los demás son el respaldo. */
    private static final String[] DIRECTORY = {"all.api.radio-browser.info", "de1.api.radio-browser.info",
            "fi1.api.radio-browser.info", "nl1.api.radio-browser.info"};
    private static final int COLUMNS = 4;

    private Host host;
    private final List<RadioPlayer.Station> shown = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
    private FrameLayout listArea;
    private ImageView npLogo;
    private TextView npName;
    private TextView npTitle;
    private ImageView npToggle;
    private volatile boolean destroyed;
    private int loadSeq;
    /** Las listas M3U del usuario (Config.m3uLists): la pestaña 1 + i es la lista i. */
    private String[][] lists = new String[0][];
    private final Runnable onChange = this::renderNowPlaying;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(nowPlaying(c));
        // Pestañas: las populares y cada lista del usuario (o «Mi lista» si no tiene ninguna).
        Config cfg = new Config(c);
        lists = cfg.m3uLists(Config.RADIO);
        boolean hasList = lists.length > 0;
        favs = Favorites.load(cfg, Favorites.RADIO);
        // ★ (favoritos), Populares y cada lista (o «Mi lista» si no hay ninguna): la pestaña i es la fuente i - 1.
        String[] tabs = new String[2 + Math.max(1, lists.length)];
        tabs[0] = "★";
        tabs[1] = Str.get(R.string.hql_radio_popular);
        if (hasList) for (int i = 0; i < lists.length; i++) tabs[2 + i] = lists[i][0];
        else tabs[2] = Str.get(R.string.hql_radio_my_list);
        int first = hasList ? 2 + cfg.m3uSelected(Config.RADIO) : 1;
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(CarStyle.tabs(c, tabs, first, i -> load(i - 1)));
        col.addView(hs);
        listArea = new FrameLayout(c);
        col.addView(listArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        RadioPlayer.addListener(onChange);
        renderNowPlaying();
        load(first - 1);
        return col;
    }

    @Override
    public void destroy() {
        destroyed = true;
        RadioPlayer.removeListener(onChange);
    }

    private View nowPlaying(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(24, 18, 24, 18);
        card.setBackground(CarStyle.round(CarStyle.CARD, 28));
        npLogo = new ImageView(c);
        npLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        npLogo.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
        npLogo.setPadding(8, 8, 8, 8);
        card.addView(npLogo, new LinearLayout.LayoutParams(96, 96));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(22, 0, 12, 0);
        npName = CarStyle.text(c, "", 30, CarStyle.TEXT);
        npName.setSingleLine(true);
        npTitle = CarStyle.text(c, "", 22, CarStyle.TEXT_DIM);
        npTitle.setSingleLine(true);
        texts.addView(npName);
        texts.addView(npTitle);
        card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Iconos de Material, no caracteres: ⏸ y similares salen como emoji de color.
        ImageView prev = control(c, R.drawable.hql_ic_prev);
        npToggle = control(c, R.drawable.hql_ic_play);
        ImageView next = control(c, R.drawable.hql_ic_next);
        ImageView stop = control(c, R.drawable.hql_ic_stop);
        for (ImageView t : new ImageView[]{prev, npToggle, next, stop}) card.addView(t);
        prev.setOnClickListener(v -> RadioPlayer.step(c, -1));
        next.setOnClickListener(v -> RadioPlayer.step(c, 1));
        npToggle.setOnClickListener(v -> RadioPlayer.toggle());
        stop.setOnClickListener(v -> RadioPlayer.stop());
        FrameLayout wrap = new FrameLayout(c);
        wrap.setPadding(24, 18, 24, 0);
        wrap.addView(card);
        return wrap;
    }

    private static ImageView control(Context c, int icon) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(CarStyle.TEXT));
        b.setBackground(CarStyle.round(CarStyle.PILL_BG, 30));
        b.setPadding(20, 20, 20, 20);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CarStyle.touch(84), CarStyle.touch(72));
        lp.leftMargin = 10;
        b.setLayoutParams(lp);
        return b;
    }

    private void renderNowPlaying() {
        if (destroyed || npName == null) return;
        RadioPlayer.Station s = RadioPlayer.current();
        if (s == null) {
            npName.setText(Str.get(R.string.hql_radio));
            npTitle.setText(Str.get(R.string.hql_radio_pick));
            npLogo.setImageResource(R.drawable.hql_ic_radio);
            npToggle.setImageResource(R.drawable.hql_ic_play);
            return;
        }
        npName.setText(s.name);
        String sub = !RadioPlayer.error.isEmpty() ? RadioPlayer.error
                : RadioPlayer.isBuffering() ? Str.get(R.string.hql_connecting)
                : !RadioPlayer.nowTitle.isEmpty() ? RadioPlayer.nowTitle
                : RadioPlayer.isPlaying() ? Str.get(R.string.hql_radio_live) : Str.get(R.string.hql_paused);
        npTitle.setText(sub);
        npToggle.setImageResource(RadioPlayer.isPlaying() ? R.drawable.hql_ic_pause : R.drawable.hql_ic_play);
        if (s.logo != null && !s.logo.isEmpty()) {
            Glide.with(host.context().getApplicationContext()).load(s.logo).error(R.drawable.hql_ic_radio).into(npLogo);
        } else {
            npLogo.setImageResource(R.drawable.hql_ic_radio);
        }
    }

    private JSONArray favs = new JSONArray();
    private int currentSource;

    /** -1: favoritos; 0: populares del directorio; 1 + i: la lista M3U i del usuario (queda elegida). */
    private void load(int source) {
        Context c = host.context();
        int seq = ++loadSeq;
        currentSource = source;
        if (source < 0) {
            shown.clear();
            for (int i = 0; i < favs.length(); i++) {
                JSONObject o = favs.optJSONObject(i);
                if (o == null) continue;
                RadioPlayer.Station s = new RadioPlayer.Station();
                s.name = o.optString("name");
                s.url = o.optString("url");
                s.logo = o.optString("logo");
                s.group = o.optString("group");
                shown.add(s);
            }
            if (shown.isEmpty()) showMessage(Str.get(R.string.hql_fav_none));
            else showGrid(c);
            return;
        }
        if (source >= 1 && lists.length == 0) {
            showMessage(Str.get(R.string.hql_radio_setup_list));
            return;
        }
        String src = source >= 1 ? lists[source - 1][1] : "";
        if (source >= 1) new Config(c).setM3uSelected(Config.RADIO, source - 1);
        showMessage(source == 0 ? Str.get(R.string.hql_radio_loading_popular) : Str.get(R.string.hql_radio_loading_list));
        new Thread(() -> {
            List<RadioPlayer.Station> list = new ArrayList<>();
            String error = null;
            try {
                list = source == 0 ? popular() : fromM3u(c, src);
            } catch (Exception e) {
                error = e.getMessage();
                L.w("radio: no se pudo cargar la lista: " + e);
            }
            List<RadioPlayer.Station> fl = list;
            String fe = error;
            host.post(() -> {
                if (destroyed || seq != loadSeq) return;
                if (fl.isEmpty()) {
                    showMessage(fe != null ? Str.get(R.string.hql_load_failed, fe) : Str.get(R.string.hql_radio_none));
                    return;
                }
                shown.clear();
                shown.addAll(fl);
                showGrid(c);
            });
        }, "radio-load").start();
    }

    private void showMessage(String s) {
        listArea.removeAllViews();
        listArea.addView(CarStyle.message(host.context(), s), CarStyle.match());
    }

    private static List<RadioPlayer.Station> popular() throws Exception {
        String cc = Locale.getDefault().getCountry();
        if (cc == null || cc.length() != 2) cc = "ES";
        String q = "/json/stations/search?countrycode=" + URLEncoder.encode(cc, "UTF-8")
                + "&order=clickcount&reverse=true&hidebroken=true&limit=150";
        Exception last = null;
        for (String host : DIRECTORY) {
            try {
                JSONArray a = new JSONArray(Http.get("https://" + host + q));
                List<RadioPlayer.Station> out = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.getJSONObject(i);
                    RadioPlayer.Station s = new RadioPlayer.Station();
                    s.name = o.optString("name").trim();
                    s.url = o.optString("url_resolved", o.optString("url"));
                    s.logo = o.optString("favicon");
                    s.group = o.optString("tags");
                    if (!s.name.isEmpty() && !s.url.isEmpty()) out.add(s);
                }
                return out;
            } catch (Exception e) {
                last = e;
                L.w("radio: directorio " + host + ": " + e.getMessage());
            }
        }
        throw last != null ? last : new IllegalStateException("sin directorio");
    }

    private static List<RadioPlayer.Station> fromM3u(Context c, String src) throws Exception {
        List<RadioPlayer.Station> out = new ArrayList<>();
        for (IptvScreen.Channel ch : IptvScreen.parse(IptvScreen.open(c, src))) {
            RadioPlayer.Station s = new RadioPlayer.Station();
            s.name = ch.name;
            s.url = ch.url;
            s.logo = ch.logo;
            s.group = ch.group;
            out.add(s);
        }
        return out;
    }

    private void showGrid(Context c) {
        RecyclerView rv = new RecyclerView(c);
        rv.setPadding(18, 6, 18, 6);
        // En una pantalla pequeña (T03), menos columnas: celdas más anchas para el texto más grande.
        int cols = CarStyle.gridColumns(COLUMNS, host.width() - 36, CarStyle.MIN_CELL_SCREEN_PX);
        rv.setLayoutManager(new GridLayoutManager(c, cols));
        int cellW = (host.width() - 36) / cols;
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                LinearLayout item = new LinearLayout(parent.getContext());
                item.setOrientation(LinearLayout.HORIZONTAL);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(14, 10, 14, 10);
                RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(cellW - 12, 104);
                lp.setMargins(6, 6, 6, 6);
                item.setLayoutParams(lp);
                item.setBackground(CarStyle.round(CarStyle.ITEM_BG, 16));
                ImageView logo = new ImageView(parent.getContext());
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                item.addView(logo, new LinearLayout.LayoutParams(76, 76));
                TextView name = CarStyle.text(parent.getContext(), "", 24, CarStyle.TEXT);
                name.setMaxLines(2);
                name.setPadding(16, 0, 0, 0);
                item.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                return new RecyclerView.ViewHolder(item) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                RadioPlayer.Station s = shown.get(pos);
                LinearLayout item = (LinearLayout) vh.itemView;
                ImageView logo = (ImageView) item.getChildAt(0);
                ((TextView) item.getChildAt(1)).setText(Favorites.contains(favs, s.url) ? "★ " + s.name : s.name);
                if (s.logo != null && !s.logo.isEmpty()) {
                    Glide.with(c.getApplicationContext()).load(s.logo).error(R.drawable.hql_ic_radio).into(logo);
                } else {
                    logo.setImageResource(R.drawable.hql_ic_radio);
                }
                item.setOnClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i >= 0) RadioPlayer.play(c, shown, i);
                });
                // Mantener pulsado: entra o sale de favoritos.
                item.setOnLongClickListener(v -> {
                    int i = vh.getBindingAdapterPosition();
                    if (i < 0) return true;
                    RadioPlayer.Station st = shown.get(i);
                    boolean in = Favorites.toggle(favs, Favorites.item(st.name, st.url, st.logo, st.group, null));
                    Favorites.save(new Config(c), Favorites.RADIO, favs);
                    L.i("radio: " + (in ? "favorita añadida" : "favorita quitada") + " (" + favs.length() + ")");
                    if (currentSource < 0) load(-1);
                    else if (adapter != null) adapter.notifyDataSetChanged();
                    return true;
                });
            }

            @Override
            public int getItemCount() {
                return shown.size();
            }
        };
        rv.setAdapter(adapter);
        listArea.removeAllViews();
        listArea.addView(rv, CarStyle.match());
    }
}
