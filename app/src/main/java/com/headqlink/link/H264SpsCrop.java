package com.headqlink.link;

import java.io.ByteArrayOutputStream;

/**
 * Reescribe el recorte (frame_cropping) de los SPS H.264 de una unidad Annex-B para que el
 * decodificador entregue solo una franja centrada del frame, sin recodificar la imagen.
 *
 * Uso: Android Auto emite 1920x1080 con su interfaz en la franja central de 1920x882 (márgenes
 * arriba/abajo); el coche espera 1920x882. Con el recorte, su decodificador entrega 1920x882.
 */
final class H264SpsCrop {
    private H264SpsCrop() {
    }

    /**
     * Devuelve la unidad con todos sus SPS recortados a targetW x targetH (centrado), o null si no
     * contiene SPS o no se ha podido reescribir (en ese caso hay que enviar el original).
     */
    static byte[] rewrite(byte[] au, int off, int len, int targetW, int targetH) {
        return rewrite(au, off, len, targetW, targetH, false);
    }

    /** topAligned: la franja empieza arriba (filas 0..targetH-1) en vez de centrada. */
    static byte[] rewrite(byte[] au, int off, int len, int targetW, int targetH, boolean topAligned) {
        return rewrite(au, off, len, targetW, targetH, topAligned, false);
    }

    /**
     * lowDelay: además fija en la VUI max_num_reorder_frames=0 y max_dec_frame_buffering mínimo, para
     * que el decodificador del coche muestre cada frame nada más decodificarlo en vez de retener varios.
     */
    static byte[] rewrite(byte[] au, int off, int len, int targetW, int targetH, boolean topAligned, boolean lowDelay) {
        int end = off + len;
        int i = findStart(au, off, end);
        if (i < 0) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream(len + 16);
        boolean changed = false;
        out.write(au, off, i - off); // lo que hubiera antes del primer start code
        while (i >= 0) {
            int scLen = (i + 3 < end && au[i + 2] == 1) ? 3 : 4;
            int nalStart = i + scLen;
            int next = findStart(au, nalStart, end);
            int nalEnd = next >= 0 ? next : end;
            out.write(au, i, scLen);
            int type = nalStart < nalEnd ? au[nalStart] & 0x1f : -1;
            byte[] replaced = type == 7 ? rewriteSps(au, nalStart, nalEnd - nalStart, targetW, targetH, topAligned, lowDelay) : null;
            if (replaced != null) {
                out.write(replaced, 0, replaced.length);
                changed = true;
            } else {
                out.write(au, nalStart, nalEnd - nalStart);
            }
            i = next;
        }
        return changed ? out.toByteArray() : null;
    }

    /** Posición del siguiente start code (00 00 01 o 00 00 00 01), o -1. */
    private static int findStart(byte[] b, int from, int end) {
        for (int i = from; i + 2 < end; i++) {
            if (b[i] == 0 && b[i + 1] == 0) {
                if (b[i + 2] == 1) return (i > from && b[i - 1] == 0) ? i - 1 : i;
            }
        }
        return -1;
    }

    /** NAL SPS completo (con su byte de cabecera) → NAL reescrito, o null. */
    /** Resumen del último SPS original visto (perfil, referencias, VUI), para el registro. */
    static volatile String lastInfo = "";

    static byte[] rewriteSps(byte[] b, int off, int len, int targetW, int targetH, boolean topAligned) {
        return rewriteSps(b, off, len, targetW, targetH, topAligned, false);
    }

    static byte[] rewriteSps(byte[] b, int off, int len, int targetW, int targetH, boolean topAligned, boolean lowDelay) {
        byte[] rbsp = unescape(b, off + 1, len - 1);
        try {
            BitReader r = new BitReader(rbsp);
            BitWriter w = new BitWriter();
            int profile = r.u(8);
            w.u(profile, 8);
            w.u(r.u(8), 8); // constraint flags
            int level = r.u(8);
            w.u(level, 8); // level_idc
            w.ue(r.ue()); // seq_parameter_set_id
            int chromaFormat = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                    || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                    || profile == 139 || profile == 134 || profile == 135) {
                chromaFormat = r.ue();
                w.ue(chromaFormat);
                if (chromaFormat == 3) w.u(r.u(1), 1);
                w.ue(r.ue()); // bit_depth_luma_minus8
                w.ue(r.ue()); // bit_depth_chroma_minus8
                w.u(r.u(1), 1); // qpprime_y_zero_transform_bypass_flag
                int scaling = r.u(1);
                if (scaling != 0) return null; // matrices de escalado: no soportado
                w.u(0, 1);
            }
            w.ue(r.ue()); // log2_max_frame_num_minus4
            int pocType = r.ue();
            w.ue(pocType);
            if (pocType == 0) {
                w.ue(r.ue());
            } else if (pocType == 1) {
                w.u(r.u(1), 1);
                w.se(r.se());
                w.se(r.se());
                int n = r.ue();
                w.ue(n);
                for (int k = 0; k < n; k++) w.se(r.se());
            }
            int numRef = r.ue();
            w.ue(numRef); // max_num_ref_frames
            w.u(r.u(1), 1); // gaps_in_frame_num_value_allowed_flag
            int wMbs = r.ue();
            int hMapUnits = r.ue();
            w.ue(wMbs);
            w.ue(hMapUnits);
            int frameMbsOnly = r.u(1);
            w.u(frameMbsOnly, 1);
            if (frameMbsOnly == 0) w.u(r.u(1), 1);
            w.u(r.u(1), 1); // direct_8x8_inference_flag
            int cropFlag = r.u(1);
            int oldL = 0, oldR = 0, oldT = 0, oldB = 0;
            if (cropFlag == 1) {
                oldL = r.ue();
                oldR = r.ue();
                oldT = r.ue();
                oldB = r.ue();
            }
            int codedW = (wMbs + 1) * 16;
            int codedH = (hMapUnits + 1) * 16 * (2 - frameMbsOnly);
            int unitX = chromaFormat == 1 || chromaFormat == 2 ? 2 : 1;
            int unitY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly);
            // Centrado dentro de la imagen visible original (p. ej. 1080 filas de un 1088 codificado).
            int visW = codedW - (oldL + oldR) * unitX;
            int visH = codedH - (oldT + oldB) * unitY;
            if (targetW > visW || targetH > visH) return null;
            int left = (oldL * unitX + (visW - targetW) / 2) / unitX;
            int top = topAligned ? oldT : (oldT * unitY + (visH - targetH) / 2) / unitY;
            int right = (codedW - targetW - left * unitX) / unitX;
            int bottom = (codedH - targetH - top * unitY) / unitY;
            w.u(1, 1);
            w.ue(left);
            w.ue(right);
            w.ue(top);
            w.ue(bottom);
            int stop = lastOneBit(rbsp);
            StringBuilder info = new StringBuilder("perfil " + profile + " nivel " + level + " refs " + numRef);
            if (lowDelay) {
                copyVuiLowDelay(r, w, Math.max(1, numRef), info);
            } else {
                info.append(" (VUI sin tocar)");
            }
            lastInfo = info.toString();
            // Resto tal cual, hasta el rbsp_stop_one_bit; luego nuevo stop bit y alineación.
            while (r.pos() < stop) w.u(r.u(1), 1);
            w.u(1, 1);
            w.align();
            byte[] newRbsp = w.toByteArray();
            ByteArrayOutputStream nal = new ByteArrayOutputStream(newRbsp.length + 8);
            nal.write(b[off]);
            escape(newRbsp, nal);
            return nal.toByteArray();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Copia vui_parameters_present_flag y la VUI, forzando bitstream_restriction con
     * max_num_reorder_frames=0 y max_dec_frame_buffering=decBuf (>= max_num_ref_frames, como exige
     * la norma). Si no había VUI, escribe una mínima con solo esa restricción.
     */
    private static void copyVuiLowDelay(BitReader r, BitWriter w, int decBuf, StringBuilder info) {
        int present = r.u(1);
        w.u(1, 1);
        if (present == 0) {
            w.u(0, 1); // aspect_ratio_info_present_flag
            w.u(0, 1); // overscan_info_present_flag
            w.u(0, 1); // video_signal_type_present_flag
            w.u(0, 1); // chroma_loc_info_present_flag
            w.u(0, 1); // timing_info_present_flag
            w.u(0, 1); // nal_hrd_parameters_present_flag
            w.u(0, 1); // vcl_hrd_parameters_present_flag
            w.u(0, 1); // pic_struct_present_flag
            writeRestriction(w, 1, 2, 1, 16, 16, decBuf);
            info.append(" · sin VUI → añadida con reorder=0 buffering=").append(decBuf);
            return;
        }
        if (copy(r, w, 1) == 1 && copy(r, w, 8) == 255) { // aspect_ratio_info, aspect_ratio_idc
            copy(r, w, 16);
            copy(r, w, 16);
        }
        if (copy(r, w, 1) == 1) copy(r, w, 1); // overscan
        if (copy(r, w, 1) == 1) { // video_signal_type
            copy(r, w, 4); // video_format + video_full_range_flag
            if (copy(r, w, 1) == 1) copy(r, w, 24); // colour_description
        }
        if (copy(r, w, 1) == 1) { // chroma_loc_info
            w.ue(r.ue());
            w.ue(r.ue());
        }
        if (copy(r, w, 1) == 1) { // timing_info
            copy(r, w, 32);
            copy(r, w, 32);
            copy(r, w, 1);
        }
        int nalHrd = copy(r, w, 1);
        if (nalHrd == 1) copyHrd(r, w);
        int vclHrd = copy(r, w, 1);
        if (vclHrd == 1) copyHrd(r, w);
        if (nalHrd == 1 || vclHrd == 1) copy(r, w, 1); // low_delay_hrd_flag
        copy(r, w, 1); // pic_struct_present_flag
        if (r.u(1) == 1) {
            int mv = r.u(1);
            int bytesDenom = r.ue();
            int bitsDenom = r.ue();
            int mvH = r.ue();
            int mvV = r.ue();
            int reorder = r.ue();
            int buffering = r.ue();
            info.append(" · VUI original reorder=").append(reorder).append(" buffering=").append(buffering);
            writeRestriction(w, mv, bytesDenom, bitsDenom, mvH, mvV, decBuf);
        } else {
            info.append(" · VUI sin restricción → añadida reorder=0 buffering=").append(decBuf);
            writeRestriction(w, 1, 2, 1, 16, 16, decBuf);
        }
    }

    private static void writeRestriction(BitWriter w, int mv, int bytesDenom, int bitsDenom, int mvH, int mvV, int decBuf) {
        w.u(1, 1); // bitstream_restriction_flag
        w.u(mv, 1);
        w.ue(bytesDenom);
        w.ue(bitsDenom);
        w.ue(mvH);
        w.ue(mvV);
        w.ue(0); // max_num_reorder_frames
        w.ue(decBuf); // max_dec_frame_buffering
    }

    private static void copyHrd(BitReader r, BitWriter w) {
        int cpbCnt = r.ue();
        w.ue(cpbCnt);
        copy(r, w, 8); // bit_rate_scale + cpb_size_scale
        for (int i = 0; i <= cpbCnt; i++) {
            w.ue(r.ue());
            w.ue(r.ue());
            copy(r, w, 1);
        }
        copy(r, w, 20); // cuatro longitudes de 5 bits
    }

    private static int copy(BitReader r, BitWriter w, int bits) {
        int v = 0;
        for (int i = 0; i < bits; i++) {
            int bit = r.u(1);
            w.u(bit, 1);
            v = (v << 1) | bit;
        }
        return v;
    }

    private static int lastOneBit(byte[] rbsp) {
        for (int i = rbsp.length - 1; i >= 0; i--) {
            int v = rbsp[i] & 0xff;
            if (v != 0) return i * 8 + (7 - Integer.numberOfTrailingZeros(v));
        }
        return rbsp.length * 8;
    }

    private static byte[] unescape(byte[] b, int off, int len) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(len);
        int zeros = 0;
        for (int i = off; i < off + len; i++) {
            int v = b[i] & 0xff;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    private static void escape(byte[] rbsp, ByteArrayOutputStream o) {
        int zeros = 0;
        for (byte x : rbsp) {
            int v = x & 0xff;
            if (zeros >= 2 && v <= 3) {
                o.write(3);
                zeros = 0;
            }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
    }

    private static final class BitReader {
        private final byte[] d;
        private int p;

        BitReader(byte[] d) {
            this.d = d;
        }

        int pos() {
            return p;
        }

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                if (p >= d.length * 8) throw new IllegalStateException("fin del SPS");
                v = (v << 1) | ((d[p >> 3] >> (7 - (p & 7))) & 1);
                p++;
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (u(1) == 0) {
                if (++zeros > 31) throw new IllegalStateException("ue inválido");
            }
            return zeros == 0 ? 0 : (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    private static final class BitWriter {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        private int cur;
        private int n;

        void u(int v, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                cur = (cur << 1) | ((v >> i) & 1);
                if (++n == 8) {
                    o.write(cur);
                    cur = 0;
                    n = 0;
                }
            }
        }

        void ue(int v) {
            int x = v + 1;
            int len = 32 - Integer.numberOfLeadingZeros(x);
            u(0, len - 1);
            u(x, len);
        }

        void se(int v) {
            ue(v > 0 ? 2 * v - 1 : -2 * v);
        }

        void align() {
            while (n != 0) u(0, 1);
        }

        byte[] toByteArray() {
            return o.toByteArray();
        }
    }
}
