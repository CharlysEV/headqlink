package com.c10link.link;

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
 * heartbeats, resumen del vídeo), inicio/fin de sesión y los avisos y errores de C10Link.
 */
final class CarTrace {
    private static final SimpleDateFormat TS = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static final int MAX_HEX = 512;

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
    }

    /** Abre el diario (al arrancar el servicio). */
    static synchronized void open() {
        if (out != null || dir == null) return;
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
        try {
            out.close();
        } catch (IOException ignored) {
        }
        out = null;
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

    /** Avisos y errores de C10Link (llamado desde L). */
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
            StringBuilder sb = new StringBuilder();
            for (Proto.Finger f : Proto.parseTouch(payload, off, len)) {
                if (f != null) sb.append(String.format(Locale.US, "[id%d a%d %.1f,%.1f] ", f.id, f.action, f.x, f.y));
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
        try {
            out.write(TS.format(new Date()));
            out.write(' ');
            out.write(tag);
            out.write(" | ");
            out.write(msg);
            out.write('\n');
            out.flush();
        } catch (IOException ignored) {
        }
    }
}
