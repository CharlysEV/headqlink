package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lo que se lee de la pantalla de Google Maps en Android Auto (MapsReader), por líneas con su recuadro en píxeles.
 * <ul>
 * <li>Navegando, abajo a la izquierda está el recuadro del tiempo y los km («30 min» y debajo «36 km · 21:09»): se
 * toca para abrir la tarjeta del viaje.</li>
 * <li>La tarjeta del viaje lleva arriba el destino en dos o tres líneas («Calle Mayor, 1», «Centro», «28001 Madrid»),
 * luego lo fácil que es aparcar y la línea «30 min · 36 km».</li>
 * </ul>
 * Puro: se prueba en el PC con líneas inventadas.
 */
final class MapsCard {
    private MapsCard() {
    }

    /** Una línea de texto leída, con su recuadro (px de la imagen). */
    static final class Line {
        final String text;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Line(String text, int left, int top, int right, int bottom) {
            this.text = text == null ? "" : text.trim();
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int cx() {
            return (left + right) / 2;
        }

        int cy() {
            return (top + bottom) / 2;
        }

        int w() {
            return right - left;
        }

        int h() {
            return bottom - top;
        }
    }

    /**
     * Un tiempo: «30 min», «1 h 5 min», «2 h», «6 h y 8 min»; también sin espacios («6hy8min»), como a veces lo da el
     * lector de texto. Tras la «h», nada de letras salvo «y» o «e» (las de «6 h y 8 min» y «6 h e 8 min»).
     */
    static final Pattern TIME = Pattern.compile("(?i)\\d\\s*min|\\d\\s*h(?![\\p{L}&&[^ye]])");
    /** Una distancia: «36 km», «1,7 km», «850 m», «12 mi». */
    static final Pattern DIST = Pattern.compile("(?i)(^|[^\\p{L}\\d])\\d{1,4}([.,]\\d+)?\\s*(km|mi)\\b");
    /** Lo fácil que es aparcar y otras líneas de la tarjeta que no son el destino (en los idiomas de la app). */
    private static final Pattern NOT_ADDRESS = Pattern.compile("(?i)^(?:\\W{0,3}P\\W{0,3}\\s+)?(f[aá]cil|moderado|dif[ií]cil|easy|medium|moderate|"
            + "hard|difficult|facile|moderato|difficile|mod[eé]r[eé]|einfach|mittel|schwer|makkelijk|gemiddeld|moeilijk|"
            + "aparcamiento.*|parking.*|estacionamento.*|parcheggio.*|parken.*|parkeren.*)$");

    /**
     * Puro: el recuadro del tiempo y los km de la navegación (cerrado): la línea con un tiempo más baja de la mitad de
     * abajo de la imagen que tenga una distancia justo debajo (o en ella). null si no se ve (Maps sin navegar, otra
     * pantalla de AA).
     */
    static Line etaBox(List<Line> lines, int imageH) {
        Line best = null;
        for (Line l : lines) {
            if (!TIME.matcher(l.text).find() || l.cy() < imageH * 0.5) continue;
            boolean dist = DIST.matcher(l.text).find();
            if (!dist) {
                for (Line d : lines) {
                    if (d == l || !DIST.matcher(d.text).find()) continue;
                    int gap = d.top - l.bottom;
                    boolean below = gap >= -l.h() / 2 && gap <= l.h() * 1.5;
                    boolean aligned = Math.abs(d.left - l.left) <= Math.max(40, l.w()) || Math.abs(d.cx() - l.cx()) <= l.w();
                    if (below && aligned) {
                        dist = true;
                        break;
                    }
                }
            }
            if (dist && (best == null || l.cy() > best.cy())) best = l;
        }
        return best;
    }

    /** Puro: la línea de los km del recuadro (la misma que eta si van juntos, o la de justo debajo). */
    static Line kmLine(List<Line> lines, Line eta) {
        if (eta == null) return null;
        if (DIST.matcher(eta.text).find()) return eta;
        Line best = null;
        for (Line d : lines) {
            if (d == eta || !DIST.matcher(d.text).find()) continue;
            int gap = d.top - eta.bottom;
            if (gap < -eta.h() / 2 || gap > eta.h() * 1.5) continue;
            if (best == null || d.top < best.top) best = d;
        }
        return best;
    }

    /**
     * Puro: dónde tocar el icono de la ruta (el último de la fila de botones del recuadro de navegación: abre la tarjeta
     * con el destino). En argb (w × h, de arriba abajo) se mira la franja de debajo de la línea de los km: el fondo del
     * recuadro es el color que más se repite justo ahí; los iconos, lo que contrasta con él (de día, negro sobre blanco;
     * de noche, claro sobre oscuro); el recuadro acaba donde deja de verse su fondo (empieza el mapa). Del último grupo
     * de columnas con icono, su centro. null si no se ve.
     */
    static int[] routeIcon(int[] argb, int w, int h, Line eta, Line km) {
        List<int[]> icons = rowIcons(argb, w, h, eta, km);
        return icons.isEmpty() ? null : icons.get(icons.size() - 1);
    }

    /** Puro: dónde tocar la X del recuadro de navegación (el primer icono de la fila: termina la navegación), o null. */
    static int[] cancelIcon(int[] argb, int w, int h, Line eta, Line km) {
        List<int[]> icons = rowIcons(argb, w, h, eta, km);
        return icons.size() < 2 ? null : icons.get(0);
    }

    /** Puro: los centros de los iconos de la fila de botones del recuadro, de izquierda a derecha (ver routeIcon). */
    static List<int[]> rowIcons(int[] argb, int w, int h, Line eta, Line km) {
        List<int[]> out = new ArrayList<>();
        if (argb == null || eta == null || km == null || argb.length < w * h) return out;
        int lh = Math.max(8, Math.max(eta.h(), km.h()));
        // La franja de los botones, sin llegar al borde de abajo del recuadro (debajo ya es mapa, con sus calles).
        int y0 = Math.min(h - 1, km.bottom + lh * 4 / 10);
        int y1 = Math.min(h - 1, km.bottom + lh * 19 / 10);
        int x0 = Math.max(0, eta.left - lh);
        int x1 = Math.min(w - 1, eta.left + lh * 18);
        if (y1 - y0 < lh || x1 - x0 < lh * 4) return out;
        // Fondo: el color (cuantizado) más repetido en la franja, del principio del recuadro.
        java.util.Map<Integer, Integer> hist = new java.util.HashMap<>();
        for (int y = y0; y <= y1; y += 2) {
            for (int x = Math.max(0, eta.left); x <= Math.min(x1, eta.left + lh * 4); x += 2) {
                int q = argb[y * w + x] & 0xF0F0F0;
                hist.merge(q, 1, Integer::sum);
            }
        }
        int bg = 0;
        int bgN = -1;
        for (java.util.Map.Entry<Integer, Integer> e : hist.entrySet()) {
            if (e.getValue() > bgN) {
                bgN = e.getValue();
                bg = e.getKey() | 0x080808;
            }
        }
        int bgL = luma(bg);
        // Por columnas: cuántos píxeles de icono y cuántos del fondo.
        int bands = y1 - y0 + 1;
        int panelEnd = x1;
        int notBgRun = 0;
        boolean[] icon = new boolean[x1 + 1];
        for (int x = x0; x <= x1; x++) {
            int ic = 0;
            int bgc = 0;
            for (int y = y0; y <= y1; y++) {
                int c = argb[y * w + x];
                // El fondo, con poca tolerancia: de día el recuadro es blanco y el mapa beis claro (con 28 se confundían y
                // se tomaban por iconos la ruta y el coche del mapa: 16/10, tema claro).
                if (near(c, bg, 12)) bgc++;
                else if (Math.abs(luma(c) - bgL) > 90) ic++;
            }
            icon[x] = ic >= 2;
            // El recuadro acaba donde el fondo deja de ser mayoría durante un buen trecho (ya es mapa).
            if (x > eta.left + lh * 3 && bgc < bands * 0.45) {
                if (++notBgRun >= lh) {
                    panelEnd = x - notBgRun;
                    break;
                }
            } else {
                notBgRun = 0;
            }
        }
        // Grupos de columnas con icono hasta el borde del recuadro (los huecos de menos de lh/3 son del mismo icono).
        int start = -1;
        int last = -1;
        for (int x = x0; x <= panelEnd + 1; x++) {
            boolean on = x <= panelEnd && icon[x];
            if (on) {
                if (start < 0) start = x;
                last = x;
            } else if (start >= 0 && (x > panelEnd || x - last > lh / 3)) {
                int[] c = iconCenter(argb, w, y0, y1, start, last, bg, bgL);
                if (last - start >= lh / 3 && c != null) out.add(c);
                start = -1;
            }
        }
        return rowRhythm(out, lh);
    }

    /**
     * Puro: los botones del recuadro van en fila, a la misma altura y con la misma separación (X, alternativas, añadir
     * parada y ruta): se quedan los que siguen ese ritmo, como mucho ROW_MAX. Así no cuenta lo del mapa de al lado cuando
     * su color se parece al del recuadro (de día, el mapa casi blanco y un globo con el nombre de una calle: 16/10).
     */
    static List<int[]> rowRhythm(List<int[]> icons, int lh) {
        List<int[]> out = new ArrayList<>();
        for (int[] c : icons) {
            if (out.size() >= ROW_MAX) break;
            if (!out.isEmpty() && Math.abs(c[1] - out.get(0)[1]) > lh) break;
            if (out.size() >= 2) {
                int gap = out.get(1)[0] - out.get(0)[0];
                int gi = c[0] - out.get(out.size() - 1)[0];
                if (Math.abs(gi - gap) > gap * 0.35) break;
            }
            out.add(c);
        }
        return out;
    }

    /** Botones de la fila del recuadro de navegación de Maps. */
    static final int ROW_MAX = 4;

    /** El centro de un icono: en horizontal, el de sus columnas; en vertical, la media de sus filas con píxeles de icono. */
    private static int[] iconCenter(int[] argb, int w, int y0, int y1, int start, int end, int bg, int bgL) {
        long sy = 0;
        int n = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = start; x <= end; x++) {
                int c = argb[y * w + x];
                if (!near(c, bg, 28) && Math.abs(luma(c) - bgL) > 90) {
                    sy += y;
                    n++;
                }
            }
        }
        if (n < 4) return null;
        return new int[]{(start + end) / 2, (int) (sy / n)};
    }

    static int luma(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    private static boolean near(int a, int b, int tol) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tol && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tol
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= tol;
    }

    /**
     * Puro: la línea «30 min · 36 km» de la tarjeta abierta (tiempo y distancia juntos), la más alta si hay varias.
     * null si la tarjeta no está abierta.
     */
    static Line cardEta(List<Line> lines) {
        Line best = null;
        for (Line l : lines) {
            if (!TIME.matcher(l.text).find() || !DIST.matcher(l.text).find()) continue;
            if (best == null || l.top < best.top) best = l;
        }
        return best;
    }

    /**
     * Puro: el destino escrito en la tarjeta abierta, en una línea («Calle Mayor, 1, Centro, 28001 Madrid»), o null. Las
     * líneas por encima de «min · km», dentro del ancho de la tarjeta (los nombres del mapa a su lado, fuera), de arriba
     * abajo, hasta la de aparcar; como mucho tres.
     */
    static String address(List<Line> lines) {
        Line eta = cardEta(lines);
        if (eta == null) return null;
        int half = Math.round(eta.w() * 1.3f);
        int minX = eta.cx() - half;
        int maxX = eta.cx() + half;
        List<Line> above = new ArrayList<>();
        for (Line l : lines) {
            if (l == eta || l.bottom > eta.top + 2) continue;
            if (l.left < minX || l.right > maxX) continue;
            if (l.text.isEmpty() || TIME.matcher(l.text).find()) continue;
            above.add(l);
        }
        Collections.sort(above, (a, b) -> Integer.compare(a.top, b.top));
        List<String> parts = new ArrayList<>();
        for (Line l : above) {
            if (NOT_ADDRESS.matcher(l.text).matches()) break;
            if (l.text.length() < 2) continue;
            parts.add(l.text);
            if (parts.size() == 3) break;
        }
        if (parts.isEmpty()) return null;
        String a = String.join(", ", parts);
        // Un destino de verdad tiene letras (no solo «P» o un número suelto).
        return a.matches("(?s).*\\p{L}{3}.*") ? a : null;
    }

    /**
     * Un código postal seguido de la ciudad: «28001 Madrid», «08011 Barcelona», «1000-001 Lisboa», «1012 AB Amsterdam».
     */
    static final Pattern POSTCODE = Pattern.compile("(?<![\\d-])(\\d{5}|\\d{4}-\\d{3}|\\d{4}(\\s?[A-Z]{2})?)\\s+(\\p{L}[\\p{L} .'-]*)");
    /** Una línea que no es el nombre ni la calle: valoración, precio, horario, teléfono, características. */
    private static final Pattern NOT_PLACE = Pattern.compile("(?i)(★|\\(\\d|^\\d+[.,]\\d\\b|€|\\babiert|\\babre\\b|\\bcierra|\\bhoras?\\b|"
            + "\\bopen|\\bclosed?\\b|\\baperto|\\bchiuso|\\bouvert|\\bferm[eé]|\\bgeöffnet|\\bgeschlossen|\\bgeopend|"
            + "\\bgesloten|\\baberto|\\bfechado|^[+\\d][\\d ]{6,}$|caracter[ií]sticas|features)");

    /**
     * Puro: lo que se busca para situar el destino de la tarjeta abierta, del más fiable al menos: la calle con su
     * código postal («Calle Ancha, 148, 28001 Madrid»), el nombre del sitio con la ciudad («Punto de carga Iberdrola,
     * Madrid») y, si no hay código postal, las primeras líneas (address). Sin repetir; vacía si la tarjeta no está abierta.
     */
    static List<String> addressQueries(List<Line> lines) {
        List<String> out = new ArrayList<>();
        Line eta = cardEta(lines);
        if (eta == null) return out;
        int half = Math.round(eta.w() * 1.3f);
        int minX = eta.cx() - half;
        int maxX = eta.cx() + half;
        List<Line> above = new ArrayList<>();
        for (Line l : lines) {
            if (l == eta || l.bottom > eta.top + 2) continue;
            if (l.left < minX || l.right > maxX) continue;
            if (l.text.length() < 2 || TIME.matcher(l.text).find()) continue;
            above.add(l);
        }
        Collections.sort(above, (a, b) -> Integer.compare(a.top, b.top));
        int pc = -1;
        for (int i = 0; i < above.size() && pc < 0; i++) if (POSTCODE.matcher(above.get(i).text).find()) pc = i;
        if (pc >= 0) {
            String postcode = above.get(pc).text;
            java.util.regex.Matcher m = POSTCODE.matcher(postcode);
            String city = m.find() ? m.group(3).trim() : "";
            // La calle: la línea más cercana por encima del código postal con un número (el portal) y que no sea la
            // valoración ni el horario; lo de en medio («Centro», «Eixample») es el barrio.
            int street = -1;
            for (int i = pc - 1; i >= Math.max(0, pc - 3) && street < 0; i--) {
                String t = above.get(i).text;
                if (t.matches(".*\\d.*") && !NOT_PLACE.matcher(t).find()) street = i;
            }
            if (street >= 0) out.add(above.get(street).text + ", " + postcode);
            // El nombre: las líneas de arriba hasta la valoración, el horario o la calle (como mucho dos).
            String name = nameLines(above, street >= 0 ? street : pc, false);
            if (name != null && !city.isEmpty()) out.add(name + ", " + city);
            if (street < 0) out.add(postcode);
        }
        String first = address(lines);
        if (first != null && !out.contains(first)) out.add(first);
        return out;
    }

    /**
     * Las líneas del nombre del sitio, de arriba abajo hasta la valoración, el horario o la línea last (la calle o el
     * código postal), como mucho dos, o null. forLabel: para enseñarlo, sin la segunda línea si viene cortada («Bar en …»,
     * su tipo) y sin el «|» que a veces lee el OCR.
     */
    private static String nameLines(List<Line> above, int last, boolean forLabel) {
        StringBuilder name = new StringBuilder();
        for (int i = 0, n = 0; i < last && n < 2; i++) {
            String raw = above.get(i).text;
            if (NOT_PLACE.matcher(raw).find()) break;
            if (forLabel && n == 1 && raw.matches(".*(…|\\.{3})\\s*$")) break;
            String t = raw.replaceAll("[|…]+|\\.{3}", " ").trim();
            if (t.isEmpty()) continue;
            if (name.length() > 0) name.append(' ');
            name.append(t);
            n++;
        }
        String out = name.toString().replaceAll("\\s+", " ").trim();
        return out.length() > 2 ? out : null;
    }

    /**
     * Puro: cómo llamar al destino de la tarjeta abierta: el nombre del sitio («Almacenes Sol Gran Vía», «Punto de
     * carga Iberdrola») o, si es una dirección sin más, la calle con su código postal; null si no se lee. El buscador
     * de direcciones solo sitúa el punto: su nombre puede ser el número del portal («7-9»).
     */
    static String placeLabel(List<Line> lines) {
        Line eta = cardEta(lines);
        if (eta == null) return null;
        int half = Math.round(eta.w() * 1.3f);
        int minX = eta.cx() - half;
        int maxX = eta.cx() + half;
        List<Line> above = new ArrayList<>();
        for (Line l : lines) {
            if (l == eta || l.bottom > eta.top + 2) continue;
            if (l.left < minX || l.right > maxX) continue;
            if (l.text.length() < 2 || TIME.matcher(l.text).find()) continue;
            above.add(l);
        }
        Collections.sort(above, (a, b) -> Integer.compare(a.top, b.top));
        int pc = -1;
        for (int i = 0; i < above.size() && pc < 0; i++) if (POSTCODE.matcher(above.get(i).text).find()) pc = i;
        if (pc < 0) return address(lines);
        int street = -1;
        for (int i = pc - 1; i >= Math.max(0, pc - 3) && street < 0; i--) {
            String t = above.get(i).text;
            if (t.matches(".*\\d.*") && !NOT_PLACE.matcher(t).find()) street = i;
        }
        String name = nameLines(above, street >= 0 ? street : pc, true);
        if (name != null) return name;
        return street >= 0 ? above.get(street).text + ", " + above.get(pc).text : above.get(pc).text;
    }

    /**
     * Puro: ¿AA tiene la pantalla partida (Maps y otra app al lado)? Entre las dos hay una franja vertical del fondo de
     * AA (negro de noche, claro de día), de arriba abajo, entre el 40 % y el 95 % del ancho; con una app a pantalla
     * completa no la hay.
     */
    static boolean splitScreen(int[] argb, int w, int h) {
        return splitGapX(argb, w, h) >= 0;
    }

    /**
     * Puro: el centro x de la franja de la pantalla partida (splitScreen), o -1. La franja tiene que acabar antes del 95 %
     * del ancho, con la otra app a su derecha: una banda del fondo que llega al final (la imagen de AA más estrecha que su
     * zona, como en el coche virtual) no es la pantalla partida (16-10: se pulsaba el icono de Maps con Maps ya a
     * pantalla completa, y así se partía). Y tiene que empezar después del 40 %, con contenido a su izquierda: la que ya
     * viene de antes es un panel de Maps (16-10: «franja al 42 %» con Maps a pantalla completa).
     */
    static int splitGapX(int[] argb, int w, int h) {
        int y0 = Math.round(h * 0.04f);
        int y1 = Math.round(h * 0.96f);
        int rows = 0;
        for (int y = y0; y < y1; y += 3) rows++;
        int minRun = Math.max(4, w / 400);
        int maxRun = Math.max(minRun, w / 20);
        int run = 0;
        int x0 = Math.round(w * 0.40f);
        for (int x = x0; x < Math.round(w * 0.95f); x++) {
            int first = luma(argb[y0 * w + x]);
            int ok = 0;
            for (int y = y0; y < y1; y += 3) {
                int l = luma(argb[y * w + x]);
                if (Math.abs(l - first) <= 12 && (l < 45 || l > 210)) ok++;
            }
            if (ok >= rows * 0.95) {
                run++;
            } else {
                if (run >= minRun && run <= maxRun && x - run > x0) return x - (run + 1) / 2;
                run = 0;
            }
        }
        return -1;
    }

    /** Los cuatro colores del icono de Google Maps (rojo, amarillo, verde, azul de Google). */
    private static final int[][] GOOGLE = {{234, 67, 53}, {251, 188, 4}, {52, 168, 83}, {66, 133, 244}};

    private static int googleColor(int c) {
        int r = (c >> 16) & 0xFF;
        int g = (c >> 8) & 0xFF;
        int b = c & 0xFF;
        for (int i = 0; i < GOOGLE.length; i++) {
            int[] t = GOOGLE[i];
            if (Math.abs(r - t[0]) < 60 && Math.abs(g - t[1]) < 60 && Math.abs(b - t[2]) < 60) return i;
        }
        return -1;
    }

    /**
     * Puro: el centro {x, y} del icono de Google Maps en la barra de apps de AA (a la izquierda), o null: el hueco del
     * tamaño de un icono, en el primer cuarto de la imagen, con los cuatro colores de Google juntos (la brújula de Maps
     * es roja y su botón de avisos amarillo, pero solos; Spotify y Teléfono, verdes).
     */
    static int[] mapsIcon(int[] argb, int w, int h) {
        int lim = Math.round(w * 0.25f);
        int gw = (lim + 1) / 2;
        int gh = (h + 1) / 2;
        // Rejilla de 2 px: por cada color, la suma acumulada (imagen integral).
        int[][] sum = new int[4][(gw + 1) * (gh + 1)];
        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int k = googleColor(argb[(gy * 2) * w + gx * 2]);
                for (int c = 0; c < 4; c++) {
                    int i = (gy + 1) * (gw + 1) + gx + 1;
                    sum[c][i] = (k == c ? 1 : 0) + sum[c][i - 1] + sum[c][i - (gw + 1)] - sum[c][i - (gw + 1) - 1];
                }
            }
        }
        int s = Math.max(4, Math.round(h * 0.07f) / 2);
        int step = Math.max(1, s / 4);
        int best = 0;
        int bx = -1;
        int by = -1;
        for (int gy = 0; gy + s <= gh; gy += step) {
            for (int gx = 0; gx + s <= gw; gx += step) {
                int m = Integer.MAX_VALUE;
                for (int c = 0; c < 4; c++) {
                    int a = gy * (gw + 1) + gx;
                    int b = (gy + s) * (gw + 1) + gx + s;
                    m = Math.min(m, sum[c][b] - sum[c][a + s] - sum[c][b - s] + sum[c][a]);
                }
                if (m > best) {
                    best = m;
                    bx = gx;
                    by = gy;
                }
            }
        }
        if (best < 3) return null;
        // El centro: la media de los píxeles de color de Google alrededor del hueco elegido.
        long sx = 0;
        long sy = 0;
        int n = 0;
        int x0 = Math.max(0, (bx - s / 2) * 2);
        int x1 = Math.min(lim, (bx + s + s / 2) * 2);
        int yA = Math.max(0, (by - s / 2) * 2);
        int yB = Math.min(h, (by + s + s / 2) * 2);
        for (int y = yA; y < yB; y += 2) {
            for (int x = x0; x < x1; x += 2) {
                if (googleColor(argb[y * w + x]) >= 0) {
                    sx += x;
                    sy += y;
                    n++;
                }
            }
        }
        return n == 0 ? null : new int[]{(int) (sx / n), (int) (sy / n)};
    }

    /** «Iniciar» de Maps en cada idioma de la app (en minúsculas). */
    private static final java.util.Set<String> START = new java.util.HashSet<>(java.util.Arrays.asList(
            "iniciar", "start", "avvia", "démarrer", "demarrer", "starten", "开始", "開始"));

    /**
     * Puro: el botón «Iniciar» de la ruta que Maps enseña antes de navegar (una línea de una o dos palabras, la última
     * «Iniciar»: el OCR a veces lee la flecha del icono como una letra), o null.
     */
    static Line startButton(List<Line> lines) {
        for (Line l : lines) {
            String t = l.text.trim().toLowerCase(java.util.Locale.ROOT);
            String[] w = t.split("\\s+");
            if (w.length <= 2 && START.contains(w[w.length - 1])) return l;
        }
        return null;
    }

    /** «Salir de la navegación» de Maps en cada idioma de la app (en minúsculas). */
    private static final String[] EXIT_NAV = {
            "salir de la navegación", "salir de la navegacion", "exit navigation", "sair da navegação", "sair da navegacao",
            "esci dalla navigazione", "quitter la navigation", "navigation beenden", "navigatie afsluiten", "navigatie stoppen",
            "退出导航", "結束導航", "退出導航"};

    /**
     * Puro: el botón «Salir de la navegación» del menú que abre la X de Maps cuando su ruta lleva paradas (junto a
     * «Quitar parada»), o null.
     */
    static Line exitNavButton(List<Line> lines) {
        for (Line l : lines) {
            String t = l.text.trim().toLowerCase(java.util.Locale.ROOT);
            for (String e : EXIT_NAV) if (t.contains(e)) return l;
        }
        return null;
    }

    /** Lo leído de la tarjeta: las búsquedas (de la más fiable a la menos) y cómo llamar al destino. */
    static final class Read {
        final List<String> queries;
        final String label;

        Read(List<String> queries, String label) {
            this.queries = queries;
            this.label = label;
        }
    }

    /** Puro: ¿sigue abierta la tarjeta? (para comprobar que se ha cerrado). */
    static boolean cardOpen(List<Line> lines) {
        return cardEta(lines) != null && address(lines) != null;
    }

    /** Para el log: cuántas líneas y si se ha visto cada cosa, sin el texto (lleva direcciones). */
    static String summary(List<Line> lines, Line eta) {
        return String.format(Locale.US, "%d líneas, recuadro %s", lines.size(), eta != null ? "sí" : "no");
    }
}
