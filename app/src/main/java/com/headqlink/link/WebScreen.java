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
    private TextView pc;
    private String mobileUa;

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
        pc = CarStyle.pill(c, "PC");
        desktop = prefs(c).getBoolean("desktop", false);
        paintPc();
        TextView kb = CarStyle.pill(c, Str.get(R.string.hql_keyboard));
        bar.addView(back);
        bar.addView(fwd);
        bar.addView(reload);
        bar.addView(start);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = 10;
        bar.addView(address, alp);
        bar.addView(mic);
        bar.addView(pc);
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
        pc.setOnClickListener(v -> {
            desktop = !desktop;
            prefs(host.context()).edit().putBoolean("desktop", desktop).apply();
            paintPc();
            if (web != null) {
                applyUa(web.getSettings());
                if (web.getParent() != null) web.reload();
            }
        });
        kb.setOnClickListener(v -> {
            autoKeyboard = false;
            showKeyboard(keyboard.getVisibility() != View.VISIBLE, false);
        });

        home = buildHome(c);
        // Al volver al navegador, sigue donde se dejó (si fue hace poco).
        SharedPreferences sp = prefs(c);
        String last = sp.getString("last_url", "");
        if (!last.isEmpty() && System.currentTimeMillis() - sp.getLong("last_at", 0) < RESUME_MS) go(last);
        else showHome();
        return root;
    }

    @Override
    public void destroy() {
        stopListening();
        if (webAudio != null) webAudio.close();
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
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
        prefs(host.context()).edit().remove("last_url").apply();
    }

    // ------------------------------------------------------------------ navegar

    private void go(String input) {
        String url = toUrl(input);
        if (url == null) return;
        ensureWeb().loadUrl(url);
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

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private WebView ensureWeb() {
        if (web != null) return web;
        web = new WebView(host.context());
        webAudio = new WebAudio(web);
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
                webAudio.navigation();
                loading = true;
                reload.setText("✕");
                progress.setVisibility(View.VISIBLE);
                showPage();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                loading = false;
                reload.setText("⟳");
                progress.setVisibility(View.INVISIBLE);
                v.evaluateJavascript(FOCUS_JS, null);
                showPage();
                remember(url, v.getTitle());
                webAudio.pageFinished();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                progress.setProgress(p);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                showPage();
            }

            @Override
            public void onReceivedIcon(WebView v, Bitmap icon) {
                saveIcon(host.context(), host(v.getUrl()), icon);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean dialog, boolean userGesture, Message result) {
                if (!userGesture) return false;
                // Una WebView de paso que solo recoge la dirección y la abre en la principal.
                WebView temp = new WebView(host.context());
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView tv, WebResourceRequest req) {
                        web.loadUrl(req.getUrl().toString());
                        tv.destroy();
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView tv, String url, Bitmap favicon) {
                        if (url != null && !url.equals("about:blank")) web.loadUrl(url);
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
        return web;
    }

    private void paintPc() {
        pc.setBackground(CarStyle.round(desktop ? CarStyle.ACCENT_BG : CarStyle.PILL_BG, 28));
        pc.setTextColor(desktop ? CarStyle.ACCENT : CarStyle.TEXT);
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
    }

    private void showKeyboard(boolean show, boolean forAddress) {
        typingAddress = forAddress;
        keyboard.setVisibility(show ? View.VISIBLE : View.GONE);
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
