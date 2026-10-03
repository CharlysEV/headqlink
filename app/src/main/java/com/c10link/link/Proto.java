package com.c10link.link;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Framing de QDLink/SSPLink formato "5A5A" (ver docs/SSPLINK_PROTOCOL.md).
 * Todo big-endian.
 */
final class Proto {
    static final int UDP_LISTEN_PORT = 18463;
    static final int UDP_REPLY_PORT = 18464;
    static final String UDP_MAGIC = "QDrive_SSPLink_UDP_MSG";
    static final String UDP_BROADCAST = "Connect_Broadcast";
    static final String UDP_ACK = "Broadcast_ACK";

    static final String FMT_NEW = "5A5A";
    static final String FMT_OLD = "!BIN";

    static final int HEADER_SIZE = 16;
    static final int VIDEO_EXT_SIZE = 32;
    static final int VIDEO_PREFIX = HEADER_SIZE + VIDEO_EXT_SIZE;

    static final int MSG_CMD = 0;
    static final int MSG_VIDEO = 1;
    static final int MSG_TOUCH = 2;
    static final int MSG_SPEECH = 12;
    static final int MSG_APP = 13;
    static final int MSG_CUSTOM = 99;

    static final int PAYLOAD_BINARY = 0;
    static final int PAYLOAD_JSON = 1;
    static final int PAYLOAD_VIDEO = 2;

    private Proto() {
    }

    /** Cabecera común de 16 bytes. */
    static void writeHeader(ByteBuffer bb, int totalSize, int extSize, int msgType, int payloadFormat, int reserved) {
        bb.put((byte) '5').put((byte) 'A').put((byte) '5').put((byte) 'A');
        bb.putInt(totalSize);
        bb.putShort((short) extSize);
        bb.put((byte) msgType);
        bb.put((byte) 0);
        bb.put((byte) 0);
        bb.put((byte) payloadFormat);
        bb.put((byte) reserved);
        bb.put((byte) 0);
    }

    static byte[] jsonPacket(int msgType, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(HEADER_SIZE + body.length);
        writeHeader(bb, HEADER_SIZE + body.length, 0, msgType, PAYLOAD_JSON, 0);
        bb.put(body);
        return bb.array();
    }

    /** Parámetros que van en la cabecera extendida de vídeo. */
    static final class VideoHeader {
        int width;
        int height;
        int angle = 90;
        int orientation = 1;
        int encodingType;
        int frameRate;
        int bitRate;
        int frameInterval;
        int appCapture = 1;
    }

    /**
     * Paquete de vídeo en modo WiFi: 16 + 32 + datos H.264, sin relleno.
     * (El relleno a múltiplo de 512 de QDLink solo se aplica en modo USB.)
     */
    static byte[] videoPacket(VideoHeader h, byte[] data, int off, int len) {
        int total = VIDEO_PREFIX + len;
        byte[] out = new byte[total];
        ByteBuffer bb = ByteBuffer.wrap(out);
        writeHeader(bb, total, VIDEO_EXT_SIZE, MSG_VIDEO, PAYLOAD_VIDEO, 0);
        bb.putShort((short) VIDEO_EXT_SIZE);
        bb.put((byte) 1);
        bb.put((byte) 0);
        bb.putInt(h.width);
        bb.putInt(h.height);
        bb.putShort((short) h.angle);
        bb.put((byte) h.orientation);
        bb.put((byte) h.encodingType);
        bb.putInt(h.frameRate);
        bb.putInt(h.bitRate);
        bb.putInt(h.frameInterval);
        bb.put((byte) h.appCapture);
        System.arraycopy(data, off, out, VIDEO_PREFIX, len);
        return out;
    }

    /**
     * Saludo inicial "AppStatus" en formato antiguo !BIN (512 bytes, message/b.java de QDLink).
     * QDLink lo envía nada más aceptar el TCP; el coche no habla hasta recibirlo.
     */
    static byte[] appStatus(int sdkInt) {
        ByteBuffer bb = ByteBuffer.allocate(512);
        bb.put((byte) '!').put((byte) 'B').put((byte) 'I').put((byte) 'N');
        bb.putInt(0);   // dataType
        bb.putInt(512);
        bb.putInt(512);
        bb.putInt(64);
        bb.putInt(128);
        bb.putInt(128);
        bb.putInt(2);   // action (offset 28)
        for (int i = 0; i < 32; i++) bb.put((byte) (i + 32));
        bb.putInt(0);
        bb.putInt(1);   // cmd (offset 68)
        bb.putInt(1);   // value
        bb.putInt(sdkInt);
        bb.putInt(2);   // integrator_server
        bb.putInt(192, 0); // ret
        return bb.array();
    }

    static String hexN(int v, int digits) {
        String s = Integer.toHexString(v).toUpperCase(Locale.US);
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < digits; i++) sb.append('0');
        return sb.append(s).toString();
    }

    /** Respuesta UDP del móvil al broadcast del coche. */
    static byte[] udpAck(String json) {
        String s = UDP_MAGIC + hexN(45 + json.length(), 4) + hexN(13, 2) + UDP_ACK + hexN(json.length(), 4) + json;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Header parseado de un paquete entrante. */
    static final class Header {
        String magic;
        int totalSize;
        int extSize;
        int msgType;
        int payloadFormat;
        int reserved;

        static Header parse(byte[] b) {
            ByteBuffer bb = ByteBuffer.wrap(b, 0, HEADER_SIZE);
            Header h = new Header();
            h.magic = new String(b, 0, 4, StandardCharsets.US_ASCII);
            bb.position(4);
            h.totalSize = bb.getInt();
            h.extSize = bb.getShort() & 0xffff;
            h.msgType = b[10] & 0xff;
            h.payloadFormat = b[13] & 0xff;
            h.reserved = b[14] & 0xff;
            return h;
        }

        @Override
        public String toString() {
            return magic + " total=" + totalSize + " ext=" + extSize + " type=" + msgType
                    + " fmt=" + payloadFormat + " res=" + reserved;
        }
    }

    /** Un dedo de un evento táctil (msgType 2). action: 1 down, 2 up, 3 move. */
    static final class Finger {
        int id;
        int action;
        float x;
        float y;
    }

    static Finger[] parseTouch(byte[] p, int off, int len) {
        ByteBuffer bb = ByteBuffer.wrap(p, off, len);
        bb.getInt(); // ACTION global
        int count = bb.get() & 0xff;
        Finger[] f = new Finger[count];
        for (int i = 0; i < count && bb.remaining() >= 10; i++) {
            Finger fi = new Finger();
            fi.id = bb.get() & 0xff;
            fi.action = bb.get() & 0xff;
            fi.x = bb.getFloat();
            fi.y = bb.getFloat();
            f[i] = fi;
        }
        return f;
    }
}
