package com.headqlink.link;

/**
 * Consumo REAL con los datos de la nube: la bajada del % de batería por la capacidad del perfil, entre los km del
 * cuentakilómetros del coche. Sin Android: se prueba en el PC.
 *
 * Los límites de credibilidad (8-70 % de batería cada 100 km) son los de LMB10 (daily_stats.dart).
 */
final class CloudEnergy {
    /** Por debajo de esta bajada del % no se mide (un 1 % de redondeo sería un error enorme). */
    static final double MIN_DROP_PCT = 2.0;
    /** El cuentakilómetros va en km enteros: menos que esto no da una media fiable. */
    static final double MIN_KM = 3.0;
    /** % de batería cada 100 km creíble (fuera de esto es un dato malo, no un consumo). */
    static final double PCT_PER_100_MIN = 8.0;
    static final double PCT_PER_100_MAX = 70.0;

    enum Kind {
        /** Medido. */
        OK,
        /** Aún poco consumo (o pocos km) para medir. */
        LITTLE,
        /** Se cargó por el camino: la bajada no es el consumo. */
        CHARGED,
        /** Fuera de lo creíble. */
        IMPLAUSIBLE,
        /** Faltan datos. */
        NONE
    }

    static final class Result {
        final Kind kind;
        final double socDrop;
        final double km;
        final double kwh;
        final double kwhPer100;

        Result(Kind kind, double socDrop, double km, double kwh, double kwhPer100) {
            this.kind = kind;
            this.socDrop = socDrop;
            this.km = km;
            this.kwh = kwh;
            this.kwhPer100 = kwhPer100;
        }

        boolean ok() {
            return kind == Kind.OK;
        }
    }

    private CloudEnergy() {
    }

    static final Result NONE = new Result(Kind.NONE, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

    /** kWh de una bajada del % de batería con la capacidad dada. */
    static double kwh(double socDropPct, double capacityKwh) {
        return socDropPct / 100.0 * capacityKwh;
    }

    /**
     * Consumo real entre dos lecturas (inicio y ahora, o inicio y fin de un viaje). charged: se vio la batería cargando
     * o subiendo entre medias.
     */
    static Result between(double socStart, double odoStart, double socEnd, double odoEnd, double capacityKwh, boolean charged) {
        if (Double.isNaN(socStart) || Double.isNaN(socEnd) || Double.isNaN(odoStart) || Double.isNaN(odoEnd)
                || Double.isNaN(capacityKwh) || capacityKwh <= 0) {
            return NONE;
        }
        double drop = socStart - socEnd;
        double km = odoEnd - odoStart;
        if (charged) return new Result(Kind.CHARGED, drop, km, Double.NaN, Double.NaN);
        if (drop < MIN_DROP_PCT || km < MIN_KM) return new Result(Kind.LITTLE, drop, km, Double.NaN, Double.NaN);
        double pctPer100 = drop / km * 100;
        if (pctPer100 < PCT_PER_100_MIN || pctPer100 > PCT_PER_100_MAX) {
            return new Result(Kind.IMPLAUSIBLE, drop, km, Double.NaN, Double.NaN);
        }
        double e = kwh(drop, capacityKwh);
        return new Result(Kind.OK, drop, km, e, e / km * 100);
    }

    /** % de batería al llegar: el de ahora menos los kWh que faltan sobre la capacidad. NaN si falta algo. */
    static double arrivalPct(double socNow, double remainingKwh, double capacityKwh) {
        if (Double.isNaN(socNow) || Double.isNaN(remainingKwh) || capacityKwh <= 0) return Double.NaN;
        return socNow - remainingKwh / capacityKwh * 100;
    }
}
