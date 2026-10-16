package com.headqlink.link;

/** Cuentas de la columna estrecha de la sección Coche (CarKit.page con CarKit.narrow), sin Android: la prueban los tests. */
final class NarrowGrid {
    private NarrowGrid() {
    }

    /**
     * El alto de cada hijo de una columna de la rejilla de alto h (pesos, o tamaño fijo con peso 0), descontando los
     * huecos, como lo repartiría LinearLayout.
     */
    static int[] split(float[] weights, int[] sizes, int h, int gap) {
        int n = weights.length;
        int fixed = 0;
        float total = 0;
        for (int i = 0; i < n; i++) {
            if (weights[i] > 0) total += weights[i];
            else fixed += sizes[i];
        }
        int free = Math.max(0, h - fixed - gap * Math.max(0, n - 1));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = weights[i] > 0 ? Math.round(free * weights[i] / total) : sizes[i];
        return out;
    }
}
