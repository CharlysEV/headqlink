# Integración del motor QDAuto en headqlink (rama `qdauto`)

> **Estado:** diseño listo para implementar · **Fecha:** 2026-10-04 · **Base:** rama `qdauto` en `312e8ee5` (parche de
> seguridad: se conserva tal cual).
>
> **Objetivo:** llevar al fork el transporte SSPLink validado en el C10 (`qdauto/core`), añadir el modo **«Punto de
> acceso del móvil»**, **reconectar sin reiniciar Android Auto** cuando el coche corta y vuelve a anunciarse, y dejar
> **registros completos de cada viaje exportables sin adb**. Wi-Fi Direct y el motor original de headqlink siguen
> disponibles y funcionando como hoy.

**Rutas.** Relativas a `hql\` salvo indicación.

- `[hql]` = `app/src/main/java/com/headqlink/link/`.
- `[ohu]` = `app/src/main/java/com/andrerinas/openheadunit/`.
- `[core]` = `qdcore/src/main/kotlin/dev/qdauto/core/`. Es la copia en el fork de `qdauto/core/src/main/kotlin/dev/qdauto/core/`.

Los números de línea son los de hoy: `312e8ee5` y `qdauto/core` sin tocar.

**Reglas que este diseño respeta:**

- No se modifican `ref/` ni `qdauto/`; lo que haga falta se copia.
- No se toca el móvil desde los agentes: ni adb ni instalaciones.
- Commits en `qdauto`, con mensajes en español y la línea `Co-Authored-By` (§8).

---

## Índice

0. [Decisiones](#0-decisiones)
1. [Punto de partida](#1-punto-de-partida)
2. [El núcleo dentro del fork: módulo `:qdcore`](#2-el-núcleo-dentro-del-fork-módulo-qdcore)
3. [Ampliaciones de la API del núcleo](#3-ampliaciones-de-la-api-del-núcleo)
4. [El adaptador en el fork](#4-el-adaptador-en-el-fork)
5. [Modos de conexión: Wi-Fi Direct y punto de acceso del móvil](#5-modos-de-conexión)
6. [Reconexión sin reiniciar Android Auto](#6-reconexión-sin-reiniciar-android-auto)
7. [Registro de viajes](#7-registro-de-viajes)
8. [Plan de implementación (commits)](#8-plan-de-implementación)
9. [Plan de pruebas](#9-plan-de-pruebas)
10. [Riesgos, mitigaciones y preguntas abiertas](#10-riesgos-mitigaciones-y-preguntas-abiertas)
- [Anexo A. Claves nuevas de `Config`](#anexo-a-claves-nuevas-de-config)
- [Anexo B. Textos nuevos (ES/EN)](#anexo-b-textos-nuevos)
- [Anexo C. Ficheros nuevos y modificados](#anexo-c-ficheros-nuevos-y-modificados)

---

## 0. Decisiones

| # | Tema | Decisión |
|---|---|---|
| D1 | Cómo entra el núcleo | Módulo Gradle **`:qdcore`**, Kotlin/JVM, copia literal de `qdauto/core` (`src/main` + `src/test`, paquete `dev.qdauto.core`). Se compila con el **KGP 1.9.22 del fork** a bytecode Java 8, con `-Xjvm-default=all` y `-Xjdk-release=1.8`. No cambian Kotlin, AGP, Java 1.8 ni minSdk del fork. Solo hacen falta 3 retoques de compatibilidad (§2.4). |
| D2 | API nueva del núcleo | Toda aditiva, con valores por defecto que mantienen el comportamiento validado en el coche. Añade: finalización por frame, políticas de descarte de vídeo, puerta de escritura de vídeo, acceso y opciones del socket, ganchos de hilo, `PHONE_INFO` según `CAR_INFO`, escucha y configuración por sesión, filtros de IP, *bind* del `ServerSocket`, re-ACK y relevo de sesión. Todo con tests JVM. |
| D3 | Adaptador | `UdpDiscovery` + `SspSession` **se sustituyen** (no se envuelven) por cuatro piezas: **`QdLinkHost`** (dueño de `PhoneLink`), un **`QdSessionBridge`** por sesión, un **`SessionPort`** por sesión y **`VideoHub`/`VideoPipeline`**. `SspSession.java` y `UdpDiscovery.java` no se tocan: son el motor «original», elegible con el ajuste **«Motor de protocolo: QDAuto / original»**. |
| D4 | Propiedad del vídeo | La fuente de vídeo pasa de `SspSession` a `LinkService`, a través de `VideoHub`. Incluye `VideoSource`, encoder, relay GL, `CarUi` y el freno a AA. Se **engancha** a cada sesión TCP y se **desengancha** al caer, sin destruirse. |
| D5 | Modos de conexión | Ajuste **«Conexión con el coche»**: *Wi-Fi Direct* (actual y por defecto) o *Punto de acceso del móvil*. En el segundo modo: `P2pLink` no arranca; UDP sigue en `0.0.0.0:18463`; el `ServerSocket` se ata a la IP local de la interfaz por la que llegó el coche; solo se acepta TCP de la IP que se anunció; se rechaza loopback. |
| D6 | Reconexión | Cuando cae la sesión, AA, el decodificador, el relay, el encoder y `CarUi` siguen vivos **30 s** (ajustable). La reconexión sale con el **primer broadcast**: espera 0 ms, re-ACK cada ≥ 400 ms y relevo si el coche se reanuncia con una sesión «viva». En la sesión nueva van SPS/PPS + **IDR real**: en ≤ 1 frame con encoder propio, o con un ciclo de foco (~0,8 s) en el reenvío directo. |
| D7 | Registro | Log unificado y rotativo en `getExternalFilesDir("logs")`. Lleva la traza completa del núcleo, las líneas de `L`, el detector de cortes de radio y los resúmenes por sesión, más `sessions.csv`. Botón **«Exportar log»**: ZIP en `Descargas/HeadQLink` vía MediaStore + hoja de compartir. |
| D8 | Activación | Motor por defecto **«original»** hasta superar las pruebas LAN y con AA (§9). Después pasa a **«QDAuto»**, en un commit propio. La reconexión sin cortes tiene además un interruptor oculto (`qd_keep_video`) para apagarla en el coche sin recompilar. |
| D9 | Identidad ante el coche | Se mantienen los valores del fork, que también funcionan en un C10: ACK y `PHONE_INFO` con `DeviceName`/`PhoneName` = `Build.MODEL`, el UUID persistente y las dimensiones `cfg.videoSize(...)`. La variante de QDLink (vacíos y geometría de QDLink) queda como opción oculta. |
| D10 | Hilos | Los callbacks del núcleo llegan en el hilo de eventos de cada sesión. El ciclo de vida del vídeo se serializa en un hilo propio (`hql-video`). El camino caliente usa referencias `volatile` sin candados: AA → coche, encoder → coche y puerta GL. |

---

## 1. Punto de partida

### 1.1 El fork hoy

- `LinkService` es un servicio en primer plano (`connectedDevice|location`). En su primer arranque lanza una sola vez `P2pLink`, `UdpDiscovery` (`0.0.0.0:18463`) y `SystemMonitor` (`[hql]LinkService.java:227-239`). Por cada coche anunciado crea una `SspSession` y reenvía el ACK con cada broadcast hasta que llega el TCP (`:270-287`).
- `SspSession` lo hace todo:
  - servidor TCP aleatorio, AppStatus, handshake, heartbeat, watchdog y lista blanca;
  - arranque del vídeo y sus tres caminos: reenvío directo de AA, «último frame» con relay GL y encoder, patrón/app;
  - freno a AA, puerta GL, ABR y estadísticas.

  Al cerrarse **destruye el lado del vídeo** (`:948-991`) a través de `AaPassthroughSource.stop()` (`:538-570`): `headless=false`, superficie del decodificador, relay, `CarUi` (radio, `TripLog`, ruta) y modo noche en AUTO. AA (`AapService`/`CommManager`) sigue conectado, y `carGone` (30 s) apaga AA y su servidor (`:312-353`).
- `P2pLink` no deja ningún estado que lea nadie: es «lanzar y olvidar». UDP y TCP escuchan en todas las interfaces. Para el modo zona Wi-Fi basta, sobre todo, con **no arrancar `P2pLink`**.

### 1.2 Huecos que motivan el cambio

1. **Broadcasts ignorados con la sesión «conectada».** Pasa en el fork (`LinkService.java:272`) y en el núcleo (`PhoneLink.kt:254-262`). Tras un corte silencioso hay que esperar al watchdog: 10-13 s en el fork y 15 s en el núcleo.
2. **Cada caída tira el vídeo de AA:**
   - la sesión nueva necesita un ciclo de foco (0,7-0,9 s con la imagen congelada);
   - se parte el viaje (`TripLog`) y se para la radio;
   - el modo noche vuelve a AUTO;
   - `VideoTap.headless=false` entre sesiones, con riesgo de que se abra `AapProjectionActivity` en el móvil.
3. **`writeLock` compartido** (`SspSession.java:490-497`). Un IDR atascado bloquea heartbeat, respuestas y watchdog, y el watchdog solo se evalúa después de escribir el heartbeat (`:474-488`).
4. **Lector sin resincronización.** Además, `CarTrace.packet` está fuera del `try` (`:275`): un táctil de menos de 5 bytes lanza `BufferUnderflowException` en `ssp-session` y Android mata el proceso.
5. **`UdpDiscovery` se rinde en silencio** si el 18463 está ocupado, por ejemplo con QDLink abierto (`UdpDiscovery.java:41-44`).
6. **Registros incompletos:** sin rotación, sin motivo de cierre y sin resumen. Solo se exportan 600 líneas, al portapapeles (`LogActivity.java:20-51`).

### 1.3 Lo que se sabe del C10

Fuentes: viaje 1, en `docs\viaje-1-2026-10-04.md` y `trips\20261004-coche1\qdauto-20261004-175528.log`. Las líneas citadas como «log :N» son de este último.

- **Broadcasts:** cada ~500 ms desde `10.212.226.80:18464`, y **solo mientras no hay sesión**. Durante S1 (18:46:52 → 18:47:49) no llegó ninguno. Un broadcast con la sesión abierta significa, por tanto, que el coche la da por muerta.
- **Tiempos:** TCP 11 ms después del ACK; handshake completo en ~40 ms; `KEY_FRAME_REQ` ~340 ms después del primer frame.
- **Pellizco (log :3080-3746):**
  - último TX a las 18:47:44.420;
  - **5 s sin nada en ninguno de los dos sentidos**;
  - a las 18:47:49.413 llega de golpe una ráfaga de toques retenidos;
  - EOF a las 18:47:49.554;
  - primer broadcast a las 18:47:49.757.

  Nuestra app tardó hasta las 18:47:50.768 en conectar por su `retryDelayMs = 1000`. Con 0 ms habría conectado ~1 s antes.
- **Modo zona Wi-Fi** verificado con la pantalla apagada: 30,0 fps, hueco máximo de 46 ms.

---

## 2. El núcleo dentro del fork: módulo `:qdcore`

### 2.1 Por qué así

Verificado (auditoría 3):

- `qdauto/core` compila **sin cambios** con `kotlinc` 1.9.22, `-jvm-target 1.8`, contra `android-36/android.jar`: 0 errores, 194 clases, bytecode major 52.
- Con 1.9.22 pasan los 67 tests.
- No usa sintaxis de Kotlin 2 ni API de la stdlib posterior a 1.9.
- El escaneo de API Android (javap frente a `api-versions.xml`, más D8 `--min-api 16` y `dexdump`) solo encuentra dos problemas reales; se corrigen en §2.4.
- Un proyecto de prueba con el toolchain del fork (Gradle 8.13, AGP 8.13.2, KGP 1.9.22) consume el módulo desde Kotlin y desde Java con `assembleDebug` correcto.
- En este diseño se ha verificado además que un cliente Kotlin 1.9.22 en modo por defecto puede implementar parcialmente, y delegar con `by`, una interfaz compilada con `-Xjvm-default=all` y también con `all-compatibility`.

Alternativas descartadas:

- **(b) Jar compilado con Kotlin 2.2.20 y `-language-version 1.9`.** El modo está obsoleto, obliga a tocar el build de `qdauto` (prohibido), mete un binario en un repo AGPL y empeora la depuración.
- **(c) Subir el fork a Kotlin 2.x.** Rompe sus scripts: `KotlinJvmOptions` da error en KGP 2.2, y kapt también.
- **Subir el minSdk.** No aporta nada: el suelo real del fork ya es Android 13, porque hay llamadas de API 33 sin guarda, como `P2pLink.java:40`.

### 2.2 Estructura

```
hql/qdcore/
  build.gradle.kts
  README.md          ← copia de qdauto/core/README.md
  UPSTREAM.md        ← origen, fecha, sha256 de cada fichero copiado, lista de cambios locales (C1…C4)
  src/main/kotlin/dev/qdauto/core/**   ← 38 ficheros (discovery, h264, json, session, sim, util, wire)
  src/test/kotlin/dev/qdauto/core/**   ← 13 ficheros, 67 tests
```

`sim/` (`CarSim`) se queda en `main`: lo usan los tests y el módulo de pruebas `:qdsim` (§9.3).

### 2.3 Gradle (exacto)

`settings.gradle.kts`:

```kotlin
include(":app", ":contract", ":qdcore")          // C1
// include(":qdsim")                              // C9 (herramienta de pruebas en PC)
```

`qdcore/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")          // KGP 1.9.22 ya está en el classpath del buildscript raíz
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // all: los métodos con cuerpo de las interfaces (SessionListener, PhoneLinkListener, Callback) son métodos
        // por defecto de la JVM → Java y Kotlin 1.9 pueden implementarlas en parte.
        // jdk-release: compila contra la API de Java 8 (atrapa String.repeat, java.time de Java 9+, etc.).
        freeCompilerArgs.addAll("-Xjvm-default=all", "-Xjdk-release=1.8")
    }
}

dependencies {
    testImplementation(kotlin("test"))                                   // 1.9.22 (se descarga una vez)
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")     // en la caché
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.1") // en la caché
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
```

Notas:

- Si `-Xjdk-release=1.8` deja de estar soportado por el JDK del build, se quita y se confía en el escaneo de API (§2.5).
- Kotlin y Java tienen que quedarse en 1.8 a la vez. KGP rechaza objetivos distintos, y `buildJsonObject` es `public inline`, así que no se puede *inline* de 17 a 1.8.
- `kotlin-test` 1.9.22 no está en la caché de Gradle. El primer `:qdcore:test` lo descarga: Maven Central responde desde este PC, comprobado con un 200 en el `.pom`.

`app/build.gradle.kts`, en `dependencies`, junto a `implementation(project(":contract"))`:

```kotlin
implementation(project(":qdcore"))
```

**No se toca:**

- `kotlinOptions` del app (`jvmTarget = "1.8"`), KGP 1.9.22, AGP 8.13.2;
- `minSdk` 16 (github) y 21 (playstore);
- `kotlin-stdlib-jdk8:1.9.0` (Gradle resuelve la stdlib a 1.9.22);
- kapt y R8: el núcleo no usa reflexión, así que no hacen falta reglas `-keep`.

**Saltos de línea.** `qdauto` usa LF y el árbol del fork usa CRLF (`core.autocrlf=true`). Los ficheros copiados entran en el índice tal cual: se esperan avisos LF→CRLF, sin cambios de contenido. No se añade `.gitattributes`.

### 2.4 Los tres retoques de compatibilidad (los únicos del commit C1)

1. `[core]discovery/DiscoveryListener.kt:175-192`: `ConcurrentHashMap.compute` (API 24) sin `desugaring` fallaría en el primer broadcast en API < 24. Sustituir por:

   ```kotlin
   val (car, isNew) = synchronized(cars) {
       val prev = cars[key]
       val next = CarAnnouncement(
           address = address, sourcePort = port, uuid = uuid, name = name, rawJson = msg.jsonText ?: "",
           rawBytes = bytes, json = json, qdlinkCompatible = msg.qdlinkCompatible, warnings = msg.warnings,
           firstSeenMillis = prev?.firstSeenMillis ?: now, lastSeenMillis = now, count = (prev?.count ?: 0) + 1,
       )
       cars[key] = next
       next to (prev == null)
   }
   ```
   La deduplicación por (`DeviceUUID`, IP) y la semántica de `onCarFound`/`onCarSeen` no cambian.
2. `[core]session/PhoneSession.kt:123`: quitar `removeOnCancelPolicy = true`. Es API 21: con minSdk 16, `ScheduledThreadPoolExecutor.setRemoveOnCancelPolicy` fallaría al crear cada sesión en API 16-20. La sesión nunca cancela tareas sueltas y el temporizador se cierra con `shutdownNow`.
3. `[core]discovery/DiscoveryModels.kt:78`: `val host: String get() = address.hostAddress ?: address.toString()`. Es el único aviso de compilación: `android.jar` marca `getHostAddress` como `@RecentlyNullable`.

Con estos tres cambios la compilación queda sin avisos, el escaneo no ve nada por encima de API 16 salvo los *backports* de D8, y pasan 67/67 tests.

### 2.5 Reglas para el código nuevo de `:qdcore`

`:qdcore` es Kotlin/JVM y el lint de Android no lo revisa. Por eso queda prohibido en `:qdcore`:

- `java.util.function.*` y los métodos por defecto de `Map`/`Collection` de Java 8: `compute`, `merge`, `getOrDefault`, `putIfAbsent`, `forEach(BiConsumer)`, `removeIf`. Sí valen las extensiones *inline* de Kotlin: `getOrPut`, `getOrElse`, `forEach`.
- `AtomicInteger/Long.updateAndGet/getAndUpdate/accumulateAndGet` (API 24): usar bucles de CAS.
- `java.time`, `java.nio.file`, `Optional`, `CompletableFuture`, `String.join`, `Objects.requireNonNullElse` y cualquier cosa de Java 9+.
- Android.

Tras cada cambio en `:qdcore` hay que reconstruir el APK, y antes de un commit de núcleo pasar el escaneo de API.

- **Script.** El de la auditoría se copia en C1 a `hql/tools/apiscan.py`. Origen: `%USERPROFILE%\AppData\Local\Temp\claude\c--Users-usuario-Desktop-qd\8e712bd3-f746-40c3-afd2-ac7addc26f1e\scratchpad\compat\apiscan.py`, Python 3, sin dependencias.
- **Uso:** `javap -v -p -c` de las clases de `qdcore/build/classes/kotlin/main` → `python tools/apiscan.py javap.txt D:\Android\sdk\platforms\android-36\data\api-versions.xml 16`.
- **Comprobación alternativa:** `d8 --min-api 16` del jar + `dexdump -d`, y buscar `compute`, `setRemoveOnCancelPolicy`, `java/util/function` y `java/time`.

### 2.6 Sincronía con `qdauto`

- `UPSTREAM.md` guarda:
  - el sha256 de cada fichero copiado, para poder hacer `diff` contra `qdauto/core` (que no es repo git);
  - los retoques de §2.4;
  - la lista de ampliaciones (§3) con su commit.
- Todas las ampliaciones son aditivas. Los 67 tests originales siguen sin cambios, y los nuevos van en ficheros propios para poder llevarlos luego a `qdauto` si se quiere.

---

## 3. Ampliaciones de la API del núcleo

Todo lo que sigue se implementa **en `hql/qdcore`** (nunca en `qdauto`).

- Con los valores por defecto, el comportamiento es idéntico al validado en el coche.
- Cada apartado indica qué comportamiento del fork hace posible y qué tests lo cubren.
- Los cambios se reparten en tres commits: C2 (§3.1-3.2), C3 (§3.3-3.6) y C4 (§3.7-3.8).

### 3.1 Finalización por frame (C2)

**Para qué.** El freno a AA retiene el `MediaAck` de cada frame hasta que ese frame ha salido hacia el coche (`SspSession.java:775-811`). Si el núcleo descarta un frame sin avisar, AA se queda sin crédito: con ventana 2 deja de mandar vídeo. Hoy `SendQueue` descarta y cierra en silencio (`SendQueue.kt:136-143, 155-189`), y el `IOException` del escritor pierde el elemento en curso (`SocketWriter.kt:37-41`).

```kotlin
// [core]session/FrameCompletion.kt
enum class FrameOutcome {
    WRITTEN,   // write()+flush() completos
    DROPPED,   // política de vídeo: atasco, retraso (MAX_LAG), esperando IDR o válvula de memoria
    REJECTED,  // vídeo aún no pedido (sin VIDEO_CTRL{1}) o en pausa
    CLOSED,    // la sesión se cerró con el frame en cola, o ya estaba cerrada
    FAILED,    // falló el write() que lo escribía
}

class FrameDone(
    val outcome: FrameOutcome,
    val isKeyframe: Boolean,
    val payloadBytes: Int,       // Annex-B, sin las cabeceras 16 + 32
    val ptsUs: Long,
    val enqueuedNanos: Long,     // 0 si no llegó a encolarse
    val writeStartNanos: Long,   // 0 si no se escribió
    val writeEndNanos: Long,     // 0 si no se escribió
    val queuedFramesAfter: Int,  // frames de vídeo en cola en ese momento
)

fun interface FrameCompletion { fun onFrameDone(done: FrameDone) }

// PhoneSession: sobrecargas nuevas (las actuales delegan con completion = null)
fun sendFrame(annexB: ByteArray, offset: Int, length: Int, isKeyframe: Boolean, ptsUs: Long,
              completion: FrameCompletion?): Boolean
fun sendFrame(annexB: ByteBuffer, isKeyframe: Boolean, ptsUs: Long, completion: FrameCompletion?): Boolean
```

**Contrato.**

- **Exactamente una vez** por cada `sendFrame` con `completion != null`.
  - Si `sendFrame` devuelve `false`, el callback ya se ha llamado, en el mismo hilo y antes de volver, con `REJECTED`, `DROPPED` o `CLOSED`.
  - Si devuelve `true`, se llamará más tarde con cualquiera de los cinco resultados.
- **Nunca con candados internos tomados** (`SendQueue.lock`, `stateLock`).
- **Hilo:**
  - el productor, si es rechazo o descarte al encolar;
  - el escritor, si es `WRITTEN`, `FAILED` o un descarte `MAX_LAG` al sacar de la cola;
  - el que cierra, si es `CLOSED`.

  El callback tiene que ser rápido y no bloquear. Si lanza una excepción, se registra y no afecta al escritor.

**Implementación.**

- **`Outgoing`** (`SendQueue.kt:9-24`):
  - nuevos campos `completion: FrameCompletion?`, `@Volatile writeStartNanos` y `@Volatile writeEndNanos`;
  - un `AtomicBoolean done`, y `complete(outcome, queuedAfter)` que solo hace algo la primera vez.
- **`SendQueue`:**
  - `offerFrame(item, configFactory, dropped: MutableList<Outgoing>)` mete en `dropped` los elementos que quita `dropQueuedDeltas`/`dropSupersededFrames`;
  - `close(): List<Outgoing>` devuelve lo que quedaba;
  - `take(...)` devuelve los descartes `MAX_LAG` en una lista que le pasa el escritor (§3.2).

  Las finalizaciones se invocan siempre **después** de soltar el candado.
- **`PhoneSession.sendFrame`:**
  - si está cerrada → `CLOSED`;
  - si `!videoAllowed()` → `REJECTED` (además del contador);
  - completa `dropped` con `DROPPED`;
  - según el resultado de la oferta, completa el propio frame con `DROPPED` o `CLOSED`.
- **`SocketWriter.run`:**
  - tras `written(item)` (contadores y traza, como hoy) → `WRITTEN`;
  - con `IOException` → `FAILED` y después `onWriteError`, que cierra; `closeWith` completa el resto con `CLOSED`.
- **`closeWith`:** completa con `CLOSED` lo que devuelva `queue.close()`.

**Tests** (`FrameCompletionTest`):

- `WRITTEN` una vez por frame con un `FakeCar` que lee;
- `REJECTED` antes de `VIDEO_CTRL{1}`;
- `DROPPED` de los P mientras se espera el IDR;
- `DROPPED` de los P en cola por atasco (`BACKLOG`);
- `CLOSED` de lo encolado al cerrar;
- `FAILED` del frame en curso cuando el coche corta a mitad de un IDR (con `SO_SNDBUF` pequeño y un `FakeCar` que no lee);
- **estrés:** 2000 frames y un `close()` en un momento aleatorio, 50 repeticiones. Se comprueba que finalizaciones = frames y que ninguna se repite;
- el callback nunca se ejecuta con `queue.lock` tomado (un gancho de test comprueba `isHeldByCurrentThread`).

### 3.2 Políticas de descarte, consultas baratas y foto de E/S (C2)

```kotlin
enum class VideoDropPolicy { BACKLOG, MAX_LAG, NONE }

// SessionConfig (nuevos)
val videoDropPolicy: VideoDropPolicy = VideoDropPolicy.BACKLOG
val videoMaxLagMs: Long = 150
```

| | `BACKLOG` (actual, validada) | `MAX_LAG` (fork, modos con encoder) | `NONE` (fork, reenvío directo de AA) |
|---|---|---|---|
| Al encolar | Con ≥ `videoBacklogFrames` o ≥ `videoBacklogBytes` en cola: tira los P encolados y el entrante, espera IDR y pide uno (`BACKLOG`, mínimo 1 s entre peticiones). | No mira el atasco. | No mira el atasco. |
| Al sacar (escritor) | — | Si la cabeza es un P que lleva > `videoMaxLagMs` en cola: tira **todo** el vídeo encolado, espera IDR, reenvía SPS/PPS delante y pide IDR **forzado** (`BACKLOG`). Es `SspSession.java:829-845`. | — |
| Tras `startStream` (VIDEO_CTRL{1}) | Descarta hasta el primer IDR. | Igual. | Igual (el fork reenviaba P-frames antes del IDR: era un error). |
| Petición de IDR mientras espera el primero | Sí, como mínimo cada `minKeyframeRequestIntervalMs`. | Igual. | Igual (la app la limita con su política, §4.9). |
| Válvula de memoria de 32 MiB | Sí. | Sí. | Sí. |

**Consultas sin candado** (contadores `@Volatile` que `SendQueue` actualiza bajo su candado):

```kotlin
fun videoQueueFrames(): Int
fun videoQueueBytes(): Long
fun io(): IoSnapshot

class IoSnapshot(
    val atNanos: Long,
    val lastReceiveNanos: Long,      // FrameReader.lastActivityNanos
    val bytesReceived: Long,
    val writingSinceNanos: Long,     // 0 = no hay write() en curso
    val writingLabel: String?,       // p. ej. "VIDEO_IDR"
    val writingBytes: Int,
    val lastWriteEndNanos: Long,
    val bytesSent: Long,
    val videoQueueFrames: Int,
    val videoQueueBytes: Long,
    val controlQueue: Int,
)
```

`stats()` sigue igual, pero es caro: crea un objeto y toma candados. La puerta GL, que se consulta cada ≥ 4 ms, y el detector de cortes, cada 100 ms, usan estas consultas.

**Tests** (`VideoDropPolicyTest`, `IoSnapshotTest`):

- `NONE`: 200 frames con un coche que no lee → ninguno descartado, salvo los P previos al primer IDR;
- `MAX_LAG`: cabeza P de más de 150 ms → vaciado, SPS/PPS delante del IDR siguiente y petición forzada;
- `BACKLOG`: los tests actuales;
- `writingSinceNanos ≠ 0` durante un `write()` bloqueado;
- `lastReceiveNanos` avanza con cada mensaje.

### 3.3 Puerta de escritura de vídeo (C3)

**Para qué.** El freno del fork (`SspSession.java:858, 887-892`) solo escribe el frame siguiente cuando la cola del kernel ha bajado de 24 KB (espera máxima 250 ms, sondeo cada 2 ms). Ponerlo como espera *después* de escribir bloquearía al escritor único y con él heartbeats y respuestas. Por eso se hace **antes de cada escritura de vídeo** y **sin bloquear el control**.

```kotlin
fun interface VideoWriteGate { fun canWriteVideo(session: PhoneSession): Boolean }

// SessionConfig (nuevos)
val videoWriteGate: VideoWriteGate? = null
val videoWriteGateMaxWaitMs: Long = 250
val videoWriteGatePollMs: Long = 2
```

**Semántica.**

- El escritor sigue sacando **control primero** y lo escribe en cuanto llega.
- Cuando lo siguiente es vídeo (SPS/PPS o frame), pregunta a la puerta **sin el candado de la cola**:
  - si está cerrada, espera `pollMs` (despierta antes si llega control o se cierra la sesión) y vuelve a preguntar;
  - pasados `maxWaitMs` desde que empezó a esperar ese elemento, escribe de todos modos (registro `D`, como mucho cada 5 s);
  - si la puerta lanza una excepción, se trata como abierta y se registra.

Pseudocódigo del escritor (sustituye a `SocketWriter.kt:30-49`):

```kotlin
while (true) {
    val item = nextItem(dropped) ?: return            // null = cola cerrada
    dropped.forEach { it.complete(DROPPED) }; dropped.clear()
    item.writeStartNanos = now(); current = item
    try { out.write(item.bytes); out.flush() }
    catch (e: IOException) { item.complete(FAILED); onWriteError(e); return }
    item.writeEndNanos = now(); current = null; lastWriteEndNanos = item.writeEndNanos
    written(item); item.complete(WRITTEN)
    if (item.closeAfter) { onCloseAfter(item); return }
}

fun nextItem(dropped): Outgoing? {
    var waitStart = 0L
    while (true) {
        when (val r = queue.poll(dropped)) {          // bajo candado: control | cabeza de vídeo (MAX_LAG aplicado) | vacía | cerrada
            is Control -> return r.item
            Closed    -> return null
            Empty     -> queue.awaitAny()
            is Video  -> {
                if (gate == null) return queue.takeVideoHead(r.item) ?: continue
                if (waitStart == 0L) waitStart = now()
                if (gateOpen() || now() - waitStart >= maxWait) return queue.takeVideoHead(r.item) ?: continue
                queue.awaitControl(pollMs)              // vuelve antes si llega control o se cierra
            }
        }
    }
}
```

`takeVideoHead(expected)` saca la cabeza solo si sigue siendo `expected`. Puede haber cambiado entretanto por una oferta con descarte o por un cierre.

Cuando `poll` vacía la cola por `MAX_LAG`, hace cuatro cosas: deja `waitingForIdr` y `pendingConfig` puestos, devuelve los descartes en `dropped` y suma un «vaciado» a `SessionCounters`. Además el escritor llama a `requestKeyframe(BACKLOG, force = true)`, que entrega el evento en el hilo de eventos y no bloquea.

**Tests** (`VideoWriteGateTest`):

- con la puerta cerrada siguen saliendo los heartbeats y la respuesta a `KEY_FRAME_REQ`, y el vídeo sale tras ≤ `maxWait + poll`;
- con la puerta abierta el vídeo sale enseguida;
- una puerta que lanza excepción no bloquea.

### 3.4 Socket: acceso, TOS, configurador y cierre (C3)

```kotlin
val socket: Socket                                     // antes private; solo lectura (NetStat, IP local, log)
// SessionConfig (nuevos)
val trafficClass: Int? = null                          // fork: 0xA0 (DSCP CS5 → WMM AC_VI)
val socketConfigurator: ((Socket) -> Unit)? = null     // último paso de configureSocket
```

**`configureSocket`** (`PhoneSession.kt:483-497`):

- aplica `trafficClass` con `try` propio, porque en Windows y en algunos kernels se ignora;
- después llama a `socketConfigurator`;
- registra también `IP_TOS`.

**`closeWith`:** antes de `socket.close()` llama a `shutdownInput()` y `shutdownOutput()`, ignorando errores. `NetStat` duplica el descriptor (`ParcelFileDescriptor.fromSocket`), y mientras quede un duplicado abierto el kernel no cierra el socket ni manda el FIN al coche.

**Tests:**

- el configurador se llama una vez y antes del AppStatus;
- `socket` devuelve el socket aceptado;
- al cerrar se llama a `shutdownInput`/`shutdownOutput` antes de `close`. Se comprueba con un `Socket` espía, una subclase que cuenta las llamadas y delega en `super`, que entrega un `ServerSocket` de test cuyo `accept()` usa `implAccept(SpySocket())`. Además, `FakeCar` ve EOF.

### 3.5 Ganchos de hilo (C3)

```kotlin
enum class ThreadRole { READER, WRITER, TIMER, EVENTS }
val onThreadStart: ((ThreadRole) -> Unit)? = null      // SessionConfig
```

- Se invoca al empezar el cuerpo de cada hilo de la sesión:
  - `startThread` en lector y escritor;
  - las `ThreadFactory` del `ScheduledThreadPoolExecutor` y del `EventDispatcher`.
- El fork pasa `{ LowLatency.boostCurrentThread() }`, que da `URGENT_DISPLAY` solo con «Optimizaciones de latencia». Es lo que hoy hacen `ssp-session`, `video-sender` y `enc-drain`.
- El táctil llega ahora por el hilo de eventos, en lugar del lector; con este gancho ese hilo tiene la misma prioridad.

### 3.6 `PHONE_INFO` según `CAR_INFO`, cierre con motivo y relevo (C3)

```kotlin
// SessionConfig
val phoneInfoOverridesFor: ((CarInfo?) -> PhoneInfoOverrides)? = null   // si no es null, sustituye a phoneInfoOverrides
// PhoneSession
fun closeLocal(message: String)                                          // LOCAL con texto ("aplicar ajustes", "usuario"…)
// CloseReason.Kind
SUPERSEDED   // el coche volvió a anunciarse: dio la sesión por muerta (§3.7)
```

`InboundHandler.onCarInfo` y `PhoneSession.resendPhoneInfo` usan `phoneInfoOverridesFor(carInfo)`. El fork lo usa para mandar `cfg.videoSize(carW, carH, 0, 0)` en los ocho campos `Phone*`/`Mirror*`, igual que `SspSession.java:400-426`. Con `CarWidth/CarHeight ≤ 0` usa 800×480, como el fork.

**Test:** el `PHONE_INFO` tras `CAR_INFO` y el de `resendPhoneInfo()` llevan los valores del proveedor.

### 3.7 `PhoneLink`: escucha y configuración por sesión, filtros, *bind*, re-ACK y relevo (C4)

```kotlin
class PhoneLink(
    config: PhoneLinkConfig = PhoneLinkConfig(),
    listener: PhoneLinkListener = object : PhoneLinkListener {},
    sessionListener: SessionListener = object : SessionListener {},
    log: QdLog = QdLog.NONE,
    sessionListenerFactory: ((PhoneSession) -> SessionListener)? = null,   // NUEVO
)

data class PhoneLinkConfig(
    /* … campos actuales … */
    val sessionConfigFor: ((CarAnnouncement) -> SessionConfig)? = null,                // NUEVO
    val acceptFilter: ((InetSocketAddress?, CarAnnouncement) -> Boolean)? = null,      // NUEVO
    val mirrorBindAddressFor: ((CarAnnouncement) -> InetAddress?)? = null,             // NUEVO
    val reAckIntervalMs: Long = 0,                 // NUEVO; 0 = un solo ACK (QDLink)
    val supersedeOnRebroadcast: Boolean = false,   // NUEVO
    val supersedeMinSessionAgeMs: Long = 2_000,    // NUEVO
    val supersedeMinSilenceMs: Long = 1_000,       // NUEVO
)

interface PhoneLinkListener {
    /* … actuales … */
    fun onAckSent(car: CarAnnouncement, mirrorPort: Int, attempt: Int) {}               // NUEVO
    fun onConnectionRejected(from: InetSocketAddress?, car: CarAnnouncement) {}         // NUEVO
    fun onSessionSuperseded(old: PhoneSession, car: CarAnnouncement) {}                 // NUEVO
}

fun closeSession(message: String)        // NUEVO: como disconnect(), con closeLocal(message)
```

**Semántica.**

1. **Escucha por sesión.** En `acceptLoop` (`PhoneLink.kt:199`):
   - se crea un `ForwardingSessionListener` (`@Volatile target`, con *override* explícito de los 20 métodos);
   - se construye la `PhoneSession` con `SessionWatcher(a, forwarding)`;
   - se fija `forwarding.target = factory(session)`, o el `sessionListener` común si no hay fábrica;
   - y después `session.start()`. Antes de `start()` no puede llegar ningún evento.

   `SessionWatcher` deja de usar `by sessionListener` (`:304`) y delega en el de esa sesión. Así desaparece la carrera de `activeSession()` que se vio en la app de prueba (`SessionEvents.kt:55-63`).
2. **Configuración por sesión.** `sessionConfigFor(car)` se evalúa en `acceptLoop` justo antes de crear la sesión.
3. **`acceptFilter`.**
   - Se evalúa tras cada `accept`.
   - Si devuelve `false`: registro `W`, `onConnectionRejected`, `close()` del socket, y se sigue aceptando con **el tiempo que quede** de los 20 s.
   - Por defecto (`null`) se acepta todo, como hoy.
4. **`mirrorBindAddressFor(car)`.** Se evalúa en `connect()` y se pasa a `MirrorServer(port, bindAddress)`; `MirrorServer.kt` ya lo admite. Con `null` escucha en todas las interfaces.
5. **Re-ACK.** Con `reAckIntervalMs > 0`, en `onCarFound`/`onCarSeen`:
   - si hay un intento para **el mismo coche** sin sesión todavía,
   - y el último ACK tiene ≥ `reAckIntervalMs`,
   - se manda `discovery.sendAck(car, attempt.server.port, AckPolicy.QDLINK)` a la IP de **ese** broadcast.

   «Mismo coche» = `uuid` no vacío e igual, o misma IP. Es el «ACK en cada broadcast» del fork (`LinkService.java:285-286`): un ACK perdido ya no cuesta 20 s.
6. **Relevo (`supersedeOnRebroadcast`).** El C10 solo se anuncia sin sesión (§1.3). Si llega un broadcast del mismo coche con una sesión viva, y la sesión tiene ≥ `supersedeMinSessionAgeMs` y lleva ≥ `supersedeMinSilenceMs` sin recibir nada (`io().lastReceiveNanos`):
   - bajo el candado: `attempt = null`;
   - fuera del candado:
     - `onSessionSuperseded(old, car)`;
     - `old.closeWith(SUPERSEDED, …)`, que completa los frames con `CLOSED` y suelta los acks de AA;
     - `server.close()` y `ack?.cancel()`;
     - **`connect(car)` en el acto**, sin `retryDelayMs`.
   - `SessionWatcher.onClosed` sigue avisando `onSessionEnded(old, SUPERSEDED)`; su `endAttempt(a)` no hace nada porque `attempt !== a`.

   Los dos umbrales evitan cortar una sesión recién aceptada por un broadcast que ya estaba en el aire.
7. **El re-ACK y el relevo** solo se aplican a broadcasts que acepta `carFilter(car)`, el mismo filtro de la conexión automática. Un anuncio que llega por una interfaz rechazada no reenvía ACK ni corta nada.
8. **`retryDelayMs = 0`** se admite y es lo que usa el fork. La espera la gestiona `carFilter` con `QuickBackoff` (§4.4).
9. **`onAckSent`** reenvía `DiscoveryListener.Callback.onAckSent` (hoy `DiscoveryCallbacks` no lo hace, `PhoneLink.kt:289-301`).

**Tests** (`PhoneLinkReconnectTest`, con `CarSim` en 127.0.0.1 y puertos libres):

- **Fábrica:**
  - se llama una vez por sesión y antes de cualquier evento;
  - ningún evento de S1 llega al listener de S2, incluido `onClosed` de S1 cuando S2 ya existe;
  - un test por reflexión comprueba que `ForwardingSessionListener` sobrescribe **todos** los métodos de `SessionListener`, para que uno nuevo no se pierda en silencio.
- **Re-ACK:**
  - con `CarSim` configurado para ignorar el primer ACK, el segundo broadcast provoca el ACK nº 2;
  - el límite de 400 ms se respeta.
- **Relevo:**
  - S1 en vídeo, el coche deja de leer y de hablar;
  - un segundo `CarSim` con el mismo UUID se anuncia;
  - S1 se cierra con `SUPERSEDED`, empieza S2 y el tiempo entre el broadcast y el TCP es < 200 ms.
- **Sin relevo:** con la sesión hablando (silencio < umbral) o con la sesión recién creada.
- **`acceptFilter`:** rechaza la primera conexión (predicado por contador) y acepta la segunda dentro de la misma ventana.
- **`mirrorBindAddressFor`:** el `ServerSocket` escucha en 127.0.0.1.
- **`retryDelayMs = 0`:** reconecta con el broadcast siguiente al cierre.

### 3.8 `CarSim`: corte de radio simulado (C4)

Hay que añadir tres cosas a `CarSim`, que vive en `[core]sim/`:

- `pauseReading(ms: Long)` / `resumeReading()`: el lector deja de leer, y el `write()` del teléfono se bloquea cuando se llenan los búferes;
- `closeAbruptly()`: `soLinger(true, 0)` + `close()`, que manda un RST;
- `CarSimConfig.ignoreAcks: Int = 0`: descarta los N primeros `Broadcast_ACK` y sigue anunciándose, para probar el re-ACK.

`goSilent()` ya existe. `goSilent() + pauseReading()` reproduce el silencio en los dos sentidos del pellizco. Solo lo usan los tests y `:qdsim`. `:qdsim` usa `broadcastIntervalMs = 500`, el periodo del C10; el valor por defecto de `CarSim` es 1000.

### 3.9 Tests nuevos del núcleo (resumen)

| Fichero | Cubre |
|---|---|
| `session/FrameCompletionTest.kt` | §3.1, con estrés |
| `session/VideoDropPolicyTest.kt` | §3.2 `NONE` / `MAX_LAG` |
| `session/IoSnapshotTest.kt` | §3.2 consultas y foto de E/S |
| `session/VideoWriteGateTest.kt` | §3.3 |
| `session/SocketHooksTest.kt` | §3.4, §3.5 (orden y roles de `onThreadStart`) |
| `session/PhoneInfoProviderTest.kt` | §3.6 |
| `session/PhoneLinkReconnectTest.kt` | §3.7 |
| `EndToEndReconnectTest.kt` | 5 sesiones seguidas con `CarSim` + un «pipeline» de prueba que manda frames a la sesión enganchada vía fábrica. Mide tiempos y comprueba que cada sesión recibe SPS/PPS antes del primer IDR. |

Objetivo: **67 tests originales + ~30 nuevos**, todos en verde con `.\gradlew.bat :qdcore:test`.

---

## 4. El adaptador en el fork

### 4.1 Mapa de clases

| Clase (nueva) | Lenguaje | Responsabilidad |
|---|---|---|
| `[hql]QdLinkHost.kt` | Kotlin | Dueño de `PhoneLink` mientras vive el servicio. Construye la configuración (§4.12), aplica `PeerPolicy` y `QuickBackoff`, y pasa a `LinkService` (hilo principal) los eventos de coche visto, conectado, `W×H` y perdido. |
| `[hql]SessionConfigs.kt` | Kotlin | Construye el `SessionConfig` de cada sesión a partir de `Config` y del modo de vídeo vigente en el `accept` (§4.12), incluida la puerta del freno. |
| `[hql]QdSessionBridge.kt` | Kotlin | `SessionListener` de **una** sesión, creado por la fábrica. Traduce eventos del núcleo a `VideoHub`, `LinkState`, `CarTrace`, `PerfTrace` y `QdTrace`. Lleva el monitor de red de 50 ms, el detector de cortes, las estadísticas de 5 s y el resumen de la sesión. |
| `[hql]SessionPort.kt` | Kotlin | Fachada «amable para Java» sobre una `PhoneSession`: `sendFrame`, `sendConfig`, tamaño de cabecera, consultas de cola, `NetStat` por hilo, estadísticas de envío y la puerta de escritura del freno. |
| `[hql]VideoHub.java` | Java | Lo crea y posee `LinkService`. Es el único dueño de la `VideoPipeline` viva. Serializa crear, enganchar, desenganchar, parar, IDR y modo noche en el hilo `hql-video`. Guarda `carDark` entre sesiones. |
| `[hql]VideoPipeline.java` | Java | Port de la **mitad de vídeo** de `SspSession`: `startVideo`, `startPassthroughIfAny`, `linkReady`, `adaptBitrate`, `askKeyFrame`, *sinks* de encoder y AA, sin socket. `attach(port)` y `detach(port)`. |
| `[hql]AaAckBrake.java` (+ `AckSlot`) | Java | Freno a AA: `VideoTap.AckGate`, un `AckSlot` por unidad y el hilo `aa-ack-drain`, que suelta el ack cuando la cola del kernel ≤ 24 KB (≤ 250 ms). |
| `[hql]KeyframePolicy.java` | Java, sin Android | IDR del reenvío directo: antirrebote de 600 ms, un solo ciclo en curso, reintento si el ciclo fue rechazado y vigilancia del IDR. |
| `[hql]PeerPolicy.kt` + `[hql]NetIfaces.kt` | Kotlin, lógica pura | Clasificación de interfaces; qué broadcasts y qué TCP se aceptan; IP local para el `ServerSocket`. |
| `[hql]QuickBackoff.kt` | Kotlin, puro | Espera entre intentos: 0 ms tras una sesión que llegó a vídeo y 0,5 → 5 s tras sesiones fallidas. |
| `[hql]HotspotWatcher.kt` | Kotlin | Estado de la zona Wi-Fi: broadcast del sistema + reflexión + escaneo de interfaces (§5.5). |
| `[hql]QdTrace.kt`, `[hql]QdLogFile.kt` | Kotlin | Log unificado y rotativo. `QdLogFile` es el port de `qdauto/app/log/LogFileWriter.kt`. |
| `[hql]StallDetector.kt` | Kotlin, puro | Detecta silencio en los dos sentidos, el coche que no lee y el móvil congelado (§7.3). |
| `[hql]SessionSummary.kt` | Kotlin | Resumen por sesión, `sessions.csv` y resumen del servicio. |
| `[hql]HqlLogExport.kt` | Kotlin | «Exportar log»: ZIP + MediaStore + hoja de compartir (§7.5). |

**Modificadas:**

- `LinkService`, `Config`, `LinkState`;
- `HomeActivity`, `SetupActivity`, `LogActivity`, `SettingsScreen`, `Ui`;
- `SystemMonitor`, `P2pLink` (solo informa a `LinkState`);
- `VideoSource` (tres métodos por defecto, §4.9), `AaPassthroughSource`, `GlFrameRelay` (`redraw()`);
- `CarTrace` y `Proto` (arreglo del fallo), `L` (copia al log unificado), `PerfTrace`;
- `App.kt`, que llama a `QdTrace.init`;
- layouts y cadenas (Anexo C).

**Intactas:** `SspSession.java`, `UdpDiscovery.java` y el flujo de `AaGuardService`. Lo de `312e8ee5` no se toca: `exported=false`, accesibilidad limitada a AA.

Todas las clases nuevas van en el paquete `com.headqlink.link`, porque `Config`, `L`, `CarTrace`, `Proto`, `NetStat` y `VideoSource` son *package-private*. Kotlin puede usar clases *package-private* de Java del mismo paquete.

Las interfaces que implementa o pasa código Java (avisos de `QdLinkHost` a `LinkService`, `HotspotWatcher.Listener`, `KeyframeCallback`) se declaran como `interface`/`fun interface`, no como tipos función de Kotlin (`(T) -> Unit`). Una referencia a método Java no encaja con `Function1<T, Unit>`.

Las clases Kotlin nuevas son `internal`; Java las ve igual desde el mismo módulo. La única excepción es `QdTrace`, que se llama desde `App.kt`, de otro paquete: es pública y en su firma solo usa tipos públicos (`Context`, `String`, `TraceEvent`). Kotlin no deja exponer tipos Java *package-private* como `Config` o `L` en una firma pública.

### 4.2 Hilos

```
UDP 0.0.0.0:18463 ─ qd-discovery-rx ─► PhoneLink ─ qd-link-accept ─► PhoneSession Sn ─ qd-sN-reader / -writer / -timer
                                           │                               └─ qd-sN-events ─► QdSessionBridge(Sn)
                                           │                                                      │
LinkService (hilo principal) ◄─ Handler ─ QdLinkHost ◄─────────────── eventos de enlace ──────────┤
     │                                                                                            ▼ (cola ordenada)
     └─ posee ─ VideoHub ── hql-video ──► VideoPipeline (vive entre sesiones)
                                            ├ VideoSource (AaPassthroughSource / PatternSource / AppSource)
                                            ├ VideoEncoder (enc-drain) · GlFrameRelay (c10-gl) · CarUi (main)
                                            ├ AaAckBrake (aa-ack-drain) · VideoTap.sink (hilo de vídeo de AA)
                                            └ volatile SessionPort activo ──► PhoneSession.sendFrame/sendCodecConfig
```

**Reglas.**

1. Ningún callback del núcleo bloquea esperando al hilo principal.
2. Crear, enganchar, desenganchar y parar el vídeo **siempre** se hace en `hql-video`, en orden de llegada. Los callbacks que lo piden (`onVideoControl`, `onKeyframeRequested`, `onClosed`) solo encolan, y como salen en orden del mismo hilo de eventos, «enganchar» va siempre antes que «IDR de STREAM_START».
3. **Camino caliente sin candados:**
   - `VideoTap.sink` (hilo de vídeo de AA), `VideoEncoder.Sink` (`enc-drain`) y `GlFrameRelay.Gate.ready()` (`c10-gl`) leen el `volatile SessionPort active`;
   - si es `null`, el frame se descarta y se cuenta, y la ranura de ack se suelta en el acto.
4. `stop()` desde el hilo principal (`APPLY`, `onDestroy`) **solo encola**. El hilo principal no espera a que se cree el vídeo.
5. El táctil va directo a la fuente desde el hilo de eventos: lectura `volatile` de la *pipeline*, sin pasar por `hql-video`, para no añadir latencia. En el fork ya llegaba desde otro hilo distinto del principal.

### 4.3 Selección de motor

- **Clave:** `Config.LINK_ENGINE = "link_engine"`, con valores `"qdauto"` y `"original"`. Hasta C10 el valor por defecto es `"original"`; desde C10, `"qdauto"`.
- **Extra de adb:** `--es link_engine qdauto|original`.
- **Interfaz:** «Ajustes de imagen › Avanzado › Motor de protocolo», con la nota «Se aplica al volver a conectar».
- Se lee **una vez** al arrancar el transporte (`LinkService`, primer arranque). Los dos motores escuchan en 18463, así que nunca conviven.
- Un cambio con el servicio en marcha se aplica al pulsar Desconectar y luego Conectar. La interfaz lo dice: no se reinicia el transporte en caliente.

`LinkService`:

```java
private boolean transportStarted;          // sustituye a la comprobación "udp == null" (:227)
private QdLinkHost qd;                     // motor QDAuto
private VideoHub video;                    // solo motor QDAuto
private HotspotWatcher hotspot;            // solo modo zona Wi-Fi

private void startTransport() {
    if (Config.LINK_P2P.equals(cfg.linkMode())) { p2p = new P2pLink(this); p2p.start(); }
    else { hotspot = new HotspotWatcher(this, this::onHotspotState); hotspot.start(); }
    if (Config.ENGINE_QDAUTO.equals(cfg.linkEngine())) {
        video = new VideoHub(this, cfg);
        qd = new QdLinkHost(this, cfg, video, qdCallbacks);
        qd.start();                         // si 18463 está ocupado: aviso + reintento cada 5 s (§4.4)
    } else {
        udp = new UdpDiscovery(this); udp.start();   // camino original, sin cambios
    }
}
```

### 4.4 `QdLinkHost`

1. **Configuración.** Construye `PhoneLinkConfig` con:
   - `discovery = DiscoveryConfig(deviceName = cfg.deviceName(), deviceUuid = cfg.deviceUuid(), ackPolicy = AckPolicy.QDLINK)`;
   - `mirrorPort = RANDOM_PORT`, `acceptTimeoutMs = 20_000`, `autoConnect = true`, `reconnect = true`, `retryDelayMs = 0`;
   - `reAckIntervalMs = 400`;
   - `supersedeOnRebroadcast = true` (2000/1000 ms);
   - `sessionConfigFor = { car -> SessionConfigs.forCurrentSettings(cfg, ...) }` (§4.12);
   - `carFilter = { car -> peerPolicy.acceptBroadcast(car) && backoff.canAttempt() }`;
   - `acceptFilter = peerPolicy::acceptTcp`;
   - `mirrorBindAddressFor = peerPolicy::bindAddressFor`.

   Crea `PhoneLink(config, linkListener, NOOP, QdTrace.qdLog, factory = { s -> QdSessionBridge(s, host = this, hub = video, ...) })`.
2. **`start()`.** Llama a `link.start()`. Con `BindException` (18463 ocupado, típicamente QDLink abierto):
   - `LinkState.setNetwork(ERROR, «Puerto 18463 ocupado (¿QDLink abierto?)…»)`;
   - estado en la notificación;
   - reintento cada 5 s en el hilo principal hasta que funcione o se pare el servicio. Es una mejora frente a `UdpDiscovery`, que se rinde.
3. **Eventos de `PhoneLinkListener`**, todos pasados al `Handler` principal e ignorados si `stopping`:

   | Evento | Acción |
   |---|---|
   | `onCarFound`/`onCarSeen` | `CarTrace.udp(...)` (agrupa repeticiones). Si no hay sesión: `LinkState SEEN(name)` (o `RECONNECTING` con detalle «coche visto») y estado `hql_car_detected`. |
   | `onAckSent` | `CarTrace.tx("UDP ip:18464", ack)`. |
   | `onConnecting` | `QdTrace` (puerto e interfaz). |
   | `onSessionStarted(s)` | `cb.onCarConnected(peer)`: `hadSession = true`, `LinkState CONNECTED`, cancela `carGone`/`noCar`, estado `hql_car_connected`. |
   | `onSessionEnded(s, reason)` | Si `s` es la sesión actual: `backoff.onSessionEnded(bridge.reachedStreaming)` y `cb.onCarLost(reason)` (§6.2). |
   | `onAcceptTimeout` | `backoff.onAcceptTimeout()` y `L.w`. |
   | `onConnectionRejected`, `onExtraConnection`, `onSessionSuperseded` | `L.w` + `QdTrace`. |
   | `onError` | `L.e`. |

4. **`closeSession(why)`** (para `APPLY`) y **`pauseReconnect(ms)`**:
   - 1000 ms tras `APPLY`;
   - 3000 ms tras `APPLY` con renegociación de AA, para que `AapService` termine de desconectar antes de que la sesión nueva llame a `ensureAaConnected`.
5. **`stop()`:** `link.close()`. Cierra UDP, el intento y la sesión (`LOCAL "servicio parado"`).
6. **`isConnected()`:** `link.currentSession != null`. Lo usan la notificación y `LinkState`.
7. **Registro de sesiones vivas.** Mapa concurrente `id → (QdSessionBridge, SessionPort)`. Se rellena en la fábrica y se vacía en `onSessionEnded`. Lo usan:
   - la puerta del freno (`VideoWriteGate`, que recibe la `PhoneSession`);
   - `onSessionEnded`, para saber si la sesión llegó a vídeo;
   - el hilo principal, para distinguir «la sesión actual» de una ya relevada.

**`QuickBackoff`**, con reloj inyectable y tests:

- `onSessionEnded(reachedStreaming)`:
  - si llegó a vídeo: `failures = 0`, `notBefore = now`;
  - si no: `failures++`, `notBefore = now + min(5000, 500·2^(failures−1))`.
- `onAcceptTimeout()`: `notBefore = now`. El coche sigue anunciándose, así que se reintenta ya.
- `pause(ms)`: `notBefore = max(notBefore, now + ms)`.

### 4.5 `QdSessionBridge`: cada evento y su equivalencia con `SspSession`

**Al crearse** (fábrica, antes de `start()`):

- crea `SessionPort(session)`;
- registra el inicio en `QdTrace` (sesión `Sn`, coche, IP e interfaz local, modo, motor, plan de vídeo previsto).

**Con `onSessionStarted`** (lo llama el host):

- `CarTrace.beginSession(remote)`;
- `PerfTrace.beginSession()` + `PerfTrace.event(screen_on|screen_off, 1)`;
- arranca el hilo `net-monitor` (`MAX_PRIORITY`, cada 50 ms), igual que `SspSession.java:353-378`, con cuatro tareas:
  - `NetStat` propio y `PerfTrace.net(v)`;
  - detector de congelación (> 150 ms);
  - `StallDetector` (§7.3);
  - cada 5 s, las estadísticas (§4.11).

| Evento del núcleo (hilo `qd-sN-events`) | Acción | Equivalente en `SspSession` |
|---|---|---|
| `onTrace(e)` | `QdTrace.trace(e, sid)`. Si es IN: `PerfTrace.rx(msgType, size)`. Primer OUT de `Mirror/WhitelistAppOn`: `CarTrace.tx("APP", json)` + `PerfTrace.event("whitelist", 1)`. Control IN/OUT, salvo heartbeat y repeticiones de la lista blanca: `L.i("<- json")` / `L.i("-> json")`. | `:308`, `:432-433`, `:466-471`, `PerfTrace.rx` en `:273` |
| `onCarInfo(info)` | Guarda `carW×carH`. `host` → `LinkState.setCar(CONNECTED, "W×H")` y estado `hql_car_connected`. El núcleo ya ha contestado `PHONE_INFO` (proveedor §3.6) + `UPDATE_NOTIFY{5}`. | `:310-316` |
| `onVideoArgs(args)` | Guarda `VIDEO_ARGS`. El núcleo contesta `SPEECH_ARGS`. | `:317-322` |
| `onVideoSupportRequest` | — (lo contesta el núcleo: `{3,1}`). | `:323-325` |
| `onVideoControl(true)` | `hub.attachOrCreate(port, CarParams(car, args))` (§4.7). | `startVideo` `:641-750` |
| `onVideoControl(false)` | `L.i("VIDEO_CTRL PlayStatus=n (se mantiene el vídeo)")`. | `:329` |
| `onKeyframeRequested(r)` | Si `CAR_REQUEST`: `PerfTrace.event("idr_req", 0)`. Siempre: `hub.requestKeyFrame(port, r)` (§4.9). | `:331-335`, `askKeyFrame` |
| `onTouch(ev)` | `Proto.Finger[]` desde `ev.pointers` (`id`, `action` 1/2/3, `x`/`y`), `PerfTrace.touch("touch", …)` del dedo 0, línea `L.i` con la regla de `LowLatency` (solo inicio/fin) y `hub.touch(ev.action, fingers)`. | `onTouch` `:928-944` |
| `onAppMessage(m)` | Si `Global/DarkModeOn`: `hub.setCarDark(CarParams.appInt(m.json, "DarkModeOn") == 1)` + `L.i("coche en modo …")`. Cualquier otro: `L.i("<- APP json")`. | `onAppMessage` `:381-398` |
| `onDisconnectRequest` | `L.i` + `CarTrace.note`. El núcleo contesta `DISCONNECT_RSP{1}` y cierra tras escribirlo. | `:340-343` |
| `onLandModeRequest`, `onKey`, `onPhoneKey`, `onBtAddr`, `onGoInLinkApp` | Solo registro (el núcleo contesta `LAND_MODE_RSP`). | `:336-339`, ignorados |
| `onUnknownMessage` | `L.w`, como mucho 1 por segundo. | `:288`, `:300` |
| `onWatchdogWarning(ms)` | `L.w("coche callado ms")` + `QdTrace`. | — |
| `onClosed(reason)` | Para el monitor. `port.close()` (todos sus `NetStat`). `hub.detach(port, reason)`, o `hub.stop(...)` si `qd_keep_video=false`. `CarTrace.endSession()` + `note("SESION", reason)`. `PerfTrace.endSession()`. Resumen (§7.4). | `close()` `:948-991` |

`reachedStreaming` = la sesión llegó a `STREAMING` y escribió al menos un frame. Lo usan `QuickBackoff` y el resumen.

### 4.6 `SessionPort`

```kotlin
internal class SessionPort(val session: PhoneSession, private val cfg: Config) {
    val id: Int                                     // session.id
    @Volatile var closed = false; private set
    val stats = SendStats()                         // fps, kbps, máx. escritura y cola, descartes, vaciados, acks…
    // NetStat: uno por hilo usuario (v[] no es seguro entre hilos). Se abren perezosamente y se cierran en close().
    fun gateOutq(): Int      // hilo GL: SIOCOUTQ en bytes, -1 si no hay NetStat
    fun writerOutq(): Int    // hilo escritor del núcleo (VideoWriteGate del freno)
    fun drainOutq(): Int     // hilo aa-ack-drain
    fun monitorSample(): IntArray?   // hilo net-monitor (v[0..11])
    fun isStreaming(): Boolean = session.state == SessionState.STREAMING
    fun videoQueueFrames(): Int = session.videoQueueFrames()
    fun sendConfig(csd: ByteArray)                                   // session.sendCodecConfig
    fun sendFrame(data: ByteArray, off: Int, len: Int, key: Boolean, ptsUs: Long, done: FrameCompletion?): Boolean
    fun setHeader(w: Int, h: Int, fps: Int?, bitrate: Int?, gop: Int?)   // setVideoOverrides solo si cambia
    fun close()                                                      // closed = true + cierra los NetStat
}
```

- `sendFrame` **siempre** pasa al núcleo una finalización propia, que alimenta `stats` y `PerfTrace.frame(bytes, key, lagMs, writeMs, queued)`, y después encadena la de la *pipeline* si la hay (la `AckSlot` del freno):
  - `lagMs = writeStart − enqueued`;
  - `writeMs = writeEnd − writeStart`;
  - `queued = queuedFramesAfter`.

  Es la fila `frame` de `SspSession.java:866`. También alimenta el `Jitter("socket")` de las estadísticas.
- `NetStat` sale con `ParcelFileDescriptor.fromSocket(session.socket)`: un *dup* por hilo, como hoy (`gateNs`, `brakeNs` y el del monitor). Todos se cierran en `onClosed`; el `shutdown*` del núcleo (§3.4) garantiza el FIN aunque alguno tarde.

### 4.7 `VideoHub` y `VideoPipeline`

**`VideoPlan`.** Clase de valor con `equals` y un `toString` legible para el log. Define si la *pipeline* viva sirve para la sesión nueva:

- `mode`, `profileId`, `reencode`, `extended`, `passthrough`;
- `videoW`, `videoH`, `fps`;
- `carW`, `carH`, `argsW`, `argsH`, `argsFps`, `argsBitrate`, `argsInterval`, `encodingType`;
- `settings = cfg.videoFingerprint()`: `aa_brake`, `aa_window`, `fps`/`kbps`/`width`/`height` manuales, perfil H.264, `prepend`, `low_latency`, `enc_*`, `aa_dpi`, `dpi`, `pkg`.

Los tamaños se calculan **exactamente** como `SspSession.startVideo` (`:648-659`):

- `cfg.videoSize(cw, ch, aw, ah)`;
- en modos AA sin anchura manual, `cfg.videoProfile().videoSize(cw > 0 ? cw : 1920, ch > 0 ? ch : 882)`.

**`VideoHub`** (`hql-video`; todos los métodos públicos encolan):

| Método | Qué hace |
|---|---|
| `attachOrCreate(port, car)` | Calcula el plan. Hay tres casos: **reutiliza** la *pipeline* si `plan.equals(actual) && pipeline.healthy()`; si existe pero no vale, `stop("plan distinto: …")` y crea otra; si no hay, crea. En los tres casos acaba con `pipeline.attach(port)`. Registra `vídeo REUTILIZADO`, `RECREADO` o `CREADO` y, si reutiliza, el tiempo sin sesión. |
| `detach(port, reason)` | `pipeline.detach(port)` si `active == port` (comparación de identidad). Si la sesión la pide alguien que ya no es la actual, no hace nada. |
| `requestKeyFrame(port, r)` | Si `port` no es el activo, lo ignora. Si lo es, `pipeline.requestKeyFrame(r)` (§4.9). |
| `touch(action, fingers)` | Directo, sin cola: `pipeline?.source.touchMulti(...)`. Vale también si aún no se ha enganchado: el fork entregaba a la fuente en cuanto existía. |
| `setCarDark(dark)` | Guarda `carDark` (persiste entre sesiones) y lo aplica si hay *pipeline*. |
| `stop(why)` | `pipeline.stop()` y luego `pipeline = null`. |

**`VideoPipeline.create(ctx, cfg, plan, carDark)`.** Port literal de `startVideo` + `startPassthroughIfAny` (`SspSession.java:505-551, 641-750`):

1. `source = VideoSource.create(ctx, cfg)`, `setCarSize`, `setVideoSize`, `setTargetFps` (`aaFps` o `fps`, según la fuente) y `onCarDarkMode(carDark)` si se conoce.
2. **Reenvío directo** (`!source.usesEncoder()`):
   - `KeyframePolicy` nueva, con `noteRequested(now)`, porque `startPassthrough` ya pide un ciclo de foco;
   - si `cfg.aaBrake()`: `brake = new AaAckBrake()` y `VideoTap.setAckGate(brake.gate)`;
   - `Jitter("origen")`;
   - `source.startPassthrough(this::onAaUnit)`.

   `onAaUnit(data, off, len)` se ejecuta en el hilo de vídeo de AA; los datos ya llegan con el SPS recortado por `AaPassthroughSource`:
   - si `AnnexB.isCodecConfig(...)`: `csd = copy`, `active?.sendConfig(csd)` y `return`. Es SPS+PPS sueltos; la puerta del ack devuelve `false` porque no hay ranura;
   - `key = AnnexB.containsIdr(...)`. Si es IDR: `policy.onIdrSeen(now)`, y métrica «primer IDR tras enganchar»;
   - si `passthroughSize()` cambió: `active?.setHeader(w, h, …)`;
   - si `port = active` es `null`: el frame se descarta y se cuenta, sin ranura;
   - si no: `slot = brake?.newSlot()` y `port.sendFrame(data, off, len, key, VideoTap.frameTimestampUs, slot)`.
3. **Con encoder:** `VideoEncoder.Params` **idénticos** a `:666-707`:
   - perfil, `prepend`, `lowLatency`, `noRepeat`, `maxClocks`;
   - en «último frame»: `cbr`, intra-refresh = fps, GOP de 30 s (10 con `enc_no_ir`), `repeatAfterUs = 100_000`, bitrate del perfil y ABR;
   - `source.setLinkGate(gate)` con `ready() = gateReady()` y `submitted() = pendingEnc++`;
   - `Jitter("encoder")`.

   `Sink`:
   - `onCodecConfig(csd)`: `this.csd = csd`, `active?.sendConfig(csd)`;
   - `onFrame(buf, len, flagKey, pts)`: `encJitter.tick()`; decrementar `pendingEnc` sin bajar de 0; `key = AnnexB.containsIdr(buf, 0, len)` (si discrepa con `flagKey`, `L.w` una sola vez); `active?.sendFrame(buf, 0, len, key, pts, null)`. El núcleo copia el búfer, así que reutilizar `outBuf` es seguro.

   Después: `source.start(encoder.start(), videoW, videoH, fps, vp.toString())`, y si hay ABR, temporizador de 1 s en `hql-video`.

**`attach(port)`:**

- `active = port`;
- cabecera:
  - con encoder: `port.setHeader(videoW, videoH, carFps > 0 ? null : vp.fps, carBitrate > 0 ? null : vp.bitrate, carInterval > 0 ? null : 4)`, que reproduce `:709-714`;
  - en reenvío: `port.setHeader(passthroughSize() o videoW×videoH, null, null, null)`;
- si hay `csd` en caché: `port.sendConfig(csd)`. La sesión ya está en `STREAMING`, así que queda en cola **antes** del primer IDR aceptado;
- `pendingEnc = 0`, contadores de la puerta a 0, ABR con `bitrate = min(actual, inicial del perfil)`;
- `source.onReattached()`: en AA, `ensureAaConnected()` si `CommManager` no está conectado;
- el IDR lo pide `STREAM_START` justo después (§4.9).

**`detach(port)`:**

- `active = null`;
- la puerta GL devuelve `false`;
- los frames que siguen llegando se descartan y se cuentan;
- el ABR se congela;
- se registra cuántos frames se tiraron hasta el siguiente `attach`.

**`healthy()`:**

- con encoder: hubo `onFrame` en los últimos 2 s. Con «repetir el último frame» a 100 ms el encoder produce aunque la imagen esté quieta, salvo con `enc_no_repeat`, en cuyo caso no se exige;
- en AA: `CommManager.isConnected()` o reconexión de AA en curso.

Si no está sana, se recrea y se registra el motivo.

**`stop()`.** Equivale a la parte de vídeo de `SspSession.close()` (`:966-978`):

- `VideoTap.setAckGate(null)`;
- `brake.stop()`, que suelta todo;
- `source.stop()` y `encoder.stop()`;
- se cancela el ABR.

Fuentes y comportamiento por modo:

| Modo (`Config.mode`) | Fuente | Vídeo al coche | Política del núcleo | Control de flujo |
|---|---|---|---|---|
| `aa_ext` (**por defecto**) | `AaPassthroughSource(reencode, extended)` + `GlFrameRelay` + `CarUi` | encoder propio | `MAX_LAG` 150 ms | puerta «último frame» + ABR |
| `aa` + perfil con recodificación | `AaPassthroughSource(reencode)` + relay + splash | encoder propio | `MAX_LAG` | puerta + ABR (Máx./Alto) |
| `aa` + Básico (o `aa_reencode=false`) | `AaPassthroughSource` (reenvío) | H.264 de AA con SPS recortado | `NONE` | freno: ventana 2, `AckGate` + `VideoWriteGate` + drenado |
| `pattern` | `PatternSource`/`TestPattern` | encoder (GOP 1 s) | `MAX_LAG` | — |
| `app` | `AppSource` (VirtualDisplay) | encoder | `MAX_LAG` | — |

### 4.8 Freno a AA (reenvío directo)

Contrato de `VideoTap.AckGate` (`[ohu]aap/AapTransport.kt:835-841`):

- `hold(release) == true` obliga a ejecutar `release` **exactamente una vez**;
- `false` significa que AA confirma en el acto.

```java
final class AckSlot {                       // estados: PENDIENTE → RETENIDO(release) → HECHO
    boolean hold(Runnable release);         // PENDIENTE→RETENIDO: true · HECHO: false
    void release();                         // →HECHO; si estaba RETENIDO, ejecuta release una vez (con try/catch)
}
final class AaAckBrake {
    final VideoTap.AckGate gate = r -> { AckSlot s = lastSlot; lastSlot = null; return s != null && s.hold(r); };
    AckSlot newSlot();                      // hilo de vídeo de AA (el mismo que luego llama a gate.hold)
    // FrameCompletion de cada ranura:
    //   WRITTEN → cola de "aa-ack-drain": esperar port.drainOutq() ≤ 24 KB, como mucho 250 ms desde writeEnd,
    //             sondeo cada 2 ms (waitKernelDrain, :887-892) → slot.release()
    //   DROPPED / REJECTED / CLOSED / FAILED → slot.release() ya
    void stop();                            // suelta todo lo pendiente; el hilo termina
}
```

- **`lastSlot`:**
  - el *sink* lo fija **en cada unidad**: a la ranura nueva, o a `null` si es SPS/PPS suelto o no hay sesión;
  - `gate.hold` lo consume y lo pone a `null`;
  - así, el ack de un mensaje que no entregó frame nunca se cuelga de la ranura de un frame anterior.

  Es un campo confinado al hilo de vídeo de AA: el *sink* y `hold()` corren en ese hilo, en `AapTransport.dispatchVideo`. Cada ranura guarda el `SessionPort` por el que salió su frame, para el drenado.
- **Puerta de escritura** (`SessionConfig.videoWriteGate`, solo en reenvío con freno):
  - busca el `SessionPort` de esa sesión en el mapa `id → port` de `QdLinkHost`;
  - `val q = port.writerOutq(); return q < 0 || q <= 24 * 1024`, con espera máxima de 250 ms. Una única muestra por consulta; `-1` significa que no hay `NetStat`, y entonces la puerta está abierta.

  Con eso el siguiente frame no sale hasta que el anterior ha drenado, como `senderLoop`, pero el control no espera.
- **Ventana 2:** `VideoTap.setVideoWindow(brake ? aaWindow : 0)` sigue en `AaPassthroughSource.startPassthrough`. Solo se aplica cuando AA configura su canal de vídeo; al reutilizar la *pipeline* se conserva.
- **Entre sesiones:** el *sink* no crea ranuras → `hold()` devuelve `false` → AA confirma en el acto y nunca se atasca.
- **Limitación que se conserva y documenta:** solo se retienen los acks de unidades de un único mensaje (≤ 16 KB). En las fragmentadas, el ack del primer fragmento sale antes de que se complete la unidad (`delivered=false`). Arreglarlo en `AapTransport` queda fuera de alcance.
- **Estadísticas:** número de acks retenidos y retención máxima en ms (`PerfTrace.event("aa_ack", ms)`), como `:801-805`.

### 4.9 IDR en cada modo

| Modo | Origen del IDR | Latencia | Política |
|---|---|---|---|
| Con encoder (`aa_ext`, `aa` recodificando, `pattern`, `app`) | `encoder.requestKeyFrame()` (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) + `source.redraw()` (relay: `hasNew = true; tryDraw()` en el hilo GL). Así sale un frame nuevo aunque AA no cambie la imagen. | 1 frame (17-33 ms) + codificación (~5-10 ms) | Cada petición, sin antirrebote, como `:613-616`. SPS/PPS: núcleo (`requestConfigResend` + caché). |
| Reenvío directo (`aa` Básico) | Ciclo de foco de AA: `releaseVideoFocusForKeyframe` → 100 ms → `retakeVideoFocusForKeyframe` (`AaPassthroughSource.java:308-320`) | ~0,7-0,9 s con la imagen congelada | `KeyframePolicy`, ver abajo. |

`KeyframePolicy`, sin Android y con reloj inyectable:

- **`onRequest(now, reason)`:**
  - `SKIP` si hay un ciclo en curso o han pasado menos de 600 ms desde el último (`KEY_DEBOUNCE_MS`);
  - si no, `FIRE`: llama a `source.requestKeyFrame(cb)` y anota `lastAsk`.
- **`onLeverRefused(now)`:** el ciclo no pudo empezar (otro en curso o AA desconectado). Un **reintento único** a +150 ms, porque la palanca solo está ocupada los 100 ms del hueco. En el fork esa petición se perdía.
- **`onIdrSeen(now)`:** cierra el ciclo.
- **`watchdog(now)`:** cada 500 ms desde `hql-video`. Si la sesión espera IDR (el núcleo descarta P) y no ha llegado ninguno 1500 ms después del último ciclo, lanza otro ciclo como mucho una vez cada 1,5 s. Evita la pantalla negra cuando se pierde un ciclo.
- El núcleo pide `STREAM_START` al empezar (forzado) y `CAR_REQUEST` con el `KEY_FRAME_REQ` del coche (~340 ms tras el primer frame; el coche descarta el primer IDR). Las peticiones `BACKLOG` mientras espera el IDR llegan como mucho cada `minKeyframeRequestIntervalMs`, que en reenvío se pone a 1500. Todas pasan por la política.

`VideoSource` gana tres métodos por defecto, que en `PatternSource`/`AppSource` se quedan como están:

```java
default void redraw() {}                                   // AaPassthroughSource: relay.redraw() si hay relay
default void onReattached() {}                             // AaPassthroughSource: ensureAaConnected() + redraw()
default void requestKeyFrame(KeyframeCallback cb) {        // AaPassthroughSource: informa si el ciclo empezó
    requestKeyFrame(); if (cb != null) cb.onResult(true);
}
```

### 4.10 Puerta «último frame» y ABR

`gateReady()` se ejecuta en el hilo GL; es `linkReady`, `:557-580`, con la sesión enganchada:

```java
SessionPort p = active;
if (p == null || p.closed || !p.isStreaming()) { denyNoSession++; return false; }
if (pendingEnc.get() >= 3) { if (now - pendingSinceNs < 200 ms) { denyPending++; return false; } pendingEnc.set(0); }
if (p.videoQueueFrames() > 1) { denyQueue++; return false; }
int q = p.gateOutq(); if (q >= 64 * 1024) { denyKernel++; abrDenies++; return false; }
return true;
```

- `submitted()`: `pendingSinceNs = now; pendingEnc++`.
- **ABR** (`:588-609`), cada 1 s en `hql-video`, solo con sesión enganchada:
  - ≥ 5 cierres por la cola del kernel → bitrate ×0,8 (sin bajar del mínimo);
  - 2 s seguidos sin cierres → +1 Mbit/s (sin pasar del máximo);
  - lo aplica `VideoEncoder.setBitrate` + `PerfTrace.event("abr_kbps", …)`.
- **`GlFrameRelay.redraw()`** es nuevo: `h.post(() -> { hasNew = true; tryDraw(); })`.

### 4.11 Estadísticas (cada 5 s, mismo formato que `maybeLogStats`, `:894-926`)

```
TX 29.6 fps  5080 kbps  max write 18 ms  max lag 7 ms  descartados 0 (vaciados 0)
  freno AA: 148 acks retenidos, máx 41 ms                 ← solo en reenvío con freno
  puerta cerrada: encoder lleno 3, cola 0, kernel 12      ← si hubo cierres
  <jitter de la fuente> · <jitter del encoder> · <jitter del socket>
```

Destinos:

- `L.i`;
- `CarTrace.note("VIDEO", línea + " · cabecera WxH")`;
- `source.setStatus(línea)`;
- `LinkState.setVideo("30 fps · 5,1 Mbps")`;
- `QdTrace`, con una línea extra con `SessionStats` del núcleo (cola, latidos del coche, hueco máximo del coche) y la última muestra de `NetStat` (outq, rtt, retrans, cwnd).

### 4.12 Configuración de sesión (lo que replica al fork)

`SessionConfigs.forCurrentSettings(cfg, port-aware gate)` se evalúa en cada `accept` (§3.7). Comparación entre núcleo, fork y elección:

| Aspecto | Núcleo por defecto (QDLink) | Fork (`SspSession`) | **Elegido** |
|---|---|---|---|
| `SO_SNDBUF` | 256 KiB | 48 KB si `isAa(mode) && aa_brake` (también en `aa_ext`); si no, 192 KB (`:212-215`) | fork |
| `SO_RCVBUF`, TOS, keepalive, `TCP_NODELAY` | sistema, —, sí, sí | 6 MB, `0xA0`, sí, sí | fork (`receiveBufferBytes = 6 MiB`, `trafficClass = 0xA0`) |
| Prioridad de hilos | Java `MAX_PRIORITY` | `URGENT_DISPLAY` con `LowLatency` | los dos (`onThreadStart`) |
| Heartbeat | 1 s, luego cada 3 s | igual | igual |
| Watchdog | aviso 5 s, corte 15 s, tic 1 s | 10 s, evaluado tras el heartbeat | aviso 5 s, **corte 10 s**, tic 1 s; corte por `write()` bloqueado a 10 s |
| `WhitelistAppOn` | AUTO (`legal_app_watch == 1`) desde `VIDEO_SUP_RSP` + 1 s | siempre, desde `accept` + 1,5 s, cada 1 s | `ALWAYS` si `send_whitelist`, si no `NEVER`; desde `VIDEO_SUP_RSP` + 1 s. Con el C10 (`legal_app_watch = 1`) da el mismo efecto. |
| `DISCONNECT_REQ` | ni contesta ni cierra | `DISCONNECT_RSP{1}` + cierre a los 300 ms | `replyDisconnectReq = true`, `closeOnDisconnectReq = true` (cierra al escribir el RSP) |
| ACK y `PHONE_INFO` | `DeviceName`/UUID vacíos; geometría de QDLink | `MODEL` + UUID persistente; `cfg.videoSize` | fork (D9). Opción oculta `qd_phone_info=qdlink`. |
| `PHONE_INFO.Version`/`Platform`/`PlatformVersion` | "1.9.7"/0/SDK | igual | igual (`PhoneIdentity(appVersion = Config.LINK_PROTOCOL_VERSION, …)`) |
| `VIDEO_CTRL{1}` sin `VIDEO_ARGS` | lo ignora | arranca con valores por defecto | fork (`requireVideoArgsForPlay = false`) |
| `VIDEO_CTRL{≠1}` | sigue emitiendo | sigue emitiendo | igual |
| Eco del heartbeat `!BIN` y resincronización | sí | no | núcleo |
| Vídeo antes del primer IDR | se descarta | reenvío: se reenvía; encoder: espera IDR | núcleo |
| Política de descarte | `BACKLOG` | lag 150 ms (encoder) / nunca (reenvío) | `MAX_LAG` / `NONE` |
| SPS/PPS con `KEY_FRAME_REQ` | reenvío delante del siguiente frame | `needConfig` | núcleo (`resendCodecConfigOnKeyframeRequest`) |
| `minKeyframeRequestIntervalMs` | 1000 | — | 1000 (encoder); 1500 (reenvío) |
| Hilo del táctil | eventos | lector | eventos, con prioridad |

`PhoneIdentity` lleva:

- `screenLongSide`/`screenShortSide` de `getRealSize`;
- `brand = MANUFACTURER`, `model = MODEL`, `sdkInt = SDK_INT`;
- `appVersion = "1.9.7"`;
- `phoneUuid = cfg.deviceUuid()`, `phoneName = cfg.deviceName()`.

### 4.13 Tabla de equivalencias `SspSession` → motor QDAuto

| `SspSession` | Motor QDAuto |
|---|---|
| ctor `:155-173`: `ServerSocket` aleatorio (20 intentos), `SO_TIMEOUT` 20 s | `MirrorServer(RANDOM_PORT)`: 10001-65535, 50 intentos, `acceptTimeoutMs = 20_000` |
| `ackJson` `:184-199` + `Proto.udpAck` | `UdpCodec.buildBroadcastAck(port, MODEL, uuid, "")`: mismos campos en el mismo orden, longitud en caracteres, hex en mayúsculas |
| `accept` cierra el servidor | El núcleo lo deja abierto y cierra y registra las conexiones extra (`onExtraConnection`) |
| Opciones del socket `:211-222` | `SessionConfig` (§4.12) |
| `CarTrace.beginSession`, `PerfTrace.beginSession`, `startMonitor` | `QdSessionBridge` al empezar (§4.5) |
| AppStatus `!BIN` `:233-236` | `sendAppStatus` del núcleo, antes que nada |
| heartbeat, whitelist y watchdog `:237-240, 458-488` | Núcleo, con un temporizador propio que no depende de las escrituras |
| `readLoop`/`dispatch`/`onCommand` | `FrameReader` + `InboundHandler` |
| `sendPhoneInfo` `:400-426` | `PhoneInfoFactory` + `phoneInfoOverridesFor` |
| `startVideo`/`startPassthroughIfAny` | `VideoHub.attachOrCreate` → `VideoPipeline.create/attach` |
| `linkReady`, `adaptBitrate` | `VideoPipeline.gateReady`, ABR |
| `askKeyFrame` | `VideoPipeline.requestKeyFrame` + `KeyframePolicy` |
| `isKeyFrame` (NAL 5 o 7) | `AnnexB.isCodecConfig` → `sendConfig`; `AnnexB.containsIdr` → clave. Un SPS/PPS suelto ya no se marca como clave. |
| `enqueueFrame`/`waitingIdr`/`needConfig` | `SendQueue`: `waitingForIdr`, `pendingConfig`, `MAX_LAG` |
| `holdAck`/`releaseAck`/`releaseAllAcks` | `AckSlot` + `FrameCompletion` + `aa-ack-drain` |
| `senderLoop` + `waitKernelDrain` | Escritor único del núcleo + `VideoWriteGate` + drenado del freno |
| `maybeLogStats` | Estadísticas del puente (§4.11) |
| `onTouch` | `onTouch(TouchEvent)` → `Proto.Finger[]` → `touchMulti` |
| `close()` | `closeWith(reason)` + `onClosed` del puente; el vídeo se **desengancha** (C8) |

---

## 5. Modos de conexión

### 5.1 Ajuste

**Clave.** `Config.LINK_MODE = "link_mode"`, con los valores:

- `Config.LINK_P2P = "p2p"`: Wi-Fi Direct; es el valor por defecto para no cambiar nada a quien ya usa la app;
- `Config.LINK_HOTSPOT = "hotspot"`: punto de acceso del móvil.

Extra de adb: `--es link_mode hotspot`.

**Métodos.** `linkMode()`, `setLinkMode()`, `isHotspotMode()`. `summary()` añade `link=… engine=…`.

**Cuándo se aplica.** Al **arrancar el transporte**. Si cambia con el servicio en marcha, se aplica al reconectar, igual que el motor.

### 5.2 Interfaz

- **Configuración inicial, paso 2** (`hql_setup_mode.xml`): debajo de la lista de modos de vídeo se añade el bloque «Conexión con el coche» (`@+id/hql_link_options`) con dos opciones `hql_mode_option`:
  - **Wi-Fi Direct**: «El coche crea la red y el móvil se une. La zona Wi-Fi del móvil tiene que estar apagada.»
  - **Punto de acceso del móvil**: «El coche se conecta a la zona Wi-Fi de tu móvil (una vez, desde los ajustes Wi-Fi del coche). Es el modo probado con la pantalla apagada en el C10.»

  `onNext()`/`finishSetup()` guardan también `link_mode`.
- **Configuración inicial, paso 3** (requisitos):
  - **Zona Wi-Fi:**
    - fila «Zona Wi-Fi del móvil» con estado `OK` (activa, con interfaz e IP), `BUSY` (apagada) o `IDLE` (no se sabe);
    - botón «Abrir» que abre los ajustes de zona Wi-Fi (§5.5);
    - texto de guía con los pasos del coche (Anexo B).
  - **Wi-Fi Direct:**
    - fila «Zona Wi-Fi apagada» (`OK`, o `BUSY` con «Apágala para usar Wi-Fi Direct» y el botón «Abrir»);
    - el permiso `NEARBY_WIFI_DEVICES` solo se pide en este modo: `Ui.permsGranted(ctx)` pasa a depender del modo.
- **Inicio** (`HomeActivity`):
  - la línea de modo queda como «Android Auto ampliado · Punto de acceso del móvil»;
  - fila nueva **«Red»** (`LinkState.network`):
    - en zona Wi-Fi: «Zona Wi-Fi activa (swlan0 10.212.226.210)» o, en `ERROR`, «Zona Wi-Fi apagada: actívala», y al tocarla se abren los ajustes;
    - en P2P: «Wi-Fi Direct: en el grupo del coche (192.168.49.1)» o «buscando el coche». Lo informa `P2pLink.onInfo/discover`, con una llamada a `LinkState` y sin cambiar su lógica;
  - fila «Coche» con el estado nuevo `RECONNECTING`: `BUSY` y «Reconectando… (Android Auto en espera)»;
  - pistas (*hints*) por modo.
- **Ajustes de imagen › Avanzado** (`hql_dialog_video.xml`): grupo «Motor de protocolo», con «QDAuto (recomendado)» y «Original de headqlink», más la nota «Se aplica al volver a conectar».
- **Diagnóstico** (`LogActivity`):
  - botón **«Exportar log»**;
  - interruptor **«Prueba sin Android Auto (patrón)»**: guarda el modo actual en `mode_before_pattern`, pone `mode = pattern` y lo restaura al apagarlo. Sirve para las pruebas LAN sin adb;
  - línea de información con el motor y la conexión.
- **Ajustes desde el coche** (`SettingsScreen`): tarjeta «Conexión» de solo lectura con modo, motor, IP del coche e interfaz local.

### 5.3 `LinkService`

- `startTransport()` (§4.3): `P2pLink` **solo** en P2P y `HotspotWatcher` **solo** en zona Wi-Fi.
- En zona Wi-Fi nunca se llama a `bindProcessToNetwork`. `AaPassthroughSource.configureAa` mantiene `WifiLauncherMode.MANUAL`, de modo que open-headunit ni crea grupos ni toca la zona Wi-Fi.
- **Cerrojos:** se mantiene el trío `PARTIAL_WAKE_LOCK` + `WIFI_MODE_FULL_LOW_LATENCY` + `MulticastLock`, el mismo que validó la app de prueba en zona Wi-Fi.
- **Arranque en zona Wi-Fi con la zona apagada**, por ejemplo con la conexión automática por Bluetooth:
  - estado y notificación «Zona Wi-Fi apagada: actívala para conectar con el coche»;
  - `PendingIntent` a los ajustes de zona Wi-Fi.
  - El servicio sigue esperando, y el temporizador `noCar` de 5 min se mantiene.
- `SystemMonitor.sampleP2p()` (`SystemMonitor.java:176`) pasa a ser `sampleLinkIface()`:
  - lee `/sys/class/net/<iface>/statistics` de la interfaz de la sesión;
  - la interfaz la fija el puente con `SystemMonitor.setLinkIface(name)` y se obtiene con `NetworkInterface.getByInetAddress(socket.localAddress)`;
  - en P2P sin sesión sigue siendo `p2p0`;
  - fila `ifstat` en `PerfTrace`; la fila `p2p0` se conserva en P2P.

### 5.4 Filtro de pares e interfaz de escucha (`PeerPolicy`)

**Clasificación** (`NetIfaces.kindOf`, a partir de `qdauto/app/link/NetworkWatcher.kt:88-98`):

| Nombre | Tipo |
|---|---|
| `swlan*`, `ap*`, `softap*` | `HOTSPOT` |
| `wlan1…wlan9` | `HOTSPOT_O_STA` (zona Wi-Fi en Pixel y otros) |
| `wlan0` | `STATION` |
| `p2p*` | `P2P` |
| `rmnet*`, `ccmni*`, `seth*` | `MOBILE` |
| `tun*`, `ppp*`, `ipsec*` | `VPN` |
| `rndis*`, `usb*`, `ncm*` | `USB` |
| `bt-pan` | `BT` |
| `lo` | `LOOPBACK` |

Para cada broadcast se busca la interfaz IPv4 local cuya subred contiene la IP de origen (`InterfaceAddress` + prefijo).

| Origen del broadcast | Wi-Fi Direct | Punto de acceso |
|---|---|---|
| loopback, IPv6, multicast | **rechazar** | **rechazar** |
| `MOBILE`, `VPN` | rechazar | rechazar |
| fuera de toda subred local | aceptar + aviso (es lo que hace hoy el fork) | **rechazar** |
| `P2P` | aceptar | rechazar + aviso |
| `HOTSPOT`, `HOTSPOT_O_STA` | aceptar + aviso | aceptar |
| `STATION` (`wlan0`) | aceptar + aviso (pruebas en LAN) | aceptar + aviso (algunos móviles crean la zona en `wlan0`) |

- **Modo estricto.** Con `peer_strict = true`, oculto, los «aceptar + aviso» pasan a rechazo.
- **Registro.** Cada decisión se registra una vez por (IP, interfaz) en `QdTrace` y `L`.
- **TCP** (`acceptFilter`): se acepta solo si `remote.address == car.address` (la IP a la que se mandó el ACK) y no es loopback. Si no, se rechaza, se cierra y se registra, y se sigue esperando dentro de la ventana.
- **`ServerSocket`** (`mirrorBindAddressFor`):
  - en zona Wi-Fi, la IP local de la interfaz que contiene al coche. Así se «escucha en la interfaz de la zona Wi-Fi»: un cliente de datos móviles o de otra Wi-Fi no puede ni abrir conexión. El coche conecta a la IP origen del ACK, que el kernel toma de esa misma interfaz;
  - si no se encuentra, o si un intento con *bind* acaba en `onAcceptTimeout`, el siguiente intento usa el comodín y lo registra;
  - en Wi-Fi Direct, comodín, como hoy.
- **UDP** sigue en `0.0.0.0:18463`, en los dos modos. En Linux, atar el socket a una IP unicast deja de recibir broadcasts.

### 5.5 Saber si la zona Wi-Fi está activa (`HotspotWatcher`)

Tres fuentes, combinadas:

1. **Broadcast del sistema** `android.net.wifi.WIFI_AP_STATE_CHANGED`, extra `wifi_state`: 10 desactivándose, 11 desactivada, 12 activándose, 13 activada, 14 fallo. QDLink lo recibe como app normal. Se registra con `RECEIVER_NOT_EXPORTED` en API ≥ 33; es un broadcast del sistema.
2. **Reflexión:** `SoftApStateReader.read(ctx)` (`[ohu]utils/SoftApStateReader.kt`), que da `ENABLED`, `NOT_ENABLED` o `UNKNOWN` si la API oculta está bloqueada.
3. **Escaneo de interfaces** cada 3 s en el hilo `hql-net`: alguna interfaz `HOTSPOT` o `HOTSPOT_O_STA` levantada con IPv4 privada.

Resultado:

- `ON`, si dicen que sí (1) = 13, (2) = `ENABLED` o (3);
- `OFF`, si (1) ∈ {11, 14}, o (2) = `NOT_ENABLED` y (3) no encuentra nada;
- `UNKNOWN`, en otro caso.

Con el resultado se publica `LinkState.setNetwork(level, text)`, se registra cada cambio con hora en `QdTrace` y `CarTrace.note("WIFI", …)`, y se alimentan la notificación y la pantalla de inicio. En la interfaz hay además `HotspotWatcher.probe(ctx)` puntual, en `onResume` de Inicio y Configuración.

**Abrir los ajustes de zona Wi-Fi.** Se prueban en orden, cada uno con `try`/`ActivityNotFoundException`:

1. `Intent("android.settings.TETHER_SETTINGS")`;
2. `ComponentName("com.android.settings", "com.android.settings.TetherSettings")`;
3. `ComponentName("com.android.settings", "com.android.settings.Settings$TetherSettingsActivity")`;
4. `Settings.ACTION_WIRELESS_SETTINGS`.

Android normal no deja a una app encender la zona Wi-Fi: lo hace el usuario.

**Consejos en la guía:**

- banda de 5 GHz: el C10 usó 5745 MHz en Wi-Fi Direct, y 2,4 GHz comparte radio con el Bluetooth del coche;
- desactivar «Apagar la zona Wi-Fi si no hay dispositivos» (Samsung);
- aviso de que el coche podría usar los datos móviles.

### 5.6 Textos

En `values/hql_strings.xml` (inglés) y `values-es/hql_strings.xml` (español). Lista completa en el Anexo B. Se reescribe `hql_welcome_wifi`, que hoy solo menciona Wi-Fi Direct.

---

## 6. Reconexión sin reiniciar Android Auto

### 6.1 Línea de tiempo objetivo (modo con encoder, p. ej. `aa_ext`)

| t | Suceso |
|---|---|
| 0 | El coche cierra (EOF), o se detecta el relevo en un broadcast (§3.7). |
| +1 ms | Sesión cerrada. Frames con `CLOSED`. Ranuras de ack sueltas. `VideoHub.detach`: puerta GL cerrada, AA y encoder siguen vivos. `LinkState RECONNECTING`. `carGone` armado a 30 s. |
| ≤ +500 ms (+200 ms en el viaje) | Primer broadcast del coche → `connect` sin espera → `ServerSocket` (*bind* a la interfaz) → ACK. |
| +11 ms | TCP aceptado → AppStatus. |
| +~40 ms | `CAR_INFO` … `VIDEO_CTRL{1}` → `attachOrCreate`: el plan coincide y se **reutiliza** (~1 ms). Cabecera y SPS/PPS en caché en cola. `STREAM_START` → `requestKeyFrame` + `redraw`. |
| +60-80 ms | IDR codificado → SPS/PPS + IDR por la radio (60-120 ms si el IDR es de 300 KB). |
| **≈ +0,3-0,5 s** | El coche vuelve a pintar. Unos 340 ms después llega `KEY_FRAME_REQ` → otro IDR al instante. |

En reenvío directo (`aa` Básico) se suma el ciclo de foco: **≈ +1,1-1,4 s**.

**Métrica en el log:** `reconexión X ms` = del cierre de la sesión anterior al primer IDR escrito en la nueva. Desglosada en «broadcast», «ACK», «TCP», «VIDEO_CTRL» y «IDR».

### 6.2 Estados de `LinkService`

```
OFF ─Conectar─► SEARCHING ─broadcast─► SEEN ─TCP─► CONNECTED ─EOF/watchdog/relevo/DISCONNECT_REQ─► RECONNECTING
                    ▲                                    ▲                                              │
                    │                                    └──────────── sesión nueva (< 30 s) ───────────┤
                    └── (sin vídeo creado) ◄─────────────────────────────────────────────────────────────┤
                                                                         30 s sin coche (car_gone_ms) ──┴─► shutdownAll()
```

**`LinkState.Car`** gana `RECONNECTING`:

- solo se usa si hay vídeo vivo en `VideoHub`; si no, se pasa a `SEARCHING`, como hoy;
- `HomeActivity.render` lo pinta;
- `noCar` y `BT_CAR_GONE` lo tratan como «no conectado», así que se apaga si el Bluetooth del coche desaparece, que es lo correcto.

**`carGone`:**

- si en ese momento hay sesión o un intento en marcha, se rearma 5 s en lugar de apagar;
- si no, `shutdownAll()`, sin cambios: aparca AA si el móvil está bloqueado y para AA y su servidor. Después `onDestroy` → `video.stop("servicio parado")`.

  El orden se conserva. `AaGuardService.park` pone `headless=true` antes de que `AaPassthroughSource.stop()` consulte `parked`.

**`ACTION_APPLY`** (`:209-225`):

- `qd.closeSession("aplicar ajustes")` + `video.stop("ajustes")`;
- con `EXTRA_AA_RENEGOTIATE`, igual que hoy (`ACTION_DISCONNECT` + `ACTION_STOP_SELF_MODE`) y además `qd.pauseReconnect(3000)`; si no, `pauseReconnect(1000)`.

**`HomeActivity.connect()`** manda `ACTION_APPLY` también en el primer arranque. Sin sesión ni vídeo no hace nada, igual que hoy con `session == null`.

### 6.3 Qué vive entre sesiones

| Pieza | Entre sesiones (≤ 30 s) |
|---|---|
| `AapService`/`CommManager` (AA) | Conectado, sin cambios. |
| `VideoTap.headless` | `true` todo el rato: nunca se abre la proyección en el móvil. |
| `VideoTap.sink` / `AckGate` / ventana | Puestos. Sin sesión: frames descartados y acks inmediatos. |
| Decodificador de AA + superficie (`ImageReader` o relay) | Vivos: AA sigue pintando y la imagen está al día al volver. |
| `GlFrameRelay` | Vivo. La puerta devuelve `false`: no dibuja y reintenta cada ≥ 4 ms, con coste despreciable. |
| `VideoEncoder` | Vivo. «Repetir el último frame» produce ~10 fps que se tiran. SPS/PPS en caché. |
| `CarUi` (radio, `TripLog`, ruta, `RoadInfo`), `SplashUi` | Vivos: no se parte el viaje ni se corta la radio. |
| Modo noche de AA (`carDark`) | Se conserva (en `VideoHub`). |
| ABR | Congelado. Al enganchar: `min(actual, inicial)`. |
| `PerfTrace` / `CarTrace` | Cierre de la sesión (CSV nuevo por sesión, como hoy). Diario continuo. |

### 6.4 Primer IDR en la sesión nueva

Lo genera siempre el `STREAM_START` del núcleo, que llega al enganchar.

- **Con encoder:**
  1. `requestKeyFrame()` + `redraw()`;
  2. el SPS/PPS en caché ya va en cola;
  3. el núcleo descarta los P hasta el IDR (`waitingForIdr`);
  4. el IDR sale con el SPS/PPS delante, si no lo estaba ya, por `pendingConfig`.

  Si `enc_no_repeat` está activo y la imagen no cambia, `redraw()` garantiza el frame.
- **Reenvío directo:**
  1. el SPS/PPS recortado en caché se reenvía;
  2. `KeyframePolicy` lanza un ciclo de foco;
  3. los P de AA se descartan hasta que llega su IDR (los acks se sueltan);
  4. el vigilante de §4.9 repite el ciclo si en 1,5 s no hay IDR.
- **Patrón / app:** igual que con encoder (GOP de 1 s de todas formas).

### 6.5 Casos límite

| Caso | Comportamiento |
|---|---|
| El coche vuelve con otro `CAR_INFO`/`VIDEO_ARGS`, u otro coche | El plan no coincide → `stop` + `create` (registro «vídeo recreado: plan distinto (…)»). |
| AA se desconectó durante la espera | `healthy()` falso o `onReattached()` → `ensureAaConnected()`, que lanza Self-Mode. Si el servidor de AA no responde, sigue actuando `AaRecovery`. |
| El encoder murió (por ejemplo, MediaCodec reclamado) | `healthy()` falso → se recrea al enganchar. |
| Broadcast antiguo justo después de aceptar | No hay relevo: la sesión tiene < 2 s o el coche ha hablado en el último segundo. |
| Silencio de radio sin FIN y el coche se reanuncia | Relevo al primer broadcast que cumpla los umbrales (en el peor caso ~1 s tras la ráfaga). |
| La zona Wi-Fi se apaga | La sesión muere (EOF o watchdog) → `RECONNECTING` + aviso «Zona Wi-Fi apagada» → a los 30 s, `shutdownAll`. |
| El usuario pulsa «Desconectar» | `shutdownAll(true)`, como hoy. |
| `DISCONNECT_REQ` | `RSP{1}` + cierre → espera de 30 s, por si el usuario vuelve a abrir la proyección en el coche. |
| 18463 ocupado | Aviso y reintento cada 5 s (§4.4). |
| Dos coches | Solo uno con sesión. Los broadcasts de otros se registran y se ignoran mientras hay sesión. |
| `qd_keep_video = false` | Comportamiento del fork: `hub.stop()` al cerrar cada sesión. |

---

## 7. Registro de viajes

### 7.1 Ficheros (`/sdcard/Android/data/com.headqlink.app/files/`)

| Ruta | Contenido | Rotación y retención |
|---|---|---|
| `logs/qd-AAAAMMDD-HHMMSS[-pN].log` | **Log unificado** (§7.2), uno por arranque del proceso | Partes de 32 MiB. Se conservan ≤ 40 ficheros y ≤ 300 MiB. |
| `logs/sessions.csv` | Una fila por sesión (§7.4) | Se recorta a las últimas 2000 filas. |
| `headqlink-*.log` (`L`) | Sin cambios | Nuevo: ≤ 30 ficheros y ≤ 100 MiB, al iniciar. |
| `car/car-*.log` (`CarTrace`) | Con QDAuto: resumen (UDP, ACK, sesión con motivo, VIDEO 5 s, avisos). Con el original: como hoy. | Nuevo: ≤ 30 y ≤ 100 MiB. |
| `perf/perf-*.csv` (`PerfTrace`) | Sin cambios | Nuevo: ≤ 60 y ≤ 200 MiB. |
| `logcat/`, `crash/` (`LogcatCapture`) | Sin cambios | 10 × 10 MB (ya existe); `crash/` ≤ 50. |

La retención la aplica un `LogRetention.prune(dir, prefix, maxFiles, maxBytes)` común al iniciar cada registro.

### 7.2 Log unificado (`QdTrace` + `QdLogFile`)

**Escritor.** `QdLogFile` es el port de `qdauto/app/log/LogFileWriter.kt`:

- hilo `hql-logfile` con prioridad `BACKGROUND`;
- cola acotada de 200 000 líneas, que cuenta las pérdidas;
- escritura por lotes con `BufferedWriter` de 64 KiB;
- `flush` cuando la cola se vacía y `flush(timeout)` bajo demanda;
- marca de tiempo con `ThreadLocal<SimpleDateFormat>`, sin `java.time`.

**Nunca se escribe en un hilo de protocolo.**

`QdTrace.init(ctx)` se llama en `App.onCreate` junto a `LogcatCapture.start`. Cabecera del fichero: versión, `BuildConfig.GIT_SHA`, modelo, Android, `Config.summary()`, modo de conexión y motor.

| Fuente | Formato de la línea |
|---|---|
| `QdLog` del núcleo (descubrimiento, enlace, `MirrorServer`, sesión), todos los niveles | `HH:mm:ss.SSS I/QD/S2 [qd-s2-reader]: texto`. `WARN`/`ERROR` también van a `L`, y de ahí a `CarTrace`. |
| `TraceEvent` (cada mensaje IN/OUT) | `HH:mm:ss.SSS T/S2 <- CAR_INFO (412 B) {json completo ≤ 4096 car.}` + volcado hex ≤ 4096 B. Táctil: `T/S2 <- TOUCH (41 B) action=2 N=2 [id=0 move x=… y=…] …` + hex completo, con los bits crudos de los floats. Vídeo, una línea por frame: `T/S2 -> VIDEO_IDR (315112 B) … 1920x882 app=1 ang=90 or=1 cola=3ms pts=…`. |
| `L` (copia) | `L.write()` añade `QdTrace.forkLine(level, msg)` → `HH:mm:ss.SSS I/HQL: texto` (cola no bloqueante). |
| Puente | Inicio y fin de sesión con motivo; estadísticas de 5 s con `SessionStats` y `NetStat`; decisiones de `PeerPolicy`, `QuickBackoff` y relevo; vídeo creado, reutilizado, recreado o parado; ciclos de foco pedidos, rechazados y servidos; primer IDR tras enganchar. |
| Red | Lista de interfaces (nombre, IP/prefijo, tipo) al empezar y acabar cada sesión y en cada cambio. Estado de la zona Wi-Fi y del grupo P2P. Wi-Fi cliente, si la hay (frecuencia, velocidad, RSSI): un canal distinto implica MCC. |
| Sistema | Pantalla encendida, apagada o desbloqueada; escaneos Wi-Fi (`scanReceiver`); estado del Bluetooth del coche (A2DP/HFP); térmica, ahorro de energía y doze, que ya muestrea `SystemMonitor`. |

Volumen estimado: 10-20 MiB por hora conduciendo, sobre todo vídeo y táctil.

### 7.3 Cortes de radio: detección y registro (`StallDetector`)

Se evalúa cada 100 ms, en el hilo `net-monitor` de la sesión, con:

- `io()` del núcleo;
- la muestra de `NetStat`: `v[0]` outq, `v[1]` rtt, `v[3]` unacked, `v[4]` retrans, `v[5]` cwnd, `v[9]` tiempo limitado por el receptor, `v[11]` ventana del coche;
- el reloj del propio hilo.

**Definiciones:**

- **RX parado:** `now − lastReceiveNanos ≥ 400 ms`.
- **TX atascado:** hay un `write()` en curso desde hace ≥ 400 ms, o hay datos en la cola del kernel (`outq > 0`) y ni ha bajado ni ha terminado ningún `write()` en ≥ 400 ms.

**Clasificación al empezar el corte:**

| Tipo | Condición | Significado |
|---|---|---|
| `RADIO` | RX parado **y** TX atascado | Silencio en los dos sentidos: el pellizco del viaje 1. |
| `COCHE_NO_LEE` | TX atascado, ventana del coche = 0 o `rwnd_limited` creciendo, RX puede seguir | La app del coche no lee su socket. |
| `MOVIL_CONGELADO` | El propio monitor no corrió durante > 150 ms | Proceso congelado: GC, CPU o doze. Ya existía como evento `stall` en `PerfTrace`. |

**Líneas en el log unificado y en `L`**, con todas las horas absolutas. Ejemplo con las horas reales del viaje 1; los valores de `NetStat` y de la radio son ilustrativos:

```
18:47:44.820 W/QD/Corte S1 INICIO RADIO: último RX 18:47:44.392 (TOUCH), TX atascado desde 18:47:44.420
             (write de VIDEO_IDR 315112 B, 400 ms) · outq 196 KB · unacked 41 · retrans 3 · rtt 12/4 ms · cwnd 10
             · zona Wi-Fi swlan0 · Wi-Fi cliente desconectada · BT A2DP sí · escaneo Wi-Fi hace 31 s · pantalla apagada
18:47:45.820 W/QD/Corte S1 sigue 1400 ms: outq 196 KB (sin bajar) · retrans 7 (+4) · rtt 220 ms
…
18:47:49.413 W/QD/Corte S1 FIN tras 4993 ms: vuelve RX (ráfaga de 13 mensajes en 12 ms) · outq 0 · retrans 21 (+21)
18:47:49.554 W/QD/S1 cerrando: EOF: el coche cerró la conexión (141 ms después del fin del corte)
```

- `PROGRESO` cada 1 s.
- `FIN` en cuanto vuelve el RX o progresa el TX, o con el cierre de la sesión, indicando el motivo.
- También va una línea a `CarTrace.note("CORTE", …)` y una fila `stall_radio` / `stall_peer` en `PerfTrace`.
- Durante el corte, las filas `net` de `PerfTrace` (cada 50 ms) dan el detalle fino.

### 7.4 Resúmenes

**Al cerrar cada sesión**, bloque en el log unificado + `L.i` + `CarTrace.note("SESION", …)`. Ejemplo de formato; las cifras son ilustrativas:

```
==== S2 · 18:47:49.958 → 18:49:06.957 (77,0 s) · fin LOCAL: aplicar ajustes
coche 10.212.226.80:40736 «LeapMotor-A750» · local 10.212.226.210:18883 swlan0 (zona Wi-Fi) · motor qdauto · aa_ext/Alto 1920x882@60
handshake: CAR_INFO +13 ms · VIDEO_CTRL{1} +41 ms · primer frame +95 ms · primer IDR +95 ms · KEY_FRAME_REQ ×1
vídeo: 2240 frames (29,5 fps) · 5,1 Mbit/s · 19 IDR · descartados 0 · vaciados 0 · máx. write 46 ms · máx. cola 7 ms
coche: 25 heartbeats (2,8–14,8 s) · 1310 toques · hueco máx. 14,8 s
radio: sin cortes · retrans +3 · outq máx. 61 KB
reconexión: 330 ms desde el fin de S1 (broadcast +203 · ACK +1 · TCP +11 · VIDEO_CTRL +41 · IDR +74) · vídeo REUTILIZADO
```

**`logs/sessions.csv`**, columnas:

```
inicio,fin,duracion_s,sesion,motor,conexion,modo_video,perfil,coche_ip,coche_nombre,iface,cierre,cierre_detalle,
llego_a_video,t_car_info_ms,t_video_ctrl_ms,t_primer_frame_ms,t_primer_idr_ms,frames,fps,kbps,idr,keyframe_req_coche,
descartados,vaciados,max_write_ms,max_cola_ms,heartbeats_coche,toques,max_hueco_coche_ms,cortes,max_corte_ms,retrans,
reconexion_ms,video_reutilizado,ciclos_foco_aa
```

**Al parar el servicio** (`onDestroy`), resumen del viaje:

- duración y número de sesiones;
- tiempo con vídeo;
- reconexiones: número y huecos mínimo, medio y máximo;
- histograma de motivos de cierre;
- cortes: número y máximo;
- ciclos de foco de AA;
- arranques de AA (Self-Mode).

### 7.5 «Exportar log» (`HqlLogExport`)

**Dónde:** botón en Diagnóstico (`LogActivity`), junto a «Copiar». Se ejecuta en un hilo aparte con un `Toast` de progreso.

**Pasos:**

1. Volcar a disco:
   - `L.flush()`, `CarTrace.flush()` (con `LowLatency`, se encola un `flush` y se espera ≤ 2 s);
   - `PerfTrace.flush()`, nuevo;
   - `QdTrace.flush(3000)`.
2. Seleccionar ficheros, del más nuevo al más viejo, con un tope total de **200 MiB sin comprimir**:
   - `logs/` completo y `sessions.csv`;
   - `car/` (10 más nuevos);
   - `perf/` (20 más nuevos);
   - `logcat/` (3 más nuevos);
   - `crash/` (todos);
   - `headqlink-*.log` (10 más nuevos).

   **Se excluyen** `trips/` (contiene GPS) y las capturas `ui-preview-*`. Lo omitido se anota en `resumen.txt`.
3. Escribir el ZIP `HeadQLink-log-AAAAMMDD-HHMMSS.zip`. `resumen.txt` va primero y contiene:
   - versión y `GIT_SHA`, modelo y Android;
   - `Config.summary()`, conexión y motor;
   - estado actual de `LinkState`;
   - zona Wi-Fi o P2P e interfaces;
   - las últimas 20 sesiones;
   - lista de ficheros incluidos y omitidos.

   Destino:
   - **API ≥ 29:** `MediaStore.Downloads` con `RELATIVE_PATH = Download/HeadQLink`, `IS_PENDING = 1` mientras se escribe, `ZipOutputStream` sobre `openOutputStream(uri)`, y `IS_PENDING = 0` al acabar; si hay error, se borra el `uri`. Es la misma pauta que `qdauto/app/log/LogExport.kt:27-62`.
   - **API < 29:** `getExternalFilesDir("exports")` + `FileProvider` (`${applicationId}.fileprovider`; `provider_paths.xml` ya cubre `external-files-path`).
4. Hoja de compartir: `ACTION_SEND`, `application/zip`, `EXTRA_STREAM`, `ClipData` y `FLAG_GRANT_READ_URI_PERMISSION`, más `Toast` «Guardado en Descargas/HeadQLink».

**Privacidad.** Los logs contienen identificadores del coche (`CarUUID`, `ProjectID`), IP y nombres de red. Se comparten solo si el usuario lo decide; nada se envía solo.

### 7.6 Cambios en el registro existente

- **`CarTrace.packet`:** el bloque del táctil va en `try`/`catch (RuntimeException)`, y `Proto.parseTouch` devuelve `new Finger[0]` si `len < 5` y limita `count` a lo que cabe. Arregla el cierre del proceso de §1.2-4; afecta solo a helpers compartidos, no a `SspSession`.
- **`L`:** copia de cada línea a `QdTrace` y retención al iniciar.
- **`PerfTrace`:** `flush()` y retención.
- **`CarTrace`:** retención; con el motor QDAuto solo recibe lo indicado en §7.1.
- **`LogcatCapture`:** sin cambios. El manejador de fallos añade `QdTrace.flush(500)` antes de delegar en el manejador original.
- **`LogActivity`:** el texto de rutas pasa a «logs/ registro completo · car/ diario · perf/ rendimiento · logcat/ · crash/».

---

## 8. Plan de implementación

**Regla.** Cada commit compila y pasa sus pruebas antes del siguiente:

```powershell
$env:JAVA_HOME='D:\Android\jdk'; cd hql
cmd /c ".\gradlew.bat :qdcore:test :app:assembleGithubDebug --console=plain"
cmd /c ".\gradlew.bat :app:testGithubDebugUnitTest --tests com.headqlink.link.* --console=plain"   # desde C7
```

**Commits:**

- con `git -c user.name="QDAuto" -c user.email="noreply@anthropic.com" commit`;
- mensaje en español;
- la última línea, `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.

| # | Asunto del commit | Contenido | Comprobación |
|---|---|---|---|
| C0 | `docs: diseño de la integración del motor QDAuto` | Este documento. | — |
| C1 | `qdcore: módulo con el núcleo de QDAuto, ajustado para minSdk 16` | Copia literal, `build.gradle.kts`, `settings`, dependencia del app, los 3 retoques, `UPSTREAM.md` con sha256, `tools/apiscan.py`. | 67/67, APK, `dexdump` sin `compute` ni `setRemoveOnCancelPolicy`. |
| C2 | `qdcore: finalización por frame y políticas de descarte de vídeo` | §3.1, §3.2. | 67 + tests nuevos. |
| C3 | `qdcore: puerta de escritura de vídeo, opciones de socket, ganchos de hilo y PHONE_INFO por coche` | §3.3-3.6 (+ `SUPERSEDED`, `closeLocal`). | Ídem. |
| C4 | `qdcore: PhoneLink con escucha por sesión, filtros de IP, re-ACK y relevo de sesión` | §3.7, §3.8. | Ídem + `EndToEndReconnectTest`. |
| C5 | `registro: log unificado rotativo y arreglo del fallo con toques cortos` | `QdLogFile`, `QdTrace`, copia desde `L`, `LogRetention`, arreglo de `CarTrace`/`Proto`, `PerfTrace.flush`, `App.kt`. | APK. Motor original intacto. |
| C6 | `conexión: modo punto de acceso del móvil` | Claves `link_mode`, `HotspotWatcher`, `NetIfaces`, `LinkService` (P2P condicional), `LinkState.network`, interfaz (configuración, inicio), `Ui.permsGranted` por modo, `SystemMonitor`, `P2pLink` → `LinkState`, cadenas ES/EN. | APK. Con el motor original y P2P, el comportamiento es idéntico. |
| C7 | `enlace: motor QDAuto dentro del fork (paridad con SspSession)` | `QdLinkHost`, `QdSessionBridge`, `SessionPort`, `VideoHub`, `VideoPipeline`, `AaAckBrake`, `KeyframePolicy`, `PeerPolicy`, `QuickBackoff`, `VideoSource.redraw/onReattached/requestKeyFrame(cb)`, `GlFrameRelay.redraw`, ajuste «Motor de protocolo». **`qd_keep_video = false`**: el vídeo se para con cada sesión, como el fork. | Tests de app (§9.1) + prueba LAN (§9.3, sin reconexión). |
| C8 | `enlace: reconexión sin reiniciar Android Auto` | `qd_keep_video = true` por defecto, estado `RECONNECTING` y su interfaz, comprobación de salud de la *pipeline*, `car_gone_ms`, relevo y re-ACK activos, `pauseReconnect`. | §9.3 escenarios de reconexión + §9.4. |
| C9 | `diagnóstico: detector de cortes, resúmenes por sesión y exportar log` | `StallDetector`, `SessionSummary`, `sessions.csv`, resumen del servicio, `HqlLogExport`, botón y prueba con patrón en `LogActivity`, módulo **`:qdsim`**. | Tests + exportación en el móvil (§9.3). |
| C10 | `enlace: QDAuto pasa a ser el motor por defecto` | `link_engine` por defecto `qdauto`. **Solo** tras superar §9.3 y §9.4. | — |

---

## 9. Plan de pruebas

### 9.1 JVM (PC, sin móvil)

- **`:qdcore:test`:** 67 tests originales + ~30 nuevos (§3.9).
- **`:app:testGithubDebugUnitTest --tests com.headqlink.link.*`:** JUnit 4, como el resto de tests del app. Las clases bajo prueba son lógica pura sin `android.*`:
  - `AckSlotTest`: exactamente una vez en todos los órdenes posibles (finalizar antes de retener, retener y luego finalizar, doble finalización, cierre con ranuras pendientes);
  - `KeyframePolicyTest`: antirrebote de 600 ms, reintento único tras rechazo, vigilante de 1,5 s y secuencia `STREAM_START` → IDR → `CAR_REQUEST` a +340 ms;
  - `StallDetectorTest`: muestras sintéticas → `INICIO`/`PROGRESO`/`FIN` en los instantes correctos; sin falsos positivos con la sesión ociosa ni con el coche callado y TX fluyendo; los tres tipos;
  - `PeerPolicyTest`: tabla de §5.4 completa, con interfaces inyectadas;
  - `QuickBackoffTest`;
  - `VideoPlanTest`: igualdad y diferencias relevantes;
  - `QdLogFileTest`: rotación por tamaño y retención por número y bytes en un directorio temporal;
  - `ExportSelectionTest`: tope de 200 MiB, exclusiones y orden.

### 9.2 Compilación

`:app:assembleGithubDebug`. Desde C5, además, revisar el arranque en el emulador o en el móvil (lo hace el usuario) para comprobar que `logs/` se crea y rota.

### 9.3 LAN: coche simulado con la fuente de patrón

Montaje (lo hace el usuario):

- móvil con la app, «Punto de acceso del móvil» y la zona Wi-Fi activa;
- el PC conectado a esa zona Wi-Fi;
- en Diagnóstico, «Prueba sin Android Auto (patrón)» activado.

En el PC (Windows) hay que permitir a `java.exe` recibir UDP 18464 y TCP en la red de la zona Wi-Fi: Windows la clasifica como «Pública».

1. **Handshake y vídeo con el carsim validado.** Se usa sin modificarlo:

   ```
   qdauto\carsim\build\install\carsim\bin\carsim.bat --duration 60 --width 1920 --height 882 --fps 30 --bitrate 5080320 --gop 3 --out s1.h264 --report s1.json
   ```

   Criterio: `PASS` en todas las comprobaciones (handshake, AppStatus, cabeceras, SPS/PPS antes del IDR, táctil con pellizco).
2. **Escenarios de `:qdsim`**, módulo nuevo en C9:
   - Kotlin/JVM, plugin `application`, `implementation(project(":qdcore"))`, mismas opciones de compilación que `:qdcore`;
   - CLI sobre `CarSim`: `--target`, `--scenario`, `--sessions`, `--duration`, `--gap-ms`, `--stall-s`, tamaños, `--out`.

   ```
   .\gradlew.bat :qdsim:run --args="--scenario reconnect --sessions 10 --duration 20 --gap-ms 200"
   ```

   | Escenario | Qué hace el «coche» | Criterio de aceptación |
   |---|---|---|
   | `normal` | 1 sesión con guion táctil (toque, arrastre, pellizco, `keyframe`) | `PASS`; en el móvil, el patrón reacciona al táctil. |
   | `reconnect` | N sesiones; cierra (FIN) y se reanuncia a los `gap-ms` | Cada sesión tiene SPS/PPS + IDR **≤ 500 ms** tras `VIDEO_CTRL{1}`. El log dice «vídeo REUTILIZADO» en todas menos la primera. `reconexión` < 1 s. |
   | `stall` | 5 s de vídeo; `goSilent` + `pauseReading` durante `stall-s`; cierre y nuevo anuncio | `INICIO`/`FIN` de un corte `RADIO` con horas coherentes; reconexión < 1 s. |
   | `silent` | Silencio y sin leer, **sin cerrar**; a los 2 s otro `CarSim` con el mismo UUID se anuncia | Cierre `SUPERSEDED`; sesión nueva < 200 ms tras el broadcast. |
   | `disconnect` | Manda `DISCONNECT_REQ` | `DISCONNECT_RSP{CanDisconnect:1}` y cierre del móvil; vuelve a conectar si el coche se reanuncia. |
   | `rack` | Ignora el primer ACK | Segundo ACK ≥ 400 ms después; conexión. |

3. **Motor original:** repetir el punto 1 con «Original de headqlink» → mismo `PASS`. Es la prueba de regresión del fallback.
4. **Exportar log:** pulsar «Exportar log» → ZIP en `Descargas/HeadQLink`, compartirlo al PC y comprobar `resumen.txt`, `sessions.csv` y el log unificado.

### 9.4 Android Auto en el móvil, sin coche

Con `carsim` haciendo de coche y el modo `aa_ext` por defecto:

- **Imagen de AA:** `carsim --out aa.h264` → `ffplay -f h264 aa.h264` (`tools/ffmpeg`) muestra la interfaz de AA.
- **Reconexión:** `:qdsim --scenario reconnect` con 10 sesiones:
  - AA no se reinicia: no aparece «AA: lanzando Self-Mode» después de la primera sesión;
  - IDR ≤ 500 ms;
  - la radio y el viaje de `CarUi` no se cortan;
  - el modo noche se mantiene. Se prueba con `sendAppMessage("Global","DarkModeOn",{DarkModeOn:1})` desde el escenario.
- **Espera:** con más de 30 s sin coche se apaga todo, igual que el fork, y el siguiente Conectar vuelve a lanzar AA.
- **Básico (reenvío directo):**
  - reconexión con ciclo de foco ≤ 1,5 s;
  - la estadística «acks retenidos» > 0;
  - tras 10 reconexiones AA sigue mandando vídeo (ni un ack perdido);
  - en el log, `KeyframePolicy` sin ciclos en cascada.
- **Pantalla apagada y móvil bloqueado:** los mismos escenarios con el móvil bloqueado a mitad.

### 9.5 En el coche (C10)

1. **Zona Wi-Fi + QDAuto (`aa_ext`):**
   - arranque con la pantalla apagada;
   - 15 minutos de conducción;
   - un **pellizco** a propósito para provocar el corte: debe verse `INICIO`/`FIN` del corte, EOF y reconexión < 1 s **sin reiniciar AA**, y la imagen de vuelta < 1 s;
   - exportar el log al terminar.
2. **Wi-Fi Direct + QDAuto:** igual, con la zona Wi-Fi apagada.
3. **Wi-Fi Direct + original:** sesión corta, para confirmar que el fallback funciona como antes.
4. **(Opcional) Básico** en zona Wi-Fi: freno y ciclos de foco.

**Qué anotar de cada viaje:** `sessions.csv`, número y duración de cortes por tipo, `reconexión_ms` y si hubo algún reinicio de AA.

---

## 10. Riesgos, mitigaciones y preguntas abiertas

| Riesgo | Mitigación |
|---|---|
| **Acks de AA perdidos** (AA deja de mandar vídeo). | §3.1, finalización exactamente una vez con test de estrés; `AckSlot` de estados atómicos; `stop()` lo suelta todo; la puerta solo retiene si hay ranura. |
| **El relevo corta una sesión sana.** | Umbrales de edad (2 s) y de silencio (1 s); el C10 no se anuncia con sesión; todo se registra; desactivable con `supersedeOnRebroadcast` (clave oculta `qd_supersede`). |
| **Reutilizar el vídeo con un estado incoherente** (cabecera, SPS, encoder muerto). | Plan con huella de ajustes; `healthy()`; SPS/PPS en caché + `STREAM_START` en cada enganche; `qd_keep_video=false` como salida de emergencia. |
| **La API se aleja de `qdauto/core`.** | Cambios aditivos con valores por defecto neutros; `UPSTREAM.md` con sha256; tests propios trasladables. |
| **Sin lint de Android en `:qdcore`.** | Reglas de §2.5, `-Xjdk-release=1.8` y `tools/apiscan.py` + `dexdump` en los commits de núcleo. |
| **Retraso del control tras un `write()` de vídeo grande** (un IDR de 315 KB bloquea el escritor). | Igual que hoy: no se puede abortar un `write()`. El corte por escritura bloqueada (10 s) y el EOF del coche (~5 s) acotan el caso. La puerta del freno solo afecta al vídeo. |
| **Detección de la zona Wi-Fi** (la reflexión puede dar `UNKNOWN`). | Tres fuentes; `UNKNOWN` no bloquea nada; la ayuda lo explica. |
| **Lo que no se ha visto en el coche.** | Re-ACK (el fork lo hace en cada broadcast), `PHONE_INFO` del fork con el núcleo, TOS 0xA0, *bind* a la interfaz de la zona Wi-Fi (con vuelta al comodín). |
| **Coste de mantener el vídeo 30 s sin coche** (radio, GPS, encoder a ~10 fps). | Acotado por `car_gone_ms`, lo mismo que hoy mantiene AA. |
| **`kotlin-test` 1.9.22 no está en la caché.** | Hay red: primer `:qdcore:test` con conexión. Si no la hubiera, se usa la `kotlin-test` 2.2.20 de la caché solo para tests, con `-Xskip-metadata-version-check`, como en la auditoría. |
| **Ajustes con la sesión en marcha.** | `APPLY` para el vídeo y pausa la reconexión 1-3 s, para que AA termine de renegociar. |

**Preguntas abiertas** (las cierran el viaje y sus registros):

1. ¿El pellizco es un gesto del coche (salir o minimizar) o un corte de radio provocado por la ráfaga? El tipo de corte (`RADIO` frente a `COCHE_NO_LEE`) y `rwnd_limited` lo dirán.
2. Tras un cierre **local** (`APPLY`), ¿el C10 se reanuncia en < 1 s como tras un EOF suyo?
3. ¿Le afecta al C10 un ACK repetido? En el fork no; se registrará `onAckSent` con el número de intento.
4. ¿Acepta el C10 el `PHONE_INFO` del fork con 720p? Ya funciona con headqlink: hay que confirmar que no cambia nada con el núcleo.
5. ¿30 s de espera es suficiente? Se ajustará con `reconexión_ms` y la duración de los cortes de los viajes.

---

## Anexo A. Claves nuevas de `Config`

| Clave | Tipo | Defecto | Extra de adb | Uso |
|---|---|---|---|---|
| `link_mode` | `p2p` \| `hotspot` | `p2p` | `--es link_mode` | `LinkService`, `PeerPolicy`, interfaz |
| `link_engine` | `original` \| `qdauto` | `original` (→ `qdauto` en C10) | `--es link_engine` | `LinkService` |
| `qd_keep_video` | bool | `true` (desde C8) | `--ez qd_keep_video` | `VideoHub` / `QdSessionBridge` |
| `car_gone_ms` | int | 30000 | `--ei car_gone_ms` | `LinkService.carGone` |
| `peer_strict` | bool | `false` | `--ez peer_strict` | `PeerPolicy` |
| `qd_supersede` | bool | `true` | `--ez qd_supersede` | `PhoneLinkConfig.supersedeOnRebroadcast` |
| `qd_phone_info` | `fork` \| `qdlink` | `fork` | `--es qd_phone_info` | `SessionConfigs` |
| `mode_before_pattern` | String | — | — | Prueba con patrón en Diagnóstico |

`Config.videoFingerprint()` (nuevo) junta en un texto todas las claves que afectan al plan de vídeo (§4.7).

## Anexo B. Textos nuevos

Nombre (`hql_…`) → español (`values-es`) / inglés (`values`). Los apóstrofos de la columna inglesa ya van escapados para XML (`\'`).

| Nombre | ES | EN |
|---|---|---|
| `link_title` | Conexión con el coche | Connection to the car |
| `link_p2p` | Wi-Fi Direct | Wi-Fi Direct |
| `link_p2p_detail` | El coche crea la red y el móvil se une. La zona Wi-Fi del móvil tiene que estar apagada. | The car creates the network and the phone joins it. The phone\'s hotspot must be off. |
| `link_hotspot` | Punto de acceso del móvil | Phone hotspot |
| `link_hotspot_detail` | El coche se conecta a la zona Wi-Fi de tu móvil. Probado en el C10, también con la pantalla apagada. | The car joins your phone\'s hotspot. Tested on the C10, also with the screen off. |
| `hotspot_on` | Zona Wi-Fi activa (%1$s) | Hotspot on (%1$s) |
| `hotspot_off` | Zona Wi-Fi apagada: actívala | Hotspot off: turn it on |
| `hotspot_unknown` | No se sabe si la zona Wi-Fi está activa | Hotspot state unknown |
| `hotspot_open` | Abrir zona Wi-Fi | Open hotspot settings |
| `hotspot_guide` | 1. Activa la zona Wi-Fi del móvil (mejor en 5 GHz y sin apagado automático).\n2. Solo la primera vez: en el coche, Ajustes › Wi-Fi › elige la zona Wi-Fi de tu móvil.\n3. Abre la app de espejo en la pantalla del coche. HeadQLink conecta solo, también con el móvil bloqueado.\nEl coche podría usar tus datos móviles. | 1. Turn on the phone\'s hotspot (5 GHz and no auto-off is best).\n2. First time only: on the car, Settings › Wi-Fi › pick your phone\'s hotspot.\n3. Open the mirroring app on the car\'s screen. HeadQLink connects on its own, even with the phone locked.\nThe car may use your mobile data. |
| `p2p_needs_hotspot_off` | Apaga la zona Wi-Fi del móvil para usar Wi-Fi Direct. | Turn off the phone\'s hotspot to use Wi-Fi Direct. |
| `hint_searching_hotspot` | Comprueba que el coche está conectado a la zona Wi-Fi del móvil y abre la app de espejo en su pantalla. El móvil se puede bloquear. | Make sure the car is connected to the phone\'s hotspot and open the mirroring app on its screen. You can lock the phone. |
| `reconnecting` | Reconectando… (Android Auto en espera) | Reconnecting… (Android Auto on hold) |
| `network` | Red | Network |
| `p2p_in_group` | Wi-Fi Direct: en el grupo del coche (%1$s) | Wi-Fi Direct: in the car\'s group (%1$s) |
| `p2p_searching` | Wi-Fi Direct: buscando el coche | Wi-Fi Direct: looking for the car |
| `udp_busy` | El puerto 18463 está ocupado (¿QDLink abierto?). Ciérralo; se reintenta cada 5 s. | Port 18463 is busy (is QDLink open?). Close it; retrying every 5 s. |
| `engine_title` | Motor de protocolo | Protocol engine |
| `engine_qdauto` | QDAuto (recomendado) | QDAuto (recommended) |
| `engine_original` | Original de headqlink | Original headqlink |
| `applies_on_reconnect` | Se aplica al volver a conectar. | Applies on next connection. |
| `export_log` | Exportar log | Export log |
| `export_done` | Guardado en Descargas/HeadQLink | Saved to Downloads/HeadQLink |
| `export_failed` | No se pudo exportar: %1$s | Could not export: %1$s |
| `export_share` | Compartir log de HeadQLink | Share HeadQLink log |
| `pattern_test` | Prueba sin Android Auto (patrón) | Test without Android Auto (pattern) |
| `welcome_wifi` (cambia) | El móvil y el coche se conectan por Wi-Fi (Wi-Fi Direct o la zona Wi-Fi del móvil); no hace falta cable. | The phone and the car connect over Wi-Fi (Wi-Fi Direct or the phone\'s hotspot); no cable needed. |

## Anexo C. Ficheros nuevos y modificados

**Nuevos**

- Módulos:
  - `qdcore/` (C1-C4), con `UPSTREAM.md`;
  - `qdsim/` (C9);
  - `tools/apiscan.py` (C1).
- `[hql]` (C5-C9): `QdLinkHost.kt`, `QdSessionBridge.kt`, `SessionPort.kt`, `SessionConfigs.kt`, `PeerPolicy.kt`, `NetIfaces.kt`, `QuickBackoff.kt`, `HotspotWatcher.kt`, `QdTrace.kt`, `QdLogFile.kt`, `LogRetention.java`, `StallDetector.kt`, `SessionSummary.kt`, `HqlLogExport.kt`, `VideoHub.java`, `VideoPipeline.java`, `VideoPlan.java`, `AaAckBrake.java`, `AckSlot.java`, `KeyframePolicy.java`, `KeyframeCallback.java`.
- Tests de app (JUnit 4), en `app/src/test/java/com/headqlink/link/`: `AckSlotTest`, `KeyframePolicyTest`, `StallDetectorTest`, `PeerPolicyTest`, `QuickBackoffTest`, `VideoPlanTest`, `QdLogFileTest`, `ExportSelectionTest`.

**Modificados**

- Gradle: `settings.gradle.kts`, `app/build.gradle.kts` (dependencia `:qdcore`).
- `[hql]`:
  - `LinkService.java`: transporte por modo y motor, `RECONNECTING`, `carGone` configurable, `APPLY`, `onDestroy`;
  - `Config.java`: Anexo A, `videoFingerprint`, `summary`;
  - `LinkState.java`: `Car.RECONNECTING`, `network`;
  - `HomeActivity.java`, `SetupActivity.java`, `LogActivity.java`, `SettingsScreen.java`, `Ui.java`: permisos por modo y abrir ajustes de zona Wi-Fi;
  - `SystemMonitor.java`: interfaz de la sesión;
  - `P2pLink.java`: solo llamadas a `LinkState.setNetwork`;
  - `VideoSource.java`: tres métodos por defecto;
  - `AaPassthroughSource.java`: `redraw`, `onReattached`, `requestKeyFrame(cb)`;
  - `GlFrameRelay.java`: `redraw()`;
  - `CarTrace.java`, `Proto.java`: arreglo del táctil y retención;
  - `L.java`: copia a `QdTrace` y retención;
  - `PerfTrace.java`: `flush` y retención;
  - `LogcatCapture.java`: `flush` del log unificado en un fallo.
- `[ohu]App.kt`: `QdTrace.init(this)`.
- Recursos:
  - `values/hql_strings.xml`, `values-es/hql_strings.xml` (Anexo B);
  - `layout/hql_setup_mode.xml` (bloque de conexión);
  - `layout/hql_dialog_video.xml` (motor);
  - `layout/hql_activity_log.xml` (exportar y patrón).

**Sin cambios:** `SspSession.java`, `UdpDiscovery.java`, `AaGuardService.java`, `AndroidManifest.xml` (salvo que un *receiver* nuevo lo exigiera: no hace falta, todos son dinámicos), el commit de seguridad `312e8ee5`, `ref/` y `qdauto/`.
