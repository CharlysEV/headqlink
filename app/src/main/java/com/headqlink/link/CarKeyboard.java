package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Teclado en la propia pantalla del coche. El teclado de Android saldría en el móvil (el
 * VirtualDisplay no tiene método de entrada), así que la web, las búsquedas y las URL usan este.
 *
 * Cada tecla se ilumina al pulsarla (y un momento después, para que se vea aunque el toque sea muy corto) y, si quien lo
 * usa llama a showText, encima de las teclas va lo que se está escribiendo: se ve sin apartar la vista del teclado.
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

    /** Cuánto se queda iluminada una tecla tras pulsarla. */
    private static final long FLASH_MS = 160;
    private static final int KEY_LIT = 0xFF00E5C7;

    private final Listener listener;
    private boolean shift;
    private final java.util.List<TextView> letterKeys = new java.util.ArrayList<>();
    /** Lo escrito, encima de las teclas (oculto hasta el primer showText). */
    private final TextView echo;

    CarKeyboard(Context c, Listener listener) {
        super(c);
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFF15181C);
        setPadding(10, 10, 10, 10);
        echo = CarStyle.text(c, "", 30, CarStyle.TEXT);
        echo.setSingleLine(true);
        echo.setEllipsize(android.text.TextUtils.TruncateAt.START);
        echo.setGravity(Gravity.CENTER_VERTICAL);
        echo.setPadding(22, 0, 22, 0);
        echo.setVisibility(GONE);
        LayoutParams elp = new LayoutParams(LayoutParams.MATCH_PARENT, 62);
        elp.setMargins(4, 0, 4, 6);
        addView(echo, elp);
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
                    flash(k);
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
        row.addView(key(Str.get(R.string.hql_key_space), 2.4f, () -> listener.onText(" ")));
        row.addView(key(Str.get(R.string.hql_go), 1.2f, listener::onEnter));
        row.addView(key(Str.get(R.string.hql_hide), 1.4f, listener::onHide));
        addView(row);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, 72));
        return row;
    }

    /** Enseña encima de las teclas lo escrito (con el cursor) o, vacío, la pista en gris. */
    void showText(CharSequence text, CharSequence hint) {
        boolean empty = text == null || text.length() == 0;
        echo.setText(empty ? hint : text + "▏");
        echo.setTextColor(empty ? 0xFF8296A9 : CarStyle.TEXT);
        echo.setBackground(CarStyle.round(0xFF0F1318, 12));
        echo.setVisibility(VISIBLE);
    }

    private TextView key(String label, float weight, Runnable action) {
        TextView k = CarStyle.text(getContext(), label, 28, CarStyle.TEXT);
        k.setGravity(Gravity.CENTER);
        k.setBackground(keyBackground());
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(4, 4, 4, 4);
        k.setLayoutParams(lp);
        if (action != null) k.setOnClickListener(v -> {
            flash(k);
            action.run();
        });
        return k;
    }

    /** Fondo de tecla: normal o, pulsada, iluminada. */
    private static android.graphics.drawable.StateListDrawable keyBackground() {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed}, CarStyle.round(KEY_LIT, 10));
        d.addState(new int[]{}, CarStyle.round(CarStyle.ITEM_BG, 10));
        return d;
    }

    /** Ilumina la tecla un momento (el estado «pulsada» puede durar menos que un fotograma del vídeo al coche). */
    private static void flash(TextView k) {
        k.setBackground(CarStyle.round(KEY_LIT, 10));
        k.setTextColor(0xFF00211C);
        k.postDelayed(() -> {
            k.setBackground(keyBackground());
            k.setTextColor(CarStyle.TEXT);
        }, FLASH_MS);
    }
}
