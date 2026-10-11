package com.headqlink.link;

import android.webkit.WebView;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Cada documento (incluidos los iframes) comunica solamente si tiene audio y recibe la orden de pausa. */
final class WebAudio {
    private final WebView web;
    private final MediaFocus focus;
    private final Map<String, JavaScriptReplyProxy> playing = new HashMap<>();
    private ScriptHandler script;
    private final String source;
    private boolean closed;
    private boolean messaging;

    WebAudio(WebView web) {
        this.web = web;
        focus = new MediaFocus(web.getContext(), this::pause);
        try (InputStream in = web.getContext().getAssets().open("web_audio.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            source = out.toString("UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("web audio script", e);
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            messaging = true;
            WebViewCompat.addWebMessageListener(web, "HqlAudio", java.util.Collections.singleton("*"),
                    (view, message, origin, mainFrame, reply) -> {
                        if (closed) return;
                        String text = message.getData();
                        if (text == null || text.length() > 100) return;
                        if (text.startsWith("play:")) {
                            playing.put(text.substring(5), reply);
                            if (!focus.acquire()) pause();
                        } else if (text.startsWith("idle:")) {
                            playing.remove(text.substring(5));
                            if (playing.isEmpty()) focus.release();
                        }
                    });
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                script = WebViewCompat.addDocumentStartJavaScript(web, source, java.util.Collections.singleton("*"));
            }
        }
    }

    void pause() {
        for (JavaScriptReplyProxy reply : new java.util.ArrayList<>(playing.values())) reply.postMessage("pause");
        playing.clear();
        focus.release();
    }

    void navigation() {
        playing.clear();
        focus.release();
    }

    void pageFinished() {
        if (!closed && messaging && script == null) web.evaluateJavascript(source, null);
    }

    void close() {
        closed = true;
        playing.clear();
        focus.close();
        if (script != null) script.remove();
        if (messaging) WebViewCompat.removeWebMessageListener(web, "HqlAudio");
    }
}
