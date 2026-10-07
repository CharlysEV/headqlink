package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.andrerinas.openheadunit.aap.NavTap;

import java.util.Locale;

/**
 * Pestaña "Ruta": destino y llegada (de Android Auto o elegido aquí), fichas con lo que falta, la hora de llegada, la
 * energía y el consumo previstos, y el perfil de elevación de la ruta coloreado por la pendiente (subidas en ámbar y
 * rojo, bajadas en verde: ahí se recupera), con el viento por tramos, los cargadores, la batería prevista y «estás
 * aquí»; debajo, el consumo de cada tramo. A la derecha, la batería al llegar en un anillo (con el % actual que indica
 * el usuario, ±5), el tiempo en el destino a la hora de llegada y los cargadores junto a la ruta («Ir» abre la
 * navegación de Google Maps en el móvil y AA la muestra). Todo estimado: ver RoutePlanner y EnergyModel. Con la cuenta
 * de Leapmotor (CarCloud), el % de ahora es el REAL del coche (sin ±5, con la edad del dato) y la batería al llegar
 * sale de él con la capacidad de la variante elegida. Bajo las fichas, de dónde sale la previsión: lo que suben y bajan
 * los km que faltan (y lo que cuestan), la media según el propio coche y, al llegar, lo previsto frente a lo real. En un
 * REEV la batería no baja del 20 %: lo que falta lo pone el generador, en litros de gasolina.
 */
final class RouteTab implements CarScreen {
    private static final long TICK_MS = 1000;
    /** Tramos de la barra de consumo bajo el perfil. */
    private static final int SEGMENTS = 12;
    /** Bajada fuerte, bajada, llano, subida y subida fuerte. */
    private static final int[] SLOPE_COLORS = {0xFF3DDC84, 0xFF2FA86A, 0xFF5E7A96, CarKit.AMBER, CarKit.RED};

    private RoutePlanner planner;
    private Context ctx;
    private FrameLayout root;
    private boolean running;
    private CarKit.Card routeCard;
    private CarKit.Card batteryCard;
    private CarKit.Card weatherCard;
    private LinearLayout chargers;
    /** Filtro de los cargadores (ChargerFilter): potencia mínima y redes; y su resumen junto al botón. */
    private int chargerMinKw;
    private java.util.Set<String> chargerNets = new java.util.LinkedHashSet<>();
    private TextView filterSummary;
    /** Coche virtual: «Desde: GPS» / «Desde: Sevilla». */
    private TextView originPill;
    /** El plan de carga con el % de ahora (null sin % de batería, sin ruta o en un REEV) y de qué datos salió. */
    private ChargePlanner.Result chargePlan;
    private TextView search;
    /** Los botones de arriba a la derecha (buscar, Google Maps, quitar): su ancho real recorta el nombre del destino. */
    private LinearLayout actionsRow;
    private TextView goMaps;
    private TextView clear;
    private TextView cta;
    private int shownChargersFor = -1;
    private RoutePlanner.Plan plan;
    private double soc = Double.NaN;
    /** El % de ahora es el real del coche (nube de Leapmotor), no el indicado. */
    private boolean realSoc;
    /** Capacidad con la que se pasa de kWh a %: la de la variante con datos reales; si no, la del modelo. */
    private double capKwh = EnergyModel.USABLE_KWH;
    private CarCloud.Snapshot cloud = CarCloud.Snapshot.of(CarCloud.State.NO_ACCOUNT);
    private LinearLayout socControls;
    private String status = "";
    /** El GPS no está al día (móvil bloqueado, sin cobertura): el avance de la ruta está parado. null si lo está. */
    private String gpsNote;
    private NavTap.Info nav = new NavTap.Info();
    private final Runnable tickTask = this::tick;
    private final RectF tmp = new RectF();
    private final CarKit.Para para = new CarKit.Para(27, CarKit.DIM, CarKit.REGULAR);
    private final CarKit.Para statusPara = new CarKit.Para(22, CarKit.DIM, CarKit.MEDIUM);
    // Perfil precalculado (cambia con la ruta o el tamaño): áreas por pendiente, la cresta y el degradado.
    private RoutePlanner.Plan cachedPlan;
    private float cachedW;
    private final Path[] slopePaths = new Path[5];
    private final Path[] ridgePaths = new Path[5];
    private final LinearGradient[] slopeFills = new LinearGradient[5];
    private final Path socLine = new Path();
    private final Path road = new Path();
    private final RectF chartRect = new RectF();
    private final RectF segRect = new RectF();
    private final android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{14, 14}, 0);
    private float minE;
    private float maxE;

    @Override
    public View create(Host h) {
        ctx = h.context();
        planner = RoutePlanner.start(ctx);
        for (int i = 0; i < slopePaths.length; i++) {
            slopePaths[i] = new Path();
            ridgePaths[i] = new Path();
        }

        LinearLayout row = CarKit.row(ctx);
        routeCard = CarKit.add(row, new CarKit.Card(ctx, null, this::paintRoute), 1.95f, 0);
        // Destino elegido aquí (AA no nos manda el suyo): buscar, abrir en Google Maps, quitar.
        LinearLayout actions = CarKit.row(ctx);
        actionsRow = actions;
        search = CarKit.pill(ctx, Str.get(R.string.hql_route_search_dest), true);
        goMaps = CarKit.pill(ctx, Str.get(R.string.hql_route_guide_maps), false);
        clear = CarKit.pill(ctx, Str.get(R.string.hql_remove), false);
        // Coche virtual (modo prueba): desde dónde sale la ruta (el GPS o un lugar elegido: «un Sevilla-Barcelona»).
        originPill = CarKit.pill(ctx, "", false);
        originPill.setOnClickListener(v -> openSearch(true));
        if (RoutePlanner.testMode()) {
            actions.addView(originPill);
            actions.addView(search, spaced());
        } else {
            actions.addView(search);
        }
        actions.addView(goMaps, spaced());
        actions.addView(clear, spaced());
        routeCard.addView(actions, CarKit.at(Gravity.TOP | Gravity.END, 0, 0, 0, 0));
        search.setOnClickListener(v -> openSearch(false));
        goMaps.setOnClickListener(v -> {
            RoutePlanner.Place m = RoutePlanner.manualDestination();
            if (m != null) navigateTo(m.lat, m.lon);
        });
        clear.setOnClickListener(v -> {
            RoutePlanner.setManualDestination(null);
            tick();
        });
        // Sin destino: el botón grande en el centro.
        cta = CarKit.pill(ctx, Str.get(R.string.hql_route_search_dest), true);
        cta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
        cta.setPadding(44, 20, 44, 20);
        routeCard.addView(cta, CarKit.at(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 90));
        cta.setOnClickListener(v -> openSearch(false));

        LinearLayout right = CarKit.add(row, CarKit.col(ctx), 1f, 0);
        batteryCard = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_battery_arrival), this::paintBattery), 0, 290);
        // Batería: el usuario indica el % actual (hasta tener datos del coche).
        LinearLayout ctl = CarKit.row(ctx);
        socControls = ctl;
        TextView minus = CarKit.pill(ctx, "−5", false);
        TextView plus = CarKit.pill(ctx, "+5", false);
        minus.setMinWidth(92);
        plus.setMinWidth(92);
        ctl.addView(minus);
        ctl.addView(plus, spaced());
        batteryCard.addView(ctl, CarKit.at(Gravity.BOTTOM | Gravity.END, 0, 0, 0, 0));
        minus.setOnClickListener(v -> bump(-5));
        plus.setOnClickListener(v -> bump(5));
        weatherCard = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_route_weather), this::paintWeather), 0, 172);
        CarKit.Card ch = CarKit.add(right, new CarKit.Card(ctx, Str.get(R.string.hql_route_chargers), null), 1f, 0);
        Config cfg0 = new Config(ctx);
        chargerMinKw = cfg0.chargerMinKw();
        chargerNets = ChargerFilter.parseNetworks(cfg0.chargerNetworks());
        LinearLayout chCol = CarKit.col(ctx);
        // Filtro: potencia mínima y redes (como en los planificadores de rutas).
        LinearLayout fRow = CarKit.row(ctx);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView filter = CarKit.pill(ctx, Str.get(R.string.hql_charger_filter), false);
        filter.setOnClickListener(v -> openChargerFilter());
        fRow.addView(filter);
        filterSummary = CarStyle.text(ctx, "", 20, CarKit.DIM);
        filterSummary.setSingleLine(true);
        filterSummary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        filterSummary.setPadding(16, 0, 0, 0);
        fRow.addView(filterSummary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        chCol.addView(fRow);
        chargers = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(chargers);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = 6;
        chCol.addView(sv, svp);
        ch.addView(chCol, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateFilterSummary();

        running = true;
        root = CarKit.page(ctx, row);
        tick();
        String pq = previewQuery;
        if (pq != null) {
            previewQuery = null;
            root.post(() -> {
                openSearch(false);
                if (searchState != null) {
                    searchState.buf.append(pq);
                    searchState.changed();
                    searchState.searchNow(false);
                }
            });
        }
        return root;
    }

    private static LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = 12;
        return lp;
    }

    // ------------------------------------------------------------------ búsqueda de destino

    /** Espera tras la última tecla antes de buscar solo (ms). */
    private static final long TYPE_PAUSE_MS = 650;
    /** El buscador abierto, o null. */
    private SearchState searchState;
    /** Vista previa: abrir el buscador con este texto al crear la pestaña. */
    static volatile String previewQuery;

    /**
     * Buscador a pantalla completa de la pestaña: la caja con lo escrito, el micrófono, los resultados (o los destinos
     * recientes con la caja vacía) y el teclado del coche, que enseña también lo escrito encima de las teclas. Busca solo
     * al dejar de escribir (TYPE_PAUSE_MS, desde 3 letras) en el buscador de Android y en Photon (PlaceSearch); Buscar
     * o Ir buscan ya y, si no hay nada, prueban Nominatim.
     */
    private void openSearch(boolean origin) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        LinearLayout bar = CarStyle.bar(ctx);
        bar.setBackgroundColor(CarKit.SURFACE);
        TextView query = CarStyle.text(ctx, "", 30, CarKit.TEXT);
        query.setHint(Str.get(origin ? R.string.hql_route_origin_hint : R.string.hql_route_query_hint));
        query.setHintTextColor(CarKit.FAINT);
        query.setSingleLine(true);
        query.setEllipsize(android.text.TextUtils.TruncateAt.START);
        query.setPadding(26, 14, 26, 14);
        query.setBackground(CarKit.outlined(CarKit.SURFACE_HI, CarKit.ACCENT, 28));
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qlp.rightMargin = 10;
        bar.addView(query, qlp);
        TextView mic = CarKit.pill(ctx, "🎤 " + Str.get(R.string.hql_search_voice), false);
        TextView find = CarKit.pill(ctx, Str.get(R.string.hql_search), true);
        TextView close = CarKit.pill(ctx, Str.get(R.string.hql_close), false);
        bar.addView(mic);
        bar.addView(find, spaced());
        bar.addView(close, spaced());
        panel.addView(bar);
        LinearLayout results = new LinearLayout(ctx);
        results.setOrientation(LinearLayout.VERTICAL);
        results.setPadding(24, 10, 24, 8);
        ScrollView rs = new ScrollView(ctx);
        rs.addView(results);
        panel.addView(rs, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        SearchState st = new SearchState(panel, query, results, origin);
        searchState = st;
        CarKeyboard kb = new CarKeyboard(ctx, new CarKeyboard.Listener() {
            @Override
            public void onText(String t) {
                st.buf.append(t);
                st.changed();
            }

            @Override
            public void onBackspace() {
                if (st.buf.length() > 0) st.buf.setLength(st.buf.length() - 1);
                st.changed();
            }

            @Override
            public void onEnter() {
                st.searchNow(true);
            }

            @Override
            public void onHide() {
            }
        });
        st.kb = kb;
        panel.addView(kb);
        find.setOnClickListener(v -> st.searchNow(true));
        mic.setOnClickListener(v -> st.listen());
        close.setOnClickListener(v -> st.close());
        root.addView(panel, CarStyle.match());
        st.changed();
    }

    /** Lo que vive mientras está abierto el buscador. */
    private final class SearchState {
        final View panel;
        final TextView query;
        final LinearLayout results;
        final StringBuilder buf = new StringBuilder();
        CarKeyboard kb;
        /** Sube con cada búsqueda: la respuesta de una búsqueda vieja no pisa la de una nueva. */
        int seq;
        String shownFor = "";
        android.speech.SpeechRecognizer speech;
        final Runnable typed = () -> searchNow(false);
        /** Se busca la salida (coche virtual), no el destino. */
        final boolean origin;

        SearchState(View panel, TextView query, LinearLayout results, boolean origin) {
            this.panel = panel;
            this.query = query;
            this.results = results;
            this.origin = origin;
        }

        String hint() {
            return Str.get(origin ? R.string.hql_route_origin_hint : R.string.hql_route_query_hint);
        }

        /** Cambió el texto: se ve en la caja y sobre el teclado; se busca solo al dejar de escribir. */
        void changed() {
            query.setText(buf);
            if (kb != null) kb.showText(buf, hint());
            results.removeCallbacks(typed);
            String q = buf.toString().trim();
            if (q.isEmpty()) {
                seq++;
                shownFor = "";
                showRecents();
            } else if (PlaceSearch.worthTyping(q) && !q.equals(shownFor)) {
                results.postDelayed(typed, TYPE_PAUSE_MS);
            }
        }

        /** Busca ya; explicit: pulsado Buscar/Ir (entonces, sin resultados, se prueba también Nominatim). */
        void searchNow(boolean explicit) {
            results.removeCallbacks(typed);
            String q = buf.toString().trim();
            if (q.isEmpty() || (!explicit && q.equals(shownFor))) return;
            int my = ++seq;
            shownFor = q;
            header(Str.get(R.string.hql_searching), CarKit.DIM);
            double[] pos = planner.position();
            new Thread(() -> {
                // Las capturas (reloj quieto) no usan la red; la vista previa interactiva sí busca de verdad.
                java.util.List<RoutePlanner.Place> found = DemoMode.active() && !DemoMode.live() ? DemoMode.searchResults(q)
                        : PlaceSearch.search(ctx, q, pos[0], pos[1]);
                String err = null;
                if (found.isEmpty() && explicit) {
                    try {
                        found = RoutePlanner.search(q, pos[0], pos[1]);
                    } catch (Exception e) {
                        err = e.getMessage();
                    }
                }
                java.util.List<RoutePlanner.Place> f = found;
                String fe = err;
                results.post(() -> {
                    if (my == seq && root.indexOfChild(panel) >= 0) showResults(this, f, fe, pos);
                });
            }, "route-search").start();
        }

        void showRecents() {
            results.removeAllViews();
            java.util.List<RoutePlanner.Place> rec = PlaceSearch.recents(new Config(ctx).recentPlaces());
            if (rec.isEmpty()) {
                header(Str.get(R.string.hql_search_tip), CarKit.FAINT);
                if (origin) results.addView(gpsItem(this), itemParams());
                return;
            }
            header(Str.get(R.string.hql_search_recent), CarKit.FAINT);
            if (origin) results.addView(gpsItem(this), itemParams());
            double[] pos = planner.position();
            for (RoutePlanner.Place pl : rec) results.addView(item(this, pl, pos), itemParams());
        }

        void header(String text, int color) {
            results.removeAllViews();
            TextView h = CarStyle.text(ctx, text, 22, color);
            h.setPadding(6, 4, 6, 12);
            results.addView(h);
        }

        /** Buscar hablando (el reconocimiento de voz del móvil, con su micrófono). */
        void listen() {
            if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                header(Str.get(R.string.hql_search_voice_permission), CarKit.AMBER);
                return;
            }
            if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
                header(Str.get(R.string.hql_search_voice_none), CarKit.AMBER);
                return;
            }
            stopListening();
            speech = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx);
            speech.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override
                public void onReadyForSpeech(android.os.Bundle params) {
                    header(Str.get(R.string.hql_search_listening), CarKit.ACCENT);
                }

                @Override
                public void onPartialResults(android.os.Bundle b) {
                    String t = first(b);
                    if (t != null && !t.isEmpty()) {
                        buf.setLength(0);
                        buf.append(t);
                        query.setText(buf);
                        if (kb != null) kb.showText(buf, "");
                    }
                }

                @Override
                public void onResults(android.os.Bundle b) {
                    String t = first(b);
                    stopListening();
                    if (t == null || t.trim().isEmpty()) {
                        header(Str.get(R.string.hql_search_voice_nothing), CarKit.DIM);
                        return;
                    }
                    L.i("buscar: destino dicho en voz alta"); // sin el texto: el log se exporta
                    buf.setLength(0);
                    buf.append(t.trim());
                    query.setText(buf);
                    if (kb != null) kb.showText(buf, "");
                    searchNow(true);
                }

                @Override
                public void onError(int error) {
                    stopListening();
                    L.i("buscar: la voz no entendió nada (error " + error + ")");
                    header(Str.get(error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                            ? R.string.hql_search_voice_permission : R.string.hql_search_voice_nothing), CarKit.DIM);
                }

                @Override
                public void onBeginningOfSpeech() {
                }

                @Override
                public void onRmsChanged(float rmsdB) {
                }

                @Override
                public void onBufferReceived(byte[] buffer) {
                }

                @Override
                public void onEndOfSpeech() {
                    header(Str.get(R.string.hql_searching), CarKit.DIM);
                }

                @Override
                public void onEvent(int eventType, android.os.Bundle params) {
                }
            });
            Intent in = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            in.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            in.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            try {
                speech.startListening(in);
            } catch (RuntimeException e) {
                stopListening();
                header(Str.get(R.string.hql_search_voice_none), CarKit.AMBER);
            }
        }

        String first(android.os.Bundle b) {
            java.util.ArrayList<String> r = b == null ? null : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
            return r == null || r.isEmpty() ? null : r.get(0);
        }

        void stopListening() {
            if (speech == null) return;
            try {
                speech.cancel();
                speech.destroy();
            } catch (RuntimeException ignored) {
                // Ya parado.
            }
            speech = null;
        }

        void close() {
            results.removeCallbacks(typed);
            seq++;
            stopListening();
            root.removeView(panel);
            if (searchState == this) searchState = null;
        }
    }

    private static LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 8;
        return lp;
    }

    private void showResults(SearchState st, java.util.List<RoutePlanner.Place> found, String err, double[] pos) {
        LinearLayout results = st.results;
        results.removeAllViews();
        if (found.isEmpty()) {
            st.header(err != null ? Str.get(R.string.hql_search_failed, err) : Str.get(R.string.hql_no_results), CarKit.DIM);
            return;
        }
        for (RoutePlanner.Place pl : found) results.addView(item(st, pl, pos), itemParams());
    }

    /** Un resultado: nombre, dirección y, a la derecha, a cuántos km está. Al pulsarlo, es el destino. */
    private View item(SearchState st, RoutePlanner.Place pl, double[] pos) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(22, 14, 22, 14);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.OUTLINE, 18));
        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView n = CarStyle.text(ctx, pl.name, 27, CarKit.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView d = CarStyle.text(ctx, pl.detail, 20, CarKit.DIM);
        d.setSingleLine(true);
        d.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(n);
        text.addView(d);
        item.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!Double.isNaN(pos[0])) {
            double km = RoadInfo.dist(pos[0], pos[1], pl.lat, pl.lon) / 1000;
            TextView k = CarStyle.text(ctx, km < 10 ? String.format(Locale.getDefault(), "%.1f km", km)
                    : String.format(Locale.getDefault(), "%.0f km", km), 24, CarKit.ACCENT);
            k.setPadding(18, 0, 0, 0);
            item.addView(k);
        }
        item.setOnClickListener(v -> {
            Config c = new Config(ctx);
            c.setRecentPlaces(PlaceSearch.remember(c.recentPlaces(), pl));
            if (st.origin) {
                RoutePlanner.setTestOrigin(pl);
            } else {
                L.i("ruta: destino elegido en el coche"); // sin el nombre: el log se exporta (qdauto §7.5)
                RoutePlanner.setManualDestination(pl);
            }
            st.close();
            tick();
        });
        return item;
    }

    /** Buscando la salida: «Mi ubicación (GPS)», para volver a salir de donde está el móvil. */
    private View gpsItem(SearchState st) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(22, 18, 22, 18);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.ACCENT, 18));
        TextView n = CarStyle.text(ctx, "📍 " + Str.get(R.string.hql_route_origin_gps), 27, CarKit.ACCENT);
        item.addView(n);
        item.setOnClickListener(v -> {
            RoutePlanner.setTestOrigin(null);
            st.close();
            tick();
        });
        return item;
    }

    private void bump(int d) {
        double now = planner.socNow();
        planner.setSoc(Double.isNaN(now) ? 80 : Math.round(now) + d);
        tick();
    }

    private void tick() {
        if (!running) return;
        boolean manual = RoutePlanner.manualDestination() != null;
        plan = planner.plan();
        // Batería: la real del coche si la hay (nube de Leapmotor); si no, la indicada con ±5.
        cloud = CarCloud.snapshot();
        double real = cloud.soc(DemoMode.wallClockMs(), CarCloud.SOC_MAX_AGE_MS);
        realSoc = !Double.isNaN(real) && !RoutePlanner.testMode();
        soc = realSoc ? real : planner.socNow();
        capKwh = realSoc || (RoutePlanner.testMode() && cloud.hasData()) ? cloud.capacityKwh : EnergyModel.USABLE_KWH;
        socControls.setVisibility(realSoc ? View.GONE : View.VISIBLE);
        status = planner.status();
        CarSensors.Snapshot gps = planner.sensorSnapshot();
        gpsNote = gps.gpsState == GpsWatch.State.WAITING ? null : CarSensors.gpsNote(gps);
        nav = DemoMode.navInfo();
        boolean have = plan != null && plan.n >= 2;
        boolean test = RoutePlanner.testMode();
        goMaps.setVisibility(manual && have ? View.VISIBLE : View.GONE);
        String guide = Str.get(NavApps.useWaze(ctx) ? R.string.hql_route_guide_waze : R.string.hql_route_guide_maps);
        if (!guide.contentEquals(goMaps.getText())) goMaps.setText(guide);
        if (test) {
            RoutePlanner.Place o = RoutePlanner.testOrigin();
            originPill.setText(Str.get(R.string.hql_route_from, o == null ? Str.get(R.string.hql_route_gps) : o.name));
        }
        clear.setVisibility(manual ? View.VISIBLE : View.GONE);
        cta.setVisibility(have ? View.GONE : View.VISIBLE);
        search.setVisibility(have ? View.VISIBLE : View.GONE);
        routeCard.invalidate();
        batteryCard.invalidate();
        weatherCard.invalidate();
        if (have) {
            updateChargePlan();
            int key = ((System.identityHashCode(plan) * 31 + plan.progress / 10) * 31 + chargerMinKw * 7 + chargerNets.hashCode()) * 31
                    + System.identityHashCode(chargePlan) + plan.chargerVersion * 131;
            if (key != shownChargersFor) {
                shownChargersFor = key;
                fillChargers(plan);
            }
        } else if (shownChargersFor != 0) {
            shownChargersFor = 0;
            chargers.removeAllViews();
            chargers.addView(hint(Str.get(R.string.hql_route_chargers_hint)));
        }
        routeCard.removeCallbacks(tickTask);
        routeCard.postDelayed(tickTask, TICK_MS);
    }

    private TextView hint(String s) {
        TextView t = CarStyle.text(ctx, s, 22, CarKit.FAINT);
        t.setPadding(0, 6, 0, 0);
        return t;
    }

    /** El cargador pasa el filtro elegido. */
    private boolean wanted(RoutePlanner.Charger c) {
        return ChargerFilter.accepts(c.maxKw, c.network, chargerMinKw, chargerNets);
    }

    /** «Todos los cargadores» o «≥ 100 kW · Tesla, Zunder». */
    private void updateFilterSummary() {
        StringBuilder b = new StringBuilder();
        if (chargerMinKw > 0) b.append(Str.get(R.string.hql_charger_summary_kw, chargerMinKw));
        if (!chargerNets.isEmpty()) {
            StringBuilder n = new StringBuilder();
            for (String k : chargerNets) {
                n.append(n.length() > 0 ? ", " : "").append(k.equals(ChargerFilter.OTHER) ? Str.get(R.string.hql_charger_other)
                        : ChargerFilter.label(k));
            }
            b.append(b.length() > 0 ? " · " : "").append(n);
        }
        filterSummary.setText(b.length() == 0 ? Str.get(R.string.hql_charger_summary_all) : b.toString());
        filterSummary.setTextColor(b.length() == 0 ? CarKit.FAINT : CarKit.ACCENT);
    }

    /** Cambió el filtro: se guarda, se rehace la lista y el perfil. */
    private void filterChanged() {
        Config c = new Config(ctx);
        c.setChargerMinKw(chargerMinKw);
        c.setChargerNetworks(ChargerFilter.formatNetworks(chargerNets));
        updateFilterSummary();
        shownChargersFor = -1;
        tick();
    }

    /**
     * Panel del filtro de cargadores: potencia mínima (una) y redes (varias; ninguna marcada = todas), con las redes que
     * hay en esta ruta y cuántos cargadores tiene cada una.
     */
    private void openChargerFilter() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        panel.setPadding(CarKit.PAD, 18, CarKit.PAD, CarKit.PAD);
        LinearLayout bar = CarKit.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_charger_filter_title), 34, CarKit.TEXT);
        title.setTypeface(CarKit.MEDIUM);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = CarKit.pill(ctx, Str.get(R.string.hql_close), true);
        bar.addView(done);
        panel.addView(bar);
        LinearLayout body = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();
            body.addView(section(Str.get(R.string.hql_charger_min_power)));
            LinearLayout kw = CarKit.row(ctx);
            for (int v : ChargerFilter.MIN_KW_CHOICES) {
                String label = v == 0 ? Str.get(R.string.hql_charger_any) : v + " kW";
                kw.addView(chip(label, chargerMinKw == v, () -> {
                    chargerMinKw = v;
                    filterChanged();
                    build[0].run();
                }), chipParams());
            }
            body.addView(kw);
            body.addView(section(Str.get(R.string.hql_plan_arrive_min)));
            Config pc = new Config(ctx);
            LinearLayout arr = CarKit.row(ctx);
            for (int v : new int[]{10, 15, 20, 25, 30}) {
                arr.addView(chip(v + " %", pc.planArrivePct() == v, () -> {
                    pc.setPlanArrivePct(v);
                    filterChanged();
                    build[0].run();
                }), chipParams());
            }
            body.addView(arr);
            body.addView(section(Str.get(R.string.hql_plan_max_charge)));
            LinearLayout mx = CarKit.row(ctx);
            for (int v : new int[]{70, 80, 90, 100}) {
                mx.addView(chip(v + " %", pc.planMaxPct() == v, () -> {
                    pc.setPlanMaxPct(v);
                    filterChanged();
                    build[0].run();
                }), chipParams());
            }
            body.addView(mx);
            body.addView(section(Str.get(R.string.hql_plan_voice)));
            LinearLayout vo = CarKit.row(ctx);
            vo.addView(chip(capital(Str.get(R.string.hql_yes)), pc.planVoice(), () -> {
                pc.setPlanVoice(true);
                build[0].run();
            }), chipParams());
            vo.addView(chip(capital(Str.get(R.string.hql_no)), !pc.planVoice(), () -> {
                pc.setPlanVoice(false);
                build[0].run();
            }), chipParams());
            body.addView(vo);
            // Navegador para «Guiar», los «Ir» y las paradas (Waze, si está instalado).
            if (NavApps.wazeInstalled(ctx)) {
                body.addView(section(Str.get(R.string.hql_nav_app)));
                LinearLayout na = CarKit.row(ctx);
                na.addView(chip("Google Maps", !NavApps.useWaze(ctx), () -> {
                    pc.setNavApp(Config.NAV_MAPS);
                    build[0].run();
                    tick();
                }), chipParams());
                na.addView(chip("Waze", NavApps.useWaze(ctx), () -> {
                    pc.setNavApp(Config.NAV_WAZE);
                    build[0].run();
                    tick();
                }), chipParams());
                body.addView(na);
            }
            body.addView(section(Str.get(R.string.hql_charger_networks)));
            java.util.List<String> keys = new java.util.ArrayList<>();
            RoutePlanner.Plan pl = plan;
            if (pl != null) for (RoutePlanner.Charger c : pl.chargers) keys.add(c.network == null ? ChargerFilter.OTHER : c.network);
            java.util.List<java.util.Map.Entry<String, Integer>> found = ChargerFilter.counts(keys);
            // Las marcadas que no salen en esta ruta también se ven (para poder quitarlas).
            for (String k : chargerNets) {
                boolean in = false;
                for (java.util.Map.Entry<String, Integer> e : found) in |= e.getKey().equals(k);
                if (!in) found.add(new java.util.AbstractMap.SimpleEntry<>(k, 0));
            }
            LinearLayout row = null;
            int i = 0;
            row = CarKit.row(ctx);
            row.addView(chip(Str.get(R.string.hql_charger_all_networks), chargerNets.isEmpty(), () -> {
                chargerNets.clear();
                filterChanged();
                build[0].run();
            }), chipParams());
            i++;
            for (java.util.Map.Entry<String, Integer> e : found) {
                if (i % 4 == 0) {
                    body.addView(row);
                    row = CarKit.row(ctx);
                }
                String k = e.getKey();
                String name = k.equals(ChargerFilter.OTHER) ? Str.get(R.string.hql_charger_other) : ChargerFilter.label(k);
                row.addView(chip(name + " · " + e.getValue(), chargerNets.contains(k), () -> {
                    if (!chargerNets.remove(k)) chargerNets.add(k);
                    filterChanged();
                    build[0].run();
                }), chipParams());
                i++;
            }
            body.addView(row);
            TextView note = CarStyle.text(ctx, Str.get(R.string.hql_charger_filter_note), 21, CarKit.FAINT);
            note.setPadding(4, 26, 4, 0);
            body.addView(note);
        };
        build[0].run();
        done.setOnClickListener(v -> root.removeView(panel));
        root.addView(panel, CarStyle.match());
    }

    private TextView section(String s) {
        TextView t = CarStyle.text(ctx, s.toUpperCase(Locale.getDefault()), 21, CarKit.FAINT);
        t.setLetterSpacing(0.1f);
        t.setTypeface(CarKit.MEDIUM);
        t.setPadding(4, 30, 4, 12);
        return t;
    }

    /** Ficha de elección: marcada, rellena con el acento. */
    private TextView chip(String label, boolean on, Runnable action) {
        TextView t = CarKit.pill(ctx, label, on);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 25);
        t.setPadding(30, 16, 30, 16);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private static String capital(String t) {
        return t.isEmpty() ? t : t.substring(0, 1).toUpperCase(Locale.getDefault()) + t.substring(1);
    }

    private static LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 12;
        lp.bottomMargin = 12;
        return lp;
    }

    /**
     * El plan de carga de RoutePlanner (que lo rehace también en segundo plano, con cómo se está gastando en el viaje,
     * y avisa si cambia).
     */
    private void updateChargePlan() {
        boolean charging = cloud != null && cloud.hasData() && (cloud.status.charging() || cloud.status.pluggedIn());
        chargePlan = planner.chargePlan(soc, capKwh, realSoc, reev(), charging);
    }

    /** La sección del plan de carga encima de la lista de cargadores. */
    private void addPlan(RoutePlanner.Plan p) {
        ChargePlanner.Result cp = chargePlan;
        if (cp == null) return;
        if (cp.outcome == ChargePlanner.Outcome.NO_STOPS) {
            TextView t = hint(Str.get(R.string.hql_plan_none, cp.arrivalPct));
            t.setTextColor(CarKit.GREEN);
            chargers.addView(t);
            addReplanNote(cp);
            return;
        }
        if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER && cp.stops.isEmpty()) {
            TextView t = hint(Str.get(R.string.hql_plan_impossible));
            t.setTextColor(CarKit.AMBER);
            chargers.addView(t);
            return;
        }
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_plan_title).toUpperCase(Locale.getDefault()), 19, CarKit.ACCENT);
        title.setTypeface(CarKit.MEDIUM);
        title.setLetterSpacing(0.1f);
        title.setPadding(0, 10, 0, 2);
        chargers.addView(title);
        String dur = DriveTab.duration(Math.round(cp.chargeMinutes * 60));
        // Sin cargador alcanzable tras la última parada: dónde se queda, no un «llegas con -130 %».
        String sum = cp.outcome == ChargePlanner.Outcome.NO_CHARGER
                ? Str.get(R.string.hql_plan_summary_stuck, cp.stops.size(), dur, cp.stops.get(cp.stops.size() - 1).km)
                : cp.stops.size() == 1 ? Str.get(R.string.hql_plan_summary_one, dur, cp.arrivalPct)
                : Str.get(R.string.hql_plan_summary_many, cp.stops.size(), dur, cp.arrivalPct);
        TextView s = CarStyle.text(ctx, sum, 22, cp.outcome == ChargePlanner.Outcome.PLANNED ? CarKit.TEXT : CarKit.AMBER);
        chargers.addView(s);
        addReplanNote(cp);
        if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER) chargers.addView(hint(Str.get(R.string.hql_plan_impossible)));
        int n = 1;
        for (ChargePlanner.Stop st : cp.stops) {
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 10, 0, 10);
            ChargerDot dot = new ChargerDot(ctx, st.charger.maxKw);
            item.addView(dot, new LinearLayout.LayoutParams(52, 52));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, n++ + ". " + st.charger.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView line = CarStyle.text(ctx, Str.get(R.string.hql_plan_stop_line, st.km, st.arrivePct, st.departPct,
                    DriveTab.duration(Math.round(st.minutes * 60))) + (st.charger.maxKw > 0
                    ? String.format(Locale.getDefault(), " · %.0f kW", st.charger.maxKw) : ""), 20, CarKit.ACCENT);
            line.setMaxLines(2);
            txt.addView(name);
            txt.addView(line);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(st.charger.lat, st.charger.lon));
            item.addView(go);
            chargers.addView(item);
        }
        TextView all = CarKit.pill(ctx, Str.get(R.string.hql_plan_go), true);
        all.setOnClickListener(v -> RoutePlanner.navigateWithStops(ctx, p, cp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 6;
        lp.bottomMargin = 10;
        chargers.addView(all, lp);
        TextView other = CarStyle.text(ctx, Str.get(R.string.hql_plan_other_chargers).toUpperCase(Locale.getDefault()), 19, CarKit.FAINT);
        other.setTypeface(CarKit.MEDIUM);
        other.setLetterSpacing(0.1f);
        other.setPadding(0, 8, 0, 0);
        chargers.addView(other);
    }

    /**
     * Bajo el resumen del plan: el último cambio durante el viaje («Recalculado a las 14:32: …», en ámbar, media hora)
     * o, si no, cuánto se está gastando de más o de menos en este viaje (ya está metido en el plan).
     */
    private void addReplanNote(ChargePlanner.Result cp) {
        String alert = planner.lastAlert;
        long at = planner.lastAlertAtMs;
        long now = DemoMode.wallClockMs();
        if (alert != null && now - at < 30 * 60_000L) {
            String hhmm = new java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(new java.util.Date(at));
            TextView t = hint(Str.get(R.string.hql_plan_replanned, hhmm) + " " + alert);
            t.setTextColor(CarKit.AMBER);
            t.setMaxLines(3);
            chargers.addView(t);
            return;
        }
        double pct = (cp.trend - 1) * 100;
        if (Math.abs(pct) >= 5) {
            chargers.addView(hint(Str.get(R.string.hql_plan_trend, String.format(Locale.getDefault(), "%+.0f %%", pct))));
        }
    }

    private void fillChargers(RoutePlanner.Plan p) {
        chargers.removeAllViews();
        // Tramos sin datos de cargadores (OpenStreetMap no respondió): el plan puede no ser el mejor.
        for (double[] g : p.chargerGaps) {
            TextView t = hint(Str.get(R.string.hql_route_charger_gap, g[0], g[1]));
            t.setTextColor(CarKit.AMBER);
            chargers.addView(t);
        }
        addPlan(p);
        java.util.Set<RoutePlanner.Charger> inPlan = new java.util.HashSet<>();
        if (chargePlan != null) for (ChargePlanner.Stop st : chargePlan.stops) inPlan.add(st.charger);
        double here = p.km[Math.min(p.progress, p.n - 1)];
        int shown = 0;
        int hidden = 0;
        for (RoutePlanner.Charger c : p.chargers) {
            if (c.kmAlong < here - 0.5 || inPlan.contains(c)) continue;
            if (!wanted(c)) {
                hidden++;
                continue;
            }
            if (shown >= 12) continue;
            shown++;
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 10, 0, 10);
            ChargerDot dot = new ChargerDot(ctx, c.maxKw);
            item.addView(dot, new LinearLayout.LayoutParams(52, 52));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, c.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String sub = Str.get(R.string.hql_charger_line, c.kmAlong, Math.max(0, c.kmAlong - here))
                    + (c.maxKw > 0 ? String.format(Locale.getDefault(), " · %.0f kW", c.maxKw) : "")
                    + (c.detail.isEmpty() ? "" : " · " + c.detail);
            TextView detail = CarStyle.text(ctx, sub, 20, CarKit.DIM);
            // Dos renglones: con la potencia y el detalle, uno solo cortaba el texto («350 k…»).
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(name);
            txt.addView(detail);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
            item.addView(go);
            chargers.addView(item);
        }
        if (shown == 0 && hidden > 0) {
            // Hay cargadores, pero el filtro los esconde todos: se dice y se deja quitarlo.
            chargers.addView(hint(Str.get(R.string.hql_charger_none_filtered)));
            TextView clearF = CarKit.pill(ctx, Str.get(R.string.hql_charger_clear_filters), false);
            clearF.setOnClickListener(v -> {
                chargerMinKw = 0;
                chargerNets.clear();
                filterChanged();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = 12;
            chargers.addView(clearF, lp);
        } else if (shown == 0) {
            chargers.addView(hint(Str.get(R.string.hql_route_no_chargers)));
        }
    }

    /** Círculo con el rayo, del color según la potencia del cargador. */
    private static final class ChargerDot extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;

        ChargerDot(Context c, double kw) {
            super(c);
            color = kw >= 150 ? CarKit.GREEN : kw >= 50 ? CarKit.ACCENT : CarKit.DIM;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            p.setColor(CarKit.alpha(color, 0.14f));
            cv.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            CarIcons.bolt(cv, getWidth() / 2f, getHeight() / 2f, r * 1.1f, color, p);
        }
    }

    /** Abre la navegación de Google Maps en el móvil; Android Auto la muestra en el coche. */
    private void navigateTo(double lat, double lon) {
        NavApps.go(ctx, lat, lon);
    }

    @Override
    public void destroy() {
        running = false;
        if (searchState != null) searchState.close();
    }

    static String weatherText(int code) {
        if (code < 0) return "—";
        if (code == 0) return Str.get(R.string.hql_wx_clear);
        if (code <= 3) return Str.get(R.string.hql_wx_partly);
        if (code <= 48) return Str.get(R.string.hql_wx_fog);
        if (code <= 57) return Str.get(R.string.hql_wx_drizzle);
        if (code <= 67) return Str.get(R.string.hql_wx_rain);
        if (code <= 77) return Str.get(R.string.hql_wx_snow);
        if (code <= 82) return Str.get(R.string.hql_wx_showers);
        if (code <= 86) return Str.get(R.string.hql_wx_snow_showers);
        return Str.get(R.string.hql_wx_storm);
    }

    // ------------------------------------------------------------------ datos derivados

    /** kWh hasta el destino (desde la posición actual), o NaN. */
    private double remainingKwh() {
        ChargePlanner.Result cp = chargePlan;
        RoutePlanner.Plan pl = plan;
        if (cp != null && pl != null && pl.n >= 2) return ChargePlanner.kwhAt(cp.km, cp.kwhCum, pl.totalKm) - ChargePlanner.kwhAt(cp.km, cp.kwhCum, cp.fromKm);
        return RoutePlanner.remainingKwh(plan);
    }

    /** % de batería al llegar, o NaN si no se sabe (en un REEV, no baja del 20 %: lo pone el generador). */
    private double arrivalPct() {
        ChargePlanner.Result cp = chargePlan;
        if (cp != null && cp.outcome == ChargePlanner.Outcome.PLANNED) return cp.arrivalPct;
        double a = CloudEnergy.arrivalPct(soc, remainingKwh(), capKwh);
        return reev() && !Double.isNaN(a) ? Math.max(Math.min(soc, REEV_FLOOR_PCT), a) : a;
    }

    /** REEV: por debajo de este % arranca el generador y la batería ya no baja (lo pone la gasolina). */
    static final double REEV_FLOOR_PCT = 20;

    /** El coche es un REEV (lo dice la nube). */
    private boolean reev() {
        return cloud != null && cloud.hasData() && cloud.status.reev();
    }

    /** Litros de gasolina que pondría el generador para llegar (REEV), o 0. */
    private double reevLiters() {
        double raw = CloudEnergy.arrivalPct(soc, remainingKwh(), capKwh);
        if (!reev() || Double.isNaN(raw) || raw >= REEV_FLOOR_PCT) return 0;
        return (REEV_FLOOR_PCT - raw) / 100 * capKwh / TripStats.KWH_PER_LITER;
    }

    /** Consumo medio según el coche: el de sus viajes de los últimos 30 días o, si no, el de las últimas semanas. NaN si no. */
    private static double carAverage() {
        CloudHistory.Match m = CloudHistory.totals(CarCloud.history(), DemoMode.wallClockMs() - 30 * 86_400_000L);
        if (m != null && m.km >= 20 && !Double.isNaN(m.kwhPer100())) return m.kwhPer100();
        CloudHistory.Weekly w = CarCloud.weekly();
        return w == null ? Double.NaN : w.avgKwhPer100;
    }

    /**
     * De dónde sale la previsión, en una línea: al llegar, lo previsto frente a lo real; antes, lo que suben y bajan los km
     * que faltan y lo que cuestan esas cuestas, y la media según el coche.
     */
    private String explain(RoutePlanner.Plan pl, int prog) {
        if (!Double.isNaN(pl.arrivalPredictedKwh)) {
            return Double.isNaN(pl.arrivalRealKwh)
                    ? Str.get(R.string.hql_route_arrival_generator, pl.arrivalPredictedKwh)
                    : Str.get(R.string.hql_route_arrival_compare, pl.arrivalPredictedKwh, pl.arrivalRealKwh);
        }
        double up = 0;
        double down = 0;
        for (int i = prog + 1; i < pl.n; i++) {
            double d = pl.elev[i] - pl.elev[i - 1];
            if (d > 0) up += d;
            else down -= d;
        }
        double hills = pl.gravCum == null ? Double.NaN : pl.gravCum[pl.n - 1] - pl.gravCum[Math.min(prog, pl.n - 1)];
        StringBuilder b = new StringBuilder();
        if (up < 15 && down < 15) {
            b.append(Str.get(R.string.hql_route_flat_ahead));
        } else if (!Double.isNaN(hills)) {
            b.append(Str.get(hills >= 0 ? R.string.hql_route_hills_cost : R.string.hql_route_hills_gain, Math.round(up), Math.round(down),
                    Math.abs(hills)));
        }
        return b.toString();
    }

    private static int levelColor(double pct) {
        return pct >= 20 ? CarKit.ACCENT : pct >= 8 ? CarKit.AMBER : CarKit.RED;
    }

    // ------------------------------------------------------------------ ruta

    private void paintRoute(Canvas cv, RectF r, Paint p) {
        RoutePlanner.Plan pl = plan;
        float x0 = r.left;
        float y0 = r.top;
        String dest = nav.destination != null ? nav.destination : pl != null ? pl.destination : null;
        if (pl == null || pl.n < 2) {
            paintEmpty(cv, r, p, dest);
            return;
        }
        // Destino.
        CarIcons.pin(cv, x0 + 16, y0 + 30, 40, CarKit.ACCENT, CarKit.SURFACE, p);
        // El hueco de los botones, medido (en otros idiomas son más anchos que en español).
        float pillsW = actionsRow.getWidth() > 0 ? actionsRow.getWidth() + 20
                : goMaps.getVisibility() == View.VISIBLE ? 620 : clear.getVisibility() == View.VISIBLE ? 360 : 240;
        CarKit.label(cv, Str.get(R.string.hql_destination), x0 + 48, y0 + 14, CarKit.FAINT, p, Paint.Align.LEFT, r.width() - pillsW - 60);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(40);
        CarKit.text(cv, CarKit.ellipsize(dest != null ? dest : "", r.width() - pillsW - 60, p), x0 + 48, y0 + 58, 40, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        // Fichas: lo que falta, llegada, energía y consumo previstos.
        int prog = Math.min(pl.progress, pl.n - 1);
        double remKm = nav.remainingMeters >= 0 ? nav.remainingMeters / 1000.0 : pl.totalKm - pl.km[prog];
        long remSec = nav.remainingSeconds >= 0 ? nav.remainingSeconds
                : Math.round(pl.totalSeconds * (pl.totalKm - pl.km[prog]) / Math.max(0.1, pl.totalKm));
        String eta = nav.eta != null ? nav.eta : android.text.format.DateFormat.getTimeFormat(ctx)
                .format(new java.util.Date(DemoMode.wallClockMs() + remSec * 1000));
        double kwh = remainingKwh();
        double per100 = pl.totalKm - pl.km[prog] > 1 ? kwh / (pl.totalKm - pl.km[prog]) * 100 : Double.NaN;
        float cw = (r.width() - 3 * 14) / 4f;
        float cy0 = y0 + 92;
        tmp.set(x0, cy0, x0 + cw, cy0 + 88);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_remaining), String.format(Locale.getDefault(), "%.0f", remKm), "km", CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_drive_arrival), eta, DriveTab.duration(remSec), CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_energy_needed), "~" + String.format(Locale.getDefault(), "%.1f", kwh), "kWh", CarKit.TEXT, p);
        tmp.offset(cw + 14, 0);
        CarKit.chip(cv, tmp, Str.get(R.string.hql_expected_use), Double.isNaN(per100) ? "—" : String.format(Locale.getDefault(), "%.1f", per100),
                "kWh/100", CarKit.TEXT, p);
        if (gpsNote != null) {
            // Sin GPS al día, el avance por la ruta (y lo que falta) se queda donde estaba: se dice.
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(21);
            CarKit.text(cv, CarKit.ellipsize(gpsNote, r.width(), p), x0, cy0 + 114, 21, CarKit.RED, CarKit.MEDIUM, p, Paint.Align.LEFT);
        } else {
            // De dónde sale la previsión: las cuestas de lo que falta (o, al llegar, lo previsto y lo real), a la izquierda
            // de la nota «ruta aproximada».
            String why = explain(pl, prog);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(18);
            float right = CarKit.text(cv, Str.get(R.string.hql_route_source), r.right, cy0 + 114, 18, CarKit.FAINT, CarKit.MEDIUM, p,
                    Paint.Align.RIGHT) + 30;
            if (!why.isEmpty()) {
                p.setTextSize(21);
                CarKit.text(cv, CarKit.ellipsize(why, r.width() - right, p), x0, cy0 + 114, 21, CarKit.DIM, CarKit.MEDIUM, p,
                        Paint.Align.LEFT);
            }
        }
        // Perfil, viento, tramos y leyenda.
        float gx0 = x0 + 74;
        RectF chart = chartRect;
        chart.set(gx0, y0 + 222, r.right, y0 + 530);
        profile(cv, chart, pl, prog, p);
        float wy = chart.bottom + 36;
        for (int i = 1; i < pl.n; i++) {
            double hw = pl.headwind[i];
            p.setColor(hw > 15 ? CarKit.RED : hw > 5 ? CarKit.AMBER : hw < -5 ? CarKit.BLUE : CarKit.SURFACE_TOP);
            cv.drawRect(xFor(pl, i - 1, chart), wy, xFor(pl, i, chart) + 1, wy + 10, p);
        }
        CarKit.text(cv, Str.get(R.string.hql_wind), x0, wy + 11, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        segRect.set(gx0, wy + 26, r.right, r.bottom - 40);
        segments(cv, segRect, pl, prog, p);
        CarKit.text(cv, "kWh/100", x0, r.bottom - 60, 16, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        legend(cv, x0, r.bottom - 4, r.right, p);
    }

    private static float xFor(RoutePlanner.Plan pl, int i, RectF c) {
        return (float) (c.left + pl.km[i] / Math.max(0.1, pl.totalKm) * c.width());
    }

    private float yFor(double e, RectF c) {
        return (float) (c.bottom - (e - minE) / (maxE - minE) * c.height());
    }

    /** Recalcula las áreas por pendiente y la cresta si cambió la ruta o el tamaño. */
    private void buildProfile(RoutePlanner.Plan pl, RectF c) {
        if (pl == cachedPlan && c.width() == cachedW) return;
        cachedPlan = pl;
        cachedW = c.width();
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (double e : pl.elev) {
            lo = Math.min(lo, e);
            hi = Math.max(hi, e);
        }
        double span = Math.max(60, hi - lo);
        minE = (float) Math.max(lo - span * 0.08, Math.min(0, lo));
        if (lo > 0) minE = (float) Math.max(0, lo - span * 0.08);
        maxE = (float) (hi + span * 0.12);
        for (Path path : slopePaths) path.rewind();
        for (Path path : ridgePaths) path.rewind();
        // Pendiente de cada tramo, media de unos puntos alrededor (el relieve de una ruta larga cambia poco de un
        // punto al siguiente); los tramos seguidos del mismo color van en un solo polígono (sin costuras).
        double window = Math.max(0.6, pl.totalKm / 60);
        int[] bucket = new int[pl.n];
        for (int i = 1; i < pl.n; i++) bucket[i] = slopeBucket(pl, i, window);
        // Los tramos muy cortos toman el color del anterior: sin rayas en un relieve con ruido.
        double minRun = pl.totalKm / 90;
        int from = 1;
        for (int i = 2; i <= pl.n; i++) {
            if (i < pl.n && bucket[i] == bucket[from]) continue;
            if (from > 1 && pl.km[i - 1] - pl.km[from - 1] < minRun) {
                for (int k = from; k < i; k++) bucket[k] = bucket[from - 1];
            }
            from = i;
        }
        int run = -1;
        int runStart = 0;
        for (int i = 1; i <= pl.n; i++) {
            int b = i < pl.n ? bucket[i] : -2;
            if (b != run) {
                if (run >= 0) addRun(slopePaths[run], ridgePaths[run], pl, runStart, i - 1, c);
                run = b;
                runStart = i - 1;
            }
        }
        // Cada color, en degradado vertical: intenso arriba (en la cresta) y casi transparente abajo.
        for (int b = 0; b < slopeFills.length; b++) {
            slopeFills[b] = new LinearGradient(0, c.top, 0, c.bottom, CarKit.alpha(SLOPE_COLORS[b], b == 2 ? 0.42f : 0.62f),
                    CarKit.alpha(SLOPE_COLORS[b], 0.04f), Shader.TileMode.CLAMP);
        }
    }

    /**
     * Color de pendiente del tramo i-1 → i (bajada fuerte, bajada, llano, subida, subida fuerte), con la pendiente media
     * de unos km alrededor: el relieve de una ruta larga cambia poco de un punto al siguiente.
     */
    private static int slopeBucket(RoutePlanner.Plan pl, int i, double windowKm) {
        int a = i - 1;
        int b = i;
        while (a > 0 && pl.km[i] - pl.km[a] < windowKm / 2) a--;
        while (b < pl.n - 1 && pl.km[b] - pl.km[i] < windowKm / 2) b++;
        double dkm = pl.km[b] - pl.km[a];
        double grade = dkm > 0 ? (pl.elev[b] - pl.elev[a]) / (dkm * 1000) * 100 : 0;
        return grade <= -1.0 ? 0 : grade <= -0.25 ? 1 : grade < 0.25 ? 2 : grade < 1.0 ? 3 : 4;
    }

    /** Polígono del relieve entre los puntos from y to (cerrado por abajo) y su tramo de cresta. */
    private void addRun(Path path, Path top, RoutePlanner.Plan pl, int from, int to, RectF c) {
        path.moveTo(xFor(pl, from, c), c.bottom);
        top.moveTo(xFor(pl, from, c), yFor(pl.elev[from], c));
        for (int k = from; k <= to; k++) {
            path.lineTo(xFor(pl, k, c), yFor(pl.elev[k], c));
            if (k > from) top.lineTo(xFor(pl, k, c), yFor(pl.elev[k], c));
        }
        path.lineTo(xFor(pl, to, c), c.bottom);
        path.close();
    }

    private void profile(Canvas cv, RectF c, RoutePlanner.Plan pl, int prog, Paint p) {
        buildProfile(pl, c);
        // Rejilla y ejes.
        p.setColor(CarKit.OUTLINE);
        for (int k = 0; k <= 2; k++) {
            float y = c.top + c.height() * k / 2f;
            cv.drawRect(c.left, y - 1, c.right, y + 1, p);
        }
        CarKit.text(cv, String.format(Locale.getDefault(), "%.0f m", maxE), c.left - 10, c.top + 7, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        CarKit.text(cv, String.format(Locale.getDefault(), "%.0f m", minE), c.left - 10, c.bottom, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
        // Relieve por pendiente: cada color en su degradado, y la cresta con el color de la pendiente.
        p.setColor(0xFFFFFFFF);
        for (int b = 0; b < slopePaths.length; b++) {
            p.setShader(slopeFills[b]);
            cv.drawPath(slopePaths[b], p);
        }
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(4f);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int b = 0; b < ridgePaths.length; b++) {
            p.setColor(b == 2 ? 0xFF9FB4C8 : SLOPE_COLORS[b]);
            cv.drawPath(ridgePaths[b], p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStyle(Paint.Style.FILL);
        // Lo ya recorrido, apagado.
        float xp = xFor(pl, prog, c);
        p.setColor(CarKit.alpha(CarKit.SURFACE, 0.5f));
        cv.drawRect(c.left, c.top, xp, c.bottom + 1, p);
        // Cargadores sobre el eje.
        double here = pl.km[prog];
        java.util.Set<RoutePlanner.Charger> planStops = new java.util.HashSet<>();
        if (chargePlan != null) for (ChargePlanner.Stop st : chargePlan.stops) planStops.add(st.charger);
        for (RoutePlanner.Charger ch : pl.chargers) {
            if (!wanted(ch)) continue;
            float x = (float) (c.left + ch.kmAlong / Math.max(0.1, pl.totalKm) * c.width());
            if (planStops.contains(ch)) {
                // Parada del plan: anillo del acento alrededor del rayo.
                p.setColor(CarKit.alpha(CarKit.ACCENT, 0.35f));
                cv.drawCircle(x, c.bottom - 18, 22, p);
            }
            boolean ahead = ch.kmAlong >= here - 0.5;
            p.setColor(ahead ? CarKit.SURFACE_TOP : CarKit.SURFACE_HI);
            cv.drawCircle(x, c.bottom - 18, 15, p);
            CarIcons.bolt(cv, x, c.bottom - 18, 20, ahead ? (ch.maxKw >= 100 ? CarKit.GREEN : CarKit.ACCENT) : CarKit.MUTED, p);
        }
        // Batería prevista a lo largo de la ruta (escala 0-100 % en el alto del gráfico).
        if (!Double.isNaN(soc)) {
            socLine.rewind();
            double last = soc;
            boolean hold = reev();
            ChargePlanner.Result cp = chargePlan;
            // Con paradas (aunque después no se llegue): la línea sube en cada una.
            boolean planned = cp != null && !cp.stops.isEmpty();
            int stop = 0;
            double[] kc = cp != null ? cp.kwhCum : pl.kwhCum;
            for (int i = prog; i < pl.n; i++) {
                double sp = soc - (kc[i] - kc[prog]) / capKwh * 100;
                if (hold) sp = Math.max(Math.min(soc, REEV_FLOOR_PCT), sp);
                if (planned) {
                    // Al pasar por una parada, la línea sube en vertical hasta lo cargado.
                    while (stop < cp.stops.size() && cp.stops.get(stop).km <= pl.km[i]) {
                        ChargePlanner.Stop st = cp.stops.get(stop++);
                        float xs = (float) (c.left + st.km / Math.max(0.1, pl.totalKm) * c.width());
                        socLine.lineTo(xs, (float) (c.bottom - Math.max(0, Math.min(100, st.arrivePct)) / 100 * c.height()));
                        socLine.lineTo(xs, (float) (c.bottom - Math.max(0, Math.min(100, st.departPct)) / 100 * c.height()));
                    }
                    sp = cp.pctAt(pl.km[i]);
                }
                last = sp;
                float y = (float) (c.bottom - Math.max(0, Math.min(100, sp)) / 100 * c.height());
                if (i == prog) socLine.moveTo(xFor(pl, i, c), y);
                else socLine.lineTo(xFor(pl, i, c), y);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(CarKit.ACCENT);
            cv.drawPath(socLine, p);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeCap(Paint.Cap.BUTT);
            float ly = (float) (c.bottom - Math.max(0, Math.min(100, last)) / 100 * c.height());
            String lt = String.format(Locale.getDefault(), "%.0f %%", Math.max(0, last));
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(22);
            float tw = p.measureText(lt) + 20;
            tmp.set(c.right - tw, ly - 44, c.right, ly - 12);
            p.setColor(CarKit.SURFACE_TOP);
            cv.drawRoundRect(tmp, 10, 10, p);
            CarKit.text(cv, lt, tmp.centerX(), tmp.bottom - 9, 22, levelColor(last), CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
        // Estás aquí.
        float yp = yFor(pl.elev[prog], c);
        p.setColor(CarKit.alpha(CarKit.TEXT, 0.55f));
        cv.drawRect(xp - 1, c.top + 30, xp + 1, c.bottom, p);
        p.setColor(CarKit.alpha(CarKit.ACCENT, 0.3f));
        cv.drawCircle(xp, yp, 17, p);
        p.setColor(CarKit.ACCENT);
        cv.drawCircle(xp, yp, 9, p);
        String here2 = Str.get(R.string.hql_here_km, here);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(20);
        float tw = p.measureText(here2) + 22;
        float lx = Math.max(c.left, Math.min(c.right - tw, xp - tw / 2));
        tmp.set(lx, c.top - 4, lx + tw, c.top + 28);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawRoundRect(tmp, 10, 10, p);
        CarKit.text(cv, here2, tmp.centerX(), tmp.bottom - 9, 20, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        // Km bajo el eje.
        double stepKm = pl.totalKm > 300 ? 100 : pl.totalKm > 120 ? 50 : pl.totalKm > 40 ? 10 : 5;
        for (double k = 0; k <= pl.totalKm + 0.1; k += stepKm) {
            float x = (float) (c.left + k / pl.totalKm * c.width());
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f", k), x, c.bottom + 24, 18, CarKit.FAINT, CarKit.MEDIUM, p,
                    k == 0 ? Paint.Align.LEFT : Paint.Align.CENTER);
        }
        CarKit.text(cv, "km", c.right, c.bottom + 24, 18, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.RIGHT);
    }

    /**
     * Consumo previsto de cada tramo (kWh/100 km); lo ya recorrido, apagado. Con datos del coche, su media como línea de
     * referencia: así se ve qué tramos gastarán más o menos de lo normal en ese coche.
     */
    private void segments(Canvas cv, RectF c, RoutePlanner.Plan pl, int prog, Paint p) {
        double len = pl.totalKm / SEGMENTS;
        double avg = carAverage();
        double max = Double.isNaN(avg) ? 30 : Math.max(30, avg * 1.25);
        double[] v = new double[SEGMENTS];
        for (int j = 0; j < SEGMENTS; j++) {
            double a = kwhAt(pl, j * len);
            double b = kwhAt(pl, (j + 1) * len);
            v[j] = (b - a) / len * 100;
            max = Math.max(max, v[j]);
        }
        float w = c.width() / SEGMENTS;
        double here = pl.km[prog];
        if (!Double.isNaN(avg)) averageLine(cv, c, avg, max, p);
        for (int j = 0; j < SEGMENTS; j++) {
            float h = (float) (Math.max(0, v[j]) / max * (c.height() - 20));
            float x = c.left + j * w + 4;
            boolean past = (j + 1) * len <= here;
            int col = v[j] < 15 ? CarKit.GREEN : v[j] < 21 ? CarKit.ACCENT : v[j] < 27 ? CarKit.AMBER : CarKit.RED;
            p.setColor(CarKit.alpha(col, past ? 0.25f : 0.85f));
            tmp.set(x, c.bottom - Math.max(4, h), x + w - 8, c.bottom);
            cv.drawRoundRect(tmp, 5, 5, p);
            CarKit.text(cv, String.format(Locale.getDefault(), "%.0f", v[j]), x + (w - 8) / 2, tmp.top - 6, 17,
                    past ? CarKit.MUTED : CarKit.DIM, CarKit.MEDIUM, p, Paint.Align.CENTER);
        }
    }

    /** La media según el coche como línea a trazos por detrás de las barras (la explica la leyenda). */
    private void averageLine(Canvas cv, RectF c, double avg, double max, Paint p) {
        float ya = (float) (c.bottom - avg / max * (c.height() - 20));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(CarKit.alpha(CarKit.TEXT, 0.7f));
        p.setPathEffect(dash);
        cv.drawLine(c.left, ya, c.right, ya, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
    }

    private static double kwhAt(RoutePlanner.Plan pl, double km) {
        if (km <= 0) return 0;
        for (int i = 1; i < pl.n; i++) {
            if (pl.km[i] >= km) {
                double f = (km - pl.km[i - 1]) / Math.max(1e-6, pl.km[i] - pl.km[i - 1]);
                return pl.kwhCum[i - 1] + (pl.kwhCum[i] - pl.kwhCum[i - 1]) * f;
            }
        }
        return pl.kwhCum[pl.n - 1];
    }

    private void legend(Canvas cv, float x, float y, float right, Paint p) {
        x = swatch(cv, x, y, CarKit.RED, Str.get(R.string.hql_legend_up), p);
        x = swatch(cv, x, y, CarKit.GREEN, Str.get(R.string.hql_legend_down), p);
        x = swatch(cv, x, y, CarKit.AMBER, Str.get(R.string.hql_legend_headwind), p);
        x = swatch(cv, x, y, CarKit.BLUE, Str.get(R.string.hql_legend_tailwind), p);
        p.setColor(CarKit.ACCENT);
        cv.drawRect(x, y - 9, x + 22, y - 5, p);
        x += 30 + CarKit.text(cv, Str.get(R.string.hql_legend_battery), x + 30, y, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT) + 22;
        // La media según el coche (la línea a trazos sobre las barras), si hay datos y cabe.
        double avg = carAverage();
        if (!Double.isNaN(avg)) {
            String t = Str.get(R.string.hql_legend_car_avg, avg);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(19);
            if (x + 30 + p.measureText(t) <= right) {
                p.setColor(CarKit.TEXT);
                cv.drawRect(x, y - 9, x + 8, y - 5, p);
                cv.drawRect(x + 13, y - 9, x + 22, y - 5, p);
                CarKit.text(cv, t, x + 30, y, 19, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
            }
        }
    }

    private float swatch(Canvas cv, float x, float y, int color, String s, Paint p) {
        p.setColor(color);
        tmp.set(x, y - 15, x + 15, y);
        cv.drawRoundRect(tmp, 4, 4, p);
        float w = CarKit.text(cv, s, x + 23, y, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        return x + 23 + w + 22;
    }

    /** Sin ruta: un camino que sube hasta la chincheta, la pregunta y el estado (calculando, error…). */
    private void paintEmpty(Canvas cv, RectF r, Paint p, String dest) {
        float cx = r.centerX();
        float top = r.top + 70;
        road.rewind();
        road.moveTo(cx - 230, top + 230);
        road.cubicTo(cx - 60, top + 230, cx - 150, top + 120, cx, top + 120);
        road.cubicTo(cx + 120, top + 120, cx + 40, top + 60, cx + 70, top + 40);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(34);
        p.setColor(CarKit.SURFACE_TOP);
        cv.drawPath(road, p);
        p.setStrokeWidth(4);
        p.setColor(CarKit.MUTED);
        p.setPathEffect(dash);
        cv.drawPath(road, p);
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        CarIcons.pin(cv, cx + 74, top + 6, 64, CarKit.ACCENT, CarKit.SURFACE, p);
        CarKit.text(cv, Str.get(R.string.hql_route_where), cx, top + 310, 42, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        String msg = status.isEmpty() ? Str.get(R.string.hql_calculating) : status;
        para.draw(cv, msg, r.left + 80, top + 336, (int) r.width() - 160, true);
    }

    // ------------------------------------------------------------------ batería y tiempo

    private void paintBattery(Canvas cv, RectF r, Paint p) {
        double arr = arrivalPct();
        float rad = Math.min(r.height() / 2f, 98) - 4;
        float cx = r.left + rad + 4;
        float cy = r.top + r.height() / 2f;
        tmp.set(cx - rad, cy - rad, cx + rad, cy + rad);
        int col = Double.isNaN(arr) ? CarKit.MUTED : levelColor(arr);
        CarKit.ring(cv, tmp, 135, 270, Double.isNaN(arr) ? 0 : (float) Math.max(0, arr) / 100f, 18, CarKit.SURFACE_TOP, col, p);
        if (Double.isNaN(arr)) {
            CarKit.text(cv, "—", cx, cy + 16, 50, CarKit.MUTED, CarKit.REGULAR, p, Paint.Align.CENTER);
        } else {
            CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", Math.max(0, arr)), "%", cx, cy + 18, 56, col, CarKit.REGULAR, p, Paint.Align.CENTER);
        }
        CarKit.text(cv, Str.get(R.string.hql_route_on_arrival), cx, cy + rad - 6, 19, CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.CENTER);
        float tx = cx + rad + 30;
        float lw = CarKit.label(cv, Str.get(R.string.hql_now), tx, r.top + 18, p);
        // De dónde sale: el dato real del coche (con su edad) o la estimación desde el % indicado.
        if (!Double.isNaN(soc)) {
            String src = realSoc ? CarCloud.realLabel(cloud, DemoMode.wallClockMs())
                    : Str.get(RoutePlanner.testMode() ? R.string.hql_route_test_soc : R.string.hql_cloud_estimated);
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(19);
            CarKit.text(cv, CarKit.ellipsize(src, r.right - tx - lw - 12, p), tx + lw + 12, r.top + 18, 19,
                    realSoc ? CarKit.ACCENT : CarKit.FAINT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        }
        CarKit.number(cv, Double.isNaN(soc) ? "—" : String.format(Locale.getDefault(), "%.0f", soc), "%", tx, r.top + 70, 50, CarKit.TEXT,
                CarKit.REGULAR, p, Paint.Align.LEFT);
        String st;
        int sc;
        if (Double.isNaN(soc)) {
            st = Str.get(R.string.hql_route_set_battery);
            sc = CarKit.DIM;
        } else if (Double.isNaN(arr)) {
            st = "";
            sc = CarKit.DIM;
        } else if (reevLiters() > 0.05) {
            st = Str.get(R.string.hql_route_reev_fuel, reevLiters());
            sc = CarKit.AMBER;
        } else if (chargePlan != null && chargePlan.outcome == ChargePlanner.Outcome.PLANNED) {
            st = Str.get(R.string.hql_route_battery_plan, DriveTab.duration(Math.round(chargePlan.chargeMinutes * 60)));
            sc = CarKit.ACCENT;
        } else if (arr < 0) {
            st = Str.get(R.string.hql_route_battery_no);
            sc = CarKit.RED;
        } else if (arr < 15) {
            st = Str.get(R.string.hql_route_battery_tight);
            sc = CarKit.AMBER;
        } else {
            st = Str.get(R.string.hql_route_battery_ok);
            sc = CarKit.GREEN;
        }
        statusPara.color(sc);
        statusPara.draw(cv, st, tx, r.top + 86, (int) (r.right - tx), false);
    }

    private void paintWeather(Canvas cv, RectF r, Paint p) {
        RoutePlanner.Plan pl = plan;
        if (pl == null || Double.isNaN(pl.destTemp)) {
            CarKit.text(cv, pl == null ? "—" : Str.get(R.string.hql_route_no_forecast), r.left, r.centerY() + 10, 26, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
            return;
        }
        CarIcons.weather(cv, pl.destCode, r.left + 40, r.centerY(), 76, p);
        float tx = r.left + 100;
        float w = CarKit.number(cv, String.format(Locale.getDefault(), "%.0f", pl.destTemp), "°C", tx, r.top + 40, 46, CarKit.TEXT, CarKit.REGULAR, p, Paint.Align.LEFT);
        p.setTypeface(CarKit.MEDIUM);
        p.setTextSize(25);
        CarKit.text(cv, CarKit.ellipsize(weatherText(pl.destCode), r.right - tx - w - 20, p), tx + w + 18, r.top + 38, 25, CarKit.TEXT, CarKit.MEDIUM, p, Paint.Align.LEFT);
        CarKit.text(cv, Str.get(R.string.hql_wind_rain, pl.destWind, pl.destRain), tx, r.bottom, 22, CarKit.DIM, CarKit.REGULAR, p, Paint.Align.LEFT);
    }
}
