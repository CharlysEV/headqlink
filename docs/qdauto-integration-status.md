# Estado de la integración del motor QDAuto (rama `qdauto`)

> **Fecha:** 2026-10-05 · **Rama:** `qdauto`, desde `312e8ee5` (parche de seguridad, que se conserva tal cual) ·
> **Diseño:** [`qdauto-integration.md`](qdauto-integration.md).
>
> **En una frase:** el plan C0-C10 del diseño está implementado, compila y pasa todas las pruebas que se pueden hacer
> en el PC. **Falta probarlo en el móvil y en el coche**: eso quedaba fuera de este trabajo (ni adb ni instalaciones).

Rutas relativas a `hql\`. `[hql]` = `app/src/main/java/com/headqlink/link/`.

---

## 1. Resumen

- **Núcleo `:qdcore`.** Copia de `qdauto/core` con los 3 retoques de compatibilidad, las ampliaciones de API del
  diseño (§3) y un arreglo de un fallo del original (C9b). Tiene 101 tests (67 originales y 34 nuevos), todos en
  verde. Bytecode Java 8 y API ≤ 16, comprobados con `tools/apiscan.py` y `d8 --min-api 16`. El origen y los cambios
  están en `qdcore/UPSTREAM.md`.
- **Motor QDAuto dentro del fork, motor por defecto.** Lo forman `QdLinkHost`, `QdSessionBridge`, `SessionPort`,
  `SessionConfigs`, `VideoHub`/`VideoPipeline` y el freno a AA. El motor original (`SspSession` + `UdpDiscovery`, sin
  tocar) sigue disponible como respaldo en **Ajustes de imagen › Avanzado › Motor de protocolo**.
- **Conexión con el coche.**
  - *Wi-Fi Direct*: como antes y por defecto.
  - ***Punto de acceso del móvil***: nuevo, con su interfaz en español, la guía para unir el coche, el estado de la
    zona Wi-Fi en la fila «Red» y en la notificación, y el filtro de pares por interfaz.
- **Reconexión sin reiniciar Android Auto.** El vídeo sigue vivo entre sesiones hasta `car_gone_ms` (30 s): Android
  Auto, el decodificador, el relay GL, el encoder y la interfaz del coche (radio, viaje, ruta). La sesión nueva:
  - conecta con el primer broadcast;
  - reutiliza ese vídeo si el plan coincide y está sano;
  - manda SPS/PPS en caché y un IDR real.

  También hay relevo de sesión (el coche se reanuncia con la sesión abierta) y re-ACK.
- **Registro de viajes.**
  - Log unificado rotativo `logs/qd-*.log`.
  - Detector de cortes de radio.
  - Resumen por sesión y del viaje, y `logs/sessions.csv`.
  - **Exportar log**: ZIP en `Descargas/HeadQLink` y hoja de compartir, sin adb.
- **`:qdsim`.** Es el coche simulado con los 6 escenarios del plan de pruebas. Con `--local-phone` lleva su propio
  móvil de prueba y funciona solo en el PC.

---

## 2. Qué hay en cada commit

`git log --oneline 312e8ee5..qdauto`, del más antiguo al más nuevo:

| Commit | Asunto | Qué trae |
|---|---|---|
| `316c0ac9` | docs: diseño de la integración del motor QDAuto | `docs/qdauto-integration.md` (C0). |
| `2bfa73f2` | qdcore: módulo con el núcleo de QDAuto, ajustado para minSdk 16 | C1. Copia literal en `:qdcore`, Gradle, 3 retoques (sin `ConcurrentHashMap.compute`, sin `removeOnCancelPolicy`, `host` nulo-seguro), `UPSTREAM.md` con sha256 y `tools/apiscan.py`. 67/67. |
| `1cc0fa6d` | qdcore: finalización por frame y políticas de descarte de vídeo | C2. `FrameCompletion` exactamente una vez y fuera de candados; `VideoDropPolicy` (`BACKLOG`, `MAX_LAG`, `NONE`); consultas sin candado (`io()`, colas, `isWaitingForKeyframe`). 79/79. |
| `53c73fab` | qdcore: puerta de escritura de vídeo, opciones de socket, ganchos de hilo y PHONE_INFO por coche | C3. `VideoWriteGate` (el control nunca espera); socket público, TOS, configurador y `shutdown` antes de `close`; `onThreadStart`; `phoneInfoOverridesFor`; `closeLocal`; `SUPERSEDED`. 89/89. |
| `3ce1d79c` | qdcore: PhoneLink con escucha por sesión, filtros de IP, re-ACK y relevo de sesión | C4. Listener por sesión, configuración por coche, `acceptFilter`, *bind* del `ServerSocket`, re-ACK, relevo y `CarSim` con cortes simulados. 100/100. |
| `ca1d9cf1` | registro: log unificado rotativo y arreglo del fallo con toques cortos | C5. `QdTrace` + `QdLogFile`, `LogRetention`, copia de `L` y arreglo del cierre del proceso con un táctil de < 5 bytes (`Proto.parseTouch`, `CarTrace.packet`). |
| `a82827ce` | conexión: modo punto de acceso del móvil | C6. `link_mode`, `HotspotWatcher`, `NetIfaces`, `LinkService` sin P2P en ese modo, interfaz (configuración, inicio, fila «Red»), permisos por modo. |
| `db1d5027` | enlace: motor QDAuto dentro del fork (paridad con SspSession) | C7. Adaptador completo y ajuste «Motor de protocolo», con `qd_keep_video = false` (como el fork). |
| `2801558b` | enlace: reconexión sin reiniciar Android Auto | C8. `qd_keep_video = true`, estado `RECONNECTING`, `car_gone_ms`, salud y reutilización de la *pipeline*, métrica «reconexión X ms». |
| `f0266703` | diagnóstico: detector de cortes, resúmenes por sesión y exportar log | C9. `StallDetector`, `SessionSummary`, `sessions.csv`, resumen del viaje, `HqlLogExport`, prueba con patrón y módulo `:qdsim`. |
| `2b7e8ab2` | qdcore: no perder el broadcast que llega mientras se cierra la sesión anterior | C9b. Arreglo de un fallo del núcleo original, visto con `qdsim --scenario stall` (§4.4). 101/101. |
| `c4def373` | enlace: ajustes del motor QDAuto tras revisarlo | Revisión del adaptador (§4.7). |
| `4ab25362` | enlace: el resumen del viaje incluye la última sesión y el relevo respeta el vídeo nuevo | Revisión del adaptador (§4.7). |
| `ae2a9b6f` | enlace: QDAuto pasa a ser el motor por defecto | C10 (§4.1). |
| `0d5c5b9d` | diagnóstico: opciones de prueba del motor QDAuto sin adb | Diagnóstico › «Opciones de prueba (QDAuto)» (§4.2) y modo noche en `qdsim --scenario reconnect`. |
| — | docs: estado de la integración del motor QDAuto | Este documento. |

---

## 3. Resultados en el PC

| Comprobación | Resultado |
|---|---|
| `:app:assembleGithubDebug` | **BUILD SUCCESSFUL**. APK: `app\build\outputs\apk\github\debug\com.headqlink.app_0.1-beta_debug.apk` (31 MiB). |
| `:qdcore:test` | **101/101** en 19 clases (67 originales + 34 nuevos). |
| `:app:testGithubDebugUnitTest --tests com.headqlink.link.*` | **46/46** en 10 clases: `AckSlotTest`, `KeyframePolicyTest`, `StallDetectorTest`, `PeerPolicyTest`, `QuickBackoffTest`, `VideoPlanTest`, `QdLogFileTest`, `ExportSelectionTest`, `NetIfacesTest` y `SessionSummaryTest`. |
| Escaneo de API de `:qdcore` (minSdk 16) | Solo aparecen `Boolean/Integer/Long/Float/Double.hashCode(x)` (API 24), que D8 reescribe. No hay `compute`, `setRemoveOnCancelPolicy` ni `java.time`. |
| Avisos del compilador | **Ninguno** de Kotlin (`:qdcore`, `:qdsim` y el código nuevo del app). En javac solo quedan avisos de antes: `source/target 8` obsoletos y 3 usos de API obsoleta en código que no se ha tocado (`CarBtReceiver`, `SystemMonitor.isStaConnected`, `Control.java` generado). Ninguno señala un fallo. |
| Android lint (`:app:lintGithubDebug`) | Sin errores en el código nuevo (§6, punto 10). |
| `:qdsim` con `--local-phone` | **6/6 PASS**: `normal`, `reconnect` (6 sesiones), `stall`, `silent` (relevo), `disconnect` y `rack` (re-ACK). |

Tiempos de `qdsim` contra el móvil de prueba local. Sirven de referencia del núcleo; **no son tiempos del coche**:

- reconexión: ACK +1 ms tras el broadcast; IDR a 7-35 ms de `VIDEO_CTRL{1}` en todas las sesiones;
- relevo: ACK +6 ms tras el primer broadcast;
- re-ACK: segundo ACK a +514 ms.

---

## 4. Desviaciones del diseño y por qué

### 4.1 C10 adelantado: QDAuto por defecto sin pruebas en el móvil ni en el coche

El diseño (D8 y §8) dejaba el cambio de motor por defecto para después de §9.3 y §9.4. Aquí se ha adelantado porque
el encargo pedía QDAuto por defecto. Esas pruebas no se podían hacer aquí.

El original queda a un toque: **Ajustes de imagen › Avanzado › Motor de protocolo › Original de headqlink**, y se
aplica al desconectar y volver a conectar. Si algo falla en la primera prueba con el coche, es lo primero que hay que
probar.

### 4.2 Las opciones ocultas ya no van por adb: «Opciones de prueba (QDAuto)» en Diagnóstico

El Anexo A preveía cambiar `qd_keep_video`, `qd_supersede`, `peer_strict`, `qd_phone_info` y `car_gone_ms` con extras
de adb a `LinkService`. Pero el parche de seguridad `312e8ee5` dejó `LinkService` con `exported="false"`, y desde
entonces Android no deja que el shell (`adb shell am …`) lo arranque, ni con extras ni sin ellos. Lo mismo pasa con los
extras de conexión y de motor, aunque esos dos sí tienen su ajuste en la interfaz.

En vez de volver a abrir una entrada externa, las cinco opciones están ahora en **Inicio › ⋮ › Diagnóstico ›
«Opciones de prueba (QDAuto)»**. Es la «salida de emergencia en el coche sin recompilar» que pedía el diseño (§10).
El código de los extras en `Config.applyExtras` se mantiene, pero solo lo alcanza la propia app.

### 4.3 Re-ACK y relevo desde C7 (el diseño los activaba en C8)

- **Re-ACK.** El fork ya responde con un ACK a cada broadcast. Para tener paridad desde C7, el motor QDAuto re-ACKea
  desde el principio: como mucho cada 400 ms, mientras el coche no conecta.
- **Relevo.** Va con su propia clave (`qd_supersede`, activa), independiente de `qd_keep_video`. Con `qd_keep_video =
  false` también hace falta: si no, tras un corte silencioso se vuelve a esperar al watchdog.

### 4.4 Arreglo de un fallo del núcleo original (C9b)

`qdsim --scenario stall` destapó una carrera en `PhoneLink.onBroadcast`:

1. La sesión anterior ya estaba cerrada, pero su intento aún no había terminado (`endAttempt` va detrás de
   `onClosed`, en el hilo de eventos).
2. Si el broadcast del coche llegaba justo entonces, se ignoraba.
3. La reconexión esperaba al broadcast siguiente: unos 500 ms más en el C10.

Ahora ese broadcast da el intento por terminado y conecta en el acto. Lo cubre un test que sin el arreglo da
1013 ms. **Conviene llevar este arreglo a `qdauto/core`**, que aquí no se podía tocar: está en `UPSTREAM.md` (C9b).

### 4.5 Detector de cortes (`StallDetector`)

Diferencias con §7.3:

- **`RADIO`** incluye también el caso en que solo se atasca la subida: el coche sigue hablando y su ventana es
  normal. La línea lo dice («el coche sigue hablando») y ese corte no termina porque llegue RX.
- **`COCHE_NO_LEE`** gana cuando la ventana del coche es 0, aunque además esté callado: ese 0 lo anuncia su propio
  TCP, así que la radio funciona.
- **`MOVIL_CONGELADO`** usa un umbral de 250 ms y no de 150 ms. El detector se evalúa cada 100 ms, y con 150 ms el
  jitter normal del hilo daría falsos positivos. El evento `stall` de 50 ms de `PerfTrace` conserva sus 150 ms.
- Hay una fila nueva **`stall_phone`** en `PerfTrace`, junto a `stall_radio` y `stall_peer`.

### 4.6 Añadidos pequeños fuera del diseño

- **Núcleo:**
  - `PhoneLink.isConnecting`: `carGone` no lo apaga todo si hay un intento en marcha;
  - `PhoneSession.startedAtNanos`: umbral de edad del relevo.

  Los dos están en `UPSTREAM.md`.
- **`PerfTrace`:** `beginSession()` devuelve un testigo y `endSession(testigo)` solo cierra esa sesión. Así, una
  sesión relevada que termina tarde no cierra el CSV de la nueva.
- **`:qdsim`:**
  - opciones `--local-phone` (para probarlo sin teléfono), `--broadcast-ms`, `--uuid` y `--name`;
  - el escenario `reconnect` manda `DarkModeOn:1` en S1, para comprobar en S2+ que AA sigue en noche (§9.4).

  No comprueba el tamaño ni el contenido de la imagen; solo mira el handshake, el orden SPS/PPS → IDR y los
  tiempos. En el móvil la cabecera puede ser legítimamente 720p, según el perfil. Lo demás («vídeo REUTILIZADO»,
  «reconexión X ms», cortes) se mira en el log del móvil (§5.6).

### 4.7 Fallos encontrados y corregidos por el camino

No son desviaciones, pero conviene saberlo:

- **Puerta de escritura (C3).** La espera máxima del vídeo se reiniciaba con cada mensaje de control. Se encontró con
  `jstack`; ahora es por elemento y un test lo cubre.
- **`QdLogFile.flush`.** Con la cola llena fallaba en el acto. Ahora espera sitio dentro del plazo, para que el
  manejador de fallos y la exportación no pierdan el final del log.
- **Revisión final del adaptador (`c4def373`, `4ab25362`):**
  - Cuando el UDP 18463 vuelve a estar libre (por ejemplo, al cerrar QDLink), la fila «Red» recupera el estado de la
    zona Wi-Fi o del grupo P2P; antes se quedaba en el error. El error se registra una vez, no cada 5 s.
  - El filtro de pares escanea las interfaces como mucho una vez por segundo, no con cada broadcast.
  - Una sesión relevada no borra la interfaz ni el detalle de la nueva. Con `qd_keep_video = false`, tampoco para
    el vídeo que la nueva ya ha enganchado (`VideoHub.stopFor`).
  - El tamaño del coche (`CAR_INFO`) que llega antes del inicio formal de la sesión ya no se pierde en la pantalla
    de inicio.
  - Al parar el servicio se cierra primero la sesión y se espera su resumen (como mucho 500 ms). Así el resumen del
    viaje la incluye, y se anota en el diario del coche antes de cerrarlo.
  - `VideoPipeline`: la cabecera del reenvío es `volatile` (la escriben y la leen hilos distintos).

---

## 5. Cómo probar

### 5.1 En el PC, sin móvil

```powershell
$env:JAVA_HOME='D:\Android\jdk'; cd hql
cmd /c ".\gradlew.bat :qdcore:test :app:assembleGithubDebug --console=plain"
cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest --tests com.headqlink.link.* --console=plain"
cmd /c ".\gradlew.bat :qdsim:installDist --console=plain"
qdsim\build\install\qdsim\bin\qdsim.bat --scenario reconnect --sessions 6 --duration 5 --local-phone
```

Cada escenario (`normal`, `reconnect`, `stall`, `silent`, `disconnect`, `rack`) termina con `==== resultado (…):
PASS` y código 0. `qdsim.bat --help` lista todas las opciones.

### 5.2 Ajustes y dónde están

| Ajuste | Dónde | Por defecto | Cuándo se aplica |
|---|---|---|---|
| Conexión con el coche: Wi-Fi Direct / **Punto de acceso del móvil** | Inicio › «Cambiar» (paso 2 de la configuración; el paso 3 dice si la zona Wi-Fi está activa y cómo unir el coche) | Wi-Fi Direct | Al desconectar y volver a conectar |
| Motor de protocolo: **QDAuto** / Original de headqlink | Inicio › ⋮ › Ajustes de imagen › Avanzado | QDAuto | Al desconectar y volver a conectar |
| Prueba sin Android Auto (patrón) | Inicio › ⋮ › Diagnóstico | Apagada | En el acto (se reinicia la sesión) |
| Mantener Android Auto entre sesiones (`qd_keep_video`) | Diagnóstico › «Opciones de prueba (QDAuto)» | Sí | Al cerrarse la sesión siguiente |
| Relevo de sesión (`qd_supersede`) | Ídem | Sí | Al volver a conectar |
| Filtro estricto de pares (`peer_strict`) | Ídem | No | Al volver a conectar |
| Presentarse como QDLink (`qd_phone_info`) | Ídem | No (identidad del fork) | Al volver a conectar |
| Espera a que vuelva el coche (`car_gone_ms`) | Ídem | 30 s (de 5 a 600) | En la próxima pérdida del coche |
| Exportar log | Diagnóstico | — | — |

«Volver a conectar» = «Desconectar» y «Conectar» en Inicio.

### 5.3 Móvil y PC con el coche simulado (diseño §9.3)

**Montaje.** Lo hace el usuario:

1. Instalar el APK de §3.
2. Cerrar QDLink del todo (Ajustes › Aplicaciones › QDLink › Forzar detención). Si no, ocupa el UDP 18463:
   HeadQLink lo avisa y reintenta cada 5 s.
3. En HeadQLink, poner la conexión en «Punto de acceso del móvil» y el motor en QDAuto (§5.2).
4. Activar la zona Wi-Fi del móvil y unir el PC a ella.
5. En el cortafuegos de Windows (la red de la zona Wi-Fi sale como «Pública»), dejar entrar a
   `D:\Android\jdk\bin\java.exe` el UDP 18464 y TCP.
6. Para probar sin Android Auto: Diagnóstico › «Prueba sin Android Auto (patrón)». Con Android Auto, ver §5.4.
7. Pulsar «Conectar». La fila «Red» debe decir «Zona Wi-Fi activa (swlan0 …)», o el nombre de la interfaz de ese
   móvil.

**Pruebas.**

1. **Handshake y vídeo con el carsim validado**, sin modificar:

   ```powershell
   $env:JAVA_HOME='D:\Android\jdk'; cd 
   qdauto\carsim\build\install\carsim\bin\carsim.bat --duration 60 --width 1920 --height 882 --fps 30 --bitrate 5080320 --gop 3 --out s1.h264 --report s1.json
   ```

   - Criterio: `RESULTADO: PASS`. Comprueba handshake, AppStatus, cabeceras, SPS/PPS antes del IDR y el táctil con
     pellizco.
   - Si no llega el ACK, añadir `--target <IP del móvil en la zona Wi-Fi>`.
   - El vídeo recibido se ve con `ffplay -f h264 s1.h264`.
2. **Escenarios de `:qdsim`** contra el móvil (sin `--local-phone`; con `--target <IP del móvil>` si hace falta):

   ```powershell
   cd hql
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario normal
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario reconnect --sessions 10 --duration 20 --gap-ms 200
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario stall --stall-s 5
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario silent
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario disconnect
   qdsim\build\install\qdsim\bin\qdsim.bat --scenario rack
   ```

   Los criterios son los de la tabla de §9.3 del diseño. En el log del móvil (§5.6) hay que ver además:
   - **`reconnect`:** «vídeo REUTILIZADO» en todas las sesiones menos la primera y «reconexión X ms» < 1 s;
   - **`stall`:** «Corte S1 INICIO …» y «FIN …» con horas coherentes;
   - **`silent`:** cierre de S1 con `SUPERSEDED`.
3. **Regresión del motor original:** cambiar a «Original de headqlink», desconectar y conectar, y repetir el punto
   1. Tiene que dar el mismo `PASS`.
4. **Exportar log:** Diagnóstico › «Exportar log» deja `HeadQLink-log-AAAAMMDD-HHMMSS.zip` en `Descargas/HeadQLink`.
   Compartirlo al PC y revisar `resumen.txt`, `logs/sessions.csv` y `logs/qd-*.log`.

### 5.4 Android Auto en el móvil, sin coche (diseño §9.4)

El montaje es el de §5.3, con la prueba con patrón **apagada** y el modo «Auto extendido» (`aa_ext`, el de por
defecto).

- **Imagen de AA:** `carsim … --out aa.h264` y `ffplay -f h264 aa.h264` tienen que mostrar la interfaz de AA.
- **Reconexión:** `qdsim --scenario reconnect --sessions 10`.
  - AA no se reinicia: no aparece «AA: lanzando Self-Mode» después de la primera sesión.
  - IDR ≤ 500 ms.
  - La radio y el viaje de la interfaz del coche no se cortan.
  - AA sigue en modo noche en S2-S10: S1 manda `DarkModeOn:1`.
- **Espera:** con más de 30 s sin coche se apaga todo, como en el fork. El siguiente «Conectar» vuelve a lanzar AA.
- **Básico (reenvío directo: modo «Auto» con el perfil de imagen «Básico»):**
  - la reconexión, con su ciclo de foco, en ≤ 1,5 s;
  - «freno AA: N acks retenidos» con N > 0;
  - tras 10 reconexiones AA sigue mandando vídeo;
  - sin ciclos de foco en cascada (`KeyframePolicy`).
- **Pantalla apagada y móvil bloqueado:** repetir lo mismo bloqueando el móvil a mitad.

### 5.5 En el coche (diseño §9.5)

1. **Zona Wi-Fi + QDAuto (Auto extendido):**
   - arrancar con la pantalla apagada;
   - 15 minutos de conducción;
   - un pellizco a propósito: tiene que verse el corte (INICIO/FIN), el EOF y la reconexión < 1 s **sin reiniciar
     AA**;
   - exportar el log al terminar.
2. **Wi-Fi Direct + QDAuto:** igual, con la zona Wi-Fi apagada.
3. **Wi-Fi Direct + original:** una sesión corta, para confirmar que el respaldo funciona como antes.
4. **(Opcional) Perfil Básico en la zona Wi-Fi:** freno y ciclos de foco.

De cada viaje hay que anotar `sessions.csv`, los cortes por tipo, `reconexion_ms` y si AA se reinició alguna vez
(«arranques de AA» en el resumen del viaje).

Si la reconexión diera problemas en el coche:

1. Probar primero sin relevo (Opciones de prueba › relevo desactivado).
2. Si sigue fallando, sin mantener Android Auto (comportamiento del fork).
3. En último caso, el motor original.

### 5.6 Qué buscar en el log (`logs/qd-*.log`)

| Línea | Significado |
|---|---|
| `motor QDAuto escuchando (zona Wi-Fi) · móvil …` | Arranque del motor. Con QDLink abierto, en cambio: «El puerto 18463 está ocupado». |
| `pares: broadcast de 10.x.x.x (…): aceptado — zona Wi-Fi · interfaz swlan0 …` | Decisión del filtro de pares (una vez por IP, interfaz y veredicto). |
| `T/S2 <- CAR_INFO …` / `T/S2 -> VIDEO_IDR …` | Traza de cada mensaje, con el JSON completo y el volcado hexadecimal. |
| `vídeo CREADO / REUTILIZADO / RECREADO para S2: …` | Qué pasó con el vídeo al enganchar la sesión. |
| `reconexión 330 ms desde el fin de S1 (broadcast +… · ACK +… · TCP +… · VIDEO_CTRL +… · IDR +…)` | Métrica de reconexión (§6.1). |
| `Corte S1 INICIO RADIO: …` / `sigue …` / `FIN tras … ms: …` | Detector de cortes, con hora absoluta, outq, retrans, rtt y contexto. |
| `==== S2 · … · fin …` (etiqueta `HQL/Resumen`) | Resumen de la sesión: handshake, vídeo, coche, radio y reconexión. |
| `HQL/Viaje` | Resumen del viaje al parar el servicio: sesiones, reconexiones, cierres, cortes, ciclos de foco y arranques de AA. |

---

## 6. Limitaciones conocidas y pendientes

1. **Nada probado todavía en el móvil ni en el coche.** Lo del PC no ejercita la parte Android: el «móvil de
   prueba» de `qdsim --local-phone` es el núcleo con los ajustes del fork, sin `VideoHub`, AA, GL, encoder ni
   interfaz. Siguen abiertas las preguntas de §10 del diseño:
   - si el C10 acepta el `PHONE_INFO` del fork enviado por el núcleo;
   - el TOS 0xA0;
   - el *bind* a la interfaz de la zona Wi-Fi;
   - el ACK repetido;
   - el reanuncio tras un cierre local;
   - si 30 s de espera bastan.
2. **QDAuto es el motor por defecto sin esas pruebas** (§4.1). El respaldo está a un toque.
3. **Reenvío directo (perfil Básico).** El IDR de una sesión nueva sale de un ciclo de foco: unos 0,8 s de imagen
   congelada, y la reconexión queda en ≈ 1,1-1,4 s. Con encoder propio (`aa_ext` y «último frame») el IDR sale al
   momento.
4. **Freno a AA con unidades fragmentadas.** Solo se retiene el ack del mensaje que completa un frame; los fragmentos
   anteriores se confirman en el acto. Con fragmentación, el freno deja a AA algo más de margen que la ventana
   nominal. Es el mismo contrato que tenía el fork.
5. **Detección de la zona Wi-Fi.** La reflexión (`getWifiApState`) puede estar bloqueada y los nombres de interfaz
   cambian según el fabricante (`swlan0`, `ap0`, `wlan1`…). El estado puede salir «No se sabe si la zona Wi-Fi está
   activa», pero `UNKNOWN` no bloquea nada.
6. **QDLink abierto** ocupa el UDP 18463: el motor lo dice y reintenta cada 5 s hasta que se cierra.
7. **Diario del coche (`car/`) con QDAuto.** Lleva solo el resumen (UDP, ACK, sesiones, vídeo cada 5 s, cortes y
   avisos), como preveía §7.1. El detalle completo está en `logs/`.
8. **Coste de la espera.** Mantener AA y el encoder vivos sin coche gasta batería y radio durante `car_gone_ms`. Es
   el mismo orden de magnitud que el fork, que ya mantenía AA 30 s.
9. **Cierres bruscos en el log.** Un cierre con RST del coche deja un `READ_ERROR … Connection reset` con su traza
   Java en el log unificado. Es esperado e informativo, no un fallo.
10. **Android lint.** Corrido sobre el app (`app\build\reports\lint-results-githubDebug.html`). En el código nuevo no
    hay errores. Los avisos `NewApi`/`ObsoleteSdkInt` del código de headqlink, nuevo o viejo, son los de siempre: el
    fork declara minSdk 16, pero su parte headqlink asume un Android moderno (servicio en primer plano,
    `RECEIVER_NOT_EXPORTED`…). No se ha cambiado esa premisa.
11. **Pendiente de llevar a `qdauto/core`:** el arreglo C9b (§4.4) y, si se quieren allí, las ampliaciones de API
    (todas aditivas; la lista está en `qdcore/UPSTREAM.md`).

---

## 7. Revisión adversarial (2026-10-05)

Dos revisores mandaron 3 defectos de vídeo y enlace (P1-P3) y 9 del adaptador (A1-A9). Cada uno se comprobó contra el
código antes de tocar nada. **Los 12 son reales y están corregidos**; ninguno se rechaza. En dos se ha ajustado la
propuesta (P1 y A7, ver la tabla).

| # | Gravedad | Resultado | Qué pasaba y qué se ha hecho |
|---|---|---|---|
| P1 | Mayor | **Corregido** (`d56bcff2`) | En `aa_ext` y «último frame», sin sesión la puerta GL no deja dibujar y el encoder, tras sus repeticiones (acotadas), se calla; `healthy()` pedía una salida en los últimos 2 s, así que a los ~3 s la reconexión **recreaba** el vídeo (relay, CarUi, encoder y la Surface del decodificador de AA) en vez de reutilizarlo hasta `car_gone_ms`. Ahora, con la puerta GL, el encoder solo cuenta como enfermo si se le dio un frame (`Gate.submitted`) después de su última salida y no devolvió nada en 2 s, o si el códec falló por su cuenta (`VideoEncoder.failed`, nuevo). Sin puerta (patrón, app) la regla es la de antes. De la segunda propuesta («redraw + IDR al enganchar») ya se hacía el redraw (`onReattached`) y el IDR (STREAM_START); no se recrea a posteriori: la muerte del códec sin sesión la detecta `failed`. Regla pura con tests (`VideoPipelineHealthTest`). |
| P2 | Menor | **Corregido** (`d56bcff2`) | `attach` leía el SPS/PPS en caché y enganchaba la sesión en dos pasos; uno nuevo que saliera entre medias (patrón o app recién creados, o AA en el reenvío directo) no llegaba nunca a esa sesión. Ahora `attach`, `onCodecConfig` y el SPS/PPS de AA escriben `csd` y `active` con el mismo candado (`csdLock`), como proponía el revisor. |
| P3 | Menor | **Corregido** (`9780b3ce`) | `SessionConfigs` dejaba `watchdogRequiresCarTraffic = true` (como QDLink): un coche que abre el TCP y nunca manda un 5A5A dejaba la sesión abierta para siempre. Ahora es `false`, como el fork (`SspSession.lastRx` desde el accept), con el mismo corte a 10 s (comprobado cada segundo: 10-11 s; el fork, 10-13 s). También en el móvil de prueba de `qdsim`. Test nuevo en `:qdcore`. |
| A1 | Mayor | **Corregido** (`997451dd`) | El fin de S1 (hilo de eventos de S1) podía poner a 0 la sesión actual justo después de que S2 la fijara (hilo de aceptación) en un relevo o en la vía rápida C9b; el fin de S2 salía entonces sin `onCarLost` y LinkService se quedaba CONNECTED con AA, encoder y candados. Ahora `SessionBook` cambia la actual y encola su aviso al hilo principal con el mismo candado (el fin solo borra la suya), así que «conectado» y «perdido» llegan en orden; una sesión que se cierra antes de terminar de empezar no llega a ser la actual. La interfaz de `SystemMonitor` y el detalle de `LinkState` llevan la sesión dueña y solo los borra ella: comparar el valor no basta, porque dos sesiones seguidas con el mismo coche ponen el mismo texto. Tests (`SessionBookTest`), con una prueba de dos hilos que cruza el fin de S1 y el inicio de S2 2000 veces. |
| A2 | Menor | **Corregido** (`997451dd`) | El hueco de «reconexión X ms» se perdía si S2 empezaba antes de que S1 terminara su cierre. Ahora el fin se sella al empezar el `onClosed` de cada puente (o en el relevo, con su broadcast); el primer broadcast y el primer ACK se apuntan también con la anterior ya cerrada; la sesión nueva se lleva el hueco al empezar y lo resuelve con su primer IDR (espera al sello como mucho 5 s). Los relevos también tienen métrica. |
| A3 | Menor | **Corregido** (`15919a8f`) | «Arranques de AA» del viaje contaba todos los del proceso. `startTrip` guarda el contador y el resumen da la diferencia. Test. |
| A4 | Menor | **Corregido** (`997451dd`) | `StallDetector` lo usaban el monitor de red y el hilo de eventos a la vez: los dos podían cerrar el mismo corte (NPE en `end()`, que dejaría `onClosed` a medias). Ahora `onClosed` espera al monitor (200 ms) y el detector va con candado. |
| A5 | Menor | **Corregido** (`15919a8f`) | La retención solo se aplicaba al arrancar el proceso. Ahora también al abrir cada traza de rendimiento y cada diario del coche, y `headqlink-*.log` se parte cada 16 Mi caracteres (`-pN`), con retención en cada parte. Las trazas llevan milisegundos y la sesión (`perf-AAAAMMDD-HHMMSS-mmm-S3.csv`): antes dos sesiones en el mismo segundo **truncaban** el fichero de la primera (`FileWriter` sin añadir). |
| A6 | Menor | **Corregido** (`895cd1da`) | El ZIP se presentaba sin GPS, pero los registros que exporta podían llevar la ubicación: el error del viento (con la URL de Open-Meteo, latitud y longitud), el destino de la ruta y, además de lo señalado, el error de abrir Google Maps (el Intent con las coordenadas) y `NavTap` en el logcat (la dirección del destino). Ahora el código HTTP se mira antes, `Http.safeError` deja de cada URL solo el host (y de un JSON inesperado, solo el tipo), y no se registran destinos. Tests (`HttpSafeErrorTest`). El resumen del ZIP lo dice y avisa de que los registros de versiones anteriores sí podían llevar alguno. |
| A7 | Menor | **Corregido, con ajuste** (`15919a8f`) | `sessions.csv` admitía fórmulas (el nombre del coche llega del broadcast UDP). Las celdas que empiezan por `= + - @`, tabulador o retorno llevan `'` delante. Ajuste: los números negativos se quedan igual, porque la regla tal cual estropearía las columnas con `-1` («no pasó»). Test. |
| A8 | Menor | **Corregido** (`b0936014`) | La conexión (o el motor) cambiados en marcha se aplican al volver a conectar, pero puentes, `sessions.csv`, contexto de los cortes, ZIP, Diagnóstico, tarjeta del coche e inicio leían lo configurado. Ahora `LinkService` lee los dos una vez al arrancar el transporte y los publica en `LinkState` (`activeLinkMode`, `activeEngine`); `QdLinkHost` los pasa a los puentes. Inicio, Diagnóstico y el ZIP enseñan aparte lo configurado si es distinto, y la configuración avisa con «Se aplica al volver a conectar». |
| A9 | Menor | **Corregido** (`b0936014`) | En Android 9 o menos el ZIP va a `exports/` de la carpeta de la app, pero el aviso y el log decían Descargas/HeadQLink. `Exported.where` dice dónde quedó y el aviso lo usa («Guardado en …»). |

**Resultados en el PC tras las correcciones:**

- `:qdcore:test`: **103/103** en 19 clases.
- `:app:testGithubDebugUnitTest --tests com.headqlink.link.*`: **60/60** en 13 clases. Las nuevas son `VideoPipelineHealthTest`,
  `SessionBookTest` y `HttpSafeErrorTest`, y hay casos nuevos en `SessionSummaryTest`.
- `:qdsim:installDist` y `:app:assembleGithubDebug`: **BUILD SUCCESSFUL**.

Sigue sin probarse nada en el móvil ni en el coche (§6, punto 1). En la prueba de §5.4 conviene mirar sobre todo dos cosas:

- que con huecos de más de 3 s el log diga «vídeo REUTILIZADO» y no «RECREADO» (P1);
- que tras un relevo (`qdsim --scenario silent`) el estado acabe en «Conectado» y el fin de la sesión nueva lo devuelva a «Reconectando» (A1).

---

## 8. Optimización térmica (2026-10-05)

En el viaje 2 (`../docs/viaje-2-2026-10-05.md`; S25 Ultra, Android 16, AA 17.7, modo `aa`, perfil `muy_alto`, zona
Wi-Fi) el móvil se calentó: estado térmico 0→1→2→3 en 15 min (SoC ~58 °C, batería ~44 °C) y 4 (crítico) en la segunda
sesión, con el TX bajando de ~55 a ~40 fps. Tres causas, tres arreglos:

| Causa | Arreglo | Commit |
|---|---|---|
| Se enviaban 50-60 fps (1920×882@60, 5-16 Mbit/s, relojes del encoder al máximo) cuando el C10 pide en VIDEO_ARGS `FrameRate 30`, `BitRate 5080320` | Perfil **«Coche»** (el recomendado): fps y bitrate de VIDEO_ARGS, AA también a 30, relay GL a ese ritmo | `4c3c4877` |
| La pantalla del móvil no se apagaba | Modo coche de Android con `ENABLE_CAR_MODE_ALLOW_SLEEP` y ajuste «Mantener la pantalla del móvil encendida» (por defecto, no) | `b8ed989b` |
| Ninguna adaptación al calor | `ThermalPolicy` + `ThermalGuard`: 24 o 20 fps y menos bitrate en marcha, con histéresis | `d72ef0c5` |

Antes de eso, `76aad022` deja **toda** la batería del app en verde en Windows (`:app:testGithubDebugUnitTest` sin
filtro): `LogActivity` avisaba con `Toast.makeText` directo (lo prohíbe `ToastUsageTest`) y `BlinkAutoSerialChannelTest`
necesitaba `sh` en el PATH (ahora se salta si no está).

### 8.1 Perfil «Coche»

- **Qué es.** «Último frame» con lo que pide el coche: fps de `VIDEO_ARGS FrameRate` (entre 10 y 60; 30 si no llega) y
  bitrate de `VIDEO_ARGS BitRate` (5 Mbit/s si no llega), fijo y sin ABR, a la resolución de la pantalla del coche
  (`CAR_INFO`), **sin** optimizaciones de latencia ni relojes al máximo. En el C10: 1920×882@30, 5,08 Mbit/s,
  intra-refresh de 30 frames.
- **Android Auto también a 30.** `AaPassthroughSource.configureAa` ya ponía `fpsLimit = 30` si el objetivo es ≤ 30, y
  Open Headunit lo anuncia en el *service discovery* (`ServiceDiscoveryResponse`: `VideoConfiguration.frameRate =
  VideoFrameRateType._30`). Con «Muy alto» era 60. El decodificador de AA usa ese valor como `KEY_OPERATING_RATE`. Ojo:
  AA solo lo negocia al conectar. Con el APK nuevo el proceso arranca de cero, así que la primera sesión ya va a 30.
- **Recomendado.** `VideoProfile.recommend` (puro): «Coche» si el móvil codifica y decodifica 1080p30 por hardware y
  tiene ≥ 4 GB; si no, Medio o Básico, como antes. «Automático» lo sigue. Los perfiles de antes (Muy alto, Alto, Medio,
  Básico y Muy bajo) siguen en la lista, y un id desconocido es «Coche». **Si se había elegido «Muy alto» a mano, se
  queda**: hay que volver a «Automático» o elegir «Coche» en Inicio › ⋮ › Ajustes de imagen.
- **Ritmo del relay GL** (`FramePacer`, puro). Sin rejilla, GCRA: cada frame en cuanto llega, con medio periodo de
  tolerancia, pero nunca más de los fps del vídeo de media. Con una sola fuente se comporta igual que antes (medio
  periodo desde el último dibujo). Lo nuevo es que AA y nuestra capa juntas (Auto extendido) ya no suman 60 dibujos por
  segundo enviando a 30. La rejilla (cadencia fija y pantallas propias delante) no cambia.

### 8.2 Pantalla del móvil

- **Quién activaba el modo coche.** `AapService.setupCarMode()` de Open Headunit, en el `onCreate` del servicio de
  Android Auto, con `UiModeManager.enableCarMode(0)`. Es lo que se veía en `dumpsys uimode`
  (`carModeApps=com.headqlink.app`). Open Headunit está pensado para una radio de coche con Android; aquí el «head unit»
  es el propio móvil.
- **Por qué no se apagaba la pantalla.** AOSP, `UiModeManagerService.updateLocked`: si el modo coche está activo, el
  móvil enchufado (`mCharging`), `config_carDockKeepsScreenOn = 1` (`mCarModeKeepsScreenOn`, lo normal) y al activarlo
  no se pasó `ENABLE_CAR_MODE_ALLOW_SLEEP`, el sistema guarda un `FULL_WAKE_LOCK` («UiModeManager»). Por eso en casa,
  enchufado por USB, no la apagaban ni el tiempo de espera ni `KEYCODE_SLEEP`/`KEYCODE_POWER`.
- **Matiz del viaje.** Según `batterystats`, en el coche el móvil iba **sin enchufar** (`plug=none` de 12:32 a 13:32),
  así que ese bloqueo no aplicaba allí. El *display* sí se llegó a apagar (por ejemplo, `state=OFF` a las 12:58:18 y a
  las 13:00:58). Además seguía puesto el `screen_off_timeout` de 10 min de las pruebas en casa: **conviene devolverlo a
  su valor**.
- **Qué se hace ahora** (`PhoneScreen`):
  - El modo coche se sigue activando, así que Android Auto y el sistema ven lo mismo, pero con
    `ENABLE_CAR_MODE_ALLOW_SLEEP`, que es justo lo que quita ese bloqueo.
  - Ajuste nuevo **«Mantener la pantalla del móvil encendida»** en Ajustes de imagen, por defecto apagado. Encendido,
    vuelve a lo de antes (sin `ALLOW_SLEEP`). Se aplica en el acto si AA está en marcha: `AapService.refreshCarModeFlags`
    vuelve a activar el modo coche con los indicadores nuevos.
- **Con la pantalla apagada AA sigue.** El receptor `WakeDetect` de `AapService` llamaba en `SCREEN_OFF` a
  `pauseForSleep()`, que para el decodificador (el que alimenta el «último frame»), el audio y el micrófono de AA; eso
  está pensado para una radio que se duerme. Con AA sin vista en el móvil (`VideoTap.headless`) ya no se llama.
- **Candados del app.** No cambian: `LinkService` guarda un `PARTIAL_WAKE_LOCK` («headqlink:link»), el candado de Wi-Fi y
  el de multicast. Ninguna pantalla de la proyección pone `FLAG_KEEP_SCREEN_ON`; solo lo hacen `LatencyActivity`
  (diagnóstico) y las actividades de Open Headunit, mientras están delante.

### 8.3 Adaptación térmica

- **Política** (`ThermalPolicy`, pura):
  - estado ≥ MODERADO (2): **24 fps** y el bitrate ×0,7;
  - estado ≥ GRAVE (3): **20 fps** y como mucho **3 Mbit/s** (nunca más que en moderado).

  Sube en el acto. Baja solo tras **60 s seguidos** por debajo del nivel actual, y al nivel más alto que se vio en ese
  rato: para volver a normal, el estado tiene que estar en LIGERO (1) o menos durante 60 s.
- **Escucha** (`ThermalGuard`, en `LinkService`). `PowerManager.addThermalStatusListener` solo con API 29+ (minSdk 16 en
  `github` y 21 en `playstore`); sin él no se adapta nada. Sustituye al aviso térmico de `SystemMonitor`, que además no
  estaba protegido por versión.
- **En marcha, sin reiniciar la sesión ni AA** (`VideoHub` → `VideoPipeline.applyThermal`):
  - bitrate del encoder con `setParameters` (`KEY_VIDEO_BITRATE`); el ABR de Muy alto y Alto respeta el techo;
  - ritmo del relay GL con `GlFrameRelay.setMaxFps`, en rejilla por debajo de los fps de la sesión.

  Una *pipeline* nueva (reconexión con el vídeo recreado) empieza ya con el tope. AA sigue a 30: bajarlo exigiría
  reconectarlo. Lo que se ahorra es composición GL, encoder y radio.
- **Sin encoder propio no se cambia nada.** En el reenvío directo (perfil Básico) y con el motor original solo se
  registra.
- **Registro.**
  - Cada cambio de estado y de nivel.
  - Cada minuto, en `HQL/Térmico`: temperatura de la batería (`ACTION_BATTERY_CHANGED`, `EXTRA_TEMPERATURE`), carga,
    enchufado o no, estado térmico, margen (`getThermalHeadroom(10)`, API 30+) y nivel.
  - En el resumen de cada sesión y en `sessions.csv`, columnas nuevas: `termico_fin`, `termico_max`, `tope_fps_fin` y
    `tope_fps_min`. El resumen del viaje da el máximo térmico y el tope más bajo.

### 8.4 Cómo comprobarlo en el móvil

| Qué | Dónde mirar | Qué debe salir |
|---|---|---|
| Perfil | Ajustes de imagen | «Automático (Coche)» y «Coche · Recomendado» |
| Encoder a lo que pide el coche | log (`logs/qd-*.log`) | `perfil recomendado: coche (… enc1080p30 true · dec1080p30 true …)`, `VIDEO start: coche pide 1920x882@30 5080320bps intervalo 3 \| usamos 1920x882@30 5080kbps … intra-refresh=30` **sin** `relojes-max`, `encoder c2.qti.avc.encoder 1920x882@30 …` **sin** `· baja latencia`, `vídeo CREADO para S1: aa/coche último frame 1920x882@30 …` |
| AA a 30 | log | `AA: pantalla del coche 1920x882 · vídeo 1920x882 (1080p) … 30 fps`; `GL relay: AA ~150 frames, enviados ~150` cada 5 s; fps ≈ 30 en el resumen y en `sessions.csv` |
| Modo coche sin bloqueo de pantalla | log | `pantalla: modo coche de Android con ENABLE_CAR_MODE_ALLOW_SLEEP …` al arrancar AA; `pantalla: screen_off · modo coche de Android con ALLOW_SLEEP` al apagar |
| Ídem | `adb shell dumpsys uimode` | `mCarModeEnabled=true`, `carModeApps=com.headqlink.app` y, si esta versión lo imprime, `mCarModeEnableFlags=2` |
| Ídem | `adb shell dumpsys power` | **ningún** `FULL_WAKE_LOCK 'UiModeManager'`, también **enchufado**; solo `PARTIAL_WAKE_LOCK 'headqlink:link'` del app |
| Pantalla apagada y AA sigue | botón de encendido, enchufado y sin enchufar | se apaga; en el coche sigue el vídeo y el táctil; logcat: `WakeDetect: SCREEN_OFF (headqlink sin pantalla: AA sigue, sin pausa)` |
| Ajuste «Mantener la pantalla…» | marcarlo con AA en marcha | `pantalla: modo coche de Android SIN ENABLE_CAR_MODE_ALLOW_SLEEP …` y, enchufado, vuelve el `FULL_WAKE_LOCK 'UiModeManager'` |
| Térmico sin calentar el móvil | `adb shell cmd thermalservice override-status 2` (luego `3`, `1`; al acabar, `reset`) | `HQL/Térmico: estado térmico 2 (moderado)`, `térmico 2 → perfil moderado (24 fps y bitrate ×0.7)`, `térmico 2 → perfil moderado: 24 fps (sesión 30) · 3.6 Mbit/s (sesión 5.1)`, `GL relay: ritmo 24 fps en rejilla`; con `3`: 20 fps y 3,0 Mbit/s; con `1`, **60 s después**: `térmico 1 → perfil normal`, `GL relay: ritmo 30 fps` |
| Batería cada minuto | log | `HQL/Térmico: batería 41.3 °C · 78 % · sin enchufar · estado térmico 1 · margen 0.65 · nivel normal` |
| Resumen | `HQL/Resumen` y `sessions.csv` | `vídeo: … · térmico 1 (máx. 3) · tope 30 fps (mín. 20)`; columnas `termico_fin,termico_max,tope_fps_fin,tope_fps_min` |

### 8.5 Resultados en el PC

`cmd /c ".\gradlew.bat :qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`:
**BUILD SUCCESSFUL**.

- `:qdcore:test`: 103/103.
- `:app:testGithubDebugUnitTest`, **todo el app** (Open Headunit incluido): 2740 pruebas, 0 fallos y 1 saltada (la de
  `sh`). Las nuevas son `VideoProfileTest`, `FramePacerTest`, `PhoneScreenTest` y `ThermalPolicyTest`, y hay un caso
  nuevo en `SessionSummaryTest`.

Sin probar todavía en el móvil ni en el coche. Además de §8.4, en el próximo viaje hay que comparar el estado térmico
máximo y la temperatura de la batería a los 15 min con los del viaje 2 (3 y ~44 °C).

---

## 9. Comprobación de requisitos

La app comprueba todo lo que tiene que estar activado para los ajustes actuales y dice qué falta. Arriba pone
**«Todo listo»** (verde) o **«Faltan N cosas»**: en rojo si falta algo obligatorio, en ámbar si solo falta algo
recomendado. Debajo va una fila por requisito, con icono de estado (✓ ! ✕ ? … i), importancia (**Obligatorio**,
**Recomendado**, **Opcional** o **Consejo**), una línea de explicación y un botón. El botón lleva a la pantalla exacta
de Ajustes o pide el permiso.

**Dónde está.**
- Pantalla principal › menú (engranaje) › **Comprobación**, y la fila nueva **«Requisitos»** del panel de estado.
- Paso 3 de la configuración: sustituye a las filas de antes (una sola lógica).
- Al pulsar **Conectar**, también en el arranque automático al abrir la app: si falta algo obligatorio se abre la
  comprobación, con «Conectar igualmente» (pasa a «Conectar» cuando ya está todo).
- «Terminar igualmente» en la configuración no vuelve a avisar en ese primer «Conectar».

**Qué comprueba.** Solo sale lo que importa para el modo, la conexión y la conexión automática por Bluetooth.

| Requisito | Cuándo sale | Importancia | El botón abre |
|---|---|---|---|
| Android Auto instalado, activado y su versión | modos Auto | Obligatorio | Play Store / Info. de la app de AA |
| Accesibilidad de HeadQLink (`ENABLED_ACCESSIBILITY_SERVICES` y servicio conectado) | AA ≥ 17.4 (sin `force_legacy_launch`) y modo App | Obligatorio | su página (`ACCESSIBILITY_DETAILS_SETTINGS` con `EXTRA_COMPONENT_NAME`, Android 13+) o la lista de accesibilidad. Si Android 13+ la restringe (operación `access_restricted_settings`, o la app no viene de Play), explica «Permitir ajustes restringidos» con un segundo botón a Info. de la app. Si está activada pero parada: «desactívala y vuelve a activarla» |
| Modo desarrollador de AA | AA ≥ 17.4 | Obligatorio | «Comprobar» (la automatización de siempre) y «Abrir AA» (sus ajustes; al volver se comprueba solo), con la guía debajo |
| App elegida | modo App | Obligatorio | el selector de apps |
| Dispositivos Wi-Fi cercanos (ubicación antes de Android 13) | Wi-Fi Direct | Obligatorio | el permiso; si se denegó para siempre, Info. de la app |
| Wi-Fi activado | Wi-Fi Direct | Obligatorio | Ajustes de Wi-Fi |
| Zona Wi-Fi apagada | Wi-Fi Direct | Obligatorio | Ajustes de la zona Wi-Fi (`HotspotWatcher.openSettings`) |
| Zona Wi-Fi activa | Punto de acceso | Obligatorio | ídem |
| Banda de 5 GHz y sin apagado automático | Punto de acceso | Consejo (no se puede leer) | ídem |
| QDLink (`com.neusoft.qdrivelink`) instalado | si está instalado | Recomendado (aviso: ciérralo) | su Info. de la app (Forzar detención) |
| Puerto UDP 18463 ocupado | si lo está | Obligatorio (error) | Info. de la app de QDLink |
| Notificaciones (`POST_NOTIFICATIONS` y `areNotificationsEnabled`) | siempre | Recomendado | el permiso; si se denegó, los ajustes de notificaciones de la app |
| Bluetooth (`BLUETOOTH_CONNECT`) | «Conectar al detectar el Bluetooth del coche» activado | Obligatorio | el permiso o Info. de la app |
| Batería sin restricciones (`isIgnoringBatteryOptimizations`) | siempre (con la conexión automática dice que la necesita) | Recomendado | el diálogo `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, si no la lista `IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, si no Info. de la app |
| Samsung: «Aplicaciones que nunca se suspenden» | Samsung | Consejo | la batería de la app (`VIEW_ADVANCED_POWER_USAGE_DETAIL`), si no el gestor de Samsung o Info. de la app |
| Gestor de energía del fabricante | Honor, Xiaomi, Oppo… (`PowerHelper`) | Consejo | su pantalla |
| Mostrar sobre otras apps | modo App (obligatorio) y modos Auto (opcional: AA lo arranca la accesibilidad) | ver columna «Cuándo sale» | `MANAGE_OVERLAY_PERMISSION` |
| Fotos, vídeos y ubicación | Auto ampliado | Opcional | el permiso o Info. de la app |

«Faltan N» cuenta lo obligatorio y lo recomendado que falta o falla. No cuentan lo opcional, los consejos, el aviso
de QDLink ni lo que no se pudo comprobar (zona Wi-Fi desconocida). «Denegado para siempre» se distingue de «se puede
pedir» guardando que ya se pidió (`req_asked_*`) y mirando `shouldShowRequestPermissionRationale`.

**El puerto 18463.**
- Con el servicio parado, la comprobación abre el puerto un instante con las mismas opciones que el motor
  (`reuseAddress`).
- En marcha, usa lo que dijo el motor al abrirlo (`LinkState.udpBusy`, de `QdLinkHost` y de `UdpDiscovery`). Cuando
  QDLink se cierra y el motor por fin abre el puerto, el error desaparece solo.

**En vivo.** Se vuelve a comprobar:
- al volver a la pantalla;
- con los avisos del sistema: Wi-Fi, zona Wi-Fi (con su `wifi_state`), apps instaladas o quitadas, notificaciones
  bloqueadas;
- con el `ContentObserver` de la accesibilidad, y otra vez 1,2 s después, porque TouchService tarda en conectarse;
- cuando cambian «en marcha» o «puerto ocupado» en `LinkState`.

Lo lento (zona Wi-Fi y puerto) va en un hilo y, mientras, sale «Comprobando…».

**Código.**
- `[hql]Requirements.java`: la lógica, pura. De una foto del estado (`Snapshot`) a la lista de requisitos con
  importancia, estado y matiz. La prueba `RequirementsTest`.
- `[hql]Checklist.java`: lee el estado del móvil, pinta las filas (`hql_row_req.xml`) y hace las acciones.
- `[hql]ChecklistActivity.java`: la pantalla.
- `SetupActivity` y `HomeActivity` la usan; `hql_row_check.xml` y los permisos por modo de `Ui` desaparecen.
- En el manifiesto: la actividad y `<queries>` de QDLink.
- Textos en los cuatro idiomas (`hql_req_*`).

**Cómo comprobarlo en el móvil.**
- Desactiva la accesibilidad de HeadQLink en Ajustes: con la comprobación abierta, la fila pasa a rojo sin tocar nada.
  «Activar» abre su página.
- Con Wi-Fi Direct, enciende la zona Wi-Fi: «Zona Wi-Fi apagada» pasa a rojo.
- Abre QDLink y pulsa Conectar: sale la comprobación con «Puerto 18463 · Ocupado» y «Conectar igualmente».
- Deniega dos veces las notificaciones: el botón pasa a «Abrir» (ajustes de notificaciones de la app).

**Resultados en el PC.** `:app:testGithubDebugUnitTest :app:assembleGithubDebug`: **BUILD SUCCESSFUL**.
- Todo el app: 2760 pruebas, 0 fallos y 1 saltada (la de `sh`).
- `RequirementsTest`, nueva: 20/20. Cubre qué sale en cada modo y conexión, la importancia, qué cuenta en «Faltan
  N», la versión de AA (17.4), la lectura de `ENABLED_ACCESSIBILITY_SERVICES` y los estados de los permisos.

Sin probar todavía en el móvil.
