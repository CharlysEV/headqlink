package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Fotos o vídeos del móvil en el coche: pastillas de álbum (cámara, WhatsApp, descargas, favoritos de la galería,
 * capturas, USB, la carpeta de vídeos de la app y el resto de carpetas), cuadrícula por días con una barra para saltar
 * por meses, el visor de fotos con pase de diapositivas (PhotoViewer) y el reproductor (VideoPlayer). En los vídeos, la
 * duración y lo visto en cada miniatura (MediaResume).
 */
final class GalleryScreen implements CarScreen {
    private static final int COLUMNS = 5;
    private static final int SCRUBBER_W = 76;

    private final boolean videos;
    private final List<MediaLibrary.Item> all = new ArrayList<>();
    private final List<MediaLibrary.Item> shown = new ArrayList<>();
    private String album = MediaLibrary.ALL;
    private Host host;
    private View root;
    private RecyclerView grid;
    private int gridScroll;
    private VideoPlayer player;

    GalleryScreen(boolean videos) {
        this.videos = videos;
    }

    /** Con un vídeo puesto, no se corta al pasar de día a noche. */
    @Override
    public boolean keepOnThemeChange() {
        return player != null && player.playing();
    }

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        String perm = videos ? android.Manifest.permission.READ_MEDIA_VIDEO : android.Manifest.permission.READ_MEDIA_IMAGES;
        boolean granted = c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
        if (granted) all.addAll(MediaLibrary.query(c, videos));
        if (videos) {
            List<MediaLibrary.Item> mine = MediaLibrary.folder(c, new Config(c).videoTree());
            all.addAll(mine);
            all.sort((a, b) -> Long.compare(b.dateMs, a.dateMs));
        }
        if (all.isEmpty()) {
            if (!granted) return CarStyle.message(c, videos ? Str.get(R.string.hql_gallery_perm_videos) : Str.get(R.string.hql_gallery_perm_photos));
            return CarStyle.message(c, videos ? Str.get(R.string.hql_gallery_no_videos) : Str.get(R.string.hql_gallery_no_photos));
        }
        L.i("galería: " + all.size() + (videos ? " vídeos" : " fotos"));
        root = build(c);
        return root;
    }

    @Override
    public void destroy() {
        if (player != null) player.release();
        player = null;
    }

    private String albumName(MediaLibrary.Album a) {
        if (a.name != null) return a.name;
        switch (a.key) {
            case MediaLibrary.CAMERA: return Str.get(R.string.hql_gal_camera);
            case MediaLibrary.WHATSAPP: return "WhatsApp";
            case MediaLibrary.DOWNLOAD: return Str.get(R.string.hql_gal_downloads);
            case MediaLibrary.FAV: return "★ " + Str.get(R.string.hql_gal_favorites);
            case MediaLibrary.SCREENSHOTS: return Str.get(R.string.hql_gal_screenshots);
            case MediaLibrary.USB: return "USB";
            case MediaLibrary.FOLDER: return Str.get(R.string.hql_gal_my_folder);
            default: return Str.get(R.string.hql_gal_all);
        }
    }

    private View build(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        // Arriba: los álbumes y, en fotos, el pase de diapositivas.
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(16, 12, 16, 8);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout pills = new LinearLayout(c);
        hs.addView(pills);
        List<MediaLibrary.Album> albums = MediaLibrary.albums(all);
        List<TextView> views = new ArrayList<>();
        for (MediaLibrary.Album a : albums) {
            TextView p = CarStyle.pill(c, albumName(a) + "  " + a.count);
            views.add(p);
            p.setOnClickListener(v -> {
                album = a.key;
                for (int k = 0; k < views.size(); k++) stylePill(views.get(k), albums.get(k).key.equals(album));
                fill(c);
                L.i("galería: álbum " + (a.key.startsWith(MediaLibrary.BUCKET) ? "de carpeta" : a.key) + " (" + a.count + ")");
            });
            stylePill(p, a.key.equals(album));
            pills.addView(p);
        }
        top.addView(hs, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!videos) {
            TextView show = CarStyle.pill(c, "▶  " + Str.get(R.string.hql_gal_slideshow));
            show.setBackground(CarStyle.accent(28));
            show.setTextColor(CarStyle.ON_ACCENT);
            show.setOnClickListener(v -> {
                if (!shown.isEmpty()) viewer().slideshow(0);
            });
            top.addView(show);
        }
        col.addView(top);
        FrameLayout body = new FrameLayout(c);
        grid = new RecyclerView(c);
        FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gl.rightMargin = SCRUBBER_W;
        body.addView(grid, gl);
        // Al cambiar el ancho (el panel se recoge o se despliega), las celdas se rehacen a su medida.
        grid.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol && or - ol > 0) grid.post(() -> {
                if (grid.getAdapter() != null) grid.getAdapter().notifyDataSetChanged();
            });
        });
        scrubber = new MonthScrubber(c);
        body.addView(scrubber, new FrameLayout.LayoutParams(SCRUBBER_W, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END));
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        fill(c);
        return col;
    }

    private void stylePill(TextView t, boolean on) {
        t.setBackground(on ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
        t.setTextColor(on ? CarStyle.ON_ACCENT : CarStyle.TEXT);
    }

    private MonthScrubber scrubber;
    private List<int[]> rows = new ArrayList<>();
    private Map<String, MediaResume.Mark> marks;

    /** La cuadrícula del álbum elegido, por días. */
    private void fill(Context c) {
        shown.clear();
        for (MediaLibrary.Item it : all) if (MediaLibrary.inAlbum(it, album)) shown.add(it);
        TimeZone tz = TimeZone.getDefault();
        rows = MediaLibrary.sections(shown, tz);
        marks = videos ? MediaResume.all(c) : null;
        int[] cellSize = {Math.max(1, (host.width() - SCRUBBER_W) / COLUMNS)};
        GridLayoutManager lm = new GridLayoutManager(c, COLUMNS);
        lm.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int pos) {
                return rows.get(pos)[0] >= 0 ? COLUMNS : 1;
            }
        });
        grid.setLayoutManager(lm);
        long now = System.currentTimeMillis();
        SimpleDateFormat dayFmt = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),
                "EEEEdMMMM"), Locale.getDefault());
        SimpleDateFormat dayYearFmt = new SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),
                "dMMMMyyyy"), Locale.getDefault());
        int thisYear = MediaLibrary.dayKey(now, tz) / 10000;
        grid.setAdapter(new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @Override
            public int getItemViewType(int pos) {
                return rows.get(pos)[0] >= 0 ? 0 : 1;
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
                Context pc = parent.getContext();
                if (parent.getWidth() > 0) cellSize[0] = parent.getWidth() / COLUMNS;
                int cell = cellSize[0];
                if (type == 0) {
                    TextView t = CarStyle.text(pc, "", 24, CarTheme.navText());
                    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    t.setPadding(12, 22, 12, 10);
                    t.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    return new RecyclerView.ViewHolder(t) {
                    };
                }
                FrameLayout f = new FrameLayout(pc);
                f.setLayoutParams(new RecyclerView.LayoutParams(cell, cell));
                ImageView iv = new ImageView(pc);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackgroundColor(CarStyle.ITEM_BG);
                f.addView(iv, new FrameLayout.LayoutParams(cell - 6, cell - 6, Gravity.CENTER));
                if (videos) {
                    TextView play = CarStyle.text(pc, "▶", 40, Color.WHITE);
                    play.setShadowLayer(6, 0, 0, Color.BLACK);
                    f.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                    TextView dur = CarStyle.text(pc, "", 18, Color.WHITE);
                    dur.setPadding(8, 2, 8, 2);
                    dur.setBackground(CarStyle.round(0xAA000000, 10));
                    FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                    dl.setMargins(0, 0, 10, 16);
                    f.addView(dur, dl);
                    // Lo visto: una barra abajo; visto entero, ✓ arriba.
                    View bar = new View(pc);
                    bar.setBackgroundColor(CarStyle.ACCENT);
                    f.addView(bar, new FrameLayout.LayoutParams(0, 8, Gravity.BOTTOM | Gravity.START));
                    TextView done = CarStyle.text(pc, "✓", 22, CarStyle.ON_ACCENT);
                    done.setGravity(Gravity.CENTER);
                    done.setBackground(CarStyle.accent(20));
                    FrameLayout.LayoutParams okl = new FrameLayout.LayoutParams(40, 40, Gravity.TOP | Gravity.END);
                    okl.setMargins(0, 10, 10, 0);
                    f.addView(done, okl);
                }
                return new RecyclerView.ViewHolder(f) {
                };
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder vh, int pos) {
                int[] r = rows.get(pos);
                if (r[0] >= 0) {
                    ((TextView) vh.itemView).setText(dayLabel(r[0], now, tz, thisYear, dayFmt, dayYearFmt));
                    return;
                }
                MediaLibrary.Item it = shown.get(r[1]);
                FrameLayout f = (FrameLayout) vh.itemView;
                if (grid.getWidth() > 0) cellSize[0] = grid.getWidth() / COLUMNS;
                int cell = cellSize[0];
                if (f.getLayoutParams().width != cell) {
                    f.getLayoutParams().width = cell;
                    f.getLayoutParams().height = cell;
                    View img = f.getChildAt(0);
                    img.getLayoutParams().width = cell - 6;
                    img.getLayoutParams().height = cell - 6;
                    f.requestLayout();
                }
                ImageView iv = (ImageView) f.getChildAt(0);
                Glide.with(c.getApplicationContext()).load(it.uri).override(cell, cell).centerCrop().into(iv);
                if (videos) {
                    TextView dur = (TextView) f.getChildAt(2);
                    dur.setText(it.durMs > 0 ? MediaResume.clock(it.durMs) : "");
                    dur.setVisibility(it.durMs > 0 ? View.VISIBLE : View.GONE);
                    MediaResume.Mark m = marks == null ? null : marks.get(it.uri.toString());
                    View bar = f.getChildAt(3);
                    FrameLayout.LayoutParams bl = (FrameLayout.LayoutParams) bar.getLayoutParams();
                    bl.width = m != null && !m.done() ? Math.round((cell - 6) * m.progress()) : 0;
                    bar.setLayoutParams(bl);
                    f.getChildAt(4).setVisibility(m != null && m.done() ? View.VISIBLE : View.GONE);
                }
                vh.itemView.setOnClickListener(v -> {
                    gridScroll = lm.findFirstVisibleItemPosition();
                    int i = rows.get(vh.getBindingAdapterPosition())[1];
                    if (i < 0) return;
                    if (videos) {
                        player = new VideoPlayer(host, shown, GalleryScreen.this::backToGrid);
                        player.open(i);
                    } else {
                        viewer().open(i);
                    }
                });
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
        // La barra de meses.
        List<int[]> months = MediaLibrary.months(rows);
        List<String> labels = new ArrayList<>();
        boolean[] years = new boolean[months.size()];
        String[] shortMonths = new DateFormatSymbols(Locale.getDefault()).getShortMonths();
        int prevYear = -1;
        for (int k = 0; k < months.size(); k++) {
            int ym = months.get(k)[0];
            int y = ym / 100;
            years[k] = y != prevYear;
            labels.add(years[k] && y != thisYear ? String.valueOf(y) : shortMonths[Math.max(0, Math.min(11, ym % 100 - 1))].replace(".", ""));
            prevYear = y;
        }
        scrubber.set(labels, years, k -> lm.scrollToPositionWithOffset(months.get(k)[1], 0));
        scrubber.setVisibility(months.size() > 1 ? View.VISIBLE : View.GONE);
        grid.clearOnScrollListeners();
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                int first = lm.findFirstVisibleItemPosition();
                int k = 0;
                for (int j = 0; j < months.size(); j++) if (months.get(j)[1] <= first) k = j;
                scrubber.setCurrent(k);
            }
        });
        scrubber.setCurrent(0);
    }

    /** «Hoy», «Ayer», «martes, 12 de octubre» o, de otro año, «12 de octubre de 2025». */
    private static String dayLabel(int day, long now, TimeZone tz, int thisYear, SimpleDateFormat dayFmt, SimpleDateFormat dayYearFmt) {
        int rel = MediaLibrary.relativeDay(day, now, tz);
        if (rel == 0) return Str.get(R.string.hql_gal_today);
        if (rel == 1) return Str.get(R.string.hql_gal_yesterday);
        Calendar cal = Calendar.getInstance(tz);
        cal.clear();
        cal.set(day / 10000, (day / 100) % 100 - 1, day % 100, 12, 0);
        String s = (day / 10000 == thisYear ? dayFmt : dayYearFmt).format(cal.getTime());
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private PhotoViewer viewer() {
        return new PhotoViewer(host, shown, this::backToGrid);
    }

    /** Del visor o del reproductor, a la cuadrícula donde estaba (con lo visto al día). */
    private void backToGrid() {
        player = null;
        host.setFullscreen(false);
        host.setContent(root);
        if (videos) {
            marks = MediaResume.all(host.context());
            if (grid.getAdapter() != null) grid.getAdapter().notifyDataSetChanged();
        }
        if (grid.getLayoutManager() != null) grid.getLayoutManager().scrollToPosition(Math.max(0, gridScroll));
    }
}
