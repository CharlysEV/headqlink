package com.headqlink.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Message;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import com.andrerinas.openheadunit.R;
import com.bumptech.glide.Glide;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Navegador web en la pantalla del coche, con accesos directos y teclado propio (CarKeyboard).
 * - Barra: atrás, adelante, recargar (o parar mientras carga), inicio, la página (candado, título y dominio; al tocarla
 *   se escribe una dirección o una búsqueda), hablar y teclado. Debajo, la barra de carga.
 * - Al tocar un campo de texto de la web sale solo el teclado del coche y se va al salir del campo.
 * - Inicio: buscador grande con micrófono, los marcadores con su icono y las páginas visitadas hace poco.
 * - De noche, las webs sin modo oscuro se oscurecen; los enlaces que abren ventana nueva se abren aquí; al volver al
 *   navegador sigue en la última página (si es de las últimas 12 h).
 * - El vídeo a pantalla completa (YouTube…) ocupa toda la zona de contenido.
 * Nunca se registran direcciones ni búsquedas (el log se exporta).
 */
final class WebScreen implements CarScreen {
    static final String[][] DEFAULT_SHORTCUTS = {
            {"YouTube", "https://m.youtube.com"},
            {"Google", "https://www.google.com"},
            {"Wikipedia", "https://es.wikipedia.org"},
            {"Twitch", "https://m.twitch.tv"},
    };
    private static final String PREFS = "hql_web";
    private static final int MAX_RECENT = 12;
    /** Volver a la última página si se dejó hace menos de esto. */
    private static final long RESUME_MS = 12 * 3600_000L;

    private Host host;
    private WebView web;
    private WebAudio webAudio;
    private FrameLayout body;
    private View home;
    private TextView address;
    private ProgressBar progress;
    private TextView reload;
    private CarKeyboard keyboard;
    private FrameLayout root;
    private View customView;
    /** El teclado escribe en la barra de dirección (true) o en la página (false). */
    private boolean typingAddress;
    /** El teclado salió solo al tocar un campo de la web (y se va solo al salir de él). */
    private boolean autoKeyboard;
    private final StringBuilder addressBuf = new StringBuilder();
    private android.speech.SpeechRecognizer speech;
    private boolean loading;
    /** «PC»: la web como en un ordenador (Netflix, Disney+… no dejan verlas en un móvil). Se recuerda. */
    private boolean desktop;
    private String mobileUa;

    /** Una pestaña: su WebView (creada al verla por primera vez), su sonido y lo último que cargó. */
    private static final class Tab {
        WebView web;
        WebAudio audio;
        String url = "";
        String title = "";
    }

    static final int MAX_TABS = 6;
    private final List<Tab> tabs = new ArrayList<>();
    /** La pestaña de delante (la de web/webAudio), o -1. */
    private int cur = -1;
    private TextView tabsPill;
    /** Panel «⋯» o vista de pestañas abiertos (encima de la página), o null. */
    private View overlay;
    private LinearLayout suggestRow;
    private HorizontalScrollView suggestScroll;
    private int suggestSeq;
    /** Bloqueo de anuncios y rastreadores (se recuerda) y lo bloqueado en la página de delante. */
    private volatile boolean adblock;
    private volatile int blockedHere;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        root = new FrameLayout(c);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, CarStyle.match());

        LinearLayout bar = CarStyle.bar(c);
        TextView back = CarStyle.pill(c, "‹");
        TextView fwd = CarStyle.pill(c, "›");
        reload = CarStyle.pill(c, "⟳");
        TextView start = CarStyle.pill(c, "⌂");
        address = CarStyle.text(c, "", 24, CarStyle.TEXT_DIM);
        address.setSingleLine(true);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        address.setPadding(22, 12, 22, 12);
        address.setBackground(CarStyle.round(CarStyle.ITEM_BG, 28));
        TextView mic = CarStyle.pill(c, "🎤");
        tabsPill = CarStyle.pill(c, "⧉ 1");
        TextView more = CarStyle.pill(c, "⋯");
        adblock = prefs(c).getBoolean("adblock", true);
        TextView kb = CarStyle.pill(c, Str.get(R.string.hql_keyboard));
        bar.addView(back);
        bar.addView(fwd);
        bar.addView(reload);
        bar.addView(start);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = 10;
        bar.addView(address, alp);
        bar.addView(mic);
        bar.addView(tabsPill);
        bar.addView(more);
        bar.addView(kb);
        col.addView(bar);
        progress = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(CarStyle.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(0x00000000));
        progress.setVisibility(View.INVISIBLE);
        col.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6));

        body = new FrameLayout(c);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // Sugerencias mientras se escribe la dirección (Google y el historial), encima del teclado.
        suggestRow = new LinearLayout(c);
        suggestRow.setPadding(10, 8, 10, 4);
        suggestScroll = new HorizontalScrollView(c);
        suggestScroll.setHorizontalScrollBarEnabled(false);
        suggestScroll.addView(suggestRow);
        suggestScroll.setVisibility(View.GONE);
        col.addView(suggestScroll);
        keyboard = new CarKeyboard(c, new CarKeyboard.Listener() {
            @Override
            public void onText(String s) {
                if (typingAddress) {
                    addressBuf.append(s);
                    showTyped();
                } else if (web != null) {
                    web.requestFocus();
                    web.evaluateJavascript("document.execCommand('insertText', false, " + JSONObject.quote(s) + ")", null);
                }
            }

            @Override
            public void onBackspace() {
                if (typingAddress) {
                    if (addressBuf.length() > 0) addressBuf.setLength(addressBuf.length() - 1);
                    showTyped();
                } else if (web != null) {
                    key(KeyEvent.KEYCODE_DEL);
                }
            }

            @Override
            public void onEnter() {
                if (typingAddress) {
                    go(addressBuf.toString());
                    showKeyboard(false, true);
                } else if (web != null) {
                    key(KeyEvent.KEYCODE_ENTER);
                }
            }

            @Override
            public void onHide() {
                showKeyboard(false, typingAddress);
                if (typingAddress) showPage();
            }
        });
        keyboard.setVisibility(View.GONE);
        col.addView(keyboard);

        back.setOnClickListener(v -> {
            if (web != null && web.canGoBack() && web.getParent() != null) web.goBack();
            else showHome();
        });
        fwd.setOnClickListener(v -> {
            if (web != null && web.canGoForward()) web.goForward();
        });
        reload.setOnClickListener(v -> {
            if (web == null) return;
            if (loading) web.stopLoading();
            else web.reload();
        });
        start.setOnClickListener(v -> showHome());
        address.setOnClickListener(v -> typeAddress());
        mic.setOnClickListener(v -> listen());
        tabsPill.setOnClickListener(v -> toggleTabs());
        more.setOnClickListener(v -> toggleMenu());
        kb.setOnClickListener(v -> {
            autoKeyboard = false;
            showKeyboard(keyboard.getVisibility() != View.VISIBLE, false);
        });

        home = buildHome(c);
        // Al volver al navegador, siguen las pestañas que había (si fue hace poco).
        if (!restoreTabs(c)) showHome();
        return root;
    }

    @Override
    public void destroy() {
        stopListening();
        for (Tab t : tabs) destroyTab(t);
        tabs.clear();
        web = null;
        webAudio = null;
    }

    // ------------------------------------------------------------------ inicio

    private View buildHome(Context c) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(30, 24, 30, 30);
        // Buscador grande con micrófono.
        LinearLayout search = new LinearLayout(c);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(CarStyle.round(CarStyle.ITEM_BG, 40));
        search.setPadding(30, 6, 12, 6);
        TextView hint = CarStyle.text(c, "🔍  " + Str.get(R.string.hql_web_address), 28, CarStyle.TEXT_DIM);
        hint.setPadding(0, 20, 0, 20);
        search.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView mic = CarStyle.pill(c, "🎤");
        mic.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 30);
        search.addView(mic);
        search.setOnClickListener(v -> typeAddress());
        mic.setOnClickListener(v -> listen());
        col.addView(search);

        // Marcadores con su icono.
        String[][] links = new Config(c).webShortcuts();
        GridLayout g = new GridLayout(c);
        g.setColumnCount(6);
        int w = (host.width() - 60) / 6;
        for (String[] l : links) g.addView(tile(c, l[0], l[1], w - 16, 170));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.topMargin = 24;
        col.addView(g, glp);

        // Visitadas hace poco.
        List<String[]> recent = recent(c);
        if (!recent.isEmpty()) {
            TextView rt = CarStyle.text(c, Str.get(R.string.hql_web_recent), 22, CarStyle.TEXT_DIM);
            rt.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            rt.setPadding(10, 28, 0, 8);
            col.addView(rt);
            LinearLayout row = new LinearLayout(c);
            for (String[] r : recent) {
                View t = tile(c, r[1].isEmpty() ? host(r[0]) : r[1], r[0], 230, 150);
                row.addView(t);
            }
            HorizontalScrollView hs = new HorizontalScrollView(c);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(row);
            col.addView(hs);
        }
        ScrollView sv = new ScrollView(c);
        sv.addView(col);
        return sv;
    }

    /** Un acceso: el icono de la web (el guardado al visitarla o el de la propia web; si no, su inicial) y el nombre. */
    private View tile(Context c, String name, String url, int w, int h) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(CarStyle.round(CarStyle.ITEM_BG, 24));
        t.setPadding(10, 14, 10, 12);
        String host = host(url);
        FrameLayout iconBox = new FrameLayout(c);
        TextView letter = CarStyle.text(c, host.isEmpty() ? "?" : host.substring(0, 1).toUpperCase(Locale.ROOT), 34, 0xFFFFFFFF);
        letter.setGravity(Gravity.CENTER);
        letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        letter.setBackground(CarStyle.round(colorFor(host), 20));
        iconBox.addView(letter, new FrameLayout.LayoutParams(72, 72));
        ImageView icon = new ImageView(c);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconBox.addView(icon, new FrameLayout.LayoutParams(72, 72));
        t.addView(iconBox, new LinearLayout.LayoutParams(72, 72));
        File cached = iconFile(c, host);
        if (cached.isFile()) {
            Glide.with(c.getApplicationContext()).load(cached).into(icon);
            letter.setVisibility(View.INVISIBLE);
        } else if (!host.isEmpty()) {
            // El icono de la propia web (sin servicios de terceros): el grande de iOS y, si no, el favicon.
            Glide.with(c.getApplicationContext()).load("https://" + host + "/apple-touch-icon.png")
                    .error(Glide.with(c.getApplicationContext()).load("https://" + host + "/favicon.ico"))
                    .into(icon);
        }
        TextView label = CarStyle.text(c, name, 22, CarStyle.TEXT);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(0, 10, 0, 0);
        t.addView(label);
        // Vale en la rejilla y en la fila (las dos convierten unos MarginLayoutParams).
        ViewGroup.MarginLayoutParams lp = new ViewGroup.MarginLayoutParams(w, h);
        lp.setMargins(8, 8, 8, 8);
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> go(url));
        return t;
    }

    private void showHome() {
        if (webAudio != null) webAudio.pause();
        if (web != null) web.stopLoading();
        body.removeAllViews();
        home = buildHome(host.context());
        body.addView(home, CarStyle.match());
        address.setText(Str.get(R.string.hql_web_address));
        address.setTextColor(CarStyle.TEXT_DIM);
        progress.setVisibility(View.INVISIBLE);
        closeOverlay();
    }

    // ------------------------------------------------------------------ navegar

    private void go(String input) {
        String url = toUrl(input);
        if (url == null) return;
        closeOverlay();
        WebView w = ensureWeb();
        applySiteSettings(w, url, false);
        w.loadUrl(url);
        if (web.getParent() == null) {
            body.removeAllViews();
            body.addView(web, CarStyle.match());
        }
    }

    /** Puro: lo escrito o dicho → dirección (con https si falta) o búsqueda en Google; null si está vacío. */
    static String toUrl(String input) {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) return null;
        if (s.matches("(?i)^(https?|about|file):.*")) return s;
        if (s.contains(".") && !s.contains(" ") && s.matches("^[^/]+\\.[a-zA-Z]{2,}(:[0-9]+)?(/.*)?$")) return "https://" + s;
        return "https://www.google.com/search?q=" + Uri.encode(s);
    }

    /** Puro: el dominio de una dirección, sin «www.». */
    static String host(String url) {
        try {
            String h = Uri.parse(url).getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** La WebView de la pestaña de delante (crea la pestaña y la WebView si no hay). */
    private WebView ensureWeb() {
        if (web != null) return web;
        if (cur < 0 || cur >= tabs.size()) {
            tabs.add(new Tab());
            cur = tabs.size() - 1;
        }
        Tab t = tabs.get(cur);
        if (t.web == null) makeWeb(t);
        web = t.web;
        webAudio = t.audio;
        updateTabsPill();
        return web;
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void makeWeb(Tab t) {
        WebView web = new WebView(host.context());
        t.web = web;
        t.audio = new WebAudio(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Ventanas nuevas (target=_blank): se abren aquí, solo si las pide un toque (no las que salen solas).
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        applyDark(s);
        mobileUa = s.getUserAgentString();
        applyUa(s);
        web.addJavascriptInterface(new KeyboardBridge(), "HqlKeyboard");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                t.audio.navigation();
                applySiteSettings(v, url, true);
                if (v != WebScreen.this.web) return;
                blockedHere = 0;
                loading = true;
                reload.setText("✕");
                progress.setVisibility(View.VISIBLE);
                showPage();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(FOCUS_JS, null);
                t.audio.pageFinished();
                if (url != null && url.startsWith("http")) {
                    t.url = url;
                    t.title = v.getTitle() == null ? "" : v.getTitle();
                }
                remember(url, v.getTitle());
                saveTabs();
                if (v != WebScreen.this.web) return;
                loading = false;
                reload.setText("⟳");
                progress.setVisibility(View.INVISIBLE);
                showPage();
            }

            /** Anuncios y rastreadores (AdBlock.isAd): respuesta vacía, sin llegar a pedirlos. */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                if (!adblock || req.getUrl() == null || !AdBlock.isAd(req.getUrl().getHost())) return null;
                blockedHere++;
                return new android.webkit.WebResourceResponse("text/plain", "utf-8", new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (v == WebScreen.this.web) progress.setProgress(p);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (title != null) t.title = title;
                if (v == WebScreen.this.web) showPage();
            }

            @Override
            public void onReceivedIcon(WebView v, Bitmap icon) {
                saveIcon(host.context(), host(v.getUrl()), icon);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message result) {
                if (!userGesture) return false;
                // Una WebView de paso que solo recoge la dirección y la abre en una pestaña nueva (o en esta si ya hay
                // tantas como caben).
                WebView temp = new WebView(host.context());
                boolean[] done = {false};
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView tv, WebResourceRequest req) {
                        if (!done[0]) {
                            done[0] = true;
                            openInNewTab(req.getUrl().toString());
                        }
                        tv.destroy();
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView tv, String url, Bitmap favicon) {
                        if (!done[0] && url != null && !url.equals("about:blank")) {
                            done[0] = true;
                            openInNewTab(url);
                        }
                        tv.stopLoading();
                        tv.destroy();
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) result.obj;
                transport.setWebView(temp);
                result.sendToTarget();
                return true;
            }

            @Override
            public void onPermissionRequest(android.webkit.PermissionRequest r) {
                // Solo el contenido protegido (Widevine: Netflix, Disney+…), sin preguntar; cámara y micrófono, no.
                for (String res : r.getResources()) {
                    if (android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(res)) {
                        r.grant(new String[]{res});
                        return;
                    }
                }
                r.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Vídeo a pantalla completa: por encima de la barra y del teclado.
                customView = view;
                root.addView(view, CarStyle.match());
                host.setFullscreen(true);
            }

            @Override
            public void onHideCustomView() {
                if (customView != null) root.removeView(customView);
                customView = null;
                host.setFullscreen(false);
            }
        });
    }

    private void applyUa(WebSettings s) {
        if (mobileUa == null) return;
        s.setUserAgentString(desktop ? desktopUa(mobileUa) : mobileUa);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
    }

    /**
     * Puro: el agente de usuario de Chrome de ordenador (Linux) con la misma versión del de la WebView del móvil, sin
     * «Android», «wv», «Version/4.0» ni «Mobile».
     */
    static String desktopUa(String mobile) {
        String chrome = "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("Chrome/[0-9.]+").matcher(mobile == null ? "" : mobile);
        if (m.find()) chrome = m.group() + " ";
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " + chrome + "Safari/537.36";
    }

    // ------------------------------------------------------------------ pestañas

    private static void destroyTab(Tab t) {
        if (t.audio != null) t.audio.close();
        if (t.web != null) {
            t.web.stopLoading();
            t.web.loadUrl("about:blank");
            t.web.destroy();
        }
        t.web = null;
        t.audio = null;
    }

    private void updateTabsPill() {
        tabsPill.setText("⧉ " + Math.max(1, tabs.size()));
    }

    /** Guarda las pestañas (sus direcciones y la de delante) para seguir al volver. Solo en el móvil. */
    private void saveTabs() {
        JSONArray a = new JSONArray();
        int at = 0;
        for (int i = 0; i < tabs.size(); i++) {
            String u = tabs.get(i).url;
            if (u.isEmpty()) continue;
            if (i == cur) at = a.length();
            a.put(u);
        }
        prefs(host.context()).edit().putString("tabs", a.toString()).putInt("tab_cur", at)
                .putLong("last_at", System.currentTimeMillis()).apply();
    }

    /** Vuelve a abrir las pestañas de la última vez (si fue hace menos de RESUME_MS). false si no hay ninguna. */
    private boolean restoreTabs(Context c) {
        SharedPreferences sp = prefs(c);
        if (System.currentTimeMillis() - sp.getLong("last_at", 0) >= RESUME_MS) return false;
        List<String> urls = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("tabs", "[]"));
            for (int i = 0; i < a.length() && urls.size() < MAX_TABS; i++) urls.add(a.getString(i));
        } catch (Exception ignored) {
        }
        // Versiones anteriores: solo la última página.
        if (urls.isEmpty() && !sp.getString("last_url", "").isEmpty()) urls.add(sp.getString("last_url", ""));
        if (urls.isEmpty()) return false;
        for (String u : urls) {
            Tab t = new Tab();
            t.url = u;
            tabs.add(t);
        }
        switchTab(Math.max(0, Math.min(urls.size() - 1, sp.getInt("tab_cur", 0))));
        return true;
    }

    /** Pone delante la pestaña i (la carga si aún no tenía WebView). */
    private void switchTab(int i) {
        closeOverlay();
        if (i < 0 || i >= tabs.size()) return;
        if (web != null && web.getParent() != null) body.removeView(web);
        cur = i;
        web = null;
        webAudio = null;
        Tab t = tabs.get(i);
        boolean fresh = t.web == null;
        ensureWeb();
        body.removeAllViews();
        if (fresh && t.url.isEmpty()) {
            showHome();
            return;
        }
        body.addView(web, CarStyle.match());
        if (fresh) {
            applySiteSettings(web, t.url, false);
            web.loadUrl(t.url);
        } else {
            loading = false;
            reload.setText("⟳");
            progress.setVisibility(View.INVISIBLE);
            showPage();
        }
        updateTabsPill();
        saveTabs();
    }

    /** Pestaña nueva con el inicio delante (o con una dirección). Con todas ocupadas, se usa la de delante. */
    private void newTab(String url) {
        closeOverlay();
        if (tabs.size() >= MAX_TABS) {
            if (url != null) go(url);
            else showHome();
            return;
        }
        if (web != null && web.getParent() != null) body.removeView(web);
        tabs.add(new Tab());
        cur = tabs.size() - 1;
        web = null;
        webAudio = null;
        updateTabsPill();
        if (url != null) go(url);
        else showHome();
    }

    private void openInNewTab(String url) {
        host.post(() -> newTab(url));
    }

    private void closeTab(int i) {
        if (i < 0 || i >= tabs.size()) return;
        Tab t = tabs.remove(i);
        boolean front = i == cur;
        if (front && t.web != null && t.web.getParent() != null) body.removeView(t.web);
        destroyTab(t);
        if (tabs.isEmpty()) {
            cur = -1;
            web = null;
            webAudio = null;
            updateTabsPill();
            saveTabs();
            showHome();
            return;
        }
        if (i < cur) cur--;
        if (front) {
            web = null;
            webAudio = null;
            switchTab(Math.min(i, tabs.size() - 1));
        } else {
            updateTabsPill();
            saveTabs();
        }
    }

    private void closeOverlay() {
        if (overlay != null) root.removeView(overlay);
        overlay = null;
    }

    /** Fondo que cierra el panel al tocar fuera, con el panel colocado encima. */
    private void showOverlay(View panel, int gravity, int w) {
        closeOverlay();
        Context c = host.context();
        FrameLayout shade = new FrameLayout(c);
        shade.setBackgroundColor(0x66000000);
        shade.setOnClickListener(v -> closeOverlay());
        panel.setClickable(true);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
        lp.setMargins(20, 100, 20, 20);
        shade.addView(panel, lp);
        overlay = shade;
        root.addView(shade, CarStyle.match());
    }

    /** Vista de pestañas: una tarjeta por pestaña (título, web y ✕) y «Nueva pestaña». */
    private void toggleTabs() {
        if (overlay != null) {
            closeOverlay();
            return;
        }
        Context c = host.context();
        LinearLayout panel = CarStyle.card(c);
        panel.setPadding(24, 22, 24, 22);
        TextView title = CarStyle.text(c, Str.get(R.string.hql_web_tabs), 28, CarStyle.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(8, 0, 0, 14);
        panel.addView(title);
        GridLayout g = new GridLayout(c);
        g.setColumnCount(3);
        int w = (host.width() - 160) / 3;
        for (int i = 0; i < tabs.size(); i++) {
            int idx = i;
            Tab t = tabs.get(i);
            LinearLayout card = new LinearLayout(c);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(18, 16, 10, 16);
            card.setBackground(CarStyle.round(i == cur ? CarStyle.ACCENT_BG : CarStyle.ITEM_BG, 20));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            String h = host(t.url);
            TextView name = CarStyle.text(c, t.title.isEmpty() ? (h.isEmpty() ? Str.get(R.string.hql_web_new_tab) : h) : t.title, 24,
                    i == cur ? CarStyle.ACCENT : CarStyle.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(name);
            TextView sub = CarStyle.text(c, h, 19, CarStyle.TEXT_DIM);
            sub.setSingleLine(true);
            texts.addView(sub);
            card.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView x = CarStyle.pill(c, "✕");
            x.setOnClickListener(v -> {
                closeTab(idx);
                if (overlay != null) {
                    closeOverlay();
                    toggleTabs();
                }
            });
            card.addView(x);
            card.setOnClickListener(v -> switchTab(idx));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w - 16;
            lp.setMargins(8, 8, 8, 8);
            g.addView(card, lp);
        }
        panel.addView(g);
        TextView add = CarStyle.pill(c, "+  " + Str.get(R.string.hql_web_new_tab));
        add.setPadding(30, 18, 30, 18);
        add.setAlpha(tabs.size() < MAX_TABS ? 1f : 0.4f);
        add.setOnClickListener(v -> newTab(null));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = 14;
        panel.addView(add, alp);
        showOverlay(panel, Gravity.TOP | Gravity.CENTER_HORIZONTAL, host.width() - 100);
    }

    /** Menú «⋯»: guardar en Inicio, tamaño del texto y versión de ordenador (por web), bloqueo y pestaña nueva. */
    private void toggleMenu() {
        if (overlay != null) {
            closeOverlay();
            return;
        }
        Context c = host.context();
        LinearLayout panel = CarStyle.card(c);
        panel.setPadding(14, 14, 14, 14);
        String url = web != null && web.getParent() != null ? web.getUrl() : null;
        String h = url == null ? "" : host(url);
        boolean page = !h.isEmpty();
        Config cfg = new Config(c);
        boolean saved = page && isShortcut(cfg, url);
        panel.addView(menuRow(c, saved ? "★  " + Str.get(R.string.hql_web_remove_home) : "☆  " + Str.get(R.string.hql_web_save_home), page, () -> {
            toggleShortcut(cfg, url, web.getTitle());
            closeOverlay();
        }));
        int zoom = prefs(c).getInt("zoom:" + h, 100);
        panel.addView(menuRow(c, "Aa  " + Str.get(R.string.hql_web_text_size, zoom), page, () -> {
            int next = nextZoom(zoom);
            prefs(c).edit().putInt("zoom:" + h, next).apply();
            web.getSettings().setTextZoom(next);
            closeOverlay();
            toggleMenu();
        }));
        boolean pc = prefs(c).getBoolean("pc:" + h, false);
        panel.addView(menuRow(c, (pc ? "✓  " : "🖥  ") + Str.get(R.string.hql_web_desktop), page, () -> {
            prefs(c).edit().putBoolean("pc:" + h, !pc).apply();
            closeOverlay();
            applySiteSettings(web, url, false);
            web.reload();
        }));
        panel.addView(menuRow(c, (adblock ? "🛡  " : "○  ") + Str.get(R.string.hql_web_adblock)
                + (adblock && page ? " · " + Str.get(R.string.hql_web_adblock_count, blockedHere) : ""), true, () -> {
            adblock = !adblock;
            prefs(c).edit().putBoolean("adblock", adblock).apply();
            closeOverlay();
            if (web != null && web.getParent() != null) web.reload();
        }));
        panel.addView(menuRow(c, "⧉  " + Str.get(R.string.hql_web_new_tab), tabs.size() < MAX_TABS, () -> newTab(null)));
        showOverlay(panel, Gravity.TOP | Gravity.RIGHT, 640);
    }

    private static View menuRow(Context c, String text, boolean enabled, Runnable action) {
        TextView t = CarStyle.text(c, text, 25, enabled ? CarStyle.TEXT : CarStyle.TEXT_DIM);
        t.setPadding(24, 22, 24, 22);
        t.setBackground(CarStyle.round(0x00000000, 16));
        if (enabled) t.setOnClickListener(v -> action.run());
        return t;
    }

    /** Puro: el siguiente tamaño de texto (85 → 100 → 115 → 130 → 150 → 85 %). */
    static int nextZoom(int z) {
        int[] steps = {85, 100, 115, 130, 150};
        for (int s : steps) if (s > z) return s;
        return steps[0];
    }

    private static boolean isShortcut(Config cfg, String url) {
        String h = host(url);
        for (String[] l : cfg.webShortcuts()) if (host(l[1]).equals(h)) return true;
        return false;
    }

    private static void toggleShortcut(Config cfg, String url, String title) {
        String h = host(url);
        List<String[]> l = new ArrayList<>(java.util.Arrays.asList(cfg.webShortcuts()));
        boolean removed = l.removeIf(e -> host(e[1]).equals(h));
        if (!removed) l.add(new String[]{title == null || title.isEmpty() ? h : title, url});
        cfg.setWebShortcuts(l.toArray(new String[0][]));
    }

    /**
     * Lo de cada web: versión de ordenador o de móvil y tamaño del texto (Ajustes «⋯», recordados por dominio). Si al
     * empezar a cargar no coincide la versión, se vuelve a cargar con la buena.
     */
    private void applySiteSettings(WebView w, String url, boolean reloadIfChanged) {
        if (w == null || url == null || !url.startsWith("http")) return;
        String h = host(url);
        SharedPreferences sp = prefs(host.context());
        WebSettings s = w.getSettings();
        if (mobileUa == null) mobileUa = s.getUserAgentString();
        boolean pc = sp.getBoolean("pc:" + h, false);
        String want = pc ? desktopUa(mobileUa) : mobileUa;
        if (!want.equals(s.getUserAgentString())) {
            s.setUserAgentString(want);
            if (reloadIfChanged) w.post(w::reload);
        }
        if (w == web) desktop = pc;
        s.setTextZoom(sp.getInt("zoom:" + h, 100));
    }

    // ------------------------------------------------------------------ sugerencias

    /** Sugerencias para lo que se escribe: del historial (al momento) y de Google (en un hilo, la última que llega). */
    private void suggest(String q) {
        int seq = ++suggestSeq;
        String text = q.trim();
        List<String[]> local = new ArrayList<>();
        if (!text.isEmpty()) {
            String low = text.toLowerCase(Locale.ROOT);
            for (String[] r : recent(host.context())) {
                if (local.size() >= 3) break;
                if (r[0].toLowerCase(Locale.ROOT).contains(low) || r[1].toLowerCase(Locale.ROOT).contains(low)) local.add(r);
            }
        }
        showSuggestions(local, new ArrayList<>());
        if (text.length() < 2) return;
        new Thread(() -> {
            List<String> remote = fetchSuggestions(text);
            host.post(() -> {
                if (seq == suggestSeq && typingAddress) showSuggestions(local, remote);
            });
        }, "web-suggest").start();
    }

    private void showSuggestions(List<String[]> local, List<String> remote) {
        Context c = host.context();
        suggestRow.removeAllViews();
        for (String[] r : local) {
            String label = "🕘  " + (r[1].isEmpty() ? host(r[0]) : r[1]);
            suggestRow.addView(suggestChip(c, label, () -> go(r[0])));
        }
        for (String s : remote) suggestRow.addView(suggestChip(c, "🔍  " + s, () -> go(s)));
        suggestScroll.setVisibility(suggestRow.getChildCount() > 0 && typingAddress ? View.VISIBLE : View.GONE);
    }

    private View suggestChip(Context c, String label, Runnable go) {
        TextView t = CarStyle.pill(c, label);
        t.setMaxWidth(560);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setOnClickListener(v -> {
            go.run();
            showKeyboard(false, true);
        });
        return t;
    }

    /** Google (las mismas sugerencias que su buscador). Sin registrar lo escrito. */
    private static List<String> fetchSuggestions(String q) {
        try {
            java.net.HttpURLConnection con = (java.net.HttpURLConnection) new java.net.URL(
                    "https://suggestqueries.google.com/complete/search?client=firefox&hl=" + Locale.getDefault().getLanguage()
                            + "&q=" + Uri.encode(q)).openConnection();
            con.setConnectTimeout(3000);
            con.setReadTimeout(3000);
            try (java.io.InputStream in = con.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0 && out.size() < 64_000) out.write(buf, 0, n);
                return parseSuggestions(out.toString("UTF-8"), 6);
            } finally {
                con.disconnect();
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /** Puro: ["lo escrito", ["sugerencia 1", …]] (formato «firefox» de Google) → hasta max sugerencias. */
    static List<String> parseSuggestions(String json, int max) {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json).getJSONArray(1);
            for (int i = 0; i < a.length() && out.size() < max; i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** De noche, las webs sin modo oscuro propio se oscurecen (como en Chrome). */
    private static void applyDark(WebSettings s) {
        boolean night = CarTheme.night();
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, night);
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            //noinspection deprecation
            WebSettingsCompat.setForceDark(s, night ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        }
    }

    /** La página en la barra: candado si es segura, el título y el dominio en gris. */
    private void showPage() {
        if (web == null || typingAddress && keyboard.getVisibility() == View.VISIBLE) return;
        String url = web.getUrl();
        if (url == null || url.equals("about:blank")) return;
        String title = web.getTitle();
        String h = host(url);
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (url.startsWith("https://")) b.append("🔒 ");
        if (title != null && !title.isEmpty() && !title.equals(url) && !url.contains(title)) {
            b.append(title);
            int s = b.length();
            b.append("   ").append(h);
            b.setSpan(new ForegroundColorSpan(CarStyle.TEXT_DIM), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            b.append(h.isEmpty() ? url : h);
        }
        address.setText(b);
        address.setTextColor(CarStyle.TEXT);
    }

    // ------------------------------------------------------------------ escribir y hablar

    private void typeAddress() {
        addressBuf.setLength(0);
        showKeyboard(true, true);
        showTyped();
    }

    private void showTyped() {
        address.setText(addressBuf.length() == 0 ? Str.get(R.string.hql_web_address) : addressBuf);
        address.setTextColor(addressBuf.length() == 0 ? CarStyle.TEXT_DIM : CarStyle.TEXT);
        keyboard.showText(addressBuf, Str.get(R.string.hql_web_address));
        suggest(addressBuf.toString());
    }

    private void showKeyboard(boolean show, boolean forAddress) {
        typingAddress = forAddress;
        keyboard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!(show && forAddress)) {
            suggestSeq++;
            suggestScroll.setVisibility(View.GONE);
        }
        if (show) closeOverlay();
        if (show && !forAddress && web != null) web.requestFocus();
    }

    /** Desde la página (hilo del navegador): un campo de texto ganó o perdió el foco. */
    private final class KeyboardBridge {
        @JavascriptInterface
        public void focus(boolean editing) {
            host.post(() -> {
                if (web == null || customView != null) return;
                if (editing) {
                    if (keyboard.getVisibility() == View.VISIBLE && typingAddress) return;
                    autoKeyboard = true;
                    showKeyboard(true, false);
                    keyboard.showText("", "");
                    // La página encoge con el teclado: el campo, a la vista (cuando ya tiene el tamaño nuevo).
                    web.postDelayed(() -> {
                        if (web != null) web.evaluateJavascript("(function(){var e=document.activeElement;"
                                + "if(e&&e.scrollIntoView)e.scrollIntoView({block:'center'});})()", null);
                    }, 250);
                } else if (autoKeyboard && !typingAddress) {
                    autoKeyboard = false;
                    showKeyboard(false, false);
                }
            });
        }
    }

    /** Avisa de cuándo se entra o se sale de un campo de texto (inputs de texto, textarea, editables). */
    private static final String FOCUS_JS = "(function(){if(window.__hqlKb)return;window.__hqlKb=1;"
            + "function ed(e){if(!e)return false;if(e.isContentEditable)return true;var t=(e.tagName||'').toLowerCase();"
            + "if(t=='textarea')return true;if(t!='input')return false;var y=(e.getAttribute('type')||'text').toLowerCase();"
            + "return ['text','search','email','url','tel','password','number'].indexOf(y)>=0;}"
            + "document.addEventListener('focusin',function(e){if(ed(e.target))HqlKeyboard.focus(true);},true);"
            + "document.addEventListener('focusout',function(){setTimeout(function(){if(!ed(document.activeElement))"
            + "HqlKeyboard.focus(false);},200);},true);"
            + "if(ed(document.activeElement))HqlKeyboard.focus(true);})();";

    private void listen() {
        Context ctx = host.context();
        if (ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            address.setText(Str.get(R.string.hql_search_voice_permission));
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(ctx)) {
            address.setText(Str.get(R.string.hql_search_voice_none));
            return;
        }
        stopListening();
        typingAddress = true;
        speech = android.speech.SpeechRecognizer.createSpeechRecognizer(ctx);
        speech.setRecognitionListener(new android.speech.RecognitionListener() {
            @Override
            public void onReadyForSpeech(android.os.Bundle p) {
                address.setText("🎤  " + Str.get(R.string.hql_web_listening));
                address.setTextColor(CarStyle.ACCENT);
            }

            @Override
            public void onPartialResults(android.os.Bundle b) {
                String t = first(b);
                if (t != null && !t.isEmpty()) {
                    address.setText(t);
                    address.setTextColor(CarStyle.TEXT);
                }
            }

            @Override
            public void onResults(android.os.Bundle b) {
                String t = first(b);
                stopListening();
                typingAddress = false;
                if (t == null || t.trim().isEmpty()) {
                    address.setText(Str.get(R.string.hql_search_voice_nothing));
                    return;
                }
                L.i("web: búsqueda dicha en voz alta"); // sin el texto: el log se exporta
                go(t);
            }

            @Override
            public void onError(int error) {
                stopListening();
                typingAddress = false;
                address.setText(Str.get(error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                        ? R.string.hql_search_voice_permission : R.string.hql_search_voice_nothing));
                address.setTextColor(CarStyle.TEXT_DIM);
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
            }

            @Override
            public void onEvent(int eventType, android.os.Bundle params) {
            }
        });
        Intent in = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
        in.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        in.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            speech.startListening(in);
        } catch (RuntimeException e) {
            stopListening();
            address.setText(Str.get(R.string.hql_search_voice_none));
        }
    }

    private void stopListening() {
        if (speech == null) return;
        try {
            speech.destroy();
        } catch (RuntimeException ignored) {
        }
        speech = null;
    }

    private static String first(android.os.Bundle b) {
        ArrayList<String> r = b == null ? null : b.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    private void key(int code) {
        web.requestFocus();
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }

    // ------------------------------------------------------------------ historial, última página e iconos

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Guarda la página (última y visitadas hace poco). Solo en el móvil; nunca en el log. */
    private void remember(String url, String title) {
        if (url == null || !url.startsWith("http")) return;
        Context c = host.context();
        SharedPreferences sp = prefs(c);
        String json = recentAdd(sp.getString("recent", "[]"), url, title == null ? "" : title, MAX_RECENT);
        sp.edit().putString("recent", json).putString("last_url", url).putLong("last_at", System.currentTimeMillis()).apply();
    }

    /** Puro: añade una visita (sin repetir dominio: la más reciente de cada web) y deja las max últimas. */
    static String recentAdd(String json, String url, String title, int max) {
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject().put("u", url).put("t", title));
            JSONArray old = new JSONArray(json == null ? "[]" : json);
            String h = host(url);
            for (int i = 0; i < old.length() && out.length() < max; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o == null || host(o.optString("u")).equals(h)) continue;
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out.toString();
    }

    private static List<String[]> recent(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("recent", "[]"));
            for (int i = 0; i < a.length() && out.size() < 8; i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("u"), o.optString("t")});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static File iconFile(Context c, String host) {
        return new File(new File(c.getFilesDir(), "web_icons"), host.replaceAll("[^a-zA-Z0-9.-]", "_") + ".png");
    }

    private static void saveIcon(Context c, String host, Bitmap icon) {
        if (host.isEmpty() || icon == null || icon.getWidth() < 32) return;
        File f = iconFile(c, host);
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            icon.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
    }

    /** Puro: un color fijo para cada web (el fondo de su inicial cuando no hay icono). */
    static int colorFor(String host) {
        int[] palette = {0xFF1967D2, 0xFF00695C, 0xFF6A1B9A, 0xFFC5221F, 0xFFE8710A, 0xFF137333, 0xFF0D2B4E, 0xFF6D1B2B};
        return palette[Math.abs(host.hashCode() % palette.length)];
    }
}
