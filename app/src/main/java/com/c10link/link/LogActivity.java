package com.c10link.link;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;

/** Diagnóstico: el log de C10Link en vivo, con la ruta del fichero y un botón para copiarlo. */
public class LogActivity extends Activity {
    private static final int MAX_LINES = 600;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView text;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        setContentView(R.layout.c10_activity_log);
        text = findViewById(R.id.c10_log_text);
        scroll = findViewById(R.id.c10_log_scroll);
        File f = L.currentFile();
        File base = getExternalFilesDir(null);
        ((TextView) findViewById(R.id.c10_log_file)).setText(
                "Registros en " + (base != null ? base.getAbsolutePath() : "") + "\n"
                        + "car/ diario del coche · perf/ rendimiento · logcat/ log completo · crash/ fallos");
        if (f != null) {
            try {
                List<String> all = Files.readAllLines(f.toPath());
                for (int i = Math.max(0, all.size() - MAX_LINES); i < all.size(); i++) lines.addLast(all.get(i));
            } catch (Exception ignored) {
            }
        }
        refresh();
        findViewById(R.id.c10_log_latency).setOnClickListener(v ->
                startActivity(new android.content.Intent(this, LatencyActivity.class)));
        findViewById(R.id.c10_log_copy).setOnClickListener(v -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("C10Link", String.join("\n", lines)));
            Toast.makeText(this, "Log copiado", Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        L.setListener(line -> runOnUiThread(() -> {
            lines.addLast(line);
            while (lines.size() > MAX_LINES) lines.removeFirst();
            refresh();
        }));
    }

    @Override
    protected void onPause() {
        L.setListener(null);
        super.onPause();
    }

    private void refresh() {
        text.setText(String.join("\n", lines));
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }
}
