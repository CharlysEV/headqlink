package com.headqlink.link;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Piezas comunes de la interfaz de HeadQLink. */
final class Ui {
    // Los permisos que pide cada modo y conexión los decide la comprobación de requisitos (Requirements/Checklist).

    /** Nombre de la conexión con el coche. */
    static String linkTitle(String linkMode) {
        if (Config.LINK_USB.equals(linkMode)) return Str.get(R.string.hql_link_usb);
        return Config.LINK_HOTSPOT.equals(linkMode) ? Str.get(R.string.hql_link_hotspot) : Str.get(R.string.hql_link_p2p);
    }

    static String linkDetail(String linkMode) {
        if (Config.LINK_USB.equals(linkMode)) return Str.get(R.string.hql_link_usb_detail);
        return Config.LINK_HOTSPOT.equals(linkMode) ? Str.get(R.string.hql_link_hotspot_detail) : Str.get(R.string.hql_link_p2p_detail);
    }

    private Ui() {
    }

    /** Color de un estado: verde correcto, ámbar en curso, rojo fallo, gris parado. */
    static int levelColor(Context ctx, LinkState.Level level) {
        switch (level) {
            case OK:
                return ctx.getColor(R.color.hql_ok);
            case BUSY:
                return ctx.getColor(R.color.hql_warn);
            case ERROR:
                return ctx.getColor(R.color.hql_error);
            default:
                return ctx.getColor(R.color.hql_text_dim);
        }
    }

    /** Fondo translúcido (16 %) de un color de estado, para chips e insignias. */
    static int levelTint(int color) {
        return (color & 0x00FFFFFF) | 0x29000000;
    }

    /** Chip de estado: texto e icono del color del estado sobre su fondo translúcido (parado: gris sobre superficie). */
    static void chip(TextView chip, LinkState.Level level, String text) {
        Context c = chip.getContext();
        int color = levelColor(c, level);
        int icon;
        switch (level) {
            case OK:
                icon = R.drawable.hql_st_ok;
                break;
            case BUSY:
                icon = R.drawable.hql_st_busy;
                break;
            case ERROR:
                icon = R.drawable.hql_st_error;
                break;
            default:
                icon = R.drawable.hql_st_idle;
        }
        chip.setText(text);
        chip.setTextColor(color);
        chip.setBackgroundTintList(ColorStateList.valueOf(level == LinkState.Level.IDLE
                ? c.getColor(R.color.hql_surface_top) : levelTint(color)));
        Drawable d = c.getDrawable(icon);
        if (d != null) {
            d = d.mutate();
            d.setTint(color);
        }
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null);
    }

    /** Punto de estado (p. ej. «En directo»). */
    static void dot(View dot, LinkState.Level level) {
        dot.setBackgroundTintList(ColorStateList.valueOf(level == LinkState.Level.IDLE
                ? dot.getContext().getColor(R.color.hql_idle) : levelColor(dot.getContext(), level)));
    }

    /** Modo ampliado: fotos y vídeos, y ubicación para los paneles Instrumentos y Eficiencia. */
    static final String[] MEDIA_PERMS = {Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};

    static boolean mediaPermsGranted(Context ctx) {
        for (String p : MEDIA_PERMS) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    static String modeTitle(String mode) {
        switch (mode) {
            case Config.MODE_AA:
                return Str.get(R.string.hql_mode_aa);
            case Config.MODE_AA_EXT:
                return Str.get(R.string.hql_mode_aa_ext);
            case Config.MODE_APP:
                return Str.get(R.string.hql_mode_app);
            default:
                return Str.get(R.string.hql_mode_pattern);
        }
    }

    static String modeDetail(String mode) {
        switch (mode) {
            case Config.MODE_AA:
                return Str.get(R.string.hql_mode_aa_detail);
            case Config.MODE_AA_EXT:
                return Str.get(R.string.hql_mode_aa_ext_detail);
            case Config.MODE_APP:
                return Str.get(R.string.hql_mode_app_detail);
            default:
                return Str.get(R.string.hql_mode_pattern_detail);
        }
    }

    static String appLabel(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(ctx.getPackageManager().getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Versión de Android Auto instalada, o null si no está. */
    static String aaVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(AaServerStarter.AA_PKG, 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    interface AppPicked {
        void onPicked(String pkg);
    }

    /** ActivityInfo.FLAG_ALLOW_EMBEDDED (oculta en el SDK). */
    private static final int FLAG_ALLOW_EMBEDDED = 0x80000000;

    /**
     * Si la actividad principal de la app declara android:allowEmbedded. Solo esas apps pueden
     * abrirse en el display virtual que enviamos al coche (Android lo exige a las apps de terceros).
     */
    static boolean allowsEmbedded(ResolveInfo ri) {
        return (ri.activityInfo.flags & FLAG_ALLOW_EMBEDDED) != 0;
    }

    /** Lista de apps con icono para elegir cuál se abre en el coche; primero las compatibles. */
    static void pickApp(Activity act, AppPicked cb) {
        PackageManager pm = act.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (!ri.activityInfo.packageName.equals(act.getPackageName())) apps.add(ri);
        }
        Collections.sort(apps, (a, b) -> {
            int e = Boolean.compare(allowsEmbedded(b), allowsEmbedded(a));
            return e != 0 ? e : String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
        });
        StringBuilder compat = new StringBuilder();
        for (ResolveInfo ri : apps) if (allowsEmbedded(ri)) compat.append(' ').append(ri.activityInfo.packageName);
        L.i("apps compatibles con el modo App (allowEmbedded):" + (compat.length() > 0 ? compat : " ninguna"));

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int i) {
                return apps.get(i);
            }

            @Override
            public long getItemId(int i) {
                return i;
            }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                if (v == null) v = LayoutInflater.from(act).inflate(R.layout.hql_app_row, parent, false);
                ResolveInfo ri = apps.get(i);
                Drawable icon = ri.loadIcon(pm);
                ((ImageView) v.findViewById(R.id.hql_app_icon)).setImageDrawable(icon);
                ((TextView) v.findViewById(R.id.hql_app_label)).setText(ri.loadLabel(pm));
                TextView tag = v.findViewById(R.id.hql_app_tag);
                tag.setVisibility(allowsEmbedded(ri) ? View.VISIBLE : View.GONE);
                v.setAlpha(allowsEmbedded(ri) ? 1f : 0.45f);
                return v;
            }
        };
        new MaterialAlertDialogBuilder(act)
                .setTitle(Str.get(R.string.hql_pick_app_title))
                .setAdapter(adapter, (d, which) -> cb.onPicked(apps.get(which).activityInfo.packageName))
                .setNegativeButton(Str.get(R.string.hql_cancel), null)
                .show();
    }
}
