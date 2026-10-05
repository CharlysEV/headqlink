package com.headqlink.link;

import java.util.Locale;

/**
 * Tamaño de los IDR del encoder propio («último frame», patrón y app). El receptor del C10 se cuelga con mensajes de más
 * de ~512 KiB (2026-10-05: IDR de 525-593 KB → el coche deja de leer y la sesión se corta a los 10 s) y el núcleo
 * descarta los que pasan de su tope (SessionConfig.maxVideoMessageBytes, 480 KiB). Aquí se apunta a TARGET_BYTES por
 * IDR, con margen de sobra:
 *
 * - Con Android 12+ (KEY_VIDEO_QP_I_MIN/MAX), QP mínimo de los I-frames adaptable (empieza en QP_START). Un IDR por
 *   encima del objetivo sube el mínimo lo que haga falta (+6 de QP ≈ la mitad de bytes), desde el QP medio que informe
 *   el encoder si lo hace; uno por encima del tope, al menos OVERSIZE_MIN_STEP. RELAX_AFTER IDR seguidos muy por debajo
 *   (menos de WELL_BELOW del objetivo) lo bajan de uno en uno. Siempre entre QP_FLOOR y QP_CEIL.
 * - Plan B, bajar el bitrate justo antes de pedir el IDR (al DIP_START, 40 %) y reponerlo al salir: en cada IDR pedido
 *   si no hay claves de QP o el encoder no les hace caso (QP medio informado por debajo del mínimo, o
 *   INEFFECTIVE_AFTER IDR seguidos por encima del objetivo sin bajar de tamaño); y en el siguiente IDR pedido tras uno
 *   por encima del tope, tras dos seguidos por encima del objetivo o con el mínimo ya en QP_CEIL. La bajada se ajusta
 *   entre DIP_MIN y DIP_START según salgan los IDR con ella.
 *
 * Sin Android: la prueban los tests. Thread-safe.
 */
final class IdrSizeController {
    /** Objetivo por IDR (payload Annex-B). */
    static final int TARGET_BYTES = 300 * 1024;
    static final int QP_START = 24;
    static final int QP_FLOOR = 18;
    static final int QP_CEIL = 40;
    /** QP máximo de los I-frames (el de H.264: sin tope). */
    static final int QP_MAX = 51;
    static final int OVERSIZE_MIN_STEP = 4;
    static final int MAX_STEP = 10;
    static final double WELL_BELOW = 0.5;
    static final int RELAX_AFTER = 3;
    static final int INEFFECTIVE_AFTER = 3;
    static final double DIP_START = 0.4;
    static final double DIP_MIN = 0.2;

    private final boolean qpKeys;
    private final int capBytes;
    private final int targetBytes;

    private int qpMin = QP_START;
    private boolean qpIgnored;
    private int over;
    private int under;
    private int lastOverBytes;
    private boolean dipOnce;
    private double dip = DIP_START;

    // Estadísticas (log y resumen).
    private int idrs;
    private int overTarget;
    private int overCap;
    private int raises;
    private int relaxes;
    private int dips;
    private int maxBytes;

    /**
     * qpKeys: el encoder se configuró con las claves de QP de los I-frames (Android 12+). capBytes: payload máximo que deja
     * pasar el núcleo (tope del mensaje menos sus 48 B de cabeceras).
     */
    IdrSizeController(boolean qpKeys, int capBytes) {
        this(qpKeys, capBytes, TARGET_BYTES);
    }

    IdrSizeController(boolean qpKeys, int capBytes, int targetBytes) {
        this.qpKeys = qpKeys;
        this.capBytes = capBytes;
        this.targetBytes = Math.min(targetBytes, capBytes);
    }

    /** Lo que pasó con un IDR (para el log y para aplicar el QP nuevo). */
    static final class Step {
        final int bytes;
        /** QP medio del IDR que informó el encoder, o -1. */
        final int reportedQp;
        final int qpBefore;
        final int qpAfter;
        final boolean overTarget;
        final boolean overCap;
        /** Salió con el bitrate bajado (factor), o 0. */
        final double dipped;
        /** El próximo IDR pedido irá con el bitrate bajado a este factor, o 0. */
        final double nextDip;
        final boolean qpKeys;
        /** Cambio de modo (el encoder no hace caso del QP…), o null. */
        final String note;
        /** Objetivo y tope con que se evaluó. */
        final int target;
        final int capLimit;

        Step(int bytes, int reportedQp, int qpBefore, int qpAfter, boolean overTarget, boolean overCap, double dipped,
             double nextDip, boolean qpKeys, String note, int target, int capLimit) {
            this.bytes = bytes;
            this.reportedQp = reportedQp;
            this.qpBefore = qpBefore;
            this.qpAfter = qpAfter;
            this.overTarget = overTarget;
            this.overCap = overCap;
            this.dipped = dipped;
            this.nextDip = nextDip;
            this.qpKeys = qpKeys;
            this.note = note;
            this.target = target;
            this.capLimit = capLimit;
        }

        boolean qpChanged() {
            return qpAfter != qpBefore;
        }

        /** Algo que contar aunque el IDR no se haya pedido: por encima del objetivo, cambio de QP o de modo. */
        boolean notable() {
            return overTarget || qpChanged() || note != null || dipped > 0;
        }

        /** «IDR 312 KB · QP-I mín 30», con lo demás que haya pasado. */
        String line() {
            StringBuilder sb = new StringBuilder("IDR ").append(kb(bytes)).append(" KB");
            if (reportedQp > 0) sb.append(" (QP ").append(reportedQp).append(')');
            if (dipped > 0) sb.append(" con el bitrate al ").append(pct(dipped)).append(" %");
            if (overCap) {
                sb.append(" > tope ").append(kb(capLimit)).append(" KB: lo descarta el núcleo y se pide otro");
            } else if (overTarget) {
                sb.append(" > objetivo ").append(kb(target)).append(" KB");
            }
            if (qpKeys) {
                sb.append(" · QP-I mín ");
                if (qpChanged()) sb.append(qpBefore).append(" → ");
                sb.append(qpAfter);
            } else {
                sb.append(" · sin QP-I (Android < 12)");
            }
            if (nextDip > 0) sb.append(" · próximo IDR pedido con el bitrate al ").append(pct(nextDip)).append(" %");
            if (note != null) sb.append(" · ").append(note);
            return sb.toString();
        }
    }

    /**
     * Un IDR del encoder: bytes (Annex-B), QP medio informado (-1 si no) y si salió con el bitrate bajado (factor, o 0).
     * Devuelve el paso, con el QP-I mínimo que hay que aplicar ahora (qpAfter).
     */
    synchronized Step onIdr(int bytes, int reportedQp, double dipped) {
        idrs++;
        if (bytes > maxBytes) maxBytes = bytes;
        int before = qpMin;
        boolean isOverCap = bytes > capBytes;
        boolean isOverTarget = bytes > targetBytes;
        String note = null;
        if (qpKeys && !qpIgnored && reportedQp > 0 && reportedQp < qpMin - 1) {
            qpIgnored = true;
            note = "el encoder no respeta el QP-I mínimo (QP " + reportedQp + " < " + qpMin + "): desde ahora cada IDR pedido"
                    + " baja el bitrate";
        }
        if (isOverTarget) {
            overTarget++;
            if (isOverCap) overCap++;
            under = 0;
            over++;
            if (qpKeys) {
                int step = stepFor(bytes);
                if (isOverCap) step = Math.max(step, OVERSIZE_MIN_STEP);
                int base = reportedQp > 0 ? Math.max(qpMin, reportedQp) : qpMin;
                qpMin = Math.min(QP_CEIL, base + step);
                if (qpMin > before) raises++;
                if (!qpIgnored && reportedQp <= 0 && over >= INEFFECTIVE_AFTER && lastOverBytes > 0
                        && bytes >= lastOverBytes * 9L / 10) {
                    qpIgnored = true;
                    note = over + " IDR seguidos por encima del objetivo sin bajar de tamaño: el QP-I no basta; desde ahora"
                            + " cada IDR pedido baja el bitrate";
                }
            }
            if (dipped > 0) dip = Math.max(DIP_MIN, round2(dip - 0.1));
            if (isOverCap || over >= 2 || (qpKeys && qpMin >= QP_CEIL)) dipOnce = true;
            lastOverBytes = bytes;
        } else {
            lastOverBytes = 0;
            over = 0;
            if (bytes < targetBytes * WELL_BELOW) {
                if (++under >= RELAX_AFTER) {
                    under = 0;
                    if (qpKeys && qpMin > QP_FLOOR) {
                        qpMin--;
                        relaxes++;
                    }
                    if (dipped > 0) dip = Math.min(DIP_START, round2(dip + 0.05));
                }
            } else {
                under = 0;
            }
        }
        return new Step(bytes, reportedQp, before, qpMin, isOverTarget, isOverCap, dipped, peekDip(), qpKeys, note,
                targetBytes, capBytes);
    }

    /** Antes de pedir un IDR: factor al que bajar el bitrate para él, o 0 (sin bajada). Consume la bajada de una vez. */
    synchronized double takeDip() {
        double d = peekDip();
        dipOnce = false;
        if (d > 0) dips++;
        return d;
    }

    private double peekDip() {
        return dipAlways() || dipOnce ? dip : 0;
    }

    /** Plan B permanente: sin claves de QP o el encoder no les hace caso. */
    synchronized boolean dipAlways() {
        return !qpKeys || qpIgnored;
    }

    synchronized int qpMin() {
        return qpMin;
    }

    synchronized int maxBytes() {
        return maxBytes;
    }

    /** Cómo se controla el tamaño (para el log al arrancar). */
    synchronized String describe() {
        return "tamaño de los IDR: objetivo " + kb(targetBytes) + " KB, tope del núcleo " + kb(capBytes) + " KB · "
                + (qpKeys ? "QP-I mínimo adaptable " + QP_FLOOR + "-" + QP_CEIL + " (empieza en " + qpMin + ")"
                : "sin claves de QP (Android < 12): cada IDR pedido baja el bitrate al " + pct(dip) + " %");
    }

    synchronized String summary() {
        return "IDR " + idrs + " · máx. " + kb(maxBytes) + " KB · por encima del objetivo " + overTarget + " · del tope "
                + overCap + " · QP-I mín " + (qpKeys ? String.valueOf(qpMin) : "—") + " (subidas " + raises + ", bajadas "
                + relaxes + ") · con bitrate bajado " + dips + (qpIgnored ? " · el encoder no respeta el QP" : "");
    }

    /** Subida de QP para pasar de bytes al objetivo: 6·log2(bytes/objetivo), redondeada hacia arriba, entre 1 y MAX_STEP. */
    int stepFor(int bytes) {
        double ratio = (double) bytes / targetBytes;
        int step = (int) Math.ceil(6 * Math.log(ratio) / Math.log(2) - 1e-9);
        return Math.max(1, Math.min(MAX_STEP, step));
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static int kb(int bytes) {
        return (bytes + 512) / 1024;
    }

    static int pct(double factor) {
        return (int) Math.round(factor * 100);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "IdrSizeController(qp=%b, qpMin=%d, dip=%.2f)", qpKeys, qpMin, dip);
    }
}
