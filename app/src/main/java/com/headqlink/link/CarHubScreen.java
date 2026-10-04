package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * Sección "Coche": pestañas Ruta, Conducción, Viajes, Instrumentos y Eficiencia, todo con datos del
 * móvil (GPS, sensores) y servicios abiertos, sin datos del coche.
 */
final class CarHubScreen implements CarScreen {
    private static final int[] TABS = {R.string.hql_tab_route, R.string.hql_tab_drive, R.string.hql_tab_trips, R.string.hql_tab_gauges, R.string.hql_tab_efficiency};
    private static int lastTab;

    /** Para UiPreview: pestaña con la que se abrirá la sección. */
    static void selectTab(int tab) {
        lastTab = Math.max(0, Math.min(TABS.length - 1, tab));
    }

    private Host host;
    private FrameLayout body;
    private CarScreen current;

    @Override
    public View create(Host h) {
        host = h;
        Context c = h.context();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        String[] names = new String[TABS.length];
        for (int i = 0; i < TABS.length; i++) names[i] = Str.get(TABS[i]);
        col.addView(CarStyle.tabs(c, names, lastTab, this::open));
        body = new FrameLayout(c);
        col.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        open(lastTab);
        return col;
    }

    private void open(int tab) {
        lastTab = tab;
        if (current != null) current.destroy();
        body.removeAllViews();
        switch (tab) {
            case 0:
                current = new RouteTab();
                break;
            case 1:
                current = new DriveTab();
                break;
            case 2:
                current = new TripsTab();
                break;
            case 3:
                current = new InstrumentsScreen();
                break;
            default:
                current = new EfficiencyScreen();
        }
        body.addView(current.create(sub), CarStyle.match());
    }

    @Override
    public void destroy() {
        if (current != null) current.destroy();
        current = null;
    }

    /** Las pestañas trabajan dentro del cuerpo, debajo de la fila de pestañas. */
    private final Host sub = new Host() {
        @Override
        public Context context() {
            return host.context();
        }

        @Override
        public void setContent(View v) {
            body.removeAllViews();
            body.addView(v, CarStyle.match());
        }

        @Override
        public int width() {
            return host.width();
        }

        @Override
        public int height() {
            return host.height() - 90;
        }

        @Override
        public void post(Runnable r) {
            host.post(r);
        }

        @Override
        public void setFullscreen(boolean on) {
            host.setFullscreen(on);
        }
    };
}
