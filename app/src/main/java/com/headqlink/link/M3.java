package com.headqlink.link;

import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

/** Piezas de Material 3 para lo que se monta en código en el móvil (diálogos): campos con contorno y botones. */
final class M3 {
    private M3() {
    }

    /** Un campo de texto de Material 3 (meterlo con outlined()). */
    static TextInputEditText field(Context c) {
        return new TextInputEditText(c);
    }

    /**
     * El campo dentro de su caja con contorno; la pista pasa a ser la etiqueta que sube al escribir, y en las
     * contraseñas sale el ojo para verla.
     */
    static TextInputLayout outlined(EditText e) {
        TextInputLayout box = new TextInputLayout(e.getContext(), null, com.google.android.material.R.attr.textInputOutlinedStyle);
        CharSequence hint = e.getHint();
        if (hint != null) {
            box.setHint(hint);
            e.setHint(null);
        }
        int v = e.getInputType() & InputType.TYPE_MASK_VARIATION;
        if ((e.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) {
            box.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        }
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * c(e).getResources().getDisplayMetrics().density);
        box.setLayoutParams(lp);
        return box;
    }

    /** Botón de Material 3: relleno (la acción principal) o de texto. */
    static MaterialButton button(Context c, String text, boolean filled) {
        MaterialButton b = filled ? new MaterialButton(c) : new MaterialButton(c, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        b.setText(text);
        return b;
    }

    private static Context c(EditText e) {
        return e.getContext();
    }
}
