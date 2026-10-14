package com.headqlink.link;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Importes en la interfaz con el formato del idioma de la app: «1,23 €» en español, francés o alemán, «€1.23» en inglés,
 * «€ 1,23» en neerlandés. Los precios (Ajustes, cargadores) son siempre euros: no se convierte nada, solo se escribe
 * como toca. Para logs, CSV y JSON no: ahí siguen las cifras sin formato.
 */
final class Money {
    /** Los Leapmotor de la app se venden en Europa y los precios que se guardan son en euros. */
    static final Currency EUR = Currency.getInstance("EUR");

    private Money() {
    }

    /** Un importe con dos decimales en el idioma de la app. */
    static String format(double amount) {
        return format(amount, 2, Str.locale());
    }

    /** Un importe con {@code decimals} decimales en el idioma de la app («≈ 12 €» con 0). */
    static String format(double amount, int decimals) {
        return format(amount, decimals, Str.locale());
    }

    /** Un precio por unidad en el idioma de la app: «0,25 €/kWh», «€0.25/kWh». */
    static String per(double price, String unit) {
        return per(price, unit, Str.locale());
    }

    /** Puro: el importe en euros con el formato de moneda de {@code loc} y {@code decimals} decimales. */
    static String format(double amount, int decimals, Locale loc) {
        return currency(decimals, loc).format(amount);
    }

    /** Puro: precio por unidad («0,25 €/kWh», «€0.25/kWh», «€ 0,25/kWh»). */
    static String per(double price, String unit, Locale loc) {
        return format(price, 2, loc) + "/" + unit;
    }

    /**
     * Puro: el importe partido para las cifras grandes del coche (número grande y unidad pequeña detrás):
     * {cifra, «€»} si el idioma pone el símbolo detrás («12,34» + «€»); si lo pone delante, todo en la cifra
     * («€12.34» + «») para no escribir «12.34 €» en inglés.
     */
    static String[] parts(double amount, int decimals, Locale loc) {
        NumberFormat f = currency(decimals, loc);
        String s = f.format(amount);
        String sym = f instanceof DecimalFormat ? ((DecimalFormat) f).getDecimalFormatSymbols().getCurrencySymbol() : "€";
        int at = s.lastIndexOf(sym);
        if (at <= 0 || at + sym.length() < s.length()) return new String[]{s, ""};
        return new String[]{s.substring(0, at).trim().replace(' ', ' ').replace(' ', ' ').trim(), sym};
    }

    /** {@link #parts(double, int, Locale)} en el idioma de la app, con dos decimales. */
    static String[] parts(double amount) {
        return parts(amount, 2, Str.locale());
    }

    private static NumberFormat currency(int decimals, Locale loc) {
        NumberFormat f = NumberFormat.getCurrencyInstance(loc == null ? Locale.getDefault() : loc);
        f.setCurrency(EUR);
        f.setMinimumFractionDigits(decimals);
        f.setMaximumFractionDigits(decimals);
        // Como String.format (0,125 → 0,13), no al par más cercano.
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }
}
