package com.headqlink.link;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Diario continuo del enlace con el coche, un fichero por arranque del servicio en
 * getExternalFilesDir()/car/: todo lo que envía el coche (UDP, cada paquete TCP con cabecera,
 * JSON completo, binarios en hex, toques), todo lo que le enviamos (ACK, saludo, comandos,
 * heartbeats, resumen del vídeo), inicio/fin de sesión y los avisos y errores de HeadQLink.
 */
final class CarTrace {
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final int MAX_HEX = 512;
    /** Retención (qdauto §7.1): ≤ 30 diarios y ≤ 100 MiB, contando el que se abre. */
    private static final int MAX_FILES = 30;
    private static final long MAX_BYTES = 100L * 1024 * 1024;

    private static File dir;
    private static Writer out;
    private static String lastUdp;
    private static int udpRepeats;

    private CarTrace() {
    }

    static synchronized void init(Context ctx) {
        if (dir != null) return;
        dir = new File(ctx.getExternalFilesDir(null), "car");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        LogRetention.prune(dir, "car-", MAX_FILES - 1, MAX_BYTES);
    }

    /** Abre el diario (al arrancar el servicio). */
    static synchronized void open() {
        if (out != null || dir == null) return;
        // Un diario por arranque del servicio: la retención también aquí, no solo al arrancar el proceso.
        LogRetention.prune(dir, "car-", MAX_FILES - 1, MAX_BYTES);
        String name = "car-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".log";
        try {
            out = new FileWriter(new File(dir, name), true);
            L.i("diario del coche: " + new File(dir, name).getAbsolutePath());
        } catch (IOException e) {
            out = null;
            return;
        }
        line("SERVICIO", "inicio");
    }

    /** Cierra el diario (al parar el servicio). */
    static synchronized void close() {
        if (out == null) return;
        flushUdpRepeats();
        line("SERVICIO", "fin");
        // Se cierra detrás de lo pendiente, para no perder las últimas líneas.
        java.io.Writer w = out;
        LowLatency.log(() -> {
            synchronized (CarTrace.class) {
                try {
                    w.close();
                } catch (IOException ignored) {
                }
                if (out == w) out = null;
            }
        });
    }

    static synchronized void beginSession(String remote) {
        flushUdpRepeats();
        line("SESION", "coche conectado desde " + remote);
    }

    static synchronized void endSession() {
        flushUdpRepeats();
        line("SESION", "fin");
    }

    private static void flushUdpRepeats() {
        if (udpRepeats > 0) line("UDP", "(x" + udpRepeats + " repetido)");
        udpRepeats = 0;
    }

    /** Lo que enviamos al coche (comandos, ACK, saludo, heartbeats). */
    static synchronized void tx(String what, String msg) {
        line("TX " + what, msg);
    }

    /** Eventos y estado (resumen de vídeo, P2P, Android Auto…). */
    static synchronized void note(String what, String msg) {
        line(what, msg);
    }

    /** Avisos y errores de HeadQLink (llamado desde L). */
    static synchronized void problem(String level, String msg) {
        line(level, msg);
    }

    /** Broadcast UDP del coche. Los idénticos seguidos solo se cuentan. */
    static synchronized void udp(String from, String msg) {
        if (msg.equals(lastUdp)) {
            udpRepeats++;
            return;
        }
        if (udpRepeats > 0) line("UDP", "(x" + udpRepeats + " repetido)");
        udpRepeats = 0;
        lastUdp = msg;
        line("UDP " + from, msg);
    }

    /** Paquete de la sesión TCP en formato 5A5A. */
    static synchronized void packet(Proto.Header h, byte[] payload, int off, int len) {
        if (out == null) return;
        String head = "type=" + h.msgType + " fmt=" + h.payloadFormat + " res=" + h.reserved + " ext=" + h.extSize
                + " len=" + len;
        if (h.payloadFormat == Proto.PAYLOAD_JSON) {
            line("RX " + head, new String(payload, off, len, StandardCharsets.UTF_8));
        } else if (h.msgType == Proto.MSG_TOUCH) {
            // Un táctil corto o raro no puede tumbar el hilo lector (antes lanzaba BufferUnderflowException).
            StringBuilder sb = new StringBuilder();
            try {
                for (Proto.Finger f : Proto.parseTouch(payload, off, len)) {
                    if (f != null) sb.append(String.format(Locale.US, "[id%d a%d %.1f,%.1f] ", f.id, f.action, f.x, f.y));
                }
            } catch (RuntimeException ex) {
                sb.append("(táctil ilegible: ").append(ex).append(") ");
            }
            line("RX " + head, sb + "| " + L.hex(payload, off, Math.min(len, 64)));
        } else {
            line("RX " + head, L.hex(payload, off, Math.min(len, MAX_HEX)) + (len > MAX_HEX ? " …" : ""));
        }
    }

    /** Paquete en otro formato (p. ej. el antiguo !BIN de 512 bytes). */
    static synchronized void raw(String what, byte[] data) {
        line("RX " + what, L.hex(data, 0, Math.min(data.length, MAX_HEX)));
    }

    private static void line(String tag, String msg) {
        if (out == null) return;
        String ts = TS.format(new Date());
        // Con "Optimizaciones de latencia", la escritura sale del hilo que lee del coche (LowLatency).
        LowLatency.log(() -> {
            synchronized (CarTrace.class) {
                if (out == null) return;
                try {
                    out.write(ts);
                    out.write(' ');
                    out.write(tag);
                    out.write(" | ");
                    out.write(msg);
                    out.write('\n');
                    if (!LowLatency.enabled) out.flush();
                } catch (IOException ignored) {
                }
            }
        });
    }

    /** Vuelca a disco lo escrito (hilo escritor de LowLatency). */
    static synchronized void flush() {
        if (out == null) return;
        try {
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
