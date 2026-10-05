package dev.qdauto.core.session

import dev.qdauto.core.util.Hex
import dev.qdauto.core.util.i
import dev.qdauto.core.util.w
import dev.qdauto.core.util.e
import dev.qdauto.core.wire.AppIds
import dev.qdauto.core.wire.AppMessage
import dev.qdauto.core.wire.BinBlock
import dev.qdauto.core.wire.BinaryMessage
import dev.qdauto.core.wire.BtAddrRequest
import dev.qdauto.core.wire.CarInfo
import dev.qdauto.core.wire.CarKey
import dev.qdauto.core.wire.CarParams
import dev.qdauto.core.wire.Cmd
import dev.qdauto.core.wire.ControlMessage
import dev.qdauto.core.wire.Direction
import dev.qdauto.core.wire.FunctionIds
import dev.qdauto.core.wire.Header
import dev.qdauto.core.wire.MsgType
import dev.qdauto.core.wire.PayloadFormat
import dev.qdauto.core.wire.PhoneMessages
import dev.qdauto.core.wire.TouchCodec
import dev.qdauto.core.wire.Traces
import dev.qdauto.core.wire.UnknownMessage
import dev.qdauto.core.wire.VideoArgs
import dev.qdauto.core.wire.WireMessage

/**
 * Reacción a cada mensaje del coche (hilo lector): respuestas automáticas, cambios de estado y callbacks.
 * El `switch` de QDLink está en LC/a.java:1943-2321 (msgType 0, 2, 12, 13, 99) y el legado en LC/a.java:1101-1290.
 */
internal class InboundHandler(private val s: PhoneSession) {
    private val config = s.config
    private val listener = s.listener
    private val log = s.log
    private val tag = s.tag

    fun dispatch(msg: WireMessage) {
        when (msg) {
            is WireMessage.Frame -> handleFrame(msg)
            is WireMessage.Bin -> handleBin(msg.block)
            is WireMessage.Garbage -> handleGarbage(msg)
        }
    }

    private fun handleFrame(f: WireMessage.Frame) {
        val h = f.header
        s.carSpoke = true // QDLink: W = "5A5A" con cualquier cabecera 5A5A (LC/a.java:357-360)
        if (!f.extValid) {
            unknownFrame(f, "extLen ${h.extLen} mayor que el cuerpo (${f.body.size} B); QDLink lo descarta")
            return
        }
        when (h.msgType) {
            MsgType.CONTROL ->
                if (h.payloadFormat == PayloadFormat.JSON) {
                    handleControl(ControlMessage.parse(h, f.payloadText()), f)
                } else {
                    unknownFrame(f, "mensaje de control con payLoadFormat ${h.payloadFormat} (QDLink solo acepta 1)")
                }
            MsgType.TOUCH ->
                if (h.payloadFormat == PayloadFormat.BINARY) {
                    handleTouch(f)
                } else {
                    unknownFrame(f, "táctil con payLoadFormat ${h.payloadFormat} (QDLink solo acepta 0)")
                }
            MsgType.APP ->
                if (h.payloadFormat == PayloadFormat.JSON) {
                    handleApp(AppMessage.parse(h, f.payloadText()), f)
                } else {
                    unknownFrame(f, "msgType 13 con payLoadFormat ${h.payloadFormat} (QDLink solo acepta 1)")
                }
            MsgType.SPEECH, MsgType.CUSTOM -> {
                s.trace(Traces.ofBinary(Direction.IN, MsgType.name(h.msgType), h.msgType, f.wireBytes(), h.describe(), config.traceHexPrefixBytes))
                val m = BinaryMessage(h, f.payload())
                s.post { listener.onBinaryMessage(m) }
            }
            else -> unknownFrame(f, "msgType ${h.msgType} desconocido")
        }
    }

    private fun handleControl(msg: ControlMessage, f: WireMessage.Frame) {
        s.trace(Traces.ofJson(Direction.IN, msg.cmd.ifEmpty { "CONTROL?" }, MsgType.CONTROL, f.wireBytes(), msg.text, config.traceMaxJsonChars))
        if (msg.parseError != null) log.w(tag, "JSON de control ilegible (${msg.parseError}): ${msg.text}")
        s.post { listener.onControlMessage(msg) }
        when (msg.cmd) {
            Cmd.CAR_INFO -> onCarInfo(msg)
            Cmd.VIDEO_SUP_REQ -> onVideoSupportRequest(msg)
            Cmd.VIDEO_ARGS -> onVideoArgs(msg)
            Cmd.VIDEO_CTRL -> onVideoCtrl(msg)
            Cmd.KEY_FRAME_REQ -> {
                // QDLink solo reenvía SPS/PPS (SC/managers/a.java:627-631); además pedimos un IDR de verdad.
                if (config.resendCodecConfigOnKeyframeRequest) s.queue.requestConfigResend()
                s.requestKeyframe(KeyframeReason.CAR_REQUEST, force = true)
            }
            Cmd.LAND_MODE_REQ -> {
                val orientation = CarParams.orientation(msg.json)
                s.post { listener.onLandModeRequest(orientation, msg) }
                if (config.replyLandMode) {
                    s.enqueueControl(Cmd.LAND_MODE_RSP, PhoneMessages.landModeRspJson(orientation, config.landModeAuthority, config.landModeStatusArg))
                }
            }
            Cmd.BT_ADDR -> onBtAddr(msg)
            Cmd.PHONE_KEYS -> {
                val code = CarParams.phoneKeys(msg.json)
                s.post { listener.onPhoneKey(code, msg) }
                val key = CarKey.fromPhoneKeys(code)
                if (key != null) s.post { listener.onKey(key) } else log.w(tag, "PHONE_KEYS con código desconocido $code")
            }
            Cmd.GO_IN_LINK_APP -> s.post { listener.onGoInLinkApp(msg) }
            Cmd.DISCONNECT_REQ -> onDisconnectRequest(msg)
            Cmd.HEARTBEAT -> s.counters.carHeartbeat() // QDLink no responde (LC/a.java:2070-2071)
            else -> unknown(
                msg.header,
                if (msg.cmd.isEmpty()) "mensaje de control sin CMD legible" else "CMD desconocido '${msg.cmd}' (QDLink no lo atiende)",
                f.wireBytes(),
            )
        }
    }

    private fun onCarInfo(msg: ControlMessage) {
        val info = CarInfo.parse(msg.json, config.qdlinkStrictParsing)
        if (info.stoppedAt != null) log.w(tag, "CAR_INFO incompleto: QDLink habría parado en '${info.stoppedAt}'")
        s.carInfo = info
        s.geometry = MirrorGeometry.forCarInfo(config.phone.screenLongSide, config.phone.screenShortSide, info.carWidth, info.carHeight)
        s.recomputeVideoParams()
        log.i(tag, "CAR_INFO: $info → geometría ${s.geometry}, cabecera ${s.videoParams}")
        s.post { listener.onCarInfo(info, msg) }
        val phoneInfo = PhoneInfoFactory.build(info, config.phone, s.phoneInfoOverridesFor(info))
        s.lastPhoneInfo = phoneInfo
        // Mismo orden que QDLink (PHONE_INFO y luego UPDATE_NOTIFY, LC/a.java:1964-1965), garantizado por la cola.
        if (config.replyPhoneInfo) s.enqueueControl(Cmd.PHONE_INFO, PhoneMessages.phoneInfoJson(phoneInfo))
        if (config.replyUpdateNotify) s.enqueueControl(Cmd.UPDATE_NOTIFY, PhoneMessages.updateNotifyJson(config.updateNotifyStatus))
        s.advanceHandshake(SessionState.CAR_INFO)
    }

    private fun onVideoSupportRequest(msg: ControlMessage) {
        val format = CarParams.videoFormat(msg.json)
        s.post { listener.onVideoSupportRequest(format, msg) }
        if (!config.replyVideoSupport) return
        s.enqueueControl(Cmd.VIDEO_SUP_RSP, PhoneMessages.videoSupportRspJson(config.videoSupportFormat, config.videoSupportValue))
        s.advanceHandshake(SessionState.VIDEO_SUPPORTED)
        s.maybeStartWhitelist()
        if (config.announceUnlocked) s.enqueueControl(Cmd.LOCK_SCREEN_STATUS, PhoneMessages.lockScreenStatusJson(3))
    }

    private fun onVideoArgs(msg: ControlMessage) {
        val args = VideoArgs.parse(msg.json, config.qdlinkStrictParsing)
        if (args.stoppedAt != null) log.w(tag, "VIDEO_ARGS incompleto: QDLink habría parado en '${args.stoppedAt}'")
        s.videoArgs = args
        s.recomputeVideoParams()
        log.i(tag, "VIDEO_ARGS: $args → cabecera ${s.videoParams}")
        s.post { listener.onVideoArgs(args, msg) }
        if (config.replySpeechArgs) s.enqueueControl(Cmd.SPEECH_ARGS, PhoneMessages.speechArgsJson(config.speechArgs))
        s.advanceHandshake(SessionState.VIDEO_ARGS)
    }

    private fun onVideoCtrl(msg: ControlMessage) {
        val playStatus = CarParams.playStatus(msg.json)
        if (playStatus != 1) {
            // QDLink ignora PlayStatus ≠ 1 (LC/a.java:2005-2007).
            if (config.pauseOnVideoCtrlStop && s.state == SessionState.STREAMING) s.setState(SessionState.PAUSED)
            log.i(tag, "VIDEO_CTRL{PlayStatus:$playStatus}" + if (config.pauseOnVideoCtrlStop) "" else " (QDLink lo ignora: se sigue emitiendo)")
            s.post { listener.onVideoControl(false, playStatus, msg) }
            return
        }
        if (config.requireVideoArgsForPlay && s.videoArgs == null) {
            log.w(tag, "VIDEO_CTRL{1} sin VIDEO_ARGS previo: QDLink no arranca el vídeo (NPE en LC/a.java:2012); se ignora")
            return
        }
        if (s.state == SessionState.STREAMING) {
            // Repetido con el vídeo en marcha: QDLink no hace nada. Reenviamos SPS/PPS y pedimos IDR por si el coche
            // perdió el flujo, pero sin cortar los P-frames.
            s.queue.requestConfigResend()
            log.i(tag, "VIDEO_CTRL{1} repetido")
        } else {
            // Primero se arma la espera del IDR y después se abre la puerta al vídeo.
            s.queue.startStream()
            if (s.setState(SessionState.STREAMING)) log.i(tag, "el coche pide vídeo")
        }
        s.post { listener.onVideoControl(true, playStatus, msg) }
        s.requestKeyframe(KeyframeReason.STREAM_START, force = true)
    }

    private fun onBtAddr(msg: ControlMessage) {
        val req = BtAddrRequest.parse(msg.json)
        log.i(tag, "BT_ADDR: $req")
        s.post { listener.onBtAddr(req, msg) }
        if (req.address == null) {
            log.w(tag, "BT_ADDR sin BluetoothAddr: QDLink no respondería (NPE en LC/a.java:2033)")
            return
        }
        val result = try {
            config.btResultProvider?.invoke(req)
        } catch (e: Exception) {
            log.e(tag, "btResultProvider falló", e)
            null
        }
        if (result != null) s.enqueueControl(Cmd.BT_RESULT, PhoneMessages.btResultJson(result))
    }

    private fun onDisconnectRequest(msg: ControlMessage) {
        s.post { listener.onDisconnectRequest(msg) }
        when {
            config.replyDisconnectReq ->
                s.enqueueControl(Cmd.DISCONNECT_RSP, PhoneMessages.disconnectRspJson(1), closeAfter = config.closeOnDisconnectReq)
            config.closeOnDisconnectReq -> s.closeWith(CloseReason(CloseReason.Kind.DISCONNECT_REQ, "DISCONNECT_REQ del coche"))
        }
    }

    private fun handleTouch(f: WireMessage.Frame) {
        val event = TouchCodec.parse(f.body, f.payloadOffset, f.payloadLength, System.nanoTime())
        if (event == null) {
            unknownFrame(f, "táctil de menos de 5 bytes")
            return
        }
        s.counters.touchEvents.incrementAndGet()
        s.trace(Traces.ofBinary(Direction.IN, "TOUCH", MsgType.TOUCH, f.wireBytes(), event.describe(), 0))
        if (event.wouldQdlinkDrop) log.w(tag, "táctil que QDLink descartaría: ${event.describe()}")
        s.post { listener.onTouch(event) }
    }

    private fun handleApp(msg: AppMessage, f: WireMessage.Frame) {
        s.trace(Traces.ofJson(Direction.IN, msg.key, MsgType.APP, f.wireBytes(), msg.text, config.traceMaxJsonChars))
        s.post { listener.onAppMessage(msg) }
        val known = when (msg.appId) {
            AppIds.MUSIC -> CarKey.fromMusicFunction(msg.functionId)?.also { key -> s.post { listener.onKey(key) } } != null
            AppIds.AUDIO_SOURCE -> msg.functionId == FunctionIds.AUDIO_SOURCE_STATE
            AppIds.GLOBAL -> msg.functionId == FunctionIds.DARK_MODE_ON
            else -> false
        }
        if (!known) unknown(msg.header, "AppID/FunctionID no reconocido '${msg.key}' (QDLink no hace nada)", f.wireBytes())
    }

    private fun handleBin(block: BinBlock) {
        val wire = block.bytes + block.extra
        s.trace(Traces.ofBinary(Direction.IN, "!BIN", null, wire, block.describe(), config.traceHexPrefixBytes))
        s.post { listener.onLegacyMessage(block) }
        if (block.isLegacyHeartbeatRequest) {
            if (config.echoLegacyHeartbeat) s.enqueueRaw(BinBlock.legacyHeartbeatReply(block.bytes), "!BIN HeartbeatReply")
        } else {
            unknown(null, "bloque !BIN legado no soportado (${block.describe()})", wire)
        }
    }

    private fun handleGarbage(g: WireMessage.Garbage) {
        s.counters.garbageBytes.addAndGet(g.totalBytes)
        log.w(tag, "${g.totalBytes} bytes no válidos: ${g.reason}\n${Hex.dump(g.bytes, maxBytes = 256)}")
        s.trace(Traces.ofBinary(Direction.IN, "GARBAGE", null, g.bytes, "${g.reason} (${g.totalBytes} B)", config.traceHexPrefixBytes))
        val m = UnknownMessage(null, g.reason, g.bytes, g.totalBytes)
        s.post { listener.onUnknownMessage(m) }
    }

    private fun unknownFrame(f: WireMessage.Frame, reason: String) {
        val wire = f.wireBytes()
        s.trace(Traces.ofBinary(Direction.IN, "UNKNOWN", f.header.msgType, wire, "$reason; ${f.header.describe()}", config.traceHexPrefixBytes))
        unknown(f.header, reason, wire)
    }

    private fun unknown(header: Header?, reason: String, wire: ByteArray) {
        log.w(tag, "mensaje no reconocido: $reason\n${Hex.dump(wire, maxBytes = 256)}")
        val m = UnknownMessage(header, reason, wire.copyOf(minOf(wire.size, MAX_UNKNOWN_KEPT)), wire.size.toLong())
        s.post { listener.onUnknownMessage(m) }
    }

    private companion object {
        const val MAX_UNKNOWN_KEPT = 4096
    }
}
