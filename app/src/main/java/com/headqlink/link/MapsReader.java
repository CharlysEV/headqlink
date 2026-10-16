package com.headqlink.link;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.KeyEvent;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * El destino de Google Maps leído de su pantalla en Android Auto, sin tocar el móvil: la app toca el recuadro del
 * tiempo y los km (abre la tarjeta del viaje), lee la dirección de la imagen que sale al coche con el lector de texto
 * de Google (ML Kit, en el móvil, sin red) y la cierra con «atrás». Un segundo, más o menos. Solo lo pide el
 * planificador cuando no ha podido deducir el destino por los km y el coche va despacio (RoutePlanner.guessFromMaps).
 * Ni el texto ni la imagen se guardan ni van al log (llevan direcciones).
 */
final class MapsReader {
    /** Lo que se espera a que la tarjeta se abra o se cierre (la animación de AA). */
    static final long CARD_ANIM_MS = 750;
    /**
     * Al empezar a navegar, Maps pasa unos segundos por el mapa general (y a veces un aviso, «Hora de apertura»): se
     * espera FIRST_WAIT_MS y se busca el recuadro cada RETRY_MS hasta RETRIES veces.
     */
    static final long FIRST_WAIT_MS = 2500;
    static final long RETRY_MS = 2000;
    /**
     * Unos 15 s en total: Maps puede tapar el recuadro con un aviso («Hora de apertura»: el sitio está cerrado) hasta que
     * el conductor decide; no se pulsa nada por él, y si no aparece a tiempo, no se toca nada.
     */
    static final int RETRIES = 7;
    /** Tras tocar el icono: miradas a la tarjeta (la primera a CARD_ANIM_MS, las demás cada CARD_LOOK_MS). */
    static final int CARD_LOOKS = 5;
    static final long CARD_LOOK_MS = 600;
    /** Copias tras el «atrás» para ver que la tarjeta se ha cerrado. */
    private static final int CLOSE_LOOKS = 3;
    /** Copias en las que se busca la X de Maps para quitar su navegación, y la pausa entre ellas. */
    private static final int X_LOOKS = 3;
    private static final long X_LOOK_MS = 700;
    /** Lo que se espera a cada copia de la imagen y a cada lectura. */
    static final long CAPTURE_MS = 2000;
    static final long OCR_MS = 4000;

    private static volatile boolean busy;
    /** Entre la copia en la que se busca el icono y el cierre de la tarjeta: el panel del coche no se mueve. */
    private static volatile boolean hold;

    private MapsReader() {
    }

    /**
     * ¿Está mirando la pantalla o tocando? Mientras, el panel del coche no se mueve (Android Auto cambiaría de sitio entre
     * la copia y el toque). Solo durante cada intento y la lectura de la tarjeta, no mientras espera.
     */
    static boolean busy() {
        return hold;
    }

    /**
     * Lee el destino; done recibe la dirección o null. Uno a la vez. stillOk: se mira antes de cada intento (Maps sigue
     * navegando y el coche sigue despacio); si deja de cumplirse, no se toca nada.
     */
    static void read(Context ctx, java.util.function.BooleanSupplier stillOk, Consumer<MapsCard.Read> done) {
        if (busy) {
            done.accept(new MapsCard.Read(new ArrayList<>(), null));
            return;
        }
        busy = true;
        new Thread(() -> {
            MapsCard.Read out = new MapsCard.Read(new ArrayList<>(), null);
            TextRecognizer rec = null;
            try {
                rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                out = readNow(ctx, rec, stillOk);
            } catch (Exception | LinkageError e) {
                L.w("Maps: lectura de la tarjeta: " + e.getClass().getSimpleName());
            } finally {
                if (rec != null) rec.close();
                hold = false;
                busy = false;
            }
            done.accept(out);
        }, "maps-reader").start();
    }

    private static MapsCard.Read readNow(Context ctx, TextRecognizer rec, java.util.function.BooleanSupplier stillOk) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        SystemClock.sleep(FIRST_WAIT_MS);
        // 0) Maps a pantalla completa (si estaba partida).
        AaPassthroughSource first = AaPassthroughSource.current();
        if (first == null || !first.aaOnScreen()) return new MapsCard.Read(new ArrayList<>(), null);
        hold = true;
        if (!ensureMapsFull(first)) {
            hold = false;
            return new MapsCard.Read(new ArrayList<>(), null);
        }
        // 1) El recuadro del tiempo y los km (cuando Maps ya está en la vista de navegación).
        AaPassthroughSource src = null;
        MapsCard.Line eta = null;
        int[] icon = null;
        int seen = 0;
        int etaNoIcon = 0;
        for (int i = 0; i < RETRIES && icon == null; i++) {
            if (i > 0) SystemClock.sleep(RETRY_MS);
            if (!stillOk.getAsBoolean()) {
                L.i("Maps: dejo de buscar la tarjeta (Maps ya no navega o Android Auto no está en pantalla)");
                return new MapsCard.Read(new ArrayList<>(), null);
            }
            src = AaPassthroughSource.current();
            if (src == null || !src.aaOnScreen()) continue;
            hold = true;
            Bitmap shot = capture(src);
            if (shot == null) {
                hold = false;
                continue;
            }
            List<MapsCard.Line> lines = ocr(rec, shot);
            int w = shot.getWidth();
            int h = shot.getHeight();
            seen = lines.size();
            eta = MapsCard.etaBox(lines, h);
            if (eta != null) {
                // El icono de la ruta (abre la tarjeta con el destino), por la imagen: no tiene texto.
                int[] px = new int[w * h];
                shot.getPixels(px, 0, w, 0, 0, w, h);
                icon = MapsCard.routeIcon(px, w, h, eta, MapsCard.kmLine(lines, eta));
                lastShot(ctx, shot);
                if (icon == null) {
                    etaNoIcon++;
                }
            }
            if (icon == null) hold = false;
            shot.recycle();
        }
        if (icon == null || src == null) {
            L.i(String.format(java.util.Locale.US, "Maps: no veo el icono de la ruta tras %d intentos (en %d vi el recuadro sin el "
                    + "icono; última copia con %d líneas): no toco nada", RETRIES, etaNoIcon, seen));
            return new MapsCard.Read(new ArrayList<>(), null);
        }
        // 2) Tocar el icono de la ruta y leer la tarjeta.
        float[] p = src.aaToCar(icon[0], icon[1]);
        L.i(String.format(java.util.Locale.US, "Maps: recuadro (%d,%d)-(%d,%d), icono de la ruta en (%d,%d) de la imagen -> coche (%.0f,%.0f)",
                eta.left, eta.top, eta.right, eta.bottom, icon[0], icon[1], p[0], p[1]));
        src.tapCar(p[0], p[1]);
        List<String> address = new ArrayList<>();
        String label = null;
        boolean opened = false;
        for (int i = 0; i < CARD_LOOKS && address.isEmpty(); i++) {
            SystemClock.sleep(i == 0 ? CARD_ANIM_MS : CARD_LOOK_MS);
            Bitmap card = capture(src);
            if (card == null) continue;
            List<MapsCard.Line> cardLines = ocr(rec, card);
            card.recycle();
            opened |= MapsCard.cardEta(cardLines) != null;
            address = MapsCard.addressQueries(cardLines);
            if (!address.isEmpty()) label = MapsCard.placeLabel(cardLines);
        }
        // 3) Cerrarla con un «atrás» (si se abrió) y comprobarlo. Nunca un segundo: si el primero ya la cerró y la copia
        // llegó tarde, el segundo quitaría la navegación de Maps (pasó en el coche).
        if (opened) {
            AaPassthroughSource.carKey(ctx, KeyEvent.KEYCODE_BACK);
            boolean still = true;
            for (int i = 0; i < CLOSE_LOOKS && still; i++) {
                SystemClock.sleep(i == 0 ? CARD_ANIM_MS : CARD_LOOK_MS);
                Bitmap after = capture(src);
                if (after == null) continue;
                still = MapsCard.cardOpen(ocr(rec, after));
                after.recycle();
            }
            if (still) L.w("Maps: la tarjeta sigue abierta tras «atrás»: la dejo (otro «atrás» podría quitar la navegación)");
        }
        L.i(String.format(java.util.Locale.US, "Maps: tarjeta %s, destino %s · %d ms", opened ? "abierta y cerrada" : "no abierta",
                address.isEmpty() ? "no leído" : "leído (" + address.size() + " búsquedas)", SystemClock.elapsedRealtime() - t0));
        return new MapsCard.Read(address, label);
    }

    /**
     * Quita la navegación que tenga Google Maps tocando la X de su recuadro (para mandarle después la del plan, con las
     * paradas de carga). Fuera del hilo principal; then se llama siempre al acabar (se haya podido o no).
     */
    static void cancelNavigation(Context ctx, Runnable then) {
        cancelNavigation(ctx, ok -> then.run());
    }

    /** ¿Navega Google Maps? (su notificación de navegación, con km por delante). */
    private static boolean navigating() {
        return MapsNavListener.routing();
    }

    /** Como cancelNavigation; then recibe si Maps ha dejado de navegar (o no navegaba). */
    static void cancelNavigation(Context ctx, Consumer<Boolean> then) {
        boolean[] killed = {true};
        if (busy) {
            then.accept(!navigating());
            return;
        }
        busy = true;
        new Thread(() -> {
            TextRecognizer rec = null;
            try {
                rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                AaPassthroughSource src = AaPassthroughSource.current();
                if (src != null && src.aaOnScreen() && !ensureMapsFull(src)) {
                    killed[0] = !navigating();
                } else if (src != null && src.aaOnScreen()) {
                    hold = true;
                    // La X, en hasta X_LOOKS copias (AA puede estar recolocándose o Maps enseñando otra cosa).
                    int[] x = null;
                    for (int look = 0; look < X_LOOKS && x == null && navigating(); look++) {
                        if (look > 0) SystemClock.sleep(X_LOOK_MS);
                        Bitmap shot = capture(src);
                        if (shot == null) continue;
                        List<MapsCard.Line> lines = ocr(rec, shot);
                        int w = shot.getWidth();
                        int h = shot.getHeight();
                        MapsCard.Line eta = MapsCard.etaBox(lines, h);
                        if (eta != null) {
                            int[] px = new int[w * h];
                            shot.getPixels(px, 0, w, 0, 0, w, h);
                            x = MapsCard.cancelIcon(px, w, h, eta, MapsCard.kmLine(lines, eta));
                        }
                        shot.recycle();
                        if (x == null) L.i("Maps: no veo la X de su navegación (copia " + (look + 1) + " de " + X_LOOKS + ")");
                    }
                    if (x != null) {
                        float[] p = src.aaToCar(x[0], x[1]);
                        // Se toca la X y se comprueba que Maps deja de navegar (su notificación se va). Con paradas en la
                        // ruta, la X abre un menú («Quitar parada» / «Salir de la navegación»): se pulsa salir. Si no
                        // hay menú, la X otra vez.
                        src.tapCar(p[0], p[1]);
                        for (int k = 0; k < 15 && navigating(); k++) SystemClock.sleep(200);
                        if (navigating()) {
                            Bitmap menu = capture(src);
                            MapsCard.Line exit = null;
                            if (menu != null) {
                                exit = MapsCard.exitNavButton(ocr(rec, menu));
                                menu.recycle();
                            }
                            if (exit != null) {
                                float[] e = src.aaToCar(exit.cx(), (exit.top + exit.bottom) / 2f);
                                L.i("Maps: su ruta lleva paradas: pulso «Salir de la navegación»");
                                src.tapCar(e[0], e[1]);
                            } else {
                                src.tapCar(p[0], p[1]);
                            }
                            for (int k = 0; k < 15 && navigating(); k++) SystemClock.sleep(200);
                        }
                        boolean gone = !navigating();
                        L.i("Maps: su navegación " + (gone ? "quitada (X)" : "sigue tras tocar la X"));
                        if (!gone) killed[0] = false;
                    } else {
                        killed[0] = !navigating();
                    }
                }
            } catch (Exception | LinkageError e) {
                L.w("Maps: quitar su navegación: " + e.getClass().getSimpleName());
            } finally {
                if (rec != null) rec.close();
                hold = false;
                busy = false;
            }
            then.accept(killed[0] && !navigating());
        }, "maps-cancel").start();
    }

    /** «Iniciar» tras mandarle la ruta a Maps: primera copia, entre copias y cuántas. */
    private static final long START_FIRST_MS = 2500;
    private static final long START_LOOK_MS = 1500;
    private static final int START_LOOKS = 6;

    /**
     * Tras mandarle a Maps la ruta con las paradas (lo pidió el conductor con «Añadir a Maps»): Maps la enseña y espera
     * a «Iniciar»; se busca ese botón por su texto y se pulsa, hasta que Maps navega. Fuera del hilo principal.
     */
    static void pressStart(Context ctx) {
        if (busy) return;
        busy = true;
        new Thread(() -> {
            TextRecognizer rec = null;
            boolean pressed = false;
            try {
                rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                for (int i = 0; i < START_LOOKS && !navigating(); i++) {
                    SystemClock.sleep(i == 0 ? START_FIRST_MS : START_LOOK_MS);
                    AaPassthroughSource src = AaPassthroughSource.current();
                    if (src == null || !src.aaOnScreen() || navigating()) continue;
                    hold = true;
                    // Sin pasar a pantalla completa: en «Detalles de la ruta» el icono de Maps no lo hace, y el toque va
                    // solo donde esta misma copia enseña el «Iniciar» de Maps.
                    Bitmap shot = capture(src);
                    if (shot == null) continue;
                    List<MapsCard.Line> lines = ocr(rec, shot);
                    shot.recycle();
                    MapsCard.Line b = MapsCard.startButton(lines);
                    if (b == null) continue;
                    float[] p = src.aaToCar(b.cx(), (b.top + b.bottom) / 2f);
                    L.i("Maps: pulso «Iniciar» (copia " + (i + 1) + " de " + START_LOOKS + ")");
                    src.tapCar(p[0], p[1]);
                    pressed = true;
                    for (int k = 0; k < 15 && !navigating(); k++) SystemClock.sleep(200);
                }
                L.i("Maps: " + (navigating() ? "navega con las paradas" : pressed ? "sigue sin navegar tras pulsar «Iniciar»" : "no veo «Iniciar»"));
            } catch (Exception | LinkageError e) {
                L.w("Maps: pulsar «Iniciar»: " + e.getClass().getSimpleName());
            } finally {
                if (rec != null) rec.close();
                hold = false;
                busy = false;
            }
        }, "maps-start").start();
    }

    /** Tras pulsar el icono de Maps: lo que tarda AA en poner Maps a pantalla completa. */
    private static final long FULL_ANIM_MS = 1500;

    /**
     * Antes de tocar nada en Maps, Maps a pantalla completa en AA: con la pantalla partida (Maps y la tarjeta de música
     * al lado) se pulsa el icono de Maps de la barra de AA, que alterna entre partida y completa, y se comprueba. Así un
     * toque nunca cae en otra app que se ponga delante. false: no se ha podido (y no se toca nada más).
     */
    private static boolean ensureMapsFull(AaPassthroughSource src) throws InterruptedException {
        // Un solo toque: el icono alterna entre partida y completa (un segundo lo desharía).
        for (int i = 0; i < 2; i++) {
            Bitmap shot = capture(src);
            if (shot == null) return false;
            int w = shot.getWidth();
            int h = shot.getHeight();
            int[] px = new int[w * h];
            shot.getPixels(px, 0, w, 0, 0, w, h);
            shot.recycle();
            if (!MapsCard.splitScreen(px, w, h)) {
                if (i > 0) L.i("Maps: ya a pantalla completa");
                return true;
            }
            if (i == 1) break;
            int[] icon = MapsCard.mapsIcon(px, w, h);
            if (icon == null) {
                L.i("Maps: pantalla partida y no veo el icono de Maps en la barra: no toco nada");
                return false;
            }
            float[] p = src.aaToCar(icon[0], icon[1]);
            L.i("Maps: pantalla partida: pulso el icono de Maps para ponerlo a pantalla completa");
            src.tapCar(p[0], p[1]);
            SystemClock.sleep(FULL_ANIM_MS);
        }
        L.i("Maps: sigue la pantalla partida tras pulsar su icono: no toco nada");
        return false;
    }

    /** Copia de la zona de AA del próximo frame (o null si no llega a tiempo). */
    /**
     * Diagnóstico: la última imagen en la que se buscó el icono de la ruta, en la caché del móvil (no va en los logs que se
     * exportan; se sobrescribe cada vez). Sirve para ver por qué no se abre la tarjeta.
     */
    private static void lastShot(Context ctx, Bitmap b) {
        try {
            java.io.File dir = ctx.getExternalCacheDir();
            if (dir == null) return;
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "maps-lector.png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Exception ignored) {
        }
    }

    private static Bitmap capture(AaPassthroughSource src) throws InterruptedException {
        Bitmap[] got = new Bitmap[1];
        CountDownLatch done = new CountDownLatch(1);
        if (!src.captureAa(b -> {
            got[0] = b;
            done.countDown();
        })) return null;
        if (!done.await(CAPTURE_MS, TimeUnit.MILLISECONDS)) {
            L.w("Maps: no llega la copia de la imagen");
            return null;
        }
        return got[0];
    }

    /** Las líneas de texto de la imagen con su recuadro. */
    private static List<MapsCard.Line> ocr(TextRecognizer rec, Bitmap b) throws Exception {
        Text t = Tasks.await(rec.process(InputImage.fromBitmap(b, 0)), OCR_MS, TimeUnit.MILLISECONDS);
        List<MapsCard.Line> out = new ArrayList<>();
        for (Text.TextBlock bl : t.getTextBlocks()) {
            for (Text.Line ln : bl.getLines()) {
                Rect r = ln.getBoundingBox();
                if (r == null) continue;
                out.add(new MapsCard.Line(ln.getText(), r.left, r.top, r.right, r.bottom));
            }
        }
        return out;
    }
}
