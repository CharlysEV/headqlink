package com.headqlink.link;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Los ficheros del certificado de cliente que el usuario va eligiendo en el selector del sistema (CarCloudActivity):
 * el .crt y el .key a la vez o uno detrás de otro (en el selector, tocar un fichero lo devuelve en el acto; elegir
 * dos pide una pulsación larga que casi nadie conoce). Si llega solo uno, se guarda aquí, en memoria, hasta que llegue
 * el otro; un .pem con los dos o un .p12/.pfx vale solo. Puro (sin Android): lo prueba CertPickTest.
 *
 * Nunca se escribe en disco ni en el log el contenido: solo los nombres. Lo que se descarta se sobrescribe con ceros.
 */
final class CertPick {
    /** Lo elegido a medias caduca: pasado este rato, el siguiente fichero empieza de cero. */
    static final long EXPIRE_MS = 15 * 60_000L;

    /** Qué lleva un fichero (sin leerlo del todo). */
    enum Content {
        /** Uno o más certificados X.509 (PEM o DER). */
        CERT,
        /** Una clave privada (PEM de cualquier tipo, también cifrada, o PKCS#8 en DER). */
        KEY,
        /** Un PEM con certificado y clave. */
        BOTH,
        /** Un PKCS#12 (.p12/.pfx) o un fichero demasiado grande: lo decide LeapTls.parse por sí solo. */
        BUNDLE,
        /** Nada que sirva (texto sin bloques PEM útiles, binario que no es certificado, clave ni PKCS#12). */
        UNKNOWN,
    }

    /** Qué hacer después de elegir. */
    static final class Step {
        enum Kind {
            /** Nada elegido. */
            NOTHING,
            /** Ya está todo: files va a LeapTls.parse. */
            READY,
            /** Hay certificado (have) y falta la clave. */
            NEED_KEY,
            /** Hay clave (have) y falta el certificado. */
            NEED_CERT,
            /** Lo elegido no sirve (unknown); lo de antes, si había, se conserva. */
            UNREADABLE,
        }

        final Kind kind;
        /** Con READY: los ficheros para LeapTls.parse (quien los recibe los borra con {@link #wipe} al terminar). */
        final List<LeapTls.Picked> files;
        /** Con NEED_KEY o NEED_CERT: el nombre de lo que ya se tiene. */
        final String have;
        /** Los nombres de los ficheros de esta vez que no sirven (para el log y el aviso). */
        final List<String> unknown;

        Step(Kind kind, List<LeapTls.Picked> files, String have, List<String> unknown) {
            this.kind = kind;
            this.files = files;
            this.have = have;
            this.unknown = unknown;
        }

        @Override
        public String toString() {
            switch (kind) {
                case READY:
                    return "listo (" + files.size() + " fichero(s))";
                case NEED_KEY:
                    return "certificado «" + have + "»; falta la clave";
                case NEED_CERT:
                    return "clave «" + have + "»; falta el certificado";
                case UNREADABLE:
                    return "no sirve: " + unknown;
                default:
                    return "nada";
            }
        }
    }

    private static final class Entry {
        final LeapTls.Picked file;
        final Content content;

        Entry(LeapTls.Picked file, Content content) {
            this.file = file;
            this.content = content;
        }
    }

    private final List<Entry> partial = new ArrayList<>();
    private long since;

    /** Añade lo elegido ahora a lo que ya había y dice qué hacer. */
    synchronized Step add(List<LeapTls.Picked> picked, long nowMs) {
        expire(nowMs);
        List<Entry> fresh = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        boolean cert = false, key = false, complete = false;
        for (LeapTls.Picked f : picked) {
            Content c = classify(f);
            switch (c) {
                case UNKNOWN:
                    unknown.add(f.name);
                    wipe(f);
                    continue;
                case CERT:
                    cert = true;
                    break;
                case KEY:
                    key = true;
                    break;
                default:
                    complete = true;
            }
            fresh.add(new Entry(f, c));
        }
        if (fresh.isEmpty()) {
            return unknown.isEmpty() ? state() : new Step(Step.Kind.UNREADABLE, Collections.emptyList(), "", unknown);
        }
        if (complete || (cert && key)) {
            // Un .pem con los dos, un .p12 o el par entero de una vez: vale solo; lo de antes sobra.
            clear();
            return ready(fresh, unknown);
        }
        // Medio par: sustituye a lo mismo elegido antes (otro .crt en lugar del primero) y se junta con lo otro.
        for (int i = partial.size() - 1; i >= 0; i--) {
            Entry e = partial.get(i);
            if ((cert && e.content == Content.CERT) || (key && e.content == Content.KEY)) {
                wipe(e.file);
                partial.remove(i);
            }
        }
        if (partial.isEmpty()) since = nowMs;
        partial.addAll(fresh);
        Step s = state();
        if (s.kind == Step.Kind.READY) {
            List<Entry> all = new ArrayList<>(partial);
            partial.clear();
            return ready(all, unknown);
        }
        return new Step(s.kind, s.files, s.have, unknown);
    }

    /** Lo que hay elegido a medias ahora (NOTHING, NEED_KEY o NEED_CERT). */
    synchronized Step state(long nowMs) {
        expire(nowMs);
        return state();
    }

    /** «Empezar de nuevo» (o ya importado, o borrado todo): se olvida lo elegido a medias. */
    synchronized void clear() {
        for (Entry e : partial) wipe(e.file);
        partial.clear();
    }

    private Step state() {
        String certName = null, keyName = null;
        for (Entry e : partial) {
            if (e.content == Content.CERT && certName == null) certName = e.file.name;
            if (e.content == Content.KEY && keyName == null) keyName = e.file.name;
        }
        List<String> none = Collections.emptyList();
        if (certName != null && keyName != null) return new Step(Step.Kind.READY, Collections.emptyList(), "", none);
        if (certName != null) return new Step(Step.Kind.NEED_KEY, Collections.emptyList(), certName, none);
        if (keyName != null) return new Step(Step.Kind.NEED_CERT, Collections.emptyList(), keyName, none);
        return new Step(Step.Kind.NOTHING, Collections.emptyList(), "", none);
    }

    private void expire(long nowMs) {
        if (!partial.isEmpty() && nowMs - since > EXPIRE_MS) clear();
    }

    private static Step ready(List<Entry> entries, List<String> unknown) {
        List<LeapTls.Picked> files = new ArrayList<>();
        for (Entry e : entries) files.add(e.file);
        return new Step(Step.Kind.READY, files, "", unknown);
    }

    /** Qué lleva el fichero: por los tipos de sus bloques PEM o, si es binario, por su estructura DER. */
    static Content classify(LeapTls.Picked f) {
        if (f.bytes.length > LeapTls.MAX_FILE_BYTES) return Content.BUNDLE;
        String text = LeapTls.pemText(f.bytes);
        if (text != null) {
            boolean cert = false, key = false;
            int at = 0;
            while (true) {
                int b = text.indexOf("-----BEGIN ", at);
                if (b < 0) break;
                int te = text.indexOf("-----", b + 11);
                if (te < 0) break;
                String type = text.substring(b + 11, te).trim();
                if (type.endsWith("CERTIFICATE")) cert = true;
                else if (type.endsWith("PRIVATE KEY")) key = true;
                at = te + 5;
            }
            return cert && key ? Content.BOTH : cert ? Content.CERT : key ? Content.KEY : Content.UNKNOWN;
        }
        if (LeapTls.tryCert(f.bytes) != null) return Content.CERT;
        if (LeapTls.tryPkcs8(f.bytes) != null) return Content.KEY;
        if (LeapTls.looksLikePkcs12(f.bytes)) return Content.BUNDLE;
        return Content.UNKNOWN;
    }

    /** Sobrescribe con ceros el contenido de los ficheros (una clave privada no se queda en memoria más de la cuenta). */
    static void wipe(List<LeapTls.Picked> files) {
        if (files == null) return;
        for (LeapTls.Picked f : files) wipe(f);
    }

    private static void wipe(LeapTls.Picked f) {
        if (f != null && f.bytes != null) Arrays.fill(f.bytes, (byte) 0);
    }

    /** Solo los nombres, para el log: «app.crt, app.key». */
    static String names(List<LeapTls.Picked> files) {
        StringBuilder b = new StringBuilder();
        for (LeapTls.Picked f : files) {
            if (b.length() > 0) b.append(", ");
            b.append(f.name.isEmpty() ? "(sin nombre)" : f.name);
        }
        return b.toString();
    }
}
