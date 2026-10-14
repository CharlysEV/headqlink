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
    /** Con potencia mínima, también los cargadores sin potencia confirmada (Config.chargerIncludeUnknown). */
    private boolean chargerUnknown = true;
    private TextView filterSummary;
    /** Coche virtual: «Desde: GPS» / «Desde: Sevilla». */
    private TextView originPill;
    /** El plan de carga con el % de ahora (null sin % de batería, sin ruta o en un REEV) y de qué datos salió. */
    private ChargePlanner.Result chargePlan;
    private TextView search;
    /** Los botones de arriba a la derecha (buscar, Google Maps, quitar): su ancho real recorta el nombre del destino. */
    private LinearLayout actionsRow;
    private TextView goMaps;
    /** Mapa con el trazado del plan (para compararlo con el de Google Maps). */
    private TextView mapPill;
    private TextView clear;
    private TextView cta;
    /** El viaje: paradas intermedias, ida y vuelta y guardarlo («Viaje · 2 ⇄»). */
    private TextView tripPill;
    /** Sin ruta: Casa, Trabajo y Guardados, grandes, arriba a la izquierda. */
    private LinearLayout quickRow;
    private TextView homePill;
    private TextView workPill;
    /** Los guardados (SavedPlaces) y el JSON del que salen (se vuelven a leer si cambia). */
    private java.util.List<SavedPlaces.Saved> saved = new java.util.ArrayList<>();
    private String savedJson;
    /** Favoritos y vetados (ChargerMarks) y su JSON. */
    private java.util.List<ChargerMarks.Mark> marks = new java.util.ArrayList<>();
    private String marksJson = "";
    /** Cargadores cerca del destino (cargar allí): de qué ruta son, y si se están pidiendo a OpenStreetMap. */
    private RoutePlanner.Plan destNearFor;
    private java.util.List<RoutePlanner.Charger> destNear = new java.util.ArrayList<>();
    private boolean destFetching;
    /** El panel del viaje abierto (para rehacerlo al volver del buscador), o null. */
    private Runnable tripRefresh;
    /** El panel de guardados abierto (para rehacerlo), o null. */
    private Runnable savedRefresh;
    private int shownChargersFor = -1;
    private RoutePlanner.Plan plan;
    private double soc = Double.NaN;
    /** El % de ahora es el real del coche (nube de Leapmotor), no el indicado. */
    private boolean realSoc;
    /** Capacidad con la que se pasa de kWh a %: la de la variante con datos reales; si no, la del modelo. */
    private double capKwh = EnergyModel.usableKwh();
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
            search.setText(Str.get(R.string.hql_search));
            actions.addView(originPill);
            actions.addView(search, spaced());
        } else {
            actions.addView(search);
        }
        tripPill = CarKit.pill(ctx, Str.get(R.string.hql_pl_trip), false);
        tripPill.setOnClickListener(v -> openTrip());
        actions.addView(tripPill, spaced());
        mapPill = CarKit.pill(ctx, Str.get(R.string.hql_route_map), false);
        mapPill.setOnClickListener(v -> openMap());
        actions.addView(mapPill, spaced());
        actions.addView(goMaps, spaced());
        actions.addView(clear, spaced());
        routeCard.addView(actions, CarKit.at(Gravity.TOP | Gravity.END, 0, 0, 0, 0));
        search.setOnClickListener(v -> openSearch(false));
        goMaps.setOnClickListener(v -> {
            RoutePlanner.Place m = RoutePlanner.manualDestination();
            if (m == null) return;
            // Con plan de carga, el navegador lleva sus paradas (si quedan por delante); si no, directo al destino.
            ChargePlanner.Result cp = chargePlan;
            RoutePlanner.Plan pl = plan;
            // Un viaje de varios tramos lleva también sus paradas y la vuelta.
            if (pl != null && ((cp != null && cp.next() != null) || pl.multi())) RoutePlanner.navigateWithStops(ctx, pl, cp);
            else navigateTo(m.lat, m.lon);
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
        // Sin ruta: Casa, Trabajo y los guardados, a un toque (arriba a la izquierda).
        quickRow = CarKit.row(ctx);
        homePill = bigPill("");
        workPill = bigPill("");
        TextView savedPill = bigPill("★ " + Str.get(R.string.hql_pl_saved));
        quickRow.addView(homePill);
        quickRow.addView(workPill, spaced());
        quickRow.addView(savedPill, spaced());
        routeCard.addView(quickRow, CarKit.at(Gravity.TOP | Gravity.START, 0, 0, 0, 0));
        homePill.setOnClickListener(v -> goTagged(SavedPlaces.HOME));
        workPill.setOnClickListener(v -> goTagged(SavedPlaces.WORK));
        savedPill.setOnClickListener(v -> openSaved());
        // Cargando: la carga en directo encima del perfil de la ruta (con «Ver ruta» para volver a él).
        routeCard.addView(buildLive(), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        liveShow = CarKit.pill(ctx, Str.get(R.string.hql_live_block_title), true);
        liveShow.setOnClickListener(v -> {
            liveHiddenFor = null;
            tick();
        });
        actions.addView(liveShow, 0);
        actionsRow.bringToFront();

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
        chargerUnknown = cfg0.chargerIncludeUnknown();
        chargerNets = ChargerFilter.parseNetworks(cfg0.chargerNetworks());
        LinearLayout chCol = CarKit.col(ctx);
        // Filtro: potencia mínima y redes (como en los planificadores de rutas).
        LinearLayout fRow = CarKit.row(ctx);
        fRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView filter = CarKit.pill(ctx, Str.get(R.string.hql_charger_filter), false);
        filter.setOnClickListener(v -> openChargerFilter());
        fRow.addView(filter);
        TextView near = CarKit.pill(ctx, Str.get(R.string.hql_near_button), false);
        near.setOnClickListener(v -> openNearby());
        fRow.addView(near, spaced());
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
        // Tocar la tarjeta (su título o una fila): la ventana con todos los cargadores de la ruta.
        ch.setOnClickListener(v -> openChargerList(false));
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
        openSearch(origin ? S_ORIGIN : S_DEST);
    }

    /** Qué se busca: el destino, la salida (coche virtual), una parada del viaje, Casa o Trabajo. */
    private static final int S_DEST = 0;
    private static final int S_ORIGIN = 1;
    private static final int S_VIA = 2;
    private static final int S_HOME = 3;
    private static final int S_WORK = 4;

    private static String hintFor(int mode) {
        switch (mode) {
            case S_ORIGIN:
                return Str.get(R.string.hql_route_origin_hint);
            case S_VIA:
                return Str.get(R.string.hql_pl_via_hint);
            case S_HOME:
                return Str.get(R.string.hql_pl_home_hint);
            case S_WORK:
                return Str.get(R.string.hql_pl_work_hint);
            default:
                return Str.get(R.string.hql_route_query_hint);
        }
    }

    private void openSearch(int mode) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        LinearLayout bar = CarStyle.bar(ctx);
        bar.setBackgroundColor(CarKit.SURFACE);
        TextView query = CarStyle.text(ctx, "", 30, CarKit.TEXT);
        query.setHint(hintFor(mode));
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
        SearchState st = new SearchState(panel, query, results, mode);
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
                // «Ocultar»: más sitio para los resultados; tocar la caja lo vuelve a sacar.
                if (st.kb != null) st.kb.setVisibility(View.GONE);
            }
        });
        st.kb = kb;
        query.setOnClickListener(v -> kb.setVisibility(View.VISIBLE));
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
        /** S_DEST, S_ORIGIN, S_VIA, S_HOME o S_WORK. */
        final int mode;

        SearchState(View panel, TextView query, LinearLayout results, int mode) {
            this.panel = panel;
            this.query = query;
            this.results = results;
            this.mode = mode;
            this.origin = mode == S_ORIGIN;
        }

        String hint() {
            return hintFor(mode);
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
            double[] pos = planner.position();
            // Buscando el destino: primero los guardados (Casa, Trabajo, los de nombre), a un toque.
            java.util.List<SavedPlaces.Saved> sv = mode == S_DEST ? savedList() : new java.util.ArrayList<>();
            if (!sv.isEmpty()) {
                results.addView(sub(Str.get(R.string.hql_pl_saved)));
                for (SavedPlaces.Saved x : sv) results.addView(savedItem(this, x, pos), itemParams());
            }
            results.addView(sub(Str.get(rec.isEmpty() ? R.string.hql_search_tip : R.string.hql_search_recent)));
            if (origin) results.addView(gpsItem(this), itemParams());
            for (RoutePlanner.Place pl : rec) results.addView(item(this, pl, pos), itemParams());
        }

        /** Un subtítulo de la lista (sin quitar lo que hay). */
        TextView sub(String text) {
            TextView h = CarStyle.text(ctx, text, 22, CarKit.FAINT);
            h.setPadding(6, 4, 6, 12);
            return h;
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
        if (st.mode == S_DEST || st.mode == S_VIA) {
            // Guardar (como Casa, Trabajo o con su nombre) sin elegirlo como destino.
            TextView save = CarKit.pill(ctx, "☆", false);
            save.setMinWidth(76);
            save.setOnClickListener(v -> openSaveChooser(pl, null, false));
            LinearLayout.LayoutParams lp = spaced();
            lp.leftMargin = 18;
            item.addView(save, lp);
        }
        item.setOnClickListener(v -> {
            Config c = new Config(ctx);
            if (st.mode == S_HOME || st.mode == S_WORK) {
                // Fijar Casa o Trabajo: se guarda y ya (no es el destino).
                String tag = st.mode == S_HOME ? SavedPlaces.HOME : SavedPlaces.WORK;
                c.setSavedPlaces(SavedPlaces.setTagged(c.savedPlaces(), pl, tag));
                L.i("ruta: " + (st.mode == S_HOME ? "Casa" : "Trabajo") + " fijado en el coche"); // sin el sitio
                st.close();
                if (savedRefresh != null) savedRefresh.run();
                flash(Str.get(R.string.hql_pl_saved_ok, tagLabel(tag)));
                tick();
                return;
            }
            c.setRecentPlaces(PlaceSearch.remember(c.recentPlaces(), pl));
            if (st.origin) {
                RoutePlanner.setTestOrigin(pl);
            } else if (st.mode == S_VIA) {
                // Una parada más del viaje, antes del destino.
                java.util.List<RoutePlanner.Place> v2 = RoutePlanner.vias();
                RoutePlanner.Place dest = RoutePlanner.manualDestination();
                if (dest != null && v2.size() < SavedPlaces.MAX_VIA) {
                    v2.add(pl);
                    RoutePlanner.setTrip(dest, v2, RoutePlanner.roundTrip());
                }
            } else {
                L.i("ruta: destino elegido en el coche"); // sin el nombre: el log se exporta (qdauto §7.5)
                RoutePlanner.setManualDestination(pl);
            }
            st.close();
            if (tripRefresh != null) tripRefresh.run();
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
        capKwh = realSoc || (RoutePlanner.testMode() && cloud.hasData()) ? cloud.capacityKwh : EnergyModel.usableKwh();
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
        // El viaje (paradas, ida y vuelta): «Viaje · 2 ⇄».
        tripPill.setVisibility(manual && !DemoMode.active() ? View.VISIBLE : View.GONE);
        if (manual) {
            int nv = RoutePlanner.vias().size();
            String tt = Str.get(R.string.hql_pl_trip) + (nv > 0 ? " · " + nv : "") + (RoutePlanner.roundTrip() ? " ⇄" : "");
            if (!tt.contentEquals(tripPill.getText())) tripPill.setText(tt);
        }
        updateQuick(!have);
        String mj = new Config(ctx).chargerMarks();
        if (!mj.equals(marksJson)) {
            marksJson = mj;
            marks = ChargerMarks.parse(mj);
            shownChargersFor = -1;
        }
        mapPill.setVisibility(have ? View.VISIBLE : View.GONE);
        cta.setVisibility(have ? View.GONE : View.VISIBLE);
        search.setVisibility(have ? View.VISIBLE : View.GONE);
        routeCard.invalidate();
        batteryCard.invalidate();
        weatherCard.invalidate();
        planner.demoChargeTick();
        updateLive();
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

    // ------------------------------------------------------------------ guardados: Casa, Trabajo y los de nombre

    /** Botón grande (Casa, Trabajo, Guardados). */
    private TextView bigPill(String text) {
        TextView t = CarKit.pill(ctx, text, false);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 28);
        t.setPadding(36, 18, 36, 18);
        return t;
    }

    /** Un aviso corto abajo en el centro (2,5 s). */
    private void flash(String text) {
        if (root == null) return;
        TextView t = CarKit.pill(ctx, text, true);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26);
        t.setPadding(34, 16, 34, 16);
        root.addView(t, CarKit.at(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, 0, 0, 40));
        t.postDelayed(() -> root.removeView(t), 2500);
    }

    /** «Casa», «Trabajo» o "" (uno con nombre). */
    private static String tagLabel(String tag) {
        if (SavedPlaces.HOME.equals(tag)) return Str.get(R.string.hql_pl_home);
        if (SavedPlaces.WORK.equals(tag)) return Str.get(R.string.hql_pl_work);
        return "";
    }

    private static String tagIcon(String tag) {
        return SavedPlaces.HOME.equals(tag) ? "🏠" : SavedPlaces.WORK.equals(tag) ? "💼" : "★";
    }

    /** Los guardados (se vuelven a leer solo si cambió el JSON). */
    private java.util.List<SavedPlaces.Saved> savedList() {
        String j = new Config(ctx).savedPlaces();
        if (!j.equals(savedJson)) {
            savedJson = j;
            saved = SavedPlaces.parse(j);
        }
        return saved;
    }

    /** Sin ruta: los botones de Casa y Trabajo («Fijar Casa» si aún no está). */
    private void updateQuick(boolean show) {
        quickRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        java.util.List<SavedPlaces.Saved> sv = savedList();
        String h = "🏠 " + Str.get(SavedPlaces.tagged(sv, SavedPlaces.HOME) != null ? R.string.hql_pl_home : R.string.hql_pl_set_home);
        String w = "💼 " + Str.get(SavedPlaces.tagged(sv, SavedPlaces.WORK) != null ? R.string.hql_pl_work : R.string.hql_pl_set_work);
        if (!h.contentEquals(homePill.getText())) homePill.setText(h);
        if (!w.contentEquals(workPill.getText())) workPill.setText(w);
    }

    /** Casa o Trabajo con un toque: el destino si está guardado; si no, buscarlo para fijarlo. */
    private void goTagged(String tag) {
        SavedPlaces.Saved s = SavedPlaces.tagged(savedList(), tag);
        if (s == null) {
            openSearch(SavedPlaces.HOME.equals(tag) ? S_HOME : S_WORK);
            return;
        }
        goSaved(s);
    }

    /** Un guardado como destino (con sus paradas y su vuelta, si es un viaje). */
    private void goSaved(SavedPlaces.Saved s) {
        L.i("ruta: destino guardado elegido en el coche" + (s.isTrip() ? " (viaje con " + s.via.size() + " paradas"
                + (s.roundTrip ? ", ida y vuelta)" : ")") : "")); // sin el sitio: el log se exporta
        RoutePlanner.setTrip(s.place(), s.via, s.roundTrip);
        tick();
    }

    /** «🏠 Casa · Calle…» o «★ Nombre · con 2 paradas · ida y vuelta». */
    private String savedTitle(SavedPlaces.Saved s) {
        String label = tagLabel(s.tag);
        return tagIcon(s.tag) + " " + (label.isEmpty() ? s.name : label);
    }

    private String savedDetail(SavedPlaces.Saved s) {
        StringBuilder b = new StringBuilder();
        if (!s.tag.isEmpty()) b.append(s.name);
        else if (s.detail != null) b.append(s.detail);
        if (!s.via.isEmpty()) {
            b.insert(0, Str.get(s.via.size() == 1 ? R.string.hql_pl_via_one : R.string.hql_pl_via_many, s.via.size())
                    + (b.length() > 0 ? " · " : ""));
        }
        if (s.roundTrip) b.insert(0, Str.get(R.string.hql_pl_round) + " · ");
        return b.toString();
    }

    /** Un guardado en el buscador: al pulsarlo, es el destino. */
    private View savedItem(SearchState st, SavedPlaces.Saved s, double[] pos) {
        LinearLayout item = new LinearLayout(ctx);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(22, 14, 22, 14);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.ACCENT, 18));
        LinearLayout text = CarKit.col(ctx);
        TextView n = CarStyle.text(ctx, savedTitle(s), 27, CarKit.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView d = CarStyle.text(ctx, savedDetail(s), 20, CarKit.DIM);
        d.setSingleLine(true);
        d.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(n);
        text.addView(d);
        item.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!Double.isNaN(pos[0])) {
            double km = RoadInfo.dist(pos[0], pos[1], s.lat, s.lon) / 1000;
            TextView k = CarStyle.text(ctx, km < 10 ? String.format(Locale.getDefault(), "%.1f km", km)
                    : String.format(Locale.getDefault(), "%.0f km", km), 24, CarKit.ACCENT);
            k.setPadding(18, 0, 0, 0);
            item.addView(k);
        }
        item.setOnClickListener(v -> {
            st.close();
            goSaved(s);
        });
        return item;
    }

    /** Guardar un lugar (o el viaje: via y round) como Casa, como Trabajo o con su nombre. */
    private void openSaveChooser(RoutePlanner.Place pl, java.util.List<RoutePlanner.Place> via, boolean round) {
        LinearLayout panel = overlay(Str.get(R.string.hql_pl_save_title, pl.name), null);
        LinearLayout body = (LinearLayout) panel.getTag();
        Runnable close = () -> root.removeView(panel);
        boolean trip = (via != null && !via.isEmpty()) || round;
        body.addView(section(Str.get(R.string.hql_pl_save)));
        LinearLayout row = CarKit.row(ctx);
        row.addView(chip("🏠 " + Str.get(R.string.hql_pl_save_home), false, () -> {
            Config c = new Config(ctx);
            c.setSavedPlaces(SavedPlaces.setTagged(c.savedPlaces(), pl, SavedPlaces.HOME));
            L.i("ruta: Casa fijada en el coche");
            close.run();
            flash(Str.get(R.string.hql_pl_saved_ok, tagLabel(SavedPlaces.HOME)));
            tick();
        }), chipParams());
        row.addView(chip("💼 " + Str.get(R.string.hql_pl_save_work), false, () -> {
            Config c = new Config(ctx);
            c.setSavedPlaces(SavedPlaces.setTagged(c.savedPlaces(), pl, SavedPlaces.WORK));
            L.i("ruta: Trabajo fijado en el coche");
            close.run();
            flash(Str.get(R.string.hql_pl_saved_ok, tagLabel(SavedPlaces.WORK)));
            tick();
        }), chipParams());
        row.addView(chip("★ " + Str.get(trip ? R.string.hql_pl_save_trip : R.string.hql_pl_save_named), false, () -> {
            Config c = new Config(ctx);
            c.setSavedPlaces(SavedPlaces.add(c.savedPlaces(), pl, via, round, pl.name));
            L.i("ruta: guardado en el coche" + (trip ? " (viaje)" : ""));
            close.run();
            flash(Str.get(R.string.hql_pl_saved_ok, pl.name));
            if (savedRefresh != null) savedRefresh.run();
        }), chipParams());
        body.addView(row);
        if (trip) body.addView(hint(Str.get(R.string.hql_pl_save_trip_note)));
        root.addView(panel, CarStyle.match());
    }

    /**
     * Ventana a pantalla completa con un título y «Cerrar»; el cuerpo (con desplazamiento) va en su tag. Para los paneles
     * de guardados, viaje y guardar.
     */
    private LinearLayout overlay(String titleText, Runnable onClose) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        panel.setPadding(CarKit.PAD, 18, CarKit.PAD, CarKit.PAD);
        LinearLayout bar = CarKit.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CarStyle.text(ctx, titleText, 34, CarKit.TEXT);
        title.setTypeface(CarKit.MEDIUM);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = CarKit.pill(ctx, Str.get(R.string.hql_close), true);
        bar.addView(done);
        panel.addView(bar);
        LinearLayout body = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = 6;
        panel.addView(sv, svp);
        done.setOnClickListener(v -> {
            root.removeView(panel);
            if (onClose != null) onClose.run();
        });
        panel.setTag(body);
        return panel;
    }

    /** Los guardados: ir, renombrar y borrar; y fijar Casa o Trabajo si aún no están. */
    private void openSaved() {
        LinearLayout panel = overlay(Str.get(R.string.hql_pl_saved_title), () -> savedRefresh = null);
        LinearLayout body = (LinearLayout) panel.getTag();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();
            java.util.List<SavedPlaces.Saved> sv = savedList();
            LinearLayout top = CarKit.row(ctx);
            if (SavedPlaces.tagged(sv, SavedPlaces.HOME) == null) {
                top.addView(chip("🏠 " + Str.get(R.string.hql_pl_set_home), false, () -> openSearch(S_HOME)), chipParams());
            }
            if (SavedPlaces.tagged(sv, SavedPlaces.WORK) == null) {
                top.addView(chip("💼 " + Str.get(R.string.hql_pl_set_work), false, () -> openSearch(S_WORK)), chipParams());
            }
            if (top.getChildCount() > 0) body.addView(top);
            if (sv.isEmpty()) body.addView(hint(Str.get(R.string.hql_pl_saved_empty)));
            for (int i = 0; i < sv.size(); i++) body.addView(savedRow(sv.get(i), i, build[0], () -> root.removeView(panel)), itemParams());
        };
        savedRefresh = () -> {
            if (panel.getParent() != null) build[0].run();
        };
        build[0].run();
        root.addView(panel, CarStyle.match());
    }

    /** Una fila de los guardados: el nombre (y Casa o Trabajo), «Ir», «Renombrar» y «Borrar» (con un segundo toque). */
    private View savedRow(SavedPlaces.Saved s, int index, Runnable rebuild, Runnable closePanel) {
        LinearLayout item = CarKit.row(ctx);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(18, 12, 18, 12);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, s.tag.isEmpty() ? CarKit.OUTLINE : CarKit.ACCENT, 18));
        LinearLayout txt = CarKit.col(ctx);
        txt.setPadding(6, 0, 12, 0);
        TextView name = CarStyle.text(ctx, savedTitle(s), 26, CarKit.TEXT);
        name.setTypeface(CarKit.MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView detail = CarStyle.text(ctx, savedDetail(s), 21, CarKit.DIM);
        detail.setSingleLine(true);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(detail);
        item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), true);
        go.setOnClickListener(v -> {
            closePanel.run();
            savedRefresh = null;
            goSaved(s);
        });
        TextView ren = CarKit.pill(ctx, Str.get(R.string.hql_pl_rename), false);
        ren.setOnClickListener(v -> openRename(index, s.name, rebuild));
        TextView del = CarKit.pill(ctx, Str.get(R.string.hql_pl_delete), false);
        del.setOnClickListener(v -> {
            if (!Boolean.TRUE.equals(del.getTag())) {
                // Primer toque: se pide confirmar (que no se borre Casa por rozar el botón).
                del.setTag(Boolean.TRUE);
                del.setText(Str.get(R.string.hql_pl_delete_confirm));
                del.setTextColor(CarKit.RED);
                return;
            }
            Config c = new Config(ctx);
            c.setSavedPlaces(SavedPlaces.remove(c.savedPlaces(), index));
            L.i("ruta: guardado borrado en el coche");
            rebuild.run();
            tick();
        });
        item.addView(go);
        item.addView(ren, spaced());
        item.addView(del, spaced());
        return item;
    }

    /** Cambiar el nombre de un guardado con el teclado del coche. */
    private void openRename(int index, String current, Runnable done) {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        LinearLayout bar = CarStyle.bar(ctx);
        bar.setBackgroundColor(CarKit.SURFACE);
        StringBuilder buf = new StringBuilder(current == null ? "" : current);
        TextView box = CarStyle.text(ctx, buf.toString(), 30, CarKit.TEXT);
        box.setHint(Str.get(R.string.hql_pl_name_hint));
        box.setHintTextColor(CarKit.FAINT);
        box.setSingleLine(true);
        box.setEllipsize(android.text.TextUtils.TruncateAt.START);
        box.setPadding(26, 14, 26, 14);
        box.setBackground(CarKit.outlined(CarKit.SURFACE_HI, CarKit.ACCENT, 28));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.rightMargin = 10;
        bar.addView(box, blp);
        TextView ok = CarKit.pill(ctx, Str.get(R.string.hql_pl_save), true);
        TextView cancel = CarKit.pill(ctx, Str.get(R.string.hql_close), false);
        bar.addView(ok);
        bar.addView(cancel, spaced());
        panel.addView(bar);
        panel.addView(new View(ctx), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Runnable apply = () -> {
            String name = buf.toString().trim();
            if (!name.isEmpty()) {
                Config c = new Config(ctx);
                c.setSavedPlaces(SavedPlaces.rename(c.savedPlaces(), index, name));
            }
            root.removeView(panel);
            done.run();
        };
        CarKeyboard[] kbRef = new CarKeyboard[1];
        CarKeyboard kb = new CarKeyboard(ctx, new CarKeyboard.Listener() {
            @Override
            public void onText(String t) {
                buf.append(t);
                box.setText(buf);
                kbRef[0].showText(buf, Str.get(R.string.hql_pl_name_hint));
            }

            @Override
            public void onBackspace() {
                if (buf.length() > 0) buf.setLength(buf.length() - 1);
                box.setText(buf);
                kbRef[0].showText(buf, Str.get(R.string.hql_pl_name_hint));
            }

            @Override
            public void onEnter() {
                apply.run();
            }

            @Override
            public void onHide() {
                kbRef[0].setVisibility(View.GONE);
            }
        });
        kbRef[0] = kb;
        box.setOnClickListener(v -> kb.setVisibility(View.VISIBLE));
        panel.addView(kb);
        kb.showText(buf, Str.get(R.string.hql_pl_name_hint));
        ok.setOnClickListener(v -> apply.run());
        cancel.setOnClickListener(v -> root.removeView(panel));
        root.addView(panel, CarStyle.match());
    }

    // ------------------------------------------------------------------ viaje: paradas intermedias e ida y vuelta

    /** El viaje: desde dónde, las paradas (quitar, añadir), el destino, ida y vuelta y guardarlo. */
    private void openTrip() {
        if (RoutePlanner.manualDestination() == null) return;
        LinearLayout panel = overlay(Str.get(R.string.hql_pl_trip_title), () -> tripRefresh = null);
        LinearLayout body = (LinearLayout) panel.getTag();
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();
            RoutePlanner.Place dest = RoutePlanner.manualDestination();
            if (dest == null) {
                root.removeView(panel);
                return;
            }
            java.util.List<RoutePlanner.Place> via = RoutePlanner.vias();
            boolean round = RoutePlanner.roundTrip();
            RoutePlanner.Place o = RoutePlanner.testOrigin();
            body.addView(tripLine("📍", Str.get(R.string.hql_pl_from), o == null ? Str.get(R.string.hql_pl_your_location) : o.name, null));
            for (int i = 0; i < via.size(); i++) {
                final int k = i;
                body.addView(tripLine("•", Str.get(R.string.hql_pl_via_n, i + 1), via.get(i).name, () -> {
                    java.util.List<RoutePlanner.Place> v2 = RoutePlanner.vias();
                    if (k < v2.size()) v2.remove(k);
                    RoutePlanner.setTrip(RoutePlanner.manualDestination(), v2, RoutePlanner.roundTrip());
                    build[0].run();
                    tick();
                }), itemParams());
            }
            body.addView(tripLine("🏁", Str.get(R.string.hql_destination), dest.name, null), itemParams());
            if (round) body.addView(tripLine("⇄", Str.get(R.string.hql_pl_back), Str.get(R.string.hql_pl_your_location), null), itemParams());
            LinearLayout acts = CarKit.row(ctx);
            if (via.size() < SavedPlaces.MAX_VIA) {
                acts.addView(chip("＋ " + Str.get(R.string.hql_pl_add_via), false, () -> openSearch(S_VIA)), chipParams());
            }
            body.addView(acts);
            body.addView(section(Str.get(R.string.hql_pl_round_q)));
            LinearLayout rr = CarKit.row(ctx);
            rr.addView(chip(capital(Str.get(R.string.hql_yes)), round, () -> {
                RoutePlanner.setTrip(RoutePlanner.manualDestination(), RoutePlanner.vias(), true);
                build[0].run();
                tick();
            }), chipParams());
            rr.addView(chip(capital(Str.get(R.string.hql_no)), !round, () -> {
                RoutePlanner.setTrip(RoutePlanner.manualDestination(), RoutePlanner.vias(), false);
                build[0].run();
                tick();
            }), chipParams());
            body.addView(rr);
            body.addView(hint(Str.get(R.string.hql_pl_trip_note)));
            LinearLayout sv = CarKit.row(ctx);
            sv.setPadding(0, 24, 0, 0);
            sv.addView(chip("★ " + Str.get(R.string.hql_pl_save), false, () -> openSaveChooser(dest, via, round)), chipParams());
            body.addView(sv);
        };
        tripRefresh = () -> {
            if (panel.getParent() != null) build[0].run();
        };
        build[0].run();
        root.addView(panel, CarStyle.match());
    }

    /** Una línea del viaje: icono, qué es («Parada 1»), el sitio y, si se puede, «Quitar». */
    private View tripLine(String icon, String what, String place, Runnable remove) {
        LinearLayout item = CarKit.row(ctx);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(18, 12, 18, 12);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.OUTLINE, 18));
        TextView ic = CarStyle.text(ctx, icon, 28, CarKit.ACCENT);
        ic.setMinWidth(52);
        item.addView(ic);
        LinearLayout txt = CarKit.col(ctx);
        txt.setPadding(12, 0, 12, 0);
        txt.addView(CarStyle.text(ctx, what, 20, CarKit.FAINT));
        TextView n = CarStyle.text(ctx, place, 26, CarKit.TEXT);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(n);
        item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (remove != null) {
            TextView rm = CarKit.pill(ctx, Str.get(R.string.hql_remove), false);
            rm.setOnClickListener(v -> remove.run());
            item.addView(rm);
        }
        return item;
    }

    // ------------------------------------------------------------------ cargadores favoritos y vetados

    /** «☆» (sin marca), «★» (favorito) o «⊘» (vetado); al tocarlo, la siguiente. */
    private TextView markPill(RoutePlanner.Charger c) {
        TextView t = CarKit.pill(ctx, "", false);
        t.setMinWidth(72);
        paintMark(t, ChargerMarks.kindOf(marks, c));
        t.setOnClickListener(v -> {
            int k = ChargerMarks.next(ChargerMarks.kindOf(marks, c));
            Config cfg = new Config(ctx);
            cfg.setChargerMarks(ChargerMarks.set(cfg.chargerMarks(), c, k));
            marksJson = cfg.chargerMarks();
            marks = ChargerMarks.parse(marksJson);
            L.i("ruta: cargador " + (k == ChargerMarks.FAV ? "marcado como favorito" : k == ChargerMarks.BAN ? "vetado" : "sin marca"));
            paintMark(t, k);
            flash(Str.get(k == ChargerMarks.FAV ? R.string.hql_pl_mark_fav_done : k == ChargerMarks.BAN ? R.string.hql_pl_mark_ban_done
                    : R.string.hql_pl_mark_none_done, RoutePlanner.chargerName(c)));
            shownChargersFor = -1;
            tick();
        });
        return t;
    }

    private static void paintMark(TextView t, int kind) {
        t.setText(kind == ChargerMarks.FAV ? "★" : kind == ChargerMarks.BAN ? "⊘" : "☆");
        t.setTextColor(kind == ChargerMarks.FAV ? CarKit.AMBER : kind == ChargerMarks.BAN ? CarKit.RED : CarKit.DIM);
    }

    /** En el filtro: la lista de favoritos y vetados, para quitarles la marca. */
    private void addMarksSection(LinearLayout body, Runnable rebuild) {
        body.addView(section(Str.get(R.string.hql_pl_marks_title)));
        java.util.List<ChargerMarks.Mark> list = ChargerMarks.parse(new Config(ctx).chargerMarks());
        if (list.isEmpty()) {
            body.addView(hint(Str.get(R.string.hql_pl_marks_empty)));
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            ChargerMarks.Mark m = list.get(i);
            final int k = i;
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(18, 10, 18, 10);
            item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.OUTLINE, 18));
            TextView ic = CarStyle.text(ctx, m.kind == ChargerMarks.FAV ? "★" : "⊘", 28, m.kind == ChargerMarks.FAV ? CarKit.AMBER : CarKit.RED);
            ic.setMinWidth(52);
            item.addView(ic);
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(12, 0, 12, 0);
            TextView n = CarStyle.text(ctx, m.name.isEmpty() ? Str.get(R.string.hql_plan_charger) : m.name, 24, CarKit.TEXT);
            n.setSingleLine(true);
            n.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(n);
            txt.addView(CarStyle.text(ctx, Str.get(m.kind == ChargerMarks.FAV ? R.string.hql_pl_mark_fav : R.string.hql_pl_mark_ban), 20,
                    CarKit.DIM));
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView rm = CarKit.pill(ctx, Str.get(R.string.hql_remove), false);
            rm.setOnClickListener(v -> {
                Config c = new Config(ctx);
                c.setChargerMarks(ChargerMarks.remove(c.chargerMarks(), k));
                L.i("ruta: marca de un cargador quitada");
                rebuild.run();
                tick();
            });
            item.addView(rm);
            body.addView(item, itemParams());
        }
    }

    // ------------------------------------------------------------------ tramos del viaje, cargar en el destino y comer

    /** En un viaje de varios tramos: con cuánto se llega a cada punto que queda («Parada 1 · km 80 · llegas con 54 %»). */
    private void addLegs(RoutePlanner.Plan p, ChargePlanner.Result cp) {
        if (p.waypoints.isEmpty() || (!p.multi() && !p.roundTrip)) return;
        double[] at = ChargePlanner.arrivals(cp, p.waypointKm);
        for (int i = 0; i < p.waypoints.size() && i < at.length; i++) {
            if (p.waypointKm[i] <= cp.fromKm + ChargePlanner.HERE_KM) continue;
            RoutePlanner.Place w = p.waypoints.get(i);
            boolean last = i == p.waypoints.size() - 1;
            String what = i == p.destIndex ? w.name : last && p.roundTrip ? Str.get(R.string.hql_pl_back) : w.name;
            TextView t = hint(Str.get(R.string.hql_pl_leg_line, what, p.waypointKm[i], Math.max(0, at[i])));
            t.setTextColor(at[i] < 10 ? CarKit.AMBER : CarKit.DIM);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            chargers.addView(t);
        }
    }

    /** Radio en el que se buscan cargadores junto al destino (km). */
    static final double DEST_RADIUS_KM = 1.5;

    /**
     * Si al destino se llega con poca batería (menos del umbral del filtro) o, de ida y vuelta, con menos de lo que hace
     * falta para volver: los cargadores más cerca del destino (los favoritos primero), para cargar allí.
     */
    private void addDestChargers(RoutePlanner.Plan p, ChargePlanner.Result cp) {
        double destKm = p.waypoints.isEmpty() ? p.totalKm
                : p.destIndex >= 0 && p.destIndex < p.waypointKm.length ? p.waypointKm[p.destIndex] : Double.NaN;
        if (Double.isNaN(destKm) || destKm <= cp.fromKm + ChargePlanner.HERE_KM || DemoMode.active()) return;
        Config c = new Config(ctx);
        double pct = cp.pctAt(destKm);
        double ret = p.roundTrip && destKm < p.totalKm - 1 ? ChargePlanner.neededFrom(cp, destKm, c.planArrivePct()) : Double.NaN;
        if (!ChargePlanner.destChargeAdvised(pct, c.destChargePct(), ret)) return;
        if (destNearFor != p) {
            destNearFor = p;
            java.util.List<String> missing = new java.util.ArrayList<>();
            java.util.List<RoutePlanner.Charger> near = ChargerMarks.withoutBanned(planner.nearby(p.destLat, p.destLon, DEST_RADIUS_KM, missing), marks);
            java.util.List<RoutePlanner.Charger> sorted = new java.util.ArrayList<>(near);
            java.util.Collections.sort(sorted, (a, b) -> {
                int fa = ChargerMarks.kindOf(marks, a) == ChargerMarks.FAV ? 0 : 1;
                int fb = ChargerMarks.kindOf(marks, b) == ChargerMarks.FAV ? 0 : 1;
                return fa != fb ? fa - fb : Double.compare(a.kmAlong, b.kmAlong);
            });
            destNear = sorted;
            if (!missing.isEmpty() && !destFetching) {
                destFetching = true;
                planner.fetchNearby(missing, () -> root.post(() -> {
                    destFetching = false;
                    destNearFor = null;
                    shownChargersFor = -1;
                }));
            }
            L.i(String.format(Locale.US, "ruta: cargar en el destino (llegada %.0f %%): %d cargadores a menos de %.1f km", pct,
                    sorted.size(), DEST_RADIUS_KM));
        }
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_pl_dest_section).toUpperCase(Locale.getDefault()), 19, CarKit.AMBER);
        title.setTypeface(CarKit.MEDIUM);
        title.setLetterSpacing(0.1f);
        title.setPadding(0, 12, 0, 2);
        chargers.addView(title);
        TextView why = hint(!Double.isNaN(ret) && pct < ret ? Str.get(R.string.hql_pl_dest_return, Math.max(0, pct), ret)
                : Str.get(R.string.hql_pl_dest_low, Math.max(0, pct)));
        why.setTextColor(CarKit.AMBER);
        why.setMaxLines(2);
        chargers.addView(why);
        int shown = 0;
        for (RoutePlanner.Charger ch : destNear) {
            if (shown >= 3) break;
            shown++;
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 8, 0, 8);
            item.addView(new ChargerDot(ctx, ch.maxKw, ch.network), new LinearLayout.LayoutParams(52, 52));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, ch.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            TextView line = CarStyle.text(ctx, Str.get(R.string.hql_pl_dest_line, ch.kmAlong, kwText(ch)), 20, CarKit.DIM);
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(name);
            txt.addView(line);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(markPill(ch));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(ch.lat, ch.lon));
            item.addView(go, spaced());
            chargers.addView(item);
        }
        if (shown == 0) {
            chargers.addView(hint(destFetching ? Str.get(R.string.hql_pl_dest_loading) : Str.get(R.string.hql_pl_dest_none, DEST_RADIUS_KM)));
        }
    }

    /** «🍴 Bar Ejemplo y 3 más cerca» o «Sin sitio para comer a menos de 300 m» (o "" si aún no se sabe). */
    private String foodText(RoutePlanner.Charger c) {
        FoodNearby.Info i = FoodNearby.get(ctx, c);
        if (i == null) return "";
        if (i.count == 0) return Str.get(R.string.hql_pl_food_no, Math.round(FoodNearby.RADIUS_M));
        String name = i.name.isEmpty() ? Str.get(R.string.hql_pl_food_place) : i.name;
        return i.count == 1 ? Str.get(R.string.hql_pl_food_yes, name) : Str.get(R.string.hql_pl_food_more, name, i.count - 1);
    }

    /**
     * Pide (una vez: FoodNearby guarda lo sabido) si hay dónde comer junto a las paradas largas del plan; con «preferir
     * comida», también junto a unas pocas alternativas rápidas de las paradas largas que no tienen.
     */
    private void requestFood(RoutePlanner.Plan p, ChargePlanner.Result cp) {
        java.util.List<RoutePlanner.Charger> want = new java.util.ArrayList<>();
        boolean prefer = new Config(ctx).planPreferFood();
        for (ChargePlanner.Stop st : cp.stops) {
            if (!ChargePlanner.longStop(st) || st.km <= cp.fromKm + ChargePlanner.HERE_KM) continue;
            want.add(st.charger);
            FoodNearby.Info i = FoodNearby.get(ctx, st.charger);
            if (!prefer || i == null || i.count > 0) continue;
            int alt = 0;
            for (RoutePlanner.Charger c : p.chargers) {
                if (alt >= 4) break;
                if (c == st.charger || Math.abs(c.kmAlong - st.km) > 40 || !wanted(c) || c.acOnly || c.maxKw < ChargePlanner.FAST_KW) continue;
                want.add(c);
                alt++;
            }
        }
        if (want.isEmpty()) return;
        FoodNearby.request(ctx, want, () -> root.post(() -> {
            shownChargersFor = -1;
            if (running) tick();
        }));
    }

    // ------------------------------------------------------------------ carga en directo

    private LinearLayout livePanel;
    private TextView liveTitle;
    private TextView liveKw;
    private TextView livePct;
    private TextView liveWhen;
    private TextView liveNext;
    private TextView liveSkip;
    private TextView liveWarn;
    private ChargeCurve liveCurve;
    private TextView liveShow;
    /** La carga para la que se ha pulsado «Ver ruta» (no se vuelve a tapar la ruta hasta la siguiente). */
    private ChargeSession liveHiddenFor;

    /** El bloque de la carga en directo: kW, % → objetivo, hora de lista, a dónde se llega, ahorrar parada y la curva. */
    private View buildLive() {
        LinearLayout panel = CarKit.col(ctx);
        panel.setBackground(CarStyle.round(CarKit.SURFACE, CarKit.RADIUS));
        panel.setClickable(true);
        panel.setPadding(CarKit.PAD, 92, CarKit.PAD, 16);
        liveTitle = CarStyle.text(ctx, "", 20, CarKit.ACCENT);
        liveTitle.setTypeface(CarKit.MEDIUM);
        liveTitle.setLetterSpacing(0.08f);
        liveTitle.setSingleLine(true);
        liveTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        panel.addView(liveTitle);
        LinearLayout top = CarKit.row(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout big = CarKit.col(ctx);
        liveKw = CarStyle.text(ctx, "", 64, CarKit.TEXT);
        liveKw.setTypeface(CarKit.REGULAR);
        livePct = CarStyle.text(ctx, "", 30, CarKit.TEXT);
        livePct.setTypeface(CarKit.MEDIUM);
        big.addView(liveKw);
        big.addView(livePct);
        top.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout info = CarKit.col(ctx);
        info.setPadding(36, 0, 0, 0);
        liveWhen = CarStyle.text(ctx, "", 26, CarKit.TEXT);
        liveWhen.setTypeface(CarKit.MEDIUM);
        liveNext = CarStyle.text(ctx, "", 22, CarKit.DIM);
        liveSkip = CarStyle.text(ctx, "", 21, CarKit.ACCENT);
        liveWarn = CarStyle.text(ctx, "", 21, CarKit.AMBER);
        for (TextView t : new TextView[]{liveWhen, liveNext, liveSkip, liveWarn}) {
            t.setMaxLines(2);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(t);
        }
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(top);
        liveCurve = new ChargeCurve(ctx);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = 12;
        panel.addView(liveCurve, cp);
        LinearLayout bottom = CarKit.row(ctx);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        TextView legend = CarStyle.text(ctx, Str.get(R.string.hql_live_legend), 18, CarKit.FAINT);
        bottom.addView(legend, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hide = CarKit.pill(ctx, Str.get(R.string.hql_live_hide), false);
        hide.setOnClickListener(v -> {
            liveHiddenFor = ChargeWatch.session();
            tick();
        });
        bottom.addView(hide);
        panel.addView(bottom);
        panel.setVisibility(View.GONE);
        livePanel = panel;
        return panel;
    }

    /** Cada tick: el bloque visible mientras se carga (salvo «Ver ruta») y al día. */
    private void updateLive() {
        ChargeSession s = ChargeWatch.session();
        boolean show = s != null && s != liveHiddenFor;
        livePanel.setVisibility(show ? View.VISIBLE : View.GONE);
        liveShow.setVisibility(s != null && !show ? View.VISIBLE : View.GONE);
        if (!show) return;
        StringBuilder t = new StringBuilder(Str.get(R.string.hql_live_block_title).toUpperCase(Locale.getDefault()));
        if (s.charger != null) t.append(" · ").append(s.charger.name).append(" · ").append(kwText(s.charger));
        liveTitle.setText(t);
        ChargeSession.Sample l = s.last();
        liveKw.setText(l == null || Double.isNaN(l.kw) ? "— kW" : String.format(Locale.getDefault(), "%.0f kW", l.kw));
        livePct.setText(Double.isNaN(s.targetPct) || s.readyFired
                ? String.format(Locale.getDefault(), "%.0f %%", s.soc())
                : String.format(Locale.getDefault(), "%.0f %% → %.0f %%", s.soc(), s.targetPct));
        if (s.readyFired) {
            liveWhen.setText(Str.get(R.string.hql_live_ready_title));
            liveWhen.setTextColor(CarKit.GREEN);
        } else {
            liveWhen.setText(capital(ChargeWatch.when(s)));
            liveWhen.setTextColor(CarKit.TEXT);
        }
        boolean next = !Double.isNaN(s.nextArrivePct) && !s.nextName.isEmpty();
        liveNext.setText(next ? Str.get(R.string.hql_live_next, s.nextName, s.nextArrivePct) : "");
        liveNext.setVisibility(next ? View.VISIBLE : View.GONE);
        String skip = ChargeWatch.skipText(s);
        liveSkip.setText(skip);
        liveSkip.setTextColor(s.skipWorth() ? CarKit.ACCENT : CarKit.FAINT);
        liveSkip.setVisibility(skip.isEmpty() ? View.GONE : View.VISIBLE);
        String warn = "";
        if (s.slowFired) warn = Str.get(R.string.hql_live_slow_title) + ": " + Str.get(R.string.hql_live_slow_text, s.slowKw, s.slowExpectedKw);
        else if (s.lowVolts()) warn = Str.get(R.string.hql_charger_low_volts, s.charger.maxVolts);
        liveWarn.setText(warn);
        liveWarn.setVisibility(warn.isEmpty() ? View.GONE : View.VISIBLE);
        liveCurve.setSession(s);
    }

    private TextView hint(String s) {
        TextView t = CarStyle.text(ctx, s, 22, CarKit.FAINT);
        t.setPadding(0, 6, 0, 0);
        return t;
    }

    /** El cargador pasa el filtro elegido. */
    private boolean wanted(RoutePlanner.Charger c) {
        return ChargerFilter.accepts(c, chargerMinKw, chargerNets, chargerUnknown)
                && (marks.isEmpty() || ChargerMarks.kindOf(marks, c) != ChargerMarks.BAN);
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

    /** El trazado del plan sobre el mapa, a pantalla completa (Cerrar vuelve). */
    private void openMap() {
        RoutePlanner.Plan pl = plan;
        if (pl == null || pl.n < 2) return;
        View[] ref = new View[1];
        ref[0] = RouteMap.panel(ctx, planner, pl, chargePlan, () -> root.removeView(ref[0]));
        root.addView(ref[0], new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * Ventana con los cargadores de la ruta por delante, con el distintivo de su red, km, potencia, enchufes, si son
     * parada del plan e «Ir». all: también los que no pasan el filtro.
     */
    private void openChargerList(boolean all) {
        RoutePlanner.Plan pl = plan;
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        panel.setPadding(CarKit.PAD, 18, CarKit.PAD, CarKit.PAD);
        LinearLayout bar = CarKit.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_chargers_list_title), 34, CarKit.TEXT);
        title.setTypeface(CarKit.MEDIUM);
        bar.addView(title);
        TextView count = CarStyle.text(ctx, "", 22, CarKit.DIM);
        count.setPadding(20, 0, 0, 0);
        bar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView onlyF = chip(Str.get(R.string.hql_chargers_list_filtered), !all, () -> {
            root.removeViewAt(root.getChildCount() - 1);
            openChargerList(false);
        });
        TextView every = chip(Str.get(R.string.hql_chargers_list_all), all, () -> {
            root.removeViewAt(root.getChildCount() - 1);
            openChargerList(true);
        });
        bar.addView(onlyF, chipParams());
        bar.addView(every, chipParams());
        TextView done = CarKit.pill(ctx, Str.get(R.string.hql_close), true);
        bar.addView(done);
        panel.addView(bar);
        LinearLayout body = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = 10;
        panel.addView(sv, svp);
        done.setOnClickListener(v -> root.removeView(panel));
        int total = 0;
        int passing = 0;
        if (pl == null) {
            body.addView(hint(Str.get(R.string.hql_route_chargers_hint)));
        } else {
            double here = pl.km[Math.min(pl.progress, pl.n - 1)];
            java.util.Map<RoutePlanner.Charger, Integer> stopNo = new java.util.HashMap<>();
            ChargePlanner.Result cp = chargePlan;
            if (cp != null) for (int i = 0; i < cp.stops.size(); i++) stopNo.put(cp.stops.get(i).charger, i + 1);
            for (RoutePlanner.Charger c : pl.chargers) {
                if (c.kmAlong < here - 0.5) continue;
                total++;
                boolean ok = wanted(c);
                if (ok) passing++;
                if (!ok && !all) continue;
                body.addView(chargerRow(c, here, stopNo.get(c), ok), itemParams());
            }
            if (body.getChildCount() == 0) body.addView(hint(Str.get(R.string.hql_chargers_list_none)));
            body.addView(hint(Str.get(pl.dgtCount > 0 ? R.string.hql_chargers_source_dgt : R.string.hql_chargers_source_osm)));
        }
        count.setText(Str.get(R.string.hql_chargers_list_count, total, passing));
        root.addView(panel, CarStyle.match());
    }

    // ------------------------------------------------------------------ cargadores cerca

    /** Radios del buscador (km), potencias mínimas (kW) y lo que se recuerda mientras dura la sesión. */
    private static final int[] NEAR_RADII = {5, 10, 25, 50};
    private static final int[] NEAR_MIN_KW = {0, 50, 100, 150};
    private int nearRadius = 10;
    private int nearMinKw;
    private final java.util.Set<String> nearNets = new java.util.HashSet<>();
    /** Con la búsqueda en OpenStreetMap en marcha (fuera de España): para no pedirla dos veces. */
    private boolean nearFetching;

    /**
     * Buscador de cargadores cerca (sin ruta): los de la DGT (y OpenStreetMap) a menos del radio elegido, de más cerca a
     * más lejos, con su red, a cuánto están, potencia, voltaje, enchufes y puntos, y «Ir». Se elige la red («los Wenea
     * cerca de mí») y la potencia mínima.
     */
    private void openNearby() {
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(CarKit.BG);
        panel.setClickable(true);
        panel.setPadding(CarKit.PAD, 18, CarKit.PAD, CarKit.PAD);
        LinearLayout bar = CarKit.row(ctx);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CarStyle.text(ctx, Str.get(R.string.hql_near_title), 34, CarKit.TEXT);
        title.setTypeface(CarKit.MEDIUM);
        bar.addView(title);
        TextView count = CarStyle.text(ctx, "", 22, CarKit.DIM);
        count.setPadding(20, 0, 0, 0);
        bar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView done = CarKit.pill(ctx, Str.get(R.string.hql_close), true);
        bar.addView(done);
        panel.addView(bar);
        LinearLayout body = CarKit.col(ctx);
        ScrollView sv = new ScrollView(ctx);
        sv.addView(body);
        LinearLayout.LayoutParams svp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        svp.topMargin = 6;
        panel.addView(sv, svp);
        done.setOnClickListener(v -> root.removeView(panel));
        Runnable[] build = new Runnable[1];
        build[0] = () -> {
            body.removeAllViews();
            double[] pos = planner.position();
            if (pos == null || Double.isNaN(pos[0])) {
                count.setText("");
                body.addView(hint(Str.get(R.string.hql_near_no_position)));
                return;
            }
            java.util.List<String> missing = new java.util.ArrayList<>();
            java.util.List<RoutePlanner.Charger> all = planner.nearby(pos[0], pos[1], nearRadius, missing);
            if (!missing.isEmpty() && !nearFetching) {
                nearFetching = true;
                planner.fetchNearby(missing, () -> root.post(() -> {
                    nearFetching = false;
                    if (panel.getParent() != null) build[0].run();
                }));
            }
            // Filtros: distancia, potencia mínima y redes (las que hay cerca, con cuántos tiene cada una).
            LinearLayout rr = CarKit.row(ctx);
            rr.setGravity(Gravity.CENTER_VERTICAL);
            for (int r : NEAR_RADII) {
                rr.addView(chip(r + " km", nearRadius == r, () -> {
                    nearRadius = r;
                    build[0].run();
                }), chipParams());
            }
            TextView sep = CarStyle.text(ctx, "·", 22, CarKit.FAINT);
            sep.setPadding(12, 0, 12, 0);
            rr.addView(sep);
            for (int k : NEAR_MIN_KW) {
                String label = k == 0 ? Str.get(R.string.hql_near_any_power) : "≥" + k + " kW";
                rr.addView(chip(label, nearMinKw == k, () -> {
                    nearMinKw = k;
                    build[0].run();
                }), chipParams());
            }
            body.addView(rr);
            java.util.List<String> keys = new java.util.ArrayList<>();
            for (RoutePlanner.Charger c : all) keys.add(c.network == null ? ChargerFilter.OTHER : c.network);
            java.util.List<java.util.Map.Entry<String, Integer>> found = ChargerFilter.counts(keys);
            LinearLayout row = CarKit.row(ctx);
            row.setPadding(0, 10, 0, 0);
            row.addView(chip(Str.get(R.string.hql_charger_all_networks), nearNets.isEmpty(), () -> {
                nearNets.clear();
                build[0].run();
            }), chipParams());
            int i = 1;
            for (java.util.Map.Entry<String, Integer> e : found) {
                if (i % 6 == 0) {
                    body.addView(row);
                    row = CarKit.row(ctx);
                    row.setPadding(0, 10, 0, 0);
                }
                String k = e.getKey();
                String name = k.equals(ChargerFilter.OTHER) ? Str.get(R.string.hql_charger_other) : ChargerFilter.label(k);
                row.addView(chip(name + " · " + e.getValue(), nearNets.contains(k), () -> {
                    if (!nearNets.remove(k)) nearNets.add(k);
                    build[0].run();
                }), chipParams());
                i++;
            }
            body.addView(row);
            int shown = 0;
            int passing = 0;
            for (RoutePlanner.Charger c : all) {
                String net = c.network == null ? ChargerFilter.OTHER : c.network;
                if (!nearNets.isEmpty() && !nearNets.contains(net)) continue;
                if (nearMinKw > 0 && !(c.maxKw >= nearMinKw && !c.acOnly)) continue;
                passing++;
                if (shown >= 80) continue;
                shown++;
                LinearLayout.LayoutParams lp = itemParams();
                if (shown == 1) lp.topMargin = 16;
                body.addView(nearbyRow(c), lp);
            }
            if (passing == 0) body.addView(hint(Str.get(nearFetching ? R.string.hql_near_loading : R.string.hql_near_none, nearRadius)));
            count.setText(Str.get(R.string.hql_near_count, passing, nearRadius));
            body.addView(hint(Str.get(DgtChargers.inTile(ChargerCache.tileOf(pos[0], pos[1])) != null
                    ? R.string.hql_chargers_source_dgt : R.string.hql_chargers_source_osm)));
        };
        build[0].run();
        root.addView(panel, CarStyle.match());
    }

    /** Una fila del buscador: red, nombre, a cuántos km, potencia y voltaje, enchufes y puntos, y «Ir». */
    private View nearbyRow(RoutePlanner.Charger c) {
        LinearLayout item = CarKit.row(ctx);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(18, 12, 18, 12);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, CarKit.OUTLINE, 18));
        item.addView(new ChargerDot(ctx, c.maxKw, c.network), new LinearLayout.LayoutParams(64, 64));
        LinearLayout txt = CarKit.col(ctx);
        txt.setPadding(20, 0, 12, 0);
        TextView name = CarStyle.text(ctx, c.name, 26, CarKit.TEXT);
        name.setTypeface(CarKit.MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String net = c.network == null || c.network.equals(ChargerFilter.OTHER) ? "" : ChargerFilter.label(c.network) + " · ";
        String sub = net + Str.get(R.string.hql_near_line, c.kmAlong) + " · " + kwText(c)
                + (c.detail == null || c.detail.isEmpty() ? "" : " · " + c.detail);
        TextView detail = CarStyle.text(ctx, sub, 21, CarKit.DIM);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(detail);
        if (ChargePlanner.lowVolts(c, planner.carVolts())) {
            txt.addView(CarStyle.text(ctx, Str.get(R.string.hql_charger_low_volts, c.maxVolts), 20, CarKit.AMBER));
        }
        item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        item.addView(markPill(c));
        TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
        go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
        item.addView(go, spaced());
        return item;
    }

    /** Una fila de la ventana de cargadores. */
    private View chargerRow(RoutePlanner.Charger c, double here, Integer stop, boolean passes) {
        LinearLayout item = CarKit.row(ctx);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(18, 12, 18, 12);
        item.setBackground(CarKit.outlined(CarKit.SURFACE, stop != null ? CarKit.ACCENT : CarKit.OUTLINE, 18));
        ChargerDot dot = new ChargerDot(ctx, c.maxKw, c.network);
        item.addView(dot, new LinearLayout.LayoutParams(64, 64));
        LinearLayout txt = CarKit.col(ctx);
        txt.setPadding(20, 0, 12, 0);
        TextView name = CarStyle.text(ctx, c.name, 26, passes ? CarKit.TEXT : CarKit.DIM);
        name.setTypeface(CarKit.MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String net = c.network == null || c.network.equals(ChargerFilter.OTHER) ? "" : ChargerFilter.label(c.network) + " · ";
        // Sin repetir la red en el detalle (su operador suele ser ella misma: «Iberdrola», «Tesla, Inc.»).
        String detailText = c.detail;
        if (!net.isEmpty()) {
            String brand = ChargerFilter.label(c.network).toLowerCase(Locale.ROOT);
            StringBuilder kept = new StringBuilder();
            for (String part : c.detail.split(" · ")) {
                if (part.isEmpty() || part.toLowerCase(Locale.ROOT).contains(brand)) continue;
                kept.append(kept.length() > 0 ? " · " : "").append(part);
            }
            detailText = kept.toString();
        }
        String sub = net + Str.get(R.string.hql_charger_line, c.kmAlong, Math.max(0, c.kmAlong - here)) + " · " + kwText(c)
                + (detailText.isEmpty() ? "" : " · " + detailText);
        TextView detail = CarStyle.text(ctx, sub, 21, CarKit.DIM);
        detail.setMaxLines(2);
        detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        txt.addView(name);
        txt.addView(detail);
        if (ChargePlanner.lowVolts(c, planner.carVolts())) {
            txt.addView(CarStyle.text(ctx, Str.get(R.string.hql_charger_low_volts, c.maxVolts), 20, CarKit.AMBER));
        }
        item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (stop != null) {
            TextView tag = CarStyle.text(ctx, Str.get(R.string.hql_chargers_list_stop, stop), 21, CarKit.ACCENT);
            tag.setTypeface(CarKit.MEDIUM);
            tag.setPadding(0, 0, 16, 0);
            item.addView(tag);
        }
        item.addView(markPill(c));
        TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
        go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
        item.addView(go, spaced());
        return item;
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
            // Casi ningún cargador de OpenStreetMap dice su potencia: con potencia mínima, ¿se cuentan esos también?
            if (chargerMinKw > 0) {
                Config ic = new Config(ctx);
                LinearLayout un = CarKit.row(ctx);
                un.addView(chip(Str.get(R.string.hql_charger_unknown_include), ic.chargerIncludeUnknown(), () -> {
                    ic.setChargerIncludeUnknown(true);
                    chargerUnknown = true;
                    filterChanged();
                    build[0].run();
                }), chipParams());
                un.addView(chip(Str.get(R.string.hql_charger_unknown_exclude), !ic.chargerIncludeUnknown(), () -> {
                    ic.setChargerIncludeUnknown(false);
                    chargerUnknown = false;
                    filterChanged();
                    build[0].run();
                }), chipParams());
                body.addView(un);
                body.addView(hint(Str.get(R.string.hql_charger_unknown_note)));
            }
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
            // Cargar en el destino: si se llega con menos de este % (o, de ida y vuelta, con menos de lo de volver).
            body.addView(section(Str.get(R.string.hql_pl_dest_setting)));
            LinearLayout dc = CarKit.row(ctx);
            for (int v : new int[]{0, 10, 15, 20, 25, 30}) {
                dc.addView(chip(v == 0 ? capital(Str.get(R.string.hql_no)) : v + " %", pc.destChargePct() == v, () -> {
                    pc.setDestChargePct(v);
                    filterChanged();
                    build[0].run();
                }), chipParams());
            }
            body.addView(dc);
            body.addView(section(Str.get(R.string.hql_pl_food_pref)));
            LinearLayout fo = CarKit.row(ctx);
            fo.addView(chip(capital(Str.get(R.string.hql_yes)), pc.planPreferFood(), () -> {
                pc.setPlanPreferFood(true);
                filterChanged();
                build[0].run();
            }), chipParams());
            fo.addView(chip(capital(Str.get(R.string.hql_no)), !pc.planPreferFood(), () -> {
                pc.setPlanPreferFood(false);
                filterChanged();
                build[0].run();
            }), chipParams());
            body.addView(fo);
            body.addView(hint(Str.get(R.string.hql_pl_food_note, Math.round(FoodNearby.LONG_STOP_MIN), Math.round(FoodNearby.RADIUS_M))));
            addMarksSection(body, build[0]);
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

    /**
     * Con un coche de 800 V, los cargadores de 400–500 V que pasan el filtro por delante (el plan para en los de alto
     * voltaje; en estos también carga, a la mitad): cuántos y dónde está el primero. Toca para verlos en la lista.
     */
    private void addLowVoltsNote(RoutePlanner.Plan p, ChargePlanner.Result cp) {
        if (cp.carVolts < ChargePlanner.HIGH_VOLTS) return;
        double here = p.km[Math.min(p.progress, p.n - 1)];
        java.util.List<RoutePlanner.Charger> ahead = new java.util.ArrayList<>();
        for (RoutePlanner.Charger c : p.chargers) if (wanted(c)) ahead.add(c);
        java.util.List<RoutePlanner.Charger> low = ChargePlanner.lowVoltsBetween(ahead, here, p.km[p.n - 1], cp.carVolts);
        if (low.isEmpty()) return;
        TextView t = CarStyle.text(ctx, Str.get(R.string.hql_plan_low_volts, low.size(), low.get(0).kmAlong), 20, CarKit.AMBER);
        t.setPadding(0, 8, 0, 4);
        t.setOnClickListener(v -> openChargerList(false));
        chargers.addView(t);
    }

    /**
     * «150 kW», «≈150 kW» (típica de su red) o «potencia sin confirmar» (OpenStreetMap no la dice); con el voltaje en
     * continua si se sabe («350 kW · 920 V»).
     */
    static String kwText(RoutePlanner.Charger c) {
        String v = c.maxVolts > 0 && !c.acOnly ? String.format(Locale.getDefault(), " · %.0f V", c.maxVolts) : "";
        if (c.kwSource == ChargerFilter.KW_TAGGED && c.maxKw > 0) return String.format(Locale.getDefault(), "%.0f kW", c.maxKw) + v;
        if (c.kwSource == ChargerFilter.KW_NETWORK || c.acOnly) return String.format(Locale.getDefault(), "≈%.0f kW", c.maxKw) + v;
        return Str.get(R.string.hql_charger_kw_unknown) + v;
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

    /** El consejo de velocidad (SpeedAdvice) debajo del resumen del plan, si lo hay. */
    private void addSpeedAdvice(SpeedAdvice a) {
        String t = speedText(a);
        if (t == null) return;
        TextView v = hint(t);
        v.setTextColor(a.rescue ? CarKit.AMBER : CarKit.GREEN);
        chargers.addView(v);
    }

    /** El texto del consejo de velocidad, o null. */
    static String speedText(SpeedAdvice a) {
        if (a == null) return null;
        String drive = DriveTab.duration(Math.round(a.extraDriveMin * 60));
        String charge = DriveTab.duration(Math.round(Math.max(0, a.chargeSavedMin) * 60));
        if (a.rescue) return Str.get(R.string.hql_speed_advice_rescue, a.capKmh, drive);
        if (a.stopsAfter == 0) return Str.get(R.string.hql_speed_advice_nostop, a.capKmh, drive, charge);
        if (a.stopsAfter < a.stopsBefore) {
            // Menos paradas pero cargando más rato (la que queda llega a la parte lenta de la curva): lo que se tarda de más.
            if (a.chargeSavedMin < 0) {
                return Str.get(R.string.hql_speed_advice_stops_total, a.capKmh, a.stopsAfter, a.stopsBefore,
                        DriveTab.duration(Math.round(-a.gainMin() * 60)));
            }
            return Str.get(R.string.hql_speed_advice_stops, a.capKmh, a.stopsAfter, a.stopsBefore, drive, charge);
        }
        return Str.get(R.string.hql_speed_advice, a.capKmh, DriveTab.duration(Math.round(a.gainMin() * 60)), drive, charge);
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
            addLegs(p, cp);
            addDestChargers(p, cp);
            return;
        }
        if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER && cp.stops.isEmpty()) {
            TextView t = hint(Str.get(R.string.hql_plan_impossible));
            t.setTextColor(CarKit.AMBER);
            chargers.addView(t);
            // Si yendo más despacio sí se llega, se dice aquí (es para lo que está el consejo de rescate).
            addSpeedAdvice(cp.speed);
            addDestChargers(p, cp);
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
        // Con el precio de ahora (si se cambia en Ajustes, el plan no se rehace por eso).
        double cost = ChargePlanner.cost(cp, new Config(ctx).fastChargePrice());
        if (cost >= 0.5) sum += Str.get(R.string.hql_plan_cost, cost);
        TextView s = CarStyle.text(ctx, sum, 22, cp.outcome == ChargePlanner.Outcome.PLANNED ? CarKit.TEXT : CarKit.AMBER);
        chargers.addView(s);
        addReplanNote(cp);
        addSpeedAdvice(cp.speed);
        addLegs(p, cp);
        if (cp.outcome == ChargePlanner.Outcome.NO_CHARGER) chargers.addView(hint(Str.get(R.string.hql_plan_impossible)));
        int n = 1;
        for (ChargePlanner.Stop st : cp.stops) {
            LinearLayout item = CarKit.row(ctx);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(0, 10, 0, 10);
            ChargerDot dot = new ChargerDot(ctx, st.charger.maxKw, st.charger.network);
            item.addView(dot, new LinearLayout.LayoutParams(52, 52));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, n++ + ". " + st.charger.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            boolean low = ChargePlanner.lowVolts(st.charger, cp.carVolts);
            TextView line = CarStyle.text(ctx, Str.get(R.string.hql_plan_stop_line, st.km, st.arrivePct, st.departPct,
                    DriveTab.duration(Math.round(st.minutes * 60))) + " · " + kwText(st.charger)
                    + (low ? " · " + Str.get(R.string.hql_charger_low_volts, st.charger.maxVolts) : ""), 20, low ? CarKit.AMBER : CarKit.ACCENT);
            line.setMaxLines(2);
            txt.addView(name);
            txt.addView(line);
            // Parada larga: si hay dónde comer cerca (FoodNearby, pedido una vez).
            String food = ChargePlanner.longStop(st) ? foodText(st.charger) : "";
            if (!food.isEmpty()) {
                TextView f = CarStyle.text(ctx, food, 19, food.startsWith("🍴") ? CarKit.GREEN : CarKit.FAINT);
                f.setSingleLine(true);
                f.setEllipsize(android.text.TextUtils.TruncateAt.END);
                txt.addView(f);
            }
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(markPill(st.charger));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(st.charger.lat, st.charger.lon));
            item.addView(go, spaced());
            chargers.addView(item);
        }
        addLowVoltsNote(p, cp);
        addDestChargers(p, cp);
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
        if (chargePlan != null) requestFood(p, chargePlan);
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
            ChargerDot dot = new ChargerDot(ctx, c.maxKw, c.network);
            item.addView(dot, new LinearLayout.LayoutParams(52, 52));
            item.setOnClickListener(v -> openChargerList(false));
            LinearLayout txt = CarKit.col(ctx);
            txt.setPadding(16, 0, 10, 0);
            TextView name = CarStyle.text(ctx, c.name, 24, CarKit.TEXT);
            name.setTypeface(CarKit.MEDIUM);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String sub = Str.get(R.string.hql_charger_line, c.kmAlong, Math.max(0, c.kmAlong - here))
                    + " · " + kwText(c)
                    + (c.detail.isEmpty() ? "" : " · " + c.detail);
            TextView detail = CarStyle.text(ctx, sub, 20, CarKit.DIM);
            // Dos renglones: con la potencia y el detalle, uno solo cortaba el texto («350 k…»).
            detail.setMaxLines(2);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            txt.addView(name);
            txt.addView(detail);
            item.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            item.addView(markPill(c));
            TextView go = CarKit.pill(ctx, Str.get(R.string.hql_go), false);
            go.setOnClickListener(v -> navigateTo(c.lat, c.lon));
            item.addView(go, spaced());
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
        private final Object[] badge;

        ChargerDot(Context c, double kw) {
            this(c, kw, null);
        }

        /** Con red conocida, su distintivo (color e iniciales) y, en la esquina, el color de la potencia. */
        ChargerDot(Context c, double kw, String network) {
            super(c);
            color = kw >= 150 ? CarKit.GREEN : kw >= 50 ? CarKit.ACCENT : CarKit.DIM;
            badge = network == null ? null : ChargerFilter.badge(network);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            if (badge == null) {
                p.setColor(CarKit.alpha(color, 0.14f));
                cv.drawCircle(cx, cy, r, p);
                CarIcons.bolt(cv, cx, cy, r * 1.1f, color, p);
                return;
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor((Integer) badge[0]);
            cv.drawCircle(cx, cy, r * 0.92f, p);
            String ini = (String) badge[1];
            p.setTypeface(CarKit.MEDIUM);
            p.setTextSize(r * (ini.length() > 1 ? 0.78f : 1.0f));
            p.setTextAlign(Paint.Align.CENTER);
            p.setColor(((Integer) badge[2]) == 1 ? 0xFF15181C : 0xFFFFFFFF);
            Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText(ini, cx, cy - (fm.ascent + fm.descent) / 2, p);
            // La potencia, en un punto abajo a la derecha (verde ≥ 150 kW, acento ≥ 50, gris lento o sin saber).
            p.setColor(CarKit.SURFACE);
            cv.drawCircle(cx + r * 0.68f, cy + r * 0.68f, r * 0.34f, p);
            p.setColor(color);
            cv.drawCircle(cx + r * 0.68f, cy + r * 0.68f, r * 0.24f, p);
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
        return (cloud != null && cloud.hasData() && cloud.status.reev()) || CarSpec.current().reev;
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
        // Con la carga en directo encima no se pinta la ruta (se colaba por las esquinas redondeadas).
        if (livePanel != null && livePanel.getVisibility() == View.VISIBLE) return;
        RoutePlanner.Plan pl = plan;
        float x0 = r.left;
        float y0 = r.top;
        String dest = nav.destination != null ? nav.destination : pl != null ? pl.destination : null;
        if (dest != null && nav.destination == null && pl != null) {
            // Viaje: «Destino · +2 ⇄» (las paradas que quedan antes del destino y si vuelve).
            int before = Math.max(0, pl.destIndex);
            if (before > 0) dest += " · +" + before;
            if (pl.roundTrip) dest += " ⇄";
        }
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
            String why = pl.flatElevation ? Str.get(R.string.hql_route_flat) : explain(pl, prog);
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
        // Con los botones ±5 debajo, dos líneas como mucho (la tercera caía encima de ellos).
        statusPara.maxLines(socControls.getVisibility() == View.VISIBLE ? 2 : 3);
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
