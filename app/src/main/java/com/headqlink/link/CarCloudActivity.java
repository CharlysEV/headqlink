package com.headqlink.link;

import com.andrerinas.openheadunit.R;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.andrerinas.openheadunit.utils.ToastUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ⚙ › «Datos del coche (cuenta Leapmotor)»: opcional y de solo lectura. Explica qué hace, importa el certificado de
 * cliente (selector de ficheros del sistema: el par .crt + .key, un .pem con los dos o un .p12/.pfx con contraseña),
 * entra con el correo y la contraseña (que no se guarda), elige el coche y la variante de la batería, prueba la lectura
 * («Leer estado ahora») y borra todo («Cerrar sesión y borrar datos»).
 */
public class CarCloudActivity extends Activity {
    private static final int REQ_CERT = 41;

    /**
     * El .crt o el .key elegido a medias, esperando al otro: en memoria del proceso (sobrevive a que Android rehaga la
     * pantalla mientras el selector está abierto), nunca en disco.
     */
    private static final CertPick PENDING = new CertPick();

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Leer y guardar el certificado: aparte de la red, para que una lectura de la nube en curso no lo retrase. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private CarCloudStore store;
    private boolean busy;
    private boolean rendering;
    /** Leyendo o guardando el certificado elegido (desde importingSince, SystemClock.elapsedRealtime). */
    private boolean importing;
    private long importingSince;
    /** Una lectura que tarda más (un fichero en la nube sin red) ya no bloquea el botón: se puede volver a elegir. */
    private static final long IMPORT_STUCK_MS = 30_000;

    private MaterialSwitch enabled;
    private TextView certState;
    private MaterialButton certImport;
    private TextView certRestart;
    private View loginBox;
    private View sessionBox;
    private TextView session;
    private EditText email;
    private EditText password;
    private MaterialButton login;
    private MaterialButton logout;
    private TextView car;
    private MaterialButton chooseCar;
    private RadioGroup profile;
    private TextView profileNote;
    private MaterialButton test;
    private TextView result;
    private TextView status;
    private MaterialButton wipe;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        L.init(this);
        Str.init(this);
        setContentView(R.layout.hql_activity_cloud);
        if (!CarCloudStore.supported()) {
            findViewById(R.id.hql_cloud_unsupported).setVisibility(View.VISIBLE);
            findViewById(R.id.hql_cloud_body).setVisibility(View.GONE);
            return;
        }
        store = new CarCloudStore(this);
        enabled = findViewById(R.id.hql_cloud_enabled);
        certState = findViewById(R.id.hql_cloud_cert_state);
        certImport = findViewById(R.id.hql_cloud_cert_import);
        certRestart = findViewById(R.id.hql_cloud_cert_restart);
        loginBox = findViewById(R.id.hql_cloud_login_box);
        sessionBox = findViewById(R.id.hql_cloud_session_box);
        session = findViewById(R.id.hql_cloud_session);
        email = findViewById(R.id.hql_cloud_email);
        password = findViewById(R.id.hql_cloud_password);
        login = findViewById(R.id.hql_cloud_login);
        logout = findViewById(R.id.hql_cloud_logout);
        car = findViewById(R.id.hql_cloud_car);
        chooseCar = findViewById(R.id.hql_cloud_choose_car);
        profile = findViewById(R.id.hql_cloud_profile);
        profileNote = findViewById(R.id.hql_cloud_battery_note);
        test = findViewById(R.id.hql_cloud_test);
        result = findViewById(R.id.hql_cloud_result);
        status = findViewById(R.id.hql_cloud_status);
        wipe = findViewById(R.id.hql_cloud_wipe);

        enabled.setOnCheckedChangeListener((v, on) -> {
            if (rendering) return;
            store.setEnabled(on);
            L.i("nube Leapmotor: lectura " + (on ? "activada" : "desactivada"));
            CarCloud.settingsChanged();
        });
        certImport.setOnClickListener(v -> pickCertificate());
        certRestart.setOnClickListener(v -> restartPick());
        login.setOnClickListener(v -> doLogin());
        logout.setOnClickListener(v -> run(R.string.hql_cloud_test_failed, () -> CarCloudSession.logout(this), this::render));
        chooseCar.setOnClickListener(v -> chooseCar());
        profile.setOnCheckedChangeListener((g, id) -> {
            if (rendering) return;
            if (id == R.id.hql_cloud_profile_life) {
                setProfile(CarCloudStore.PROFILE_C10_LIFE, 0);
            } else if (id == R.id.hql_cloud_profile_promax) {
                setProfile(CarCloudStore.PROFILE_C10_PROMAX, 0);
            } else if (id == R.id.hql_cloud_profile_custom) {
                askCustomKwh();
            }
        });
        // «Otra» ya elegida: tocarla otra vez deja cambiar los kWh (un RadioButton marcado no avisa de nada).
        findViewById(R.id.hql_cloud_profile_custom).setOnClickListener(v -> {
            if (CarCloudStore.PROFILE_CUSTOM.equals(store.profile())) askCustomKwh();
        });
        test.setOnClickListener(v -> readNow());
        wipe.setOnClickListener(v -> confirmWipe());
        String saved = CarCloudSession.email(this);
        if (!saved.isEmpty()) email.setText(saved);
        render();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        io.shutdown();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ pantalla

    private void render() {
        if (store == null || isFinishing()) return;
        rendering = true;
        boolean hasCert = store.hasIdentity();
        boolean hasSession = store.hasSession();
        enabled.setChecked(store.enabled());
        String certText = Str.get(R.string.hql_cloud_cert_none);
        if (hasCert) {
            try {
                LeapTls.Identity id = store.identity();
                if (id == null) throw new IOException("certificado borrado");
                String cn = id.commonName();
                String until = Str.get(R.string.hql_cloud_cert_until, id.notAfter());
                certText = Str.get(R.string.hql_cloud_cert_ok, (cn.isEmpty() ? "" : "CN=" + cn + " · ") + until);
            } catch (IOException | GeneralSecurityException e) {
                certText = Str.get(R.string.hql_cloud_cert_err_unreadable);
            }
        }
        certState.setText(certText);
        // Con medio par elegido, el botón dice qué falta; el siguiente toque abre el selector para eso.
        CertPick.Step half = PENDING.state(SystemClock.elapsedRealtime());
        certImport.setText(half.kind == CertPick.Step.Kind.NEED_KEY ? R.string.hql_cloud_cert_pick_key
                : half.kind == CertPick.Step.Kind.NEED_CERT ? R.string.hql_cloud_cert_pick_cert
                : hasCert ? R.string.hql_cloud_cert_replace : R.string.hql_cloud_cert_import);
        certRestart.setVisibility(half.kind == CertPick.Step.Kind.NEED_KEY || half.kind == CertPick.Step.Kind.NEED_CERT
                ? View.VISIBLE : View.GONE);
        loginBox.setVisibility(hasSession ? View.GONE : View.VISIBLE);
        sessionBox.setVisibility(hasSession ? View.VISIBLE : View.GONE);
        session.setText(Str.get(R.string.hql_cloud_logged_in, store.maskedEmail()));
        String type = store.carType();
        String tail = hasSession ? CarCloudSession.vinTail(this) : "";
        car.setText(type.isEmpty() && tail.isEmpty() ? Str.get(R.string.hql_cloud_car_none)
                : (type.toUpperCase(Locale.ROOT) + " " + tail).trim());
        chooseCar.setEnabled(hasSession && !busy);
        String p = store.profile();
        int checked = CarCloudStore.PROFILE_C10_LIFE.equals(p) ? R.id.hql_cloud_profile_life
                : CarCloudStore.PROFILE_C10_PROMAX.equals(p) ? R.id.hql_cloud_profile_promax
                : CarCloudStore.PROFILE_CUSTOM.equals(p) ? R.id.hql_cloud_profile_custom : View.NO_ID;
        if (checked == View.NO_ID) profile.clearCheck();
        else profile.check(checked);
        ((TextView) findViewById(R.id.hql_cloud_profile_custom)).setText(CarCloudStore.PROFILE_CUSTOM.equals(p)
                ? Str.get(R.string.hql_cloud_profile_custom, String.format(Locale.getDefault(), "%.1f", store.customKwh()))
                : Str.get(R.string.hql_cloud_profile_custom_none));
        profileNote.setText(Str.get(R.string.hql_cloud_battery_note) + (p.isEmpty() ? "\n" + Str.get(R.string.hql_cloud_profile_unset) : ""));
        login.setEnabled(!busy);
        // Siempre activo: este estilo no cambia de aspecto al desactivarlo, y un botón «muerto» que parece vivo no dice
        // nada. Si está leyendo, el toque lo avisa (pickCertificate).
        certImport.setEnabled(true);
        test.setEnabled(hasCert && hasSession && !busy);
        test.setText(busy ? R.string.hql_cloud_working : R.string.hql_cloud_test);
        wipe.setEnabled(!busy && (hasCert || hasSession));
        // Con el modo extendido en marcha: qué está viendo el coche (el último dato o el problema).
        CarCloud.Snapshot snap = CarCloud.snapshot();
        long now = System.currentTimeMillis();
        String line = null;
        if (CarCloud.running() && !snap.demo) {
            String problem = CarStatusTab.problem(snap, now);
            line = problem != null ? problem : snap.hasData() ? CarCloud.realLabel(snap, now) : null;
        }
        status.setVisibility(line != null ? View.VISIBLE : View.GONE);
        if (line != null) status.setText(Str.get(R.string.hql_cloud_status_line, line));
        rendering = false;
    }

    private interface Job {
        void run() throws Exception;
    }

    /**
     * Trabajo de red o de disco fuera del hilo principal; después, done en el principal o, si falla, el error en pantalla
     * con failRes («No se pudo entrar: …», «No se pudo leer: …»).
     */
    private void run(int failRes, Job job, Runnable done) {
        if (busy) {
            L.i("nube Leapmotor (móvil): pulsado con otra petición en curso; espera");
            ToastUtils.showToast(this, R.string.hql_cloud_working, Toast.LENGTH_SHORT, true);
            return;
        }
        busy = true;
        render();
        worker.execute(() -> {
            Exception err = null;
            try {
                job.run();
            } catch (Exception e) {
                err = e;
            }
            Exception fe = err;
            main.post(() -> {
                busy = false;
                if (isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
                if (fe != null) {
                    onError(failRes, fe, () -> run(failRes, job, done));
                } else if (done != null) {
                    done.run();
                }
                render();
            });
        });
    }

    /** Un error para la pantalla (y el log, sin datos): si es la clave del servidor, se pregunta si confiar. */
    private void onError(int failRes, Exception e, Runnable retry) {
        if (e instanceof LeapHttps.ServerKeyChangedException) {
            askServerKey(((LeapHttps.ServerKeyChangedException) e).detail, retry);
            return;
        }
        String msg = describe(e);
        L.w("nube Leapmotor (móvil): " + CarCloud.safeError(e));
        showResult(Str.get(failRes, msg), true);
    }

    static String describe(Exception e) {
        if (e instanceof LeapApi.SessionExpiredException) return Str.get(R.string.hql_cloud_err_expired);
        if (e instanceof CarCloudSession.NotConfiguredException) return Str.get(R.string.hql_cloud_err_not_configured);
        if (e instanceof LeapApi.ApiException) return ((LeapApi.ApiException) e).serverMessage;
        if (e instanceof GeneralSecurityException) return Str.get(R.string.hql_cloud_err_p12, e.getClass().getSimpleName());
        if (e instanceof IOException) return Str.get(R.string.hql_cloud_err_network);
        return e.getClass().getSimpleName();
    }

    private void showResult(String text, boolean error) {
        result.setVisibility(View.VISIBLE);
        result.setText(text);
        result.setTextColor(androidx.core.content.ContextCompat.getColor(this, error ? R.color.hql_warn : R.color.hql_text));
        // El recuadro está al final de la pantalla: un error (p. ej. «Incorrect account or password» del login) no se veía.
        if (error) {
            ToastUtils.showToast(this, text, Toast.LENGTH_LONG, true);
            result.post(() -> {
                android.view.ViewParent v = result.getParent();
                while (v != null && !(v instanceof android.widget.ScrollView)) v = v.getParent();
                if (v != null) ((android.widget.ScrollView) v).smoothScrollTo(0, Math.max(0, result.getBottom()));
            });
        }
    }

    // ------------------------------------------------------------------ certificado

    /**
     * Tipos para el selector: todo (los .crt/.key no tienen un tipo fijo y cada gestor de ficheros les pone uno) y, por
     * si algún selector filtra, los que se suelen ver para certificados y claves.
     */
    static final String[] CERT_MIME_TYPES = {"*/*", "application/x-pem-file", "application/x-x509-ca-cert",
            "application/pkcs8", "application/x-pkcs12", "application/octet-stream", "text/plain"};

    private void pickCertificate() {
        if (importing) {
            long waited = SystemClock.elapsedRealtime() - importingSince;
            if (waited < IMPORT_STUCK_MS) {
                L.i("nube Leapmotor: importar certificado: pulsado mientras leo lo anterior; espera");
                ToastUtils.showToast(this, R.string.hql_cloud_cert_reading, Toast.LENGTH_SHORT, true);
                return;
            }
            L.w("nube Leapmotor: importar certificado: la lectura anterior no termina (" + waited / 1000
                    + " s); abro el selector igualmente");
        }
        CertPick.Step st = PENDING.state(SystemClock.elapsedRealtime());
        L.i("nube Leapmotor: importar certificado: abro el selector" + (st.kind == CertPick.Step.Kind.NOTHING ? ""
                : " (" + st + ")"));
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, CERT_MIME_TYPES)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_CERT);
        } catch (android.content.ActivityNotFoundException e) {
            L.w("nube Leapmotor: importar certificado: no hay selector de ficheros");
            importFailed(Str.get(R.string.hql_cloud_cert_err_no_picker));
        } catch (RuntimeException e) {
            L.w("nube Leapmotor: importar certificado: no se pudo abrir el selector (" + e.getClass().getSimpleName() + ")");
            importFailed(Str.get(R.string.hql_cloud_cert_err_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CERT) return;
        List<Uri> uris = new ArrayList<>();
        if (data != null && data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri u = data.getClipData().getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        } else if (data != null && data.getData() != null) {
            uris.add(data.getData());
        }
        L.i("nube Leapmotor: importar certificado: el selector devuelve " + (res == RESULT_OK ? "OK" : res == RESULT_CANCELED
                ? "cancelado" : "código " + res) + ", " + uris.size() + " fichero(s)");
        if (res != RESULT_OK || uris.isEmpty() || store == null) return;
        importing = true;
        importingSince = SystemClock.elapsedRealtime();
        render();
        // Se lee ya (el permiso del selector dura lo que esta pantalla) y en su propio hilo: un fichero que tarda (en la
        // nube) no deja colgado el resto de la pantalla.
        io.execute(() -> {
            List<LeapTls.Picked> files = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            for (Uri u : uris) {
                String name = displayName(u);
                try {
                    files.add(new LeapTls.Picked(name, readUri(u)));
                } catch (IOException | RuntimeException e) {
                    // SecurityException (sin permiso), FileNotFoundException, IllegalArgumentException…: solo el tipo.
                    failed.add(name);
                    L.w("nube Leapmotor: importar certificado: no se pudo leer «" + name + "» (" + e.getClass().getSimpleName() + ")");
                }
            }
            L.i("nube Leapmotor: importar certificado: leídos " + files.size() + " de " + uris.size()
                    + (files.isEmpty() ? "" : ": " + CertPick.names(files)));
            main.post(() -> {
                importing = false;
                if (gone()) {
                    CertPick.wipe(files);
                    return;
                }
                onPicked(files, failed);
            });
        });
    }

    /** Lo leído del selector: se junta con lo elegido antes (el .crt y el .key pueden llegar por separado). */
    private void onPicked(List<LeapTls.Picked> files, List<String> failed) {
        if (files.isEmpty()) {
            importFailed(Str.get(R.string.hql_cloud_cert_err_read, failed.isEmpty() ? "?" : failed.get(0)));
            render();
            return;
        }
        CertPick.Step step = PENDING.add(files, SystemClock.elapsedRealtime());
        L.i("nube Leapmotor: importar certificado: " + step + (step.unknown.isEmpty() || step.kind == CertPick.Step.Kind.UNREADABLE
                ? "" : " (no sirven: " + step.unknown + ")"));
        switch (step.kind) {
            case READY:
                importCertificate(step.files, null);
                break;
            case NEED_KEY:
                partialPicked(Str.get(R.string.hql_cloud_cert_have_cert, step.have));
                break;
            case NEED_CERT:
                partialPicked(Str.get(R.string.hql_cloud_cert_have_key, step.have));
                break;
            case UNREADABLE:
                importFailed(Str.get(R.string.hql_cloud_cert_err_unknown, step.unknown.isEmpty() ? "?" : step.unknown.get(0)));
                break;
            default:
                break;
        }
        render();
    }

    /** Medio par elegido: se dice qué falta y el siguiente toque en el botón abre el selector para eso. */
    private void partialPicked(String text) {
        showResult(text, false);
        ToastUtils.showToast(this, text, Toast.LENGTH_LONG, true);
    }

    /** Un error del certificado: en el recuadro de resultados y en un aviso (por si el recuadro queda fuera de la vista). */
    private void importFailed(String text) {
        showResult(text, true);
        ToastUtils.showToast(this, text, Toast.LENGTH_LONG, true);
    }

    /** «Empezar de nuevo»: se olvida el .crt o el .key elegido a medias. */
    private void restartPick() {
        PENDING.clear();
        L.i("nube Leapmotor: importar certificado: empiezo de nuevo (descarto lo elegido a medias)");
        result.setVisibility(View.GONE);
        render();
    }

    private boolean gone() {
        return isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed());
    }

    /** El nombre que enseña el selector (Mis archivos de Samsung, Archivos de Google, Descargas…), o el final del URI. */
    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String n = col >= 0 && !c.isNull(col) ? c.getString(col) : null;
                if (n != null && !n.trim().isEmpty()) return n.trim();
            }
        } catch (RuntimeException ignored) {
            // Algún proveedor no admite la consulta: queda el URI.
        }
        String last = u.getLastPathSegment();
        if (last == null) return "";
        int cut = Math.max(last.lastIndexOf('/'), last.lastIndexOf(':'));
        return cut >= 0 && cut < last.length() - 1 ? last.substring(cut + 1) : last;
    }

    private byte[] readUri(Uri u) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("sin contenido");
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > LeapTls.MAX_FILE_BYTES) break;
            }
            return o.toByteArray();
        }
    }

    /** Lee y guarda el certificado; si tiene contraseña, la pide y vuelve a probar. */
    private void importCertificate(List<LeapTls.Picked> files, char[] pw) {
        importing = true;
        importingSince = SystemClock.elapsedRealtime();
        render();
        io.execute(() -> {
            LeapTls.Identity got = null;
            LeapTls.ImportException.Kind kind = null;
            Exception other = null;
            try {
                got = LeapTls.parse(files, pw);
                store.saveIdentity(got);
            } catch (LeapTls.ImportException e) {
                kind = e.kind;
            } catch (Exception e) {
                other = e;
            } finally {
                if (pw != null) java.util.Arrays.fill(pw, '\0');
            }
            boolean ok = got != null && other == null;
            if (ok) {
                CarCloud.settingsChanged();
                L.i("nube Leapmotor: certificado de cliente importado (" + got.key.getAlgorithm() + ", " + files.size()
                        + " fichero(s))");
            } else if (kind != null) {
                L.w("nube Leapmotor: importar certificado: no válido (" + kind + "; " + CertPick.names(files) + ")");
            } else if (other != null) {
                L.w("nube Leapmotor: importar certificado: no se pudo guardar (" + CarCloud.safeError(other) + ")");
            }
            LeapTls.ImportException.Kind k = kind;
            Exception fe = other;
            main.post(() -> {
                importing = false;
                if (gone()) {
                    CertPick.wipe(files);
                    return;
                }
                if (ok) {
                    CertPick.wipe(files);
                    PENDING.clear();
                    ToastUtils.showToast(this, R.string.hql_cloud_cert_saved, Toast.LENGTH_SHORT, true);
                    result.setVisibility(View.GONE);
                } else if (k == LeapTls.ImportException.Kind.NEEDS_PASSWORD || k == LeapTls.ImportException.Kind.WRONG_PASSWORD) {
                    askPassword(files, k == LeapTls.ImportException.Kind.WRONG_PASSWORD);
                } else {
                    CertPick.wipe(files);
                    PENDING.clear();
                    importFailed(k != null ? importError(k)
                            : Str.get(R.string.hql_cloud_cert_err_save, fe != null ? fe.getClass().getSimpleName() : "?"));
                }
                render();
            });
        });
    }

    private void askPassword(List<LeapTls.Picked> files, boolean wrong) {
        L.i("nube Leapmotor: importar certificado: " + (wrong ? "contraseña incorrecta; la pido otra vez" : "tiene contraseña; la pido"));
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        in.setHint(R.string.hql_cloud_cert_password_hint);
        LinearLayout box = new LinearLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cloud_cert_password_title)
                .setMessage(wrong ? R.string.hql_cloud_cert_err_wrong_password : R.string.hql_cloud_cert_err_needs_password)
                .setView(box)
                .setPositiveButton(R.string.hql_save, (d, w) -> {
                    char[] pw = new char[in.length()];
                    in.getText().getChars(0, in.length(), pw, 0);
                    in.setText("");
                    importCertificate(files, pw);
                })
                .setNegativeButton(R.string.hql_cancel, (d, w) -> {
                    L.i("nube Leapmotor: importar certificado: contraseña cancelada");
                    CertPick.wipe(files);
                })
                .show();
    }

    static String importError(LeapTls.ImportException.Kind k) {
        switch (k) {
            case NEEDS_PASSWORD:
                return Str.get(R.string.hql_cloud_cert_err_needs_password);
            case WRONG_PASSWORD:
                return Str.get(R.string.hql_cloud_cert_err_wrong_password);
            case NO_KEY:
                return Str.get(R.string.hql_cloud_cert_err_no_key);
            case NO_CERT:
                return Str.get(R.string.hql_cloud_cert_err_no_cert);
            case MISMATCH:
                return Str.get(R.string.hql_cloud_cert_err_mismatch);
            case ENCRYPTED_KEY_UNSUPPORTED:
                return Str.get(R.string.hql_cloud_cert_err_encrypted);
            case TOO_BIG:
                return Str.get(R.string.hql_cloud_cert_err_too_big);
            default:
                return Str.get(R.string.hql_cloud_cert_err_unreadable);
        }
    }

    // ------------------------------------------------------------------ cuenta y coche

    private void doLogin() {
        if (!store.hasIdentity()) {
            showResult(Str.get(R.string.hql_cloud_need_cert_first), true);
            return;
        }
        String mail = email.getText() == null ? "" : email.getText().toString().trim();
        String pass = password.getText() == null ? "" : password.getText().toString();
        if (mail.isEmpty() || pass.isEmpty()) return;
        final List<LeapApi.Vehicle> cars = new ArrayList<>();
        run(R.string.hql_cloud_login_failed, () -> cars.addAll(CarCloudSession.login(this, mail, pass)), () -> {
            password.setText("");
            result.setVisibility(View.GONE);
            if (cars.isEmpty()) {
                showResult(Str.get(R.string.hql_cloud_no_cars), true);
            } else if (cars.size() > 1) {
                showCars(cars);
            } else {
                afterCarChosen();
            }
        });
    }

    private void chooseCar() {
        final List<LeapApi.Vehicle> cars = new ArrayList<>();
        run(R.string.hql_cloud_login_failed, () -> cars.addAll(CarCloudSession.vehicles(this)), () -> {
            if (cars.isEmpty()) showResult(Str.get(R.string.hql_cloud_no_cars), true);
            else showCars(cars);
        });
    }

    private void showCars(List<LeapApi.Vehicle> cars) {
        String[] names = new String[cars.size()];
        for (int i = 0; i < cars.size(); i++) {
            LeapApi.Vehicle v = cars.get(i);
            String tail = v.vin.length() >= 4 ? "…" + v.vin.substring(v.vin.length() - 4) : "";
            String nick = v.nickName == null || v.nickName.isEmpty() ? "" : " · " + v.nickName;
            names[i] = v.carType.toUpperCase(Locale.ROOT) + " " + tail + nick + (v.shared ? " (" + Str.get(R.string.hql_cloud_shared) + ")" : "");
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cloud_choose_car)
                .setItems(names, (d, which) -> run(R.string.hql_cloud_login_failed, () -> CarCloudSession.chooseVehicle(this, cars.get(which)),
                        this::afterCarChosen))
                .setNegativeButton(R.string.hql_cancel, null)
                .show();
    }

    /** Elegido el coche: si aún no hay variante de batería, se pregunta (la nube no la dice). */
    private void afterCarChosen() {
        if (store.profile().isEmpty()) {
            String[] names = {Str.get(R.string.hql_cloud_profile_life), Str.get(R.string.hql_cloud_profile_promax),
                    Str.get(R.string.hql_cloud_profile_custom_none)};
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.hql_cloud_step_battery)
                    .setItems(names, (d, which) -> {
                        if (which == 0) setProfile(CarCloudStore.PROFILE_C10_LIFE, 0);
                        else if (which == 1) setProfile(CarCloudStore.PROFILE_C10_PROMAX, 0);
                        else askCustomKwh();
                    })
                    .show();
        }
    }

    private void setProfile(String id, double kwh) {
        store.setProfile(id, kwh);
        L.i(String.format(Locale.US, "nube Leapmotor: batería %s (%.1f kWh)", id, store.capacityKwh()));
        CarCloud.settingsChanged();
        render();
    }

    private void askCustomKwh() {
        EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        double cur = store.customKwh();
        if (cur >= 10) in.setText(String.format(Locale.US, "%.1f", cur));
        LinearLayout box = new LinearLayout(this);
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 3, pad, 0);
        box.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cloud_profile_custom_title)
                .setView(box)
                .setPositiveButton(R.string.hql_save, (d, w) -> {
                    try {
                        double v = Double.parseDouble(in.getText().toString().trim().replace(',', '.'));
                        setProfile(CarCloudStore.PROFILE_CUSTOM, v);
                    } catch (NumberFormatException e) {
                        render();
                    }
                })
                .setNegativeButton(R.string.hql_cancel, (d, w) -> render())
                .setOnCancelListener(d -> render())
                .show();
    }

    // ------------------------------------------------------------------ prueba, clave del servidor y borrar

    private void readNow() {
        final LeapStatus[] got = new LeapStatus[1];
        final long[] lat = new long[1];
        run(R.string.hql_cloud_test_failed, () -> {
            long t0 = SystemClock.elapsedRealtime();
            got[0] = CarCloudSession.readStatus(this);
            lat[0] = SystemClock.elapsedRealtime() - t0;
            CarCloud.publish(this, got[0], lat[0]);
            L.i("nube Leapmotor: prueba desde el móvil: " + got[0].logLine() + " · " + lat[0] + " ms");
        }, () -> showResult(summary(got[0], lat[0]), false));
    }

    /** Lo leído, para comprobarlo: batería, autonomía, carga, ruedas y edad del dato. */
    private String summary(LeapStatus s, long latencyMs) {
        String charge = s.charging() ? Str.get(s.dcPlugged() ? R.string.hql_cloud_charging_dc : R.string.hql_cloud_charging_ac)
                : Boolean.TRUE.equals(s.chargeCompleted) && s.pluggedIn() ? Str.get(R.string.hql_cloud_charge_done)
                : s.pluggedIn() ? Str.get(R.string.hql_cloud_plugged) : Str.get(R.string.hql_cloud_unplugged);
        double kw = s.powerKw();
        if (!Double.isNaN(kw)) charge += String.format(Locale.getDefault(), " · %.1f kW", kw);
        String left = CarStatusTab.minutes(s.chargeRemainMin);
        if (s.charging() && !left.isEmpty()) charge += " · " + Str.get(R.string.hql_cloud_full_in, left);
        StringBuilder tyres = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) tyres.append(" · ");
            double bar = s.tyreBar(i);
            tyres.append(Double.isNaN(bar) ? "—" : String.format(Locale.getDefault(), "%.2f", bar));
            if (s.tyreWarning(i)) tyres.append(" (!)");
        }
        long now = System.currentTimeMillis();
        long t = s.carTimeMs > 0 ? s.carTimeMs : now;
        String when = CarCloud.ago(now - t) + (s.carTimeMs > 0 ? " (" + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(t)) + ")" : "");
        return Str.get(R.string.hql_cloud_test_result, fmt(s.socBest(), 1), fmt(s.rangeKm, 0), charge, tyres, when, latencyMs);
    }

    private static String fmt(double v, int decimals) {
        return Double.isNaN(v) ? "—" : String.format(Locale.getDefault(), decimals == 0 ? "%.0f" : "%.1f", v);
    }

    /** El servidor presenta una clave que no es la conocida: se enseña la huella y el usuario decide. */
    private void askServerKey(LeapTls.UnknownServerKeyException k, Runnable retry) {
        String until = DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(k.notAfterMs));
        L.w("nube Leapmotor: el servidor presenta otra clave (huella " + k.fingerprint + "); pregunto en el móvil");
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cloud_server_key_dialog_title)
                .setMessage(Str.get(R.string.hql_cloud_server_key_dialog, k.fingerprint, until))
                .setPositiveButton(R.string.hql_cloud_server_key_accept, (d, w) -> {
                    store.acceptPin(k.pin);
                    L.i("nube Leapmotor: clave del servidor aceptada por el usuario (huella " + k.fingerprint + ")");
                    CarCloud.settingsChanged();
                    if (retry != null) retry.run();
                })
                .setNegativeButton(R.string.hql_cancel, null)
                .show();
    }

    private void confirmWipe() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hql_cloud_wipe)
                .setMessage(R.string.hql_cloud_wipe_confirm)
                .setPositiveButton(R.string.hql_cloud_wipe, (d, w) -> run(R.string.hql_cloud_test_failed, () -> CarCloudSession.wipe(this), () -> {
                    email.setText("");
                    password.setText("");
                    PENDING.clear();
                    result.setVisibility(View.GONE);
                    ToastUtils.showToast(this, R.string.hql_cloud_wiped, Toast.LENGTH_SHORT, true);
                }))
                .setNegativeButton(R.string.hql_cancel, null)
                .show();
    }
}
