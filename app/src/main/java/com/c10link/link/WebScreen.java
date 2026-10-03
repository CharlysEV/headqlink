package com.c10link.link;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Navegador web en la pantalla del coche, con accesos directos y teclado propio (CarKeyboard).
 * El vídeo a pantalla completa (YouTube…) ocupa toda la zona de contenido.
 * Pendiente (fases finales): bloquear el vídeo con el coche en marcha.
 */
final class WebScreen implements CarScreen {
    static final String[][] DEFAULT_SHORTCUTS = {
            {"YouTube", "https://m.youtube.com"},
            {"Google", "https://www.google.com"},
            {"Wikipedia", "https://es.wikipedia.org"},
            {"Twitch", "https://m.twitch.tv"},
    };

    private Host host;
    private WebView web;
    private FrameLayout body;
    private View home;
    private TextView address;
    private CarKeyboard keyboard;
    private FrameLayout root;
    private View customView;
    /** El teclado escribe en la barra de dirección (true) o en la página (false). */
    private boolean typingAddress;
    private final StringBuilder addressBuf = new StringBuilder();

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
        TextView reload = CarStyle.pill(c, "⟳");
        TextView start = CarStyle.pill(c, "Inicio");
        address = CarStyle.text(c, "Buscar o escribir dirección", 24, CarStyle.TEXT_DIM);
        address.setSingleLine(true);
        address.setPadding(22, 12, 22, 12);
        address.setBackground(CarStyle.round(CarStyle.ITEM_BG, 28));
        TextView kb = CarStyle.pill(c, "Teclado");
        bar.addView(back);
        bar.addView(fwd);
        bar.addView(reload);
        bar.addView(start);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        alp.rightMargin = 10;
        bar.addView(address, alp);
        bar.addView(kb);
        col.addView(bar);

        body = new FrameLayout(c);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        keyboard = new CarKeyboard(c, new CarKeyboard.Listener() {
            @Override
            public void onText(String s) {
                if (typingAddress) {
                    addressBuf.append(s);
                    address.setText(addressBuf);
                    address.setTextColor(CarStyle.TEXT);
                } else if (web != null) {
                    web.requestFocus();
                    web.evaluateJavascript("document.execCommand('insertText', false, " + JSONObject.quote(s) + ")", null);
                }
            }

            @Override
            public void onBackspace() {
                if (typingAddress) {
                    if (addressBuf.length() > 0) addressBuf.setLength(addressBuf.length() - 1);
                    address.setText(addressBuf);
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
            }
        });
        keyboard.setVisibility(View.GONE);
        col.addView(keyboard);

        back.setOnClickListener(v -> {
            if (web != null && web.canGoBack()) web.goBack();
            else showHome();
        });
        fwd.setOnClickListener(v -> {
            if (web != null && web.canGoForward()) web.goForward();
        });
        reload.setOnClickListener(v -> {
            if (web != null) web.reload();
        });
        start.setOnClickListener(v -> showHome());
        address.setOnClickListener(v -> {
            addressBuf.setLength(0);
            address.setText("");
            showKeyboard(true, true);
        });
        kb.setOnClickListener(v -> showKeyboard(keyboard.getVisibility() != View.VISIBLE, false));

        home = buildHome(c);
        showHome();
        return root;
    }

    @Override
    public void destroy() {
        if (web != null) {
            web.stopLoading();
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
    }

    private View buildHome(Context c) {
        GridLayout g = new GridLayout(c);
        g.setColumnCount(4);
        g.setPadding(30, 30, 30, 30);
        String[][] links = new Config(c).webShortcuts();
        int w = (host.width() - 60) / 4;
        for (String[] l : links) {
            TextView t = CarStyle.text(c, l[0], 28, CarStyle.TEXT);
            t.setGravity(android.view.Gravity.CENTER);
            t.setBackground(CarStyle.round(CarStyle.ITEM_BG, 20));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = w - 20;
            lp.height = 140;
            lp.setMargins(10, 10, 10, 10);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> go(l[1]));
            g.addView(t);
        }
        return g;
    }

    private void showHome() {
        body.removeAllViews();
        body.addView(home, CarStyle.match());
        address.setText("Buscar o escribir dirección");
        address.setTextColor(CarStyle.TEXT_DIM);
    }

    private void go(String input) {
        String s = input.trim();
        if (s.isEmpty()) return;
        String url;
        if (s.startsWith("http://") || s.startsWith("https://")) url = s;
        else if (s.contains(".") && !s.contains(" ")) url = "https://" + s;
        else url = "https://www.google.com/search?q=" + Uri.encode(s);
        ensureWeb().loadUrl(url);
        body.removeAllViews();
        body.addView(web, CarStyle.match());
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView ensureWeb() {
        if (web != null) return web;
        web = new WebView(host.context());
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                address.setText(url);
                address.setTextColor(CarStyle.TEXT);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                // Vídeo a pantalla completa: por encima de la barra y del teclado.
                customView = view;
                root.addView(view, CarStyle.match());
            }

            @Override
            public void onHideCustomView() {
                if (customView != null) root.removeView(customView);
                customView = null;
            }
        });
        return web;
    }

    private void showKeyboard(boolean show, boolean forAddress) {
        typingAddress = forAddress;
        keyboard.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show && forAddress && addressBuf.length() == 0 && web == null) showHome();
        if (show && !forAddress && web != null) web.requestFocus();
    }

    private void key(int code) {
        web.requestFocus();
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
}
