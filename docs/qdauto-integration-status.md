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
nuestra head unit ocupándolo (AA atiende una sola conexión). Después, el cierre de siempre. Si AA se cae durante la
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
  termina con el cable puesto, se vuelve a abrir con espera creciente (1, 2, 5, 10, 30 s).
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
lleva «Modo automático». También como extra `aa_server_start`. Se lee en cada decisión: vale en el acto.

**Qué hace el manual.** Nunca se pulsa nada (ni arrancar, ni parar, ni la capa, ni el botón de la notificación de AA):

| Situación | Qué pasa |
|---|---|
| Conectar, Bluetooth del coche, cable USB, coche anunciado | `LinkLifecycle` pide `CHECK_SERVER` (en cada arranque, sea cual sea el disparador, y otra vez con el coche anunciado: el último estado conocido no vale). `AaServerManual` mira en su hilo si 127.0.0.1:5277 contesta (conexión con 400 ms de tiempo máximo; si nuestra head unit está conectada no conecta: está encendido) |
| Encendido | Se sigue como siempre: el Self-Mode conecta solo, **también con el móvil bloqueado** (Bluetooth y cable no necesitan desbloquear) |
| Apagado cuando hace falta (lo anterior, una sesión que necesita AA —Self-Mode sin 5277— o el coche que vuelve con AA en pausa pero caído) | Notificación de prioridad alta **«Arranca el servidor de Android Auto»** («Android Auto › ⋮ › Iniciar servidor de la unidad principal»; al tocarla, los ajustes de AA, el mismo intent que la automatización, o su Info. de la app si no existe). La fila «Auto» dice «Esperando a que arranques el servidor de Android Auto» y, al tocarla (o el texto de debajo), abre lo mismo. Se mira cada **2 s** |
| El usuario lo arranca | Fuera la notificación, `AA server: listo (arrancado a mano)` y la fila «Servidor de Android Auto listo». Si una sesión esperaba a AA, se relanza el Self-Mode 1,5 s después (cuando el intento fallido ya ha terminado) |
| Esperando al coche con el servidor encendido | Se vuelve a mirar cada **60 s** (cada comprobación es una conexión que AA acepta y ve cerrarse; con AA conectado no se conecta) |
| Desconectar o fin del viaje | `ShutdownPlan.LEAVE_SERVER_ON`: se para nuestra head unit, el servidor no se toca y no hay guardián (no habría nada que apagar al desbloquear). 1,5 s después, si sigue contestando, **una** notificación «El servidor de Android Auto sigue encendido · Puedes pararlo en Android Auto › ⋮ › Parar servidor (recomendable si te conectas a una Wi-Fi pública)» (al tocarla, los ajustes de AA). Si ya estaba apagado, solo el log |
| Red de seguridad | Todas las entradas de `AaServerStarter` que pulsan algo lo comprueban: `runAndWait` (por `cannotRunReason`), `restartAndWait`, `requestStop`, `stopIfUnlocked`, `runPendingStop`, `onUnlock`, `requestStartOnUnlock` y el botón de la notificación de AA. `ACTION_AA_SERVER_RESTART` se rechaza. `AaRecovery` (servidor «sordo») dice «Auto no responde: arranque manual: para y vuelve a iniciar su servidor en Android Auto › ⋮». Un guardián que tuviera AA aparcado de antes del cambio lo suelta al desbloquear sin apagar el servidor (y avisa si sigue encendido) |

**Comprobación (`Requirements`).** Con el manual y AA 17.4+ en un modo Auto: «Accesibilidad» pasa a **Opcional**
(«Solo para el modo automático»); «Modo desarrollador» pasa a consejo si no se sabe (sin accesibilidad no se puede abrir
el menú para comprobarlo; si el servidor contesta, consta activo); fila nueva **«Servidor de Android Auto»** de
importancia «Información» (Encendido / Apagado / Comprobando…) con «Abrir AA» y «Modo automático», y la guía «Cómo
arrancar el servidor de Android Auto». Nada de esto cuenta en «Faltan N» ni bloquea «Conectar» (tampoco en la
configuración inicial, ni en el arranque por Bluetooth o por cable, que no pasan por la comprobación). El modo App sigue
pidiendo la accesibilidad como obligatoria (toques), elija lo que elija el usuario.

**Decisión pura (`AaServerPolicy`).** `onNeed(motivo, estado)` con el modo, el servidor (UP, DOWN, UNKNOWN), AA
conectado, móvil bloqueado y accesibilidad:

| | Servidor encendido / AA conectado | Apagado | Sin dato |
|---|---|---|---|
| Manual (bloqueado o no, cualquier motivo) | `NONE` | `ASK_USER` | `PROBE` |
| Automático, desbloqueado | `NONE` | `AUTOMATE` | `AUTOMATE` |
| Automático, bloqueado | `NONE` | `AUTOMATE_ON_UNLOCK` | `AUTOMATE_ON_UNLOCK` |
| Automático sin accesibilidad | `NONE` | `NO_ACCESSIBILITY` | `NO_ACCESSIBILITY` |

La vigilancia (`WATCH`) es solo del manual. `onEnd`: automático → `STOP_SERVER` (el cierre de siempre); manual →
`LEAVE_ON_NOTICE` (o nada si ya está apagado). La usan `LinkLifecycle` (sus decisiones del automático son las de antes,
línea por línea), `HomeActivity` (Conectar) y `AaServerManual`.

**Código.** `[hql]AaServerPolicy.java` (puro) y `[hql]AaServerManual.java` (nuevos); `Config` (`aa_server_start`,
`servidorAA=` en el resumen del arranque); `AaServerStarter` (el desvío y la red de seguridad, `aaSettingsIntent`);
`LinkLifecycle` (`Env.manualServer`, `CHECK_SERVER`, `LEAVE_SERVER_ON`); `LinkService`; `HomeActivity` (Conectar, fila
«Auto», ajuste); `AaGuardService`; `Requirements` y `Checklist`; `hql_dialog_video.xml`; textos en los cuatro idiomas;
manuales (sección «Sin accesibilidad», con el consejo de Obtainium).

**Qué buscar en el log** (todo con «ciclo:»):

| Línea | Significado |
|---|---|
| `servicio iniciado. … servidorAA=manual` | El ajuste |
| `conectar: arranque manual del servidor de Android Auto (sin accesibilidad): lo comprueba el enlace` | Conectar sin automatización |
| `arranque (Conectar): arranque manual del servidor de Android Auto (sin accesibilidad): compruebo si contesta y, si no, aviso para que lo arranques` | `CHECK_SERVER` (también «Bluetooth del coche», «cable USB del coche», «coche anunciado») |
| `AA server: encendido (127.0.0.1:5277 contesta; Bluetooth del coche; arranque manual)` | Encendido: se sigue |
| `W AA server: apagado (Conectar, móvil bloqueado): aviso «Arranca el servidor de Android Auto» y miro cada 2 s si 127.0.0.1:5277 contesta (arranque manual, sin accesibilidad)` | Se le pide al usuario |
| `AA server: listo (arrancado a mano)` | Lo arrancó el usuario |
| `relanzo Android Auto (Self-Mode) para el coche que espera (servidor arrancado a mano)` | Una sesión esperaba a AA |
| `cierre (a mano): paro Android Auto; su servidor queda encendido (arranque manual: HeadQLink no lo para)` | Desconectar |
| `W AA server: sigue encendido tras Desconectar (arranque manual: HeadQLink no lo para); aviso «El servidor de Android Auto sigue encendido» con cómo pararlo (Android Auto › ⋮ › Parar servidor)` / `AA server: apagado al cerrar (fin del viaje): nada que avisar` | El aviso del final |
| `arranque del servidor de Android Auto: manual (sin accesibilidad) (Ajustes de imagen)` | Cambio del ajuste |
| `AA server: no se puede automatizar: arranque manual: …` | La red de seguridad paró una automatización |

**Cómo comprobarlo en el móvil.**
1. Ajustes de imagen › Avanzado › «Manual (sin accesibilidad)» y desactivar la accesibilidad: la Comprobación no pide
   nada obligatorio; «Servidor de Android Auto · Apagado».
2. Conectar con el servidor apagado: notificación y fila «Auto»; tocarla, ⋮ › «Iniciar servidor de la unidad
   principal», volver: en ~2 s «listo (arrancado a mano)» y la fila «Servidor de Android Auto listo».
3. Con el servidor encendido y el móvil bloqueado, la llegada por Bluetooth o por cable conecta sin desbloquear.
4. Desconectar: notificación «sigue encendido»; tocarla abre AA (⋮ › «Parar servidor unidad principal»).
5. Que nunca aparezca «Arrancando Auto…» / «Cerrando Auto…».

**Pruebas en el PC.** `AaServerPolicyTest` (7: el manual nunca automatiza, sea cual sea el motivo o el bloqueo; con AA
conectado no hace falta nada; el automático de siempre; matriz completa modo × servidor × bloqueo × accesibilidad ×
motivo; Bluetooth o cable con el móvil bloqueado y el servidor encendido; el final; cadencia de 2 s y 60 s).
`LinkLifecycleTest` +5 (comprobación en cada disparador en vez de automatizar, otra vez con el coche anunciado, nada con
AA conectado o aparcado, vuelta de la pausa con AA caído, el servidor se queda encendido y nunca se aparca hasta
desbloquear). `RequirementsTest` +4 (accesibilidad opcional y fila del servidor, servidor apagado informativo y sin
bloquear, el modo App y AA < 17.4 no cambian, la oferta del manual en el automático).

**Limitaciones.** Sin probar en el móvil. Cada comprobación con el servidor encendido es una conexión TCP que AA acepta y
ve cerrarse al instante (por eso 60 s esperando al coche y ninguna con AA conectado); no se ha visto aún si AA lo anota o
muestra algo. Los nombres del menú de AA en inglés y portugués («Start head unit server», «Iniciar servidor da unidade
principal») se han escrito sin verlos en el móvil.

**Resultados en el PC.** `cmd /c ".\gradlew.bat :qdcore:test :app:testGithubDebugUnitTest :app:assembleGithubDebug
--console=plain"`: **BUILD SUCCESSFUL** (con el commit anterior, el del cable USB, incluido). `:qdcore`: 156/156 (sin
cambios). App: 2857 pruebas, 0 fallos y 1 saltada (la de `sh`); el paquete `com.headqlink.link`, 199/199.

---

## 16. Widget y botón de los ajustes rápidos (2026-10-06)

**Qué hay** (desde Android 8, `@bool/hql_widget_enabled`):

- **Widget «HeadQLink»**: 4x2 por defecto y redimensionable hasta 2x2. El botón grande (Conectar / Desconectar), el
  estado, el selector de conexión («Zona Wi-Fi», «Wi-Fi Direct», «Cable USB») y, en el 4x2 con altura, el modo («Auto» /
  «Extendido»). En 2x2, el botón, el estado y el icono de la conexión (al tocarlo, la siguiente). Colores: gris
  apagado; ámbar en curso (preparando, buscando, coche encontrado, sesión sin imagen, esperando a que vuelva); verde con
  imagen, con fps y Mbit/s; rojo con un problema (puerto 18463, red, Android Auto). La marca abre la app sin conectar.
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
cabe según `OPTION_APPWIDGET_*`. Esquinas del sistema, `previewLayout`, `targetCellWidth/Height` y `description`;
`previewImage` (inglés y español) para Android 8-11.

**Código.** `[hql]LinkGlance.java` (puro: del estado al color, botón, estado, detalle, tamaño y cuándo repintar),
`LinkWidgetViews` (RemoteViews, textos y `PendingIntent`), `WidgetUpdater`, `LinkWidget`, `QuickToggleActivity`,
`LinkControl`, `LinkTileService` y `WidgetShots` (capturas). Cambian `LinkService` (`ACTION_SET_LINK`), `HomeActivity`
(`LinkControl`, el menú, sin conectar sola desde el widget o el botón de los ajustes rápidos), `LinkState.preparing`,
`Config.listen`, `App` (arranca `WidgetUpdater` tras el primer desbloqueo) y `PreviewActivity` (`render=widget`).
Recursos `hql_widget_*` y `hql_w_*`, textos en los cuatro idiomas y manuales («Widget y botón de ajustes rápidos»).

**Vista previa.**

- En el PC: `cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest --tests *WidgetRender* -Ppreview"` deja
  `app/build/preview/widget_<estado>_<tamaño>.png` (apagado, buscando, conectado_wifi, conectado_usb, esperando y
  problema; 4x2 y 2x2), con las mismas `RemoteViews` que pinta el launcher.
- En el móvil: `adb shell am start -n com.headqlink.app/com.headqlink.link.PreviewActivity --es render widget` y
  `adb pull /sdcard/Android/data/com.headqlink.app/files/preview/`.

**Cómo comprobarlo en el móvil.**

1. ⚙ › «Añadir widget a la pantalla de inicio»: el launcher lo coloca; «Apagado» en gris. Redimensionarlo a 2x2.
2. Botón grande con todo listo: «Preparando…» (si hay que arrancar el servidor de AA), ámbar «Buscando el coche…» con la
   fila «Red» debajo y verde «Coche conectado» con las cifras cada 5 s. Con algo obligatorio pendiente, la «Comprobación».
3. En marcha, tocar otra conexión: en el log, `widget: conexión … (enlace en marcha: se aplica ya)` y `conexión: … → …
   (widget, con el enlace en marcha)`; la imagen vuelve por la nueva sin `AA: lanzando Self-Mode`.
4. Ajustes rápidos › lápiz › «HeadQLink» (o ⚙ › «Añadir botón…»): activo en marcha, con las cifras; mantenerlo pulsado
   abre la app sin conectar.
5. Con QDLink abierto: rojo, «Cierra QDLink».

**Pruebas en el PC.** `LinkGlanceTest` (13: apagado, preparando, buscando, coche encontrado por Wi-Fi o por cable, verde
con fps y Mbit/s y su formato por idioma, sesión sin imagen, esperando, problemas, un aviso viejo de la red no tapa el
verde, el selector con el cable por delante, el ciclo del 2x2, los tamaños y cuándo repintar). `LinkWidgetViewsTest` (5,
Robolectric: las `RemoteViews` aplicadas como lo hace el launcher, en cada estado y tamaño: textos, colores,
descripciones de accesibilidad y toques). `LinkWidgetActionsTest` (6: conexión y modo guardados y, en marcha, aplicados
con `ACTION_SET_LINK` y `ACTION_APPLY`; el botón grande desconecta en marcha y, sin configurar, abre la app; el botón de
los ajustes rápidos: activo, subtítulo y Desconectar).

**Limitaciones.** Sin probar en el móvil. Pasar de Wi-Fi Direct a la zona Wi-Fi en marcha no sale del grupo P2P del
coche (igual que al pasar al cable). En el 2x2 más pequeño (110 dp) el botón queda pequeño.

**Resultados en el PC.** `cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest :app:assembleGithubDebug --console=plain"`:
**BUILD SUCCESSFUL**. App: 2904 pruebas, 0 fallos y 3 saltadas (la de `sh` y las dos de dibujo, que piden `-Ppreview`);
el paquete `com.headqlink.link`, 246.
