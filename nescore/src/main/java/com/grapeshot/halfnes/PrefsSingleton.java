/*
 * HalfNES by Andrew Hoffman
 * Licensed under the GNU GPL Version 3. See LICENSE file
 *
 * HeadQLink: java.util.prefs no existe en Android; las preferencias del núcleo van en un mapa en memoria con la misma
 * API (getBoolean, getInt, …). Quien usa el núcleo pone las suyas con put*.
 */
package com.grapeshot.halfnes;

import java.util.HashMap;
import java.util.Map;

public class PrefsSingleton {
    private static Prefs instance = null;

    protected PrefsSingleton() {
    }

    public synchronized static Prefs get() {
        if (instance == null) {
            instance = new Prefs();
        }
        return instance;
    }

    public static final class Prefs {
        private final Map<String, String> values = new HashMap<>();

        public synchronized boolean getBoolean(String key, boolean def) {
            String v = values.get(key);
            return v == null ? def : Boolean.parseBoolean(v);
        }

        public synchronized int getInt(String key, int def) {
            String v = values.get(key);
            try {
                return v == null ? def : Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        public synchronized double getDouble(String key, double def) {
            String v = values.get(key);
            try {
                return v == null ? def : Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return def;
            }
        }

        public synchronized String get(String key, String def) {
            String v = values.get(key);
            return v == null ? def : v;
        }

        public synchronized void putBoolean(String key, boolean v) {
            values.put(key, String.valueOf(v));
        }

        public synchronized void putInt(String key, int v) {
            values.put(key, String.valueOf(v));
        }

        public synchronized void putDouble(String key, double v) {
            values.put(key, String.valueOf(v));
        }

        public synchronized void put(String key, String v) {
            values.put(key, v);
        }
    }
}
