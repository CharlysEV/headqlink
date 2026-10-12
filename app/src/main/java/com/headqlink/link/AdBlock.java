package com.headqlink.link;

import java.util.Locale;

/**
 * Bloqueo básico de anuncios y rastreadores del navegador del coche: una lista corta de dominios de publicidad y
 * analítica de terceros. Lo que pide una página a esos dominios recibe una respuesta vacía (WebScreen). No toca los
 * vídeos (googlevideo.com, los de YouTube, Disney+…) ni los propios de cada web. Puro: se prueba en el PC.
 */
final class AdBlock {
    private static final String[] HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "google-analytics.com", "googletagmanager.com", "googletagservices.com", "pagead2.googlesyndication.com",
            "adnxs.com", "criteo.com", "criteo.net", "taboola.com", "outbrain.com", "amazon-adsystem.com",
            "scorecardresearch.com", "quantserve.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net",
            "casalemedia.com", "moatads.com", "teads.tv", "smartadserver.com", "3lift.com", "sharethrough.com",
            "hotjar.com", "ads-twitter.com", "adform.net", "media.net", "zemanta.com", "yieldmo.com", "bidswitch.net",
            "advertising.com", "adcolony.com", "applovin.com", "unityads.unity3d.com", "chartbeat.com", "mgid.com",
            "seedtag.com", "adition.com", "smartclip.net", "sascdn.com",
    };

    private AdBlock() {
    }

    /** Puro: ¿es de un dominio de la lista (o un subdominio suyo)? */
    static boolean isAd(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : HOSTS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }
}
