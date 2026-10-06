package com.headqlink.link;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Comprobación de requisitos (pura: la prueban los tests). De una foto del estado del móvil ({@link Snapshot}) a la
 * lista de lo que hace falta para los ajustes actuales (modo, conexión con el coche, conexión automática por
 * Bluetooth), cada cosa con su importancia y su estado. Qué texto y qué botón lleva cada una lo decide
 * {@link Checklist}; aquí no hay nada de Android.
 *
 * Qué entra según los ajustes:
 * - Android Auto (modos Auto): instalado y activado. Con AA 17.4 o más (salvo force_legacy_launch), además la
 *   accesibilidad de HeadQLink y el modo desarrollador de AA (arrancan su servidor de head unit). Con una versión más
 *   vieja AA se lanza sin ellos (SelfLauncherManager) y no salen.
 * - Con el «Arranque del servidor de Android Auto» en manual (sin accesibilidad): la accesibilidad pasa a opcional
 *   («Solo para el modo automático»), el modo desarrollador no se puede comprobar (consejo, salvo que AA ya haya atendido
 *   a HeadQLink) y sale una fila informativa «Servidor de Android Auto» con el atajo a sus ajustes. Esa fila **no mira el
 *   puerto** (una conexión de prueba, que abre y cierra sin hablar, bloquea el servidor hasta pararlo y volver a
 *   iniciarlo): dice lo que se sabe sin tocarlo (en uso por HeadQLink, esperando a que lo arranques, o cómo arrancarlo).
 *   Con el automático y la accesibilidad sin activar, un consejo ofrece el arranque manual. Nada de esto bloquea
 *   «Conectar»: si el servidor no atiende, el enlace avisa y reintenta hasta que lo arranque (o lo reinicie) el usuario.
 * - Modo App: la app elegida, la accesibilidad (toques) y «Mostrar sobre otras apps» (abrirla en segundo plano).
 * - Wi-Fi Direct: «Dispositivos Wi-Fi cercanos» (ubicación antes de Android 13), el Wi-Fi activado y la zona Wi-Fi
 *   apagada (en Samsung no conviven). Zona Wi-Fi: que esté activa, y el consejo de la banda de 5 GHz (no se puede leer).
 *   Cable USB (experimental): nada de Wi-Fi ni zona Wi-Fi; solo el consejo del cable de datos en el puerto USB de datos.
 * - QDLink: si está instalado, aviso de cerrarlo; si el puerto UDP 18463 está ocupado, error. Con el cable USB no hay
 *   puerto UDP: el aviso es que Android puede preguntar qué app abre «QDriveLink».
 * - Notificaciones (recomendado), Bluetooth (solo con la conexión automática), batería sin restricciones
 *   (recomendado) y el consejo del gestor de energía del fabricante.
 * - «Mostrar sobre otras apps» es opcional en los modos Auto; fotos, vídeos y ubicación, opcionales en Auto ampliado.
 */
final class Requirements {
    enum Id {
        ANDROID_AUTO, AA_SERVER, ACCESSIBILITY, SERVER_MANUAL_OFFER, AA_DEVMODE, TARGET_APP,
        NEARBY_WIFI, WIFI_ON, HOTSPOT_OFF, HOTSPOT_ON, HOTSPOT_BAND, USB_CABLE,
        QDLINK, NOTIFICATIONS, BLUETOOTH, BATTERY, BATTERY_OEM, OVERLAY, MEDIA
    }

    /** INFO: solo informa (nunca cuenta en «Faltan N» ni bloquea). */
    enum Importance { REQUIRED, RECOMMENDED, OPTIONAL, INFO }

    /**
     * OK: cumplido. MISSING: falta y el usuario lo puede arreglar. ERROR: falla ahora (p. ej. el puerto ocupado).
     * WARN: puede dar problemas pero no se puede saber. UNKNOWN: no se pudo comprobar. CHECKING: comprobándose.
     * TIP: consejo que no se puede leer desde una app.
     */
    enum Status { OK, MISSING, ERROR, WARN, UNKNOWN, CHECKING, TIP }

    /** Matiz del estado, para elegir el texto y el botón. */
    enum Hint {
        NONE,
        /** Permiso denegado para siempre (o notificaciones desactivadas): solo desde Ajustes. */
        BLOCKED,
        /** Android 13+, app instalada fuera de la tienda: «Permitir ajustes restringidos» antes de activarla. */
        RESTRICTED,
        /** Accesibilidad activada en Ajustes pero el servicio no está en marcha (Android lo paró). */
        NOT_RUNNING,
        NOT_INSTALLED,
        DISABLED,
        /** Modo desarrollador de AA: aún no se ha comprobado. */
        NOT_CHECKED,
        /** Arranque manual del servidor de AA: la accesibilidad solo es para el automático; el modo desarrollador no se comprueba. */
        MANUAL,
        /** QDLink instalado (ciérralo antes de conectar). */
        INSTALLED,
        /** El UDP 18463 está ocupado. */
        PORT_BUSY,
        /** Cable USB con QDLink instalada: Android puede preguntar qué app abre «QDriveLink». */
        USB_CHOOSER,
        /** Batería: la necesita la conexión automática por Bluetooth. */
        BT_AUTO,
        SAMSUNG,
        OEM
    }

    /** Permiso en tiempo de ejecución: concedido, se puede pedir, o solo desde Ajustes. */
    enum Perm { GRANTED, ASK, BLOCKED }

    enum Hotspot { ON, OFF, UNKNOWN, CHECKING }

    enum Port { FREE, BUSY, UNKNOWN }

    enum Oem { NONE, SAMSUNG, OTHER }

    /**
     * Servidor de head unit de AA (127.0.0.1:5277) con el arranque manual, sin conectarse a él: IN_USE, nuestra head unit
     * lo tiene (AA conectado); WAITING, los intentos fallan (aviso «Arranca (o vuelve a arrancar)…» puesto); UNKNOWN, no
     * se sabe sin conectarse (y una sonda lo bloquearía).
     */
    enum AaServer { IN_USE, WAITING, UNKNOWN }

    /** Foto del estado del móvil. Por defecto, un móvil con todo en orden en Auto ampliado y Wi-Fi Direct. */
    static final class Snapshot {
        int sdk = 34;
        String mode = Config.MODE_AA_EXT;
        String linkMode = Config.LINK_P2P;
        boolean btAuto;
        boolean forceLegacyLaunch;
        /** «Arranque del servidor de Android Auto»: manual (sin accesibilidad). */
        boolean manualServer;
        AaServer aaServer = AaServer.UNKNOWN;

        /** En Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. */
        boolean accessibilityEnabled = true;
        /** TouchService conectado. */
        boolean accessibilityRunning = true;
        boolean restrictedSettings;

        /** versionName de Android Auto, o null si no está instalado. */
        String aaVersion = "17.6.1";
        boolean aaEnabled = true;
        /** 1 activo, 0 falta, -1 sin comprobar (AaServerStarter.devModeState). */
        int devMode = 1;
        boolean devModeChecking;

        boolean targetAppChosen = true;

        Perm notifications = Perm.GRANTED;
        /** NEARBY_WIFI_DEVICES (Android 13+) o ubicación (antes). */
        Perm nearby = Perm.GRANTED;
        Perm bluetooth = Perm.GRANTED;
        Perm media = Perm.GRANTED;
        boolean overlay = true;

        boolean wifiOn = true;
        Hotspot hotspot = Hotspot.OFF;

        boolean batteryUnrestricted = true;
        Oem oem = Oem.NONE;

        boolean qdlinkInstalled;
        Port port = Port.FREE;
    }

    static final class Item {
        final Id id;
        final Importance importance;
        final Status status;
        final Hint hint;

        Item(Id id, Importance importance, Status status, Hint hint) {
            this.id = id;
            this.importance = importance;
            this.status = status;
            this.hint = hint;
        }

        /** Falta o falla (hay que hacer algo). */
        boolean missing() {
            return status == Status.MISSING || status == Status.ERROR;
        }

        /** Cuenta en «Faltan N cosas»: lo obligatorio y lo recomendado. */
        boolean counts() {
            return missing() && (importance == Importance.REQUIRED || importance == Importance.RECOMMENDED);
        }

        /** Sin esto no se conecta: se avisa al pulsar Conectar. */
        boolean blocks() {
            return missing() && importance == Importance.REQUIRED;
        }

        @Override
        public String toString() {
            return id + "/" + importance + "/" + status + (hint == Hint.NONE ? "" : "/" + hint);
        }
    }

    private Requirements() {
    }

    static List<Item> evaluate(Snapshot s) {
        List<Item> out = new ArrayList<>();
        // Las constantes de Config se copian al compilar: la clase (con Android) ni se carga en los tests.
        boolean aa = Config.MODE_AA.equals(s.mode) || Config.MODE_AA_EXT.equals(s.mode);
        boolean app = Config.MODE_APP.equals(s.mode);
        boolean hotspotLink = Config.LINK_HOTSPOT.equals(s.linkMode);
        boolean usbLink = Config.LINK_USB.equals(s.linkMode);
        boolean server = aa && s.aaVersion != null && !s.forceLegacyLaunch && usesHeadUnitServer(s.aaVersion);
        // El arranque manual solo cuenta si AA usa su servidor de head unit (el modo App siempre necesita los toques).
        boolean manual = server && s.manualServer;

        // Lo que pide el modo.
        if (aa) {
            Status st = s.aaVersion == null || !s.aaEnabled ? Status.MISSING : Status.OK;
            Hint h = s.aaVersion == null ? Hint.NOT_INSTALLED : !s.aaEnabled ? Hint.DISABLED : Hint.NONE;
            out.add(new Item(Id.ANDROID_AUTO, Importance.REQUIRED, st, h));
        }
        if (manual) {
            // Informativa: nunca bloquea (si no atiende, el enlace avisa y reintenta hasta que lo arranques).
            out.add(new Item(Id.AA_SERVER, Importance.INFO, aaServerStatus(s.aaServer), Hint.NONE));
        }
        if (server || app) {
            Status st;
            Hint h = Hint.NONE;
            if (s.accessibilityRunning) {
                st = Status.OK;
            } else {
                st = Status.MISSING;
                if (s.accessibilityEnabled) h = Hint.NOT_RUNNING;
                else if (s.restrictedSettings) h = Hint.RESTRICTED;
            }
            if (manual) {
                // Solo para el modo automático: opcional y sin matices (no hace falta activarla).
                out.add(new Item(Id.ACCESSIBILITY, Importance.OPTIONAL, st, Hint.MANUAL));
            } else {
                out.add(new Item(Id.ACCESSIBILITY, Importance.REQUIRED, st, h));
                // Sin la accesibilidad (lo que más cuesta, sobre todo con «ajustes restringidos»): se ofrece el manual.
                if (server && st != Status.OK) {
                    out.add(new Item(Id.SERVER_MANUAL_OFFER, Importance.OPTIONAL, Status.TIP, Hint.NONE));
                }
            }
        }
        if (server) {
            if (manual) {
                // Sin accesibilidad no se puede abrir su menú para comprobarlo; si AA atiende a HeadQLink, está activo.
                boolean on = s.devMode == 1 || s.aaServer == AaServer.IN_USE;
                out.add(new Item(Id.AA_DEVMODE, Importance.RECOMMENDED, on ? Status.OK : Status.TIP,
                        on ? Hint.NONE : Hint.MANUAL));
            } else {
                Status st = s.devModeChecking ? Status.CHECKING : s.devMode == 1 ? Status.OK : Status.MISSING;
                Hint h = st == Status.MISSING && s.devMode != 0 ? Hint.NOT_CHECKED : Hint.NONE;
                out.add(new Item(Id.AA_DEVMODE, Importance.REQUIRED, st, h));
            }
        }
        if (app) {
            out.add(new Item(Id.TARGET_APP, Importance.REQUIRED, s.targetAppChosen ? Status.OK : Status.MISSING, Hint.NONE));
        }

        // La conexión con el coche.
        if (hotspotLink) {
            out.add(new Item(Id.HOTSPOT_ON, Importance.REQUIRED, hotspotStatus(s.hotspot, true), Hint.NONE));
            out.add(new Item(Id.HOTSPOT_BAND, Importance.RECOMMENDED, Status.TIP, Hint.NONE));
        } else if (usbLink) {
            // Lo que pide el cable no se puede leer desde la app (que el coche ponga el móvil en modo accesorio).
            out.add(new Item(Id.USB_CABLE, Importance.RECOMMENDED, Status.TIP, Hint.NONE));
        } else {
            out.add(perm(Id.NEARBY_WIFI, Importance.REQUIRED, s.nearby));
            out.add(new Item(Id.WIFI_ON, Importance.REQUIRED, s.wifiOn ? Status.OK : Status.MISSING, Hint.NONE));
            out.add(new Item(Id.HOTSPOT_OFF, Importance.REQUIRED, hotspotStatus(s.hotspot, false), Hint.NONE));
        }
        if (usbLink) {
            if (s.qdlinkInstalled) out.add(new Item(Id.QDLINK, Importance.RECOMMENDED, Status.WARN, Hint.USB_CHOOSER));
        } else if (s.port == Port.BUSY) {
            out.add(new Item(Id.QDLINK, Importance.REQUIRED, Status.ERROR, Hint.PORT_BUSY));
        } else if (s.qdlinkInstalled) {
            out.add(new Item(Id.QDLINK, Importance.RECOMMENDED, Status.WARN, Hint.INSTALLED));
        }

        // Permisos y segundo plano.
        out.add(perm(Id.NOTIFICATIONS, Importance.RECOMMENDED, s.notifications));
        if (s.btAuto) out.add(perm(Id.BLUETOOTH, Importance.REQUIRED, s.bluetooth));
        out.add(new Item(Id.BATTERY, Importance.RECOMMENDED, s.batteryUnrestricted ? Status.OK : Status.MISSING,
                s.btAuto ? Hint.BT_AUTO : Hint.NONE));
        if (s.oem != Oem.NONE) {
            out.add(new Item(Id.BATTERY_OEM, Importance.RECOMMENDED, Status.TIP, s.oem == Oem.SAMSUNG ? Hint.SAMSUNG : Hint.OEM));
        }
        if (app || aa) {
            out.add(new Item(Id.OVERLAY, app ? Importance.REQUIRED : Importance.OPTIONAL,
                    s.overlay ? Status.OK : Status.MISSING, Hint.NONE));
        }
        if (Config.MODE_AA_EXT.equals(s.mode)) out.add(perm(Id.MEDIA, Importance.OPTIONAL, s.media));
        return Collections.unmodifiableList(out);
    }

    /**
     * En uso por HeadQLink: OK; los intentos fallan: aviso (no falta nada que se arregle aquí: lo arranca el usuario);
     * sin saber: consejo (cómo arrancarlo), porque mirarlo con una sonda lo bloquearía.
     */
    private static Status aaServerStatus(AaServer a) {
        switch (a) {
            case IN_USE:
                return Status.OK;
            case WAITING:
                return Status.WARN;
            default:
                return Status.TIP;
        }
    }

    private static Status hotspotStatus(Hotspot h, boolean wantOn) {
        switch (h) {
            case ON:
                return wantOn ? Status.OK : Status.MISSING;
            case OFF:
                return wantOn ? Status.MISSING : Status.OK;
            case CHECKING:
                return Status.CHECKING;
            default:
                return Status.UNKNOWN;
        }
    }

    private static Item perm(Id id, Importance imp, Perm p) {
        return new Item(id, imp, p == Perm.GRANTED ? Status.OK : Status.MISSING, p == Perm.BLOCKED ? Hint.BLOCKED : Hint.NONE);
    }

    /** Lo que cuenta en «Faltan N cosas». */
    static int missingCount(List<Item> items) {
        int n = 0;
        for (Item i : items) if (i.counts()) n++;
        return n;
    }

    /** Lo obligatorio que falta (sin ello no se conecta). */
    static List<Item> blocking(List<Item> items) {
        List<Item> out = new ArrayList<>();
        for (Item i : items) if (i.blocks()) out.add(i);
        return out;
    }

    static Item find(List<Item> items, Id id) {
        for (Item i : items) if (i.id == id) return i;
        return null;
    }

    /**
     * Si Android Auto arranca por su servidor de head unit (17.4 o más), con la misma lectura de la versión que
     * SelfLauncherManager: lo que no se entiende cuenta como 0 (y va por el camino viejo).
     */
    static boolean usesHeadUnitServer(String versionName) {
        if (versionName == null) return false;
        String[] parts = versionName.split("\\.");
        int major = parts.length > 0 ? toInt(parts[0]) : 0;
        int minor = parts.length > 1 ? toInt(parts[1]) : 0;
        return major > 17 || (major == 17 && minor >= 4);
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Si la lista de Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES ("pkg/cls:pkg/.Cls…") tiene el servicio
     * pkg/cls (también en la forma corta con el punto).
     */
    static boolean serviceEnabled(String setting, String pkg, String cls) {
        if (setting == null || setting.isEmpty() || pkg == null || cls == null) return false;
        for (String entry : setting.split(":")) {
            int slash = entry.indexOf('/');
            if (slash <= 0) continue;
            String p = entry.substring(0, slash).trim();
            String c = entry.substring(slash + 1).trim();
            if (c.startsWith(".")) c = p + c;
            if (p.equalsIgnoreCase(pkg) && c.equals(cls)) return true;
        }
        return false;
    }

    /**
     * Estado de un permiso: concedido; se puede pedir si nunca se pidió o Android aún explicaría por qué; si ya se
     * pidió y Android no lo explicaría, el usuario lo denegó para siempre y solo queda Ajustes.
     */
    static Perm permState(boolean granted, boolean asked, boolean rationale) {
        if (granted) return Perm.GRANTED;
        if (!asked || rationale) return Perm.ASK;
        return Perm.BLOCKED;
    }
}
