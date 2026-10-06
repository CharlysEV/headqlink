package com.headqlink.link;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS mutuo con la nube de Leapmotor: el certificado de cliente que importa el usuario (el mismo que pide LMB10: un
 * par PEM .crt + .key, un PEM con los dos bloques o un .p12/.pfx con su contraseña), el PKCS#12 de cuenta que llega en
 * el login y la confianza en el servidor. El esquema (certificado del usuario para el login y PKCS#12 de cuenta para
 * lo demás) es el de LMB10 (https://github.com/txurtxil/LPB10, GPL-3.0); este código es propio.
 *
 * El servidor (appgateway.leapmotor-international.de) presenta un certificado de la CA privada de Leapmotor
 * («AppSubCA»), que ningún móvil reconoce: LMB10 acepta cualquier certificado. Aquí no: se fija la clave pública del
 * servidor (SPKI SHA-256). Si Leapmotor la cambia, la conexión se para y el móvil enseña la huella nueva para que el
 * usuario decida si confía en ella (Ajustes › Datos del coche); nunca se acepta sola.
 */
final class LeapTls {
    /** Clave pública de appgateway.leapmotor-international.de (certificado de 2026-07 a 2027-08), SPKI SHA-256. */
    static final String[] BUILT_IN_PINS = {"gVb+SZ2GBCAhSDc1IA2Ra6jc+O4UrAEZWUmZG6FnQvU="};

    /** Tope de un fichero importado: un certificado con su clave ocupa unos pocos KB. */
    static final int MAX_FILE_BYTES = 256 * 1024;

    private LeapTls() {
    }

    // ------------------------------------------------------------------ identidad (certificado + clave)

    /** Certificado de cliente con su clave privada. El primero de la cadena es el que corresponde a la clave. */
    static final class Identity {
        final PrivateKey key;
        final X509Certificate[] chain;

        Identity(PrivateKey key, X509Certificate[] chain) {
            this.key = key;
            this.chain = chain;
        }

        X509Certificate leaf() {
            return chain[0];
        }

        /** El CN del certificado (para enseñarlo; nada de la clave). */
        String commonName() {
            return LeapTls.commonName(leaf().getSubjectX500Principal());
        }

        /** Fecha de caducidad del certificado, «dd/MM/yyyy». */
        String notAfter() {
            return new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(leaf().getNotAfter());
        }

        /** Para guardarlo cifrado: la clave en PKCS#8 y la cadena en DER, en Base64. */
        org.json.JSONObject toJson() throws GeneralSecurityException {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("alg", key.getAlgorithm());
                o.put("key", Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
                org.json.JSONArray a = new org.json.JSONArray();
                for (X509Certificate c : chain) a.put(Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP));
                o.put("chain", a);
                return o;
            } catch (org.json.JSONException e) {
                throw new GeneralSecurityException("identidad: " + e.getClass().getSimpleName());
            }
        }

        static Identity fromJson(org.json.JSONObject o) throws GeneralSecurityException {
            try {
                PrivateKey k = KeyFactory.getInstance(o.getString("alg"))
                        .generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(o.getString("key"), Base64.NO_WRAP)));
                org.json.JSONArray a = o.getJSONArray("chain");
                X509Certificate[] chain = new X509Certificate[a.length()];
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                for (int i = 0; i < a.length(); i++) {
                    chain[i] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(Base64.decode(a.getString(i), Base64.NO_WRAP)));
                }
                if (chain.length == 0) throw new GeneralSecurityException("identidad sin certificado");
                return new Identity(k, chain);
            } catch (org.json.JSONException e) {
                throw new GeneralSecurityException("identidad: " + e.getClass().getSimpleName());
            }
        }
    }

    static String commonName(Principal p) {
        if (p == null) return "";
        for (String part : p.getName().split(",")) {
            String t = part.trim();
            if (t.regionMatches(true, 0, "CN=", 0, 3)) return t.substring(3);
        }
        return "";
    }

    /** Por qué no se pudo importar el certificado. */
    static final class ImportException extends Exception {
        enum Kind { NEEDS_PASSWORD, WRONG_PASSWORD, NO_KEY, NO_CERT, MISMATCH, ENCRYPTED_KEY_UNSUPPORTED, UNREADABLE, TOO_BIG }

        final Kind kind;

        ImportException(Kind kind) {
            super(kind.name());
            this.kind = kind;
        }
    }

    /** Un fichero elegido por el usuario. */
    static final class Picked {
        final String name;
        final byte[] bytes;

        Picked(String name, byte[] bytes) {
            this.name = name == null ? "" : name;
            this.bytes = bytes;
        }
    }

    /**
     * Lee el certificado de cliente de los ficheros elegidos: uno o varios PEM (CERTIFICATE, PRIVATE KEY, RSA PRIVATE
     * KEY, EC PRIVATE KEY o ENCRYPTED PRIVATE KEY), certificados en DER, claves PKCS#8 en DER o un PKCS#12 (.p12/.pfx).
     * password: la del PKCS#12 o la de la clave cifrada (null o vacía si no tiene).
     */
    static Identity parse(List<Picked> files, char[] password) throws ImportException {
        List<X509Certificate> certs = new ArrayList<>();
        List<PrivateKey> keys = new ArrayList<>();
        Identity fromP12 = null;
        boolean encryptedKey = false;
        for (Picked f : files) {
            if (f.bytes.length > MAX_FILE_BYTES) throw new ImportException(ImportException.Kind.TOO_BIG);
            String text = pemText(f.bytes);
            if (text != null) {
                for (Pem block : pemBlocks(text)) {
                    switch (block.type) {
                        case "CERTIFICATE":
                        case "X509 CERTIFICATE":
                        case "TRUSTED CERTIFICATE":
                            certs.add(cert(block.der));
                            break;
                        case "PRIVATE KEY":
                            keys.add(pkcs8(block.der));
                            break;
                        case "RSA PRIVATE KEY":
                            if (block.encryptedLegacy) throw new ImportException(ImportException.Kind.ENCRYPTED_KEY_UNSUPPORTED);
                            keys.add(pkcs8(rsaPkcs1ToPkcs8(block.der)));
                            break;
                        case "EC PRIVATE KEY":
                            if (block.encryptedLegacy) throw new ImportException(ImportException.Kind.ENCRYPTED_KEY_UNSUPPORTED);
                            keys.add(pkcs8(sec1ToPkcs8(block.der)));
                            break;
                        case "ENCRYPTED PRIVATE KEY":
                            encryptedKey = true;
                            keys.add(encryptedPkcs8(block.der, password));
                            break;
                        default:
                            // EC PARAMETERS, CERTIFICATE REQUEST…: no hacen falta.
                    }
                }
                continue;
            }
            // Binario: certificado DER, PKCS#12 o clave PKCS#8 en DER.
            X509Certificate c = tryCert(f.bytes);
            if (c != null) {
                certs.add(c);
                continue;
            }
            PrivateKey k = tryPkcs8(f.bytes);
            if (k != null) {
                keys.add(k);
                continue;
            }
            fromP12 = pkcs12(f.bytes, password);
        }
        if (fromP12 != null && keys.isEmpty()) return fromP12;
        if (keys.isEmpty()) throw new ImportException(encryptedKey ? ImportException.Kind.NEEDS_PASSWORD : ImportException.Kind.NO_KEY);
        if (certs.isEmpty()) throw new ImportException(ImportException.Kind.NO_CERT);
        for (PrivateKey k : keys) {
            for (X509Certificate c : certs) {
                if (matches(k, c.getPublicKey())) {
                    List<X509Certificate> chain = new ArrayList<>();
                    chain.add(c);
                    for (X509Certificate o : certs) if (o != c && !chain.contains(o)) chain.add(o);
                    return new Identity(k, chain.toArray(new X509Certificate[0]));
                }
            }
        }
        throw new ImportException(ImportException.Kind.MISMATCH);
    }

    /** El texto del fichero si es un PEM (con o sin BOM y con texto antes de los bloques), o null si es binario. */
    static String pemText(byte[] b) {
        int n = Math.min(b.length, 4096);
        String head = new String(b, 0, n, StandardCharsets.ISO_8859_1);
        if (!head.contains("-----BEGIN ")) return null;
        return new String(b, StandardCharsets.UTF_8);
    }

    static final class Pem {
        final String type;
        final byte[] der;
        /** «Proc-Type: 4,ENCRYPTED» (clave cifrada al estilo antiguo de OpenSSL). */
        final boolean encryptedLegacy;

        Pem(String type, byte[] der, boolean encryptedLegacy) {
            this.type = type;
            this.der = der;
            this.encryptedLegacy = encryptedLegacy;
        }
    }

    static List<Pem> pemBlocks(String text) throws ImportException {
        List<Pem> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int b = text.indexOf("-----BEGIN ", at);
            if (b < 0) break;
            int te = text.indexOf("-----", b + 11);
            if (te < 0) break;
            String type = text.substring(b + 11, te).trim();
            String endMark = "-----END " + type + "-----";
            int e = text.indexOf(endMark, te + 5);
            if (e < 0) throw new ImportException(ImportException.Kind.UNREADABLE);
            String body = text.substring(te + 5, e);
            boolean legacy = body.contains("Proc-Type:") && body.contains("ENCRYPTED");
            StringBuilder b64 = new StringBuilder();
            for (String line : body.split("\r?\n")) {
                String l = line.trim();
                if (l.isEmpty() || l.contains(":")) continue; // cabeceras (Proc-Type, DEK-Info)
                b64.append(l);
            }
            try {
                out.add(new Pem(type, Base64.decode(b64.toString(), Base64.DEFAULT), legacy));
            } catch (IllegalArgumentException ex) {
                throw new ImportException(ImportException.Kind.UNREADABLE);
            }
            at = e + endMark.length();
        }
        return out;
    }

    private static X509Certificate cert(byte[] der) throws ImportException {
        X509Certificate c = tryCert(der);
        if (c == null) throw new ImportException(ImportException.Kind.UNREADABLE);
        return c;
    }

    private static X509Certificate tryCert(byte[] der) {
        if (der.length < 2 || der[0] != 0x30) return null;
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | RuntimeException e) {
            return null;
        }
    }

    private static PrivateKey pkcs8(byte[] der) throws ImportException {
        PrivateKey k = tryPkcs8(der);
        if (k == null) throw new ImportException(ImportException.Kind.UNREADABLE);
        return k;
    }

    /** Clave PKCS#8 sin cifrar (RSA o EC, según el OID de su algoritmo), o null. */
    static PrivateKey tryPkcs8(byte[] der) {
        String alg = pkcs8Algorithm(der);
        if (alg == null) return null;
        try {
            return KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | RuntimeException e) {
            return null;
        }
    }

    private static final byte[] OID_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    private static final byte[] OID_EC = {0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x02, 0x01};

    /** «RSA» o «EC» según el AlgorithmIdentifier de un PrivateKeyInfo, o null si no lo es. */
    static String pkcs8Algorithm(byte[] der) {
        try {
            Der top = new Der(der, 0);
            if (top.tag != 0x30) return null;
            Der version = new Der(der, top.start);
            if (version.tag != 0x02) return null;
            Der algId = new Der(der, version.end);
            if (algId.tag != 0x30) return null;
            Der oid = new Der(der, algId.start);
            if (oid.tag != 0x06) return null;
            byte[] o = java.util.Arrays.copyOfRange(der, oid.start, oid.end);
            if (java.util.Arrays.equals(o, OID_RSA)) return "RSA";
            if (java.util.Arrays.equals(o, OID_EC)) return "EC";
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** RSAPrivateKey (PKCS#1) → PrivateKeyInfo (PKCS#8) con rsaEncryption. */
    static byte[] rsaPkcs1ToPkcs8(byte[] pkcs1) {
        byte[] algId = seq(concat(tlv(0x06, OID_RSA), new byte[]{0x05, 0x00}));
        return seq(concat(new byte[]{0x02, 0x01, 0x00}, algId, tlv(0x04, pkcs1)));
    }

    /** ECPrivateKey (SEC1) → PrivateKeyInfo (PKCS#8): la curva sale de sus parámetros [0]. */
    static byte[] sec1ToPkcs8(byte[] sec1) throws ImportException {
        try {
            Der top = new Der(sec1, 0);
            int at = top.start;
            byte[] curve = null;
            while (at < top.end) {
                Der el = new Der(sec1, at);
                if (el.tag == 0xA0) {
                    Der oid = new Der(sec1, el.start);
                    if (oid.tag == 0x06) curve = java.util.Arrays.copyOfRange(sec1, oid.headerStart, oid.end);
                }
                at = el.end;
            }
            if (curve == null) throw new ImportException(ImportException.Kind.UNREADABLE);
            byte[] algId = seq(concat(tlv(0x06, OID_EC), curve));
            return seq(concat(new byte[]{0x02, 0x01, 0x00}, algId, tlv(0x04, sec1)));
        } catch (RuntimeException e) {
            throw new ImportException(ImportException.Kind.UNREADABLE);
        }
    }

    /** PKCS#8 cifrado (ENCRYPTED PRIVATE KEY) con la contraseña dada, si este Android sabe descifrarlo. */
    private static PrivateKey encryptedPkcs8(byte[] der, char[] password) throws ImportException {
        if (password == null || password.length == 0) throw new ImportException(ImportException.Kind.NEEDS_PASSWORD);
        EncryptedPrivateKeyInfo info;
        try {
            info = new EncryptedPrivateKeyInfo(der);
        } catch (IOException e) {
            throw new ImportException(ImportException.Kind.UNREADABLE);
        }
        // El nombre del algoritmo (PBE clásico) o, con PBES2, el de sus parámetros (PBEWithHmacSHA256AndAES_256…).
        java.security.AlgorithmParameters params = info.getAlgParameters();
        String[] names = {info.getAlgName(), params != null ? params.toString() : null};
        for (String alg : names) {
            if (alg == null) continue;
            byte[] plain;
            try {
                Cipher c = Cipher.getInstance(alg);
                c.init(Cipher.DECRYPT_MODE, SecretKeyFactory.getInstance(alg).generateSecret(new PBEKeySpec(password)), params);
                plain = c.doFinal(info.getEncryptedData());
            } catch (javax.crypto.BadPaddingException e) {
                throw new ImportException(ImportException.Kind.WRONG_PASSWORD);
            } catch (GeneralSecurityException | RuntimeException e) {
                continue;
            }
            PrivateKey k = tryPkcs8(plain);
            if (k == null) throw new ImportException(ImportException.Kind.WRONG_PASSWORD);
            return k;
        }
        throw new ImportException(ImportException.Kind.ENCRYPTED_KEY_UNSUPPORTED);
    }

    /** PKCS#12 (.p12/.pfx) del usuario: su clave y su cadena. */
    private static Identity pkcs12(byte[] bytes, char[] password) throws ImportException {
        char[] pw = password == null ? new char[0] : password;
        try {
            return loadPkcs12(bytes, pw);
        } catch (IOException e) {
            // Contraseña mala o que falta (el formato no deja distinguirlo) o un fichero que no es PKCS#12.
            if (!looksLikePkcs12(bytes)) throw new ImportException(ImportException.Kind.UNREADABLE);
            throw new ImportException(pw.length == 0 ? ImportException.Kind.NEEDS_PASSWORD : ImportException.Kind.WRONG_PASSWORD);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new ImportException(ImportException.Kind.UNREADABLE);
        }
    }

    /** Un PFX empieza por SEQUENCE { INTEGER 3, … }. */
    static boolean looksLikePkcs12(byte[] b) {
        try {
            Der top = new Der(b, 0);
            if (top.tag != 0x30) return false;
            Der v = new Der(b, top.start);
            return v.tag == 0x02 && v.end - v.start == 1 && b[v.start] == 3;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Carga un PKCS#12 (el del usuario o el de cuenta del login) y saca la primera clave con su cadena. */
    static Identity loadPkcs12(byte[] bytes, char[] password) throws IOException, GeneralSecurityException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(new ByteArrayInputStream(bytes), password);
        for (Enumeration<String> e = ks.aliases(); e.hasMoreElements(); ) {
            String alias = e.nextElement();
            if (!ks.isKeyEntry(alias)) continue;
            Key k = ks.getKey(alias, password);
            java.security.cert.Certificate[] cc = ks.getCertificateChain(alias);
            if (!(k instanceof PrivateKey) || cc == null || cc.length == 0) continue;
            X509Certificate[] chain = new X509Certificate[cc.length];
            for (int i = 0; i < cc.length; i++) chain[i] = (X509Certificate) cc[i];
            return new Identity((PrivateKey) k, chain);
        }
        throw new GeneralSecurityException("PKCS#12 sin clave privada");
    }

    /** La clave privada corresponde a la pública: firma una prueba con una y la comprueba con la otra. */
    static boolean matches(PrivateKey k, PublicKey pub) {
        if (!k.getAlgorithm().equals(pub.getAlgorithm())) return false;
        try {
            String alg = "EC".equals(k.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature s = Signature.getInstance(alg);
            s.initSign(k);
            s.update(probe);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(pub);
            v.update(probe);
            return v.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ TLS

    /** Gestor de claves que presenta siempre la misma identidad (la del usuario o la de cuenta). */
    static final class FixedKeyManager extends X509ExtendedKeyManager {
        static final String ALIAS = "leapmotor";
        private final Identity id;

        FixedKeyManager(Identity id) {
            this.id = id;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return ALIAS.equals(alias) ? id.chain.clone() : null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return ALIAS.equals(alias) ? id.key : null;
        }
    }

    /** El servidor presenta una clave pública que no está entre las de confianza. */
    static final class UnknownServerKeyException extends CertificateException {
        final String pin;
        final String fingerprint;
        final String subject;
        final long notAfterMs;

        UnknownServerKeyException(String pin, String fingerprint, String subject, long notAfterMs) {
            super("clave del servidor desconocida");
            this.pin = pin;
            this.fingerprint = fingerprint;
            this.subject = subject;
            this.notAfterMs = notAfterMs;
        }
    }

    /** Confía solo en las claves públicas fijadas (las de serie y las que el usuario haya aceptado). */
    static final class PinTrust implements X509TrustManager {
        private final Set<String> pins;

        PinTrust(Collection<String> extraPins) {
            Set<String> s = new LinkedHashSet<>();
            Collections.addAll(s, BUILT_IN_PINS);
            if (extraPins != null) s.addAll(extraPins);
            pins = Collections.unmodifiableSet(s);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("no se aceptan clientes");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("el servidor no presenta certificado");
            X509Certificate leaf = chain[0];
            String pin = spkiPin(leaf.getPublicKey());
            if (pins.contains(pin)) return;
            throw new UnknownServerKeyException(pin, fingerprint(leaf), commonName(leaf.getSubjectX500Principal()),
                    leaf.getNotAfter().getTime());
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** SPKI SHA-256 en Base64 (el formato habitual de los pines). */
    static String spkiPin(PublicKey k) {
        return Base64.encodeToString(LeapCrypto.digest("SHA-256", k.getEncoded()), Base64.NO_WRAP);
    }

    /** Huella SHA-256 del certificado, «AB:66:9B:…». */
    static String fingerprint(X509Certificate c) {
        try {
            byte[] d = LeapCrypto.digest("SHA-256", c.getEncoded());
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) b.append(':');
                b.append(String.format(Locale.ROOT, "%02X", d[i] & 0xFF));
            }
            return b.toString();
        } catch (java.security.cert.CertificateEncodingException e) {
            return "?";
        }
    }

    /** Fábrica de sockets TLS con la identidad dada y la confianza fijada. */
    static SSLSocketFactory socketFactory(Identity id, X509TrustManager trust) throws GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{new FixedKeyManager(id)}, new TrustManager[]{trust}, new SecureRandom());
        return ctx.getSocketFactory();
    }

    // ------------------------------------------------------------------ DER mínimo

    /** Un elemento DER: etiqueta, cabecera y contenido [start, end). */
    static final class Der {
        final int tag;
        final int headerStart;
        final int start;
        final int end;

        Der(byte[] b, int at) {
            headerStart = at;
            tag = b[at] & 0xFF;
            int len = b[at + 1] & 0xFF;
            int p = at + 2;
            if (len >= 0x80) {
                int n = len & 0x7F;
                if (n == 0 || n > 4) throw new IllegalArgumentException("longitud DER");
                len = 0;
                for (int i = 0; i < n; i++) len = (len << 8) | (b[p + i] & 0xFF);
                p += n;
            }
            start = p;
            end = p + len;
            if (len < 0 || end > b.length) throw new IllegalArgumentException("DER truncado");
        }
    }

    static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = content.length;
        if (n < 0x80) {
            o.write(n);
        } else if (n < 0x100) {
            o.write(0x81);
            o.write(n);
        } else if (n < 0x10000) {
            o.write(0x82);
            o.write(n >> 8);
            o.write(n);
        } else {
            o.write(0x83);
            o.write(n >> 16);
            o.write(n >> 8);
            o.write(n);
        }
        o.write(content, 0, n);
        return o.toByteArray();
    }

    static byte[] seq(byte[] content) {
        return tlv(0x30, content);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }
}
