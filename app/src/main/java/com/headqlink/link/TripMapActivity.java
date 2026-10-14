package com.headqlink.link;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.andrerinas.openheadunit.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Un recorrido en el móvil (Viajes › Recorridos): su mapa (el mismo que en el coche: trazado y paradas) y, encima, la
 * fecha, km, tiempo, consumo, coste, desnivel y velocidad máxima.
 */
public class TripMapActivity extends Activity {
    static final String EXTRA_START = "start";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        long start = getIntent().getLongExtra(EXTRA_START, -1);
        TripLog.Trip trip = null;
        for (TripLog.Trip t : TripLog.recent(this, 200)) {
            if (t.startMs == start) {
                trip = t;
                break;
            }
        }
        if (trip == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0E14);
        root.setFitsSystemWindows(true);
        WebView w = new WebView(this);
        w.setBackgroundColor(0xFF17202B);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setUserAgentString(s.getUserAgentString() + " " + Http.USER_AGENT);
        w.setWebViewClient(new WebViewClient());
        List<TripStops.Stop> stops = TripStops.find(trip.track);
        w.loadDataWithBaseURL("file:///android_asset/", TripDetail.mapHtml(trip, stops), "text/html", "utf-8", null);
        root.addView(w, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        com.google.android.material.card.MaterialCardView frame = new com.google.android.material.card.MaterialCardView(this);
        frame.setCardBackgroundColor(getColor(R.color.hql_surface_hi));
        frame.setStrokeWidth(0);
        frame.setRadius(dp(12));
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        card.setPadding(pad, dp(12), pad, dp(12));
        frame.addView(card);
        TextView head = text(new SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.getDefault()).format(new Date(trip.startMs)), 15,
                PhoneCharts.ACCENT);
        head.setTypeface(PhoneCharts.MEDIUM);
        card.addView(head);
        List<CloudHistory.Trip> history = CarCloud.history();
        double per100 = TripStats.bestKwhPer100(trip, history);
        Config cfg = new Config(this);
        double cost = TripStats.cost(trip, history, cfg.electricityPrice(), cfg.fuelPrice());
        TextView big = text(String.format(Locale.getDefault(), "%.1f km · %s", trip.km, PhoneInsights.duration(trip.minutes)), 24,
                PhoneCharts.TEXT);
        big.setTypeface(PhoneCharts.MEDIUM);
        card.addView(big);
        StringBuilder line = new StringBuilder();
        if (!Double.isNaN(per100)) line.append(String.format(Locale.getDefault(), "%.1f kWh/100 km", per100));
        if (!Double.isNaN(cost) && cost > 0) line.append(" · ").append(Money.format(cost));
        line.append(String.format(Locale.getDefault(), " · ↑%.0f m ↓%.0f m", trip.climb, trip.descent));
        if (trip.maxKmh > 0) line.append(String.format(Locale.getDefault(), " · %s %.0f km/h", Str.get(R.string.hql_ins_max), trip.maxKmh));
        if (!stops.isEmpty()) line.append(" · ").append(Str.plural(R.plurals.hql_ins_stops, stops.size()));
        card.addView(text(line.toString(), 14, PhoneCharts.DIM));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        clp.setMargins(dp(12), dp(12), dp(12), 0);
        root.addView(frame, clp);
        setContentView(root);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private int dp(float d) {
        return Math.round(d * getResources().getDisplayMetrics().density);
    }
}
