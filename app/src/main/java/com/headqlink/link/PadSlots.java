package com.headqlink.link;

/**
 * Puro: qué mando es cada jugador en la partida en marcha. El primer mando que pulsa un botón es el jugador 1; el segundo
 * mando distinto, el jugador 2; un tercero se ignora. No se reparte por orden de conexión: los mandos Bluetooth se
 * duermen y al despertar vuelven con otro deviceId (más alto), y los que ya estaban conectados antes del juego no dicen
 * cuál llegó primero; «pulsa un botón y eres el siguiente jugador» es lo de las consolas y no depende de eso.
 * <p>
 * Cada mando se reconoce por una clave estable (InputDevice.getDescriptor(), que sobrevive a una reconexión) y por su
 * deviceId del momento (el aviso de desconexión solo trae el id). Un mando desconectado guarda su puesto y lo recupera al
 * volver; solo si un mando nuevo no encuentra sitio libre ocupa el del desconectado. El fin de la partida lo limpia todo.
 */
final class PadSlots {
    static final int PLAYERS = 2;
    static final int NONE = -1;

    private final String[] keys = new String[PLAYERS];
    private final int[] ids = new int[PLAYERS];
    private final boolean[] gone = new boolean[PLAYERS];

    /** Puesto (0 = jugador 1, 1 = jugador 2) del mando con esa clave, sin asignarle nada; NONE si no tiene. */
    synchronized int seatOf(String key) {
        if (key == null) return NONE;
        for (int i = 0; i < PLAYERS; i++) if (key.equals(keys[i])) return i;
        return NONE;
    }

    /**
     * El puesto de ese mando: el suyo si ya lo tenía (y si se había desconectado, lo recupera con el id nuevo); si es
     * nuevo, el primero libre, o el de un mando desconectado si no queda ninguno libre; NONE si todos están ocupados.
     */
    synchronized int claim(String key, int id) {
        if (key == null) return NONE;
        int s = seatOf(key);
        if (s == NONE) {
            for (int i = 0; i < PLAYERS && s == NONE; i++) if (keys[i] == null) s = i;
            for (int i = 0; i < PLAYERS && s == NONE; i++) if (gone[i]) s = i;
            if (s == NONE) return NONE;
            keys[s] = key;
        }
        ids[s] = id;
        gone[s] = false;
        return s;
    }

    /** Se ha desconectado el dispositivo con ese id: su puesto queda guardado (y marcado). Devuelve el puesto, o NONE. */
    synchronized int removed(int id) {
        for (int i = 0; i < PLAYERS; i++) {
            if (keys[i] != null && !gone[i] && ids[i] == id) {
                gone[i] = true;
                return i;
            }
        }
        return NONE;
    }

    /** ¿Tiene mando ese puesto? (conectado o no). */
    synchronized boolean taken(int seat) {
        return keys[seat] != null;
    }

    /** ¿Tiene mando y está conectado? */
    synchronized boolean connected(int seat) {
        return keys[seat] != null && !gone[seat];
    }

    /** Mandos con puesto (conectados o no). */
    synchronized int count() {
        int n = 0;
        for (String k : keys) if (k != null) n++;
        return n;
    }

    /** Fin de la partida: nadie tiene puesto. */
    synchronized void clear() {
        for (int i = 0; i < PLAYERS; i++) {
            keys[i] = null;
            ids[i] = 0;
            gone[i] = false;
        }
    }
}
