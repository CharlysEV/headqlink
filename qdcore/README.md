# `:core`: motor del protocolo QDLink (lado teléfono) y simulador de coche

Biblioteca Kotlin/JVM 17 **sin dependencias** (ni Android ni librerías de terceros). Implementa el protocolo
Wi-Fi de QDLink 1.9.7 en el papel de **teléfono**, según `docs/protocol/04-wire-spec.md`. Incluye además un
**simulador del coche** (`CarSim`), que usan los tests y la CLI `:carsim`.

- `:app` usa `PhoneLink` (o las piezas sueltas `DiscoveryListener` + `MirrorServer` + `PhoneSession`) y le da
  H.264 de `MediaCodec`.
- `:carsim` usa `CarSim` para hacer de coche contra el móvil real o contra la app en local.

Los comentarios del código citan QDLink así: `// QDLink: h0/a.java:35`.

---

## 1. Paquetes

| Paquete | Contenido |
|---|---|
| `dev.qdauto.core.util` | `BE` (big-endian), `Hex` (`dump`, `encode`, `decode`, `prefix`), `QdLog`/`LogLevel` |
| `dev.qdauto.core.json` | `JsonValue` (`JsonObject`, `JsonArray`, `JsonString`, `JsonNumber`, `JsonBool`, `JsonNull`), `JsonParser`, `JsonWriter`, `buildJsonObject`, `Json.toValue` |
| `dev.qdauto.core.wire` | Códecs: `Header` (5A5A), `Frames`, `BinBlock` (`!BIN`), `PhoneMessages` (teléfono → coche), `CarMessages` (coche → teléfono, para el simulador), `CarInfo`/`VideoArgs`/`BtAddrRequest`/`CarParams`/`CarKey` (parsers), `ControlMessage`/`AppMessage`/`BinaryMessage`/`UnknownMessage`, `TouchCodec`/`TouchEvent`/`TouchPointer`, `VideoMessage`/`VideoParams`/`VideoExtHeader`, `FrameReader`/`WireMessage`, `UdpCodec`/`UdpMessage`/`BroadcastAck`, `TraceEvent`/`Traces`/`Direction`, constantes `Cmd`, `AppIds`, `FunctionIds`, `MsgType`, `PayloadFormat` |
| `dev.qdauto.core.h264` | `AnnexB` (NAL, start codes), `NalUnit`, `NalType` |
| `dev.qdauto.core.discovery` | `DiscoveryListener`, `DiscoveryConfig`, `AckPolicy`, `AckHandle`, `CarAnnouncement` |
| `dev.qdauto.core.session` | `PhoneLink` (orquestador opcional), `MirrorServer`, `PhoneSession`, `SessionConfig` (+ `PhoneIdentity`, `PhoneInfoOverrides`, `VideoOverrides`, `WhitelistMode`), `SessionListener`, `SessionState`, `KeyframeReason`, `CloseReason`, `SessionStats`, `EncoderSuggestion`, `MirrorGeometry`, `PhoneInfoFactory` |
| `dev.qdauto.core.sim` | `CarSim`, `CarSimConfig` (+ `CarInfoValues`, `VideoArgsValues`), `CarSimListener`, `CarSimState`, `CarSimReport`, `SentTouch`, `VideoValidator`, `VideoExpectations`, `VideoFrameInfo`, `VideoKind` |

Todo lo `internal` (cola de envío, ventana de tasa…) no forma parte de la API.

---

## 2. Uso en la app

### 2.1 Con `PhoneLink` (recomendado)

`PhoneLink` hace los pasos 1-5 de la spec §10.1:

1. escucha UDP en 18463;
2. elige coche (el primero que pase `carFilter`, o el que se pase a `connect()`);
3. abre el `ServerSocket` (puerto aleatorio 10001-65535, como QDLink) **antes** del ACK;
4. manda el `Broadcast_ACK` desde el socket de 18463 a `IP_coche:18464`;
5. acepta con un límite de 20 s y arranca la `PhoneSession`.

Cuando la sesión termina, vuelve a buscar y reconecta con el siguiente broadcast.

```kotlin
val log = QdLog { level, tag, msg, err -> /* Logcat + fichero */ }
val link = PhoneLink(
    PhoneLinkConfig(
        discovery = DiscoveryConfig(ackPolicy = AckPolicy.QDLINK),   // o AckPolicy.RESEND_UNTIL_20S
        session = SessionConfig(
            phone = PhoneIdentity(
                screenLongSide = 2340, screenShortSide = 1080,   // getRealSize()
                brand = Build.MANUFACTURER, model = Build.MODEL, sdkInt = Build.VERSION.SDK_INT,
            ),
        ),
        mirrorPort = MirrorServer.RANDOM_PORT,      // o un puerto fijo
        autoConnect = true,
        carFilter = { car -> true },                // p. ej. por car.uuid o car.host
    ),
    linkListener,      // PhoneLinkListener: onCarFound, onConnecting, onSessionStarted(session), onSessionEnded…
    sessionListener,   // SessionListener: se reenvía a cada PhoneSession
    log,
).start()
// …
link.currentSession?.sendFrame(buffer, isKey, ptsUs)
link.disconnect()   // corta la sesión actual (vuelve a buscar si reconnect = true)
link.close()        // todo fuera
```

`PhoneLinkListener.onExtraConnection` avisa si el coche abre otra conexión TCP con la sesión ya activa. QDLink deja
esas conexiones colgadas; nosotros las registramos y las cerramos.

### 2.2 Con las piezas sueltas

```kotlin
val discovery = DiscoveryListener(DiscoveryConfig(), object : DiscoveryListener.Callback {
    override fun onCarFound(car: CarAnnouncement) { /* car.name, car.uuid, car.host, car.rawJson, car.rawBytes */ }
    override fun onCarSeen(car: CarAnnouncement) { /* car.count, car.lastSeenMillis */ }
    override fun onOtherDatagram(from: InetSocketAddress, bytes: ByteArray, parsed: UdpMessage) { }
}, log).start()

val server = MirrorServer(MirrorServer.RANDOM_PORT, log = log)    // ya escuchando
val ack = discovery.sendAck(car, server.port)                     // mismo socket que 18463 → car:18464
val socket = server.accept(MirrorServer.DEFAULT_ACCEPT_TIMEOUT_MS) // SocketTimeoutException a los 20 s
ack.cancel()                                                       // para los reenvíos, si los hay
val session = PhoneSession(socket, SessionConfig(), sessionListener, log).start()
```

`server.accept()` se puede volver a llamar para ver si llegan conexiones extra; el servidor sigue abierto hasta
`server.close()`. **Ojo**: QDLink deja su socket UDP 18463 abierto mientras viva su proceso. Si QDLink está abierto,
los dos reciben los broadcasts y los dos podrían contestar. Hay que forzar su cierre antes de usar nuestra app.

### 2.3 Vídeo desde `MediaCodec`

La sesión acepta vídeo solo después de `VIDEO_CTRL{PlayStatus:1}` (`SessionState.STREAMING`). Antes, `sendFrame`
devuelve `false` (salvo con `sendVideoBeforePlay`).

```kotlin
override fun onVideoControl(play: Boolean, playStatus: Int, message: ControlMessage) {
    if (play) startEncoder(session.encoderSuggestion)   // ancho/alto = par(CarWidth)×par(CarHeight); fps/bitrate/GOP de VIDEO_ARGS
}
override fun onKeyframeRequested(reason: KeyframeReason) {
    codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
}

// Hilo de salida del encoder:
val buf = codec.getOutputBuffer(index)!!.apply { position(info.offset); limit(info.offset + info.size) }
if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
    session.sendCodecConfig(buf)                                       // SPS‖PPS, mensaje propio
} else {
    session.sendFrame(buf, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0, info.presentationTimeUs)
}
codec.releaseOutputBuffer(index, false)                                // la sesión ya ha copiado los bytes
```

- `sendFrame(ByteBuffer, …)` copia una sola vez (del buffer del codec al mensaje de 48 + N bytes) y **nunca
  bloquea**. Devuelve `false` si el frame se descartó.
- También existen `sendFrame(ByteArray, …)` y `sendFrame(ByteArray, offset, length, …)`. Para no copiar nada más,
  `VideoMessage.writeHeaders(msg, len, params)` rellena las cabeceras de un array que ya lleva el payload en el
  offset 48.
- Al empezar el vídeo (y tras un descarte) la sesión **espera un IDR** y lo precede del SPS/PPS guardado. Por eso pide
  un IDR con `onKeyframeRequested(STREAM_START)`.
- `KEY_FRAME_REQ` del coche hace dos cosas: reenvía el SPS/PPS antes del siguiente frame (como QDLink) y pide un IDR
  (`CAR_REQUEST`), cosa que QDLink no hace.
- Cabecera extendida: `session.videoParams`, que por defecto es el modo in-app de QDLink (spec §8.7): W×H =
  `par(CarWidth)×par(CarHeight)`, appType 1, ángulo 90, orientación 1 y eco crudo de `VIDEO_ARGS`.
  - Se cambia con `SessionConfig.videoOverrides` o, en caliente, con `session.setVideoOverrides(VideoOverrides(appType = 2, …))`.
  - Si la app fuerza otro tamaño de encoder, debe forzar también `width`/`height` aquí.
- El protocolo no lleva timestamps: `ptsUs` solo va a la traza. Si la imagen no cambia, QDLink repite frames a ritmo
  fijo (timer GL). En la app, `KEY_REPEAT_PREVIOUS_FRAME_AFTER` sirve para eso.

### 2.4 Táctil, teclas y resto de mensajes

- `onTouch(TouchEvent)` trae todos los dedos:
  - `action` (i32 global; QDLink solo distingue 0 = empieza y 1 = termina);
  - por dedo, `TouchPointer(id, action, x, y, xBits, yBits)`: `id` es un byte con signo, `action` vale 1 down,
    2 up o 3 move, y `x`/`y` son float32 con sus bits crudos;
  - `declaredCount`, `truncated`, `extraBytes` y `wouldQdlinkDrop`.
  - Se desconoce el espacio de coordenadas. Hipótesis A: px del frame (`videoParams.width/height`). Hipótesis B:
    px de la pantalla del teléfono (`config.phone`). Ver spec §9.3.
- `onKey(CarKey)` es la interpretación de alto nivel:
  - `PHONE_KEYS`: 1 → `HOME`, 2 → `BACK`, 3 → `RECENTS`;
  - `Music/…`: `MEDIA_PLAY_PAUSE`, `MEDIA_PLAY`, `MEDIA_PAUSE`, `MEDIA_PREVIOUS`, `MEDIA_NEXT`, `MUTE_TOGGLE`.
  - El crudo llega por `onPhoneKey(code, msg)` y `onAppMessage(msg)`.
- `onControlMessage` recibe **todos** los msgType 0 y `onAppMessage` todos los msgType 13, antes del callback
  específico.
- `onUnknownMessage(UnknownMessage)` recibe, con `header` (o `null`), `reason`, `bytes` y `hexdump`:
  - `CMD` desconocidos (también `LOCK_SCREEN_REQ` y `UPDATE_PKG_REQ`, que QDLink no atiende);
  - AppID/FunctionID no reconocidos;
  - msgType o payLoadFormat inesperados;
  - `extLen` imposible;
  - `!BIN` no soportados;
  - bytes basura (con resincronización).

  Nada se descarta en silencio.
- `onBtAddr(BtAddrRequest, msg)`: el resultado depende del estado Bluetooth del móvil. Se contesta desde la app con
  `session.sendBtResult(r)` o con `SessionConfig.btResultProvider` (spec §6.8).
- Envíos manuales para botones de depuración:
  - `sendLockScreenStatus(1|2|3)`, `sendCarAppBackground()`, `sendCarAppForeground()`;
  - `sendDisconnectRsp()`, `sendWhitelistAppOn(v)`, `setWhitelistValue(v)`;
  - `sendPhoneInfoChange(...)`, `resendPhoneInfo()`, `sendSpeechCtrl(s)`, `sendPlayState(s)`;
  - `sendControl(cmd, para)`, `sendAppMessage(appId, fid, para)`, `sendCustom(subtype, bytes)`;
  - `sendRaw(bytes)` (ya enmarcado).

### 2.5 Trazas, estado y estadísticas

- `onTrace(TraceEvent)`: una por mensaje que entra o sale.
  - Campos: `timeMillis`, `direction`, `kind` (CMD, `AppID/FunctionID`, `TOUCH`, `VIDEO_IDR`…), `msgType`, `size`,
    `summary` (JSON recortado, táctil decodificado o resumen del frame) y `bytes`.
  - `bytes` es el mensaje completo para registrar cada byte. En vídeo vale `null`, para no copiar megas.
- `session.stats()` devuelve un `SessionStats`:
  - vídeo enviado: frames y bytes enviados, fps y kbit/s del último segundo;
  - colas y pérdidas: profundidad de las colas, frames descartados y rechazados, peticiones de IDR;
  - lado del coche: tiempo desde el último byte del coche, hueco máximo entre mensajes, heartbeats del coche e
    intervalo del último.
- Otras propiedades: `session.state`, `carInfo`, `videoArgs`, `geometry` (`MirrorGeometry`), `lastPhoneInfo`,
  `videoParams`, `encoderSuggestion` y `closeReason`.

---

## 3. Modelo de hilos

| Hilo | Qué hace |
|---|---|
| `qd-discovery-rx` | Recibe datagramas UDP y llama a `DiscoveryListener.Callback` |
| `qd-discovery-ack` | Solo si hay reenvíos del ACK |
| `qd-link-accept` | Uno por intento de `PhoneLink`: `accept()` con límite y después registro de conexiones extra |
| `qd-sN-reader` | Lee del socket (`FrameReader`), parsea y encola las respuestas automáticas. Prioridad `ioThreadPriority` |
| `qd-sN-writer` | **Único** que escribe en el socket: un `write()` por mensaje, el control siempre antes que el vídeo |
| `qd-sN-timer` | Heartbeat, watchdog, detector de `write()` bloqueado y `WhitelistAppOn` |
| `qd-sN-events` | Entrega **en orden** todos los callbacks de `SessionListener`. Nunca corre en el lector, el escritor ni el hilo del encoder |

- Los `send*` de la sesión se pueden llamar desde cualquier hilo y **nunca bloquean**: encolan y vuelven.
- `close()` es idempotente. Cierra el socket, para los temporizadores y termina todos los hilos. `onClosed` es
  siempre el **último** evento, y llega una sola vez.
- `awaitTermination(ms)` espera a los hilos. No hay que llamarlo desde un callback.
- Los callbacks de `DiscoveryListener`, `PhoneLinkListener` y `CarSimListener` llegan desde sus hilos internos: no
  hay que bloquearlos.

Política de la cola de vídeo:

- La configuración (SPS/PPS) y los IDR no se descartan nunca.
- Atasco: si al llegar un frame ya hay `videoBacklogFrames` frames (6 por defecto) o `videoBacklogBytes` bytes en
  cola, se tiran los P-frames encolados.
  - Si el que llega es un P, también se tira, y no se acepta otro P hasta el siguiente IDR. Se pide uno con
    `onKeyframeRequested(BACKLOG)`, como mucho cada `minKeyframeRequestIntervalMs`.
  - Si el que llega es un IDR, se encola: deja obsoletos los P anteriores.
- Hay una válvula de memoria (`videoQueueHardLimitBytes`, 32 MiB) que solo quita frames ya superados por un IDR
  posterior. Solo puede actuar si el encoder manda exclusivamente IDR.

---

## 4. Configuración

### 4.1 `SessionConfig` (por defecto, como QDLink)

| Parámetro | Por defecto | QDLink / motivo |
|---|---|---|
| `tcpNoDelay` | `true` | WF/d.java:88 |
| `sendBufferBytes` | 256 KiB | QDLink pide 4 MiB (WF/d.java:90): el kernel esconde segundos de vídeo y el atasco se ve tarde. Con 256 KiB (≈ 250 ms a 8 Mbit/s) la presión llega antes a nuestra cola, que descarta P-frames y pide un IDR. `null` = no tocar |
| `receiveBufferBytes` | `null` | QDLink: 6 MiB. Aquí solo entra control y táctil |
| `keepAlive` | `true` | WF/d.java:94 |
| `maxMessageBytes` | 8 MiB | spec §3.6 |
| `ioThreadPriority` | `Thread.MAX_PRIORITY` | QDLink pone el lector a 10 |
| `controlQueueCapacity` | 1000 | Cola de control acotada |
| `sendAppStatus` | `true` | `!BIN` de 512 B al conectar (§4.2) |
| `heartbeatEnabled` / `heartbeatInitialDelayMs` / `heartbeatPeriodMs` | `true` / 1000 / 3000 | §5.1 |
| `watchdogEnabled` / `watchdogCheckIntervalMs` | `true` / 1000 | |
| `watchdogWarnMs` / `watchdogTimeoutMs` | 5000 / 15000 | Recomendación §5.3 (QDLink corta a los 5-10 s) |
| `watchdogRequiresCarTraffic` | `true` | Como QDLink: no corta hasta que el coche ha hablado 5A5A |
| `writeStallTimeoutMs` | 10000 | Cierra si un `write()` se queda bloqueado |
| `replyPhoneInfo` / `replyUpdateNotify` / `updateNotifyStatus` | `true` / `true` / 5 | `CAR_INFO` → `PHONE_INFO` + `UPDATE_NOTIFY` |
| `replyVideoSupport` / `videoSupportFormat` / `videoSupportValue` | `true` / 3 / 1 | `VIDEO_SUP_REQ` → `VIDEO_SUP_RSP` inmediato |
| `replySpeechArgs` / `speechArgs` | `true` / 16000-1-1-16 | `VIDEO_ARGS` → `SPEECH_ARGS` |
| `replyLandMode` / `landModeAuthority` / `landModeStatusArg` | `true` / 1 / 0 | §6.7, sin girar nada |
| `echoLegacyHeartbeat` | `true` | §4.3 |
| `replyDisconnectReq` / `closeOnDisconnectReq` | `false` / `false` | QDLink ignora `DISCONNECT_REQ` |
| `btResultProvider` | `null` | La app decide (§6.8) |
| `whitelistMode` / `whitelistValue` / `whitelistInitialDelayMs` / `whitelistPeriodMs` | `AUTO` / 1 / 1000 / 1000 | `AUTO` = solo si `legal_app_watch == 1` (§6.6) |
| `announceUnlocked` | `false` | Opción: `LOCK_SCREEN_STATUS{3}` tras `VIDEO_SUP_RSP` |
| `phone` (`PhoneIdentity`) | S25 Ultra FHD+, "samsung", "SM-S938B", SDK 36, Version "1.9.7" | Rellenar con los datos reales |
| `phoneInfoOverrides` | todo `null` | Forzar campos de `PHONE_INFO` (§10.3) |
| `qdlinkStrictParsing` | `true` | `CAR_INFO`/`VIDEO_ARGS` como `h0/c`: la primera clave que falta deja las siguientes a 0 |
| `requireVideoArgsForPlay` | `true` | `VIDEO_CTRL{1}` sin `VIDEO_ARGS` no arranca (QDLink: NPE) |
| `pauseOnVideoCtrlStop` | `false` | QDLink ignora `PlayStatus ≠ 1` |
| `sendVideoBeforePlay` | `false` | Experimentos |
| `resendCodecConfigOnKeyframeRequest` / `resendCodecConfigAfterDrop` | `true` / `true` | |
| `videoOverrides` | todo `null` | Forzar W×H, fps, bitrate, GOP, encodingType, appType, ángulo, orientación |
| `videoBacklogFrames` / `videoBacklogBytes` / `videoQueueHardLimitBytes` | 6 / 4 MiB / 32 MiB | |
| `minKeyframeRequestIntervalMs` | 1000 | |
| `traceVideoFrames` / `traceMaxJsonChars` / `traceHexPrefixBytes` | `true` / 4096 / 64 | |

**Siempre "desbloqueado y en primer plano"**, para que el coche siga mostrando vídeo con la pantalla apagada:

- no se manda `LOCK_SCREEN_STATUS` ni `CAR_APP_BACKGROUND` salvo a mano;
- el AppStatus dice `value` 1, igual que QDLink.

### 4.2 `DiscoveryConfig`

- `port` 18463 y `ackPort` 18464.
- `bindAddress` `null` (todas las interfaces).
- `receiveBufferSize`: 65 535. QDLink usa 1024 y trunca.
- `reuseAddress` y `broadcast`: `true`.
- `ackPolicy`:
  - `AckPolicy.QDLINK` (un solo ACK) es el valor por defecto;
  - `AckPolicy.RESEND_UNTIL_20S` reenvía cada 2 s desde los 3 s hasta los 20 s;
  - también se puede dar una política propia.
- `deviceName`, `deviceUuid` y `passistMobileNum` del ACK: vacíos, como QDLink.

El ACK sale siempre del socket de escucha, así que el puerto origen es 18463. Con un `MirrorPort` de 5 cifras mide
174 B, exactamente como el de QDLink.

### 4.3 `PhoneLinkConfig`

| Parámetro | Por defecto |
|---|---|
| `discovery` | `DiscoveryConfig()` |
| `session` | `SessionConfig()` |
| `mirrorPort` | `RANDOM_PORT` |
| `acceptTimeoutMs` | 20 000 |
| `autoConnect` | `true` |
| `carFilter` | todos los coches |
| `reconnect` | `true` |
| `retryDelayMs` | 3000 (espera entre intentos automáticos) |

---

## 5. Desviaciones conscientes respecto a QDLink

- Lector robusto (spec §3.6):
  - `readFully` de cabecera y cuerpo;
  - tope de 8 MiB;
  - resincronización buscando `5A5A`/`!BIN` byte a byte. QDLink hace un solo `read()` y descarta de 16 en 16.
- Un EOF del coche cierra la sesión (`CloseReason.Kind.EOF`). QDLink sigue mandando heartbeats al vacío.
- Watchdog con aviso (5 s) y corte (15 s), además de un detector de `write()` bloqueado.
- Un solo hilo escritor con cola y prioridad para el control. QDLink lanza un hilo por mensaje y no garantiza el
  orden. Aquí `PHONE_INFO` siempre sale antes que `UPDATE_NOTIFY`.
- `VIDEO_SUP_RSP` inmediato: no hay MediaProjection que esperar.
- `KEY_FRAME_REQ`, inicio del vídeo y atascos: además de reenviar SPS/PPS se pide un IDR de verdad. Un
  `VIDEO_CTRL{1}` repetido reenvía SPS/PPS y pide IDR, sin cortar los P-frames.
- Un `!BIN` del coche no cambia el "formato" de la sesión: se sigue hablando 5A5A. En QDLink, recibir un `!BIN`
  cambia `W` y estropea la sesión.
- `BT_RESULT` solo se manda si la app lo decide. Nunca se empareja Bluetooth por nuestra cuenta.
- Formato de los mensajes que enviamos: JSON byte a byte idéntico al de QDLink en Android ≥ 7, con el orden de
  `java.util.HashMap`. Eso significa `{"PARA":{…},"CMD":"…"}` y `{"FunctionID":…,"AppID":…,"Para":{…}}`.

---

## 6. Simulador de coche (`CarSim`) para `:carsim` y tests

```kotlin
val sim = CarSim(
    CarSimConfig(
        broadcastAddress = InetAddress.getByName("192.168.43.255"), // o 255.255.255.255; en local, 127.0.0.1
        phoneDiscoveryPort = 18463, carAckPort = 18464,
        carInfo = CarInfoValues(carWidth = 1920, carHeight = 1080),  // CarFactory 018, CarType 2D4, HUFactory 119
        videoArgs = VideoArgsValues(frameRate = 30, bitRate = 4_000_000, frameInterval = 1),
        recordVideoTo = File("recibido.h264"),                      // ffplay -f h264 recibido.h264
    ),
    object : CarSimListener {
        override fun onStateChanged(state: CarSimState) = println("estado: $state")
        override fun onVideoFrame(info: VideoFrameInfo) { if (info.errors.isNotEmpty()) println(info.errors) }
        override fun onTrace(event: TraceEvent) = println(event)
    },
    QdLog.STDOUT,
).start()

sim.awaitState(CarSimState.STREAMING, 30_000)
sim.tap(960f, 540f)
sim.drag(100f, 500f, 1800f, 500f, steps = 20, durationMs = 400)
sim.pinch(960f, 540f, startDistance = 200f, endDistance = 600f)
sim.sendPhoneKey(2)                                // Atrás
sim.sendMusicKey(FunctionIds.NEXT)
sim.requestKeyframe()                              // KEY_FRAME_REQ
sim.sendLandModeReq(1); sim.sendBtAddr("AA:BB:CC:DD:EE:FF", 0, 0); sim.sendDisconnectReq()
sim.goSilent()                                     // deja de mandar nada (prueba el watchdog del móvil)
println(sim.report().summary())                    // CarSimReport: handshake, AppStatus, vídeo validado…
sim.close(); sim.awaitTermination(5_000)
```

Pasos de `CarSim`:

1. Manda `Connect_Broadcast` cada `broadcastIntervalMs` desde el puerto `carAckPort` hasta recibir el ACK.
2. Conecta por TCP a la IP origen del ACK (o a `connectHost`).
3. Hace el handshake del coche esperando cada respuesta: `CAR_INFO` → `PHONE_INFO`, `VIDEO_SUP_REQ` →
   `VIDEO_SUP_RSP`, `VIDEO_ARGS` → `SPEECH_ARGS` y, por último, `VIDEO_CTRL{1}`.
4. Manda heartbeats y valida cada mensaje de vídeo:
   - cabeceras 16 + 32;
   - eco de `VIDEO_ARGS`;
   - W×H = `par(CarW)×par(CarH)`;
   - appType;
   - start codes y tipos de NAL;
   - orden: primero SPS/PPS, después IDR, y ningún P antes del primer IDR.
5. Compara el AppStatus byte a byte con el de QDLink.

`report()` devuelve un `CarSimReport` con el ACK, los mensajes del teléfono por tipo y en orden, los heartbeats y sus
intervalos, el vídeo (contadores, primer tipo, última cabecera y errores, con `videoValid`), lo inesperado y el motivo
de cierre. `sentTouches()` devuelve los táctiles enviados.

Cada `CarSim` sirve para una sola conexión. Para otra, hay que crear uno nuevo.

---

## 7. Utilidades sueltas

- **JSON**:
  - `JsonParser.parseObject(text, allowTrailing = true)` imita `new JSONObject(String)` de Android, que ignora lo que
    venga detrás del objeto.
  - `json.string/int/long/double/bool/obj/array(key)` tienen la coerción de `getX()` de `org.json` y devuelven `null`
    donde `org.json` lanzaría una excepción.
  - `toJson()` produce la misma salida que `JSONObject.toString()` de Android: escapa `/` y los caracteres de
    control, y escribe los enteros sin decimales.
- **Códecs**:
  - `PhoneMessages.xxx()` devuelve el mensaje 5A5A completo y `PhoneMessages.xxxJson()` solo el texto.
  - `TouchCodec.parse/build`, `VideoMessage.build`, `UdpCodec.parse/buildBroadcastAck/buildConnectBroadcast` y
    `BinBlock.appStatus/legacyHeartbeatReply`.
- **`FrameReader`**: `next()` devuelve `WireMessage.Frame`, `Bin` o `Garbage`, o `null` en un EOF limpio. Sirve para
  leer cualquier volcado: `FrameReader.readAll(bytes)`.
- **`MirrorGeometry.forCarInfo(pL, pS, carW, carH)`** reproduce la aritmética `float` de QDLink (spec §8.6).
- **`Hex.dump(bytes)`** usa el mismo formato que la spec.

---

## 8. Tests

```
cd qdauto && export JAVA_HOME=/d/Android/jdk && ./gradlew --console=plain :core:test
```

- **Vectores byte a byte de la spec**: heartbeat, AppStatus, ACK de 174 B, `Connect_Broadcast`, `VIDEO_SUP_RSP`,
  `WhitelistAppOn`, `PHONE_INFO` de 412 B, el resto de JSON con su `totalSize`, cabeceras de vídeo, táctil de 1 y
  2 dedos, eco del heartbeat legado y la tabla de geometría de §8.6. Se comprueba además que el orden de claves es
  el de `java.util.HashMap`.
- **JSON**: ida y vuelta y salida idéntica a `org.json`.
- **`FrameReader`**: entrega byte a byte, basura y magic incorrecto, `totalSize` imposible, `!BIN` con datos extra y
  EOF a medias.
- **Política de la cola de vídeo**.
- **Sesión contra un coche falso**: respuestas exactas, desconocidos, watchdog, EOF y opciones.
- **`DiscoveryListener`**: deduplicación, puerto origen del ACK y reenvíos.
- **`PhoneLink`**: reconexión, timeout de `accept()` y conexión extra.
- **Extremo a extremo** (`EndToEndTest`, en 127.0.0.1 con puertos no estándar):
  - handshake completo;
  - SPS/PPS y 60 frames mientras el coche manda táctil y teclas;
  - el vídeo llega válido y en orden;
  - el táctil llega con los valores exactos;
  - el watchdog corta cuando el coche se calla;
  - todos los hilos terminan.
