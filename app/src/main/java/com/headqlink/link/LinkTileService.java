package com.headqlink.link;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.utils.ToastUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Botón «HeadQLink» de los ajustes rápidos (Android 8+):
 * <ul>
 *   <li>tocarlo: Desconectar si está en marcha; si no, Conectar por el puente invisible (QuickToggleActivity, igual que
 *       el botón de la app: la comprobación si falta algo obligatorio, el servidor de Android Auto…). Con el móvil
 *       bloqueado, Conectar pide desbloquearlo antes;</li>
 *   <li>mantenerlo pulsado: abre la app (HomeActivity con QS_TILE_PREFERENCES, sin conectar sola);</li>
 *   <li>activo con el enlace en marcha; el subtítulo dice el estado («Buscando el coche…», «30 fps · 4,8 Mbit/s»…).</li>
 * </ul>
 * Solo escucha los cambios mientras los ajustes rápidos están a la vista (onStartListening / onStopListening).
 */
@RequiresApi(24)
public final class LinkTileService extends TileService implements LinkState.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int RC_TILE = 200;
    private static final String FROM = "ajustes rápidos";
    private boolean listening;

    @Override
    public void onStartListening() {
        super.onStartListening();
        listening = true;
        LinkState.addListener(this);
        new Config(this).listen(this, true);
        refresh();
    }

    @Override
    public void onStopListening() {
        listening = false;
        LinkState.removeListener(this);
        new Config(this).listen(this, false);
        super.onStopListening();
    }

    @Override
    public void onLinkStateChanged() {
        if (listening) refresh();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        if (listening && (Config.MODE.equals(key) || Config.LINK_MODE.equals(key))) refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        L.init(this);
        if (LinkState.running) {
            // El servicio está en marcha y en primer plano: startService vale desde aquí.
            LinkControl.stop(this, FROM);
            return;
        }
        // Conectar necesita la app delante (servicio en primer plano, comprobación, servidor de Android Auto).
        if (isLocked()) unlockAndRun(this::openToggle);
        else openToggle();
    }

    /** El puente invisible, cerrando los ajustes rápidos (Android 14+ solo con un PendingIntent). */
    @SuppressWarnings("deprecation")
    private void openToggle() {
        PendingIntent pi = LinkWidgetViews.togglePending(this, FROM, RC_TILE);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(pi);
            } else {
                startActivityAndCollapse(new Intent(this, QuickToggleActivity.class)
                        .setAction(QuickToggleActivity.ACTION_TOGGLE)
                        .putExtra(QuickToggleActivity.EXTRA_FROM, FROM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            }
        } catch (RuntimeException e) {
            L.w(FROM + ": no se pudo abrir Conectar: " + e);
        }
    }

    private void refresh() {
        Tile t = getQsTile();
        if (t == null) return;
        LinkGlance g = WidgetUpdater.glance(this);
        String status = LinkWidgetViews.oneLine(this, g);
        t.setLabel("HeadQLink");
        t.setIcon(Icon.createWithResource(this, R.drawable.hql_ic_notif));
        t.setState(g.active() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            t.setSubtitle(g.status == LinkGlance.Status.LIVE ? g.videoText() : LinkWidgetViews.title(this, g));
        }
        if (Build.VERSION.SDK_INT >= 30) t.setStateDescription(status);
        t.setContentDescription("HeadQLink. " + LinkWidgetViews.actionText(this, g) + ". " + status);
        t.updateTile();
    }

    /**
     * Menú del engranaje › «Añadir botón a los ajustes rápidos» (Android 13+): Android pregunta si se añade. Si no se
     * puede, se explica cómo hacerlo a mano.
     */
    @RequiresApi(33)
    static void requestAdd(Activity a) {
        StatusBarManager sbm = a.getSystemService(StatusBarManager.class);
        if (sbm == null) {
            showManual(a);
            return;
        }
        try {
            sbm.requestAddTileService(new ComponentName(a, LinkTileService.class), "HeadQLink",
                    Icon.createWithResource(a, R.drawable.hql_ic_notif), a.getMainExecutor(), result -> {
                        L.i("ajustes rápidos: añadir el botón → " + result);
                        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                            ToastUtils.showToast(a, Str.get(R.string.hql_w_tile_added), Toast.LENGTH_SHORT, true);
                        } else if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                            ToastUtils.showToast(a, Str.get(R.string.hql_w_tile_already), Toast.LENGTH_SHORT, true);
                        } else if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED && !a.isFinishing()) {
                            showManual(a);
                        }
                    });
        } catch (RuntimeException e) {
            L.w("ajustes rápidos: no se pudo pedir añadir el botón: " + e);
            showManual(a);
        }
    }

    private static void showManual(Activity a) {
        new MaterialAlertDialogBuilder(a)
                .setTitle(Str.get(R.string.hql_w_add_tile))
                .setMessage(Str.get(R.string.hql_w_tile_manual))
                .setPositiveButton(Str.get(R.string.hql_close), null)
                .show();
    }
}
