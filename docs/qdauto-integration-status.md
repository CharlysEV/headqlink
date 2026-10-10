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

  También hay relevo de sesión (el coche se reanuncia con la sesión abierta) y re-ACK. Pasado `car_gone_ms`, el vídeo
  se para pero el enlace sigue escuchando con Android Auto en pausa hasta «Esperar al coche» (5 min): si el coche
  vuelve, AA sale de la pausa al instante (§10).
- **Ningún mensaje de vídeo de más de 480 KiB.** El receptor del C10 se cuelga con más de ~512 KiB (cortes de 10 s). El
  núcleo los descarta y pide otro IDR; el encoder propio apunta a 300 KB por IDR (§11).
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
| Vídeo vivo sin coche (`car_gone_ms`) | Ídem | 30 s (de 5 a 600) | En la próxima pérdida del coche |
| Esperar al coche (`car_wait_min`, §10) | Inicio › ⋮ › Ajustes de imagen › Avanzado | 5 min (1, 5 o 15) | En la próxima pérdida del coche |
| Vuelta tras un corte de radio (§13): `qd_ack_resend`, `qd_stable_port`, `qd_reclaim`, `qd_udp_refresh`, `qd_car_ping` | Solo con extras (`--ez qd_reclaim false`) | Sí (todos) | Al volver a conectar |
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
- **Espera (§10):** a los 30 s sin coche se para el vídeo y AA queda en pausa (sin «AA: lanzando Self-Mode» al volver);
  pasado «Esperar al coche» se apaga todo, como en el fork, y el siguiente «Conectar» vuelve a lanzar AA.
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
   el mismo orden de magnitud que el fork, que ya mantenía AA 30 s. Después, hasta «Esperar al coche» (§10), solo
   quedan el enlace escuchando (con sus *locks* de Wi-Fi y CPU) y AA conectado sin codificar, con un ping cada 5 s.
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

- **Política** (`ThermalPolicy`, pura). *Revisada en §12.6 (2026-10-05): niveles más suaves, 30 s de histéresis y
  la «Protección térmica» elegible (Normal, Suave, Apagada).* Con la protección Normal:
  - estado MODERADO (2): **30 fps** si la sesión va a 60 (a 30, los mismos fps) y el bitrate ×0,8;
  - estado GRAVE (3): **24 fps** y como mucho **3,5 Mbit/s**;
  - estado CRÍTICO (4 o más): **20 fps** y como mucho **3 Mbit/s** (nunca más que en el nivel anterior).

  Sube en el acto. Baja solo tras **30 s seguidos** por debajo del nivel actual, y al nivel más alto que se vio en ese
  rato: para volver a normal, el estado tiene que estar en LIGERO (1) o menos durante 30 s.
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
| Térmico sin calentar el móvil | `adb shell cmd thermalservice override-status 2` (luego `3`, `1`; al acabar, `reset`) | `HQL/Térmico: estado térmico 2 (moderado)`, `térmico 2 → perfil moderado (protección normal: los fps de la sesión y bitrate ×0.8)`, `térmico 2 → perfil moderado (protección normal): 30 fps (sesión 30) · 4.1 Mbit/s (sesión 5.1)`; con `3`: `24 fps … 3.5 Mbit/s` y `GL relay: ritmo 24 fps en rejilla`; con `4`: 20 fps y 3,0 Mbit/s; con `1`, **30 s después**: `térmico 1 → perfil normal`, `GL relay: ritmo 30 fps` |
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
| Ubicación todo el tiempo (`ACCESS_BACKGROUND_LOCATION`, §20) | Auto ampliado | Recomendado | diálogo con el porqué y el permiso (Android 11+: su página de Ajustes); sin la ubicación, primero esa; denegada, Info. de la app |

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

---

## 10. Ciclo de vida: esperar al coche y reanudar al instante

**Informe (coche real, versión 0.2).** «Tuve que cerrar y abrir para que funcionara; reconectar tarda mucho porque el
sistema no se cierra aunque pone "Cerrando Auto…", hasta que abres la app y lo inicias otra vez.»

**Diagnóstico (confirmado en el código).**
- Con el coche fuera más de `car_gone_ms` (30 s), `LinkService.shutdownAll()` lo cerraba todo: dejaba de escuchar al
  coche y apagaba el servidor de head unit de AA. Con el móvil bloqueado no puede manejar los ajustes de AA: aparcaba
  la sesión (`AaGuardService`) y dejaba el apagado pendiente para el desbloqueo, tras la capa «Cerrando Auto…».
- Al volver al coche nada escuchaba. Al desbloquear, primero se apagaba el servidor; luego había que abrir la app y
  pulsar Conectar, que lo arrancaba de nuevo («Arrancando Auto…»): un ciclo apagar/arrancar con desbloqueo y dos
  automatizaciones de los ajustes de AA.
- Nada impedía que el apagado del desbloqueo y el arranque de Conectar corrieran **a la vez** sobre los mismos ajustes
  (dos automatizaciones pulsando y yendo «atrás», la capa contada dos veces). Y si el apagado no se confirmaba (con
  nuestra head unit aún conectada, el menú de AA puede no mostrar la opción del servidor), el servidor quedaba
  encendido sin aviso. Eso es «se queda en "Cerrando Auto…" y no cierra».
- Con la conexión automática por Bluetooth, al apagar el coche se iba el Bluetooth y `ACTION_BT_CAR_GONE` lo cerraba
  todo en el acto (en ese momento ya no había sesión), sin esperar ni los 30 s.

**Qué cambia.**

| Situación | Antes | Ahora |
|---|---|---|
| Coche perdido, primeros 30 s | Vídeo y AA vivos | Igual (`car_gone_ms`) |
| De 30 s a «Esperar al coche» (5 min) | Todo cerrado | El enlace sigue escuchando; vídeo parado; AA aparcado (`AaPark`: foco de vídeo nativo, ping cada 5 s, sin vista en el móvil). Notificación «Esperando al coche · Android Auto en pausa» |
| El coche vuelve en ese tiempo | Conectar, desbloquear, apagar y arrancar el servidor | Con la sesión, AA sale de la pausa al instante: sin automatización, sin desbloquear |
| Vence «Esperar al coche» | — | El cierre de siempre (`LinkLifecycle.shutdownPlan`): bloqueado, guardián y apagado al desbloquear |
| El Bluetooth del coche se va | Cierre inmediato si no había sesión | Solo cierra si aún no hubo sesión; tras una sesión, sigue esperando |
| El enlace arranca (Bluetooth, Conectar) con AA aparcado por el guardián | El guardián apagaba el servidor al desbloquear | El enlace adopta AA aparcado (`AaGuardService.handOver`), anula el apagado pendiente y lo reanuda con la sesión |
| Hay que arrancar el servidor con el móvil bloqueado | «No arranca: desbloquea el móvil»; luego Desconectar y Conectar | Notificación de prioridad alta «Desbloquea el móvil para iniciar Android Auto»; al desbloquear arranca solo (y relanza el Self-Mode si había fallado), sin abrir la app |
| Llegada por Bluetooth con el servidor apagado | Se arrancaba tarde, al pedir vídeo | Se arranca ya (desbloqueado) o al desbloquear (con el aviso) |
| Capa «Cerrando/Arrancando Auto…» | Podía quedarse | Se quita sola a los 15 s del último uso; si tapaba un apagado, aviso «El servidor de Android Auto sigue encendido · Tocar para apagarlo» (reintenta el apagado) |
| Automatizaciones de los ajustes de AA | Podían cruzarse | Una cada vez (`RUN_LOCK`). Un apagado en cola se anula si se pide el servidor (`stopEpoch`). Un apagado que falla con AA conectado suelta AA y se reintenta una vez; si sigue sin confirmarse, el aviso |

**Código.**
- `[hql]LinkLifecycle.java`, nuevo y puro. Fases BUSCANDO → CONECTADO → VÍDEO VIVO → AA EN PAUSA → CERRADO. Eventos
  (arranque con su disparador, coche anunciado, sesión, coche perdido, temporizador, Bluetooth fuera) → acciones
  (`ADOPT_PARK`, `CANCEL_PENDING_STOP`, `RESUME_AA`, `PARK_AA`, `STOP_VIDEO`, `START_SERVER`, `START_SERVER_ON_UNLOCK`,
  `SHUTDOWN`) con sus motivos para el log, y `shutdownPlan`. Prueba: `LinkLifecycleTest`.
- `[hql]AaPark.java`, nuevo: aparcar, reanudar y soltar AA (antes dentro de `AaGuardService`): foco, ping, `headless`
  y el foco pendiente de devolver (`takeFocusOwed`).
- `LinkService`: un solo temporizador (`lifeTimer`) en lugar de `carGone`/`noCar`; ejecuta las decisiones; `closeAa`
  es el cierre de siempre; nueva acción `AA_SERVER_OFF` (la del aviso).
- `AaGuardService`: usa `AaPark`; `handOver` (por una orden, no con `stopService`, que antes de `startForeground`
  cerraría la app); `active`.
- `AaServerStarter`: `RUN_LOCK`, `stopEpoch`, estado conocido del servidor (`aa_server_state`; un arranque de hace
  menos de 20 s no se repite), arranque al desbloquear, los dos avisos y el reintento.
- `TouchService`: vigilante de la capa (15 s); al desbloquear, `AaServerStarter.onUnlock` (arranque pedido o apagado
  pendiente; el apagado no se hace si el enlace está en marcha).
- `AaPassthroughSource`: al pararse con AA aparcado, la fila «Auto» dice «En pausa hasta que vuelva el coche»; al crear
  el vídeo tras la pausa, foco de vuelta si el ciclo de IDR no pudo empezar.
- `HomeActivity`: «Esperar al coche» (Ajustes de imagen › Avanzado); Conectar con AA aún conectado no arranca el
  servidor. `Config`: `car_wait_min` (1, 5 o 15; por defecto 5; también como extra).
- Textos en los cuatro idiomas; manuales (ES, PT, EN) con la espera, los avisos y dos filas nuevas en «Solución de
  problemas».

**Por qué no se quita `headless` al reanudar.** Con AA conectado y `VideoTap.headless = false`, Open Headunit abre su
proyección en el móvil (al encender la pantalla o al dar el foco). Se deja puesto, y el foco de vídeo vuelve con el
vídeo nuevo: el ciclo de IDR al arrancar la fuente o, si no puede empezar, un foco directo. Darlo antes de que haya
superficie haría que AA mandara imagen a un decodificador sin superficie, y esos reinicios cuentan para darlo por roto.

**Seguridad.** Sin coche, el servidor de AA sigue encendido como mucho «Esperar al coche» (15 min como máximo), con
nuestra head unit ocupándolo (AA atiende una conexión a la vez). Después, el cierre de siempre. Si AA se cae durante la
espera, el servidor queda libre hasta que vence, como antes durante los 30 s.

**Qué buscar en el log.** Todas las decisiones van con «ciclo:» (`I/HQL: ciclo: …` en el log unificado):

| Línea | Significado |
|---|---|
| `ciclo: coche perdido: vídeo vivo 30 s; después Android Auto en pausa y sigo esperando al coche hasta 5 min en total` | Empieza la espera |
| `ciclo: 30 s sin coche: paro el vídeo y dejo Android Auto en pausa; sigo escuchando al coche 4 min más` | AA en pausa (y `Android Auto aparcado (esperando al coche)…`) |
| `ciclo: sesión con el coche tras 2 min sin coche: Android Auto sale de la pausa al instante (…)` | Reanudación sin servidor ni desbloqueo |
| `ciclo: … anulo el apagado pendiente del servidor de Android Auto` / `ciclo: AA server: apagado (…) anulado …` | Apagado pendiente o en cola anulado |
| `ciclo: espera del coche vencida (5 min sin coche): cierro todo` | Vence la espera; sigue la línea `ciclo: cierre …` con el plan |
| `ciclo: AA guardián: paso Android Auto aparcado al enlace (…)` | Relevo del guardián al enlace |
| `ciclo: AA server: … aviso «Desbloquea el móvil para iniciar Android Auto» …` / `ciclo: desbloqueado: arranco el servidor …` | Arranque al desbloquear |
| `W/HQL: ciclo: la capa «Cerrando Auto…» llevaba 15 s: la quito …` / `ciclo: aviso: el servidor de Android Auto puede seguir encendido` | Capa vencida y aviso |

**Cómo comprobarlo en el coche.**
1. Parada corta (menos de 5 min) con el móvil bloqueado: apagar el coche 2 min y volver. La notificación pasa a
   «Esperando al coche · Android Auto en pausa»; al volver, imagen sin tocar el móvil. En el log, «sale de la pausa al
   instante» y ninguna capa; en el resumen del viaje no suben los «arranques de AA».
2. Parada larga (más de 5 min) con el móvil bloqueado y la conexión automática por Bluetooth: al volver, AA sin
   desbloquear (relevo del guardián).
3. Lo mismo sin la conexión automática: al desbloquear, «Cerrando Auto…» unos segundos (nunca más de 15) y luego
   Conectar.
4. Con la conexión automática, el servidor apagado y el móvil bloqueado al llegar: «Desbloquea el móvil para iniciar
   Android Auto»; al desbloquear arranca solo.

**Resultados en el PC.** `:app:testGithubDebugUnitTest :qdcore:test :app:assembleGithubDebug`: **BUILD SUCCESSFUL**.
- Todo el app: 2787 pruebas, 0 fallos y 1 saltada (la de `sh`). `:qdcore`: 103/103.
- `LinkLifecycleTest`, nueva: 20/20. Cubre el coche que vuelve dentro y fuera de cada espera, la pausa antes de parar el
  vídeo, el cierre al vencer (y el reintento con un intento en marcha), móvil bloqueado o no, el apagado pendiente, la
  llegada por Bluetooth con AA aparcado por el guardián o con el servidor apagado, el Bluetooth que se va, el plan de
  cierre y las opciones de «Esperar al coche».

Sin probar todavía en el móvil ni en el coche.

---

## 11. IDR grandes: el coche se cuelga con mensajes de más de 512 KiB (2026-10-05)

**Informe (viaje 3, coche real).** Perfil «Coche»: 1920×882@30, 5,08 Mbit/s VBR, intra-refresh de 30 frames, GOP de
30 s e IDR a cada `KEY_FRAME_REQ`. Hubo cortes de ~10 s y después reconexión. En una sesión el coche mandó 21
`KEY_FRAME_REQ`.

**Diagnóstico.** Todos los cortes graves empezaron mientras se escribía un IDR enorme. El coche dejaba de leer el TCP:
el `write()` se quedaba bloqueado, aunque el coche seguía mandando heartbeats. A los 10 s, `WRITE_STALL` cerraba la
sesión.

| Mensaje de vídeo (48 B de cabeceras + Annex-B) | Resultado |
|---|---|
| 592 913 B, 538 390 B, 525 208 B | Corte: el coche deja de leer |
| 493 568 B, 482 KB, 457 KB, 378 KB, 330 KB | Sin problema (en los viajes 1 y 2 el máximo fue 330 KB) |

Conclusión: el receptor de QDLink del coche admite como mucho ~512 KiB (524 288 B) por mensaje. Con uno mayor se
cuelga.

**Qué cambia.**

| Pieza | Qué hace | Dónde |
|---|---|---|
| Tope en el núcleo | Ningún mensaje de vídeo de más de **480 KiB** llega al socket, con cualquier política de descarte. Un frame mayor se descarta sin copiarlo. Los P-frames que dependen de él tampoco salen: se espera al siguiente IDR, con SPS/PPS delante. Se avisa a la app y se pide otro IDR al momento. | `SessionConfig.maxVideoMessageBytes` (constante de la app: `SessionConfigs.MAX_VIDEO_MESSAGE_BYTES`), `PhoneSession.sendFrame`, `SendQueue` |
| IDR pequeños con encoder propio («último frame», patrón, app) | Objetivo: **300 KB por IDR**. Con Android 12+, QP mínimo de los I-frames adaptable (`KEY_VIDEO_QP_I_MIN/MAX`): empieza en 24, al configurar y en marcha con `setParameters`. Un IDR por encima del objetivo sube el mínimo (+6 de QP ≈ la mitad de bytes); uno por encima del tope, al menos +4. Tres seguidos muy por debajo (< 150 KB) lo bajan 1. Siempre entre 18 y 40. Con Android 13+ se pide el QP medio de cada IDR (`KEY_VIDEO_ENCODING_STATISTICS_LEVEL`) y se usa como base. | `IdrSizeController`, `VideoEncoder`, `VideoPipeline.onEncoderIdr` |
| Plan B: bajar el bitrate | Justo antes de pedir un IDR se baja el bitrate al **40 %** y se repone en cuanto sale el IDR (o pasado 1 s). Se usa en cada IDR pedido si no hay claves de QP (Android < 12) o el encoder no les hace caso: su QP medio queda por debajo del mínimo, o 3 IDR seguidos por encima del objetivo no bajan de tamaño. Y una sola vez tras un IDR descartado, tras dos seguidos por encima del objetivo o con el mínimo ya en 40. La bajada se ajusta entre el 20 y el 40 % según salgan los IDR. | `VideoEncoder.requestKeyFrame(dip)`, `setBitrate` (respeta el ABR y el térmico) |
| Antirrebote de `KEY_FRAME_REQ` | Con encoder propio, como mucho un IDR cada **600 ms**. Una petición con el IDR pedido aún sin salir se sirve con él. Si ya salió, se aplaza una sola al final de la ventana. El primero de la sesión (`STREAM_START`) y el que sustituye a uno descartado (`OVERSIZED`) salen siempre al momento. Antes no había antirrebote con encoder. El del reenvío directo (`KeyframePolicy`: 600 ms, 1,5 s con un ciclo en curso) no cambia. | `IdrRequestGate`, `VideoPipeline.requestKeyFrame` |
| Reenvío directo (AA sin recodificar) | No se controla el encoder de AA. Un IDR grande se descarta como cualquier otro y se pide uno nuevo por `KeyframePolicy`. Con 3 seguidos, aviso en el log con la recomendación de un perfil que recodifique, y como mucho un ciclo de foco cada 5 s (también el vigilante), sin bucles. | `VideoPipeline.onOversized`, `aaOversizeBackoff` |
| Estadísticas | Mensaje de vídeo más grande enviado y frames descartados por tamaño en las estadísticas del núcleo (cada 5 s), en el resumen de la sesión, en `sessions.csv` (columnas nuevas `frame_max_kb` y `descartados_grandes`) y en el resumen del viaje. | `SessionStats`, `SessionSummary`, `QdSessionBridge` |
| «Esperar al coche» | Cada `Connect_Broadcast` sin sesión, aunque el TCP no llegue, **vuelve a contar** la búsqueda o la espera desde ese momento. Antes solo contaba la sesión: con el coche anunciándose sin conectar, todo se cerró 90 s después de verlo. Nunca acorta lo que quedaba. VÍDEO VIVO (30 s) no se alarga, solo el cierre de después. | `LinkLifecycle.carHeard`, `QdLinkHost` (un aviso cada 2 s como mucho), `LinkService` (también el motor original) |

**Decisiones.**
- **Tope de 480 KiB (491 520 B).** Queda por debajo incluso del IDR más grande que pasó (493 568 B). El objetivo de
  300 KB deja más de 150 KB de margen, así que en uso normal el tope no debería actuar nunca.
- **QP-I de partida 24.** A 1920×882 suele dar IDR de UI de unos 250-350 KB, con buena calidad: el intra-refresh y los
  P-frames afinan la imagen enseguida.
- **Por qué solo QP-I.** `KEY_VIDEO_QP_MIN/MAX` también tocaría los P-frames, que no son el problema.
- **QP en marcha.** No está garantizado que un encoder acepte el QP con `setParameters`. Si no lo respeta, se nota en el
  QP medio que informa o en el tamaño de los IDR, y entonces entra el plan B.
- **CBR.** No se ha cambiado: sin el coche no se puede medir si ayuda. Sigue disponible `enc_cbr`. Conviene compararlo
  en un viaje con el IDR máximo de `sessions.csv`.
- **Patrón y app.** Llevan un IDR por segundo. Los periódicos sin nada que contar se resumen en una línea cada 10 s; los
  pedidos y los que cambian algo salen siempre.

**Qué buscar en el log** (`logs/qd-*.log` y el log de la app):

| Línea | Significado |
|---|---|
| `VIDEO tamaño de los IDR: objetivo 300 KB, tope del núcleo 480 KB · QP-I mínimo adaptable 18-40 (empieza en 24)` | Al crear el encoder. Sin Android 12+: `sin claves de QP (Android < 12): cada IDR pedido baja el bitrate al 40 %` |
| `encoder: QP-I 24-51 al configurar; pide el QP medio de cada IDR` | El encoder aceptó las claves (y, en Android 13+, las estadísticas) |
| `VIDEO IDR 312 KB (QP 27) · QP-I mín 24 · pedido (CAR_REQUEST)` | Un IDR normal. `(QP n)` solo si el encoder lo informa |
| `VIDEO IDR 350 KB > objetivo 300 KB · QP-I mín 24 → 26 · pedido (STREAM_START)` | Ajuste del QP-I |
| `W … VIDEO IDR 526 KB > tope 480 KB: lo descarta el núcleo y se pide otro · QP-I mín 24 → 29 · próximo IDR pedido con el bitrate al 40 %` | IDR demasiado grande. Del núcleo: `vídeo: IDR de 538390 B (526 KB) > tope 491520 B (480 KB; el coche se cuelga con más de 512 KB): descartado, sin P-frames hasta el siguiente IDR; se pide otro más pequeño (1 en esta sesión)` |
| `VIDEO IDR 260 KB con el bitrate al 40 % · QP-I mín 29 · pedido (OVERSIZED, bitrate al 40 %)` | El IDR que lo sustituye |
| `IDR pedidos al encoder: 3 · aplazados 1 · servidos con otro 9 (antirrebote 600 ms)` | En las estadísticas de 5 s, solo si actuó el antirrebote |
| `núcleo: … · mensaje de vídeo máx. 313 KB · descartados por tamaño 1` | Estadísticas de 5 s del núcleo |
| `vídeo: … · mensaje máx. 313 KB · descartados por tamaño 1 (máx. 526 KB)` | Resumen de la sesión |
| `VIDEO IDR 23 · máx. 312 KB · por encima del objetivo 2 · del tope 0 · QP-I mín 26 (subidas 2, bajadas 0) · con bitrate bajado 0 · IDR pedidos 21 · aplazados 3 · servidos con otro 11` | Al parar el vídeo |
| `W Android Auto manda IDR de 540 KB, más de lo que admite el coche (480 KB): 3 seguidos descartados …` | Reenvío directo: mejor un perfil que recodifique |
| `ciclo: coche anunciado sin sesión: vuelvo a contar la espera desde ahora; cierro todo si no se anuncia en 5 min` | «Esperar al coche» vuelve a contar (una línea por minuto como mucho) |
| `ciclo: espera del coche vencida (7 min sin sesión, 5 min sin anuncios del coche): cierro todo` | Cierre tras los anuncios |

**Cómo comprobarlo en el coche.**
1. Viaje normal con el perfil «Coche». En `sessions.csv`, `frame_max_kb` ≤ 480 (lo esperado: unos 300) y
   `descartados_grandes` a 0 o casi. Ningún cierre `WRITE_STALL` con un `VIDEO_IDR` en curso en el detector de cortes.
2. Pantallas con mucho detalle (mapa a pantalla completa, galería): `VIDEO IDR …` debe quedarse cerca de 300 KB. Si
   sale `> tope`, la línea siguiente tiene que ser el IDR que lo sustituye, más pequeño.
3. Ver si el encoder del S25 acepta el QP en marcha: tras un `QP-I mín a → b`, los IDR siguientes deben salir más
   pequeños (o con `(QP ≥ b)`). Si no, aparece `el encoder no respeta el QP-I mínimo` o `el QP-I no basta`, y desde
   entonces cada IDR pedido sale `con el bitrate al 40 %`.
4. Con el coche encendido y la app de espejo cerrada, o sin llegar a conectar: HeadQLink no se cierra mientras el coche
   se anuncie (línea `ciclo: coche anunciado sin sesión…`).

**Resultados en el PC.** `:qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubDebug`: **BUILD SUCCESSFUL**.
- `:qdcore`: 109/109. `OversizedFrameTest` es nuevo (3 tests): el IDR de 538 390 B no llega al coche, sus P esperan,
  el siguiente IDR sale con SPS/PPS delante, el orden de los avisos, las estadísticas y el tope desactivable. Hay 3
  casos nuevos en `SendQueueTest` (las tres políticas, límite inclusivo, `rejectOversized`). Los tests que bloquean el
  `write()` con IDR de 0,5-2 MB llevan `maxVideoMessageBytes = 0`. El escaneo de API (minSdk 16) da lo mismo que antes.
- Todo el app: 2806 pruebas, 0 fallos y 1 saltada (la de `sh`). Son nuevas `IdrSizeControllerTest` (9: paso de QP,
  subida, tope, QP informado, techo, bajada lenta hasta el suelo, encoder que no hace caso, sin claves de QP con bajada
  adaptable y convergencia del escenario del coche) e `IdrRequestGateTest` (5: ≥ 600 ms, primer IDR inmediato, ráfaga,
  aplazamiento, urgente). Hay 4 casos nuevos en `LinkLifecycleTest` (anuncios sin sesión en búsqueda, en pausa y en
  VÍDEO VIVO; nunca acortan; una línea por minuto) y 1 en `SessionSummaryTest` (columnas `frame_max_kb` y
  `descartados_grandes`).

Sin probar todavía en el móvil ni en el coche.

---

## 12. Vídeo adaptado al enlace y protección térmica suave (2026-10-05)

**Informe (viaje 5, coche real, zona Wi-Fi, 21:40-22:25).** Sesiones buenas (S5, S8, S12: 29 fps, ≤ 1 ventana con
retraso > 100 ms, 4-7 % de frames de AA descartados por ir atrasados) frente a sesiones malas:

| Sesión | Qué pasó |
|---|---|
| S6 (Fluidez 60, 7,2 Mbit/s) | 13 % de frames de AA descartados por atrasados, 16 ventanas con write > 100 ms, 12 718 «esperas por enlace» (puerta de 64 KB), 1 388 retransmisiones en 6 min: enlace saturado. |
| S7 (30 fps, 4,3 Mbit/s) | Cortes de radio con rtt 100-300 ms, 50-63 segmentos sin confirmar, P-frames de 276 KB (ráfagas VBR de 15-19 Mbit/s en un segundo) y un `write()` bloqueado > 10 s con el coche mandando heartbeats → `WRITE_STALL` cerró la sesión. Mensajes < 480 KiB: no es el cuelgue de 512 KiB; el coche simplemente deja de leer con la radio congestionada. |
| S14/S16 (Básico, reenvío directo 720p30) | 9/17 ventanas con retraso en cola de 300-373 ms: la imagen iba con retardo visible (sin descartes posibles en el reenvío directo). |
| S17 | Tope térmico a 24 fps + 22 % de descartes por atrasados + otro bloqueo → watchdog. |

Los escaneos Wi-Fi del móvil cada 10 s (pantalla encendida, STA desconectada) **no** se correlacionan con los eventos
(21 % dentro de ±1,5 s frente al 29 % que daría el azar). **Conclusión:** el bitrate ofrecido y sus ráfagas superan a
ratos lo que la radio zona Wi-Fi ↔ coche puede llevar; el retraso se acumula en el kernel y en la cola; las ráfagas VBR
y los P-frames grandes lo empeoran; un `write()` bloqueado mata la sesión.

### 12.1 Bitrate según el enlace (`LinkRateController`)

Clase pura en `[hql]LinkRateController.java`, para los perfiles de bitrate fijo con relay GL («Coche» a 30 y a 60 fps,
«Automático» cuando es Coche, Medio, Muy bajo). Muy alto y Alto siguen con su ABR de siempre. Se alimenta cada ~100 ms
desde el monitor de red de la sesión (`QdSessionBridge.checkStalls` → `VideoHub.onLinkSample` → hql-video) con la
muestra de NetStat y lo que ve la puerta GL:

| Regla | Detalle |
|---|---|
| **Congestión** | Cola del kernel ≥ **48 KB** (o la puerta GL cerrada por el enlace ≥ 15 veces en la muestra) durante ≥ **300 ms** seguidos; o las retransmisiones suben ≥ 2 en el último segundo; o el rtt pasa del **doble del mínimo** de la sesión (y de 50 ms); o el núcleo vació la cola por retraso (> 150 ms). |
| **Bajar** | Bitrate × **0,7** (suelo **1,5 Mbit/s**), como mucho un paso cada **500 ms**, con `setParameters`. Si ya está en el suelo y la congestión sigue: tope de **24 fps** (si la sesión va por encima). Después, una línea «no hay más que bajar» y silencio. |
| **Subir** | Tras **5 s** seguidos sin congestión: primero vuelven los fps de la sesión; luego bitrate × **1,15** cada 5 s limpios hasta el techo. Una muestra congestionada reinicia la cuenta. |
| **Techo** | El bitrate del perfil (lo que pide el coche) o, con calor, el tope térmico: el menor. Bajar el techo recorta el bitrate en el acto. Los fps: el menor del tope térmico y del enlace (`VideoPipeline.applyFpsCap`). |
| **Sesión nueva** | Se empieza en el techo y con los fps de la sesión; las estadísticas (bitrate mínimo, congestiones) son por sesión. |
| **Con el control de los IDR** | Intacto: `IdrSizeController` (objetivo 300 KB, QP-I, bajada para el IDR pedido) actúa sobre el bitrate que diga el enlace (`VideoEncoder.setBitrate` respeta la bajada en curso). Tope de 480 KiB y SPS/PPS sin repetir, sin cambios. |

Opción de prueba: `link_fixed` (booleano) desactiva el controlador.

### 12.2 Ráfagas: CBR en el perfil Coche

`VideoPipeline.startEncoder`: con el perfil Coche, `KEY_BITRATE_MODE_CBR` si el códec lo declara
(`EncoderCapabilities.isBitrateModeSupported`); si no, VBR con `KEY_MAX_BITRATE` (tope de picos) en el bitrate pedido.
Se registra el modo elegido («modo de tasa CBR») y, con el primer formato de salida, si el intra-refresh sigue
(`intra-refresh-period` del formato de salida: «intra-refresh 30 frames confirmado (CBR)», o aviso si el códec lo apaga).
`enc_vbr` vuelve a VBR; `enc_cbr` sigue forzando CBR en los demás perfiles. El objetivo de 300 KB por IDR no cambia.

### 12.3 Cota de retardo

- Puerta «último frame»: cola del kernel < **32 KB** (antes 64 KB; a 5 Mbit/s, 50 ms en vez de 100 antes de contar
  nada). Configurable con `gate_outq_kb`. El descarte por retraso del núcleo (`MAX_LAG`, 150 ms) no cambia.
- Reenvío directo (Básico): la puerta del escritor espera como mucho **100 ms** (antes 250: cada frame podía esperar un
  cuarto de segundo en la cola del núcleo) y el freno a AA (`AaAckBrake`) retiene el ack mientras la cola del kernel
  pase de 24 KB **o** el frame esperó más de **120 ms** en la cola y aún quedan frames detrás (`shouldHold`, puro). Es
  AA quien espera (con su ventana de 2 frames) en vez de acumularse retraso. El tope de 250 ms desde el fin del write
  sigue: ningún ack se retiene para siempre, y al cerrar la sesión o parar se sueltan todos (sin bloqueo posible).
  Estadísticas de 5 s: `freno AA: N acks retenidos (cola del kernel a, retraso b, hasta el tope c), máx M ms · retraso
  máx L ms`.

### 12.4 Write bloqueado con el coche hablando

`PhoneSession.checkWriteStall`: pasado `writeStallTimeoutMs` (10 s) con un `write()` bloqueado:
- coche **callado** (nada recibido en los últimos 5 s): se cierra como antes (`WRITE_STALL … y el coche lleva N ms callado`);
- coche **hablando** (heartbeats, táctil): se aguanta hasta `writeStallCarTalkingTimeoutMs` (**20 s**; `write_stall_ms`
  en los ajustes de prueba), en cada comprobación del watchdog se tira el vídeo encolado (`SendQueue.flushVideo`, con
  cualquier política) y, en cuanto el `write()` vuelve, se tira lo que entró mientras tanto y se pide un IDR forzado.
  Se registra la decisión y se cuenta en `SessionStats.writeStalls`.

### 12.5 Resumen y `sessions.csv`

Columnas nuevas: `bitrate_min_kbps` (el más bajo aplicado por el enlace), `congestiones` (pasos por congestión) y
`writes_bloqueados`. `max_cola_ms` sigue. En el bloque de la sesión: `enlace: bitrate mín. 2.5 Mbit/s · congestiones 4
· writes bloqueados 1`; en el resumen del viaje, el total de congestiones y el bitrate mínimo.

### 12.6 Protección térmica más suave y elegible

Un usuario (móvil Qualcomm, Wi-Fi Direct, `aa_ext`) llegó al estado térmico 3 y se quedó a 20 fps el resto del viaje:
«menos fluido que el headqlink original» (que no tenía tope). `ThermalPolicy` cambia:

| Nivel (estado) | Normal (recomendada) | Suave | Apagada |
|---|---|---|---|
| Moderado (2) | 30 fps si la sesión va a 60; a 30, los mismos fps; bitrate ×0,8 | solo bitrate ×0,8 | solo se registra |
| Grave (3) | 24 fps; bitrate ×0,8 y ≤ 3,5 Mbit/s | 30 fps como mucho; mismo bitrate | ídem |
| Crítico (4+) | 20 fps; ≤ 3 Mbit/s | igual que Normal | ídem |

Vuelta tras **30 s** más fresco (antes 60). Ajuste nuevo **«Protección térmica»** en Ajustes de imagen (Normal / Suave /
Apagada; `thermal_mode` = `normal`, `suave`, `apagada`), en los cuatro idiomas de la app; se aplica en el acto al vídeo
vivo (`ThermalGuard.onModeChanged` → `VideoPipeline.applyThermal`, que ahora también reacciona al cambio de modo). El
controlador del enlace sigue tomando el menor de los dos topes (térmico y enlace).

### 12.7 Qué buscar en el log

| Línea | Significado |
|---|---|
| `encoder c2.qti.avc.encoder 1920x882@30 5080kbps baseline CBR … · modo de tasa CBR` | Perfil Coche con tasa constante. Si el códec no la admite: `modo de tasa VBR con tope de picos (el códec no admite CBR; tope de picos 5080 kbps)` |
| `encoder: intra-refresh 30 frames confirmado (CBR)` | El refresco intra sigue con CBR. `W intra-refresh apagado por el códec` = hay que volver a VBR (`enc_vbr`) |
| `VIDEO puerta «último frame»: cola del kernel < 32 KB · modo de tasa CBR` | Puerta nueva |
| `VIDEO bitrate adaptable al enlace 1.5 Mbit/s-5.1 Mbit/s (baja ×0.7 con congestión, sube 15 % cada 5 s limpio; 24 fps si en el suelo sigue)` | Controlador activo |
| `W enlace: congestión (outq 96 KB 310 ms, retrans +21) → bitrate 3.6 Mbit/s` | Paso de bajada, con la causa |
| `W enlace: congestión (rtt 210 ms (mín. 18)) con el bitrate en el suelo (1.5 Mbit/s) → 24 fps` | Suelo alcanzado: fps |
| `enlace: enlace limpio 5 s → 30 fps (bitrate 1.5 Mbit/s)` / `enlace: enlace limpio 5 s → bitrate 1.7 Mbit/s` | Recuperación |
| `enlace: bitrate 3.6 Mbit/s (mín. 2.5 Mbit/s, techo 5.1 Mbit/s) · congestiones 3 (bajadas 3, subidas 1)` | Estadísticas de 5 s (solo si actuó) |
| `térmico 3 → perfil grave (protección normal): 24 fps (sesión 30, enlace 24) · 3.5 Mbit/s (sesión 5.1, techo 3.5 Mbit/s)` | Tope térmico; con «Suave»: `30 fps`; con «Apagada»: `protección térmica apagada: solo se registra` |
| `protección térmica: suave (se aplica en el acto)` | Cambio del ajuste |
| `freno AA: 12 acks retenidos (cola del kernel 9, retraso 3, hasta el tope 1), máx 180 ms · retraso máx 140 ms` | Reenvío directo |
| `W un write() (VIDEO_P) lleva 10050 ms bloqueado pero el coche sigue hablando (último mensaje hace 1200 ms): aguanto hasta 20000 ms; vídeo encolado descartado (2 frames) y, al volver el write, se pedirá un IDR` | Prórroga del write bloqueado |
| `W el write bloqueado (VIDEO_P) volvió tras 12300 ms: 1 frames descartados, pido un IDR` | Vuelve el write |
| `cerrando: WRITE_STALL: un write() (VIDEO_P) lleva más de 20000 ms bloqueado (20100 ms) aunque el coche sigue hablando …` / `… y el coche lleva 6000 ms callado` | Cierre, con la decisión |
| `vídeo: … · enlace: bitrate mín. 2.5 Mbit/s · congestiones 4 · writes bloqueados 1` | Resumen de la sesión |

### 12.8 Cómo comprobarlo en el coche

1. Viaje con el perfil Coche: en `sessions.csv`, `max_cola_ms` ≤ ~150 en casi todas las sesiones, `congestiones` > 0
   solo en las de radio floja, `bitrate_min_kbps` ≥ 1500, y ningún `WRITE_STALL` con el coche hablando antes de 20 s.
2. Coche lejos del móvil (o zona Wi-Fi en 2,4 GHz): deben aparecer las líneas `enlace: congestión …` en menos de un
   segundo desde que la imagen empieza a ir a saltos, y `enlace limpio` al acercarse.
3. Perfil Básico: `freno AA: … retraso máx` por debajo de ~150 ms.
4. `adb shell cmd thermalservice override-status 3` con «Normal»: 24 fps; con «Suave»: 30 fps; con «Apagada»: sin
   cambios; `1` y 30 s después vuelve.

### 12.9 Resultados en el PC

`cmd /c ".\gradlew.bat :qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`:
**BUILD SUCCESSFUL**.

- `:qdcore`: 122/122. `WriteStallTest` es nuevo (4: coche hablando aguanta, vacía la cola y cierra a los 20 s con el
  motivo; coche callado cierra a los 10 s; al volver el write se pide un IDR y la sesión sigue; sin prórroga si el largo
  no es mayor) y hay un caso nuevo en `SendQueueTest` (`flushVideo` con las tres políticas).
- App: 2830 pruebas, 0 fallos y 1 saltada (la de `sh`); el paquete `com.headqlink.link`, 172/172. Nuevos:
  `LinkRateControllerTest` (15: arranque, cola alta 300 ms y un paso cada 500 ms, reinicio de los 300 ms, puerta
  sin NetStat, retransmisiones (y su ventana de 1 s), rtt (mínimo y suelo de 50 ms), vaciados por retraso, suelo de
  1,5 Mbit/s → 24 fps → nada más, recuperación fps-primero y +15 % cada 5 s, reinicio de la calma, techo térmico,
  Fluidez 60, perfil bajo el suelo, sesión nueva), `AaAckBrakeTest` (3), `ThermalPolicyTest` reescrito (niveles,
  30 s, Normal/Suave/Apagada) y un caso nuevo en `SessionSummaryTest`. De paso, `PhoneLinkReconnectTest` recorría una
  lista sincronizada mientras otros hilos registraban (fallo esporádico): ahora es `CopyOnWriteArrayList`.

Sin probar todavía en el móvil ni en el coche.

### 12.8 Viaje 6 (2026-10-06): ajuste del bitrate adaptable y vuelta a VBR

Con la 0.2.3 en el coche, el perfil Coche (CBR) se quedó en 1,7-2,5 Mbit/s durante toda la sesión y la imagen se
pixeló (fondo y carátula de Spotify en bloques). El control bajaba por «retrans +2..+7» con un IDR pasando por la cola
(cola ≥ 48 KB unos 250 ms) y subía +15 % cada 5 s. En el mismo enlace, el perfil Alto (VBR, ABR propio de headqlink),
frenado por el calor a 30 fps y 5 Mbit/s, llevó 4,3-4,8 Mbit/s sin perder un frame y con buena imagen.

Cambios:
- `LinkRateController`: fuera la regla de retransmisiones; cola alta sostenida 500 ms (antes 300); suelo =
  max(1,5 Mbit/s, 50 % del bitrate del coche) → 2,54 Mbit/s en el C10; bajada ×0,75; subida +25 % cada 3 s.
- Perfil Coche otra vez en **VBR** (`enc_cbr` sigue forzando CBR para pruebas; `enc_vbr` ya no hace falta).

## 13. Vuelta del coche tras un corte de radio (2026-10-06)

Viajes 5 y 6 en el C10. Tras un cierre normal (LOCAL, READ_ERROR) el coche se reanuncia a los ~5,3 s y conecta al
instante, siempre. Tras un **corte de radio** (WATCHDOG/WRITE_STALL con `cwnd 1` y «el coche lleva N ms callado»: el
enlace muerto en los dos sentidos ≥ 10 s) el siguiente `Connect_Broadcast` llegó a los 61, 109, 172 o 175 s (alguna vez,
nunca). Dos veces (09:03:06 y 09:20:36) llegó **un solo** broadcast, se mandó **un** ACK (puerto aleatorio nuevo), el
coche dejó de anunciarse (luego recibió el ACK) y nunca abrió el TCP: «el coche no conectó en 20000 ms», a buscar y
nada más hasta Desconectar/Conectar (broadcast a los 25 ms de abrir el UDP nuevo y conexión en 0,3 s). El 2026-10-05
un reinicio de la zona Wi-Fi desatascó al coche a los 8 s. No se sabe si el coche vuelve al puerto de antes, si hace
caso a un ACK que no pidió ni si sus broadcasts llegan al socket con la pantalla apagada: todo lo de abajo es defensivo,
va al log y se puede apagar por partes (extras de §5.2).

| Qué | Dónde | Por defecto |
|---|---|---|
| ACK cada 2 s hasta que llega el TCP o acaba el intento (`AckPolicy.UNTIL_CONNECTED`), y ACK en el acto con cada broadcast del mismo coche mientras se espera su TCP | `DiscoveryConfig.ackPolicy`, `PhoneLinkConfig.reAckOnBroadcast` (`qd_ack_resend`) | Sí |
| **Puerto estable**: los intentos siguientes reutilizan el `MirrorPort` de la última sesión (`SO_REUSEADDR`); si no se puede, uno aleatorio con aviso | `RecoveryConfig.stableMirrorPort` (`qd_stable_port`) | Sí |
| **Re-acogida** tras WATCHDOG, WRITE_STALL o READ_ERROR: estado `RECOVERING`, el mismo puerto escuchando hasta «Esperar al coche» (5 min) por si el coche vuelve directo a IP:puerto, y **ACK no pedidos** a IP_coche:18464 con ese puerto (cada 2 s el primer minuto, luego cada 5 s). Un broadcast suyo se contesta en el acto (y con los reenvíos de un intento normal) sin abrir otro servidor; si llega desde otra IP (zona Wi-Fi reiniciada) o es otro coche, intento nuevo | `RecoveryConfig.reclaim*`, `unsolicitedAck*` (`qd_reclaim`) | Sí |
| **UDP 18463 reabierto** tras 20 s sin ningún datagrama esperando al coche (como mucho cada 20 s), y el MulticastLock otra vez cada vez que el enlace vuelve a esperar al coche tras perder la sesión (o se reabre el UDP) | `RecoveryConfig.udpRefresh*`, `LinkService.refreshMulticastLock` (`qd_udp_refresh`) | Sí |
| **Diagnóstico** cada 10 s mientras se espera al coche, con ping a su IP (`/system/bin/ping -c 1 -W 1`, en el hilo de vigilancia) | `RecoveryConfig.diagIntervalMs`, `carReachable` = `CarPing` (`qd_car_ping`) | Sí |

La re-acogida no cuenta como «intento en marcha» para el ciclo de vida (§10) salvo en los 20 s siguientes a un
broadcast del coche: el vídeo vivo de 30 s, la pausa de AA y el cierre a los 5 min siguen igual. «Desconectar» lo para
todo (ACK no pedidos, puerto, vigilancia y ping).

### 13.1 Qué buscar en el log

| Línea | Significado |
|---|---|
| `QD/Link: re-acogida tras WATCHDOG: escucho otra vez en el puerto P hasta 300 s y llamo a 10.x.x.x con ACK no pedidos (…)` | Empieza la re-acogida (`enlace → RECOVERING`). |
| `QD/Discovery: ACK no pedido #n a /10.x.x.x:18464 (MirrorPort=P)` | Cada ACK no pedido (el #1 con el JSON completo). |
| `QD/Link: re-acogida: 10.x.x.x se anuncia; ACK en el acto con el puerto P` | Broadcast durante la re-acogida. |
| `QD/Link: esperando al coche tras WATCHDOG (30,0 s): 0 datagramas · ACK 0 pedidos / 16 no pedidos · 0 TCP · reaperturas UDP 1 · re-acogida en el puerto P · coche en la zona Wi-Fi: no (ping)` | Diagnóstico cada 10 s. «sí (ping)» y 0 datagramas = la radio del coche está, pero no se anuncia (o no nos llega). |
| `QD/Discovery: descubrimiento: reabro el socket UDP (20 s sin anuncios)` | Reapertura del UDP 18463 (y `HQL: MulticastLock cogido otra vez (UDP reabierto)`). |
| `QD/Link: vuelta del coche tras 7,3 s: por anuncio / por ACK no pedido / por puerto anterior (…; último anuncio hace … ms, último ACK no pedido hace … ms · …)` | Cómo volvió. «Por ACK no pedido» = TCP ≤ 500 ms tras uno, sin anuncio en los 20 s anteriores; un coche que vuelve solo justo tras un ACK no se distingue (por eso van los tiempos). |
| `QD/Link: no se pudo reutilizar el puerto P (…); uso uno aleatorio` | El puerto estable estaba ocupado. |
| `QD/Link: re-acogida: el coche no volvió al puerto P en 300 s` | Fin de la re-acogida sin coche (`enlace → SEARCHING`). |

### 13.2 Pruebas

- `:qdcore`: `PhoneLinkRecoveryTest` (11: ACK en cada broadcast y reenvíos hasta el TCP; puerto estable y aleatorio si
  está ocupado; sin puerto estable; re-acogida que acepta en el puerto de antes sin broadcast; cadencia de los ACK no
  pedidos y `close()` que lo para todo; coche que solo hace caso a un ACK no pedido; broadcast durante la re-acogida;
  un solo broadcast con el ACK perdido; reapertura del UDP tras el silencio con diagnóstico y ping; finales normales y
  re-acogida apagada; el coche desde otra IP), `RecoveryLogicTest` (4, reloj de mentira: vigilancia, clasificación,
  valores por defecto) y `DiscoveryListenerTest` (+3: cadencia con tramo lento, reapertura en el mismo puerto con los
  reenvíos por el socket nuevo, calendarios de las políticas). `CarSim`: `maxBroadcasts` y `directMirrorPort`.
- `:qdsim --scenario caida --local-phone`: corte de radio de 12 s (el coche ni habla ni lee; el móvil corta a los 10 s)
  y vuelta en tres variantes: **a** directo al puerto de antes, **b** un solo anuncio con el ACK perdido, **c** solo con un
  ACK no pedido. Las tres reconectan en < 10 s (≈ 0 ms, 1-2 s y 2 s desde que vuelve el coche) y el móvil dice «por puerto anterior», «por anuncio»
  y «por ACK no pedido». Contra el móvil: `--scenario caida --target <IP del móvil>`.

Sin probar todavía en el coche.

## 14. Cable USB (AOA, experimental) (2026-10-06)

**Qué hace QDLink por USB** (spec `01` §7, `04` §3.5 y §8.4). El coche es el *host* USB y pone el móvil en modo
accesorio con «Neusoft / QDriveLink / 1»; QDLink abre el accesorio (`getAccessoryList()` hasta 5 veces cada 50 ms,
permiso, `openAccessory`) y habla **el mismo protocolo 5A5A y la misma sesión** que por Wi-Fi, sin UDP ni TCP. Cierra
con `USB_ACCESSORY_DETACHED` o `ACTION_POWER_DISCONNECTED`. No se sabe si el C10 lo hace («solo algunos modelos»).

**Trama implementada** (`wire/BlockFraming`, en `:qdcore`):

| Sentido | Regla |
|---|---|
| Escritura (móvil y coche simulado) | Cada mensaje (JSON de control, heartbeat, SPS/PPS, IDR, P) se rellena con ceros hasta un múltiplo de 512 B y sale en **un solo** `write()`. El `totalSize` de la cabecera es el real (48 + N en vídeo). El AppStatus `!BIN` ya mide 512. |
| Lectura | Peticiones siempre múltiplo de 512 y nunca más allá del final (con relleno) del mensaje en curso: un bloque al empezar (como QDLink), la cabecera da `totalSize`, el resto del cuerpo en bloques enteros directos al destino y la cola por el búfer con una petición de un bloque, que trae el relleno. En USB eso evita que el núcleo tire lo que sobra de un paquete o que una lectura espere al mensaje siguiente. |
| Tolerancia | Lecturas cortas: se sigue leyendo. Relleno: se salta a la entrada del mensaje siguiente; si donde tocaba relleno llega un magic, el emisor no rellena (`unpaddedMessages`) y se sigue sin perder nada. Ceros de más entre mensajes: se saltan (`strayZeroBytes`), nunca son basura. `!BIN` de 512: sin relleno. |
| Sin cambios | Watchdog, heartbeats, IDR, tope de 480 KiB (sobre el tamaño real), handshake y táctil. |

**Transporte.** `PhoneSession` va sobre `SessionTransport`: `TcpTransport` (el TCP de siempre; con `blockSize` 512 para
probar la trama) o `StreamTransport` (el `ParcelFileDescriptor` del accesorio). Sin socket no hay NetStat: la puerta del
freno queda abierta, la del «último frame» se queda en «como mucho un frame en la cola del núcleo» y `LinkRateController`
usa el retraso del vídeo en la cola de la sesión (`videoQueueLagMs` ≥ 66 ms durante 500 ms) y los vaciados por retraso.

**En la app.**

- Conexión «Cable USB» (etiqueta «Experimental») en el paso 2, tras «Punto de acceso del móvil» (recomendado) y
  «Wi-Fi Direct». Sin requisitos de Wi-Fi ni de zona Wi-Fi: un consejo del cable de datos y, con QDLink instalada, el
  aviso de elegir HeadQLink al preguntar Android qué app abre «QDriveLink».
- `UsbAccessoryActivity` (transparente; filtro `res/xml/hql_usb_accessory_filter.xml`: Neusoft / QDriveLink, sin
  versión) arranca `LinkService` con `ACTION_USB_ATTACHED`, **sea cual sea la conexión elegida**. Con el cable puesto el
  cable tiene prioridad: se para el Wi-Fi (motor, descubrimiento, intento, P2P o vigilancia de la zona Wi-Fi) y al
  quitarlo vuelve el Wi-Fi configurado. El enlace por cable usa siempre el motor QDAuto.
- `UsbLink`: abre el accesorio con el permiso del aviso de conexión (si no lo hay, lo pide con un `PendingIntent` propio,
  mutable en Android 12+ y explícito); el mismo `QdSessionBridge`, `VideoHub` y ciclo de vida que por Wi-Fi (arranque
  con disparador `USB`, que pide el servidor de AA como el Bluetooth del coche). Al quitar el cable o perder la
  alimentación: cierre de la sesión y «coche perdido»; al volver a enchufarlo, se reanuda al instante. Si la sesión
  termina con el cable puesto, se vuelve a abrir con espera creciente (6, 6, 10, 30, 60 s; § 14.2).
- Ajustes aplicados con el cable: no se cierra la sesión (no se sabe si el coche repite el saludo por el cable); se
  rehace el vídeo (`QdSessionBridge.restartVideo`).
- Open Headunit: `UsbLauncherListener.onUsbAccessoryDetach` ya no corta la sesión de Android Auto en Self Mode (el
  accesorio que se va es el cable del coche).
- **Sondeo (`HQL/USB`)**, con el servicio en marcha (cualquier conexión) y desde la actividad: extras de la difusión fija
  `USB_STATE` (`connected`, `configured`, `host_connected`, `accessory`, funciones…), `ACTION_POWER_CONNECTED/
  DISCONNECTED`, alimentación según la batería y `getAccessoryList()` con fabricante, modelo, descripción, versión, URI,
  serie y permiso de cada accesorio, sean o no de Neusoft.

**Qué buscar en el log** (`logs/qd-*.log`, etiqueta `HQL/USB`):

| Línea | Significado |
|---|---|
| `sondeo (servicio en marcha): USB_STATE {accessory=false configured=true connected=true … } · alimentación USB · accesorios: ninguno` | Estado al arrancar |
| `USB_STATE: accessory=true … ` y `getAccessoryList (1 intento): fabricante «Neusoft» · modelo «QDriveLink» · … (permiso de HeadQLink: sí)` | El coche puso el móvil en modo accesorio, con sus nombres |
| `aviso de Android android.hardware.usb.action.USB_ACCESSORY_ATTACHED: accesorio …` | La actividad del accesorio |
| `accesorio Neusoft QDriveLink 1 conectado · abierto (…)` · `sesión S3 por cable en marcha (USB · Neusoft QDriveLink 1 · bloques de 512 B)` | Apertura y sesión |
| `S3: trama de bloques: … B de relleno enviados · del coche N mensajes con relleno, 0 sin él` | Al cerrar la sesión: si el coche no rellena, «sin él» > 0 |
| `accesorio desconectado: …` · `cierro la sesión S3: cable USB desconectado` · `cable USB fuera (…): vuelvo al Wi-Fi (…)` | Cable quitado |
| `alimentación desconectada (ACTION_POWER_DISCONNECTED)` · `cierro la sesión S3: el cable dejó de alimentar el móvil (como QDLink)` | Sin alimentación |
| `pido permiso para abrir …` · `permiso para …: concedido` | Diálogo de permiso |

**Probar la trama desde el PC.** En el móvil: Diagnóstico › «Opciones de prueba (QDAuto)» › «Trama del cable USB por
Wi-Fi» (`qd_usb_over_tcp`), Desconectar y Conectar (zona Wi-Fi). En el PC:

```powershell
qdsim\build\install\qdsim\bin\qdsim.bat --scenario normal --usb-framing --target <IP del móvil>
qdsim\build\install\qdsim\bin\qdsim.bat --scenario reconnect --sessions 5 --usb-framing --target <IP del móvil>
qdsim\build\install\qdsim\bin\qdsim.bat --scenario normal --usb-framing --local-phone
```

El coche simulado rellena y lee en bloques, y la comprobación `trama_usb` exige que todos los mensajes del móvil lleguen
rellenos (FAIL con la pista de la opción si no). Al acabar, **apagar la opción** (el C10 por Wi-Fi no la entiende).

**Pruebas en el PC.** `:qdcore`: `BlockFramingTest` (9) y `UsbSessionTest` (6, sesión completa por tubos en memoria
con la trama en los dos sentidos). App: `RequirementsTest`, `LinkModeDefaultTest`, `LinkLifecycleTest` y
`LinkRateControllerTest` con casos del cable. `:qdsim`: `QuirksTest` (`trama_usb` y la opción).

Sin probar todavía en el móvil ni en el coche: lo primero es enchufar el cable en el C10 y exportar el log.

### 14.1 Prueba real por cable (2026-10-06): espera creciente, accesorio desaparecido y «CABLE»

Log del móvil `qd-20261006-131226.log` (primera prueba con el cable en el C10):

| Qué pasó | Causa | Arreglo |
|---|---|---|
| Con el accesorio abierto pero el coche aún sin hablar, una sesión nueva cada ~11 s (WATCHDOG a los 10 s + reapertura en 1 s): **115 sesiones en 20 min** | La espera creciente (1, 2, 5, 10, 30 s) solo contaba las sesiones de menos de 10 s; las del watchdog duraban justo 10 s y la reiniciaban siempre | `UsbReopenPolicy` (puro): **1, 2, 5, 10, 30, 60 s** por cada sesión fallida seguida (sin ningún mensaje del coche, sin `CAR_INFO`, o con `CAR_INFO` pero de menos de 10 s); solo la reinicia una sesión con `CAR_INFO` de 10 s o más (o un accesorio que vuelve). Mientras dura la espera, solo abre el reintento programado (un `USB_STATE` repetido o la alimentación ya no abren antes). En 20 min de coche callado: unas 21 sesiones en vez de 115 |
| Al quitar el cable, S124 → S125 → S126 en milisegundos con `write failed: ENODEV` | El descriptor ya no valía, pero `getAccessoryList` y los avisos seguían ofreciendo el accesorio | ENODEV o EIO (o «No such device», «I/O error») en el cierre o en sus causas = **accesorio desaparecido**: no se reabre (ni en espera se le da prioridad sobre el Wi-Fi) hasta un `USB_STATE accessory=true` **nuevo** (paso de no a sí) o `USB_ACCESSORY_ATTACHED`; LinkService lo trata como un cable quitado (vuelve al Wi-Fi si la conexión elegida es otra) |
| «Corte … RADIO» en sesiones por cable | El detector de cortes no sabía del transporte | Por el cable, el mismo corte y los mismos datos se escriben **«Corte S3 INICIO CABLE: el coche no lee · …»** y «sigue … (CABLE: el coche no lee)»; en el resumen, «cable» en vez de «radio». La fila de `PerfTrace` sigue siendo `stall_radio` |

Líneas nuevas (etiqueta `HQL/USB`):

| Línea | Significado |
|---|---|
| `vuelvo a mirar el accesorio en 5000 ms (S12: la sesión terminó con el cable puesto (10 s de sesión, sin ningún mensaje del coche): no reinicia la espera; fallida 3 seguida, vuelvo a abrir en 5 s (1, 2, 5, 10, 30, 60 s))` | Espera creciente |
| `… (600 s de sesión, con CAR_INFO); espera creciente reiniciada (había 4 fallidas seguidas)` | Una sesión buena la reinicia |
| `espera creciente en curso: no abro por «USB_STATE» (lo hará el reintento programado)` | Otro aviso durante la espera (una línea por espera) |
| `W S125: accesorio desaparecido (ENODEV; 0 s de sesión): no lo vuelvo a abrir hasta que el coche lo vuelva a poner en modo accesorio (USB_STATE accessory=true o USB_ACCESSORY_ATTACHED)` | ENODEV/EIO |
| `accesorio desaparecido (ENODEV/EIO): no lo abro (…) hasta que …` | Intentos refrenados (una línea) |
| `USB_STATE accessory=true: el accesorio del coche ha vuelto; se puede abrir otra vez` | Vuelta del accesorio |

Pruebas: `UsbReopenPolicyTest` (5: espera creciente con sesiones calladas, reinicio solo con CAR_INFO de ≥ 10 s, 20 min de
coche callado, ENODEV/EIO hasta que vuelve, detección de ENODEV/EIO sin falsos positivos) y un caso nuevo en
`StallDetectorTest` (textos «CABLE: el coche no lee» y resumen «cable»).

### 14.2 Viaje por cable (2026-10-09): el coche no ve el primer saludo

28 sesiones en dos días, 24 por cable. Las que llegan a tener imagen van bien (30–40 fps, sin descartes), pero 14 por
cable se quedan sin imagen: ~4 min sin imagen en una mañana.

| Qué pasó | Causa | Arreglo |
|---|---|---|
| 6 sesiones en las que el coche manda heartbeats (uno cada ~3 s) y nunca `CAR_INFO`; a los ~18 s se calla y el watchdog corta a los ~30 s. Al reabrir, `CAR_INFO` llega en milisegundos | El coche empieza a leer unos segundos después de poner el móvil en modo accesorio y no ve el AppStatus, que se manda al abrir (en una, su primer heartbeat llegó 3 s después del AppStatus) | Por cable, si llega un HEARTBEAT sin `CAR_INFO` y hace ≥ 2,5 s del último AppStatus, **se repite el AppStatus** (como mucho 3 veces): `SessionConfig.appStatusResendMax`/`appStatusResendIntervalMs`. Si a los **12 s** sigue sin `CAR_INFO`, se cierra para volver a abrir (`handshakeTimeoutMs`, `UsbLink.USB_HANDSHAKE_TIMEOUT_MS`). Por Wi-Fi, como QDLink: un solo AppStatus y sin límite |
| 5 sesiones en las que el coche ni lee («CABLE: el coche no lee»), tras reabrir a 1–2 s de una fallida | QDLink no arranca por cable antes de 6 s desde la última desconexión (LC/a.java:1690-1707, `e.f9715s`) | `UsbReopenPolicy`: **6, 6, 10, 30, 60 s** (nunca menos de 6 s) |
| 3 sesiones con `write failed: ENODEV` en 0 s | Cable quitado | Nada nuevo (accesorio desaparecido, § 14.1) |

Líneas nuevas:

| Línea | Significado |
|---|---|
| `W el coche habla (HEARTBEAT) pero no ha empezado la sesión (sin CAR_INFO): repito el AppStatus (1/3)` | Saludo repetido |
| `cerrando: WATCHDOG: 12010 ms sin CAR_INFO del coche (AppStatus repetido 3 veces)` | Límite del saludo por cable |

Pruebas: `AppStatusResendTest` (5: repetición pasado el intervalo y no antes, tope, una sola vez por defecto, cierre sin
`CAR_INFO` y sin cierre una vez saludado) y `UsbReopenPolicyTest` con las esperas nuevas.

---

## 15. Sin accesibilidad: arranque manual del servidor de Android Auto (2026-10-06)

**Por qué.** En los modos Android Auto la accesibilidad (`TouchService`) solo la usa `AaServerStarter`: abrir los
ajustes de AA › ⋮ › «Iniciar servidor de la unidad principal» (127.0.0.1:5277) y luego «Parar servidor unidad
principal», la capa «Cerrando Auto…» y los ganchos del desbloqueo. Los toques del coche van por el protocolo de AA
(`TouchService.inject` solo lo usa `AppSource`, el modo App). Desde AA 17.4 no hay intent para arrancar el servidor, de
ahí la automatización; pero en Android 13+ una app instalada desde un APK necesita antes «Permitir ajustes
restringidos», y eso es lo que más cuesta a los usuarios.

**Ajuste.** «Arranque del servidor de Android Auto» (`aa_server_start`): «Automático (accesibilidad) · Recomendado»
(`auto`, por defecto: lo de siempre) o «Manual (sin accesibilidad)» (`manual`). Está en Ajustes de imagen › Avanzado
(solo en los modos Auto) y en la Comprobación: con el automático y la accesibilidad sin activar, la fila «¿Sin
accesibilidad?» ofrece «Arranque manual» (con un diálogo que explica lo que cambia); con el manual, la fila del servidor
lleva «Modo automático». También como extra `aa_server_start`. Se lee en cada decisión: vale en el acto. El manual solo
manda con AA 17.4 o más (su servidor de head unit) y sin `force_legacy_launch` (`AaServerManual.applies`).

### 15.1 Primera prueba (18:31-18:34): la sonda bloqueaba el servidor

La primera versión del manual (commit `084df42f`) miraba si el servidor «contestaba» abriendo y cerrando una conexión
TCP a 127.0.0.1:5277 (400 ms de plazo): en cada arranque del enlace y con el coche anunciado, cada 2 s esperando al
usuario, cada 60 s esperando al coche, al cerrar y en cada «Comprobación». En el móvil (logcat), a las 18:33:12 el
usuario arranca el servidor (`GH.DHUService: Network server running on port 5277`), la sonda conecta y cierra sin
mandar nada y AA anota `Head unit connected` → `CAR.GAL ReaderThread: end of stream received, dataReceived=false` →
`ProjectionErrorCode = PROTOCOL_IO_ERROR(3) … READER_CLOSE(52)`. Desde ahí el núcleo acepta el TCP pero AA no atiende:
la conexión real del Self-Mode (18:33:27) nunca tuvo otro `Head unit connected` y el coche se quedó sin imagen.

De ahí se sacó (commit `b98f8069`) que el servidor atiende **una conexión por arranque**, y el manual se hizo en
consecuencia: sin sondeos, con los intentos de verdad (bien) y con Android Auto aparcado entre viajes y el aviso
«Android Auto cerrado» para reiniciarlo tras cada cierre (mal: ver 15.2).

### 15.2 Prueba real de los intentos (S25 Ultra, AA 17.7, 2026-10-06 20:01-20:09): A, B y C

Con los intentos de `b98f8069` en el móvil (nuestro log y logcat):

| | Qué pasó |
|---|---|
| **A** | Servidor apagado: cada intento, rechazado al momento, cada 5 s, con el aviso. El usuario lo arranca a las 20:02:55 (`GH.DHUService: Network server running`) y vuelve a HeadQLink: **intento 17 `servido` en 349 ms** (`Head unit connected` 20:02:57). Vídeo a 29,6 fps durante 2 min, ffmpeg sin un error |
| **B** | **Desconectar** a las 20:04:21 (nuestro cierre es limpio: ByeBye). El log y el aviso decían «Android Auto cerrado: su servidor ya atendió su única conexión… hay que pararlo y volver a iniciarlo». Conectar otra vez **sin reiniciar el servidor**: **intento 1 `servido` en 338 ms** (`Head unit connected` 20:04:53, el mismo proceso de AA); 29,6 fps durante 90 s |
| **C** | Desde el PC (`adb forward`), a las 20:06:44, una conexión abierta y cerrada de golpe **sin un byte de protocolo** (lo que hacía la sonda): `Head unit connected` → `ReaderThread: end of stream received, dataReceived=false` → `ProjectionErrorCode = PROTOCOL_IO_ERROR(3) … READER_CLOSE(52)`. **Desde ahí el servidor acepta el TCP y no contesta**: intento 1 `no servido (a los 6062 ms)…`, el aviso y reintentos cada ~11 s (6 s de plazo y 5 s de espera). El usuario: ⋮ › Parar (20:08:41) y ⋮ › Iniciar (20:08:47): **intento 11 `servido`** (20:08:49). Después el proceso de proyección de AA se cayó solo (`GH.CrashHandler`, pid 7345 → 11509) y su servidor volvió por sí mismo en el proceso nuevo (`Network server running` 20:08:49.787); el reintento por «AA se desconectó con la sesión en marcha» fue `servido` a las 20:08:55 |

**Conclusiones.**

- **El servidor es reutilizable**: después del final limpio de una sesión (ByeBye) vuelve a atender, sin reiniciarlo
  (B). «Una conexión por arranque» era falso.
- **Lo único que lo bloquea es una conexión rota o cortada a medias** (C: abrir y cerrar sin hablar, como la sonda; o
  una sesión que se corta de golpe). Bloqueado, acepta el TCP pero no contesta hasta pararlo y volver a iniciarlo, y
  entonces atiende al siguiente intento.
- **Los intentos funcionan** (A, C): servido = los primeros bytes de AA; 6 s con el TCP abierto y sin respuesta = no
  servido; aviso al primer fallo; reintento cada 5 s; AA que cae con la sesión en marcha se vuelve a intentar.
- Por lo tanto, sobran el aparcamiento de AA entre viajes y el aviso «Android Auto cerrado», y lo que hay que cuidar es
  que **cada cierre de nuestra conexión sea limpio** (15.3, `AaClose`).

### 15.3 Qué hace el manual ahora

Nunca se pulsa nada y **nunca se abre una conexión a 5277 salvo la conexión de verdad del Self-Mode** (fuera, desde
`b98f8069`, `AaServerManual.probe` y todo lo que lo usaba). El ciclo de vida es **el del automático** (vídeo vivo,
pausa, cierre al vencer «Esperar al coche»), salvo que el servidor no se apaga nunca (sin accesibilidad no se puede):

| Situación | Qué pasa |
|---|---|
| Conectar, Bluetooth del coche, cable USB, coche anunciado | Nada con el servidor (`AaServerPolicy.Action.AT_SESSION`): una línea en el log. Con el servidor encendido, el viaje funciona **también con el móvil bloqueado**, sin automatizar nada |
| Una sesión con el coche necesita AA (arranca el vídeo de AA, el coche vuelve de la pausa con AA caído) | `AaPassthroughSource.ensureAaConnected` → `AaServerManual.sessionNeedsAa` → intento N: se lanza el Self-Mode, que marca 127.0.0.1:5277 |
| AA contesta | **Servido**: fuera el aviso (si estaba); la fila «Auto» pasa a «Arrancando Auto» y luego a la imagen; consta el modo desarrollador. Mientras la sesión lo use, se mira cada 2 s que siga conectado |
| AA no contesta | Rechazado (servidor apagado: al momento), cerrado sin respuesta, **6 s con el TCP abierto y sin respuesta** (servidor bloqueado) o 30 s sin llegar a marcar: se cierra ese intento (`CommManager.disconnect` sin ByeBye: AA no ha dicho nada, su servidor ya estaba bloqueado y el ByeBye va cifrado, tras el handshake; nunca si AA acaba de contestar), aviso de prioridad alta **«Arranca (o vuelve a arrancar) el servidor de Android Auto»** (al tocarlo, los ajustes de AA), la fila «Auto» y el widget dicen **«Esperando al servidor de Android Auto»** y **reintento cada 5 s** mientras la sesión necesite AA. Volver a la pantalla principal de HeadQLink adelanta el reintento a 0,5 s |
| Un reintento es servido | Fuera el aviso. Un servidor recién (re)arrancado atiende al primer reintento |
| La sesión deja de necesitar AA (3 s sin el vídeo de AA) | Se deja de intentar y fuera el aviso |
| AA se cae con la sesión en marcha (servidor parado, AA actualizado o caído) | 3 s sin AA: intento nuevo (que avisará si el servidor no atiende) |
| Coche perdido | Como el automático: 30 s de vídeo vivo y después AA en pausa (`AaPark`) escuchando al coche hasta «Esperar al coche»; si vuelve, sale al instante |
| Vence «Esperar al coche», Desconectar, Bluetooth fuera sin sesión, el sistema para el servicio | `ShutdownPlan.LEAVE_SERVER_ON`: AA se cierra **con orden** (ByeBye, `AaClose`) y el servidor se queda encendido. **Sin aviso**: una línea en el log. Bloqueado o no: sin apagado que esperar, AA no se aparca hasta desbloquear (igual que el automático sin accesibilidad). **El viaje siguiente vuelve a usar el servidor sin reiniciarlo, también con el móvil bloqueado**, mientras siga encendido |
| El guardián tenía AA aparcado cuando se eligió el manual | Como el automático salvo el apagado: al desbloquear suelta AA con orden y el servidor sigue atendiendo; si AA se cae antes, se va sin aviso (desbloquear no cerraría el servidor) |
| Cambio de perfil (o de modo desde el widget) en marcha | AA se desconecta con ByeBye (`AaClose.reconnectAa`) y la sesión nueva lo vuelve a lanzar, sin reiniciar el servidor. Fuera el aviso «queda gastado» |
| Red de seguridad | Ninguna entrada de `AaServerStarter` pulsa nada con el manual. `startAndWait` (lo llama el Self-Mode tras una marcación rechazada) devuelve false sin mirar el puerto; `reportCannotStart` solo lo apunta; `AaRecovery` (servidor «sordo») no pone el error rojo: lo llevan los intentos |

**Cierres limpios (`AaClose`).** Todo cierre de nuestra conexión con AA que pide HeadQLink pasa por `[hql]AaClose`: el
del enlace (cualquier plan de `LinkLifecycle.shutdownPlan`, también el del automático), el del guardián, el reintento
del apagado del automático y la reconexión por un cambio de perfil.

- Con la sesión hecha (`HandshakeComplete`, `TransportStarted`): la orden de siempre a `AapService`
  (`ACTION_STOP_SERVICE` o `ACTION_DISCONNECT`), que manda el ByeBye, espera su envío y luego cierra el socket. Es el
  cierre de B.
- Con el handshake a medias (`Connecting`, `Connected`, `StartingTransport`) o un Self-Mode pedido hace menos de 1,5 s
  (aún puede estar marcando): espera en su propio hilo a que termine, como mucho 6 s (si en 6 s AA no ha dicho nada, su
  servidor ya estaba bloqueado y cerrar no cambia nada), y entonces la orden. Antes, un cierre en ese momento cortaba el
  handshake (el ByeBye va cifrado: no sale antes del SSL) o dejaba un socket abierto sin dueño que el siguiente
  `connect` cerraba sin decir nada: el caso C.
- Si el enlace vuelve a arrancar mientras espera, ese cierre se anula: la conexión es la del enlace nuevo.

El único cierre sin ByeBye que queda es el de un intento al que AA no ha dicho nada en 6 s (ya estaba bloqueado).

**Comprobación (`Requirements`).** Como antes («Accesibilidad» opcional, «Modo desarrollador» consejo salvo que conste,
el modo App igual, nada bloquea «Conectar»), y la fila **«Servidor de Android Auto»** sin mirar el puerto: «En uso por
HeadQLink» (AA conectado: ✓; al cerrarse con normalidad sigue atendiendo, no hace falta reiniciarlo), «No atiende» (los
intentos fallan: !) o, sin saber, cómo arrancarlo (consejo: no se comprueba antes porque una sonda lo bloquearía).
`Requirements.AaServer`: `IN_USE` / `WAITING` / `UNKNOWN`; que AA haya atendido a HeadQLink consta como modo
desarrollador activo.

**Decisión pura (`AaServerPolicy`).** `onNeed`: con AA conectado, `NONE`; manual, siempre `AT_SESSION` (bloqueado o no,
sea cual sea el motivo); automático, lo de siempre (`NONE` / `AUTOMATE` / `AUTOMATE_ON_UNLOCK` / `NO_ACCESSIBILITY`).
`onEnd(aaMode, estado)`: automático, `STOP_SERVER`; manual, `LEAVE_SERVER_ON` (siempre: Desconectar o fin del viaje,
bloqueado o no). Fuera `KEEP_AA`, `CLOSE_AA`, `CLOSE_AA_RESTART`, `State.aaReady`, `State.serverUsed` y el parámetro de
Desconectar; en `LinkLifecycle`, fuera `KEEP_AA_PARKED`, `CLOSE_AA`, `CLOSE_AA_RESTART`, `Env.aaReady`, `Env.serverUsed`
y `shutdownPlan(env, Desconectar)`, y vuelve `LEAVE_SERVER_ON`. Los intentos los decide `AaServeAttempts` (puro, reloj
inyectable), sin cambios:

| Fase | Pasa a |
|---|---|
| REPOSO | INTENTO cuando la sesión necesita AA y no está conectado; con AA servido y la sesión usándolo, vigila (2 s) y, si AA falta 3 s, INTENTO |
| INTENTO | REPOSO si AA contesta (contador de respuestas o handshake terminado); ESPERA si no (rechazado, cerrado, 6 s con TCP, 30 s sin marcar), con el aviso al primer fallo |
| ESPERA | INTENTO a los 5 s (o 0,5 s al volver a la app) si la sesión sigue necesitando AA; REPOSO, sin aviso, si deja de necesitarlo 3 s; REPOSO si una respuesta tardía llega con el TCP abierto |

### 15.4 La señal de «servido»

**Los primeros bytes que manda AA en el intercambio de versión** (normalmente su VERSION_RESPONSE), lo primero del
handshake de AAP: `AapTransport.handshake` llama a `onPeerAnswered` con el primer `recv > 0` y `CommManager.peerAnswers`
(un contador que solo crece) lo cuenta; `AaServeAttempts` lo compara con el valor de antes de lanzar. Por qué esta:

- Es la más temprana fiable: un servidor bloqueado acepta el TCP (lo acepta el núcleo) pero nunca escribe un byte, y uno
  que atiende contesta en milisegundos (338-349 ms desde el lanzamiento del intento en B y A).
- Con el contador, un intento servido que se cae entre dos vistazos (cada 250 ms) sigue contando como servido.
- El plazo de 6 s nunca cierra una conexión a la que AA ya ha contestado (cortarla a medias la bloquearía): se mira el
  contador al decidir y otra vez justo antes de cerrar. Con `HandshakeComplete` (el SSL terminado) como señal, una
  respuesta a los 5,9 s con el SSL a los 6,1 s se habría cerrado.
- Respaldo: `HandshakeComplete` y `TransportStarted` también cuentan.

No vale que el TCP conecte (`CommManager.isConnected` ya es cierto en `Connected`) ni el «CONNECTED via dev server» del
Self-Mode.

**Código.** `[hql]AaServeAttempts.java` (los textos: «Android Auto no contesta: su servidor está bloqueado (pasa si una
conexión se cortó a medias); páralo y vuelve a iniciarlo»); `AaServerManual` (sin el aviso «Android Auto cerrado» ni su
canal, que se borran al arrancar el enlace si quedaron de la versión anterior; marca cada Self-Mode para `AaClose`);
`AaClose` (nuevo); `AaServerPolicy` y `LinkLifecycle` (`LEAVE_SERVER_ON`); `LinkService` (cierre con `AaClose`, sin el
aviso del cambio de perfil); `AaGuardService` (sin el sabor manual: suelta al desbloquear como el automático, sin
«Cerrar Android Auto»); `AaPassthroughSource`, `AaServerStarter` y `AaRecovery` (marcan el Self-Mode; el reintento del
apagado cierra con `AaClose`); `HomeActivity` (fuera el aviso del cambio de perfil); comentarios en `Config`,
`Checklist`, `Requirements`, `LinkControl`, `WidgetUpdater` y `AaPark`. En Open Headunit, los tres ganchos marcados
«headqlink» siguen igual (`AapTransport.onPeerAnswered`, `CommManager.peerAnswers` y, en
`SelfLauncherV17_4.tryDevServer`, `AaServerManual.onDevServerDial`), con el comentario corregido. Textos en los cuatro
idiomas (la descripción de la opción, el diálogo, la guía, la fila del servidor de la Comprobación, la ayuda de
«Esperar al coche» y del perfil; fuera «Android Auto cerrado», «Cerrar Android Auto», «Android Auto en pausa para el
próximo viaje» y «queda gastado») y manuales (sección «Sin accesibilidad»).

### 15.5 Qué buscar en el log

Todo con «ciclo:» en `headqlink-*.log` (logcat: etiqueta `HeadQLink`):

| Línea | Significado |
|---|---|
| `servicio iniciado. … servidorAA=manual` | El ajuste |
| `arranque (Conectar): arranque manual del servidor de Android Auto (sin accesibilidad): no lo compruebo antes (una conexión de prueba lo bloquearía); …` | Arranque sin sondeo (también «Bluetooth del coche», «cable USB del coche», «coche anunciado») |
| `AA server (arranque manual): intento 1 (la sesión con el coche necesita Android Auto): lanzo Android Auto (Self-Mode) contra 127.0.0.1:5277; …` y `AA: lanzando Self-Mode` | Un intento |
| `AA server (arranque manual): intento 1: 127.0.0.1:5277 acepta el TCP a los 15 ms; espero a que Android Auto conteste (como mucho 6 s)` | TCP abierto: aún no es «servido» |
| `AA server (arranque manual): intento 1: servido: Android Auto contestó (intento lanzado hace 338 ms)` (`; quito el aviso` si lo había) | Servido |
| `W ciclo: AA server (arranque manual): intento 1: no servido (a los 6062 ms): 127.0.0.1:5277 aceptó el TCP y en 6 s no llegó respuesta: Android Auto no contesta: su servidor está bloqueado (pasa si una conexión se cortó a medias); páralo y vuelve a iniciarlo; cierro esa conexión; reintento en 5 s; aviso «Arranca (o vuelve a arrancar) el servidor de Android Auto»` | Servidor bloqueado (C) |
| `W ciclo: AA server (arranque manual): intento 1: no servido (a los 20 ms): 127.0.0.1:5277 rechaza la conexión (el servidor de Android Auto está apagado); reintento en 5 s; …` | Servidor apagado (A) |
| `AA server (arranque manual): intento 2 (reintento): lanzo …` | Reintento (cada 5 s) |
| `AA server (arranque manual): de vuelta en HeadQLink: adelanto el reintento` | De vuelta de los ajustes de AA |
| `AA server (arranque manual): dejo de intentarlo: la sesión con el coche ya no necesita Android Auto; quito el aviso` | Sin sesión |
| `AA server (arranque manual): intento 1 (Android Auto se desconectó con la sesión en marcha): lanzo …` | AA cayó en marcha |
| `cierre (a mano): arranque manual: cierro Android Auto con su ByeBye; su servidor sigue encendido y listo para el próximo viaje (HeadQLink no lo para)` (sin «(a mano)» al vencer la espera) y `AA server (arranque manual): Android Auto cerrado (Desconectar) con un cierre limpio (ByeBye): su servidor sigue encendido y atenderá la próxima conexión sin reiniciarlo; HeadQLink no lo para` | Desconectar o fin del viaje (B): sin aviso |
| `AA: cierro Android Auto (…) en cuanto termine su handshake en curso: cortarlo a medias bloquearía su servidor` y `AA: handshake terminado a los 420 ms; cierro Android Auto (…) con su ByeBye` | Un cierre que espera al handshake (`AaClose`) |
| `W ciclo: AA: tras 6000 ms el handshake sigue a medias (Android Auto no contesta: su servidor ya estaba bloqueado); cierro igualmente (…)` | AA no contestó mientras se esperaba |
| `AA: anulo el cierre que esperaba a que terminara el handshake (el enlace vuelve a arrancar)` | Conectar justo después de cerrar |
| `aplicando ajustes: reconecto Android Auto (perfil …)` | Cambio de perfil en marcha (cierre limpio y reconexión) |

En logcat, además: `OPENHU` (`SelfMode: diag [Path3:connect1] OK` o `REFUSED`, `Handshake: Version response
received`, `AapTransport stopping and sending byebye`, `HeadlessDriver: handshake completo, arranco la lectura`) y AA
(`GH.DHUService: Network server running on port 5277`, `GH.DHUService: Head unit connected`, `CAR.GAL`,
`ProjectionErrorCode`). Un `ReaderThread: end of stream received, dataReceived=false` seguido de `PROTOCOL_IO_ERROR` es
un corte a medias: el servidor queda bloqueado.

### 15.6 Cómo comprobarlo en el móvil

1. Manual y accesibilidad desactivada; Ajustes de AA › ⋮ › «Parar servidor» (si sale). Abrir HeadQLink y la
   «Comprobación» varias veces: en logcat **ningún** `GH.DHUService` nuevo.
2. Conectar con el coche y el servidor apagado: `no servido … rechaza la conexión`, la notificación y la fila «Auto» y el
   widget en «Esperando al servidor de Android Auto»; `intento 2 (reintento)` cada ~5 s.
3. Tocar la notificación › ⋮ › «Iniciar servidor de la unidad principal» y volver: `intento N: servido … quito el
   aviso` y la imagen en el coche (A).
4. Desconectar y Conectar **sin reiniciar el servidor**: `intento 1: servido` y la imagen (B), y ningún aviso «Android
   Auto cerrado».
5. Apagar el coche y esperar a que venza «Esperar al coche» con el móvil bloqueado: `cierre: arranque manual: cierro
   Android Auto con su ByeBye…`, sin guardián ni «Auto en espera»; el viaje siguiente, **con el móvil bloqueado**:
   `intento 1: servido` sin tocar el servidor.
6. Cambio de perfil en marcha: `aplicando ajustes: reconecto Android Auto`, `AapTransport stopping and sending byebye` y
   el intento siguiente servido, sin aviso.
7. Servidor bloqueado (C): con `adb forward tcp:5277 tcp:5277`, abrir y cerrar una conexión desde el PC; el siguiente
   intento, `no servido (a los 6 s)` y el aviso; ⋮ › Parar y ⋮ › Iniciar: servido.

### 15.7 Pruebas en el PC

`AaServeAttemptsTest` (17, sin cambios en la lógica: servido al primer intento sin aviso; rechazado → aviso una vez y
reintento a los 5 s, no antes, hasta el servido que lo quita; TCP sin respuesta → no servido a los 6 s justos y se
cierra, con el motivo «su servidor está bloqueado (pasa si una conexión se cortó a medias)… páralo y vuelve a
iniciarlo» y ya sin «por arranque»; respuesta a los 5,9 s → servido y nunca se cierra; el handshake terminado cuenta;
cerrado sin respuesta, también entre dos vistazos; 30 s sin marcar, y atascado conectando se cierra; nunca dos intentos
a la vez ni con AA conectado; sin sesión 3 s se deja de intentar y fuera el aviso, un parpadeo no; sin sesión no se
reintenta ni se avisa; volver a la app adelanta el reintento; reiniciar quita el aviso; respuesta tardía en la espera =
servido; cada intento y su resultado en el log con su número; AA que cae en marcha → intento tras 3 s; la vigilancia
empieza con AA conectado y acaba con la sesión; los tiempos). `AaServerPolicyTest` (6: el manual nunca automatiza ni
sondea; AA conectado no pide nada; el automático de siempre; matriz completa; el final del manual es `LEAVE_SERVER_ON`
sea cual sea el estado; el final del automático). `LinkLifecycleTest` (los 5 del manual: ningún arranque comprueba
nada; AA conectado o aparcado no dice nada y el guardián se adopta; la vuelta con AA caído deja el intento al vídeo; el
ciclo de vida del automático (vídeo vivo 30 s, pausa y cierre al vencer la espera); `LEAVE_SERVER_ON` con cualquier
combinación de bloqueo, conexión, pausa, accesibilidad y ajustes, nunca `PARK_UNTIL_UNLOCK`). `AaCloseTest` (4, nuevo:
cada estado de `CommManager` en lo que importa para cerrar; con la sesión hecha o sin nada, ya; con el handshake a
medias o un Self-Mode recién pedido, se espera; nunca más de 6 s). `RequirementsTest` y `LinkGlanceTest`, sin cambios.

### 15.8 Limitaciones

- Probado en el móvil: los intentos (A, B y C). **Sin probar en el móvil**: el cierre al vencer «Esperar al coche» con
  el manual (es el cierre limpio del automático), la espera de `AaClose` al handshake y el cambio de perfil con el
  manual (ByeBye y reconexión a los pocos segundos: si AA tardara más de 6 s en volver a atender, saldría el aviso).
- Una sesión que se corta de golpe por fuera de HeadQLink (el sistema mata la app, AA cierra la conexión, el móvil se
  queda sin batería) puede dejar el servidor bloqueado: lo dice el siguiente intento, con el aviso.
- El plazo de 6 s se cuenta desde el primer vistazo con el TCP abierto (cada 250 ms). Si el Self-Mode prueba antes las
  vías inalámbricas (receptores de AA activados), puede tardar hasta ~20 s en marcar 5277 (plazo sin marcar: 30 s).
- El servidor sigue encendido entre viajes (es lo que permite el siguiente sin tocar nada) y escucha en toda la red: en
  una Wi-Fi pública, cualquiera podría conectarse (y, cortando a medias, bloquearlo). Los manuales recomiendan pararlo
  cuando no se use.
- Los nombres del menú de AA en inglés y portugués siguen escritos sin verlos en el móvil.

### 15.9 Resultados en el PC

`cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`: **BUILD SUCCESSFUL**.
App: 2927 pruebas, 0 fallos y 4 saltadas (la de `sh` y las tres de dibujo, que piden `-Ppreview`); el paquete
`com.headqlink.link`, 269 (4 de `AaCloseTest`, 17 de `AaServeAttemptsTest`, 6 de `LinkWidgetViewsTest`). Las imágenes
de la vista previa del widget, con `--tests *WidgetRender* -Ppreview` (§16).

---

## 16. Widget y botón de los ajustes rápidos (2026-10-06)

**Qué hay** (desde Android 8, `@bool/hql_widget_enabled`):

- **Widget «HeadQLink»**: 4x2 por defecto y redimensionable hasta 2x2. El botón grande (Conectar / Desconectar), el
  estado, el selector de conexión («Zona Wi-Fi», «Wi-Fi Direct», «Cable USB») y, en el 4x2 con altura, el modo («Auto» /
  «Extendido»). En 2x2, el botón, el estado y el icono de la conexión (al tocarlo, la siguiente). Colores: gris
  apagado; ámbar en curso (preparando, buscando, coche encontrado, sesión sin imagen, esperando a que vuelva y, con el
  arranque manual, «Esperando al servidor de Android Auto», §15); verde con imagen, con fps y Mbit/s; rojo con un
  problema (puerto 18463, red, Android Auto). La marca abre la app sin conectar.
- **Botón «HeadQLink» de los ajustes rápidos**: tocarlo, Conectar / Desconectar; mantenerlo pulsado, la app
  (`QS_TILE_PREFERENCES`, sin conectar sola). Activo con el enlace en marcha; el subtítulo dice el estado o las cifras.
- **Menú ⚙**: «Añadir widget a la pantalla de inicio» (`requestPinAppWidget`; si el launcher no lo admite, cómo hacerlo
  a mano) y, con Android 13+, «Añadir botón a los ajustes rápidos» (`requestAddTileService`).

**Conectar desde fuera de la app (Android 12+).** El botón grande y el de los ajustes rápidos abren un puente invisible
(`QuickToggleActivity`: transparente, en su propia tarea, fuera de recientes y no exportado) con un `PendingIntent` de
actividad inmutable. Con él delante, Android deja arrancar el servicio en primer plano sin depender de ninguna exención,
y se puede abrir la «Comprobación». Hace lo mismo que el botón de la app: esa lógica sale de `HomeActivity` a
`LinkControl` (requisitos, «Conectar igualmente», servidor de Android Auto con «Preparando…», que ahora es
`LinkState.preparing` y lo ve también el widget). Desconectar en marcha es un `startService` al servicio, que ya está en
primer plano. En los ajustes rápidos, `startActivityAndCollapse` con `PendingIntent` en Android 14+ (con `Intent`
antes); con el móvil bloqueado, Conectar pide desbloquearlo (`unlockAndRun`). Los toques del selector y del modo son
difusiones a `LinkWidget`, que no está exportado: ninguna otra app puede cambiar la conexión.

**Cambiar de conexión en marcha.** El widget guarda `link_mode` y manda `LinkService.ACTION_SET_LINK` → `switchLink`:
el mismo camino que el cable USB (`startUsb`, o `stopWifi` + `startWifi`), sin reiniciar Android Auto. Con una sesión
QDAuto, «coche perdido» con el vídeo vivo (`car_gone_ms`) hasta que el coche vuelve por la conexión nueva. Con el cable
del coche puesto y en uso, el cable sigue teniendo prioridad: la conexión elegida es a la que se vuelve al quitarlo. El
motor no cambia (el del arranque). El modo en marcha se aplica con `ACTION_APPLY` y renegociando Android Auto, como un
cambio de perfil. Desde «Cambiar» en la app, la conexión se sigue aplicando al volver a conectar.

**Actualizaciones** (`WidgetUpdater`). Escucha `LinkState` y los ajustes (modo y conexión). Agrupa los avisos de 250 ms,
no repinta si no cambia lo que se ve (`LinkGlance.signature`) y las cifras del vídeo (llegan cada 5 s) como mucho cada
4,5 s. Sin widgets no hace nada; sin sondeos (`updatePeriodMillis` 0) ni candados. Al arrancar el proceso corrige el
widget si se quedó en otro estado (el proceso murió en marcha). Android 12+: cuatro versiones por tamaño en un solo
`RemoteViews` (2x2 bajo o alto, 4x2 bajo o alto; umbrales 260 × 175 dp, los de `LinkGlance.sizeFor`); antes, la que
cabe según `OPTION_APPWIDGET_*`. Esquinas del sistema, `targetCellWidth/Height` y `description`. Vista previa del
selector de widgets y del diálogo «¿Añadir a la pantalla de inicio?»: `previewImage` es la imagen del widget de verdad
apagado a 4x2 (`hql_widget_preview`, 1020 × 540, en los cuatro idiomas) y `previewLayout` (`hql_widget_preview.xml`)
la enseña encajada entera (`fitCenter`) en el hueco que dé el launcher. Antes `previewLayout` era la disposición de
verdad, y el diálogo de Samsung «¿Quieres añadirlo a la pantalla Inicio?», con un hueco más bajo que un 4x2, la
recortaba a la cabecera (2026-10-06).

**Código.** `[hql]LinkGlance.java` (puro: del estado al color, botón, estado, detalle, tamaño y cuándo repintar),
`LinkWidgetViews` (RemoteViews, textos y `PendingIntent`), `WidgetUpdater`, `LinkWidget`, `QuickToggleActivity`,
`LinkControl`, `LinkTileService` y `WidgetShots` (capturas). Cambian `LinkService` (`ACTION_SET_LINK`), `HomeActivity`
(`LinkControl`, el menú, sin conectar sola desde el widget o el botón de los ajustes rápidos), `LinkState.preparing`,
`Config.listen`, `App` (arranca `WidgetUpdater` tras el primer desbloqueo) y `PreviewActivity` (`render=widget`).
Recursos `hql_widget_*` y `hql_w_*`, textos en los cuatro idiomas y manuales («Widget y botón de ajustes rápidos»).

**Vista previa.**

- En el PC: `cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest --tests *WidgetRender* -Ppreview"` deja
  `app/build/preview/widget_<estado>_<tamaño>.png` (apagado, buscando, conectado_wifi, conectado_usb, esperando y
  problema; 4x2 y 2x2), con las mismas `RemoteViews` que pinta el launcher, y `hql_widget_preview_<idioma>.png` (en,
  es, pt-PT, pt-BR: el 4x2 apagado, 3x, sin fondo; `WidgetShots.renderPreview`), que se copian a
  `res/drawable-nodpi` (inglés) y `res/drawable-<idioma>-nodpi/hql_widget_preview.png`.
- En el móvil: `adb shell am start -n com.headqlink.app/com.headqlink.link.PreviewActivity --es render widget` y
  `adb pull /sdcard/Android/data/com.headqlink.app/files/preview/`.

**Cómo comprobarlo en el móvil.**

1. ⚙ › «Añadir widget a la pantalla de inicio»: el diálogo del launcher (en Samsung, «¿Quieres añadirlo a la pantalla
   Inicio?») enseña el widget entero, no solo la cabecera; el launcher lo coloca; «Apagado» en gris. Redimensionarlo a
   2x2.
2. Botón grande con todo listo: «Preparando…» (si hay que arrancar el servidor de AA), ámbar «Buscando el coche…» con la
   fila «Red» debajo y verde «Coche conectado» con las cifras cada 5 s. Con algo obligatorio pendiente, la «Comprobación».
3. En marcha, tocar otra conexión: en el log, `widget: conexión … (enlace en marcha: se aplica ya)` y `conexión: … → …
   (widget, con el enlace en marcha)`; la imagen vuelve por la nueva sin `AA: lanzando Self-Mode`.
4. Ajustes rápidos › lápiz › «HeadQLink» (o ⚙ › «Añadir botón…»): activo en marcha, con las cifras; mantenerlo pulsado
   abre la app sin conectar.
5. Con QDLink abierto: rojo, «Cierra QDLink».

**Pruebas en el PC.** `LinkGlanceTest` (13, hoy 14 con el de §15: apagado, preparando, buscando, coche encontrado por Wi-Fi o por cable, verde
con fps y Mbit/s y su formato por idioma, sesión sin imagen, esperando, problemas, un aviso viejo de la red no tapa el
verde, el selector con el cable por delante, el ciclo del 2x2, los tamaños y cuándo repintar). `LinkWidgetViewsTest` (6,
Robolectric: las `RemoteViews` aplicadas como lo hace el launcher, en cada estado y tamaño: textos, colores,
descripciones de accesibilidad y toques; y la vista previa: el proveedor apunta a `hql_widget_preview`, la imagen tiene
la proporción del 4x2 y queda entera dentro de un hueco bajo como el del diálogo de Samsung, del 4x2 y de uno alto). `LinkWidgetActionsTest` (6: conexión y modo guardados y, en marcha, aplicados
con `ACTION_SET_LINK` y `ACTION_APPLY`; el botón grande desconecta en marcha y, sin configurar, abre la app; el botón de
los ajustes rápidos: activo, subtítulo y Desconectar).

**Limitaciones.** Sin probar en el móvil. Pasar de Wi-Fi Direct a la zona Wi-Fi en marcha no sale del grupo P2P del
coche (igual que al pasar al cable). En el 2x2 más pequeño (110 dp) el botón queda pequeño.

**Resultados en el PC.** `cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`:
**BUILD SUCCESSFUL**. App: 2904 pruebas, 0 fallos y 3 saltadas (la de `sh` y las dos de dibujo, que piden `-Ppreview`);
el paquete `com.headqlink.link`, 246.

---

## 17. Datos reales del coche: cuenta Leapmotor, solo lectura (2026-10-06)

**Qué hay.** Opcional. Con la cuenta de Leapmotor del usuario, HeadQLink lee el estado del coche de la nube
internacional de Leapmotor (`appgateway.leapmotor-international.de`) y lo enseña en el modo extendido. Todo va dentro de
HeadQLink: no hace falta LMB10 ni se lee nada suyo. Su código (GPL-3.0) es la referencia del protocolo; lo portado
lleva su cabecera y está en `NOTICE`.

**Portado de LMB10 (`lib/leapmotor_engine.dart`).**

| Qué | Dónde |
|---|---|
| Firma del login (SHA-256 de los campos en orden fijo); cabeceras HMAC-SHA256 con la clave HKDF de signIkm/signSalt/signInfo; nonce, timestamp y deviceId del token | `LeapCrypto` |
| Contraseña del PKCS#12 de cuenta (MD5, SHA-256 y SM4 con las claves de ronda fijas del protocolo) | `LeapCrypto` |
| `login`, `restoreSession`/`exportSession`, `tokenRefresh`, `withTokenRetry` (un refresco y un reintento si el error habla del token o hay 401), `_parseBody`, `getVehicleList`, `getVehicleStatus` y `statusPath` (B10/B11 → c10) | `LeapApi` |
| `kSignalToNamed` (el subconjunto que se enseña), `mergeSignalToNamed`, `_asInt`/`_asDouble`/`_asBool`, `isPluggedIn`, `isCharging`, `batteryPowerKw`, avisos de presión (estado > 1) y la hora del dato (`collectTime`… y `signal.sts`) | `LeapStatus` |
| Capacidades del C10 (Life 69,9 kWh, ProMax 81,9 kWh) y consumo creíble (8-70 % cada 100 km) de `vehicle_profile.dart` y `daily_stats.dart` | `CarCloudStore`, `CloudEnergy` |

**No portado, a propósito:** ninguna orden al coche (cerrar o abrir, maletero, clima y precondicionado, asientos y
volante, ventanillas, persiana, centinela, límites de carga y de velocidad, cargador, `cert/sync`), ni el cifrado del PIN,
ni mensajes, historial de cargas, rutinas o la posición del coche. `LeapApi` solo tiene login, refresco, lista y
estado, y el transporte rechaza cualquier ruta que no sea de esas cuatro (`LeapApi.allowed`, con su prueba).

**TLS mutuo**, como la app oficial: el certificado de cliente del usuario para el login y el PKCS#12 de cuenta que llega
en el login para lo demás. El servidor presenta un certificado de la CA privada de Leapmotor («AppSubCA», sin la
cadena). LMB10 acepta cualquier certificado; aquí se fija la clave pública del servidor (`LeapTls.BUILT_IN_PINS`, SPKI
SHA-256 del certificado vigente de 2026-07 a 2027-08), además de la comprobación de nombre de Android. Si la clave
cambia, la conexión se para (`SERVER_KEY`) y el móvil enseña la huella para aceptarla o no (`acceptPin`); nunca sola.

**Certificado de cliente** (`LeapTls.parse`): el par PEM `.crt` + `.key` (a la vez o uno detrás de otro: §21.1), un PEM con
los dos bloques (también con texto alrededor y con la cadena), claves PKCS#8, PKCS#1 (`RSA PRIVATE KEY`), SEC1
(`EC PRIVATE KEY`) y PKCS#8 cifrada (si el Android conoce el algoritmo), certificados y claves en DER, y `.p12`/`.pfx`
con contraseña (la pide si falta o es mala). Comprueba que la clave es la del certificado con una firma de prueba.

**Almacén** (`CarCloudStore`): el certificado (clave PKCS#8 y cadena) y la sesión (tokens, material de firma, PKCS#12 de
cuenta, VIN y correo), cifrados con AES-256-GCM y una clave del Android Keystore sin autenticación de usuario (el sondeo
funciona con el móvil bloqueado), en `noBackupFilesDir/carcloud`. En claro (`hql_carcloud`): activado, perfil de batería,
modelo, correo enmascarado y claves de servidor aceptadas. La contraseña nunca se guarda. Necesita Android 6 o superior.
Si la clave del Keystore ya no abre lo guardado, cuenta como «sin configurar».

**Sondeo** (`CarCloud`): arranca y para con `CarUi` (el modo extendido con el coche, también en el «coche perdido» con el
vídeo vivo, y la vista previa); lee cada 60 s, cada 30 s con la sección Coche en pantalla y, tras errores, a los 2, 5 y
10 min (`Policy`). Con la sesión caducada, una clave de servidor nueva o sin configurar, no vuelve a intentarlo hasta que
cambie algo en el móvil. Todo el acceso a la API pasa por `CarCloudSession` (sincronizado: el sondeo y «Leer estado
ahora» no refrescan a la vez el mismo token), que vuelve a guardar la sesión cuando se refresca el token. Las pantallas
leen `CarCloud.snapshot()`, una foto inmutable con la hora del dato del coche y la de la lectura.

**Pantallas.**

- Móvil: ⚙ › «Datos del coche (cuenta Leapmotor)» (`CarCloudActivity`): explicación, activado, 1 certificado, 2 cuenta,
  3 coche, 4 batería (la variante no se puede deducir del VIN ni del modelo: se pregunta tras entrar), «Leer estado
  ahora» y «Cerrar sesión y borrar datos».
- Coche: pestaña nueva «Estado» (`CarStatusTab`); «Ruta» con el % real (sin ±5) y la capacidad de la variante;
  «Eficiencia» con el consumo real del viaje (desde un 2 % de bajada) y la potencia real si el dato tiene 2 min o menos;
  «Viajes» con el consumo real de cada viaje (marcado «REAL») y los totales reales. Siempre «real · hace N» o
  «estimado».
- `TripLog` apunta la primera y la última lectura de la nube hechas después de empezar el viaje (% y km); si se ve
  cargando o el % sube más de un punto, ese viaje no da consumo real (`cloud` en el JSON del viaje).

**Qué buscar en el log.**

| Línea | Significado |
|---|---|
| `nube Leapmotor: sondeo en marcha (cada 60 s; …)` y `sondeo parado` | Arranca y para con el modo extendido |
| `nube Leapmotor: SoC 72.4 %, autonomía 301 km, sin enchufar, 7.9 kW, 12480 km · dato del coche de hace 40 s · 820 ms` | Una lectura |
| `W nube Leapmotor: sin datos (…); fallo 1, reintento en 2 min` | Error: espera 2, 5 o 10 min |
| `W nube Leapmotor: la sesión caducó (…); vuelve a entrar en el móvil (Datos del coche)` | Hay que volver a entrar |
| `W nube Leapmotor: el servidor presenta otra clave (huella …)` | Clave del servidor nueva |
| `nube Leapmotor: sesión iniciada (c***@e***.com), 1 coche(s), modelo C10` | Login (correo enmascarado) |
| `nube Leapmotor: prueba desde el móvil: …` | «Leer estado ahora» |
| `viaje: inicio con datos del coche (84.0 %)` y `viaje: datos del coche 84.0 → 79.5 %, 15 km de cuentakilómetros, 20.9 kWh/100 km reales` | Viaje con datos reales |

**Cómo comprobarlo en el móvil.**

1. ⚙ › «Datos del coche»: importar el par (a la vez o uno detrás de otro), entrar y elegir la variante.
2. «Leer estado ahora»: el % y los km tienen que coincidir con los de la app de Leapmotor.
3. Diagnóstico › «Vista previa del modo extendido» › Coche › Estado: los datos reales con «real · hace …» (sin cuenta,
   los inventados).
4. En el coche: una línea `nube Leapmotor: SoC …` por minuto (cada 30 s con la sección Coche en pantalla).

**Vista previa.** `DemoMode.cloudSnapshot()` (inventada; pasa por `LeapStatus.parse` con las señales numéricas) y
viajes con datos reales; estados `sin_nube` y `cargando`. Capturas nuevas: `coche_estado`, `coche_estado_cargando` y
`coche_estado_sin_cuenta`. Con los datos de la nube:
`gradlew :app:testGithubDebugUnitTest --tests *CarPanelRender* -Ppreview -PpreviewSuffix=_cloud -PpreviewOnly=coche_estado,coche_ruta,coche_eficiencia,coche_viajes`.

**Pruebas en el PC** (las claves y certificados se generan al vuelo; ninguno real): `LeapCryptoTest` (9: SM4 con el
vector oficial y con las claves fijas, contraseñas del PKCS#12, HKDF con el RFC 5869, firmas del login, la lista, el
estado y el refresco con vectores de un port literal en Python del Dart, deviceId del token, codificación de Dart),
`LeapStatusTest` (9), `LeapTlsTest` (10, con un handshake TLS mutuo real contra un servidor local), `LeapApiTest` (9, con
un HTTP de mentira: login, certificado de cuenta, HMAC, refresco y reintento, refresco rechazado, errores y rutas de solo
lectura), `CloudEnergyTest` (4), `TripCloudTest` (3) y `CarCloudTest` (7: sondeo y espera, edad del dato, almacén
cifrado).

**Limitaciones.** Sin probar contra la nube de verdad ni en el móvil (aquí no hay credenciales, a propósito). La API no
es oficial. El cuentakilómetros va en km enteros y la nube no es en tiempo real (el TCU duerme ~13 min después de cerrar
el coche). La nube no da las ventanillas. El C10 REEV no vale para el consumo real. Si Leapmotor cambia la clave de su
servidor, hay que aceptarla una vez en el móvil.

**Resultados en el PC.** `cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`:
**BUILD SUCCESSFUL**. App: 2978 pruebas, 0 fallos y 4 saltadas (la de `sh` y las tres de dibujo, que piden
`-Ppreview`); el paquete `com.headqlink.link`, 320 (60 nuevas).

## 18. Radio saturada: suelo de emergencia y diagnóstico de la zona Wi-Fi (2026-10-07)

**Informe (C10, 2026-10-07, 08:51-09:13, zona Wi-Fi).** El peor viaje por Wi-Fi hasta ahora, con el estado térmico a 0
todo el rato: 7 sesiones, 127 cortes de radio («Corte … RADIO»: TX atascado más de 400 ms con el coche hablando, rtt de
100-200 ms, cwnd hasta 1), 9-20 fps y cientos de frames descartados. En S26 (perfil Coche) el control del enlace (§12.1)
estaba en su suelo (`bitrate_min_kbps` 2540 = 50 % de los 5,08 Mbit/s del coche, 22 congestiones) y a 24 fps, y aun así
hubo 45 cortes y 10,5 fps: tras «no hay más que bajar» no hacía nada más. El suelo del 50 % (viaje 6) evita pixelar en
un enlace bueno, pero en uno malo sigue saturando la radio. Además, en S26 salen P-frames de 250-270 KB a 2,5 Mbit/s
(cada uno ocupa casi un segundo de enlace): queda pendiente mirar por qué el VBR los produce (§19).

### 18.1 Suelo de emergencia (`LinkRateController`)

| Regla | Detalle |
|---|---|
| **Suelo normal** | Sin cambios: max(1,5 Mbit/s, 50 % del bitrate del coche); 24 fps si en él sigue la congestión. |
| **Entrar** | Ya en el suelo normal y a ≤ 24 fps, con congestión durante ≥ **3 s** (`EMERGENCY_AFTER_MS`) y al menos **3** muestras congestionadas separadas 500 ms (`EMERGENCY_SAMPLES`; un golpe suelto no basta). Los huecos limpios de menos de 3 s no reinician la cuenta; una recuperación (3 s limpios: vuelven los fps) sí. |
| **Bajar** | ×0,75 cada 500 ms hasta **1,2 Mbit/s** (`EMERGENCY_FLOOR_BPS`), luego **20 fps** (`EMERGENCY_FPS`), luego una línea «no hay más que bajar» y silencio. |
| **Volver** | Como siempre: 3 s limpios → los fps de la sesión; luego +25 % cada 3 s. Al pasar del suelo normal, «fuera de la emergencia». |
| **Rearmar** | Una sola vez por episodio: solo tras **30 s** limpios seguidos con el bitrate en el suelo normal o por encima (`EMERGENCY_REARM_MS`; cualquier muestra congestionada reinicia la cuenta), y en cada sesión nueva. |
| **Enlace bueno** | Nunca llega: desde el techo hacen falta unos 5,5 s de congestión sostenida. Las pruebas de siempre pasan sin cambios salvo «Fluidez 60», que ahora mira el suelo normal a los 5 s (a los 5,1 s entraría la emergencia). |

Interpretación del «≥ 3 s (o ≥ 3 muestras separadas STEP_HOLD_MS)»: se exigen las dos cosas. Con solo la segunda, la
emergencia entraría 1 s después del suelo (3 muestras a 500 ms), y la de 3 s nunca contaría.

**Cortes de radio al controlador.** Antes no llegaban: `QdSessionBridge.checkStalls` mandaba a `VideoHub.onLinkSample`
solo NetStat y los vaciados. Ahora el INICIO de un corte RADIO del `StallDetector` marca la muestra
(`Sample.radioCut`, `withRadioCut()`; `with()` la conserva) y cuenta como congestión en el acto («corte de radio»; por
el cable, «corte del cable»), con la misma espera de 500 ms entre pasos.

### 18.2 Radio de la zona Wi-Fi (`HotspotRadio`)

Con la conexión «Zona Wi-Fi», `HotspotWatcher` arranca `HotspotRadio` en su hilo `hql-net`, que escucha
`WifiManager.SoftApCallback` (Android 11+) y registra una vez por cambio: frecuencia → banda y canal, ancho de canal,
estándar Wi-Fi (Android 12+), clientes (Android 12+, por instancia y con la MAC recortada a los dos últimos bytes) y
desconexiones (Android 13+, con el motivo 802.11 en Android 14+). Con 2,4 GHz, un aviso.

**No hay API pública:** `SoftApCallback`, `SoftApInfo` y `WifiClient` son API de sistema (no están en el `android.jar`
del SDK 36), así que va por reflexión y un `Proxy`, protegido por SDK y con todo en `try`. En un móvil normal lo más
probable es que Android lo niegue (`SecurityException`: pide NETWORK_SETTINGS, que solo tienen Ajustes y el sistema; o
el método oculto bloqueado): se dice una vez y la banda queda «desconocida». El RSSI y la velocidad del coche como
cliente no se pueden leer sin permisos de sistema: también se dice una vez; cada 30 s de sesión se registra lo que se
sepa (banda y clientes). El TCP con el coche (rtt, cwnd, retrans) sigue en las estadísticas de 5 s.

**Resumen y `sessions.csv`.** En la línea `coche … · local … swlan0 (zona Wi-Fi)` del bloque, `· banda Wi-Fi 5 GHz
(canal 36 · 80 MHz · Wi-Fi 6 (802.11ax))` (o `desconocida`; nada con Wi-Fi Direct o cable). Columna nueva al final:
**`banda_wifi`** (`5 GHz`, `2.4 GHz`, `6 GHz`, `desconocida`, vacía si no es la zona Wi-Fi). En el resumen del viaje,
`banda Wi-Fi: 5 GHz 3 · desconocida 1`.

### 18.3 Qué buscar en el log

| Línea | Significado |
|---|---|
| `VIDEO bitrate adaptable al enlace 2.5 Mbit/s-5.1 Mbit/s (… 24 fps si en el suelo sigue; 3 s más así: emergencia hasta 1.2 Mbit/s y 20 fps)` | Controlador activo |
| `W enlace: congestión (corte de radio) → bitrate 3.8 Mbit/s` | Un corte de radio baja el bitrate en el acto |
| `W enlace muy congestionado: bajo del suelo normal (2.5 Mbit/s) tras 3.0 s de congestión en él a 24 fps (outq 96 KB 5100 ms) → bitrate 1.9 Mbit/s` | Entra la emergencia |
| `W enlace muy congestionado (rtt 210 ms (mín. 14) 600 ms) → bitrate 1.2 Mbit/s (suelo de emergencia)` / `… con el bitrate en el suelo de emergencia (1.2 Mbit/s) → 20 fps` / `… y 20 fps: no hay más que bajar` | Pasos de la emergencia |
| `enlace: enlace limpio 3 s → bitrate 2.9 Mbit/s · fuera de la emergencia (suelo normal 2.5 Mbit/s)` | Vuelve al suelo normal |
| `enlace: emergencia rearmada (30 s limpio con 5.1 Mbit/s)` | Se puede volver a usar |
| `enlace: bitrate 1.2 Mbit/s (mín. 1.2 Mbit/s, techo 5.1 Mbit/s) · 20 fps · congestiones 10 (bajadas 6, subidas 0) · emergencia (suelo normal 2.5 Mbit/s)` | Estadísticas de 5 s (`· emergencias N` cuando ya salió) |
| `HQL/Red: radio de la zona Wi-Fi: escuchando los cambios (banda, canal, ancho, estándar y clientes)` | Android deja leerla |
| `HQL/Red: radio de la zona Wi-Fi: no se puede leer (Android pide un permiso de sistema: …): ni banda, ni canal, ni clientes, ni la señal del coche. Compruébala en Ajustes › Zona Wi-Fi › Banda (mejor 5 GHz)` | No deja (una vez por arranque de la zona Wi-Fi) |
| `HQL/Red: radio de la zona Wi-Fi: 5 GHz canal 36 (5180 MHz) · 80 MHz · Wi-Fi 6 (802.11ax)` | Banda y canal (en cada cambio) |
| `HQL/Red: radio de la zona Wi-Fi: 1 cliente (…:3f:a1 en 5 GHz canal 36)` / `… cliente …:3f:a1 desconectado de 5 GHz canal 36 (5180 MHz) (motivo 802.11 3)` | Clientes |
| `W la zona Wi-Fi va en 2,4 GHz: más lenta y con más cortes; ponla en 5 GHz (Ajustes › Zona Wi-Fi › Banda)` | Aviso |
| `HQL/Red: radio del coche S27: zona Wi-Fi 5 GHz canal 36 (5180 MHz) · 80 MHz · Wi-Fi 6 (802.11ax) · 1 cliente` | Al empezar la sesión y cada 30 s (solo si se puede leer) |
| `HQL/Red: radio del coche: Android no da a una app el RSSI ni la velocidad de cada cliente de la zona Wi-Fi …` | Una vez |

### 18.4 Resultados en el PC

`cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`: **BUILD SUCCESSFUL**.
App: 2989 pruebas, 0 fallos y 4 saltadas. Nuevas: 6 en `LinkRateControllerTest` (emergencia hasta 1,2 Mbit/s y 20 fps;
huecos cortos frente a recuperación; corte de radio como congestión; rearme solo tras 30 s limpios en el suelo normal;
una congestión reinicia los 30 s; sesión nueva rearma), `HotspotRadioTest` (4: banda y canal en 2,4/5/6/60 GHz, textos,
bandas a la vez y MAC recortada, sin zona Wi-Fi no hay banda) y una en `SessionSummaryTest` (banda en el bloque, el CSV
y el viaje). Sin probar todavía en el móvil ni en el coche.

## 19. P-frames grandes y radio floja en casa (2026-10-07)

**Informe (C10, 2026-10-07, S26 09:03-09:06, perfil Coche, VBR, el enlace ya en 2,5 Mbit/s).** Muchos P-frames de
160-270 KB («write de VIDEO_P 263150 B»). A 2,5 Mbit/s y 30 fps un P-frame medio son ~10 KB: cada uno es una ráfaga de
25 veces lo normal que ocupa casi un segundo de radio, y coinciden con los «Corte … RADIO» (TX atascado > 400 ms). En
la misma sesión los IDR salían de 92-106 KB: los P-frames grandes pesan más que un IDR entero. Hipótesis (sin
comprobar): el VBR del c2.qti gasta en un frame el presupuesto acumulado mientras la puerta «último frame» no dejaba
codificar (la marca de tiempo es la hora de dibujo); con el QP-P mínimo eso queda acotado igualmente.

### 19.1 Tamaño de los P-frames (`PFrameSizeController`)

Clase pura en `[hql]PFrameSizeController.java` (reloj inyectable), alimentada desde `VideoPipeline.onEncodedFrame` con
cada frame que no es clave, solo con sesión enganchada:

| Regla | Detalle |
|---|---|
| **Tope** | max(**24 KB**, **6** × bitrate/fps), con el bitrate pedido al encoder (el del enlace o el térmico, sin la bajada de un IDR) y los fps de la sesión (con el tope térmico o del enlace). 2,5 Mbit/s a 30 fps → 61 KB; 5,08 Mbit/s a 30 → 124 KB; 8 Mbit/s a 60 → 98 KB (a 30 fps serían 195 KB). |
| **Enlace congestionado** | Si el control del enlace vio congestión en una muestra (`LinkRateController.congestedNow`, aunque no tocara el bitrate por la espera entre pasos) o empezó un corte de radio, durante **2 s**: max(24 KB, **3** × bitrate/fps) (2,5 Mbit/s a 30 → 31 KB). Los cortes de radio cuentan también sin controlador del enlace (Muy alto/Alto, `link_fixed`). |
| **Subir** | Un P-frame por encima del tope sube el QP mínimo de los P-frames (`KEY_VIDEO_QP_P_MIN`) en **+2**, como mucho una vez cada **500 ms**, hasta **40**. Desde «sin mínimo» la primera subida va a **26** (base 24, como el QP-I de partida). |
| **Bajar** | **5 s** sin ningún P-frame por encima del tope (ni otro cambio) lo bajan **1**; por debajo de 26 vuelve a «sin mínimo» (QP-P 1-51, lo que elija el encoder). |
| **Plan B** | Bajada del bitrate al **60 %** durante **1 s** (`VideoEncoder.dipForPFrames`), como la del IDR: siempre si no hay claves de QP (Android < 12 o rechazadas al configurar); desde que el encoder rechace el QP-P en marcha; desde que, con el mínimo ya **+8** sobre el de la primera subida, un P-frame por encima del tope siga pesando ≥ 90 % del que la provocó (el encoder no parece hacer caso: cada subida baja además el bitrate); y con el mínimo ya en 40. |
| **Sesión nueva** | Estadísticas a cero; el QP-P sigue (es del encoder, que sigue vivo) y baja solo. |

**Sin pelearse con lo demás.**
- *IDR*: aquí solo llegan los P-frames; `IdrSizeController` sigue igual. En Codec2 las claves de QP se traducen a un
  único parámetro con todos los tipos de frame, así que `VideoEncoder.setQpRange` manda **siempre los dos rangos (I y
  P) juntos**: subir el QP-P no deja el QP-I sin rango ni al revés. Las dos bajadas de bitrate se combinan (manda la
  mayor) y `setBitrate` (enlace, térmico) las respeta.
- *Intra-refresh*: la franja intra va en los P-frames; un tope de 6 frames medios le deja sitio (con 30 frames de
  periodo, la franja es 1/30 de la imagen).
- *Baseline/VBR/CBR*: el QP-P vale en los tres; el tope sale del bitrate pedido, no del modo. Con CBR el encoder ya
  apenas da ráfagas: el controlador casi no actuará.
- *Configuración*: se prueba primero con QP-I + QP-P (P a 1-51, sin mínimo propio), luego solo QP-I, luego nada; con
  y sin baja latencia, como antes. Si el encoder rechaza el QP-P al configurar: plan B desde el principio.

### 19.2 ¿Hace caso el c2.qti.avc.encoder al QP-P en marcha?

No se puede saber sin el móvil. Lo que se sabe:
- En Android 12+ `setParameters` pasa las claves de QP por el mismo camino de `CCodecConfig` que `configure`, así que
  llegan al componente; aplicarlas con el encoder en marcha depende del componente de Qualcomm.
- En los logs del coche el QP-I solo ha **bajado** en marcha (24 → 18, con IDR muy por debajo del objetivo), sin
  errores de `setParameters` («no aceptó el QP-I nuevo» no ha salido nunca); nunca ha hecho falta subirlo, así que no
  hay prueba de que una subida en marcha se respete.
- Para verlo en el próximo viaje: tras «QP-P mín 26 → 28 …» los P-frames siguientes deben bajar de tamaño (en
  `perf/*.csv`: `p_kb`, `p_qp_min`). Si no, sale `… el encoder no parece respetarlo; desde ahora cada subida baja además
  el bitrate` y el plan B actúa solo. Además, `encoder: parámetros de fabricante útiles: …` ahora lista también los
  que contienen «qp» o «rate» (por si el c2.qti declara su propio rango de QP de fabricante).
- **Respuesta (§23): no.** Los cambios de QP en marcha no hacen nada en el c2.qti; el de configure, sí. La causa
  principal de los P-frames grandes era el P-frame que sigue a cada IDR, y el arreglo es un suelo de QP-P al configurar.

### 19.3 Qué buscar en el log

| Línea | Significado |
|---|---|
| `encoder c2.qti.avc.encoder 1920x882@30 5080kbps baseline VBR … qp-i=24-51 qp-p=1-51` | Parámetros pedidos |
| `encoder: QP-I 24-51 al configurar · QP-P 1-51 (sin mínimo)` | El encoder aceptó las dos claves. `· sin QP-P (rechazado)`: plan B |
| `VIDEO tamaño de los P-frames: tope max(24 KB, 6 × bitrate/fps), 3 × con el enlace congestionado · QP-P mínimo adaptable (+2 por P-frame grande cada 500 ms, desde 26 hasta 40; -1 tras 5 s limpios)` | Controlador activo |
| `VIDEO P-frames: 257 KB > tope 61 KB → QP-P mín 26` | Subida |
| `VIDEO P-frames: 40 KB > tope 31 KB (enlace congestionado) → QP-P mín 28` | Subida con el tope estrecho |
| `VIDEO P-frames: 5 s sin pasar del tope (61 KB) → QP-P mín 27` / `… → QP-P sin mínimo (el del encoder)` | Bajada |
| `W VIDEO P-frames: 260 KB > tope 61 KB → QP-P mín 34 y bitrate al 60 % 1000 ms · con el QP-P mínimo 8 más alto los P-frames siguen igual de grandes …` | El encoder no parece hacer caso: plan B |
| `W VIDEO P-frames: … · el encoder no aceptó el QP-P en marcha: …` | `setParameters` rechazado: plan B |
| `  P-frames: máx. 257 KB (tope 61 KB) · por encima del tope 3 · QP-P mín 28` | Estadísticas de 5 s (solo si hubo P-frames grandes o hay mínimo) |
| `VIDEO P-frames 5400 · máx. 257 KB · por encima del tope 12 · QP-P mín 26 (máx. 30, subidas 3, bajadas 2)` | Al parar el vídeo |
| `vídeo: … · P-frame máx. 257 KB (por encima del tope 12)` | Resumen de la sesión. En `sessions.csv`, columnas nuevas al final: `p_max_kb` y `p_sobre_tope`. En el resumen del viaje: `P-frames: máx. … · por encima del tope …` |

### 19.4 Radio floja en casa (`qdsim` y `carsim`)

El coche simulado lee el TCP a como mucho N kbit/s (cubo de fichas sobre sus lecturas: `RxThrottle`, en `:qdcore` y
en `qdauto/core`) y, si se pide, deja de leer del todo a ratos. Con algún límite, su búfer de recepción se deja en
**32 KiB** (antes de conectar), para que la ventana TCP se cierre y la cola de envío del móvil se llene como con la
radio del C10 saturada: cola del kernel alta, `write()` lento, cortes «RADIO» en el detector y el control del enlace
bajando (y, si sigue, el suelo de emergencia de §18).

| Opción | Qué hace |
|---|---|
| `--rx-kbps N` | Lectura a como mucho N kbit/s |
| `--rx-stall every=20s,for=1500ms` | Parón completo de la lectura de `for` cada `every` (el primero a los `every` de conectar) |
| `qdsim --scenario radio-mala` | Como `normal` (toques, `KEY_FRAME_REQ`, modo noche), con `--rx-kbps 1500` y `--rx-stall every=15s,for=1200ms`, 120 s. PASS si la sesión aguanta. Las tres cosas se pueden cambiar. |

Al acabar cada sesión con límite, qdsim saca tres líneas `radio:`: frames recibidos y fps (media, peor segundo,
segundos con menos de la mitad), peor hueco, kbit/s, P-frames (máximo, medio, cuántos de más de 6 medios) y lo que
hizo el límite (KB leídos, tiempo al límite, parones). La congestión que vio el móvil no viaja en el vídeo: está en su
log (`enlace: congestión …`, `enlace muy congestionado`, `VIDEO P-frames: …`, `Corte … RADIO`). carsim lo saca en la
sección «Radio floja» del informe (y `radioFloja` en el JSON), con `P-frames: máximo …` en la de vídeo.

```
qdsim\build\install\qdsim\bin\qdsim.bat --scenario radio-mala --target <IP del móvil>
qdsim\build\install\qdsim\bin\qdsim.bat --scenario normal --duration 120 --rx-kbps 1000 --rx-stall every=20s,for=1500ms
carsim\build\install\carsim\bin\carsim.bat --target <IP del móvil> --duration 120 --rx-kbps 1500 --rx-stall every=15s,for=1200ms --report radio.json
```

Con `--local-phone` (`qdsim --scenario radio-mala --local-phone --duration 20 --rx-stall every=8s,for=1200ms`) pasa:
587 frames, peor hueco 1199 ms (el parón), 2 parones, 30 % del tiempo al límite (el móvil de prueba manda ~1,1
Mbit/s).

### 19.5 Resultados en el PC

`cmd /c ".\gradlew.bat :qdcore:test :qdsim:installDist :app:testGithubDebugUnitTest :app:assembleGithubDebug"`:
**BUILD SUCCESSFUL**. `:qdcore` 162/162 (nuevo `RxThrottleTest`, 6: formato de `--rx-stall`, ventanas de parón, tasa
media del cubo en 10 s, parón que bloquea, el flujo nunca pide más de lo que deja el cubo, textos). `:qdsim`: nuevo
`RadioTest` (2: opciones y valores de `radio-mala`; resumen del vídeo recibido). App: 3001 pruebas, 0 fallos y 4
saltadas. Nuevas: `PFrameSizeControllerTest` (10: tope y suelo de 24 KB, P-frames normales con la franja intra, subida
a 26 y +2 cada 500 ms, techo 40 y bajada del bitrate, bajada de 1 cada 5 s hasta «sin mínimo», tope estrecho 2 s tras
una congestión, sin claves de QP, encoder que no hace caso, QP rechazado, sesión nueva), una en
`LinkRateControllerTest` (`congestedNow` durante la espera entre pasos) y una en `SessionSummaryTest` (P-frame máximo
en el bloque, el CSV y el viaje). En `qdauto`: `:core:test :carsim:test :carsim:installDist` en verde. Sin probar
todavía en el móvil ni en el coche.

---

## 20. GPS con el móvil bloqueado: ubicación del servicio y datos del coche honestos (2026-10-07)

**Informe (S25 Ultra, Android 16).** En «Auto extendido», con el móvil **bloqueado**, la pantalla del coche sigue (el
reloj avanza) y los sensores de movimiento también (`dumpsys sensorservice`: «has sensor access: true»), pero todo lo
que sale del GPS se congela: velocidad, avance de la ruta, viaje, consumo. Al desbloquear vuelve al instante. Medido
con `dumpsys activity processes` bloqueado: `curProcState=4 curCapability=---NFU-TI`, **sin la capacidad de ubicación
(`L`)**, aunque `LinkService` está en primer plano con los tipos `0x18` (`connectedDevice|location`). La ubicación
precisa está concedida solo «mientras se usa» (appops `FINE_LOCATION: foreground`). Las dos peticiones `HIGH_ACCURACY`
siguen registradas (`dumpsys location`), pero sin la capacidad Android no entrega nada. Desbloqueado, la `Presentation`
de la pantalla virtual pone el proceso delante (TOP) y con eso hay `L` y GPS.

### 20.1 Causa

Con la ubicación «mientras se usa», un servicio en primer plano de tipo `location` solo tiene la capacidad `L` si
Android le concedió el «while-in-use» (`mAllowWhileInUsePermissionInFgs`), y eso se decide **al arrancarlo**
(`startForegroundService`/`startService`) y **en cada `startForeground` posterior** (Android 12+: «the second or later
time startForeground() is called … check for app state again»), mirando si la app está delante en ese momento. En
HeadQLink el servicio pasa a primer plano muchas veces sin la app delante:

- **Conectar con el arranque automático de Android Auto** (`LinkControl.start`): el servicio se arranca *después* de la
  automatización, con los ajustes de AA delante y HeadQLink detrás.
- **Conexión automática por Bluetooth** (`CarBtReceiver`, un receptor en segundo plano, normalmente con el móvil
  bloqueado en el bolsillo), el widget (`ACTION_SET_LINK`, `ACTION_APPLY`), el cable (`ACTION_USB_ATTACHED` con la
  actividad puente ya cerrada) y el aviso «servidor encendido».
- **Cada orden al servicio en marcha** acababa en `goForeground`, que volvía a llamar a `startForeground` aunque ya
  estuviera en primer plano: desde segundo plano, Android lo reevalúa y le quita el «while-in-use».

- Además, Android 14+ no deja ni poner el tipo `location` desde segundo plano con la ubicación solo «mientras se usa»
  (`SecurityException`): `goForeground` caía a `connectedDevice` solo y el log decía «primer plano sin ubicación».

Desbloqueado no se notaba (la `Presentation` pone la app delante y el proceso tiene `L` por estar TOP), pero el
servicio no la conservaba al bloquear, y nada volvía a pasarlo a primer plano con la app delante.

### 20.2 Qué cambia

**El servicio conserva la ubicación (`LinkService`, `LocationAccess`).**
- `goForeground`: si el servicio ya está en primer plano y HeadQLink no se ve, **no** vuelve a llamar a
  `startForeground` (Android no lo exige ni con `startForegroundService` si ya lo está: «Service already foreground; no
  new timeout»); solo actualiza el aviso. Así una orden desde segundo plano no le quita la ubicación.
- Quien arranca el servicio desde una pantalla (`LinkControl`, `HomeActivity`, `LogActivity`, `SettingsScreen`,
  `UsbAccessoryActivity`) marca si HeadQLink estaba delante al pedirlo (`LinkService.fromApp`,
  `EXTRA_CALLER_VISIBLE`): es lo que mira Android y no siempre coincide con cuando llega la orden.
- **Recuperación** (`relatchLocation`): si el servicio pasó a primer plano sin la app delante (o llegó una orden desde
  segundo plano), vuelve a llamar a `startForeground` con el tipo `location` en cuanto el proceso está delante
  (`RunningAppProcessInfo.IMPORTANCE_FOREGROUND`): al desbloquear (`USER_PRESENT` + 1,5 s, con la pantalla del coche
  delante), al volver a la pantalla principal (`LinkService.appShown`) y cada 20 s con el enlace en marcha. Con la
  app delante Android le da el «while-in-use» y el servicio lo conserva al bloquear.
- Log, una vez por servicio: `primer plano: ubicación con el móvil bloqueado: sí/no (motivo) · acceso a la ubicación
  ahora: permitido/denegado` (lo segundo, con `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_FINE_LOCATION)`, que evalúa
  el modo `foreground` con el estado del proceso; sin privilegios). Al recuperarla: `… sí (recuperada: móvil
  desbloqueado, …)`.

**La solución robusta: «Ubicación todo el tiempo» (solo «Auto extendido»).** `ACCESS_BACKGROUND_LOCATION` en el
manifiesto (la build es de GitHub; la política de Google Play no aplica). Fila nueva de la Comprobación
**«Ubicación todo el tiempo» · Recomendado** («Para que los datos del coche sigan con el móvil bloqueado»), botón
«Abrir»: un diálogo con el porqué y luego `requestPermissions(ACCESS_BACKGROUND_LOCATION)`, que en Android 11+ abre la
página de Ajustes (Ubicación › «Permitir todo el tiempo»). Sin la ubicación normal, primero la pide y, al contestar,
encadena la de «todo el tiempo» (Android exige ese orden). Denegada para siempre: Info. de la app. En «Auto» no sale.
Cuenta en «Faltan N» y nunca bloquea «Conectar». `Requirements.bgLocation` (`Id.BG_LOCATION`, `Hint.NEEDS_FINE`).

**Datos del coche honestos (`GpsWatch`, `CarSensors`).**
- Con más de 5 s sin posición, `Snapshot.gpsState` pasa a `PAUSED_LOCKED` (bloqueado y sin «todo el tiempo») o
  `LOST` (túnel, garaje…); `WAITING` antes de la primera. `CarSensors.gpsNote` da el texto: «GPS en pausa · móvil
  bloqueado (activa la ubicación «todo el tiempo»)» o «sin GPS».
- Conducción e Instrumentos: velocidad «—» (la aguja a cero) y el motivo en rojo; Eficiencia: potencia «—» y el motivo
  en vez de «Consumiendo»; Viajes: el motivo en la línea del viaje en curso; Ruta: el motivo bajo las fichas (el avance
  está parado). Estado no cambia: son datos de la nube de Leapmotor, ya con su edad, no del GPS.
- **Huecos** (`CarSensors.bridgeGap`): cuando vuelven las posiciones tras más de 5 s, la línea recta entre la última y
  la nueva se suma al viaje y, a la media del hueco, a la energía estimada (si la media pasa de 200 km/h o el hueco de
  3 h, no se suma). Antes el paso de > 200 m se descartaba y la energía de huecos > 36 s no se integraba: el viaje y el
  consumo quedaban como si el coche hubiera estado parado. Log: `GPS: hueco de 63 s sin posiciones con el móvil
  bloqueado: 1.05 km en línea recta sumados al viaje (media 60 km/h; energía estimada a esa media)`. El viaje guardado
  lleva `gpsGapSec` y su línea del log, cuántos huecos.
- **Una vez por sesión** (sonda `GpsWatch.LockProbe`, tras el primer bloqueo con el GPS al día; lo que llega en los
  primeros 8 s no cuenta): `GPS: ubicación todo el tiempo sí/no · con el móvil bloqueado llega` o `… no llega (25 s
  bloqueado sin posiciones) · acceso a la ubicación ahora: denegado. Arreglo: …`. Si no hubo bloqueo: `… sin
  comprobar`. Además, en cada cambio: `GPS: en pausa con el móvil bloqueado (…)`, `GPS: sin posiciones desde hace N s`,
  `GPS: vuelven las posiciones`.

Manuales (es/pt/en): fila de la Comprobación, el porqué en «Qué ves y cómo se maneja» (sección Coche), fila de
problemas «Los datos del coche se congelan al bloquear el móvil» y el permiso en la tabla de privacidad. Textos en
`values`, `values-es`, `values-pt-rPT` y `values-pt-rBR`.

### 20.3 Cómo comprobarlo en el móvil

1. Sin «todo el tiempo», conectar con el arranque automático (o por Bluetooth con el móvil bloqueado). En el log:
   `primer plano: ubicación con el móvil bloqueado: no (… segundo plano …)`. Desbloquear con la pantalla del coche
   delante: `… sí (recuperada: móvil desbloqueado …)`. Bloquear y conducir: los datos siguen; a los ~25 s, `GPS:
   ubicación todo el tiempo no · con el móvil bloqueado llega`.
2. Comprobar a mano, bloqueado:
   `adb shell "dumpsys activity processes | grep -A40 'ProcessRecord{.*com.headqlink.app' | grep -E 'curProcState|curCapability'"`
   → con el móvil bloqueado debe salir `L` (`curCapability=L…`, antes `---NFU-TI`). `adb shell dumpsys location | findstr
   headqlink` sigue con sus 2 peticiones.
3. Comprobación › «Ubicación todo el tiempo» › «Abrir» › «Permitir todo el tiempo» (`adb shell appops get
   com.headqlink.app FINE_LOCATION` pasa a `allow`, y `dumpsys package com.headqlink.app | findstr BACKGROUND_LOCATION`
   a `granted=true`). Repetir 1 sin desbloquear: `GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega`.
4. Honestidad: con «mientras se usa», arrancar por Bluetooth con el móvil bloqueado y no desbloquear: Conducción e
   Instrumentos dicen «—» y «GPS en pausa · móvil bloqueado …»; al desbloquear, `GPS: hueco de N s … sumados al viaje`.

### 20.4 Resultados en el PC

`:app:testGithubDebugUnitTest :app:assembleGithubDebug`: **BUILD SUCCESSFUL**. App: 3014 pruebas, 0 fallos y 4
saltadas. Nueva `GpsWatchTest` (10: estado con y sin bloqueo y con «todo el tiempo», umbral de 5 s, huecos creíbles e
imposibles, sonda que llega, que no llega, desbloqueo antes de decidir y bloqueo sin GPS al día). `RequirementsTest`
(+3: solo en «Auto extendido» y recomendada, cuenta sin bloquear, primero la ubicación normal o Ajustes si se denegó).
Sin probar todavía en el móvil.

## 21. Certificado de la nube, Android Auto al parar su servidor y un servicio que se paraba con un arranque en cola (2026-10-07)

### 21.1 «Importar certificado…» no abría el selector (S25 Ultra, Android 16, 0.2.6)

**Informe.** En «Datos del coche», tocar «IMPORTAR CERTIFICADO…» a veces no hacía nada (ni `START` en el logcat ni
línea de HeadQLink); al final la pantalla decía «Falta el certificado: elige el .crt y el .key a la vez». El selector
del sistema funciona (`am start -a android.intent.action.OPEN_DOCUMENT …` abre `PickActivity`).

**Causas** (comprobado en el PC; en el móvil queda confirmarlo con el log nuevo):

- **El selector sí se abrió al menos una vez, y la pantalla exigía los dos ficheros a la vez.** «Falta el certificado»
  solo sale de `LeapTls.parse` con una clave y sin certificado: se eligió solo `app.key`. En el selector de Android
  tocar un fichero lo devuelve en el acto; marcar dos pide una pulsación larga que casi nadie conoce. Elegir de uno en
  uno no podía funcionar.
- **Toques que no llegaban o que no hacían nada sin decirlo.** Al abrir la pantalla el foco iba al «Correo» (el primer
  campo enfocable): teclado y sugerencias de autorrelleno, que con el teclado de Samsung salen como una ventana encima
  del campo, justo donde está el botón del certificado. Y el botón se desactivaba con `busy`, pero su estilo
  (`HQL.Button.Outline`, colores fijos) no cambia de aspecto: desactivado parece activo. `run()` también descartaba sin
  decir nada un toque con otra petición en curso, y la lectura del fichero iba en el mismo hilo que la red (un fichero
  lento dejaba la pantalla «ocupada» con el botón muerto).
- **No era R8**: en el APK de release (minify) `CarCloudActivity` conserva el `startActivityForResult(OPEN_DOCUMENT,
  41)` y su `onActivityResult` (comprobado con `dexdump`).

**Qué cambia** (`CarCloudActivity`, `CertPick` nuevo, layout, manifiesto y textos en los cuatro idiomas):

- **De uno en uno** (`CertPick`, puro): si llega solo el certificado o solo la clave, se guarda en memoria (no en disco;
  sobrevive a que Android rehaga la pantalla, caduca a los 15 min), el recuadro y un aviso dicen «Certificado leído
  (app.crt). Ahora elige la clave (app.key)» (o al revés), el botón pasa a «Elegir la clave…» y el siguiente toque abre
  el selector. Con los dos se importan juntos. Volver a elegir la misma mitad la sustituye; un .pem con los dos o un
  .p12 vale solo; los ficheros que no son ni certificado ni clave se apartan y se dice cuál. «Empezar de nuevo» olvida
  lo elegido a medias. Lo descartado se sobrescribe con ceros. Se clasifica por el contenido, no por el nombre.
- **Selector**: `ACTION_OPEN_DOCUMENT` con `*/*` y `EXTRA_MIME_TYPES` (`*/*`, `application/x-pem-file`,
  `application/x-x509-ca-cert`, `application/pkcs8`, `application/x-pkcs12`, `application/octet-stream`, `text/plain`)
  y varios a la vez. La pantalla es una `Activity` (no `ComponentActivity`): sigue con `startActivityForResult`.
- **Lectura** en su propio hilo (aparte de la red), en el acto (el permiso del selector dura lo que la pantalla). Nombre
  con `OpenableColumns.DISPLAY_NAME` y, si el proveedor no lo da, el final del URI (Mis archivos de Samsung, Archivos de
  Google, Descargas). Cada error de lectura (`SecurityException`, `FileNotFoundException`…) se dice con el nombre.
- **Botón siempre activo**; si está leyendo, el toque lo dice («Leyendo el certificado…»). `run()` avisa en vez de
  callar. Errores en el recuadro **y** en un aviso. Al abrir, el foco va al contenedor (`stateHidden`): ni teclado ni
  autorrelleno tapando el botón.

**Qué buscar en el log** (solo nombres de fichero y tipos de error; nunca el contenido):

| Línea | Significado |
|---|---|
| `nube Leapmotor: importar certificado: abro el selector` (`… (certificado «app.crt»; falta la clave)`) | Toque en el botón |
| `nube Leapmotor: importar certificado: el selector devuelve OK, 1 fichero(s)` (`cancelado`, `código N`) | Vuelta del selector |
| `nube Leapmotor: importar certificado: leídos 1 de 1: app.crt` | Leído |
| `W nube Leapmotor: importar certificado: no se pudo leer «x» (SecurityException)` | El proveedor no lo deja leer |
| `nube Leapmotor: importar certificado: certificado «app.crt»; falta la clave` / `listo (2 fichero(s))` / `no sirve: [x]` | Qué hay |
| `nube Leapmotor: certificado de cliente importado (RSA, 2 fichero(s))` | Guardado |
| `W nube Leapmotor: importar certificado: no válido (MISMATCH; app.crt, otra.key)` | Error de formato (tipo) |
| `nube Leapmotor: importar certificado: pulsado mientras leo lo anterior; espera` | Toque durante la lectura |

### 21.2 «FATAL EXCEPTION: Thread-2 … ServerSocket.isClosed() on a null object reference»

**No es de HeadQLink.** En los logcat guardados (`trips/20261005-coche3/logcat_buffer.txt`) el proceso es
`com.google.android.projection.gearhead:projection` (Android Auto) y la pila `wxl.run(SourceFile:676)`, código suyo
ofuscado. Las horas coinciden con nuestra automatización pulsando «Parar servidor unidad principal» (p. ej.
`09:08:37.199 AA server: pulsando 'Parar servidor unidad principal'` y el fallo a las 09:08:37): el hilo de escucha de su
servidor de head unit mira el `ServerSocket` que su propio apagado acaba de poner a `null`. El servidor queda parado
igual. Comprobado también que el único `ServerSocket.isClosed()` del APK es el de `MirrorServer`, con el campo `final`
asignado en el constructor (no puede ser null), y que sus hilos se llaman `qd-link-*`, no `Thread-N`.

**Qué cambia.** Una línea en el log (una vez por proceso) al parar el servidor, para no buscarlo en HeadQLink:
`AA server: parado. Si el logcat enseña un FATAL EXCEPTION de com.google.android.projection.gearhead:projection …`.
De paso, `WirelessServer.serverSocket` (Open Headunit) pasa a `@Volatile`: se asigna en la corrutina y lo cierra
`stopServer()` desde otro hilo; sin eso podía no verlo, no cerrarlo y dejar el 5288 escuchando.

### 21.3 `ForegroundServiceDidNotStartInTimeException` al pulsar «Desconectar» (AapService)

**Informe** (`qd-20261006-212222.log`, 09:19:43): «Desconectar (widget)» → cierre → a los 150 ms
`ForegroundServiceDidNotStartInTimeException … ServiceRecord{… AapService}` y la app se cierra.

**Causa (la más probable; el log de Open Headunit de ese momento no está guardado).** No son los 5 s de un arranque olvidado (`AapService` llama a `startForeground` al principio de `onCreate` y
de cada `onStartCommand`), sino un **paro con un arranque en cola**: si un servicio se para (`stopSelf()`/`stopService`)
mientras un `startForegroundService()` suyo aún no ha llegado a `onStartCommand`, Android cierra la app en el acto con
esa excepción. La orden de parar (`ACTION_STOP_SERVICE`) quita el primer plano (`stopForeground`) y llamaba a
`stopSelf()` sin número, que para aunque haya otra orden detrás; y `onTaskRemoved` (al quitarse una tarea de la app)
relanza el servicio con `startForegroundService` aunque se esté parando a petición
del usuario: justo el arranque en cola con el que muere.

**Qué cambia.**

- `AapService`: para con `stopSelf(startId)` (o con el de la última orden tras la espera del Wi-Fi), que Android no
  hace si hay otra orden en cola; esa orden llama a `startForeground` (como siempre, lo primero) y, como ya se aceptó
  un paro, termina de parar ella (`ServiceStopRacePolicy`, pura y con prueba: `STOP_NOW`, o se lo deja a la espera del
  Wi-Fi si sigue). Una segunda orden de parar ya no repite el cierre. `onTaskRemoved` no relanza si se está parando.
- `LinkService.goForeground`: solo se salta el `startForeground` (para no perder la ubicación «mientras se usa», d1b50206)
  si **el sistema** lo tiene en primer plano (`getForegroundServiceType() != 0`, `ForegroundCheck`), no solo si esta
  instancia lo cree.
- `AaGuardService`: igual, `stopSelf(lastStartId)`; un `park()` que llega mientras se libera el anterior sigue vigilando.

Líneas del log de Open Headunit (`AppLog`): `AapService: … after the stop: foreground kept, stopping now`,
`AapService: … during the stop teardown: it stops the service` y `AapService: onTaskRemoved while stopping — no
restart`.

### 21.4 Cómo comprobarlo en el móvil

1. Con un certificado de usar y tirar: ⚙ › «Datos del coche» › «Importar certificado…» › tocar solo `app.crt`. Debe
   salir «Certificado leído (app.crt). Ahora elige la clave (app.key)», el botón «Elegir la clave…» y «Empezar de
   nuevo». Tocar el botón › `app.key` › «Certificado guardado cifrado en este móvil». Repetir al revés, con los dos a la
   vez (pulsación larga), con un .pem y con un fichero que no sirve (aviso y recuadro).
2. En el log, las líneas de 21.1 en cada paso. Si un toque no deja ni `abro el selector`, el toque no llega al botón.
3. «Desconectar» desde el widget con Android Auto en marcha, varias veces: sin cierre de la app.

### 21.5 Resultados en el PC

`cmd /c ".\gradlew.bat :qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubRelease --console=plain"`:
**BUILD SUCCESSFUL** (el release con minify incluido). App: 3032 pruebas, 0 fallos y 4 saltadas; qdcore: 162, 0 fallos.
Nuevas: `CertPickTest` (11: clasificación por contenido, los dos a la vez, certificado y luego clave y al revés,
sustituir una mitad, un .p12 que reemplaza medio par, ficheros que no sirven, «Empezar de nuevo» con borrado, caducidad,
selección vacía, nombres para el log), `ServiceStopRacePolicyTest` (4) y `ForegroundCheckTest` (3). En el APK de release
(`dexdump`) están las líneas nuevas del log. Sin probar todavía en el móvil.

---

## 22. Versiones nuevas de Android Auto (17.8 / 17.9): menú, cortes y versión en el log (2026-10-07)

**Por qué.** Desde AA 17.4 el Self-Mode depende del servidor de head unit de desarrollador de AA (127.0.0.1:5277), que
arranca `AaServerStarter` pulsando su menú ⋮ por el texto, y cada versión nueva de AA puede romper las dos cosas. En
Open Headunit (de donde viene el Self-Mode):

| Referencia | Qué cuenta |
|---|---|
| open-headunit #985 «OHU Self Mode Not Working On AA 17.8» | El Self-Mode conecta y a los 1-2 s se desconecta. Volver a 17.7 lo arregla. El mantenedor no lo reproduce en un S26; sigue abierto |
| open-headunit #1022 «Self mode not responding on latest android auto» | Se arregló borrando la caché de Android Auto |
| Comentario de un usuario (AA 17.9) | «the helper doesn't work anymore»: la automatización del menú ya no encuentra lo que pulsa |

El móvil de pruebas tiene AA 17.7.663654 (en español) y la copia del sistema es un esqueleto 1.2: «Desinstalar
actualizaciones» no vuelve a una versión útil; volver atrás exige instalar el APK.

### 22.1 La automatización del menú (`AaServerStarter`, `AaMenuMatch` nuevo)

- **Texto normalizado y por trozos** (`AaMenuMatch`, puro): minúsculas, sin tildes (NFD), signos y espacios raros a un
  espacio. La opción del servidor es la que contiene «unidad principal» / «unidade principal» / «head unit» /
  «headunit» (también «unidad/unidade central»), en el texto o en la descripción; es la de **parar** si tiene una
  palabra entera de parar (`parar`, `detener`, `stop`, `interromper`, `deter`, `desactivar`, `desativar`, `apagar`,
  `terminar`, `finalizar`, `encerrar`) y si no la de iniciar (como antes). Variantes conocidas en `KNOWN_START` /
  `KNOWN_STOP` (es con y sin «de la», «Detener», en, pt-PT, pt-BR).
- **Botón ⋮ por lo estable primero**: resource-id con `overflow` (o `more_options`, `action_more`), la clase
  `…OverflowMenuButton` y, si no, la descripción («Más opciones», «More options», «Mais opções», «Outras opções»,
  «Otras opciones»…, o exactamente «Opciones» / «Options» / «Opções»).
- **Todas las ventanas de AA**: `getWindows()` (nuevo `flagRetrieveInteractiveWindows` en `hql_touch_service.xml`;
  Android lo lee al conectar el servicio, no hay que volver a activar la accesibilidad), ordenadas por capa, la de más
  arriba primero (el menú emergente es otra ventana), y la ventana activa de respaldo. Nuestra capa no cuenta (no es de
  AA).
- **Desplazamiento**: con el menú abierto y sin la opción, hasta dos `ACTION_SCROLL_FORWARD` (a los 0,6 y 1,2 s) en la
  primera lista desplazable; a los 2 s se reabre una vez, como antes.
- **Al no encontrarlo**:
  - menú sin la opción **pero con las otras del modo desarrollador** («desarrollador», «developer», «programador»,
    «desenvolvedor»): AA ha cambiado el texto. Ya no se da por desactivado el modo desarrollador (queda activo), línea
    `no encuentro … ¿lo ha cambiado esta versión?`, volcado y aviso;
  - menú sin nada del modo desarrollador: como antes («falta activar el modo desarrollador»), con el volcado;
  - AA delante y el botón ⋮ sin aparecer en los 10 s: línea, volcado de la pantalla y aviso.
- **Volcado**, una vez por proceso (uno del menú y otro de la pantalla): `AA server: no encuentro «Iniciar servidor de
  la unidad principal»; menú visto: [TextView «Configuración de desarrollador» · …]`. Texto solo de la ventana de más
  arriba con el menú abierto (sus opciones); de la pantalla de ajustes, solo clase, descripción e id. Correos tapados
  (`…@…`), números de 5 cifras o más como `#`, 40 caracteres por texto y 40 nodos como mucho.
- **Aviso** (canal nuevo «Compatibilidad con Android Auto», `aa_compat`, id 9): «HeadQLink no encuentra el botón del
  servidor en esta versión de Android Auto (17.x): arráncalo a mano (⋮ › Iniciar servidor de la unidad principal) o usa
  el modo Manual», con «Abrir AA» (sus ajustes) y «Arranque manual» (abre la Comprobación con el diálogo de siempre del
  manual: `ChecklistActivity.EXTRA_OFFER_MANUAL`). Solo al arrancar o comprobar; si falla un apagado ya avisa «El
  servidor de Android Auto sigue encendido».

### 22.2 La versión de Android Auto (`AaVersions` nuevo)

- Tabla de las probadas: **17.7.x (2026-10)**; lo demás, «sin probar todavía». Para el log, lo que se sabe de fuera de
  17.8 (#985) y 17.9 (el comentario).
- `versionName` y `versionCode` (`PackageManager`) al arrancar el servicio, en la cabecera: `servicio iniciado. …
  servidorAA=automático androidAuto=17.7.663654-release (código …): probada con HeadQLink (2026-10)` (modos Auto).
- Cada sesión: la segunda línea del bloque acaba en `· Android Auto 17.7.663654-release` y `sessions.csv` lleva la
  columna nueva **`aa_version`** al final (49 columnas). Un fichero con la cabecera vieja se reescribe con la nueva al
  añadir la primera fila, como siempre que cambian las columnas.
- Comprobación: fila **informativa** «Android Auto 17.7 · Probada con HeadQLink (2026-10)…» o «Android Auto 17.8 · Sin
  probar todavía con HeadQLink (probadas: 17.7.x). Si falla, desactiva la actualización automática de Android Auto…»,
  justo después de «Android Auto», con «Play Store». `Requirements.Id.AA_VERSION`, `Importance.INFO` (OK / TIP): nunca
  cuenta en «Faltan N» ni bloquea Conectar.

### 22.3 Cortes a los pocos segundos (#985): `AaFlapDetector` y `AaFlapWatch` nuevos

- `AaFlapWatch` escucha `CommManager.connectionState` (una corrutina por proceso, desde el primer Self-Mode).
  Conectado = `HandshakeComplete` / `TransportStarted`; corte = `Disconnected` / `Error`.
- **Los cierres de HeadQLink no cuentan** ni rompen la racha: `AaClose` (cierre o reconexión: los 6 s de espera al
  handshake más 4 s), el apagado del servidor (automatización o el botón de su notificación) y el enlace parado.
- `AaFlapDetector` (puro): una sesión que se corta sola en menos de **10 s** es corta; **2 seguidas**: diagnóstico. Una
  línea en el log cada vez que se llega a la racha y, **solo la primera vez del proceso**, el aviso «Android Auto se
  conecta y se corta a los pocos segundos» (id 10; al tocarlo, «Info. de la app» de AA para borrar la caché; botón
  «Abrir AA»). Una sesión de 10 s o más rompe la racha.
- **Nunca un bucle más rápido que cada 10 s**: con racha, ningún relanzamiento automático antes de 10 s desde el último
  corte. Automático: `AaPassthroughSource.ensureAaConnected` lo aplaza en su handler (con una línea; se anula en
  `stop()` y no se hace si el vídeo de AA ya no espera o AA volvió). Manual: `AaServeAttempts` recibe la espera
  (`Seen.relaunchHoldMs`) y el intento por «AA se desconectó con la sesión en marcha» espera (una línea). `AaFlapWatch`
  no relanza nada por sí mismo.

### 22.4 Qué buscar en el log

| Línea | Significado |
|---|---|
| `servicio iniciado. … androidAuto=17.8.661234-release (código 178661234): sin probar todavía con HeadQLink (probadas: 17.7.x; Open Headunit #985: …)` | La versión, al arrancar |
| `AA server: abriendo menú 'Más opciones'` (o el resource-id si no hay descripción) | Botón ⋮ encontrado |
| `AA server: el menú no muestra la opción del servidor; lo desplazo (1)` | Desplazamiento |
| `W ciclo: AA server: no encuentro «Iniciar servidor de la unidad principal» en el menú ⋮ (sí están las opciones del modo desarrollador) de Android Auto 17.9.… : ¿lo ha cambiado esta versión?` | AA cambió el texto |
| `W AA server: no encuentro «Iniciar servidor de la unidad principal»; menú visto: [TextView «…» · …]` (`pantalla visto: […]` sin texto) | El volcado (una vez) |
| `W ciclo: aviso: «HeadQLink no encuentra el botón del servidor» (Android Auto 17.9.…): arrancarlo a mano o pasar al arranque manual` | El aviso |
| `W ciclo: AA: Android Auto se desconectó solo a los 1,4 s de conectar (corte corto 1 de 2 para el diagnóstico)` | Primer corte corto |
| `W ciclo: AA: diagnóstico: Android Auto se conecta y se desconecta solo a los pocos segundos (2 veces seguidas; la última, a los 1,2 s). Android Auto 17.8.… Es el síntoma de Open Headunit #985 … Prueba: borrar la caché de Android Auto (Ajustes › Aplicaciones › Android Auto › Almacenamiento › Borrar caché), parar e iniciar su servidor (⋮) o volver a una versión probada (17.7.x); los relanzamientos automáticos esperan al menos 10 s; aviso «…»` | El diagnóstico |
| `ciclo: AA: Android Auto se ha cortado a los pocos segundos de conectar; lo relanzo en 9 s (como mucho uno cada 10 s)` | Relanzamiento aplazado (automático) |
| `ciclo: AA server (arranque manual): Android Auto se corta a los pocos segundos de conectar: el intento nuevo espera 7 s (como mucho uno cada 10 s)` | Ídem (manual) |

En `sessions.csv`, la última columna `aa_version`.

### 22.5 Cómo comprobarlo en el móvil

1. «Comprobación» con AA 17.7: fila «Android Auto 17.7 · Probada con HeadQLink (2026-10)» y su «Play Store».
2. Conectar: `androidAuto=17.7.663654-release (código …): probada…` en la cabecera, `· Android Auto 17.7…` en el bloque
   de la sesión y la columna `aa_version` en `sessions.csv`.
3. Automático: la automatización como siempre (`abriendo menú 'Más opciones'`, `pulsando 'Iniciar servidor de la unidad
   principal'`), también la comprobación del modo desarrollador.
4. Lo que no encuentra: poner el móvil en un idioma que HeadQLink no conoce (por ejemplo alemán) y conectar: a los 10 s,
   el volcado de la pantalla y el aviso con «Abrir AA» y «Arranque manual» (este abre la Comprobación con el diálogo).
   Volver al español.
5. Los cortes de #985 no salen con AA 17.7: los cubren las pruebas del PC. Si alguien los ve con 17.8, el log dirá
   `diagnóstico:` y el aviso saldrá una vez.

### 22.6 Pruebas en el PC

`AaMenuMatchTest` (9: los textos conocidos de iniciar y parar; variantes en los cuatro idiomas; mayúsculas, tildes
compuestas y precompuestas, signos y espacios raros; texto o descripción; las otras opciones del menú no son el
servidor y «stopwatch» no es parar; opciones del modo desarrollador en cada idioma; botón ⋮ por id, clase o
descripción, y otros botones no; volcado sin texto de la pantalla y con correos y números tapados). `AaVersionsTest` (4:
serie de la versión; solo 17.7 probada, 17.6/17.8/17.9/18.x sin probar; los textos del log con #985 en 17.8; la celda
del CSV). `AaFlapDetectorTest` (5: dos cortas seguidas → diagnóstico con un solo aviso; una larga rompe la racha; los
cierres nuestros ni cuentan ni la rompen; un corte sin sesión no cuenta y `Error` + `Disconnected` es un solo corte;
relanzamientos como mucho cada 10 s). `AaServeAttemptsTest` (+1: con la espera, el intento tras el corte espera, lo
dice una vez y lanza al acabar). `RequirementsTest` (+1: la fila de la versión, informativa, probada o consejo, nunca
cuenta ni bloquea, solo en los modos Auto con AA instalado y activado, justo después de «Android Auto»; las listas
completas la incluyen; la foto por defecto es AA 17.7.663654). `SessionSummaryTest` (+1: `aa_version` última columna y
en el bloque; las posiciones contadas desde el final, una más).

`cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubRelease --console=plain"`: **BUILD
SUCCESSFUL** (el release con minify y lint vital). App: 3053 pruebas, 0 fallos y 4 saltadas (las de siempre); el
paquete `com.headqlink.link`, 391.

### 22.7 Limitaciones

- Sin probar en el móvil. Los textos de 17.8 / 17.9 y los del menú en inglés y portugués están escritos sin verlos (no
  hay un móvil con esas versiones): el volcado está para adaptarlos en cuanto alguien mande un log.
- La causa de #985 no se conoce (lado de AA): HeadQLink solo lo diagnostica, frena los relanzamientos y explica qué
  probar.
- Un corte de verdad en los 10 s siguientes a un cierre nuestro no cuenta para el diagnóstico.
- El volcado del menú lleva sus textos tal cual (recortados): son las opciones de AA, no datos del usuario; de la
  pantalla de ajustes, donde podría salir el nombre de un coche, no se vuelca texto.

## 23. P-frames grandes: el P-frame que sigue a cada IDR (2026-10-07)

**Informe.** En los dos viajes del 2026-10-07 (mañana 08:51-09:13, tarde 15:36-16:00, perfil Coche, zona Wi-Fi) y en
la prueba de radio floja en casa (`qdsim --scenario radio-mala`, 10:38) salen P-frames de 160-335 KB mientras el
control del enlace pide 1,2-2,5 Mbit/s a 20-30 fps (un P-frame medio son 5-12 KB). Los IDR de esas sesiones pesan
56-110 KB: los P-frames grandes pesan el doble que un IDR. Subir el QP-P mínimo en marcha hasta 38-40 y bajar el bitrate
no los encoge (§19). En el modo ampliado (panel casi quieto, ~1,8 Mbit/s) no hubo cortes.

### 23.1 Método

Análisis offline de las trazas `perf/*.csv` (13 sesiones de los viajes y la de casa; las de la mañana son de una
versión sin `p_kb`: el tamaño sale de las filas `frame` y el instante de codificación, de restar la espera en cola y la
escritura). Para cada P-frame «grande» (más de 6 veces la media de la sesión y ≥ 40 KB) se mira qué pasó justo antes:

| Clase | Criterio |
|---|---|
| **tras IDR** | La salida anterior del encoder fue un IDR (≤ 250 ms antes) |
| **puerta** | Hubo dibujo (`relay_draw`) y el anterior fue hace > 100 ms con el frame esperando ≥ 60 ms o la cola del kernel ≥ 32 KB (la puerta «último frame» lo retuvo) |
| **AA quieto** | Más de 100 ms sin dibujo pero sin puerta: Android Auto no mandó nada (el encoder repetía el último frame) |
| **transición** | El P-frame anterior ya era grande (≥ 3 medias): animación o cambio de pantalla de AA a ritmo normal |
| **suelto** | Ninguna de las anteriores |

Además: `setParameters` (bitrate del enlace, QP-P, bajadas) en los 400 ms anteriores, cambio de fps en el segundo
anterior y hueco desde la salida anterior del encoder.

### 23.2 Resultado por sesión

| Traza | s | P | media KB | máx. KB | IDR (mediana) KB | grandes | tras IDR | puerta | AA quieto | transición | suelto | ≥ 150 KB (tras IDR) | P tras IDR > su IDR | P/IDR (mediana) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| mañana S22 | 336 | 6648 | 14,6 | 348 | 97 | 109 | 49 | 49 | 0 | 9 | 2 | 53 (45) | 49/55 | 2,65 |
| mañana S23 | 171 | 4697 | 12,6 | 302 | 57 | 29 | 7 | 14 | 0 | 6 | 2 | 2 (2) | 6/7 | 1,97 |
| mañana S24 | 22 | 499 | 16,1 | 78 | 80 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0/1 | — |
| mañana S25 | 55 | 651 | 18,1 | 303 | 97 | 7 | 3 | 4 | 0 | 0 | 0 | 6 (3) | 3/4 | 1,65 |
| mañana S26 | 173 | 1747 | 18,8 | 265 | 94 | 41 | 38 | 3 | 0 | 0 | 0 | 39 (36) | 38/45 | 2,61 |
| mañana S27 | 163 | 2818 | 18,2 | 380 | 104 | 38 | 27 | 11 | 0 | 0 | 0 | 32 (24) | 28/29 | 2,23 |
| mañana S28 | 74 | 622 | 27,5 | 356 | 90 | 17 | 15 | 1 | 0 | 0 | 1 | 17 (15) | 26/27 | 2,12 |
| mañana S29 (cable, 60 fps) | 134 | 6904 | 17,8 | 196 | 82 | 63 | 1 | 0 | 0 | 46 | 16 | 13 (1) | 2/2 | 1,61 |
| tarde S1 | 252 | 6430 | 21,8 | 247 | 93 | 7 | 6 | 0 | 0 | 1 | 0 | 6 (5) | 8/11 | 1,79 |
| tarde S2 | 189 | 4644 | 21,9 | 219 | 100 | 15 | 3 | 3 | 0 | 8 | 1 | 9 (2) | 4/7 | 1,01 |
| tarde S3 | 22 | 578 | 21,4 | 178 | — | 4 | 0 | 0 | 0 | 4 | 0 | 2 (0) | 0/0 | — |
| tarde S4 | 396 | 5517 | 15,8 | 335 | 78 | 61 | 5 | 14 | 6 | 32 | 4 | 23 (2) | 5/6 | 1,86 |
| tarde S5 | 78 | 1856 | 16,9 | 224 | 83 | 19 | 3 | 4 | 0 | 12 | 0 | 5 (3) | 3/3 | 2,67 |
| casa, radio floja | 120 | 2277 | 8,4 | 285 | 56 | 35 | 27 | 5 | 0 | 2 | 1 | 16 (12) | 25/33 | 2,19 |
| **Total** | | | | | | **445** | **184** | **108** | **6** | **120** | **27** | **223 (150)** | **197/230** | **2,29** |

**De los 223 P-frames de ≥ 150 KB (los que cortan la radio), 150 (67 %) son el primer P-frame tras un IDR.** Y de los
230 IDR con un P-frame detrás, en 197 (86 %) ese P-frame pesa más que el IDR, de mediana 2,3 veces.

Ejemplos (bytes exactos de la traza):

| Sesión | Secuencia |
|---|---|
| tarde S1, 32 s | P-frames de 10 KB a 30 fps · `idr_req` (el coche pide IDR) · **IDR 61 412 B** · 31 ms después **P 171 895 B** (con un dibujo normal, sin puerta) · P de 10 KB |
| tarde S1, 64 s | IDR periódico (sin petición ni `setParameters`) de 77 KB → P de 137 KB |
| casa, 77 s | IDR 57 710 B (pedido por BACKLOG) → 100 ms después, sin dibujo (la repetición del encoder de la misma imagen) **P de 164 KB** → repeticiones de 4-16 KB |
| tarde S4, 177 s | transición: 117 → 149 → 178 → 214 → 238 → 255 → 282 → 239 KB, uno cada 33 ms, sin puerta |
| tarde S1, 2 s | arranque (IDR de 7 KB con la pantalla negra, luego el fundido de AA): 2, 9, 18, 27, 40, 52, 69, 84, 102, 119, 206 KB |

### 23.3 Hipótesis

| Hipótesis | Veredicto |
|---|---|
| (a) La puerta retiene frames cientos de ms y el siguiente cambia mucho | **Secundaria**: 34 de 223 (15 %) de los ≥ 150 KB, 108 de 445 grandes; casi todos de 90-130 KB (el tamaño de un IDR). Pedir un IDR en su lugar no sirve: el IDR va seguido del P-frame gigante de (e). |
| (b) `setParameters` en marcha (bitrate, QP, fps) provoca un frame de refresco | **No**: el pico tras IDR sale igual con IDR periódicos (sin petición) y sin bajada de bitrate («con bitrate bajado 0» en todas las sesiones de la tarde). De los 261 grandes que no siguen a un IDR, 77 tienen un `setParameters` en los 400 ms anteriores, pero es la reacción del control de P-frames al grande anterior de la misma ráfaga. |
| (c) Cambios del periodo de intra-refresh al cambiar el tope de fps | **No**: `applyFpsCap` solo cambia el ritmo del relay GL; el periodo de intra-refresh no se toca en marcha. 4 de 261 con un cambio de fps en el segundo anterior. |
| (d) Huecos en la marca de tiempo: el VBR gasta segundos de presupuesto en un frame | **No**: el encoder repite el último frame cada 100 ms (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`), así que nunca ve huecos mayores; y los picos tras IDR salen a 33 ms del frame anterior. |
| **(e) El P-frame que sigue a un IDR «afina» la imagen entera** | **Sí, la principal.** Ver 23.4. |

Las transiciones (27 de los ≥ 150 KB, sobre todo el cable a 60 fps y S4) son contenido: el VBR del c2.qti deja pasar
varios frames seguidos de 150-280 KB.

### 23.4 Por qué: el QP de configure manda y el de en marcha no

- El encoder se configura con QP-I 24-51 (`IdrSizeController.QP_START`) y, hasta ahora, QP-P **1**-51 («sin mínimo»).
- El IDR sale **en el suelo de QP-I**: en casa, el mismo IDR pesó 57 0xx-57 7xx B durante un minuto mientras
  `IdrSizeController` bajaba el QP-I mínimo de 21 a 18 en marcha, y con bajadas de bitrate de hasta el 40 % en medio. Es
  decir: el control de tasa quería más calidad que QP 24 (el suelo lo frena) y **los cambios de QP en marcha no llegan**
  (lo mismo que se vio con el QP-P en §19: subirlo de 26 a 40 no encoge nada).
- El P-frame siguiente no tiene suelo: el VBR, con presupuesto de sobra tras un IDR «barato», le da un QP mucho más bajo
  y el P-frame recodifica la imagen entera a más calidad que el IDR: 2-3 veces su tamaño. Luego todo vuelve a 10 KB.
- Lo mismo acota los otros casos: un P-frame con la imagen muy cambiada (puerta, transición, fundido) también salía con
  un QP muy por debajo de 24.

**Parámetros de fabricante** que declara el c2.qti.avc.encoder (S25, línea `encoder: parámetros de fabricante útiles`):
`bitrate-boost-margin`, `bitrate-mode`, `chroma-qp-offset`, `dynamic-frame-rate`, `frame-qp` (QP fijo por frame, para
tasa apagada), `initial-qp` (I/P/B), `low-latency`, `peak-bitrate`, `roi-mbmap-info`, `slice`, `perfboost-mode` (todos
`vendor.qti-ext-enc-*`). **Ninguno de tamaño máximo de frame, VBV, HRD o control de tasa por frame**; pero el filtro solo
listaba los que contenían «qp», «rate», «latency», etc., así que ahora lista también los de `frame-size`, `max-frame`,
`vbv`, `hrd`, `rc`, `boost`, `peak`, `intra`, `refresh`, `gop`, `ltr`, `hier` y `adaptive` para el próximo viaje. El
`peak-bitrate` sale en el formato de salida como `max-bitrate` = el bitrate inicial (5,08 Mbit/s) y no baja con el del
enlace; no se toca: es un tope medio, no por frame.

### 23.5 Arreglo

| Cambio | Detalle |
|---|---|
| **Suelo de QP-P al configurar** | `VideoPipeline.startEncoder`: `vp.qpPMin = PFrameSizeController.QP_FLOOR` (= `IdrSizeController.QP_START`, 24). El encoder se configura con QP-P **24**-51 en vez de 1-51: un P-frame no puede quedar más fino que el IDR al que sigue, así que el pico tras IDR desaparece y un P-frame con la imagen entera cambiada queda acotado por el tamaño de un IDR (~60-110 KB en lugar de 160-335). Va en configure porque es lo único que el c2.qti respeta. Mismo orden de intentos (QP-I + QP-P, solo QP-I, nada). |
| **`setQpPMin` nunca baja del suelo** | `VideoEncoder.qpPMinFor(pedido, suelo)`: «sin mínimo propio» del control de P-frames (§19) es ahora el suelo de configure, no QP 1, también en los encoders que sí aplican el QP en marcha. Las subidas (26…40) y el plan B siguen igual. Textos: `QP-P en el suelo (24)` en lugar de `QP-P sin mínimo (el del encoder)`. |
| **Vigilancia del P-frame tras IDR** (`PAfterIdr`, puro) | El primer P-frame tras cada IDR: a la traza (`p_after_idr`, KB) y, si pesa más que su IDR, `W VIDEO P-frame tras IDR: 168 KB, 2.8 veces el IDR (60 KB) · QP-P suelo 24` (como mucho una línea cada 10 s; las demás se cuentan). Al desenganchar cada sesión (`VIDEO desenganchado de S3 (el vídeo sigue vivo) · P-frames tras IDR 13 · más grandes que su IDR 0 · hasta 0.3 veces el IDR · el mayor 31 KB`) y al parar el vídeo. |
| **Puerta en la traza** | `GlFrameRelay`: `gate_hold_ms` al dibujar, con los ms que la puerta «último frame» retuvo el dibujo (solo si lo retuvo), y `(puerta cerrada máx N ms)` en la línea de 5 s del relay. |

**Qué no se ha cambiado y por qué.** No se pide un IDR tras una espera larga de la puerta (el IDR va seguido del pico de
23.4 y no es más pequeño que el P-frame que sustituye); no se tocan el intra-refresh, los fps ni los IDR periódicos de
30 s (no son la causa: con el suelo, cada uno vale un IDR y un P-frame normal); sin CBR (§12: peor imagen con el mismo
bitrate) ni claves de fabricante sin probar. Coste del suelo: con la pantalla quieta y enlace bueno, la imagen ya no se
afina por debajo de QP 24, la calidad de cada IDR (la misma que se ve justo tras cada IDR desde siempre). Si se viera
blanda, el mando es `IdrSizeController.QP_START`, que mueve los dos.

### 23.6 Cómo comprobarlo

**En casa** (móvil y PC en la misma Wi-Fi; `.\gradlew.bat :qdsim:installDist` una vez):

```
qdsim\build\install\qdsim\bin\qdsim.bat --scenario radio-mala --target <IP del móvil>
```

| Dónde | Antes | Esperado |
|---|---|---|
| Log del móvil al arrancar el vídeo | `encoder: QP-I 24-51 al configurar · QP-P 1-51 (sin mínimo)` | `… · QP-P 24-51 (suelo: un P-frame no afina la imagen más que el IDR)` |
| Tras cada `VIDEO IDR 56 KB … pedido (BACKLOG)` | `VIDEO P-frames: 164 KB > tope 24 KB … → QP-P mín 40 …` 100 ms después | Nada, o un P-frame por debajo del IDR. Si sale `W VIDEO P-frame tras IDR: … veces el IDR`, el encoder no respeta el suelo |
| Al acabar la sesión | — | `VIDEO desenganchado de S1 … · P-frames tras IDR N · más grandes que su IDR 0 · hasta 0.x veces el IDR · el mayor … KB` |
| `qdsim`, líneas `radio:` | P-frames máx. 285 KB, 35 de más de 6 medios | Máximo ≲ el IDR (~60-80 KB) y muchos menos de más de 6 medios |
| `perf/*.csv` | `idr_kb,56` y luego `p_kb,164` | `p_after_idr` ≤ el `idr_kb` de justo antes |

**En el coche:** en `sessions.csv`, `p_max_kb` ≲ 110 (el IDR más grande) en vez de 220-250 y `p_sobre_tope` mucho
menor; en el log, ninguna `W VIDEO P-frame tras IDR`; en `perf/*.csv`, `p_after_idr` por debajo del `idr_kb` anterior y,
para los grandes que queden, `gate_hold_ms` justo antes (puerta, hipótesis (a)) o una racha de `p_kb` crecientes
(transición de AA). Menos «Corte … RADIO» a la vez que un `write de VIDEO_P` grande.

### 23.7 Pruebas en el PC

`cmd /c ".\gradlew.bat :qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubRelease --console=plain"`: **BUILD
SUCCESSFUL**. `:qdcore` 162/162. App: 3059 pruebas, 0 fallos y 4 saltadas (las de siempre). Nueva: `PAfterIdrTest` (5:
solo cuenta el primer P-frame tras un IDR; uno más grande que su IDR da una línea como mucho cada 10 s y el resumen; con
el suelo el resumen dice 0; sesión nueva conserva el IDR pendiente e ignora IDR vacíos; `qpPMinFor` nunca baja del suelo
y sin suelo deja el de siempre). `PFrameSizeControllerTest` con los textos nuevos del suelo. Sin probar en el móvil ni
en el coche.

---

## 24. Android Auto en el modo extendido: arranque sin esperas y relanzamiento si se cae (2026-10-09)

Quejas del viaje: «por Wi-Fi en extendido, apagas la pantalla y se para la imagen en el coche, y AA va muy lento: pulsas
algo y funciona a los 5 s». Lo que dicen los registros:

| Qué pasaba | Causa | Arreglo |
|---|---|---|
| Cada arranque de Android Auto (al conectar, al volver tras un corte, al cambiar de modo) tardaba ~12 s en dar imagen, con la zona de AA en negro | El Self-Mode de Open Headunit (AA 17.4+, `SelfLauncherV17_4`) manda primero el aviso START_WIRELESS_PROJECTION y espera 12 s. En el S25 Ultra con AA 17.7 no contestó nunca (52 de 52 arranques del 4 al 9 de octubre), y el servidor de head unit (127.0.0.1:5277) entraba a la primera | `SelfModeShortcut`: si el aviso caduca y el servidor conecta, se recuerda para esa versión de AA (`self_mode_direct_aa`) y los arranques siguientes van directos al servidor. Con otra versión de AA se vuelve a probar el aviso, y si contesta se olvida. Probado en el móvil: el primer arranque aprende |
| Al pasar del cable a la zona Wi-Fi (o al enchufar el cable con el Wi-Fi), la zona de AA se quedaba congelada en el coche hasta pulsar Desconectar y Conectar (S22 del 9: 64 s; S8 del 8; S28 del 7) | Android Auto cierra su sesión con los avisos de USB (accesorio desconectado o alimentación conectada) y Open Headunit no la relanza («Self Mode disconnected. Not restarting»). La pantalla del móvil se apagaba a la vez, por eso parecía cosa de la pantalla | `AaFlapWatch` avisa a `AaPassthroughSource.onAaDropped` cuando AA se cae solo con el enlace en marcha (sesión perdida, sin despedida: salir de AA a propósito no cuenta). Si hay sesión con el coche esperando su vídeo, se relanza a los 1,5 s, con el freno de un relanzamiento cada 10 s si se corta en bucle. Sin probar todavía en el coche |

- **Pantalla apagada:** prueba en casa con el coche simulado por la Wi-Fi de casa, la pantalla apagada 60 s y el móvil desenchufado (simulado con `dumpsys battery unplug`). AA siguió a 30 fps, el coche recibió 30 fps y los toques respondieron. En los registros, todas las imágenes congeladas «al apagar la pantalla» coinciden con enchufar el cable.
- **Toques:** en la sesión por zona Wi-Fi del 9 (S23), el móvil tarda 0,2-0,3 s desde que recibe un toque hasta mandar la imagen nueva. Los 5 s eran los 12 s de arranque de AA y la zona congelada sin relanzar.
- **Escaneos Wi-Fi (pendiente):** Google Play Services busca redes Wi-Fi cada ~8,5 s durante ~3,6 s para la ubicación por red, porque Waze y AA piden posición continuamente (`dumpsys wifiscanner`: `network_location_provider`). Con la zona Wi-Fi del móvil, el enlace con el coche se para 0,1-0,3 s varias veces en cada escaneo (`gate_hold_ms` en la traza). Por probar en el coche: desactivar «Precisión de la ubicación de Google».

Líneas nuevas:

| Línea | Significado |
|---|---|
| `AA: el aviso de proyección no contestó en 12 s y el servidor de head unit sí: los próximos arranques con AA 177663654 van directos al servidor` | Atajo aprendido |
| `AA: arranque directo al servidor de head unit (con AA … el aviso de proyección no contestó nunca en este móvil: 12 s menos)` | Atajo usado |
| `AA: Android Auto se ha desconectado solo con el coche conectado; lo relanzo` | Relanzamiento tras una caída |

Pruebas: `SelfModeShortcutTest` (la misma versión de AA, otra versión, nada aprendido).

### 24.1 La pantalla de proyección de Open Headunit tapaba el móvil (2026-10-09)

**Síntoma.** Captura del usuario: el móvil apaisado, en negro, con «Android Auto está iniciando…» (`android_auto_starting`).
Tapa la pantalla entera y los botones, y deja el móvil sin poder usarse hasta que se cierra.

**Qué era.** Era `AapProjectionActivity`, la pantalla de head unit de Open Headunit, en la pantalla 0 del móvil. Se abrió a las 08:55:03, en plena S13 por cable, sin que HeadQLink la pidiera. Hay tres caminos que la abren:
- tocar la notificación de música de AA (`BackgroundNotification`);
- tocar la de navegación (`AapNavigationHelper`);
- algunos casos de Open Headunit (ajustes, `AapBroadcastReceiver`).

**Daños.** Además de tapar el móvil, al abrirse se queda con el decodificador de AA («New surface set»): la zona de AA del coche se para. Antes, a las 08:54:28, el Self-Mode había caducado (Path 2 seguía esperando sus 12 s). `SelfLaunchResolveHelper` abrió entonces la pantalla de permisos de AA, que también tapó el móvil unos 11 s.

**Arreglo.**
- `AapProjectionActivity.onCreate` se cierra nada más crearse (`allowOnPhone = false`). No toca el decodificador, la orientación ni los márgenes anunciados, y `onDestroy` tampoco limpia nada.
- Las dos notificaciones abren HeadQLink sin conectar sola (`HomeActivity.openIntent`, la misma vía que la marca del widget).
- Con el coche conectado (`VideoTap.headless`), `SelfLaunchResolveHelper` no abre la pantalla de permisos de AA; solo lo apunta en el log.

Líneas nuevas:

| Línea | Significado |
|---|---|
| `AapProjectionActivity: headqlink - la proyección no se abre en el móvil (va al coche); se cierra` | Alguien intentó abrirla |
| `SelfMode: AA no conectó; con el coche conectado no abro su pantalla de permisos en el móvil` | Self-Mode caducado en plena sesión |

## 25. Modo extendido con la pantalla del móvil apagada, día y noche por luz y otros retoques (2026-10-09)

### 25.1 El panel se congelaba con la pantalla del móvil apagada (a batería)

**Síntoma.** Por Wi-Fi, al bloquear el móvil, la parte del extendido se quedaba congelada en el coche (Android Auto seguía). Volvía al desbloquear y se congelaba otra vez a los 5-6 s de bloquear. El móvil se bloquea 5 s después de apagar la pantalla (`lock_screen_lock_after_timeout = 5000`).

**Causa.** Prueba con el coche simulado y el móvil a batería:
- Con la pantalla del móvil en OFF, la Presentation de CarUi sigue dibujando (`gfxinfo`: de 10209 a 10307 fotogramas en 15 s), pero al relay no le llega ni un fotograma de la capa.
- Android deja de componer nuestra pantalla virtual porque va en el grupo de la del móvil.
- Android Auto, con su propia pantalla de sistema, seguía a 30 fps.
- Cargando no pasaba: Samsung deja la pantalla en reposo para el AOD de la carga.
- Una app no puede crear su pantalla virtual en otro grupo (`VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP` pide `ADD_TRUSTED_DISPLAY`).

**Arreglo** (`PhoneOffRenderer`):
- Con la pantalla del móvil en OFF o DOZE_SUSPEND (se vigila con `DisplayListener` y, por si acaso, cada 1 s), HeadQLink dibuja él mismo el árbol de vistas de la Presentation.
  - Usa un `HardwareRenderer` propio y redibuja cada vez que la Presentation dibuja (`OnDrawListener`).
  - Dibuja en una segunda entrada del relay que solo usa él (`GlFrameRelay.manualOverlayInput`), y el relay toma la capa de ahí (`setManualOverlay`).
- La pantalla virtual no se toca. Quitarle la superficie con Android aún conectado tumbaba la app en el RenderThread («getFrame() called on a context with no surface!»).
- Los reproductores (Vídeos, TV) pasan a `TextureView` (`CarStyle.player`, `app:surface_type="texture_view"`), porque una SurfaceView la compone Android aparte y no saldría.
- Probado: con la pantalla apagada y el coche simulado cambiando de pantalla cada 4 s, 38 fotogramas dibujados por HeadQLink en 42 s y sin cierres.

### 25.2 Día y noche con el sensor de luz del móvil

En túneles, en el garaje y en días nublados, Android Auto y la pantalla del coche se oscurecían y la barra del extendido no. Le mandábamos a AA amanecer y atardecer (`NightMode.AUTO`), y el coche no avisa cuando cambia su tema automático (hoy, ni un `Global/DarkModeOn`).

Ahora, al empezar la sesión, `AaPassthroughSource.sessionNightMode` elige:
- el tema que dijo el coche, si alguna vez lo dijo;
- si no, el sensor de luz del móvil (`LIGHT_SENSOR`, 100 lux con margen);
- sin sensor de luz, `AUTO`.

Android Auto y el panel siguen la misma señal.

### 25.3 Otros cambios

- **«Cerrando Auto…» sin capa.** Al apagar el servidor de head unit, la automatización ya no pone la capa: tapaba el móvil justo al desconectar o al desbloquear. Se ve el menú de AA 1-2 s. El arranque mantiene «Arrancando Auto…».
- **Recomendado según el modo** (`Config.recommendedLink`): cable USB en Auto extendido y punto de acceso en Auto. El cable deja de llevar la marca «Experimental».
- **Versión en la cabecera** de la pantalla principal («HEADQLINK v0.2.9», `Ui.versionLabel`).

Líneas nuevas:

| Línea | Significado |
|---|---|
| `CarUi: pantalla del móvil apagada: dibujo yo la interfaz (Android no compone la pantalla virtual)` | Empieza el dibujo propio |
| `GL relay: capa de la interfaz dibujada por HeadQLink (pantalla del móvil apagada)` / `… de la pantalla virtual` | El relay cambia de entrada |
| `CarUi: pantalla del móvil encendida: vuelve la composición de Android (N fotogramas dibujados por HeadQLink)` | Fin del dibujo propio |
| `AA: día y noche con el sensor de luz del móvil (túneles, garajes)` | Día y noche de la sesión |

Pruebas: `PhoneOffRendererTest`, `SessionNightModeTest` y `VersionLabelTest`.

## 26. Pantalla partida en el modo extendido y versión 0.2.30 (2026-10-09)

En «Web», «Vídeos» y «TV», el botón «Partir pantalla» del panel pone Android Auto a la izquierda, junto al panel (compacto), y esa pantalla a la derecha (al principio, mitad y mitad; ver §26.1). «Pantalla completa» la deja sola otra vez. El estado se guarda en `CarUi.split` durante la sesión con el coche (desde la 0.2.32 empieza sin partir en cada sesión).

- `CarUi.splitAaWidth(width, railW)` = `(width − railW) / 2` (912 px en 1920 con el panel de 96 px). La pantalla ocupa el resto, a la derecha de AA.
- `CarUi.Listener.onAaRegion(x, w)` sustituye a `onPanelWidth` (por defecto llama a esta). `AaPassthroughSource.setAaRegion` mueve AA a esa franja: el margen derecho que se anuncia a AA es `R = ancho negociado − ancho del vídeo de AA` y el relay coloca AA en `x`.
- `CarUi.aaVisible()` es cierto también con la pantalla partida, así que AA sigue a la vista aunque haya una pantalla abierta.
- Toques: los que caen dentro de `[aaCarX, aaCarX + aaCarW)` van a AA y el resto a la interfaz.

Líneas nuevas:

| Línea | Significado |
|---|---|
| `CarUi: pantalla partida sí (web)` / `… no` | Se parte o se une la pantalla |
| `AA: panel 96 px -> AA en x=96 ancho 912 (vídeo) · pantalla partida` | AA en su mitad |

Probado con el coche simulado: AA en la mitad izquierda, Wikipedia en la derecha; los toques abren el lanzador de AA y la web responde a los suyos.

La versión pasa a **0.2.30** (`versionCode` 12): «0.2.3» se vería como anterior a 0.2.9 al comparar versiones para avisar de actualizaciones.

Pruebas: `SplitScreenTest`.

### 26.1 Maps sin toques con la pantalla partida, y el aviso (0.2.31)

Con AA a la mitad (912 px en el C10), Google Maps no se movía ni dejaba buscar; la barra de AA y las tarjetas sí respondían. Probado en el coche virtual:
- Maps recibe los toques (`ViewPostIme` en su proceso), pero AA ha pasado a su diseño de tarjetas (mapa arriba, tiempo y música debajo) y Maps está en modo reducido, sin zoom ni botones. Cada toque solo pide más sitio (`ADU.AppDecorService: requestIncreaseContentArea`), y AA no lo da. Abrir Maps desde la barra de AA no lo arregla.
- Un ciclo de foco de vídeo tras el cambio tampoco lo arregla.
- Con 950, 1000, 1100 y 1300 px, AA usa el diseño normal y el mapa se mueve («Centrar»).

Ahora `CarUi.splitAaWidth(width, height, railW, dpi)` da la mitad, pero nunca menos de 800 dp ni de 1,15 veces el alto, y deja al menos 560 px a nuestra pantalla. En el C10, AA 1014 px y la web 810.

**Aviso:** la primera vez que se pulsa «Partir pantalla» sale un aviso que hay que aceptar: se recomienda usarla con el coche parado, y se usa bajo la responsabilidad de quien la usa. Botones «Cancelar» y «Acepto». En la 0.2.31 se aceptaba una vez para siempre; desde la 0.2.32 se pide la primera vez de cada sesión con el coche (`CarUi.splitAccepted`, por sesión), y la pantalla partida también empieza sin partir en cada sesión.

| Línea | Significado |
|---|---|
| `CarUi: aviso de la pantalla partida` / `…: aceptado` / `…: cancelado` | El aviso |

## 27. Color fijo del panel lateral (0.2.33, 2026-10-10)

En «Ajustes» del coche, tarjeta «Pantalla», «Color del panel lateral»:
- **«Automático»:** como hasta ahora. Oscuro de noche (el gris de `Config.panelColor`) y claro de día, siguiendo el día y la noche de la sesión.
- **16 colores fijos** (`CarTheme.PANEL_COLORS`): negros y grises para fundirse con las barras del C10, blanco y colores. El elegido se queda igual de día y de noche.

Detalles:
- Se guarda en `Config.panelFixedColor` (0 = automático). `CarTheme.setFixedPanel` lo aplica a `panelColor`, al fondo del relay alrededor de AA (desde el primer fotograma, en `AaPassthroughSource`) y a la animación de carga.
- El texto y los iconos del panel (`CarTheme.navText`, `navTextDim`) pasan a claros u oscuros según la luminancia del color (`CarTheme.isDark`, umbral 0,18, donde el contraste con blanco y con negro es el mismo). Azul, verde azulado y verde van algo oscurecidos para que el texto blanco se lea y no se confundan con el acento del botón activo.
- Al elegir, `CarUi.applyPanelFixed` rehace la interfaz (`CarUi.rebuild`, lo mismo que al cambiar de día a noche) y vuelve a Ajustes con el elegido marcado.

Línea nueva: `CarUi: color de la barra fijo #RRGGBB` / `… automático (día y noche)`.

Probado en el coche virtual: azul (texto blanco), blanco (texto oscuro) y vuelta a «Automático».

Pruebas: `CarThemeTest` (color fijo de día y de noche, texto según el color, 16 colores distintos y opacos).

## 28. Aviso de versión nueva, panel a medida y datos en la barra (0.2.34, 2026-10-10)

- **Aviso de versión nueva (`UpdateCheck`).** El comprobador de Open Headunit miraba las releases de open-headunit. Ahora, en la pantalla principal, como mucho cada 6 h se consulta la última release de CharlysEV/headqlink (`/releases/latest`) y se compara número a número con la instalada (0.2.30 > 0.2.9). Si es más nueva, una fila «Hay una versión nueva: X» abre sus notas (Markdown pasado a texto llano, sin la sección «Aviso» ni el SHA) con «Descargar». **Novedades:** la primera vez que arranca una versión recién instalada (versionCode distinto del último visto; en la primera instalación no), se buscan las notas de su etiqueta `v<versión>` y se enseñan; si no hay red, se reintenta en el siguiente arranque. Pruebas: `UpdateCheckTest`.
- **Botones del panel a medida** (`Config.panelButtons`, CSV en su orden; «Auto» arriba y «Ajustes» abajo fijos). En Ajustes, una fila por botón con Sí/No y flechas. Se aplica al momento (`CarUi.applyPanelButtons` → `rebuild`).
- **Color libre y transparencia.** Bajo los 16 colores, dos barras de degradado («Tono», «Claro / oscuro»: `CarTheme.fromHue`, saturación fija 0,72) dan cualquier color (se guarda en el mismo `panelFixedColor`), y «Transparencia» (`Config.panelAlpha`, 0-100) funde el color con negro (`CarTheme.dim`): detrás del panel no hay otra cosa que el fondo negro, así que es lo que una transparencia real enseñaría. El texto del panel se decide con el color ya fundido.
- **Panel a la derecha** (`Config.panelRight`). `CarUi` coloca el panel con gravedad derecha, el contenido entre AA y el panel, y anuncia a AA la zona `(0, ancho − panel)`; con la pantalla partida, AA en `[0, aaW)` y nuestra pantalla hasta el panel. `AaPassthroughSource` arranca con `panelCarW = 0` y `aaCarW = carW − PANEL_W` para que el relay y los toques estén bien desde el primer fotograma. El deslizamiento para desplegar el panel minimizado parte del borde derecho.
- **Datos en la barra de iconos** (panel reducido): hora, temperatura exterior (`CarSensors.outsideTempC`, del tiempo; sin dato no se enseña) y batería del coche (`CarCloud.snapshot().status.socBest()`; sin cuenta no se enseña). Se refresca cada 30 s.

Líneas: `versiones: la última publicada es X · instalada Y · hay versión nueva`, `versiones: novedades de la X`, `CarUi: botones del panel [...]`, `CarUi: panel a la derecha`, `CarUi: transparencia del panel N %`.

Pruebas: `UpdateCheckTest`, `PanelSettingsTest`.

## 29. Pantalla partida con «Coche», marcadores web, avisos de radar por voz y «Sesiones» (0.2.35, 2026-10-10)

- **Pantalla partida con la sección Coche.** `CarUi.splitCapable` admite `car`; el aviso de responsabilidad solo es para el ocio (`splitNeedsWarning`): la sección Coche es información de conducción y vale en marcha. Las seis pestañas no caben en la mitad: la fila de pestañas de `CarHubScreen` va en un `HorizontalScrollView`.
- **Marcadores web propios** (`Config.webShortcuts`, JSON `[[nombre, url], …]`; sin nada guardado, los cuatro de serie). En el móvil, menú ⚙ › «Marcadores web» (solo Auto extendido): lista con subir, bajar y quitar, formulario para añadir (si falta «https://» o el nombre, `webShortcutsFrom` los completa) y «Los de serie». La rejilla de la pantalla Web se desplaza si hay muchos. Prueba: `WebShortcutsTest`.
- **Avisos de radar por voz** (`RadarVoice`, puro; `RoadInfo.step` lo llama con el radar más cercano delante): «Radar a 700 metros, límite 80» una vez por radar al entrar en los 700 m, y «Vas a 95, límite 80» una vez si a 400 m o menos se va más de 4 km/h por encima. Ajuste «Avisos de radar por voz» en Ajustes del coche (`Config.radarVoice`, sí por defecto). Línea: `vía: aviso por voz: …`. Prueba: `RadarVoiceTest`. Sin probar en marcha todavía.
- **«Sesiones» en Diagnóstico** (`SessionsView`, puro): las últimas 30 filas de `sessions.csv` (con sus cabeceras repetidas: vale la última antes de cada fila), una línea por sesión: fecha, duración, conexión, fps, cortes (y el mayor) y cómo acabó; «sin vídeo» si no llegó a verse. Prueba: `SessionsViewTest`.

## 30. «Descargar e instalar» la versión nueva desde la app, y «Buscar versión nueva» (0.2.36, 2026-10-10)

`UpdateCheck.Release` lleva el primer adjunto `.apk` de la release (`fromJson`, puro). En el diálogo de la versión nueva, «Descargar e instalar» (`UpdateInstaller`): baja el APK a `files/apk/` (se borra el anterior) con barra de progreso y abre el instalador de Android por `FileProvider` (`REQUEST_INSTALL_PACKAGES`). Si HeadQLink aún no puede instalar apps desconocidas (`canRequestPackageInstalls`), se abre ese ajuste y hay que volver a pulsar. «Ver en GitHub» queda en el botón del medio. Si la release no tiene APK, se abre GitHub como antes.

Menú ⚙ › «Buscar versión nueva»: consulta GitHub ahora (sin esperar las 6 h) y enseña el diálogo o «Ya tienes la última versión». Para pruebas por adb: `am start -n com.headqlink.app/com.headqlink.link.HomeActivity --ez no_autoconnect true --ez force_update_check true`. Líneas: `versiones: APK de la X bajado (N KB)`, `versiones: abro el instalador con …`.

La 0.2.37 es solo la versión de comprobación de este flujo (0.2.36 → 0.2.37 desde la app, en el móvil de pruebas).
