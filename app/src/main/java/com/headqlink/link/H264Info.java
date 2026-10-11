package com.headqlink.link;

/**
 * Lo que dice el SPS/PPS de un vídeo H.264 (Annex-B): perfil, nivel y si va con CABAC. Para el registro: así se ve qué
 * le llega de verdad al coche (el códec puede no hacer caso del perfil pedido). Puro: se prueba en el PC.
 */
final class H264Info {
    private H264Info() {
    }

    /** «perfil 66 (Baseline) · nivel 4.2 · CAVLC», o "" si no hay SPS. */
    static String describe(byte[] annexB) {
        if (annexB == null) return "";
        int profile = -1;
        int level = -1;
        int entropy = -1;
        int i = nextStart(annexB, 0);
        while (i >= 0 && i < annexB.length) {
            int end = nextStart(annexB, i);
            int nalEnd = end < 0 ? annexB.length : startCodeBegin(annexB, end);
            int type = annexB[i] & 0x1F;
            if (type == 7 && i + 3 < nalEnd) {
                profile = annexB[i + 1] & 0xFF;
                level = annexB[i + 3] & 0xFF;
            } else if (type == 8 && entropy < 0) {
                entropy = ppsEntropyFlag(annexB, i + 1, nalEnd);
            }
            i = end;
        }
        if (profile < 0) return "";
        StringBuilder b = new StringBuilder("perfil ").append(profile).append(" (").append(profileName(profile)).append(")");
        b.append(" · nivel ").append(level / 10).append('.').append(level % 10);
        if (entropy >= 0) b.append(entropy == 1 ? " · CABAC" : " · CAVLC");
        return b.toString();
    }

    static String profileName(int idc) {
        switch (idc) {
            case 66:
                return "Baseline";
            case 77:
                return "Main";
            case 88:
                return "Extended";
            case 100:
                return "High";
            default:
                return "otro";
        }
    }

    /** Índice del primer byte tras el siguiente código de inicio (00 00 01) desde from, o -1. */
    private static int nextStart(byte[] d, int from) {
        for (int k = Math.max(0, from); k + 2 < d.length; k++) {
            if (d[k] == 0 && d[k + 1] == 0 && d[k + 2] == 1) return k + 3;
        }
        return -1;
    }

    /** Dónde empieza el código de inicio (con su cero de delante, si lo hay) que termina en afterStart. */
    private static int startCodeBegin(byte[] d, int afterStart) {
        int k = afterStart - 3;
        if (k > 0 && d[k - 1] == 0) k--;
        return k;
    }

    /** entropy_coding_mode_flag del PPS: tras pic_parameter_set_id y seq_parameter_set_id (ue(v)). -1 si no se puede leer. */
    private static int ppsEntropyFlag(byte[] d, int from, int to) {
        // Quita los bytes de prevención (00 00 03 → 00 00).
        byte[] rbsp = new byte[Math.max(0, to - from)];
        int n = 0;
        int zeros = 0;
        for (int k = from; k < to; k++) {
            int v = d[k] & 0xFF;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            zeros = v == 0 ? zeros + 1 : 0;
            rbsp[n++] = (byte) v;
        }
        int[] pos = {0};
        if (readUe(rbsp, n, pos) < 0 || readUe(rbsp, n, pos) < 0) return -1;
        return readBit(rbsp, n, pos);
    }

    private static int readBit(byte[] d, int n, int[] pos) {
        int byteIdx = pos[0] >> 3;
        if (byteIdx >= n) return -1;
        int bit = (d[byteIdx] >> (7 - (pos[0] & 7))) & 1;
        pos[0]++;
        return bit;
    }

    private static int readUe(byte[] d, int n, int[] pos) {
        int zeros = 0;
        while (true) {
            int b = readBit(d, n, pos);
            if (b < 0 || zeros > 31) return -1;
            if (b == 1) break;
            zeros++;
        }
        int v = 0;
        for (int k = 0; k < zeros; k++) {
            int b = readBit(d, n, pos);
            if (b < 0) return -1;
            v = (v << 1) | b;
        }
        return (1 << zeros) - 1 + v;
    }
}
