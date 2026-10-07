package com.headqlink.link;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Lo que se guarda de la cuenta de Leapmotor:
 * - cifrado con una clave AES-GCM del Android Keystore (no sale del móvil ni entra en las copias de seguridad), en la
 *   carpeta sin copia de seguridad: el certificado de cliente con su clave y la sesión (tokens, material de firma,
 *   PKCS#12 de cuenta, el VIN y el correo). La contraseña de la cuenta NO se guarda: si la sesión caduca, se vuelve a
 *   entrar a mano.
 * - en claro (SharedPreferences propias): si está activado, el perfil de batería, el modelo del coche, el correo
 *   enmascarado para enseñarlo y las claves del servidor que el usuario haya aceptado. Nada de eso es secreto.
 */
final class CarCloudStore {
    /** Cifra y descifra (Android Keystore en el móvil; en las pruebas, una clave en memoria). */
    interface Box {
        byte[] seal(byte[] plain) throws GeneralSecurityException;

        byte[] open(byte[] sealed) throws GeneralSecurityException;

        /** Borra la clave (Cerrar sesión y borrar datos). */
        void destroy();
    }

    static final String PREFS = "hql_carcloud";
    private static final String DIR = "carcloud";
    private static final String F_IDENTITY = "identity.bin";
    private static final String F_SESSION = "session.bin";
    /** Historial de la nube (viajes con sus kWh y litros, consumo semanal): cifrado como lo demás. */
    private static final String F_HISTORY = "history.bin";
    private static final String K_ENABLED = "enabled";
    private static final String K_PROFILE = "profile";
    private static final String K_CUSTOM_KWH = "custom_kwh";
    private static final String K_CAR_TYPE = "car_type";
    private static final String K_EMAIL_MASKED = "email_masked";
    private static final String K_PINS = "server_pins";

    // Perfiles de batería (capacidad para pasar de % a kWh).
    static final String PROFILE_C10_LIFE = "c10_life";
    static final String PROFILE_C10_PROMAX = "c10_promax";
    static final String PROFILE_CUSTOM = "custom";
    /** Autonomía extendida (generador de gasolina): se elige sola si el coche manda el depósito. */
    static final String PROFILE_C10_REEV = "c10_reev";
    static final double KWH_C10_LIFE = 69.9;
    static final double KWH_C10_PROMAX = 81.9;
    static final double KWH_C10_REEV = 28.4;

    private final File dir;
    private final SharedPreferences sp;
    private final Box box;

    CarCloudStore(Context ctx) {
        this(new File(noBackupDir(ctx), DIR), ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE), new KeystoreBox());
    }

    CarCloudStore(File dir, SharedPreferences sp, Box box) {
        this.dir = dir;
        this.sp = sp;
        this.box = box;
    }

    private static File noBackupDir(Context ctx) {
        return Build.VERSION.SDK_INT >= 21 ? ctx.getNoBackupFilesDir() : ctx.getFilesDir();
    }

    /** La lectura de la nube necesita Android 6 (el Keystore con AES-GCM). */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 23;
    }

    // ------------------------------------------------------------------ ajustes en claro

    boolean enabled() {
        return sp.getBoolean(K_ENABLED, true);
    }

    void setEnabled(boolean on) {
        sp.edit().putBoolean(K_ENABLED, on).apply();
    }

    /** Perfil de batería elegido ("" si aún no). */
    String profile() {
        return sp.getString(K_PROFILE, "");
    }

    double customKwh() {
        return sp.getFloat(K_CUSTOM_KWH, 0f);
    }

    void setProfile(String id, double customKwh) {
        SharedPreferences.Editor e = sp.edit().putString(K_PROFILE, id == null ? "" : id);
        if (PROFILE_CUSTOM.equals(id)) e.putFloat(K_CUSTOM_KWH, (float) clampKwh(customKwh));
        e.apply();
    }

    static double clampKwh(double kwh) {
        return Math.max(10, Math.min(200, kwh));
    }

    /** Capacidad de la batería del perfil (kWh). Sin elegir, la del C10 Life (69,9). */
    double capacityKwh() {
        return capacityFor(profile(), customKwh());
    }

    static double capacityFor(String profile, double customKwh) {
        if (PROFILE_C10_PROMAX.equals(profile)) return KWH_C10_PROMAX;
        if (PROFILE_C10_REEV.equals(profile)) return KWH_C10_REEV;
        if (PROFILE_CUSTOM.equals(profile) && customKwh >= 10) return clampKwh(customKwh);
        return KWH_C10_LIFE;
    }

    /**
     * El coche manda el depósito de gasolina (REEV): si el perfil es de una batería de eléctrico puro (o no hay), se pasa
     * al REEV. Un perfil «a mano» se respeta. true si ha cambiado.
     */
    boolean adoptReev() {
        String p = profile();
        if (PROFILE_C10_REEV.equals(p) || PROFILE_CUSTOM.equals(p)) return false;
        setProfile(PROFILE_C10_REEV, 0);
        return true;
    }

    String carType() {
        return sp.getString(K_CAR_TYPE, "");
    }

    String maskedEmail() {
        return sp.getString(K_EMAIL_MASKED, "");
    }

    Set<String> acceptedPins() {
        return Collections.unmodifiableSet(new HashSet<>(sp.getStringSet(K_PINS, Collections.<String>emptySet())));
    }

    void acceptPin(String pin) {
        Set<String> s = new HashSet<>(sp.getStringSet(K_PINS, Collections.<String>emptySet()));
        s.add(pin);
        sp.edit().putStringSet(K_PINS, s).apply();
    }

    // ------------------------------------------------------------------ cifrado

    boolean hasIdentity() {
        return new File(dir, F_IDENTITY).isFile();
    }

    LeapTls.Identity identity() throws IOException, GeneralSecurityException {
        byte[] b = readSealed(F_IDENTITY);
        if (b == null) return null;
        try {
            return LeapTls.Identity.fromJson(new JSONObject(new String(b, StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            throw new IOException("certificado guardado ilegible");
        }
    }

    void saveIdentity(LeapTls.Identity id) throws IOException, GeneralSecurityException {
        writeSealed(F_IDENTITY, id.toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    void deleteIdentity() {
        wipeFile(F_IDENTITY);
    }

    /** La sesión guardada: la de la nube, el coche elegido y el correo. */
    static final class Saved {
        LeapApi.Session session;
        String vin = "";
        String carType = "";
        String email = "";
    }

    boolean hasSession() {
        return new File(dir, F_SESSION).isFile();
    }

    Saved session() throws IOException, GeneralSecurityException {
        byte[] b = readSealed(F_SESSION);
        if (b == null) return null;
        try {
            JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
            Saved s = new Saved();
            s.session = LeapApi.Session.fromJson(o.getJSONObject("session"));
            s.vin = o.optString("vin");
            s.carType = o.optString("carType");
            s.email = o.optString("email");
            return s;
        } catch (JSONException e) {
            throw new IOException("sesión guardada ilegible");
        }
    }

    void saveSession(Saved s) throws IOException, GeneralSecurityException {
        try {
            JSONObject o = new JSONObject();
            o.put("session", s.session.toJson());
            o.put("vin", s.vin);
            o.put("carType", s.carType);
            o.put("email", s.email);
            writeSealed(F_SESSION, o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("no se pudo guardar la sesión");
        }
        sp.edit().putString(K_CAR_TYPE, s.carType).putString(K_EMAIL_MASKED, maskEmail(s.email)).apply();
    }

    /** El historial guardado de la nube, o null. */
    JSONObject history() {
        try {
            byte[] b = readSealed(F_HISTORY);
            return b == null ? null : new JSONObject(new String(b, StandardCharsets.UTF_8));
        } catch (IOException | GeneralSecurityException | JSONException e) {
            return null;
        }
    }

    void saveHistory(JSONObject o) throws IOException, GeneralSecurityException {
        writeSealed(F_HISTORY, o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Cerrar sesión: fuera la sesión (y el coche y el correo). El certificado se queda. */
    void deleteSession() {
        wipeFile(F_SESSION);
        wipeFile(F_HISTORY);
        sp.edit().remove(K_CAR_TYPE).remove(K_EMAIL_MASKED).apply();
    }

    /** «Cerrar sesión y borrar datos»: todo, incluidos el certificado, los ajustes y la clave del Keystore. */
    void wipeAll() {
        wipeFile(F_IDENTITY);
        wipeFile(F_SESSION);
        wipeFile(F_HISTORY);
        sp.edit().clear().apply();
        box.destroy();
    }

    private byte[] readSealed(String name) throws IOException, GeneralSecurityException {
        File f = new File(dir, name);
        if (!f.isFile()) return null;
        byte[] sealed = readAll(f);
        return box.open(sealed);
    }

    private void writeSealed(String name, byte[] plain) throws IOException, GeneralSecurityException {
        byte[] sealed = box.seal(plain);
        java.util.Arrays.fill(plain, (byte) 0);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("sin carpeta privada");
        File tmp = new File(dir, name + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(sealed);
            o.getFD().sync();
        }
        File f = new File(dir, name);
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("no se pudo guardar " + name);
        }
    }

    private void wipeFile(String name) {
        File f = new File(dir, name);
        if (!f.isFile()) return;
        // Sobrescribir antes de borrar (lo que se pueda: el cifrado es la protección de verdad).
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(new byte[(int) Math.min(f.length(), 1 << 20)]);
        } catch (IOException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        }
    }

    /** «c***@c***.es»: para enseñar con qué cuenta se entró (y en el log), sin el correo entero. */
    static String maskEmail(String email) {
        if (email == null) return "";
        String e = email.trim().toLowerCase(Locale.ROOT);
        int at = e.indexOf('@');
        if (at <= 0) return e.isEmpty() ? "" : e.charAt(0) + "***";
        String domain = e.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String tld = dot > 0 ? domain.substring(dot) : "";
        String host = dot > 0 ? domain.substring(0, dot) : domain;
        return e.charAt(0) + "***@" + (host.isEmpty() ? "" : host.charAt(0) + "***") + tld.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ Android Keystore

    /**
     * AES-256-GCM con una clave del Android Keystore. Sin autenticación del usuario: el sondeo funciona con el móvil
     * bloqueado en el coche. Formato: largo del IV (1 byte), el IV (12) y el texto cifrado con su etiqueta (16).
     */
    static final class KeystoreBox implements Box {
        private static final String ALIAS = "hql_carcloud_v1";
        private static final String KS = "AndroidKeyStore";

        private SecretKey key() throws GeneralSecurityException {
            try {
                KeyStore ks = KeyStore.getInstance(KS);
                ks.load(null);
                KeyStore.Entry e = ks.getEntry(ALIAS, null);
                if (e instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) e).getSecretKey();
                KeyGenerator g = KeyGenerator.getInstance("AES", KS);
                g.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build());
                return g.generateKey();
            } catch (IOException e) {
                throw new GeneralSecurityException("Keystore: " + e.getClass().getSimpleName());
            }
        }

        @Override
        public byte[] seal(byte[] plain) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return out;
        }

        @Override
        public byte[] open(byte[] sealed) throws GeneralSecurityException {
            if (sealed.length < 1 + 12 + 16) throw new GeneralSecurityException("datos cifrados cortos");
            int ivLen = sealed[0] & 0xFF;
            if (ivLen < 12 || ivLen > 16 || sealed.length < 1 + ivLen + 16) throw new GeneralSecurityException("formato");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, sealed, 1, ivLen));
            return c.doFinal(sealed, 1 + ivLen, sealed.length - 1 - ivLen);
        }

        @Override
        public void destroy() {
            try {
                KeyStore ks = KeyStore.getInstance(KS);
                ks.load(null);
                ks.deleteEntry(ALIAS);
            } catch (Exception e) {
                L.w("nube Leapmotor: no se pudo borrar la clave del Keystore: " + e.getClass().getSimpleName());
            }
        }
    }
}
