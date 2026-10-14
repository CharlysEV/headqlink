package com.headqlink.link;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

/**
 * Pantalla del móvil mientras se juega en el coche: recibe el mando Bluetooth entero (teclas y ejes de la cruceta y el
 * stick, que la accesibilidad no ve) y lo pasa al emulador. Se abre al empezar un juego y se cierra con él, o con atrás.
 */
public class NesPadActivity extends Activity {
    static volatile NesPadActivity instance;
    /** «Mando 1 · Mando 2»: los mandos con jugador en la partida. */
    private TextView pads;
    private final NesInput.PadsListener padsListener = notice -> runOnUiThread(this::showPads);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(getColor(R.color.hql_bg));
        int pad = Math.round(32 * getResources().getDisplayMetrics().density);
        col.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText(Str.get(R.string.hql_nes_pad_title));
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setTextColor(getColor(R.color.hql_text));
        title.setGravity(Gravity.CENTER);
        col.addView(title);
        TextView text = new TextView(this);
        text.setText(Str.get(R.string.hql_nes_pad_text));
        text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        text.setTextColor(getColor(R.color.hql_text_dim));
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, pad / 2, 0, 0);
        col.addView(text);
        TextView two = new TextView(this);
        two.setText(Str.get(R.string.hql_games_pads_help));
        two.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        two.setTextColor(getColor(R.color.hql_text_dim));
        two.setGravity(Gravity.CENTER);
        two.setPadding(0, pad / 2, 0, 0);
        col.addView(two);
        pads = new TextView(this);
        pads.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium);
        pads.setTextColor(getColor(R.color.hql_text));
        pads.setGravity(Gravity.CENTER);
        pads.setPadding(0, pad / 2, 0, 0);
        col.addView(pads);
        setContentView(col);
        NesInput.addListener(padsListener);
        showPads();
    }

    private void showPads() {
        String l = NesInput.padsLabel();
        pads.setText(l);
        pads.setVisibility(l.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        NesInput.removeListener(padsListener);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Atrás en el móvil cierra esta pantalla (el juego sigue en el coche); el resto va al mando.
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (NesInput.key(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (NesInput.motion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    private static final String CHANNEL = "hql_nes";
    private static final int NOTIFICATION_ID = 7301;

    /** Notificación «Mando: toca para abrir» mientras hay juego (abrir la pantalla desde segundo plano está bloqueado). */
    static void notify(android.content.Context ctx, String game) {
        try {
            android.app.NotificationManager nm = ctx.getSystemService(android.app.NotificationManager.class);
            nm.createNotificationChannel(new android.app.NotificationChannel(CHANNEL, Str.get(R.string.hql_nes),
                    android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 0,
                    new android.content.Intent(ctx, NesPadActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
            nm.notify(NOTIFICATION_ID, new android.app.Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(R.drawable.hql_ic_games)
                    .setContentTitle(Str.get(R.string.hql_nes_pad_title))
                    .setContentText(Str.get(R.string.hql_nes_pad_notification, game))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build());
            // En la pantalla del móvil (el contexto de la interfaz del coche apuntaría a su pantalla virtual: «Permission
            // Denial»). Con el servicio de accesibilidad activo, Android deja abrirla aunque HeadQLink no esté delante.
            // Con Android 14+ los ejes del mando llegan por la accesibilidad (TouchService.gamepadAxes): no hace falta abrirla;
            // queda la notificación por si acaso.
            if (TouchService.axesWithoutActivity()) return;
            android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
            ctx.startActivity(new android.content.Intent(ctx, NesPadActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    o.toBundle());
        } catch (Exception e) {
            L.w("NES: no se pudo avisar del mando: " + e);
        }
    }

    static void cancelNotification(android.content.Context ctx) {
        try {
            ctx.getSystemService(android.app.NotificationManager.class).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
    }

    /** Cierra la pantalla del mando si está abierta (al acabar el juego). */
    static void close() {
        NesPadActivity a = instance;
        if (a != null) a.finish();
    }
}
