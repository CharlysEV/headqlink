# HeadQLink · Manual de usuario

**Idiomas:** **Español** · [Português](MANUAL.pt.md) · [English](MANUAL.en.md)

Manual para conductores de la versión **0.2** de HeadQLink (fork [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink)).
Los nombres de botones y menús aparecen «entre comillas», tal como los muestra la app en español.

> [!WARNING]
> Haz toda la configuración **con el coche aparcado**. No toques el móvil mientras conduces, y no veas vídeos, TV,
> páginas web ni juegos en la pantalla del coche si no está parado.

**Resumen rápido**

1. Instala el APK desde [GitHub Releases](https://github.com/CharlysEV/headqlink/releases).
2. Abre HeadQLink y sigue el asistente de 3 pasos hasta que la «Comprobación» diga «Todo listo».
3. En el coche: activa la zona Wi-Fi del móvil, abre la app de espejo en la pantalla del coche y pulsa «Conectar».
4. Cuando aparezca Android Auto, bloquea el móvil y guárdalo.

## Índice

1. [Qué es y qué necesitas](#1-qué-es-y-qué-necesitas)
2. [Instalación](#2-instalación)
3. [Primer arranque](#3-primer-arranque)
4. [Uso en el coche](#4-uso-en-el-coche)
5. [Ajustes útiles](#5-ajustes-útiles)
6. [Calor y batería](#6-calor-y-batería)
7. [Solución de problemas](#7-solución-de-problemas)
8. [Exportar el log para pedir ayuda](#8-exportar-el-log-para-pedir-ayuda)
9. [Seguridad, privacidad y aviso legal](#9-seguridad-privacidad-y-aviso-legal)

---

## 1. Qué es y qué necesitas

HeadQLink lleva **Android Auto** a la pantalla del **Leapmotor C10** usando la conexión de espejo que el coche ya trae
(la de su app QDLink/SSPLink). No hace falta ningún adaptador ni cable: el móvil y el coche se hablan por Wi-Fi, y el
móvil puede ir **bloqueado y con la pantalla apagada**. No necesita root.

Puedes elegir entre dos modos:

| Modo | Qué ves en el coche |
|---|---|
| «Auto» | Android Auto a pantalla completa: mapas, música y mensajes. |
| «Auto extendido» (recomendado por la app) | Lo mismo, con un panel propio a la izquierda con más información del coche y del viaje (ruta, conducción, viajes, instrumentos, eficiencia) y más funciones (fotos, vídeos, web, TV, radio, juegos). |

**Qué necesitas**

| | |
|---|---|
| Coche | Leapmotor C10 con su app de espejo (proyección del móvil) en la pantalla. |
| Móvil | Android con **Android Auto** instalado. Se recomienda **Android 10 o superior**; elegir el idioma de la app necesita Android 13. |
| Probado en | Samsung Galaxy **S25 Ultra** con **Android 16** y Android Auto 17.7. Otros móviles pueden funcionar, pero no se han probado. |
| Android Auto 17.4 o superior | Hace falta activar una vez su **modo desarrollador** y el servicio de **accesibilidad** de HeadQLink. La app te guía (sección 3). Con versiones anteriores no hace falta. |
| Internet (opcional) | Solo para funciones de «Auto extendido» como rutas, tiempo o radio. |

> [!NOTE]
> **Estado de las pruebas.** HeadQLink es un proyecto personal y experimental. En un viaje real con un C10, un
> S25 Ultra y la conexión «Punto de acceso del móvil», Android Auto funcionó en dos sesiones de unos 16 minutos cada
> una. En esa prueba el móvil se calentó demasiado, así que esta versión añade el perfil de imagen «Coche», el apagado
> de la pantalla del móvil y la adaptación al calor (sección 6). La conexión Wi-Fi Direct con el motor QDAuto todavía
> no se ha probado en el coche.

---

## 2. Instalación

HeadQLink no está en Google Play: se instala con un archivo APK.

### Instalar

1. En el móvil, abre [github.com/CharlysEV/headqlink/releases](https://github.com/CharlysEV/headqlink/releases).
2. En la versión más reciente, despliega **Assets** y toca el archivo `.apk` (algo como `com.headqlink.app_0.2….apk`).
3. Abre el archivo descargado (desde la notificación o desde Mis archivos › Descargas).
4. Android te dirá que no puede instalar apps desconocidas de esa fuente. Toca **Ajustes**, activa **Permitir de esta
   fuente** y vuelve atrás.
5. Toca **Instalar**.

<!-- captura: aviso de Android «instalar apps desconocidas» -->

> [!TIP]
> **Aviso de Play Protect.** Como la app no viene de Google Play, Play Protect puede avisar de que es una app
> desconocida o bloquearla. Si confías en este proyecto, toca **Más detalles › Instalar de todas formas**.
>
> **Samsung:** si está activado el **Bloqueador automático** (Ajustes › Seguridad y privacidad), no te dejará instalar
> APK. Desactívalo para instalar y vuelve a activarlo después si quieres.

### Actualizar

1. Descarga el APK nuevo de la misma página de Releases.
2. Instálalo encima del que tienes: se conservan tus ajustes.
3. Abre HeadQLink y mira la «Comprobación»: **Android suele desactivar la accesibilidad al actualizar** y hay que
   volver a activarla.

> [!IMPORTANT]
> Solo se puede actualizar con un APK **firmado con la misma clave**, es decir, de las Releases de este fork. Si Android
> dice que la app no se ha instalado o que entra en conflicto con otra, es que tienes una versión con otra firma (por
> ejemplo, el HeadQLink original de ryazrm, que usa el mismo identificador, o una que compilaste tú). Desinstálala
> primero; perderás sus ajustes.

### Desinstalar

1. Si no vas a volver a usarla: en los ajustes de Android Auto puedes salir del modo desarrollador (menú ⋮).
2. Ajustes de Android › Aplicaciones › HeadQLink › **Desinstalar**.
3. Los logs exportados se quedan en Descargas/HeadQLink: bórralos a mano si no los quieres.

---

## 3. Primer arranque

La primera vez que abres HeadQLink aparece un asistente de 3 pasos. Hazlo con el coche aparcado; mejor aún, dentro del
coche y con la app de espejo abierta en su pantalla.

### Paso 1 de 3: bienvenida

Pantalla «Tu móvil, en la pantalla del C10», con lo que hace falta «Antes de empezar». Pulsa «Empezar».

### Paso 2 de 3: modo y conexión

<!-- captura: paso 2 del asistente, modo y conexión -->

**«¿Qué quieres ver en el coche?»** Elige «Auto extendido» o «Auto» (tabla de la sección 1).

> [!NOTE]
> **Privacidad en «Auto extendido».** Sus paneles usan la ubicación del móvil, si le das permiso, y servicios abiertos
> de internet: OpenStreetMap y OSRM para la ruta y los cargadores, Open-Meteo para el tiempo y el viento, y
> radio-browser.info para la radio. Para calcularlo, a esos servicios les llega tu posición o tu destino. Si no lo
> quieres, elige «Auto» o no concedas el permiso «Fotos, vídeos y ubicación».

**«Conexión con el coche»**

| Opción | Cómo funciona | Recomendación |
|---|---|---|
| «Punto de acceso del móvil» | El coche se conecta a la zona Wi-Fi de tu móvil. | **Recomendada**: es la que se ha probado en el C10, también con la pantalla apagada. |
| «Wi-Fi Direct» | El coche crea la red y el móvil se une. La zona Wi-Fi del móvil tiene que estar apagada. | Es la conexión del HeadQLink original. Con el motor QDAuto aún no se ha probado en el coche. |

> [!TIP]
> La app marca por defecto «Punto de acceso del móvil», con la etiqueta «Recomendado». Si prefieres «Wi-Fi Direct»,
> tócala antes de pulsar «Continuar».

Pulsa «Continuar». Podrás cambiar el modo y la conexión cuando quieras con el botón «Cambiar» de la pantalla principal.

### Paso 3 de 3: «Preparar Auto extendido» (o «Preparar Auto»)

Aquí está la **Comprobación**: una lista con todo lo que necesita tu configuración. Es la misma que verás después en el
menú ⚙ › «Comprobación».

<!-- captura: pantalla Comprobación -->

**Cómo leerla**

- Arriba pone «Todo listo» (verde) o «Faltan N cosas»: en rojo si falta algo obligatorio y en ámbar si solo falta algo
  recomendado.
- Cada fila lleva un icono (✓ hecho, ! falta, ✕ error, ? no se sabe, … comprobando, i consejo), su importancia
  («Obligatorio», «Recomendado», «Opcional» o «Consejo») y un **botón que te lleva al ajuste exacto**.
- Se actualiza sola al volver a HeadQLink.

**Fila por fila.** Solo salen las que importan para tu modo y tu conexión.

| Fila | Qué significa | Qué pulsar |
|---|---|---|
| «Android Auto» | Tiene que estar instalado y activado. Muestra su versión. | «Instalar» (Play Store) o «Info. de la app» si está desactivado. |
| «Accesibilidad de HeadQLink» (Obligatorio con Android Auto 17.4 o superior) | HeadQLink la usa para arrancar Android Auto sin que lo veas y para recibir los toques del coche. En Ajustes de Android se llama **«HeadQLink táctil»**. | «Activar» y enciende «HeadQLink táctil». Ver el aviso de debajo. |
| «Modo desarrollador de Android Auto» (Obligatorio con 17.4 o superior) | Android Auto solo acepta una «pantalla de coche» dentro del móvil a través de su modo para desarrolladores. Se activa una vez. | «Abrir AA» y sigue la guía de debajo. «Comprobar» lo verifica (necesita la accesibilidad activada). |
| «Notificaciones» (Recomendado) | Para ver el estado de la conexión y los avisos. | «Permitir» (o «Abrir» si las bloqueaste). |
| «Batería sin restricciones» (Recomendado) | Para que Android no cierre HeadQLink con la pantalla apagada ni bloquee la conexión automática. | «Permitir» y acepta el aviso de Android. |
| «Samsung: apps que nunca se suspenden» (Consejo, solo Samsung) | Samsung cierra apps en segundo plano por su cuenta. No se puede comprobar desde la app. | «Abrir» y elige «Sin restricciones». Además: Ajustes › Batería › Límites de uso en segundo plano › Aplicaciones que nunca se suspenden › añade HeadQLink. |
| «Gestor de energía de …» (Consejo, otras marcas) | Xiaomi, Honor, Oppo y otras tienen su propio ahorro de batería. | «Abrir» y permite el inicio automático o la actividad en segundo plano. |
| «Zona Wi-Fi del móvil» (Obligatorio con «Punto de acceso del móvil») | La zona Wi-Fi tiene que estar encendida. Si está bien, dice «Zona Wi-Fi activa (…)». | «Abrir» y actívala. |
| «Banda de 5 GHz» (Consejo) | La app no puede leerlo. En 5 GHz va mejor, porque en 2,4 GHz la radio se comparte con el Bluetooth del coche. | «Abrir»: elige 5 GHz y desactiva el apagado automático de la zona Wi-Fi. |
| «Dispositivos Wi-Fi cercanos» o «Ubicación» (Obligatorio con «Wi-Fi Direct») | Permiso para unirse a la red Wi-Fi Direct del coche («Ubicación» en Android 12 o anterior). | «Permitir» (o «Abrir» si lo denegaste). |
| «Wi-Fi activado» (Obligatorio con «Wi-Fi Direct») | Wi-Fi Direct necesita el Wi-Fi encendido, aunque no estés conectado a ninguna red. | «Abrir». |
| «Zona Wi-Fi apagada» (Obligatorio con «Wi-Fi Direct») | Wi-Fi Direct no funciona con la zona Wi-Fi encendida. | «Abrir» y apágala. |
| «QDLink» (Recomendado) | La app oficial QDLink está instalada en el móvil. Si está abierta, ocupa el puerto 18463 y el coche no encuentra a HeadQLink. | «Info. de la app» › «Forzar detención» antes de conectar. |
| «QDLink» o «Puerto 18463» en rojo («Ocupado») | QDLink (u otra app) está abierta ahora mismo. | «Forzar detención». |
| «Bluetooth (dispositivos cercanos)» (Obligatorio si activas la conexión automática) | Para reconocer el Bluetooth del coche y conectar sola. | «Permitir». |
| «Mostrar sobre otras apps» (Opcional) | Para abrir Android Auto con el móvil en segundo plano. | «Permitir». |
| «Fotos, vídeos y ubicación» (Opcional, «Auto extendido») | Para la galería y los paneles de conducción del coche. | «Permitir». |

> [!IMPORTANT]
> **«Ajuste restringido» al activar la accesibilidad.** En Android 13 o superior, a las apps instaladas desde un APK
> Android no les deja activar la accesibilidad a la primera. Si al activar «HeadQLink táctil» sale «Ajuste
> restringido»:
>
> 1. Pulsa el segundo botón de la fila, «Info. de la app» (o ve a Ajustes › Aplicaciones › HeadQLink).
> 2. Toca el menú ⋮ (arriba a la derecha) › **«Permitir ajustes restringidos»** y confírmalo. Esta opción aparece
>    después de haber intentado activarla una vez.
> 3. Vuelve, pulsa «Activar» y enciende «HeadQLink táctil».
>
> Si la fila dice «Activada pero sin funcionar (Android la paró)», apágala y vuelve a encenderla.

**Cómo activar el modo desarrollador de Android Auto** (la misma guía que muestra la app):

1. Pulsa «Abrir AA». Si no aparece, pulsa antes «Comprobar».
2. Baja hasta el final de los ajustes de Android Auto.
3. Toca **10 veces «Versión»** y acepta el aviso.
4. Vuelve a HeadQLink: se comprueba solo.

<!-- captura: ajustes de Android Auto con «Versión» al final -->

Con la conexión «Punto de acceso del móvil», debajo de la lista aparece además «Cómo conectar el coche a la zona Wi-Fi»
(sección 4).

Pulsa **«Terminar»**. Si falta algo obligatorio, el aviso «Aún falta» te lo dice: puedes «Volver» o «Terminar
igualmente» y completarlo más tarde.

> [!NOTE]
> Al terminar el asistente, y cada vez que abres HeadQLink ya configurada, **la app empieza a conectar sola** (es como
> pulsar «Conectar») y espera al coche hasta 5 minutos. Con Android Auto 17.4 o superior verás un momento la capa
> «Arrancando Auto…»: no toques nada. Si no estás en el coche, pulsa «Desconectar».

---

## 4. Uso en el coche

### La pantalla principal

<!-- captura: pantalla principal de HeadQLink -->

- Arriba, el modo y la conexión (por ejemplo «Auto extendido · Punto de acceso del móvil») con el botón «Cambiar», y
  el menú ⚙.
- El interruptor «Conectar al detectar el Bluetooth del coche».
- La tarjeta «Estado». Arriba, el panel «En directo»: dos cifras grandes, los **fps** (imágenes por segundo) y los
  **Mbps** (Mbit/s) del vídeo que llega al coche, cada una con una barra que se llena según lo que da tu perfil de
  imagen (con «Coche», 30 fps). El punto de «En directo» parpadea mientras llega vídeo; sin vídeo, las cifras muestran
  «—». Debajo, una fila para cada parte de la conexión:

| Fila | Qué te dice |
|---|---|
| «Coche» | «Desconectado», «Buscando…», «Encontrado, conectando…», «Conectado» (con el tamaño de la pantalla del coche) o «Reconectando… (Android Auto en espera)». |
| «Red» | «Zona Wi-Fi activa (…)», «Zona Wi-Fi apagada: actívala», «Wi-Fi Direct: buscando el coche» o «Wi-Fi Direct: en el grupo del coche (…)». Si la zona Wi-Fi está apagada, al tocar la fila se abren sus ajustes. |
| «Imagen» | El vídeo que se está enviando al coche. |
| «Auto» | El estado de Android Auto y, si no arranca, por qué (por ejemplo «No arranca: desbloquea el móvil»). |
| «Requisitos» | «Todo listo» o «Faltan N cosas». Al tocarla se abre la «Comprobación». |

- Un texto de ayuda y el botón grande «Conectar» / «Desconectar».

### Paso a paso con «Punto de acceso del móvil» (recomendado)

1. **Activa la zona Wi-Fi del móvil**, mejor en 5 GHz y sin apagado automático.
2. **Solo la primera vez:** en el coche, ve a Ajustes › Wi-Fi y elige la zona Wi-Fi de tu móvil. Después el coche se
   une solo.
3. **Abre la app de espejo** en la pantalla del coche.
4. **Abre HeadQLink** con el móvil desbloqueado. Empieza a conectar sola; si la tenías abierta y desconectada, pulsa
   «Conectar». Con Android Auto 17.4 o superior verás un momento «Arrancando Auto…».
5. La fila «Coche» pasa a «Encontrado, conectando…» y después a «Conectado». **Android Auto aparece en la pantalla del
   coche.**
6. **Bloquea el móvil** y déjalo en un sitio fresco. La imagen sigue llegando al coche con la pantalla apagada.

> [!CAUTION]
> Con la zona Wi-Fi activa, **el coche podría usar tus datos móviles.**

**Con «Wi-Fi Direct»:** deja la zona Wi-Fi apagada y el Wi-Fi encendido, abre la app de espejo en el coche y luego
HeadQLink. La fila «Red» pasará de «Wi-Fi Direct: buscando el coche» a «Wi-Fi Direct: en el grupo del coche (…)».

> [!IMPORTANT]
> Si QDLink está instalada en el móvil, **fuerza su detención antes de conectar**. Si está abierta, ocupa el puerto que
> necesita HeadQLink y el coche no la encuentra.

### Qué ves y cómo se maneja

- **«Auto»:** Android Auto a pantalla completa.
- **«Auto extendido»:** un panel a la izquierda, del lado del conductor, con «Auto», «Coche» (pestañas Ruta,
  Conducción, Viajes, Instrumentos y Eficiencia), «Fotos», «Vídeos», «Web», «TV», «Radio», «Juegos» y «Ajustes». Con
  Android Auto en pantalla, el panel se oculta solo a los pocos segundos; toca el borde izquierdo para que vuelva.
  Fotos, vídeos, web, TV y juegos son **solo para cuando el coche está parado**.
- **Pantalla táctil:** funciona como en Android Auto, con **multitáctil** de hasta 3 dedos (por ejemplo, pellizcar para
  hacer zoom en el mapa).
- **Botones del volante:** reproducir/pausa, siguiente y anterior funcionan a través del **Bluetooth del coche**, sin
  emparejar nada más.
- **Sonido:** música e indicaciones salen del móvil por el **Bluetooth del coche**, así que el móvil tiene que estar
  conectado a él como siempre. Las llamadas van por el manos libres del coche.

### Cortes y reconexión automática

- Si la conexión con el coche se corta un momento, la fila «Coche» dice «Reconectando… (Android Auto en espera)» y la
  imagen vuelve sola, normalmente en menos de un segundo, **sin reiniciar Android Auto** (con el motor QDAuto, el que
  viene por defecto).
- Si el coche **desaparece más de 30 segundos** (por ejemplo, porque lo apagas), HeadQLink lo cierra todo: Android Auto
  y la conexión. Para volver a usarla, pulsa «Conectar», o déjalo en manos de la conexión automática por Bluetooth.
- Si pulsas «Conectar» y en **5 minutos** no aparece ningún coche, HeadQLink se para sola.

### Al terminar el viaje

- Apaga el coche (HeadQLink se cierra sola a los 30 s) o pulsa «Desconectar».
- Si el móvil estaba bloqueado, verás la notificación «Auto en espera hasta que desbloquees el móvil (después se cierra
  solo)». **Desbloquea el móvil una vez** para que se cierre del todo (sección 9).
- Apaga la zona Wi-Fi si no la necesitas.

### Conexión automática: «Conectar al detectar el Bluetooth del coche»

Con este interruptor, HeadQLink se pone a esperar al coche en cuanto el móvil se conecta a su Bluetooth, sin que abras
la app.

1. Actívalo en la pantalla principal.
2. En el aviso «Conexión automática», escribe un texto que contenga el **nombre Bluetooth del coche** (viene
   `Leapmotor_BT`; no distingue mayúsculas) y pulsa «Guardar».
3. Concede el permiso de Bluetooth y quita las restricciones de batería cuando te lo pida. Si no, Android no la deja
   arrancar en segundo plano.

Después:

- Si el Bluetooth se va sin haber llegado a conectar con el coche, HeadQLink se para.
- Si Android no la deja arrancar en segundo plano, verás la notificación «Coche detectado · Toca para conectar
  HeadQLink». Tócala.
- Si el móvil está bloqueado cuando hay que arrancar Android Auto, la fila «Auto» puede decir «No arranca: desbloquea
  el móvil». Desbloquéalo (con el coche parado) y, si no arranca, pulsa «Desconectar» y «Conectar».

---

## 5. Ajustes útiles

Todo está en el menú ⚙ de la pantalla principal: «Comprobación», «Ajustes de imagen», «Lista de TV» y «Lista de radio»
(solo en «Auto extendido»), «Idioma» y «Diagnóstico».

### «Ajustes de imagen»

<!-- captura: Ajustes de imagen -->

**«Perfil de imagen».** La app marca con «Recomendado» el que mejor va con tu móvil. En móviles potentes es «Coche».

| Perfil | Qué hace |
|---|---|
| «Automático (…)» | Sigue al recomendado para este móvil. **Es la mejor opción si no sabes cuál elegir.** |
| «Coche» (recomendado) | Lo que pide el coche (en el C10, resolución completa a 30 fps y unos 5 Mbit/s), sin forzar el móvil. Es el que menos calienta y menos batería gasta. |
| «Muy alto» | Resolución completa a 60 fps con la mínima latencia. Gasta más. El C10 no muestra más de 30 fps. |
| «Alto» | Resolución completa a 60 fps sin forzar el móvil: menos batería y calor, algo más de retraso. |
| «Medio» | 720p a 45 fps constantes, recodificado en el móvil. |
| «Básico» | 720p a 30 fps, sin recodificar: lo más ligero para el móvil. En «Auto extendido» funciona como «Medio». |
| «Muy bajo» | 720p a 20 fps y poco bitrate: lo mínimo para la batería y la conexión. |

Si estás conectado, al guardar otro perfil el coche y Android Auto se reconectan solos en unos segundos.

> [!TIP]
> Si en una versión anterior elegiste «Muy alto» a mano, se queda así. Cámbialo a «Automático» o a «Coche»: es lo que
> evita que el móvil se caliente (sección 6).

**Otras opciones de «Ajustes de imagen»:**

- «Ocultar el panel lateral tras unos segundos (Auto extendido)»: activada por defecto.
- «Mantener la pantalla del móvil encendida (más calor y batería; si no, se apaga como siempre y la proyección sigue)»:
  desactivada por defecto. **Déjala desactivada** salvo que la necesites.
- «Avanzado ▾» › «Motor de protocolo»:
  - «QDAuto (recomendado)»: el motor por defecto, con reconexión sin reiniciar Android Auto y adaptación al calor.
  - «Original de headqlink»: el motor del HeadQLink original, como **plan B** si QDAuto te da problemas.

  El cambio «se aplica al volver a conectar»: pulsa «Desconectar» y luego «Conectar».
- El resto de «Avanzado» (fps, kbps, ancho, alto, perfil H.264, optimizaciones de latencia, freno) es para pruebas.
  Déjalo vacío o como viene.

### Ajustes desde el coche («Auto extendido»)

El botón «Ajustes» del panel del coche permite cambiar el perfil de imagen («Aplicar · reconecta unos segundos»),
ocultar el panel solo y las optimizaciones de latencia, y ver la conexión y el motor que se están usando. Úsalo solo con
el coche parado.

### «Idioma»

«Idioma» (Android 13 o superior): «Idioma del sistema», «Español», «English», «Português (Portugal)» o «Português
(Brasil)». La interfaz del coche cambia al volver a conectar. En Android 12 o anterior, la app usa el idioma del
sistema.

### «Lista de TV» y «Lista de radio» («Auto extendido»)

Pega la URL de una lista M3U o pulsa «Elegir archivo». Si no pones lista de radio, en el coche salen las emisoras
populares.

### «Diagnóstico»

- «Exportar log»: sección 8.
- «Prueba sin Android Auto (patrón)»: muestra en el coche una imagen de prueba en lugar de Android Auto. Sirve para
  saber si falla la conexión o Android Auto. **Apágala al terminar.**
- «Opciones de prueba (QDAuto)»: para probar el motor. Lo normal es dejarlas como vienen. Entre ellas está «Espera a
  que vuelva el coche, en segundos (5-600; vacío = 30)».

---

## 6. Calor y batería

Proyectar a la vez vídeo, Wi-Fi y GPS calienta el móvil. En la primera prueba larga, con el perfil «Muy alto» a
60 fps, el S25 Ultra llegó en 15 minutos al estado térmico «grave» de Android y después al «crítico». Por eso esta
versión trae tres cambios:

1. **Perfil «Coche»:** envía 30 fps y el bitrate que pide el C10 (unos 5 Mbit/s). Es justo lo que muestra el coche:
   más no se ve mejor y calienta más.
2. **Pantalla del móvil apagada:** la pantalla se apaga como siempre y la proyección sigue. La opción «Mantener la
   pantalla del móvil encendida» viene desactivada.
3. **Adaptación térmica automática** (Android 10 o superior y motor QDAuto; con «Básico» en el modo «Auto» no actúa,
   porque ese perfil no recodifica):

| Estado térmico de Android | Qué hace HeadQLink |
|---|---|
| Normal o ligero | Nada: los fps y el bitrate de la sesión. |
| Moderado | Baja a **24 fps** y al 70 % del bitrate. |
| Grave o peor | Baja a **20 fps** y como mucho **3 Mbit/s**. |

Sube de nivel en el acto. Vuelve a lo normal cuando el móvil lleva **60 segundos seguidos** más fresco. Lo hace sin
cortar la sesión ni reiniciar Android Auto, y sin avisos: solo notarás la imagen algo menos fluida. Queda anotado en el
log.

**Consejos**

- Usa el perfil «Automático» o «Coche».
- **Bloquea el móvil** en cuanto aparezca Android Auto.
- Evita el **sol directo**: no lo dejes en el salpicadero ni pegado al parabrisas.
- Mejor un **soporte ventilado o con ventilador**, por ejemplo en la rejilla del aire acondicionado, que una bandeja
  cerrada. Si el cargador inalámbrico lo calienta mucho, cárgalo por cable.
- Mientras proyectas, **no grabes vídeo en 4K**, no juegues ni uses otras apps pesadas en el móvil.
- Quítale una funda gruesa en verano.
- En viajes largos, lleva el móvil cargando; si puede ser, por cable.

---

## 7. Solución de problemas

Empieza siempre por el menú ⚙ › «Comprobación»: cada fila en rojo tiene su botón.

| Síntoma | Causa probable | Qué hacer |
|---|---|---|
| El coche no aparece («Buscando…» todo el rato) | El coche no está en la zona Wi-Fi del móvil, la zona Wi-Fi se apagó sola, o la app de espejo no está abierta en el coche. Con Wi-Fi Direct: la zona Wi-Fi encendida o el Wi-Fi apagado. | Mira la fila «Red»: debe decir «Zona Wi-Fi activa (…)». Une el coche a la zona Wi-Fi (Ajustes › Wi-Fi del coche) y abre la app de espejo. Quita el apagado automático de la zona Wi-Fi. A los 5 minutos sin coche HeadQLink se para: pulsa «Conectar» otra vez. |
| «El puerto 18463 está ocupado (¿QDLink abierto?)», o «Puerto 18463 · Ocupado» en la «Comprobación» | La app QDLink (u otra) está abierta en el móvil y ocupa el puerto. | «Forzar detención» de QDLink. HeadQLink lo reintenta cada 5 s y el aviso desaparece solo. |
| Pantalla negra en el coche, o la fila «Auto» con un error | Android Auto no arrancó: el móvil estaba bloqueado («No arranca: desbloquea el móvil»), falta el modo desarrollador o la accesibilidad. | Desbloquea el móvil, revisa la «Comprobación» y pulsa «Desconectar» y «Conectar». Para saber qué falla, activa Diagnóstico › «Prueba sin Android Auto (patrón)»: si ves la imagen de prueba, la conexión va bien y el problema es Android Auto (apágala después). Si nada funciona, prueba el motor «Original de headqlink». |
| La imagen va a tirones o con retraso | Zona Wi-Fi en 2,4 GHz, móvil caliente, perfil demasiado alto o pantalla del móvil encendida (con ella Android busca redes Wi-Fi a menudo). | Zona Wi-Fi en 5 GHz, perfil «Automático» o «Coche», bloquea el móvil y enfríalo. Si sigue, prueba «Medio». |
| Se desconecta a menudo | Apagado automático de la zona Wi-Fi, ahorro de batería, QDLink abierta o la app de espejo del coche cerrada. | Los cortes cortos se reconectan solos («Reconectando…»). Si son largos o frecuentes: «Batería sin restricciones», «Samsung: apps que nunca se suspenden», quita el apagado automático y cierra QDLink. Si sigue, exporta el log (sección 8). |
| La accesibilidad se desactiva sola | Android la desactiva al actualizar la app, o si la app se cerró de golpe. Algunos fabricantes también. | «Comprobación» › «Activar». Si dice «Activada pero sin funcionar», apágala y enciéndela. Si dice «Ajuste restringido», «Permitir ajustes restringidos» (sección 3). Quita las restricciones de batería. |
| Android Auto pide en el coche que mires el móvil | Es la primera vez que Android Auto ve esta «pantalla de coche», o tiene que pedirte un permiso o una confirmación. | Aparca, desbloquea el móvil y acepta lo que pida Android Auto. Normalmente solo pasa una vez. |
| El móvil se calienta mucho | Perfil «Muy alto» o «Alto», sol directo, pantalla encendida, carga inalámbrica. | Sección 6. |
| Notificación «Servidor de Android Auto abierto · Desbloquea el móvil para cerrarlo» | La sesión terminó con el móvil bloqueado y el servidor de Android Auto sigue abierto. | Desbloquea el móvil: HeadQLink lo cierra. |
| La conexión automática no arranca | El nombre Bluetooth no coincide, o Android no la deja arrancar en segundo plano. | Revisa el texto del aviso «Conexión automática», quita las restricciones de batería o toca la notificación «Toca para conectar HeadQLink». |
| No se instala o no se actualiza | Play Protect, Bloqueador automático de Samsung o una versión con otra firma. | Sección 2. |

---

## 8. Exportar el log para pedir ayuda

Si algo falla, el log ayuda mucho a encontrar la causa. **Expórtalo justo después del problema**, y si puedes apunta la
hora a la que pasó.

1. Menú ⚙ › «Diagnóstico».
2. Pulsa **«Exportar log»**. Verás «Exportando el log…» y después «Guardado en Descargas/HeadQLink/…».
3. Se abre «Compartir log de HeadQLink»: envíalo por correo, Telegram, Drive o la app que quieras, o déjalo guardado.

<!-- captura: pantalla Diagnóstico con «Exportar log» -->

**Dónde queda:** `Descargas/HeadQLink/HeadQLink-log-AAAAMMDD-HHMMSS.zip`. En Android 9 o anterior queda en la carpeta
de la app (`Android/data/com.headqlink.app/files/exports/`) y el aviso dice la ruta exacta.

**Qué lleva el ZIP**

| Archivo | Contenido |
|---|---|
| `resumen.txt` | Versión de la app, modelo de móvil y versión de Android, tus ajustes, conexión y motor, estado actual, interfaces de red, últimas sesiones y la lista de archivos incluidos. |
| `logs/` | El registro completo, con `sessions.csv` (una línea por sesión con el coche). |
| `car/`, `perf/`, `logcat/`, `crash/` | Diario del coche, rendimiento, log del sistema de la app y cierres inesperados (los más recientes). |

**Privacidad del log**

- **Sí lleva:** identificadores del coche (CarUUID, ProjectID), direcciones IP, nombres de red, modelo del móvil y tus
  ajustes.
- **No lleva:** tus viajes con GPS (`trips/` nunca se exporta), coordenadas ni destinos, ni capturas de pantalla. Los
  logs de versiones anteriores sí podían llevar alguna ubicación.
- **No se envía nada solo:** tú eliges con quién lo compartes. Mejor por privado, sin publicarlo abierto.

Puedes pedir ayuda en la página del proyecto en GitHub: [CharlysEV/headqlink](https://github.com/CharlysEV/headqlink).

---

## 9. Seguridad, privacidad y aviso legal

### Permisos y para qué se usan

| Permiso | Para qué | Cuándo |
|---|---|---|
| Accesibilidad «HeadQLink táctil» | Arrancar y parar el servidor de Android Auto pulsando su menú de desarrollador, y recibir los toques del coche. **Está limitada a Android Auto:** no ve otras apps. | Android Auto 17.4 o superior |
| Dispositivos Wi-Fi cercanos (o Ubicación en Android 12 o anterior) | Unirse a la red Wi-Fi Direct del coche. | Solo con «Wi-Fi Direct» |
| Notificaciones | Ver el estado de la conexión y los avisos. | Siempre (recomendado) |
| Bluetooth (dispositivos cercanos) | Reconocer el Bluetooth del coche. | Solo con la conexión automática |
| Batería sin restricciones | Seguir funcionando con la pantalla apagada. | Recomendado |
| Mostrar sobre otras apps | Abrir Android Auto con el móvil en segundo plano. | Opcional |
| Fotos, vídeos y ubicación | Galería y paneles de conducción. | Opcional, «Auto extendido» |
| Internet | Servicios de «Auto extendido» (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info) y tus listas de TV y radio. | Solo esas funciones |

- HeadQLink **no envía telemetría** ni tiene anuncios.
- **No comparte el GPS** con Android Auto por defecto.
- Sus servicios internos no están abiertos a otras apps.
- El APK declara otros permisos heredados de Open Headunit, como el micrófono. HeadQLink no los pide durante la
  configuración.

> [!IMPORTANT]
> **El servidor de Android Auto.** Con Android Auto 17.4 o superior, HeadQLink enciende el «servidor de unidad
> principal» del modo desarrollador de Android Auto. Mientras está encendido, escucha en todas las redes del móvil, así
> que **HeadQLink lo apaga al terminar cada sesión**. Con el móvil bloqueado no puede apagarlo, y lo deja en espera hasta
> que lo desbloquees («Auto en espera hasta que desbloquees el móvil…»). **Desbloquea el móvil después de cada viaje.**

**Cuando no la uses:**

- Pulsa «Desconectar» si abriste HeadQLink fuera del coche: empieza a conectar sola al abrirse.
- Apaga la zona Wi-Fi.
- Desactiva «Conectar al detectar el Bluetooth del coche» si no quieres que arranque sola.
- Si dejas de usarla una temporada, apaga la accesibilidad «HeadQLink táctil» y, si quieres, sal del modo desarrollador
  de Android Auto.

### Aviso legal

- **El software se ofrece «TAL CUAL», SIN GARANTÍAS de ningún tipo.** Es experimental, usa un protocolo no documentado y
  puede fallar o dejar de funcionar en cualquier momento.
- **Los autores no se hacen responsables** de daños de ningún tipo: accidentes, lesiones, daños al coche, al móvil o a
  terceros, pérdida de datos, multas, pérdida de garantías o incumplimiento de condiciones de terceros. Lo usas **bajo
  tu propia responsabilidad**.
- **No lo uses mientras conduces.** El conductor es el único responsable de cumplir las normas de tráfico. No veas
  vídeos, TV, webs ni juegos en marcha.
- HeadQLink **no está afiliado, respaldado ni patrocinado** por Leapmotor, Google, Neusoft, Open Headunit ni ninguna
  otra empresa o proyecto. Leapmotor, C10, Android Auto y QDLink son marcas de sus respectivos dueños.

### Licencia y créditos

- Licencia **[GNU AGPL-3.0](LICENSE)**. Tienes derecho a obtener el código fuente: está en
  [github.com/CharlysEV/headqlink](https://github.com/CharlysEV/headqlink). Las secciones 15 y 16 de la licencia (sin
  garantía y limitación de responsabilidad) también se aplican.
- Basado en **[headqlink](https://github.com/ryazrm/headqlink)** de **ryazrm**, que a su vez parte de
  **[Open Headunit](https://github.com/andreknieriem/open-headunit)** de **André Rinas (andreknieriem)**, y del trabajo
  original de **Michael Reid** ([aviso de copyright](COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt)). HeadQLink es un proyecto
  independiente, sin relación con Open Headunit ni con sus autores.
- Motor de protocolo **QDAuto**: [CharlysEV/qdauto](https://github.com/CharlysEV/qdauto).
