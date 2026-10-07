/*
 * HeadQLink: datos reales del coche (cuenta Leapmotor), solo lectura.
 *
 * Portado de LMB10 (lib/leapmotor_engine.dart), de txurtxil: https://github.com/txurtxil/LPB10
 * LMB10 se publica con la licencia GPL-3.0. HeadQLink (AGPL-3.0) incorpora este código según la sección 13 de ambas
 * licencias; el fichero sigue disponible con el resto del código fuente de HeadQLink.
 *
 * Solo la parte necesaria para leer: la contraseña del certificado de cuenta (SM4 con las tablas fijas del
 * protocolo), la clave de firma (HKDF-SHA256), la firma de las peticiones (HMAC-SHA256 y SHA-256 en el login), el
 * deviceId del token y la codificación de los formularios. Nada del cifrado del PIN ni de los comandos remotos.
 */
package com.headqlink.link;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Criptografía del protocolo de la app oficial de Leapmotor (la parte de solo lectura). Sin estado. */
final class LeapCrypto {
    // Constantes del protocolo (las de LMB10, que las toma de leapmotor_api/const.py).
    static final String APP_VERSION = "1.12.3";
    static final String CHANNEL = "1";
    static final String DEVICE_TYPE = "1";
    static final String LANGUAGE = "en-GB";
    static final String SOURCE = "leapmotor";
    static final String P12_ENC_ALG = "1";
    static final String POLICY_ID = "20260204";

    private LeapCrypto() {
    }

    // ------------------------------------------------------------------ SM4 con claves de ronda fijas

    private static final int[] SBOX = {
            0xD6, 0x90, 0xE9, 0xFE, 0xCC, 0xE1, 0x3D, 0xB7, 0x16, 0xB6, 0x14, 0xC2, 0x28, 0xFB, 0x2C, 0x05,
            0x2B, 0x67, 0x9A, 0x76, 0x2A, 0xBE, 0x04, 0xC3, 0xAA, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99,
            0x9C, 0x42, 0x50, 0xF4, 0x91, 0xEF, 0x98, 0x7A, 0x33, 0x54, 0x0B, 0x43, 0xED, 0xCF, 0xAC, 0x62,
            0xE4, 0xB3, 0x1C, 0xA9, 0xC9, 0x08, 0xE8, 0x95, 0x80, 0xDF, 0x94, 0xFA, 0x75, 0x8F, 0x3F, 0xA6,
            0x47, 0x07, 0xA7, 0xFC, 0xF3, 0x73, 0x17, 0xBA, 0x83, 0x59, 0x3C, 0x19, 0xE6, 0x85, 0x4F, 0xA8,
            0x68, 0x6B, 0x81, 0xB2, 0x71, 0x64, 0xDA, 0x8B, 0xF8, 0xEB, 0x0F, 0x4B, 0x70, 0x56, 0x9D, 0x35,
            0x1E, 0x24, 0x0E, 0x5E, 0x63, 0x58, 0xD1, 0xA2, 0x25, 0x22, 0x7C, 0x3B, 0x01, 0x21, 0x78, 0x87,
            0xD4, 0x00, 0x46, 0x57, 0x9F, 0xD3, 0x27, 0x52, 0x4C, 0x36, 0x02, 0xE7, 0xA0, 0xC4, 0xC8, 0x9E,
            0xEA, 0xBF, 0x8A, 0xD2, 0x40, 0xC7, 0x38, 0xB5, 0xA3, 0xF7, 0xF2, 0xCE, 0xF9, 0x61, 0x15, 0xA1,
            0xE0, 0xAE, 0x5D, 0xA4, 0x9B, 0x34, 0x1A, 0x55, 0xAD, 0x93, 0x32, 0x30, 0xF5, 0x8C, 0xB1, 0xE3,
            0x1D, 0xF6, 0xE2, 0x2E, 0x82, 0x66, 0xCA, 0x60, 0xC0, 0x29, 0x23, 0xAB, 0x0D, 0x53, 0x4E, 0x6F,
            0xD5, 0xDB, 0x37, 0x45, 0xDE, 0xFD, 0x8E, 0x2F, 0x03, 0xFF, 0x6A, 0x72, 0x6D, 0x6C, 0x5B, 0x51,
            0x8D, 0x1B, 0xAF, 0x92, 0xBB, 0xDD, 0xBC, 0x7F, 0x11, 0xD9, 0x5C, 0x41, 0x1F, 0x10, 0x5A, 0xD8,
            0x0A, 0xC1, 0x31, 0x88, 0xA5, 0xCD, 0x7B, 0xBD, 0x2D, 0x74, 0xD0, 0x12, 0xB8, 0xE5, 0xB4, 0xB0,
            0x89, 0x69, 0x97, 0x4A, 0x0C, 0x96, 0x77, 0x7E, 0x65, 0xB9, 0xF1, 0x09, 0xC5, 0x6E, 0xC6, 0x84,
            0x18, 0xF0, 0x7D, 0xEC, 0x3A, 0xDC, 0x4D, 0x20, 0x79, 0xEE, 0x5F, 0x3E, 0xD7, 0xCB, 0x39, 0x48,
    };

    /** Claves de ronda fijas del protocolo para la contraseña del PKCS#12 de cuenta (leapmotor_api/crypto.py). */
    static final int[] P12_ROUND_KEYS = {
            0x818FA553, 0xEBA3318D, 0x5FC3C93A, 0xBD1DADD9,
            0xBB61CAB9, 0x000FD7EA, 0xDC6E0166, 0xDA937279,
            0x607EE786, 0xB548754C, 0x107330E4, 0xEA17C186,
            0x0F56F74B, 0xB21E443C, 0xE1210FE2, 0x009995C8,
            0xE7529A48, 0x6EF474F6, 0x2AB06DF6, 0x43B11BE8,
            0x359D4A14, 0xC29E2CDE, 0x30CF6A3E, 0x79D1C806,
            0x7C502387, 0xAAAB9BC6, 0xF0FE744B, 0x1CAFC872,
            0x95A9D075, 0x88070D58, 0x22800475, 0x8391938B,
    };

    /** Sustitución por la caja S de los cuatro bytes de t. */
    static int tau(int t) {
        return (SBOX[(t >>> 24) & 0xFF] << 24) | (SBOX[(t >>> 16) & 0xFF] << 16) | (SBOX[(t >>> 8) & 0xFF] << 8)
                | SBOX[t & 0xFF];
    }

    /** Cifra un bloque de 16 bytes con las 32 claves de ronda dadas (SM4: rondas y salida en orden inverso). */
    static byte[] sm4Block(byte[] in, int off, int[] roundKeys) {
        int x0 = u32(in, off);
        int x1 = u32(in, off + 4);
        int x2 = u32(in, off + 8);
        int x3 = u32(in, off + 12);
        for (int rk : roundKeys) {
            int b = tau(x1 ^ x2 ^ x3 ^ rk);
            int nx = x0 ^ b ^ Integer.rotateLeft(b, 2) ^ Integer.rotateLeft(b, 10) ^ Integer.rotateLeft(b, 18)
                    ^ Integer.rotateLeft(b, 24);
            x0 = x1;
            x1 = x2;
            x2 = x3;
            x3 = nx;
        }
        byte[] out = new byte[16];
        put32(out, 0, x3);
        put32(out, 4, x2);
        put32(out, 8, x1);
        put32(out, 12, x0);
        return out;
    }

    private static int u32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static void put32(byte[] b, int o, int v) {
        b[o] = (byte) (v >>> 24);
        b[o + 1] = (byte) (v >>> 16);
        b[o + 2] = (byte) (v >>> 8);
        b[o + 3] = (byte) v;
    }

    /** «p12MemoryEncode»: relleno PKCS#7 a 16 y SM4 en modo ECB con las claves fijas. */
    static byte[] p12MemoryEncode(byte[] data) {
        int pad = 16 - (data.length % 16);
        byte[] padded = new byte[data.length + pad];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = data.length; i < padded.length; i++) padded[i] = (byte) pad;
        byte[] out = new byte[padded.length];
        for (int off = 0; off < padded.length; off += 16) {
            System.arraycopy(sm4Block(padded, off, P12_ROUND_KEYS), 0, out, off, 16);
        }
        return out;
    }

    private static String everyNth(String s, int step, int start) {
        StringBuilder b = new StringBuilder();
        for (int i = start; i < s.length(); i += step) b.append(s.charAt(i));
        return b.toString();
    }

    /**
     * Contraseña del PKCS#12 de cuenta que llega en el login (base64Cert): MD5 del id de cuenta, sus caracteres pares,
     * los impares del uid, SHA-256, SM4 y los 15 primeros caracteres del Base64 de los 12 primeros bytes.
     */
    static String accountP12Password(String accountId, String uid) {
        String cn = hex(digest("MD5", accountId.getBytes(StandardCharsets.US_ASCII)));
        String input = cn + everyNth(cn, 2, 0) + everyNth(uid, 2, 1);
        byte[] d = digest("SHA-256", input.getBytes(StandardCharsets.US_ASCII));
        byte[] enc = p12MemoryEncode(d);
        byte[] first12 = new byte[12];
        System.arraycopy(enc, 0, first12, 0, 12);
        String b64 = Base64.encodeToString(first12, Base64.NO_WRAP);
        return b64.substring(0, Math.min(15, b64.length()));
    }

    // ------------------------------------------------------------------ firma

    /**
     * HKDF-SHA256 como el de LMB10: extracción con la sal (32 ceros si viene vacía) y expansión con el bloque anterior
     * acumulado. Para los 32 bytes que se usan (un solo bloque) es exactamente el HKDF del RFC 5869.
     */
    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, ikm);
        byte[] t = new byte[0];
        int block = 1;
        while (t.length < length) {
            byte[] in = new byte[t.length + info.length + 1];
            System.arraycopy(t, 0, in, 0, t.length);
            System.arraycopy(info, 0, in, t.length, info.length);
            in[in.length - 1] = (byte) block;
            byte[] next = hmac(prk, in);
            byte[] grown = new byte[t.length + next.length];
            System.arraycopy(t, 0, grown, 0, t.length);
            System.arraycopy(next, 0, grown, t.length, next.length);
            t = grown;
            block++;
        }
        byte[] out = new byte[length];
        System.arraycopy(t, 0, out, 0, length);
        return out;
    }

    /** Clave de firma de la sesión: HKDF de signIkm, signSalt y signInfo (texto UTF-8), 32 bytes. */
    static byte[] signKey(String ikm, String salt, String info) {
        return hkdfSha256(ikm.getBytes(StandardCharsets.UTF_8), salt.getBytes(StandardCharsets.UTF_8),
                info.getBytes(StandardCharsets.UTF_8), 32);
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Los valores de los campos firmados, ordenados por el nombre del campo (como el sort de Dart) y concatenados. */
    static String signInput(Map<String, String> fields) {
        List<String> keys = new ArrayList<>(fields.keySet());
        Collections.sort(keys);
        StringBuilder b = new StringBuilder();
        for (String k : keys) b.append(fields.get(k));
        return b.toString();
    }

    /** Cabecera «sign» de una petición autenticada: HMAC-SHA256 (hex) de signInput con la clave de la sesión. */
    static String sign(byte[] signKey, Map<String, String> fields) {
        return hex(hmac(signKey, signInput(fields).getBytes(StandardCharsets.UTF_8)));
    }

    /** «sign» del login: SHA-256 (hex) de los campos del formulario en el orden fijo del protocolo. */
    static String loginSign(String deviceId, String email, String nonce, String password, String timestamp) {
        String s = LANGUAGE + DEVICE_TYPE + deviceId + "1" + email + "0" + "1" + nonce + password + POLICY_ID + SOURCE
                + timestamp + APP_VERSION;
        return hex(digest("SHA-256", s.getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------------ identificadores

    /** deviceId de la sesión: el tercer campo de user_name en el token (JWT) o, si no lo hay, el de reserva. */
    static String sessionDeviceId(String token, String fallback) {
        if (token == null || token.isEmpty()) return fallback;
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) return fallback;
            byte[] json = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
            org.json.JSONObject payload = new org.json.JSONObject(new String(json, StandardCharsets.UTF_8));
            Object un = payload.opt("user_name");
            String userName = un == null || un == org.json.JSONObject.NULL ? "" : String.valueOf(un);
            String[] seg = userName.split(",", -1);
            if (seg.length >= 4 && !seg[2].isEmpty()) return seg[2];
        } catch (Exception ignored) {
            // Token raro: se queda el de reserva, como en LMB10.
        }
        return fallback;
    }

    /** deviceId nuevo: 16 bytes aleatorios en hexadecimal. */
    static String newDeviceId(SecureRandom rnd) {
        byte[] b = new byte[16];
        rnd.nextBytes(b);
        return hex(b);
    }

    /** nonce del protocolo: un entero entre 100000 y 9999999. */
    static String nonce(Random rnd) {
        return String.valueOf(rnd.nextInt(9900000) + 100000);
    }

    // ------------------------------------------------------------------ utilidades

    /** Uri.encodeComponent de Dart: todo en UTF-8 y en %XX salvo A-Z a-z 0-9 - _ . ! ~ * ' ( ). */
    static String encodeComponent(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(HEX_UP[c >> 4]).append(HEX_UP[c & 15]);
            }
        }
        return b.toString();
    }

    private static final char[] HEX_LO = "0123456789abcdef".toCharArray();
    private static final char[] HEX_UP = "0123456789ABCDEF".toCharArray();

    /** Clave e IV por defecto del cifrado del PIN (si el token es corto), de leapmotor-api (markoceri, AGPL-3.0). */
    static final String OPERPWD_KEY = "f1cf0c025baec0e2";
    static final String OPERPWD_IV = "6b6a1fe94e133fd7";

    /**
     * El PIN del coche como lo piden operPwd/verify y remote/ctl: AES-128-CBC (PKCS#7) con clave e IV de 16 caracteres
     * sacados del token (el MD5 en hexadecimal de sus 32 primeros y de los 32 siguientes, del carácter 8 al 24), en
     * Base64.
     */
    static String operatePassword(String pin, String token) throws java.security.GeneralSecurityException {
        String key = OPERPWD_KEY;
        String iv = OPERPWD_IV;
        if (token != null && token.length() >= 64) {
            key = hex(digest("MD5", token.substring(0, 32).getBytes(StandardCharsets.UTF_8))).substring(8, 24);
            iv = hex(digest("MD5", token.substring(32, 64).getBytes(StandardCharsets.UTF_8))).substring(8, 24);
        }
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new javax.crypto.spec.IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        return Base64.encodeToString(c.doFinal(pin.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[2 * i] = HEX_LO[(b[i] >> 4) & 15];
            out[2 * i + 1] = HEX_LO[b[i] & 15];
        }
        return new String(out);
    }

    static byte[] digest(String alg, byte[] data) {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
