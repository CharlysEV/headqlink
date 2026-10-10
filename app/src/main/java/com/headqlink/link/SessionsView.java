package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Sesiones» en Diagnóstico: las últimas sesiones con el coche, de sessions.csv, en una línea cada una (fecha,
 * duración, conexión, fps, cortes y cómo acabó), para ver qué falló sin mandar logs. Puro: lo prueban los tests.
 */
final class SessionsView {
    private SessionsView() {
    }

    /** Una sesión ya resumida. */
    static final class Row {
        final String start;
        final double seconds;
        final String link;
        final double fps;
        final int cuts;
        final int maxCutMs;
        final boolean video;
        final String end;

        Row(String start, double seconds, String link, double fps, int cuts, int maxCutMs, boolean video, String end) {
            this.start = start;
            this.seconds = seconds;
            this.link = link;
            this.fps = fps;
            this.cuts = cuts;
            this.maxCutMs = maxCutMs;
            this.video = video;
            this.end = end;
        }
    }

    /**
     * Las filas del CSV (con su cabecera, que puede repetirse si cambió de columnas: vale la última antes de cada fila),
     * de la más reciente a la más antigua, como mucho max.
     */
    static List<Row> parse(List<String> lines, int max) {
        List<Row> out = new ArrayList<>();
        Map<String, Integer> col = null;
        for (String line : lines) {
            if (line.isEmpty()) continue;
            List<String> cells = split(line);
            if (cells.get(0).equals("inicio")) {
                col = new HashMap<>();
                for (int i = 0; i < cells.size(); i++) col.put(cells.get(i), i);
                continue;
            }
            if (col == null) continue;
            try {
                out.add(new Row(cell(cells, col, "inicio"), num(cell(cells, col, "duracion_s")), cell(cells, col, "conexion"),
                        num(cell(cells, col, "fps")), (int) num(cell(cells, col, "cortes")), (int) num(cell(cells, col, "max_corte_ms")),
                        !"0".equals(cell(cells, col, "llego_a_video")), cell(cells, col, "cierre_detalle")));
            } catch (RuntimeException ignored) {
            }
        }
        Collections.reverse(out);
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    /** Una línea legible: «09/10 22:47 · 7 min 32 s · Cable USB · 35 fps · 2 cortes (máx. 3 s) · servicio parado». */
    static String line(Row r) {
        StringBuilder b = new StringBuilder();
        // «2026-10-09 22:47:18.323» → «09/10 22:47».
        if (r.start.length() >= 16) b.append(r.start, 8, 10).append('/').append(r.start, 5, 7).append(' ').append(r.start, 11, 16);
        else b.append(r.start);
        b.append(" · ").append(duration(r.seconds));
        b.append(" · ").append(Ui.linkTitle(r.link));
        if (!r.video) {
            b.append(" · ").append(Str.get(R.string.hql_sessions_no_video));
        } else {
            b.append(" · ").append(Math.round(r.fps)).append(" fps");
            if (r.cuts > 0) {
                b.append(" · ").append(Str.get(R.string.hql_sessions_cuts, r.cuts));
                if (r.maxCutMs > 0) b.append(" (").append(Str.get(R.string.hql_sessions_max_cut, Math.round(r.maxCutMs / 1000.0))).append(')');
            }
        }
        if (!r.end.isEmpty()) b.append(" · ").append(r.end);
        return b.toString();
    }

    static String duration(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) return s + " s";
        long m = s / 60;
        if (m < 60) return m + " min " + (s % 60) + " s";
        return (m / 60) + " h " + (m % 60) + " min";
    }

    private static String cell(List<String> cells, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= cells.size() ? "" : cells.get(i);
    }

    private static double num(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** CSV con comillas dobles (y «""» dentro). */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else q = false;
                } else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }
}
