package dev.qdauto.core.sim

import dev.qdauto.core.h264.AnnexB
import dev.qdauto.core.h264.NalType
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.PayloadFormat
import dev.qdauto.core.wire.VideoExtHeader
import dev.qdauto.core.wire.VideoMessage
import java.io.OutputStream

/** Qué contiene un mensaje de vídeo. */
enum class VideoKind { CONFIG, IDR, P, OTHER }

/** Resultado de validar un mensaje de vídeo recibido. */
data class VideoFrameInfo(
    /** Índice (0 = primero) entre los mensajes de vídeo de la sesión. */
    val index: Int,
    val kind: VideoKind,
    val header: VideoExtHeader?,
    val payloadSize: Int,
    val nalTypes: List<Int>,
    val errors: List<String>,
)

/** Valores esperados en la cabecera extendida; `null` = no comprobar. */
data class VideoExpectations(
    val width: Int? = null,
    val height: Int? = null,
    val encodingType: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val gop: Int? = null,
    val appType: Int? = null,
)

/**
 * Valida el vídeo que manda el teléfono (spec §8): cabeceras (16 + 32 B), Annex-B, tipos de NAL y orden
 * (SPS/PPS primero, IDR antes de cualquier P-frame). Opcionalmente vuelca el Annex-B a [record]. No es thread-safe.
 */
class VideoValidator(
    @Volatile var expectations: VideoExpectations = VideoExpectations(),
    private val record: OutputStream? = null,
) {
    var messages = 0
        private set
    var configMessages = 0
        private set
    var idrFrames = 0
        private set
    var pFrames = 0
        private set
    var otherMessages = 0
        private set
    var payloadBytes = 0L
        private set
    var firstKind: VideoKind? = null
        private set
    var lastHeader: VideoExtHeader? = null
        private set
    private var sawConfig = false
    private var sawIdr = false
    private val errorList = ArrayList<String>()
    var errorCount = 0
        private set

    val errors: List<String> get() = errorList.toList()

    fun onMessage(header: Header, body: ByteArray): VideoFrameInfo {
        val index = messages++
        val errs = ArrayList<String>()
        if (header.msgType != MsgType.VIDEO) errs += "msgType ${header.msgType} != 1"
        if (header.extLen != VideoMessage.EXT_SIZE) errs += "extLen ${header.extLen} != 32"
        if (header.payloadFormat != PayloadFormat.VIDEO) errs += "payLoadFormat ${header.payloadFormat} != 2"
        if (header.byte11 != 0 || header.byte12 != 0 || header.reservedOne != 0 || header.byte15 != 0) {
            errs += "bytes 11/12/14/15 de la cabecera distintos de 0"
        }
        if (body.size < VideoMessage.EXT_SIZE) {
            errs += "cuerpo de ${body.size} B, menor que la cabecera extendida"
            return finish(VideoFrameInfo(index, VideoKind.OTHER, null, 0, emptyList(), errs))
        }
        val ext = VideoExtHeader.decode(body, 0)
        lastHeader = ext
        checkExt(ext, errs)
        val payloadOff = VideoMessage.EXT_SIZE
        val payloadLen = body.size - payloadOff
        payloadBytes += payloadLen
        record?.write(body, payloadOff, payloadLen)
        if (payloadLen == 0) errs += "payload vacío"
        if (payloadLen > 0 && AnnexB.startCodeLength(body, payloadOff, payloadLen) == 0) errs += "el payload no empieza por un start code"
        val nals = AnnexB.nalUnits(body, payloadOff, payloadLen)
        if (payloadLen > 0 && nals.isEmpty()) errs += "no hay ninguna NAL"
        nals.forEach { if (it.forbiddenBit) errs += "NAL con forbidden_zero_bit en ${it.offset - payloadOff}" }
        val types = nals.map { it.type }
        val kind = when {
            NalType.SPS in types && NalType.PPS in types && types.none { it == NalType.IDR || it == NalType.SLICE } -> VideoKind.CONFIG
            NalType.IDR in types -> VideoKind.IDR
            NalType.SLICE in types -> VideoKind.P
            else -> VideoKind.OTHER
        }
        if (index == 0) {
            firstKind = kind
            if (kind != VideoKind.CONFIG) errs += "el primer mensaje de vídeo no es SPS/PPS ($kind)"
        }
        when (kind) {
            VideoKind.CONFIG -> {
                configMessages++
                sawConfig = true
            }
            VideoKind.IDR -> {
                if (!sawConfig) errs += "IDR antes de SPS/PPS"
                idrFrames++
                sawIdr = true
            }
            VideoKind.P -> {
                if (!sawConfig) errs += "P-frame antes de SPS/PPS"
                if (!sawIdr) errs += "P-frame antes del primer IDR"
                pFrames++
            }
            VideoKind.OTHER -> {
                otherMessages++
                errs += "mensaje sin SPS/PPS ni slices: ${types.map { NalType.name(it) }}"
            }
        }
        return finish(VideoFrameInfo(index, kind, ext, payloadLen, types, errs))
    }

    private fun checkExt(ext: VideoExtHeader, errs: MutableList<String>) {
        val p = ext.params
        val e = expectations
        if (ext.extLength != VideoMessage.EXT_SIZE) errs += "ext[0..1] = ${ext.extLength} != 32"
        if (ext.version != 1) errs += "ext[2] = ${ext.version} != 1"
        if (ext.reserved3 != 0) errs += "ext[3] = ${ext.reserved3} != 0"
        if (ext.tail != 0) errs += "ext[29..31] distintos de 0"
        if (p.appType != 1 && p.appType != 2) errs += "appType ${p.appType} no es 1 ni 2"
        if (p.orientation !in -1..1) errs += "orientación ${p.orientation} fuera de -1..1"
        if (p.angle !in setOf(0, 90, 180, 270)) errs += "ángulo ${p.angle} no es 0/90/180/270"
        e.width?.let { if (it > 0 && p.width != it) errs += "ancho ${p.width} != $it" }
        e.height?.let { if (it > 0 && p.height != it) errs += "alto ${p.height} != $it" }
        e.appType?.let { if (p.appType != it) errs += "appType ${p.appType} != $it" }
        e.encodingType?.let { if (p.encodingType != it) errs += "encodingType ${p.encodingType} != $it (eco de VIDEO_ARGS)" }
        e.fps?.let { if (p.fps != it) errs += "fps ${p.fps} != $it (eco de VIDEO_ARGS)" }
        e.bitrate?.let { if (p.bitrate != it) errs += "bitrate ${p.bitrate} != $it (eco de VIDEO_ARGS)" }
        e.gop?.let { if (p.gop != it) errs += "GOP ${p.gop} != $it (eco de VIDEO_ARGS)" }
    }

    private fun finish(info: VideoFrameInfo): VideoFrameInfo {
        errorCount += info.errors.size
        for (err in info.errors) {
            if (errorList.size < MAX_ERRORS) errorList += "#${info.index}: $err"
        }
        return info
    }

    private companion object {
        const val MAX_ERRORS = 200
    }
}
