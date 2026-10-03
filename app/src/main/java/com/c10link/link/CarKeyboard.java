package com.c10link.link;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Teclado en la propia pantalla del coche. El teclado de Android saldría en el móvil (el
 * VirtualDisplay no tiene método de entrada), así que la web, las búsquedas y las URL usan este.
 */
final class CarKeyboard extends LinearLayout {
    interface Listener {
        void onText(String s);

        void onBackspace();

        void onEnter();

        void onHide();
    }

    private static final String[] LETTERS = {"1234567890", "qwertyuiop", "asdfghjklñ", "zxcvbnm"};
    private static final String SYMBOLS = "./:-_@?&=";

    private final Listener listener;
    private boolean shift;
    private final java.util.List<TextView> letterKeys = new java.util.ArrayList<>();

    CarKeyboard(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF15181C);
        setPadding(10, 10, 10, 10);
        for (int r = 0; r < LETTERS.length; r++) {
            LinearLayout row = row();
            if (r == 3) row.addView(key("⇧", 1.5f, () -> {
                shift = !shift;
                for (TextView k : letterKeys) {
                    String s = k.getText().toString();
                    k.setText(shift ? s.toUpperCase() : s.toLowerCase());
                }
            }));
            for (char ch : LETTERS[r].toCharArray()) {
                TextView k = key(String.valueOf(ch), 1f, null);
                k.setOnClickListener(v -> {
                    listener.onText(k.getText().toString());
                    if (shift) {
                        shift = false;
                        for (TextView l : letterKeys) l.setText(l.getText().toString().toLowerCase());
                    }
                });
                if (Character.isLetter(ch)) letterKeys.add(k);
                row.addView(k);
            }
            if (r == 3) row.addView(key("⌫", 1.5f, listener::onBackspace));
            addView(row);
        }
        LinearLayout row = row();
        for (char ch : SYMBOLS.toCharArray()) {
            String s = String.valueOf(ch);
            row.addView(key(s, 0.7f, () -> listener.onText(s)));
        }
        row.addView(key(".com", 1f, () -> listener.onText(".com")));
        row.addView(key("espacio", 2.4f, () -> listener.onText(" ")));
        row.addView(key("Ir", 1.2f, listener::onEnter));
        row.addView(key("Ocultar", 1.4f, listener::onHide));
        addView(row);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 72));
        return row;
    }

    private TextView key(String label, float weight, Runnable action) {
        TextView k = CarStyle.text(getContext(), label, 28, CarStyle.TEXT);
        k.setGravity(Gravity.CENTER);
        k.setBackground(CarStyle.round(CarStyle.ITEM_BG, 10));
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(4, 4, 4, 4);
        k.setLayoutParams(lp);
        if (action != null) k.setOnClickListener(v -> action.run());
        return k;
    }
}
