package com.headqlink.link;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.andrerinas.openheadunit.R;

/** El nombre y los botones de TV siguen el mismo temporizador que los controles del vídeo. */
final class TvPlayerControls {
    static final int HIDE_AFTER_MS = 4000;
    private static final String FILL = "tv_fill";

    private TvPlayerControls() { }

    static void bind(Context context, PlayerView playerView, View overlay, TextView fillButton) {
        Config cfg = new Config(context);
        playerView.setControllerAutoShow(false);
        playerView.setControllerAnimationEnabled(false);
        playerView.setControllerShowTimeoutMs(HIDE_AFTER_MS);
        playerView.setControllerHideOnTouch(true);
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) overlay::setVisibility);
        overlay.setVisibility(playerView.isControllerFullyVisible() ? View.VISIBLE : View.GONE);
        Runnable apply = () -> {
            boolean fill = cfg.getBool(FILL);
            playerView.setResizeMode(fill ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM : AspectRatioFrameLayout.RESIZE_MODE_FIT);
            fillButton.setText(Str.get(fill ? R.string.hql_tv_fit : R.string.hql_tv_fill));
            fillButton.setSelected(fill);
            fillButton.setBackground(fill ? CarStyle.accent(28) : CarStyle.round(CarStyle.PILL_BG, 28));
            fillButton.setTextColor(fill ? CarStyle.ON_ACCENT : CarStyle.TEXT);
        };
        fillButton.setOnClickListener(v -> {
            cfg.putBool(FILL, !cfg.getBool(FILL));
            apply.run();
            playerView.showController();
        });
        apply.run();
    }
}
