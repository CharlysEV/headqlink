package com.headqlink.link

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.andrerinas.openheadunit.R
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * «Exportar log» de Diagnóstico (qdauto §7.5): vuelca a disco los registros, elige los ficheros (los más nuevos, con un
 * tope de [MAX_TOTAL_BYTES] sin comprimir; sin `trips/`, que lleva GPS, ni capturas) y escribe
 * `HeadQLink-log-AAAAMMDD-HHMMSS.zip` con `resumen.txt` delante: en Android 10+, en Descargas/HeadQLink (MediaStore, sin
 * permisos); antes, en `exports/` de la carpeta de la app ([Exported.where] dice dónde). Nada se envía solo: la hoja de
 * compartir la abre el usuario.
 */
internal object HqlLogExport {
    const val RELATIVE_DIR = "HeadQLink"
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /** [where]: dónde quedó, para el aviso (Descargas/HeadQLink/… o la ruta en la carpeta de la app). */
    class Exported(val uri: Uri, val displayName: String, val bytes: Long, val files: Int, val where: String)

    /** Qué entra en el ZIP (nombre dentro del ZIP → fichero) y qué se omite (con el motivo). */
    class Selection(val included: List<Pair<String, File>>, val skipped: List<String>) {
        val totalBytes: Long get() = included.sumOf { it.second.length() }
    }

    /**
     * Selección pura (la prueban los tests): en este orden, `logs/` completo (con `sessions.csv`), `car/` (10 más
     * nuevos), `perf/` (20), `logcat/` (3), `crash/` (todos) y `headqlink-*.log` (10), cada grupo del más nuevo al más
     * viejo, sin pasar de [maxBytes]. `trips/` y `ui-preview-*` nunca entran.
     */
    fun select(base: File, maxBytes: Long = MAX_TOTAL_BYTES): Selection {
        val included = ArrayList<Pair<String, File>>()
        val skipped = ArrayList<String>()
        var total = 0L
        fun newest(dir: File, filter: (File) -> Boolean): List<File> =
            (dir.listFiles { f -> f.isFile && filter(f) } ?: emptyArray()).sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })

        fun add(prefix: String, files: List<File>, limit: Int) {
            for ((i, f) in files.withIndex()) {
                val name = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
                when {
                    i >= limit -> skipped += "$name (fuera de los $limit más nuevos)"
                    total + f.length() > maxBytes -> skipped += "$name (${f.length()} B: pasaría de ${maxBytes / (1024 * 1024)} MiB)"
                    else -> {
                        included += name to f
                        total += f.length()
                    }
                }
            }
        }
        add("logs", newest(File(base, "logs")) { it.name.endsWith(".log") || it.name.endsWith(".csv") }, Int.MAX_VALUE)
        add("car", newest(File(base, "car")) { true }, 10)
        add("perf", newest(File(base, "perf")) { true }, 20)
        add("logcat", newest(File(base, "logcat")) { true }, 3)
        add("crash", newest(File(base, "crash")) { true }, Int.MAX_VALUE)
        add("", newest(base) { it.name.startsWith("headqlink-") && it.name.endsWith(".log") }, 10)
        if (File(base, "trips").isDirectory) skipped += "trips/ (viajes con GPS: no se exportan)"
        val previews = base.listFiles { f -> f.name.startsWith("ui-preview-") }?.size ?: 0
        if (previews > 0) skipped += "ui-preview-* ($previews capturas: no se exportan)"
        return Selection(included, skipped)
    }

    /** Bloquea: llamar fuera del hilo principal. */
    @Throws(IOException::class)
    fun export(ctx: Context): Exported {
        // 1. Todo a disco.
        L.flush()
        CarTrace.flush()
        PerfTrace.flush()
        QdTrace.flush(3_000)
        val base = ctx.getExternalFilesDir(null) ?: throw IOException("sin almacenamiento de la app")
        val sel = select(base)
        val name = "HeadQLink-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val summary = summary(ctx, base, sel)
        val result = if (Build.VERSION.SDK_INT >= 29) exportMediaStore(ctx, name, sel, summary) else exportPrivate(ctx, name, sel, summary)
        L.i("log exportado a ${result.where}: ${sel.included.size} ficheros, ${sel.totalBytes} B sin comprimir")
        return result
    }

    /** Hoja de compartir del ZIP. */
    fun shareIntent(e: Exported): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, e.uri)
            putExtra(Intent.EXTRA_SUBJECT, "HeadQLink: ${e.displayName}")
            clipData = ClipData.newRawUri(e.displayName, e.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, Str.get(R.string.hql_export_share))
    }

    private fun exportMediaStore(ctx: Context, name: String, sel: Selection, summary: String): Exported {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore no creó el fichero en Descargas")
        try {
            val written = (resolver.openOutputStream(uri) ?: throw IOException("no se pudo abrir $uri")).use { writeZip(it, sel, summary) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Exported(uri, name, written, sel.included.size, Str.get(R.string.hql_export_downloads, "$RELATIVE_DIR/$name"))
        } catch (e: Exception) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw if (e is IOException) e else IOException("error exportando: $e", e)
        }
    }

    /** Android 9 o menos: sin MediaStore de Descargas; queda en la carpeta de la app y se comparte con FileProvider. */
    private fun exportPrivate(ctx: Context, name: String, sel: Selection, summary: String): Exported {
        val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
        val f = File(dir, name)
        val written = f.outputStream().use { writeZip(it, sel, summary) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
        return Exported(uri, name, written, sel.included.size, f.absolutePath)
    }

    /** ZIP con `resumen.txt` primero y después los ficheros elegidos. Devuelve los bytes sin comprimir. */
    private fun writeZip(out: OutputStream, sel: Selection, summary: String): Long {
        var total = 0L
        ZipOutputStream(out.buffered(64 * 1024)).use { zip ->
            val s = summary.toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("resumen.txt"))
            zip.write(s)
            zip.closeEntry()
            total += s.size
            for ((entry, f) in sel.included) {
                try {
                    zip.putNextEntry(ZipEntry(entry).apply { time = f.lastModified() })
                    f.inputStream().use { total += it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                } catch (e: IOException) {
                    // Un fichero que desaparece (rotación) no tira la exportación entera.
                    L.w("exportar log: $entry omitido (${e.message})")
                }
            }
        }
        return total
    }

    private fun summary(ctx: Context, base: File, sel: Selection): String = buildString {
        val cfg = Config(ctx)
        append("HeadQLink · exportación del log · ").append(Date()).append('\n')
        append("versión ").append(com.andrerinas.openheadunit.BuildConfig.VERSION_NAME).append(" (")
            .append(com.andrerinas.openheadunit.BuildConfig.GIT_SHA).append(") · ").append(Build.MANUFACTURER).append(' ')
            .append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n")
        append("ajustes: ").append(cfg.summary()).append('\n')
        // La del servicio en marcha (lo cambiado en marcha se aplica al volver a conectar).
        append("conexión: ").append(Ui.linkTitle(LinkState.linkModeFor(cfg))).append(" · motor ").append(LinkState.engineFor(cfg))
        if (LinkState.transportChangePending(cfg)) {
            append(" (configurado para la próxima conexión: ").append(Ui.linkTitle(cfg.linkMode())).append(" · motor ")
                .append(cfg.linkEngine()).append(')')
        }
        append("\n\n")
        append("estado: servicio ").append(if (LinkState.running) "en marcha" else "parado").append(" · coche ").append(LinkState.car)
        if (LinkState.carDetail.isNotEmpty()) append(" (").append(LinkState.carDetail).append(')')
        append(" · red ").append(LinkState.network.ifEmpty { "—" }).append(" · imagen ").append(LinkState.video.ifEmpty { "—" })
        if (LinkState.linkDetail.isNotEmpty()) append(" · sesión ").append(LinkState.linkDetail)
        append("\ninterfaces: ").append(NetIfaces.describe(NetIfaces.scan())).append("\n\n")
        val rows = SessionSummary.lastRows(File(base, "logs"), 20)
        append("últimas sesiones (").append(rows.size).append("):\n").append(SessionSummary.CSV_HEADER).append('\n')
        rows.forEach { append(it).append('\n') }
        append("\nficheros incluidos (").append(sel.included.size).append(", ").append(sel.totalBytes).append(" B sin comprimir):\n")
        sel.included.forEach { append("  ").append(it.first).append(" (").append(it.second.length()).append(" B)\n") }
        if (sel.skipped.isNotEmpty()) {
            append("omitidos:\n")
            sel.skipped.forEach { append("  ").append(it).append('\n') }
        }
        append("\nPrivacidad: los registros llevan identificadores del coche (CarUUID, ProjectID), IPs y nombres de red. ")
            .append("Ubicaciones: trips/ no se exporta y los registros no guardan coordenadas ni destinos (los escritos ")
            .append("por versiones anteriores a este aviso sí podían llevar alguno).\n")
    }
}
