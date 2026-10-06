# Origen de `:qdcore`

Copia del núcleo de QDAuto (`qdauto\core`, que no es un repositorio git) hecha el
2026-10-04 para la rama `qdauto` del fork. `qdauto/` no se modifica nunca: los cambios del fork se hacen aquí y se
anotan abajo para poder llevarlos de vuelta si se quiere.

- Paquete sin cambios: `dev.qdauto.core`.
- Compilación: KGP 1.9.22 del fork, bytecode Java 8, `-Xjvm-default=all` y `-Xjdk-release=1.8` (`build.gradle.kts`).
- Comparar con el original: `sha256sum` de cada fichero de `qdauto/core` frente a la tabla del final; un `diff -r`
  entre `qdauto/core/src` y `qdcore/src` enseña exactamente los cambios locales.
- Tras cada cambio en `:qdcore`: `.\gradlew.bat :qdcore:test :app:assembleGithubDebug` y escaneo de API
  (`javap -v -p -c` de `qdcore/build/classes/kotlin/main` → `python tools/apiscan.py javap.txt
  <sdk>\platforms\android-36\data\api-versions.xml 16`, más `d8 --min-api 16` + `dexdump -d`).

## Retoques de compatibilidad (C1)

| Fichero | Cambio |
|---|---|
| `discovery/DiscoveryListener.kt` | `ConcurrentHashMap.compute` (API 24) sustituido por un bloque `synchronized(cars)` con la misma deduplicación por (`DeviceUUID`, IP) y la misma semántica de `onCarFound`/`onCarSeen`. |
| `session/PhoneSession.kt` | Fuera `removeOnCancelPolicy = true` (API 21): la sesión no cancela tareas sueltas y el temporizador se cierra con `shutdownNow`. |
| `discovery/DiscoveryModels.kt` | `host` = `address.hostAddress ?: address.toString()` (`getHostAddress` es `@RecentlyNullable` en `android.jar`). |

Comprobación de C1: 67/67 tests. `tools/apiscan.py` (minSdk 16) solo encuentra los `Boolean/Integer/Long/Float/
Double.hashCode(x)` estáticos de API 24, que D8 reescribe (*backports*). El `dexdump` de `d8 --min-api 16` no tiene
llamadas a `ConcurrentHashMap.compute`, `setRemoveOnCancelPolicy`, `java/util/function` ni `java/time`; las únicas
apariciones de `java/util/function` son las declaraciones sintéticas de `Map`/`List` en `JsonObject`/`JsonArray`,
que nunca se llaman en API < 24.

## Ampliaciones de la API (diseño §3)

Todas aditivas: con los valores por defecto el comportamiento es el validado en el coche. Los 67 tests originales no
cambian y los nuevos van en ficheros propios.

| Commit | Qué | Tests |
|---|---|---|
| C2 | Finalización por frame: `FrameCompletion`/`FrameDone`/`FrameOutcome` (`WRITTEN`, `DROPPED`, `REJECTED`, `CLOSED`, `FAILED`), exactamente una vez y nunca con candados tomados; sobrecargas de `sendFrame` con `completion`. `SendQueue` devuelve lo que descarta o deja al cerrar y expone el escritor por `poll`/`takeVideoHead`. Políticas `VideoDropPolicy` (`BACKLOG` por defecto, `MAX_LAG`, `NONE`) con `videoMaxLagMs`; con cualquier política, si la cola sigue por encima de la válvula de 32 MiB se tiran los P y se espera un IDR (con `BACKLOG` y los valores por defecto no ocurre nunca). Consultas sin candado: `io()` (`IoSnapshot`), `videoQueueFrames()`, `videoQueueBytes()`, `isWaitingForKeyframe()`, `videoFlushes()`. | `FrameCompletionTest` (incluye estrés de 50×2000 frames), `VideoDropPolicyTest`, `IoSnapshotTest` (helper `ControlledCar`) |
| C3 | Puerta de escritura de vídeo `VideoWriteGate` (`videoWriteGate`, `videoWriteGateMaxWaitMs` 250, `videoWriteGatePollMs` 2): el escritor la consulta sin candado antes de cada mensaje de vídeo; el control sigue saliendo y la espera máxima por elemento no la reinician los mensajes de control. Socket: `socket` público (solo lectura), `trafficClass` (con su propio `try`), `socketConfigurator` y `shutdownInput`/`shutdownOutput` antes de `close` (el FIN sale aunque haya duplicados del descriptor). `onThreadStart(ThreadRole)` en lector, escritor, temporizador y eventos. `phoneInfoOverridesFor(CarInfo?)` en `CAR_INFO` y `resendPhoneInfo`. `closeLocal(motivo)` y `CloseReason.Kind.SUPERSEDED`. | `VideoWriteGateTest`, `SocketHooksTest` (socket espía con `implAccept`), `PhoneInfoProviderTest` |
| C4 | `PhoneLink`: listener propio por sesión (`sessionListenerFactory`, vía `ForwardingSessionListener` con todos los métodos explícitos; fijado antes de `start()`), `sessionConfigFor(car)`, `acceptFilter` (rechaza, cierra y sigue esperando con el tiempo que quede), `mirrorBindAddressFor(car)` (si el *bind* falla, todas las interfaces), re-ACK (`reAckIntervalMs`) con los broadcasts del mismo coche, relevo (`supersedeOnRebroadcast`, `supersedeMinSessionAgeMs` 2000, `supersedeMinSilenceMs` 1000) que cierra con `SUPERSEDED` y conecta en el acto, `closeSession(motivo)`, `isConnecting`, avisos `onAckSent`, `onConnectionRejected` y `onSessionSuperseded`. `PhoneSession.startedAtNanos`. `CarSim`: `pauseReading(ms)`/`resumeReading()`, `closeAbruptly()` (RST) y `CarSimConfig.ignoreAcks`. | `PhoneLinkReconnectTest` (10 tests: reflexión, fábrica + relevo, sin relevo, re-ACK, filtro, *bind*, reconexión inmediata, cierre con motivo), `EndToEndReconnectTest` (5 sesiones seguidas con un pipeline que vive entre ellas) |
| C9b | `PhoneLink`: un broadcast que llega cuando la sesión ya se cerró pero su intento aún no ha terminado (el `endAttempt` va detrás del `onClosed`, en el hilo de eventos) da el intento por terminado y conecta en el acto (con `retryDelayMs = 0`), en vez de perder ese broadcast; con espera entre intentos, el enlace vuelve a `SEARCHING` (lo que habría hecho `endAttempt`) y conecta pasada la espera. Visto con `:qdsim --scenario stall`. | `PhoneLinkReconnectTest.aBroadcastWhileTheClosedSessionIsStillWindingDownReconnectsAtOnce` (sin el arreglo: 1013 ms; con él, < 400 ms) y `withARetryDelayTheBroadcastInTheWindingDownGapLeavesTheLinkSearching` |
| IDR grandes (2026-10-05) | Tope de mensaje de vídeo `SessionConfig.maxVideoMessageBytes` (**por defecto 480 KiB**, `0` = sin tope; `CAR_RECEIVER_LIMIT_BYTES` = 512 KiB medido en el C10). **Cambia el comportamiento por defecto**: un frame cuyo mensaje (48 B + Annex-B) pase del tope no se encola (`PhoneSession.sendFrame` lo mira antes de construir el mensaje; `SendQueue.offerFrame` también, con cualquier `VideoDropPolicy`): `FrameOffer.DROPPED_OVERSIZED`, finalización `DROPPED`, espera del siguiente IDR (con SPS/PPS delante si `resendCodecConfigAfterDrop`), `SessionListener.onVideoFrameOversized(OversizedFrame)` y `onKeyframeRequested(KeyframeReason.OVERSIZED)` forzado, en ese orden. `SessionStats`: `maxVideoMessageBytes`, `videoFramesOversized`, `maxOversizedBytes`. | `OversizedFrameTest` (3), `SendQueueTest` (+3); los tests que bloquean el `write()` con IDR de 0,5-2 MB llevan `maxVideoMessageBytes = 0` |
| Manías del C10 en `CarSim` (2026-10-05) | Mismo cambio que en `qdauto/core` (lado coche simulado, nada del teléfono). `CarSimConfig.receiverLimitBytes` (**512 KiB** por defecto, `C10_RECEIVER_LIMIT_BYTES`; 0 = sin límite), `receiverHangMs` (10 s) y `largeMessageBytes` (480 KiB, `RECOMMENDED_MAX_MESSAGE_BYTES`): con un mensaje de vídeo mayor que el límite el simulador deja de leer el TCP `receiverHangMs` (sin dejar de mandar heartbeats) y cierra, como el receptor del coche; `CarSimListener.onReceiverHang(ReceiverHang)` y `CarSimReport.receiverHang`, `videoMaxMessageBytes`, `videoLargeMessages`. `CodecConfigTracker` (fichero nuevo): cada SPS/PPS recibido con intervalo, si había `KEY_FRAME_REQ` pendiente, si es idéntico al anterior y qué le sigue (`CodecConfigEvent`/`CodecConfigVerdict`/`CodecConfigSummary`), en `CarSimReport.codecConfigs`; `requestKeyframe()` lo anota. `VideoFrameInfo.messageBytes`. Lo usa `:qdsim` (comprobaciones `tamano_mensaje`, `sps_repetido` y `decodifica`). | `CodecConfigTrackerTest` (6), `ReceiverHangTest` (2) |

| Vuelta tras un corte de radio (2026-10-06) | **Cambia el comportamiento por defecto** (todo se apaga con `RecoveryConfig.OFF`, `reAckOnBroadcast = false` y `AckPolicy.QDLINK`). `DiscoveryConfig.ackPolicy` por defecto `AckPolicy.UNTIL_CONNECTED` (cada 2 s hasta el TCP); `AckPolicy` con tramo lento (`slowAfterMs`/`slowIntervalMs`, `delayAfter`, `schedule`) y `UNSOLICITED` (2 s el primer minuto, luego 5 s); `DiscoveryListener`: `reopen(motivo)` (mismo puerto, hilo de recepción nuevo, los reenvíos salen por el socket abierto), ACK no pedidos (`sendAck(…, unsolicited)`, `Callback.onAckSent(…, unsolicited)`, `onReopened`), cuentas (`datagramsReceived`, `lastDatagramAtNanos`, `solicitedAcksSent`, `unsolicitedAcksSent`, `reopenCount`, `isOpen`) y `AckHandle.lastSentAtNanos`. `MirrorServer`: `SO_REUSEADDR` explícito. `PhoneLink`: `reAckOnBroadcast` (por defecto sí; `reAckIntervalMs` 0 = cada broadcast), `RecoveryConfig` (fichero nuevo `Recovery.kt`: puerto estable, re-acogida en `LinkState.RECOVERING` con el puerto de la sesión perdida y ACK no pedidos, vigilancia `qd-link-watch` con diagnóstico cada 10 s y reapertura del UDP tras 20 s sin datagramas, `CarReturn`, `LossWatch`), intento nuevo si el mismo coche llega desde otra IP, `isRecovering`, `stableMirrorPort`, `tcpAcceptCount`, avisos `onReclaimStarted`, `onCarBack`, `onDiscoveryReopened`, `onAckSent(…, unsolicited)`. `CarSim`: `maxBroadcasts`, `directMirrorPort`. | `PhoneLinkRecoveryTest` (11), `RecoveryLogicTest` (4), `DiscoveryListenerTest` (+3); `PhoneLinkTest`/`PhoneLinkReconnectTest` piden `QDLINK` y `reAckOnBroadcast = false` donde prueban un ACK por intento |
| Cable USB (2026-10-06) | Aditivo: por TCP y sin `blockFraming` todo es igual. **Transporte**: `SessionTransport` (`kind`, `blockSize`, `socket?`, `remoteAddress`, `describe`, `open`, `close`) con `TcpTransport(socket, blockSize = 0)` (las opciones del socket y el `shutdown` antes de `close`, que antes estaban en `PhoneSession`) y `StreamTransport(input, output, kind, blockSize = 512, description, onClose)` (el accesorio USB o tubos). `PhoneSession(transport, …)` con el constructor de siempre `PhoneSession(socket, …)`; `socket` pasa a `Socket?` (`null` fuera de TCP), `transport` e `isBlockFraming` nuevos. **Trama por bloques** (`wire/BlockFraming`: `BLOCK` 512, `paddedSize`, `padding`, `pad`): `SocketWriter(blockSize)` rellena cada mensaje con ceros hasta un múltiplo de 512 en un solo `write()` (el `totalSize` no cambia; `SessionStats.paddingBytesSent`); `FrameReader(…, blockSize)` pide siempre múltiplos de 512 sin pasar del final con relleno del mensaje (primer `read()` de un bloque, como QDLink), salta el relleno y los ceros de más y tolera lecturas cortas y emisores que no rellenan (`paddedMessages`, `unpaddedMessages`, `paddingBytes`, `strayZeroBytes`; en `SessionStats`: `blockSize`, `paddingBytesReceived`, `carMessagesPadded`, `carMessagesUnpadded`). `PhoneLinkConfig.blockFraming` (la trama del USB sobre el TCP, para `qdsim --usb-framing`). Sin cola del kernel: `IoSnapshot.videoQueueLagMs` y `PhoneSession.videoQueueLagMs()` (el vídeo más viejo aún sin salir, con `SendQueue.videoHeadEnqueuedNanos()` sin candado). `ForwardingSessionListener` pública y abierta. `CarSim`: `CarSimConfig.blockFraming` y `startOnStreams(input, output, label, closer)`; `CarSimReport.blockFraming`, `phonePaddedMessages`, `phoneUnpaddedMessages`, `phonePaddingBytes`, `phoneStrayZeroBytes`. | `BlockFramingTest` (9: tamaños, relleno, lecturas de 1 a 513 B y al azar, `!BIN` de 512, emisor sin relleno, ceros de más, flujo sin bloques intacto, peticiones seguras para USB, cuerpos grandes), `UsbSessionTest` (6: sesión completa por tubos en memoria con la trama en los dos sentidos, lecturas de 7 B, cierre desde el teléfono, retraso de la cola sin cola del kernel, trama del USB sobre TCP, teléfono sin trama detectado); helper `MemoryPipe` |

Total: 156 tests (67 originales + 89 nuevos).

## Ficheros copiados (sha256 del original)

| Fichero | sha256 |
|---|---|
| `src/main/kotlin/dev/qdauto/core/discovery/DiscoveryListener.kt` | `fb6dc974b0bc3be7036878b51cf01858c599bc1563511aa3f67cbc8186701c66` |
| `src/main/kotlin/dev/qdauto/core/discovery/DiscoveryModels.kt` | `f5ebb2a16b86e282a7a0e610367b7319f637e2d1d659280ca951e1ac65e7cbc6` |
| `src/main/kotlin/dev/qdauto/core/h264/AnnexB.kt` | `b9c3df73a39f909ca4573e950d6c60609e2bb15146eb1442e5d22fe02134d260` |
| `src/main/kotlin/dev/qdauto/core/json/JsonParser.kt` | `bc29b4433c25d5520821112e5ede5da09f2e0949c86ec9f31a95d311d7f02066` |
| `src/main/kotlin/dev/qdauto/core/json/JsonValue.kt` | `1c11837a1a28666eb7a6b11bfc3a58ceecc331642ed8a3e5a054af02417de6bb` |
| `src/main/kotlin/dev/qdauto/core/json/JsonWriter.kt` | `2f89eddd59eab76b4b3a47e4c8205b126c632b2d833c7f143162ffe313ec983e` |
| `src/main/kotlin/dev/qdauto/core/session/EventDispatcher.kt` | `fa3ede93f732bf04363be7f119ceeec0ea5f153a3444107ff94ac7de85b713d7` |
| `src/main/kotlin/dev/qdauto/core/session/InboundHandler.kt` | `0af892809b3315fce6ddec11da27d273f55ea1d14bd9d5ddd949689d1a9cdffe` |
| `src/main/kotlin/dev/qdauto/core/session/MirrorGeometry.kt` | `ccc2a892f5616f5f2b50e80e822516c4fe703a0a720917ed8e22c9e8b99134eb` |
| `src/main/kotlin/dev/qdauto/core/session/MirrorServer.kt` | `8ab4bc526d8b3bd10927533feb8b43fc64fc0d406345029315eb3c39d44cf942` |
| `src/main/kotlin/dev/qdauto/core/session/PhoneInfoFactory.kt` | `8f80d51821ba98a2b75024d98a1bcb98b3906a8bcd10bc56f5b22aba213eefcd` |
| `src/main/kotlin/dev/qdauto/core/session/PhoneLink.kt` | `56c38953dcb59e0c607f05b21fea67d50ec04b693150bd96440427e0426b7ea1` |
| `src/main/kotlin/dev/qdauto/core/session/PhoneSession.kt` | `f033bf29f1e15d086af17ad17a6843de480e6936d0260b4750b2db040e2fb29c` |
| `src/main/kotlin/dev/qdauto/core/session/SendQueue.kt` | `c575e34b745586a44995cef4fafb9c378237370ff9a26959cb285050451ee8db` |
| `src/main/kotlin/dev/qdauto/core/session/SessionConfig.kt` | `1309c12d188bdde7b4013088675179f83d2a0f3bb1dbc05258ec86e56270465e` |
| `src/main/kotlin/dev/qdauto/core/session/SessionCounters.kt` | `27178a252c30759b9cc381de0eafe8b892226afbdfda86e4267528fc47785e3b` |
| `src/main/kotlin/dev/qdauto/core/session/SessionModels.kt` | `e0f5d2a3f682f9ed9f476a35d8ee2bd3bbfedc1c05dbae9db52fd6a733361c24` |
| `src/main/kotlin/dev/qdauto/core/session/SocketWriter.kt` | `2fecab7d0df2f177dc6ad43502d65a6225cffac3c64673e8b3e2a5a36f2c597a` |
| `src/main/kotlin/dev/qdauto/core/sim/CarSim.kt` | `ffdfe50677e2f5c7a77129bb776a62b79770b2a36c73378186d718f433b92c8d` |
| `src/main/kotlin/dev/qdauto/core/sim/CarSimConfig.kt` | `1c765c9e085771346f57bea4f767056c55d7bf2a61787249b0f58fc2c8b053be` |
| `src/main/kotlin/dev/qdauto/core/sim/CarSimReport.kt` | `415e0bc4133399933a6bcb5003a16ad8df128b861c8c8e8ec46602fc20b1d81c` |
| `src/main/kotlin/dev/qdauto/core/sim/PhoneObserver.kt` | `fd85b00d8ae9e895fc60144fa218053e3dbc4a1eddd7b7c224b748eca17c6637` |
| `src/main/kotlin/dev/qdauto/core/sim/VideoValidator.kt` | `dee92e74d505d930d4dcc2bdc921200dad6e74cce117522d850035e676511f41` |
| `src/main/kotlin/dev/qdauto/core/util/BE.kt` | `ab2404308a15a523c8c0cbe60328167d2834ddec26d1798b1aa7ac5364d39c4e` |
| `src/main/kotlin/dev/qdauto/core/util/Hex.kt` | `456102ef4971f1fc86e845e5e575336676a94c517af59a9272b2e6f9bbdf1fa1` |
| `src/main/kotlin/dev/qdauto/core/util/QdLog.kt` | `20196d240ecee5c1f7e23c32f5d1fd9e7c87b13820ffc9f9848dc0174d861116` |
| `src/main/kotlin/dev/qdauto/core/util/RateWindow.kt` | `3212e7539837aeefc8cea50b5cfabde37089d5c905ade273be2518d7c3613f14` |
| `src/main/kotlin/dev/qdauto/core/wire/BinBlock.kt` | `e192a1aada2168944629f7f9275b0fdd8e8e68929d24558fef8487144a9bd53d` |
| `src/main/kotlin/dev/qdauto/core/wire/CarMessages.kt` | `4552c2fd077bc8f4e1ebb595fb18011ca3da7dacf414310e9228b7958cf34d18` |
| `src/main/kotlin/dev/qdauto/core/wire/FrameReader.kt` | `539427f76d9e640a80b6248615191e3a8e7a5cf37c288affa2b4da1ca719258c` |
| `src/main/kotlin/dev/qdauto/core/wire/Header.kt` | `1e03b78a1b84a06f6c2d8faea210323d2a466f39297dd3207e52713046456432` |
| `src/main/kotlin/dev/qdauto/core/wire/Inbound.kt` | `0afecf250ffd8e42324c7e7fb638da7a5d22a76b711d9add9d1769b363194d88` |
| `src/main/kotlin/dev/qdauto/core/wire/Names.kt` | `26021a4373f27796ae10bc54f17ca62f7fb0a6aefc99853fc79848e21fc6637a` |
| `src/main/kotlin/dev/qdauto/core/wire/PhoneMessages.kt` | `def0fc1f7600611e828f3387d8f89f115f825485f0b6f91e702c6c3980ebb0db` |
| `src/main/kotlin/dev/qdauto/core/wire/Touch.kt` | `f9242cb5675e58a6109c7c3adf5980b5c25175b3faf8c9fd55478a31b31a77ad` |
| `src/main/kotlin/dev/qdauto/core/wire/Trace.kt` | `f96dadb9e11559115178c1a9ffc24e6ceddbff31b1807c9ed60a3bf596f662aa` |
| `src/main/kotlin/dev/qdauto/core/wire/UdpCodec.kt` | `e667621af50ab137ead2f3cbb30840c8575aaa9ce35c0db1f067b046eb7739a0` |
| `src/main/kotlin/dev/qdauto/core/wire/VideoMessage.kt` | `cb4d01c9bed9f099c11befa77d980041f20f4313dfea96f20a66a60395213184` |
| `src/test/kotlin/dev/qdauto/core/EndToEndTest.kt` | `2ad7a3d5d49a0de9a8c43b3d90801fe0f30e42494d12ee0810b0d5dc4e8f85bc` |
| `src/test/kotlin/dev/qdauto/core/TestSupport.kt` | `2dfec14a1b8da9d212335b2f57aff59f422067c909ff1176d0ea802b43506b3e` |
| `src/test/kotlin/dev/qdauto/core/discovery/DiscoveryListenerTest.kt` | `924a8141a82680e12478016424cd8efc837d969200f6532a6a0454ac7eafc9ad` |
| `src/test/kotlin/dev/qdauto/core/json/JsonTest.kt` | `1600d6429768d661425653d3fac64a7da46812b53745ebc0496d2be58a9c1dc7` |
| `src/test/kotlin/dev/qdauto/core/session/MirrorGeometryTest.kt` | `f196aea357c81ab0b0ff4c3e6d8061524f0729c88c31c7777bbd3a81b3d7b308` |
| `src/test/kotlin/dev/qdauto/core/session/PhoneLinkTest.kt` | `ada3b351d6945b730f45210590005b9487b0c12b05fbe89b9414d69c6608de88` |
| `src/test/kotlin/dev/qdauto/core/session/PhoneSessionTest.kt` | `2d601fb3ddddaa3f488ecf35cd967d1cac76f40b4557a1eb38df0164b7cbf7f8` |
| `src/test/kotlin/dev/qdauto/core/session/SendQueueTest.kt` | `1fba5ba2e28a883d4f1748e5617eb2650089c69ae3aaed3a7980a2b7c3efcba0` |
| `src/test/kotlin/dev/qdauto/core/session/SessionTestKit.kt` | `046f2611ce99325f0e77fb69f97a7cc9fc245c80ec5ba09ee9b57417b6731746` |
| `src/test/kotlin/dev/qdauto/core/util/UtilTest.kt` | `5557e270d99ab9ceb1a29214b4ae512059493f940f0cf52ba1813e1f0e1813af` |
| `src/test/kotlin/dev/qdauto/core/wire/FrameReaderTest.kt` | `a6b9e42362987b32e89f028233c5d5c39eb9dbef96a3b4b8b560f3d1221885f3` |
| `src/test/kotlin/dev/qdauto/core/wire/ParsersTest.kt` | `cd83dee825137f37d221808732a28e748f7630856c94e6cdae4f6c7339773f4e` |
| `src/test/kotlin/dev/qdauto/core/wire/SpecVectorsTest.kt` | `c81e8b80c34038c9cd1a35c0a0396ce87d7a3431f7093214d756ee37dddf7299` |
| `README.md` | `ec0c331791fde42453705873c505e0a4e42fc954a56de992f7ce45c98b554952` |
