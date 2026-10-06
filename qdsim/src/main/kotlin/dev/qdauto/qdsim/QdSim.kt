package dev.qdauto.qdsim

import dev.qdauto.core.json.JsonObject
import dev.qdauto.core.session.CarReturn
import dev.qdauto.core.sim.CarInfoValues
import dev.qdauto.core.sim.CarSim
import dev.qdauto.core.sim.CarSimConfig
import dev.qdauto.core.sim.CarSimListener
import dev.qdauto.core.sim.CarSimState
import dev.qdauto.core.sim.ReceiverHang
import dev.qdauto.core.sim.VideoArgsValues
import dev.qdauto.core.sim.VideoFrameInfo
import dev.qdauto.core.sim.VideoKind
import dev.qdauto.core.util.LogLevel
import dev.qdauto.core.util.QdLog
import dev.qdauto.core.wire.BroadcastAck
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.TouchPointer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * `:qdsim`: el coche simulado (CarSim de `:qdcore`) contra el móvil con HeadQLink, con los escenarios del plan de pruebas
 * (qdauto §9.3). El PC tiene que estar en la misma red que el móvil (p. ej. unido a su zona Wi-Fi) y permitir a java.exe
 * UDP 18464 y TCP entrantes. Termina con código 0 si todo pasa.
 *
 * ```
 * .\gradlew.bat :qdsim:run --args="--scenario reconnect --sessions 10 --duration 20 --gap-ms 200"
 * ```
 */
fun main(args: Array<String>) {
    val o = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("qdsim: ${e.message}\n")
        System.err.println(Options.USAGE)
        exitProcess(2)
    }
    if (o.help) {
        println(Options.USAGE)
        return
    }
    val ok = QdSim(o).run()
    exitProcess(if (ok) 0 else 1)
}

class Options(
    val scenario: String,
    val target: InetAddress,
    val sessions: Int,
    val durationS: Int,
    val gapMs: Long,
    val stallS: Int,
    val width: Int,
    val height: Int,
    val carWidth: Int,
    val carHeight: Int,
    val fps: Int,
    val bitrate: Int,
    val gop: Int,
    val uuid: String,
    val name: String,
    val broadcastMs: Long,
    val out: String?,
    val verbose: Boolean,
    val help: Boolean,
    /** Arrancar también un móvil de prueba en este proceso (127.0.0.1) para probar qdsim sin el teléfono. */
    val localPhone: Boolean = false,
    /** Manía 1 del C10: límite del receptor en bytes (0 = no colgarse). */
    val limitBytes: Int = CarSimConfig.C10_RECEIVER_LIMIT_BYTES,
    /** Cuánto deja de leer el coche simulado antes de cerrar. */
    val hangMs: Long = 10_000,
    /** Manía 2 del C10: evaluar `sps_repetido`. */
    val spsCheck: Boolean = true,
    /** Pasar el vídeo de cada sesión por ffmpeg al acabar. */
    val decode: Boolean = false,
    val ffmpeg: File? = null,
    /** `caida`: variantes de la vuelta del coche (a, b, c) en este orden. */
    val variants: String = "abc",
    /** `caida`: duración del corte de radio (s). */
    val radioS: Int = 12,
) {
    /** `estricto`: los WARN cuentan como FAIL y las manías no se pueden desactivar. */
    val strict: Boolean get() = scenario == "estricto"

    companion object {
        val SCENARIOS = listOf("normal", "estricto", "reconnect", "stall", "silent", "disconnect", "rack", "caida")

        val USAGE = """
            Uso: qdsim --scenario <escenario> [opciones]
              escenarios: ${SCENARIOS.joinToString(", ")}
                normal      1 sesión con guion táctil (toque, arrastre, pellizco, KEY_FRAME_REQ)
                estricto    como normal, con todas las manías del C10: límite de 512 KiB, SPS/PPS repetidos y ffmpeg
                            (--decode) si está; los WARN cuentan como FAIL y --no-* no se admite
                reconnect   N sesiones; el coche cierra (FIN) y se reanuncia a los --gap-ms (S1 pide modo noche)
                stall       vídeo, silencio y sin leer --stall-s, cierre y nuevo anuncio
                silent      silencio y sin leer, sin cerrar; a los 2 s otro arranque del coche se anuncia (relevo)
                disconnect  DISCONNECT_REQ: espera DISCONNECT_RSP{CanDisconnect:1}, el cierre y la reconexión
                rack        el coche ignora el primer ACK: el segundo debe llegar ≥ 400 ms después
                caida       corte de radio de --radio-s (12) s: el coche ni habla ni lee y el móvil cierra la sesión;
                            después vuelve (cada variante de --variant, por defecto abc) y tiene que reconectar en < 10 s:
                              a  directo al puerto de antes, sin anunciarse ni esperar ACK
                              b  se anuncia una sola vez y pierde ese ACK: necesita un reenvío
                              c  no se anuncia: solo hace caso a un ACK no pedido del móvil
              En todos los escenarios se comprueban las manías del C10 vistas el 2026-10-05 (activas por defecto):
                tamano_mensaje  el receptor del coche se cuelga con un mensaje de vídeo (48 B + payload) de más de
                                512 KiB: el coche simulado deja de leer --hang s (manda heartbeats) y cierra → FAIL;
                                WARN si alguno pasa de 480 KiB (el teléfono tiene que recortar a 480 KiB)
                sps_repetido    el coche reinicia el decodificador con cada SPS/PPS: solo valen el primero y los que
                                preceden a un IDR pedido con KEY_FRAME_REQ (WARN si el IDR no se pidió, FAIL si no
                                hay IDR detrás); se informa del número y los intervalos
                decodifica      con --decode: ffmpeg (-f h264 -i - -f null -) decodifica el vídeo sin errores
              --limit KIB          límite del receptor del coche (512); 0 o --no-limit = no colgarse
              --hang S             segundos sin leer antes de cerrar al colgarse (10)
              --no-sps-check       no evaluar sps_repetido
              --no-quirks          --no-limit y --no-sps-check
              --decode             pasar el vídeo de cada sesión por ffmpeg (SKIP si no se encuentra ffmpeg)
              --ffmpeg RUTA        ejecutable de ffmpeg (implica --decode); por defecto tools\ffmpeg\...\ffmpeg.exe o el PATH
              --target IP          destino del broadcast (por defecto 255.255.255.255; o la IP del móvil)
              --sessions N         sesiones del escenario reconnect (10)
              --duration S         segundos de vídeo por sesión (20; normal: 30)
              --gap-ms MS          espera entre cierre y nuevo anuncio (200)
              --stall-s S          duración del corte simulado (5)
              --radio-s S          caida: duración del corte de radio (12; el móvil corta a los ~10 s)
              --variant LETRAS     caida: variantes a ejecutar, p. ej. a, bc o abc (abc)
              --width/--height     VIDEO_ARGS (1920x882)   --car-width/--car-height  CAR_INFO (1920x882)
              --fps/--bitrate/--gop  VIDEO_ARGS (30, 5080320, 3)
              --uuid/--name        DeviceUUID/DeviceName del broadcast (QDSIM-C10 / LeapMotor-QDSIM)
              --broadcast-ms MS    periodo del broadcast (500, como el C10)
              --out PREFIJO        guardar el vídeo de cada sesión en PREFIJO-sN.h264 (ffplay -f h264)
              --verbose            log completo del simulador
              --local-phone        prueba sin teléfono: un móvil de mentira (el núcleo con los ajustes del fork) en
                                   este mismo proceso, en 127.0.0.1
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            val m = HashMap<String, String>()
            var help = false
            var verbose = false
            var local = false
            var noLimit = false
            var noSps = false
            var decode = false
            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--help", "-h" -> help = true
                    "--verbose", "-v" -> verbose = true
                    "--local-phone" -> local = true
                    "--no-limit" -> noLimit = true
                    "--no-sps-check" -> noSps = true
                    "--no-quirks" -> {
                        noLimit = true
                        noSps = true
                    }
                    "--decode" -> decode = true
                    else -> {
                        require(a.startsWith("--")) { "argumento inesperado: $a" }
                        require(i + 1 < args.size) { "falta el valor de $a" }
                        m[a.removePrefix("--")] = args[i + 1]
                        i++
                    }
                }
                i++
            }
            val scenario = m["scenario"] ?: if (help) "normal" else throw IllegalArgumentException("falta --scenario")
            require(scenario in SCENARIOS) { "escenario desconocido: $scenario" }
            fun int(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
            val strict = scenario == "estricto"
            require(!strict || (!noLimit && !noSps && (m["limit"]?.toIntOrNull() ?: 1) > 0)) { "el escenario estricto no admite --no-limit, --no-sps-check, --no-quirks ni --limit 0" }
            val limitKiB = m["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: throw IllegalArgumentException("--limit espera KiB: $it") }
            val hangS = m["hang"]?.let { it.replace(',', '.').toDoubleOrNull()?.takeIf { s -> s > 0 } ?: throw IllegalArgumentException("--hang espera segundos: $it") }
            val ffmpeg = m["ffmpeg"]?.let { File(it) }
            val variants = (m["variant"] ?: "abc").lowercase(Locale.ROOT)
            require(variants.isNotEmpty() && variants.all { it in "abc" }) { "--variant espera letras a, b o c: $variants" }
            return Options(
                scenario = scenario,
                target = InetAddress.getByName(m["target"] ?: if (local) "127.0.0.1" else "255.255.255.255"),
                sessions = int("sessions", 10).coerceAtLeast(1),
                durationS = int("duration", if (scenario == "normal") 30 else 20).coerceAtLeast(3),
                gapMs = (m["gap-ms"]?.toLongOrNull() ?: 200L).coerceAtLeast(0),
                stallS = int("stall-s", 5).coerceAtLeast(1),
                width = int("width", 1920),
                height = int("height", 882),
                carWidth = int("car-width", 1920),
                carHeight = int("car-height", 882),
                fps = int("fps", 30),
                bitrate = int("bitrate", 5_080_320),
                gop = int("gop", 3),
                uuid = m["uuid"] ?: "QDSIM-C10",
                name = m["name"] ?: "LeapMotor-QDSIM",
                broadcastMs = m["broadcast-ms"]?.toLongOrNull() ?: 500L,
                out = m["out"],
                verbose = verbose,
                help = help,
                localPhone = local,
                limitBytes = if (noLimit) 0 else (limitKiB?.let { it * 1024 } ?: CarSimConfig.C10_RECEIVER_LIMIT_BYTES),
                hangMs = hangS?.let { (it * 1000).toLong() } ?: 10_000L,
                spsCheck = !noSps,
                decode = decode || ffmpeg != null || strict,
                ffmpeg = ffmpeg,
                variants = variants,
                radioS = int("radio-s", 12).coerceAtLeast(1),
            )
        }
    }
}

/** Lo que ve un coche simulado: cuándo llega cada cosa del móvil. */
private class Probe(private val t0: Long) : CarSimListener {
    @Volatile var ackMs = -1L
    @Volatile var streamingMs = -1L
    @Volatile var firstConfigMs = -1L
    @Volatile var firstIdrMs = -1L
    @Volatile var closedMs = -1L
    @Volatile var closeReason = ""
    @Volatile var disconnectRsp: ControlMessage? = null
    @Volatile var configBeforeIdr = false
    @Volatile var ackFrom: InetSocketAddress? = null
    @Volatile var mirrorPort = -1
    @Volatile var hangMs = -1L

    private fun now() = (System.nanoTime() - t0) / 1_000_000

    override fun onReceiverHang(hang: ReceiverHang) {
        if (hangMs < 0) hangMs = now()
    }

    override fun onAck(ack: BroadcastAck, from: InetSocketAddress) {
        ackMs = now()
        ackFrom = from
        mirrorPort = ack.mirrorPort
    }

    override fun onStateChanged(state: CarSimState) {
        if (state == CarSimState.STREAMING && streamingMs < 0) streamingMs = now()
    }

    override fun onVideoFrame(info: VideoFrameInfo) {
        if (info.kind == VideoKind.CONFIG && firstConfigMs < 0) firstConfigMs = now()
        if (info.kind == VideoKind.IDR && firstIdrMs < 0) {
            firstIdrMs = now()
            configBeforeIdr = firstConfigMs in 0..firstIdrMs
        }
    }

    override fun onPhoneControl(message: ControlMessage) {
        if (message.cmd == Cmd.DISCONNECT_RSP) disconnectRsp = message
    }

    override fun onClosed(reason: String) {
        closedMs = now()
        closeReason = reason
    }
}

class QdSim(private val o: Options) {
    private val log = QdLog.stdout(if (o.verbose) LogLevel.DEBUG else LogLevel.WARN)
    private val results = ArrayList<String>()
    private var localPhone: LocalPhone? = null
    private var failures = 0
    private var sessionNo = 0

    private fun stamp() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun say(msg: String) = println("${stamp()} $msg")

    private fun check(ok: Boolean, what: String) {
        val line = (if (ok) "PASS " else "FAIL ") + what
        if (!ok) failures++
        results += line
        say(line)
    }

    /** Las manías del C10: WARN no cuenta como fallo salvo en `estricto`; SKIP nunca. */
    private fun check(v: Verdict, label: String) {
        val level = if (v.level == Level.WARN && o.strict) Level.FAIL else v.level
        val line = "${level.name.padEnd(4)} $label: ${v.text}"
        if (level == Level.FAIL) failures++
        results += line
        say(line)
    }

    /** Grabación de la sesión en curso (la de `--out`, o una temporal si hay que decodificar). */
    private var recording: File? = null
    private var recordingIsTemp = false

    private fun recordingFor(session: Int): File? {
        val f = o.out?.let { File("$it-s$session.h264") } ?: if (o.decode) File.createTempFile("qdsim-s$session-", ".h264") else null
        recordingIsTemp = o.out == null && f != null
        recording = f
        return f
    }

    /** Cómo vuelve el coche ([caida]): broadcasts como mucho y, si > 0, directo a ese puerto de [directHost]. */
    private class Comeback(val maxBroadcasts: Int = Int.MAX_VALUE, val directPort: Int = 0, val directHost: String? = null)

    private fun config(ignoreAcks: Int = 0, comeback: Comeback = Comeback()) = CarSimConfig(
        broadcastAddress = o.target,
        broadcastIntervalMs = o.broadcastMs,
        discoveryTimeoutMs = 120_000,
        deviceUuid = o.uuid,
        deviceName = o.name,
        carInfo = CarInfoValues(
            version = "4.9", carType = "2D4", platform = 0, platformVersion = "", carWidth = o.carWidth, carHeight = o.carHeight,
            carFactory = "018", huFactory = "119", mirrorTypeReq = 2, projectId = "00000000000000000000000000000000",
            carUuid = "LPCOU000000000000000000000QDSIM", legalAppWatch = 1,
        ),
        videoArgs = VideoArgsValues(o.width, o.height, 3, o.fps, o.bitrate, o.gop),
        heartbeatPeriodMs = 3_000,
        // El tamaño depende del perfil de imagen del móvil (completo o 720p): se informa, no se valida.
        expectedWidth = 0,
        expectedHeight = 0,
        recordVideoTo = recordingFor(sessionNo + 1),
        ignoreAcks = ignoreAcks,
        receiverLimitBytes = o.limitBytes,
        receiverHangMs = o.hangMs,
        maxBroadcasts = comeback.maxBroadcasts,
        directMirrorPort = comeback.directPort,
        connectHost = comeback.directHost,
    )

    /** Arranca un coche y espera a que el móvil le mande vídeo. */
    private fun startCar(ignoreAcks: Int = 0, comeback: Comeback = Comeback()): Pair<CarSim, Probe> {
        val t0 = System.nanoTime()
        val probe = Probe(t0)
        val sim = CarSim(config(ignoreAcks, comeback), probe, log).start()
        sessionNo++
        when {
            comeback.directPort > 0 ->
                say("S$sessionNo: coche de vuelta, directo a ${comeback.directHost ?: o.target.hostAddress}:${comeback.directPort} sin anunciarse")
            comeback.maxBroadcasts == 0 -> say("S$sessionNo: coche de vuelta, sin anunciarse: espera un ACK no pedido en UDP 18464")
            comeback.maxBroadcasts < Int.MAX_VALUE ->
                say("S$sessionNo: coche de vuelta, se anuncia ${comeback.maxBroadcasts} vez a ${o.target.hostAddress}:18463" + if (ignoreAcks > 0) " y pierde $ignoreAcks ACK" else "")
            else -> say("S$sessionNo: coche anunciándose a ${o.target.hostAddress}:18463 cada ${o.broadcastMs} ms")
        }
        return sim to probe
    }

    /** Espera vídeo y comprueba handshake, SPS/PPS antes del IDR y el tiempo hasta el IDR. */
    private fun expectVideo(sim: CarSim, p: Probe, idrBudgetMs: Long?, label: String): Boolean {
        if (!sim.awaitState(CarSimState.STREAMING, 60_000)) {
            check(false, "$label: sin VIDEO_CTRL{1} en 60 s (${sim.report().closeReason ?: "sin cierre"})")
            return false
        }
        val deadline = System.currentTimeMillis() + 15_000
        while (p.firstIdrMs < 0 && System.currentTimeMillis() < deadline && sim.currentState != CarSimState.CLOSED) Thread.sleep(10)
        val r = sim.report()
        val idrAfter = if (p.firstIdrMs >= 0 && p.streamingMs >= 0) p.firstIdrMs - p.streamingMs else -1
        say(
            "$label: ACK +${p.ackMs} ms (${p.ackFrom}) · VIDEO_CTRL{1} +${p.streamingMs} ms · SPS/PPS +${p.firstConfigMs} ms · " +
                "IDR +${p.firstIdrMs} ms (${idrAfter} ms tras VIDEO_CTRL) · cabecera ${r.lastVideoHeader?.params}",
        )
        var ok = true
        if (r.appStatusErrors.isNotEmpty() || r.appStatusReceived == 0) {
            check(false, "$label: AppStatus ${r.appStatusReceived} ${r.appStatusErrors}")
            ok = false
        }
        if (r.handshakeErrors.isNotEmpty()) {
            check(false, "$label: handshake ${r.handshakeErrors}")
            ok = false
        }
        if (p.firstIdrMs < 0) {
            check(false, "$label: sin IDR en 15 s tras VIDEO_CTRL{1}")
            return false
        }
        if (!p.configBeforeIdr) {
            check(false, "$label: el primer IDR llegó sin SPS/PPS delante")
            ok = false
        }
        if (idrBudgetMs != null && idrAfter > idrBudgetMs) {
            check(false, "$label: IDR a los $idrAfter ms de VIDEO_CTRL{1} (objetivo ≤ $idrBudgetMs ms)")
            ok = false
        }
        return ok
    }

    private fun play(sim: CarSim, seconds: Int) {
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end && sim.currentState != CarSimState.CLOSED) Thread.sleep(100)
    }

    private fun finish(sim: CarSim, p: Probe, label: String) {
        val r = sim.report()
        say("$label: ${r.videoMessages} mensajes de vídeo (IDR ${r.idrFrames}, P ${r.pFrames}, config ${r.codecConfigMessages}) · " +
            "errores ${r.videoErrorCount} · heartbeats del móvil ${r.phoneHeartbeats}")
        if (r.videoErrorCount > 0) check(false, "$label: vídeo con errores ${r.videoErrors.take(3)}")
        quirks(sim, p, label)
        sim.close()
        sim.awaitTermination(5_000)
        decode(label)
    }

    /** Manías del C10 (2026-10-05) sobre lo que ha visto esta sesión. */
    private fun quirks(sim: CarSim, p: Probe, label: String) {
        val r = sim.report()
        say("$label: mensaje de vídeo más grande ${r.videoMaxMessageBytes} B (${r.videoLargeMessages} de más de 480 KiB) · SPS/PPS: ${r.codecConfigSummary.describe()}")
        check(Quirks.messageSize(r.videoMessages, r.videoMaxMessageBytes, r.videoLargeMessages, r.receiverHang, p.hangMs.takeIf { it >= 0 }, o.limitBytes), label)
        if (o.spsCheck) {
            check(Quirks.spsRepeat(r.videoMessages, r.codecConfigSummary), label)
        } else {
            check(Verdict(Level.SKIP, "sps_repetido: desactivada con --no-sps-check"), label)
        }
    }

    /** `--decode`: la grabación de la sesión (ya cerrada) por ffmpeg. */
    private fun decode(label: String) {
        val file = recording
        recording = null
        try {
            if (!o.decode) return
            if (o.localPhone) {
                check(Verdict(Level.SKIP, "decodifica: los frames del móvil local son falsos y no se pueden decodificar"), label)
                return
            }
            val ffmpeg = Ffmpeg.locate(o.ffmpeg)
            if (ffmpeg == null) {
                check(Verdict(Level.SKIP, "decodifica: ffmpeg no encontrado" + (o.ffmpeg?.let { " en ${it.path}" } ?: " (ni en ${Ffmpeg.DEFAULT_PATH.path} ni en el PATH)")), label)
                return
            }
            if (file == null || !file.isFile || file.length() == 0L) {
                check(Verdict(Level.SKIP, "decodifica: sin vídeo grabado"), label)
                return
            }
            say("$label: decodificando ${file.length()} B con ${ffmpeg.path}...")
            check(Quirks.decode(Ffmpeg.decode(ffmpeg, file)), label)
        } finally {
            if (recordingIsTemp) file?.delete()
        }
    }

    fun run(): Boolean {
        say("qdsim · escenario ${o.scenario}")
        val limit = if (o.limitBytes > 0) "se cuelga con mensajes de vídeo de más de ${o.limitBytes / 1024} KiB (${o.hangMs} ms sin leer)" else "sin límite de mensaje"
        say("manías del C10: $limit · ${if (o.spsCheck) "SPS/PPS repetidos" else "sin comprobar SPS/PPS repetidos"}" +
            (if (o.decode) " · ffmpeg al final de cada sesión" else "") + if (o.strict) " · estricto: los WARN cuentan como FAIL" else "")
        val phone = if (o.localPhone) LocalPhone(log).start() else null
        localPhone = phone
        try {
            when (o.scenario) {
                "normal", "estricto" -> normal()
                "reconnect" -> reconnect()
                "stall" -> stall()
                "silent" -> silent()
                "disconnect" -> disconnect()
                "rack" -> rack()
                "caida" -> caida()
            }
        } catch (e: Exception) {
            check(false, "excepción: $e")
        } finally {
            phone?.close()
        }
        println()
        println("==== resultado (${o.scenario}): " + if (failures == 0) "PASS" else "FAIL ($failures)")
        results.forEach { println("  $it") }
        return failures == 0
    }

    private fun normal() {
        val (sim, p) = startCar()
        if (expectVideo(sim, p, null, "S1")) check(true, "S1: handshake, AppStatus y vídeo")
        Thread.sleep(1_000)
        val touches = listOf(
            sim.tap(400f, 300f),
            sim.drag(1500f, 400f, 600f, 420f, steps = 12, durationMs = 400),
            sim.pinch(960f, 441f, 200f, 600f, steps = 8, durationMs = 400),
        )
        sim.sendTouch(TouchCodec.ACTION_UP, listOf(TouchPointer.up(0, 960f, 441f)))
        check(touches.all { it }, "S1: táctil (toque, arrastre y pellizco enviados)")
        val idrBefore = sim.report().idrFrames
        sim.requestKeyframe()
        Thread.sleep(2_000)
        check(sim.report().idrFrames > idrBefore, "S1: KEY_FRAME_REQ servido con un IDR")
        sim.sendAppMessage("Global", "DarkModeOn", JsonObject.of("DarkModeOn" to 1))
        play(sim, o.durationS - 4)
        finish(sim, p, "S1")
    }

    private fun reconnect() {
        for (n in 1..o.sessions) {
            val (sim, p) = startCar()
            val label = "S$sessionNo"
            if (expectVideo(sim, p, if (n == 1) null else 500, label)) check(true, "$label: vídeo")
            if (n == 1) {
                // Diseño §9.4: el modo noche que pide el coche en S1 tiene que seguir en las sesiones siguientes.
                sim.sendAppMessage("Global", "DarkModeOn", JsonObject.of("DarkModeOn" to 1))
                say("$label: modo noche del coche (DarkModeOn:1); en las sesiones siguientes Android Auto debe seguir en noche")
            }
            play(sim, o.durationS)
            finish(sim, p, label)
            Thread.sleep(o.gapMs)
        }
    }

    private fun stall() {
        val (sim, p) = startCar()
        expectVideo(sim, p, null, "S1")
        play(sim, 5)
        quirks(sim, p, "S1")
        say("S1: corte de radio simulado de ${o.stallS} s (el coche ni habla ni lee)")
        sim.goSilent()
        sim.pauseReading(0)
        Thread.sleep(o.stallS * 1000L)
        sim.closeAbruptly()
        sim.awaitTermination(5_000)
        decode("S1")
        val t = System.nanoTime()
        val (sim2, p2) = startCar()
        val ok = expectVideo(sim2, p2, 500, "S2")
        val reconnectMs = (System.nanoTime() - t) / 1_000_000
        check(ok && p2.ackMs in 0..1_000, "S2: reconexión tras el corte (ACK +${p2.ackMs} ms, ${reconnectMs} ms hasta ahora)")
        say("Revisa el log del móvil: «Corte S… INICIO/FIN» con horas coherentes y «reconexión X ms» < 1 s.")
        play(sim2, 5)
        finish(sim2, p2, "S2")
    }

    private fun silent() {
        val (sim, p) = startCar()
        expectVideo(sim, p, null, "S1")
        play(sim, 3)
        quirks(sim, p, "S1")
        say("S1: el coche se calla y deja de leer, sin cerrar")
        sim.goSilent()
        sim.pauseReading(0)
        val s1Recording = recording
        val s1Temp = recordingIsTemp
        Thread.sleep(2_000)
        val (sim2, p2) = startCar()
        val ok = expectVideo(sim2, p2, 500, "S2")
        check(ok && p2.ackMs in 0..o.broadcastMs + 200, "S2: relevo de S1 (ACK +${p2.ackMs} ms tras el primer broadcast; S1 debe cerrarse con SUPERSEDED)")
        play(sim2, 5)
        sim.close()
        finish(sim2, p2, "S2")
        if (s1Temp) s1Recording?.delete()
    }

    private fun disconnect() {
        val (sim, p) = startCar()
        expectVideo(sim, p, null, "S1")
        play(sim, 3)
        sim.sendDisconnectReq()
        val deadline = System.currentTimeMillis() + 5_000
        while (p.closedMs < 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val can = p.disconnectRsp?.para?.int("CanDisconnect")
        check(can == 1, "S1: DISCONNECT_RSP{CanDisconnect:${can ?: "—"}}")
        check(p.closedMs >= 0 && p.closeReason.contains("EOF"), "S1: el móvil cierra tras responder (${p.closeReason})")
        quirks(sim, p, "S1")
        sim.close()
        sim.awaitTermination(5_000)
        decode("S1")
        val (sim2, p2) = startCar()
        if (expectVideo(sim2, p2, 500, "S2")) check(true, "S2: vuelve a conectar cuando el coche se reanuncia")
        play(sim2, 3)
        finish(sim2, p2, "S2")
    }

    private fun rack() {
        val (sim, p) = startCar(ignoreAcks = 1)
        val ok = expectVideo(sim, p, null, "S1")
        // El primer ACK llega con el primer broadcast (~0 ms) y se ignora; el bueno, con el re-ACK (≥ 400 ms).
        check(ok && p.ackMs >= 400, "S1: segundo ACK a los ${p.ackMs} ms del primer broadcast (≥ 400 ms) y conexión")
        play(sim, 3)
        finish(sim, p, "S1")
    }

    /**
     * Corte de radio de verdad (C10, 2026-10-06): el coche ni habla ni lee [Options.radioS] s, el móvil cierra la
     * sesión (WATCHDOG/WRITE_STALL a los ~10 s) y el coche vuelve de una de tres maneras (no se sabe cuál usa el C10):
     * directo al puerto de antes (a), anunciándose una sola vez y perdiendo ese ACK (b) o solo con un ACK no pedido (c).
     * Con la re-acogida del móvil, las tres tienen que reconectar en < 10 s.
     */
    private fun caida() {
        for (v in o.variants) {
            val (sim, p) = startCar()
            val label = "S$sessionNo"
            if (!expectVideo(sim, p, null, label)) return
            play(sim, 3)
            quirks(sim, p, label)
            val port = p.mirrorPort
            val phoneIp = p.ackFrom?.address?.hostAddress
            say("$label: corte de radio de ${o.radioS} s (el coche ni habla ni lee); después vuelve con la variante $v")
            sim.goSilent()
            sim.pauseReading(0)
            Thread.sleep(o.radioS * 1000L)
            // a: 1 s más tarde, para no caer justo tras un ACK no pedido (cada 2 s desde el corte): el móvil no podría
            // distinguir esa vuelta de una provocada por el ACK.
            if (v == 'a') Thread.sleep(1_000)
            // Vuelve la radio: la conexión vieja ya no existe en el coche.
            sim.closeAbruptly()
            sim.awaitTermination(5_000)
            decode(label)
            localPhone?.lastReturn = null
            val (sim2, p2) = when (v) {
                'a' -> startCar(comeback = Comeback(maxBroadcasts = 0, directPort = port, directHost = phoneIp))
                'b' -> startCar(ignoreAcks = 1, comeback = Comeback(maxBroadcasts = 1))
                else -> startCar(comeback = Comeback(maxBroadcasts = 0))
            }
            val label2 = "S$sessionNo"
            val ok = expectVideo(sim2, p2, null, label2)
            val r = sim2.report()
            val deadline = System.currentTimeMillis() + 2_000
            while (localPhone != null && localPhone?.lastReturn == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            val back = localPhone?.lastReturn
            val how = back?.let { (h, ms) -> " · móvil: ${h.label} (${ms} ms tras la pérdida)" } ?: ""
            val what = when (v) {
                'a' -> "directo al puerto $port"
                'b' -> "1 anuncio y un ACK perdido"
                else -> "solo ACK no pedido"
            }
            val maxBroadcasts = if (v == 'b') 1 else 0
            check(
                ok && p2.streamingMs in 0..9_999 && r.broadcastsSent <= maxBroadcasts,
                "$label2: variante $v ($what): VIDEO_CTRL{1} a los ${p2.streamingMs} ms de volver (< 10 s), ${r.broadcastsSent} anuncios$how",
            )
            val expected = when (v) {
                'b' -> CarReturn.BROADCAST
                'c' -> CarReturn.UNSOLICITED_ACK
                else -> CarReturn.OLD_PORT
            }
            val got = back?.first
            if (expected != null && got != null) check(got == expected, "$label2: el móvil dice «${got.label}» (se espera «${expected.label}»)")
            play(sim2, 2)
            finish(sim2, p2, label2)
        }
        say("Revisa el log del móvil: «re-acogida tras …», «ACK no pedido #…», «esperando al coche tras …» y «vuelta del coche tras X s: …».")
    }
}
