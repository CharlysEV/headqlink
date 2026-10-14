package com.headqlink.link;

import java.util.Locale;

/**
 * Puro: lo que no es vista de la pantalla de Ajustes del móvil (SettingsActivity): opciones de cada selector, el valor
 * que sale debajo de cada fila, la validación de lo que se escribe (precios y números de Avanzado) y si hay que volver a
 * negociar con Android Auto al reconectar. Lo prueba SettingsRowsTest.
 */
final class SettingsRows {
    /** «Tamaño de la interfaz del coche» (Config.uiScalePercent): 0 = automático, o el porcentaje elegido. */
    static final int[] UI_SCALE_CHOICES = {0, 60, 70, 80, 90, 100};
    /** Pantalla del T03 (10,1"), para decir en «Automático» a cuánto se queda allí la interfaz. */
    static final int T03_W = 1024;
    static final int T03_H = 600;
    /** Perfiles H.264 de Avanzado, en el orden del selector. */
    static final String[] H264 = {"baseline", "main", "high"};
    static final String[] H264_NAMES = {"Baseline", "Main", "High"};
    /** «Protección térmica», en el orden del selector. */
    static final String[] THERMAL = {ThermalPolicy.MODE_NORMAL, ThermalPolicy.MODE_SOFT, ThermalPolicy.MODE_OFF};
    /** Precios: lo mismo que admite Config (y los − y + de la pantalla del coche). */
    static final double PRICE_HOME_MAX = 1.0;
    static final double PRICE_FAST_MAX = 1.5;
    /** Rangos de los números de Avanzado (los del antiguo diálogo «Ajustes de imagen»). */
    static final int FPS_MAX = 120;
    static final int KBPS_MAX = 100_000;
    static final int SIZE_MAX = 4096;
    static final int WINDOW_MAX = 64;

    private SettingsRows() {
    }

    /** Puro: perfiles de imagen del selector: "" (Automático, sigue al recomendado) y después VideoProfile.ALL. */
    static String[] profileIds() {
        String[] ids = new String[VideoProfile.ALL.length + 1];
        ids[0] = "";
        System.arraycopy(VideoProfile.ALL, 0, ids, 1, VideoProfile.ALL.length);
        return ids;
    }

    /** Puro: posición de v entre las opciones, o -1 si no es ninguna. */
    static int indexOf(int[] choices, int v) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == v) return i;
        }
        return -1;
    }

    /** Puro: posición de v entre las opciones, o la de por defecto (def) si no es ninguna. */
    static int indexOf(String[] choices, String v, int def) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i].equals(v)) return i;
        }
        return def;
    }

    /** Puro: porcentaje al que se queda la interfaz del modo extendido en el T03 con «Automático» (UiScale). */
    static int uiScaleAutoT03() {
        return Math.round(UiScale.forCar(T03_W, T03_H, 0) * 100);
    }

    /** Puro: un número de Avanzado debajo de su fila: el valor, o autoLabel si es 0 (automático). */
    static String numberSummary(int v, String autoLabel) {
        return v > 0 ? String.valueOf(v) : autoLabel;
    }

    /** Puro: un precio como se enseña («0,20 €/kWh» en español). */
    static String priceText(double eurPerKwh, Locale loc) {
        return String.format(loc, "%.2f €/kWh", eurPerKwh);
    }

    /** Puro: el precio para el campo de texto (sin la unidad, con la coma o el punto del idioma). */
    static String priceField(double eurPerKwh, Locale loc) {
        return String.format(loc, "%.2f", eurPerKwh);
    }

    /**
     * Puro: un precio escrito (€/kWh) redondeado al céntimo, o -1 si no vale: vacío, letras, más de dos cifras enteras o
     * por encima de max. Admite coma o punto y que se escriba la unidad («0,25 €», «0.25 €/kWh»).
     */
    static double parsePrice(String text, double max) {
        if (text == null) return -1;
        String s = text.trim().replaceAll("(?i)€?\\s*/\\s*kwh$", "").replace("€", "").trim().replace(',', '.');
        if (!s.matches("[0-9]{0,2}(\\.[0-9]{0,4})?") || s.isEmpty() || s.equals(".")) return -1;
        double v = Math.round(Double.parseDouble(s) * 100) / 100.0;
        return v <= max + 1e-9 ? v : -1;
    }

    /**
     * Puro: al aplicar lo cambiado con el enlace en marcha, si Android Auto tiene que volver a negociar (resolución, fps y
     * densidad se negocian al conectar): otro perfil, otros fps (fluidez), otro «Tamaño de Android Auto» u otro tamaño de
     * la interfaz del coche (cambia el hueco que le queda a AA).
     */
    static boolean renegotiate(String beforeId, int beforeFps, String afterId, int afterFps, boolean aaSizeChanged,
                               boolean uiScaleChanged) {
        return !beforeId.equals(afterId) || beforeFps != afterFps || aaSizeChanged || uiScaleChanged;
    }
}
