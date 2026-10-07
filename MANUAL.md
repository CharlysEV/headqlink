# HeadQLink · Manual de usuario

**Idiomas:** **Español** · [Português](MANUAL.pt.md) · [English](MANUAL.en.md) · [Italiano](MANUAL.it.md)

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
| «Auto» (recomendado) | Android Auto a pantalla completa: mapas, música y mensajes. Es el más ligero para el móvil y el enlace. |
| «Auto extendido» | Lo mismo, con un panel propio a la izquierda con más información del coche y del viaje (ruta, conducción, viajes, instrumentos, eficiencia) y más funciones (fotos, vídeos, web, TV, radio, juegos). |

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
| «Cable USB» (etiqueta «Experimental») | El móvil va por cable al puerto USB de datos del coche, sin Wi-Fi. | **Experimental**: solo funciona si el coche pone el móvil en modo accesorio, y aún no se sabe si el C10 lo hace. Ver [Conexión por cable USB (experimental)](#conexión-por-cable-usb-experimental). |

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
| «Accesibilidad de HeadQLink» (Obligatorio con Android Auto 17.4 o superior; Opcional con el arranque manual) | HeadQLink la usa para arrancar Android Auto sin que lo veas y para recibir los toques del coche. En Ajustes de Android se llama **«HeadQLink táctil»**. | «Activar» y enciende «HeadQLink táctil». Ver el aviso de debajo. |
| «¿Sin accesibilidad?» (Consejo, solo si falta la accesibilidad) | Puedes arrancar tú el servidor de Android Auto y no activarla. | «Arranque manual» (ver [Sin accesibilidad](#sin-accesibilidad-arranque-manual-del-servidor-de-android-auto)). |
| «Modo desarrollador de Android Auto» (Obligatorio con 17.4 o superior) | Android Auto solo acepta una «pantalla de coche» dentro del móvil a través de su modo para desarrolladores. Se activa una vez. | «Abrir AA» y sigue la guía de debajo. «Comprobar» lo verifica (necesita la accesibilidad activada). |
| «Servidor de Android Auto» (Información, solo con el arranque manual) | Lo que se sabe **sin conectarse a él** (una conexión de prueba lo bloquearía): «En uso por HeadQLink», «No atiende» (los intentos con el coche fallan) o, si no se sabe, cómo arrancarlo. Nunca impide conectar: si no atiende, HeadQLink te avisa y lo reintenta. | «Abrir AA» (⋮ › «Parar servidor» si aparece y ⋮ › «Iniciar servidor de la unidad principal») o «Modo automático». |
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
| «Ubicación todo el tiempo» (Recomendado, «Auto extendido») | Para que los datos del coche sigan con el móvil bloqueado (ver [Qué ves y cómo se maneja](#qué-ves-y-cómo-se-maneja)). | «Abrir»: explica el porqué y abre la página de Android; elige «Permitir todo el tiempo». Si aún no tiene la ubicación, la pide antes. |

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
>
> ¿Prefieres no pasar por esto? Mira [Sin accesibilidad](#sin-accesibilidad-arranque-manual-del-servidor-de-android-auto).

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
> pulsar «Conectar») y espera al coche hasta 5 minutos (o lo que diga «Esperar al coche», si es más). Con Android Auto
> 17.4 o superior verás un momento la capa
> «Arrancando Auto…»: no toques nada. Si no estás en el coche, pulsa «Desconectar».

### Sin accesibilidad (arranque manual del servidor de Android Auto)

Si no quieres (o no puedes) activar la accesibilidad, elige **«Ajustes de imagen» › «Avanzado ▾» › «Arranque del
servidor de Android Auto» › «Manual (sin accesibilidad)»**, o pulsa «Arranque manual» en la fila «¿Sin accesibilidad?»
de la «Comprobación». Con «Auto» y «Auto extendido» HeadQLink ya no la usa para nada: la accesibilidad pasa a
«Opcional» («Solo para el modo automático») y sale la fila «Servidor de Android Auto».

> [!IMPORTANT]
> **El servidor de Android Auto sigue atendiendo mientras esté encendido, siempre que las conexiones se cierren con
> orden.** HeadQLink cierra la suya siempre así (Desconectar, fin del viaje, cambio de perfil de imagen), así que **no
> hace falta reiniciarlo entre viajes**. Lo que sí lo bloquea es una conexión **cortada a medias** (algo que se conecta y
> se va sin decir nada, o una sesión que se corta de golpe): desde ese momento acepta conexiones pero no contesta a
> nadie hasta que lo pares y lo vuelvas a iniciar. Por eso HeadQLink **no lo comprueba antes de conectar** (comprobarlo
> sería justo eso): lo dice el primer intento de verdad con el coche.

1. Arranca tú el servidor: «Abrir AA» › ⋮ (arriba a la derecha) › **«Iniciar servidor de la unidad principal»**. Si no
   ves esa opción, activa antes el modo desarrollador (10 toques en «Versión»). Si el menú dice «Parar servidor unidad
   principal», ya está encendido: no hace falta tocarlo. Al reiniciar el móvil (o al actualizarse Android Auto) puede
   apagarse: entonces hay que volver a arrancarlo.
2. Pulsa «Conectar» (o deja que arranque solo por Bluetooth o por cable). Cuando el coche conecta, HeadQLink lanza
   Android Auto y espera a que **conteste** (como mucho 6 s). Con el servidor encendido funciona también **con el móvil
   bloqueado**: sin desbloquear ni automatizar nada.
3. Si no contesta (apagado, o bloqueado por una conexión cortada a medias), verás la notificación **«Arranca (o vuelve
   a arrancar) el servidor de Android Auto»** y la fila «Auto» (y el widget) dirá «Esperando al servidor de Android
   Auto». Tócala: Android Auto › ⋮ › «Parar servidor» (si aparece) y ⋮ › «Iniciar servidor de la unidad principal», y
   vuelve. HeadQLink lo reintenta solo cada 5 s mientras el coche esté conectado; en cuanto Android Auto contesta, la
   notificación se quita sola.
4. Lo demás, como con el automático: si el coche se va, 30 s de vídeo vivo y después Android Auto en pausa; al vencer
   «Esperar al coche» (o con «Desconectar») HeadQLink cierra Android Auto con orden. Lo único que no hace es apagar el
   servidor (sin accesibilidad no puede): **se queda encendido, y el viaje siguiente lo vuelve a usar sin reiniciarlo**,
   también con el móvil bloqueado, mientras siga encendido.

Lo que cambia, y por qué el automático sigue siendo el **recomendado**:

- Hay que arrancarlo a mano cuando esté apagado (tras reiniciar el móvil, por ejemplo) y, si alguna vez se bloquea,
  pararlo y volver a iniciarlo.
- **Se queda encendido** hasta que lo pares tú, y escucha en toda la red: en una **Wi-Fi pública**, cualquier aparato de
  esa red podría intentar conectarse a él (y, si se conecta y se va sin más, dejarlo bloqueado). Páralo cuando no vayas
  a usarlo.
- El modo «App» (una app concreta en el coche) sigue necesitando la accesibilidad para los toques.

> [!TIP]
> **Instalar con Obtainium suele evitar el paso de «ajustes restringidos».** Obtainium instala con el instalador por
> sesiones de Android y, en Android 13 y 14, así la accesibilidad se suele poder activar a la primera. En Android 15 o
> superior no está garantizado.

### Versiones de Android Auto

Desde la 17.4, HeadQLink arranca Android Auto a través de su «servidor de la unidad principal» de desarrollador, y cada
versión nueva de Android Auto puede cambiarlo (los textos de su menú ⋮ o cómo atiende la conexión). Con qué versiones
se ha probado:

| Android Auto | Estado |
|---|---|
| 17.7.x | **Probada** con HeadQLink (octubre de 2026) |
| 17.8, 17.9 y posteriores | **Sin probar todavía.** Otros proyectos (Open Headunit) cuentan problemas: con la 17.8 la conexión se corta a los 1-2 s; con la 17.9, el arranque automático del servidor deja de funcionar |

La «Comprobación» lo dice en la fila «Android Auto 17.x» (probada / sin probar todavía), y el log lo apunta al arrancar
(`androidAuto=…`) y en cada sesión (columna `aa_version` de `sessions.csv`).

**Recomendado: desactiva la actualización automática de Android Auto.** Play Store › busca «Android Auto» › ⋮ (arriba a
la derecha) › quita «Habilitar actualización automática». Así una versión nueva no te deja sin imagen en el coche de un
día para otro; actualiza a mano cuando se haya probado.

Si una versión nueva deja de funcionar:

1. **«HeadQLink no encuentra el botón del servidor»**: Android Auto ha cambiado su menú. Toca «Abrir AA» y arráncalo a
   mano (⋮ › «Iniciar servidor de la unidad principal», o como se llame ahora), o toca «Arranque manual» para no
   depender de la accesibilidad (ver [Sin accesibilidad](#sin-accesibilidad-arranque-manual-del-servidor-de-android-auto)).
2. **«Android Auto se conecta y se corta a los pocos segundos»** (dos cortes seguidos en menos de 10 s): borra la caché
   de Android Auto (Ajustes › Aplicaciones › Android Auto › Almacenamiento › **Borrar caché**; no hace falta borrar los
   datos) y para e inicia su servidor (⋮). HeadQLink lo reintenta solo, como mucho cada 10 s.
3. Si sigue, **exporta el log** (sección 8) y mándalo: lleva la versión de Android Auto y, una vez, lo que HeadQLink vio
   en su menú (los textos del menú, sin datos personales), que es lo que hace falta para adaptarlo.

> [!WARNING]
> **Volver a una versión anterior de Android Auto no es «Desinstalar actualizaciones».** En muchos móviles la copia de
> fábrica de Android Auto es solo un esqueleto (1.x) que no sirve para HeadQLink: hay que instalar a mano el APK de la
> versión probada (17.7.x) que corresponda a tu móvil (arquitectura y versión de Android) y después desactivar la
> actualización automática, o Play Store la volverá a subir.

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

### Conexión por cable USB (experimental)

Como la app original del coche, HeadQLink puede llevar la imagen **por cable** en vez de por Wi-Fi: el coche pone el
móvil en «modo accesorio» y la sesión de siempre va por el cable. **Probado en el C10 (2026-10-06): 5 minutos a 40 fps,
sin un solo corte**, y además el móvil carga. Sigue marcado como experimental hasta tener más viajes.

1. Usa un **cable de datos** (no uno solo de carga) y conéctalo al **puerto USB de datos** del coche (el de la música o
   de Android Auto/CarPlay, no uno solo de carga).
2. Abre la app de espejo en la pantalla del coche.
3. Si el coche pone el móvil en modo accesorio, Android abre HeadQLink sola. Si **QDLink** también está instalada,
   Android puede preguntar **qué app abre «QDriveLink»**: elige **HeadQLink** y **«Siempre»** (o desinstala QDLink).
4. La fila «Red» dice «Cable USB: Neusoft QDriveLink 1.0» y el coche conecta como siempre.
5. **Si el coche no contesta** (la imagen no llega en unos segundos), **desenchufa y vuelve a enchufar el cable**: el
   coche solo habla en los primeros segundos tras enchufarlo, y si HeadQLink no tenía aún el cable (por ejemplo, porque
   Android preguntó qué app abrirlo), se pierde esa ocasión.

- **No hace falta la zona Wi-Fi.** Con el cable puesto, **el cable tiene prioridad**: aunque la conexión elegida sea
  otra, HeadQLink deja el Wi-Fi en pausa y, al quitar el cable, vuelve a él. Para usar solo el cable, elige «Cable USB»
  con «Cambiar».
- Quitar el cable es como si el coche se fuera (vídeo vivo 30 s y luego Android Auto en pausa); al volver a enchufarlo
  se reanuda al instante.

**Si no funciona**, exporta el log ([sección 8](#8-exportar-el-log-para-pedir-ayuda)) justo después de enchufar el
cable y envíanoslo, con la hora. Buscamos las líneas **`HQL/USB`**: qué dice Android del cable (`USB_STATE`), si el
coche puso el móvil en modo accesorio y con qué nombre (fabricante, modelo y versión). Con eso sabremos si el C10 lo
admite.

### Qué ves y cómo se maneja

- **«Auto»:** Android Auto a pantalla completa.
- **«Auto extendido»:** un panel a la izquierda, del lado del conductor, con «Auto», «Coche» (pestañas Ruta,
  Conducción, Viajes, Instrumentos, Eficiencia y Estado), «Fotos», «Vídeos», «Web», «TV», «Radio», «Juegos» y «Ajustes». Con
  Android Auto en pantalla, el panel se oculta solo a los pocos segundos; toca el borde izquierdo para que vuelva.
  Fotos, vídeos, web, TV y juegos son **solo para cuando el coche está parado**.
- **Sección «Coche»** (estimado con los sensores del móvil y servicios abiertos; con la cuenta de Leapmotor, también
  con datos reales del coche: ver [Datos reales del coche](#datos-reales-del-coche-cuenta-leapmotor)):
  - «Ruta»: destino, llegada, energía y consumo previstos; el perfil de elevación coloreado por la pendiente (en verde
    las bajadas, donde se recupera energía), el viento por tramos, los cargadores y la batería prevista; la batería al
    llegar en un anillo (indica tu % con «−5»/«+5»), el tiempo en el destino y los cargadores junto a la ruta («Ir»
    abre la navegación de Google Maps).
    - **Buscar destino:** busca solo mientras escribes (al parar un momento, desde 3 letras), con el buscador de
      direcciones de Android (el de Google) y Photon (OpenStreetMap) a la vez: direcciones con número y sitios
      («gasolinera», «Mercadona Alcalá»), cada uno con los km a los que está. Cada tecla se ilumina al pulsarla y lo
      escrito se ve también encima del teclado. Con la caja vacía salen tus **destinos recientes**. «Hablar» busca lo
      que digas (el reconocimiento de voz del móvil; necesita el permiso de micrófono).
    - **De dónde sale la previsión:** bajo las fichas, lo que suben y bajan los km que faltan y lo que cuestan esas
      cuestas («Lo que falta sube 90 m y baja 20 m: +0,5 kWh»). Con la cuenta de Leapmotor, la línea a trazos sobre las
      barras es **la media de tu coche** (lo que dice su historial: ver [Datos reales del
      coche](#datos-reales-del-coche-cuenta-leapmotor)) y la previsión se ajusta a tu consumo real; al llegar se ve
      «previsto … · real …».
    - **Filtrar cargadores:** «Filtrar» elige la potencia mínima (cualquiera, 22, 50, 100 o 150 kW) y las redes (Tesla,
      Zunder, Ionity, Iberdrola, Endesa X, Repsol, Wenea…, las que haya en la ruta, con cuántos cargadores tiene cada
      una). Se guarda, y los rayos del perfil también lo siguen. Los datos son de OpenStreetMap: la potencia o la red
      pueden faltar.
    - **Plan de carga (gratis, como ABRP):** si no llegas con el margen, la Ruta dice **dónde parar y hasta cuánto
      cargar** entre los cargadores del filtro: el mejor cargador rápido del último tramo al que llegas y solo lo que
      hace falta (el C10 carga más despacio por encima del 50 %: mejor dos paradas cortas que una larga hasta el 100 %).
      Cada parada lleva el km, con cuánto llegas, hasta cuánto cargar y los minutos; la línea de la batería sube en cada
      parada. «Ir con las paradas» abre Google Maps con ellas (hasta 3). En «Filtrar» eliges con cuánto llegar (10-30 %)
      y hasta cuánto cargar como mucho (70-100 %). En un REEV no hay plan: el generador pone lo que falte.
    - **El plan se rehace solo durante el viaje:** con la cuenta de Leapmotor compara lo que baja tu batería de verdad
      con lo previsto para esos km y ajusta lo que queda («Ajustado a lo que gastas en este viaje: +12 %»). Las paradas
      elegidas se mantienen mientras llegues a ellas con margen; si te vas quedando sin batería (o te sobra), el plan
      cambia y **te avisa**: una tarjeta ámbar en el panel del coche (también encima de Android Auto) con «Ir» (Google
      Maps con las paradas nuevas) y «Ruta», el botón «Coche» en ámbar y **un aviso por voz** («Plan de carga cambiado.
      Gastas un 12 % más de lo previsto. Nueva parada: …»). La voz se quita en «Filtrar» → «Avisar por voz si cambia el
      plan». Mientras cargas no avisa (lo has decidido tú).
  - «Conducción»: la próxima maniobra en grande, con una barra que se vacía hasta el giro, los carriles y la maniobra
    de después; la velocidad con la señal del límite, el rumbo, la altitud, la pendiente y el sol hasta la puesta.
  - «Viajes»: el viaje en curso y los guardados, con su recorrido, consumo, desnivel y coste; los km de los últimos 14
    días y los récords. **Pulsa un viaje** y se abre en grande: el recorrido sobre un mapa (OpenStreetMap), la salida, la
    llegada y **las paradas** (2 minutos o más en el mismo sitio, con su duración, la hora y el km), y sus cifras. Las
    paradas se saben en los viajes desde la 0.2.7.
  - «Instrumentos»: velocímetro con el límite y la máxima, fuerzas G con la estela de los últimos segundos y los picos,
    inclinación y una nota de suavidad (0-100) según los tirones al acelerar, frenar y girar.
  - «Eficiencia»: la potencia de los últimos 2 minutos (en verde lo que se recupera), el consumo ahora, del viaje y
    medio, en qué se va la energía, el coste del viaje, el CO₂ que no ha salido por un tubo de escape y un consejo.
  - «Estado» (con la cuenta de Leapmotor): la batería, la autonomía, la carga, las presiones, las puertas y el
    cuentakilómetros reales del coche, con la edad del dato; las presiones, sobre **un C10 en 3D que giras con el dedo**
    (las puertas y el maletero abiertos se ven abiertos, en ámbar). Sin cuenta, dice cómo configurarla.
  - **Con el móvil bloqueado: «Ubicación todo el tiempo».** Ruta, Conducción, Viajes, Instrumentos y Eficiencia usan el
    GPS del móvil. Si la ubicación de HeadQLink es solo «Permitir mientras se usa la app», Android solo le da el GPS
    con HeadQLink a la vista o si el enlace pasó a primer plano con HeadQLink delante. Cuando arranca sin ella (la
    conexión automática por Bluetooth, el widget, el arranque de Android Auto…), al bloquear el móvil la pantalla del
    coche sigue y los sensores de movimiento también, pero la velocidad, la ruta, el viaje y el consumo se paran hasta
    desbloquear. HeadQLink lo recupera solo en cuanto lo ves con el móvil desbloqueado, pero para no depender de eso la
    «Comprobación» recomienda en «Auto extendido» **«Ubicación todo el tiempo»**: «Abrir» › «Permitir todo el tiempo»
    (o Ajustes › Aplicaciones › HeadQLink › Permisos › Ubicación). En «Auto» no hace falta. HeadQLink solo usa el GPS
    con el enlace en marcha o las pantallas «Coche» abiertas.
  - **Sin GPS, sin números congelados.** Con más de 5 s sin posiciones, la velocidad dice «—» y los paneles dicen «GPS
    en pausa · móvil bloqueado (activa la ubicación «todo el tiempo»)» (o «sin GPS», por ejemplo en un túnel). Cuando
    vuelve, el tramo sin posiciones se suma al viaje en línea recta, a su velocidad media, en vez de contar como si el
    coche hubiera estado parado.
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
- Si el coche **desaparece más rato** (por ejemplo, porque lo apagas en una parada corta), a los 30 segundos HeadQLink
  deja de enviar imagen y pone **Android Auto en pausa**, pero **sigue escuchando al coche** durante «Esperar al coche»
  (**5 minutos** por defecto; se cambia en «Ajustes de imagen» › «Avanzado»). La notificación dice «Esperando al coche ·
  Android Auto en pausa». Si el coche vuelve en ese tiempo, la imagen sale **al instante**, sin desbloquear el móvil ni
  pulsar nada.
- Pasado «Esperar al coche», HeadQLink lo cierra todo: Android Auto, su servidor y la conexión. Para volver a usarla,
  pulsa «Conectar», o déjalo en manos de la conexión automática por Bluetooth. Con el arranque manual del servidor lo
  cierra todo igual salvo el servidor, que sigue encendido para el próximo viaje ([Sin
  accesibilidad](#sin-accesibilidad-arranque-manual-del-servidor-de-android-auto)).
- Si pulsas «Conectar» y en **5 minutos** (o «Esperar al coche», si es más) no aparece ningún coche, HeadQLink se para
  sola. Mientras el coche se siga anunciando, aunque no llegue a conectar, la espera vuelve a empezar.

### El vídeo se adapta al enlace

La radio entre el móvil y el coche no siempre lleva los 5 Mbit/s que pide el C10: con el coche lejos del móvil, en
2,4 GHz o con interferencias, la imagen iba a saltos y con retraso. Ahora, con los perfiles «Coche», «Automático»,
«Medio» y «Muy bajo» (los que recodifican en el móvil):

- HeadQLink mide el enlace diez veces por segundo (datos pendientes de enviar y tiempo de ida y vuelta) y, si se
  atasca de verdad (medio segundo seguido con datos acumulados o con el tiempo de ida y vuelta disparado), **baja el
  bitrate** (×0,75 cada vez, como mucho hasta la mitad de lo que pide el coche: 2,5 Mbit/s en el C10); si aun así sigue
  atascado, baja a **24 fps**. Cuando el enlace lleva 3 segundos limpio vuelve rápido (primero los fps, luego +25 % de
  bitrate cada 3 s): del mínimo a lo que pide el coche en unos 12 s. Las retransmisiones sueltas de la Wi-Fi no
  cuentan (son normales).
- Lo que no cabe en el enlace se descarta en el móvil antes de acumular retraso (como mucho ~150 ms en cola).
- Si el coche deja de leer un momento pero sigue hablando (heartbeats, toques), la sesión aguanta hasta **20 s** antes
  de darla por perdida (antes, 10 s), tirando el vídeo viejo y mandando una imagen fresca en cuanto puede.

En el log (sección 8) lo ves como `enlace: congestión (outq 96 KB 300 ms, retrans +21) → bitrate 3.6 Mbit/s`,
`enlace: enlace limpio 5 s → bitrate 4.1 Mbit/s`, y en el resumen de cada sesión `enlace: bitrate mín. 2.5 Mbit/s ·
congestiones 4`.

### Al terminar el viaje

- Apaga el coche (HeadQLink se cierra sola cuando vence «Esperar al coche», a los 5 minutos por defecto) o pulsa
  «Desconectar» para cerrarla ya.
- Si el móvil estaba bloqueado al cerrarse, verás la notificación «Auto en espera hasta que desbloquees el móvil
  (después se cierra solo)». **Desbloquea el móvil una vez** para que se cierre del todo: verás unos segundos «Cerrando
  Auto…» (sección 9). Si vuelves al coche antes de desbloquearlo y tienes la conexión automática por Bluetooth, Android
  Auto vuelve al instante, sin pasar por «Cerrando Auto…». Con el arranque manual no hace falta: Android Auto se cierra
  ya y su servidor sigue encendido.
- «Cerrando Auto…» y «Arrancando Auto…» duran unos segundos. Si algo los atasca, desaparecen solos a los 15 s; si el
  servidor de Android Auto pudo quedar encendido, verás «El servidor de Android Auto sigue encendido · Tocar para
  apagarlo»: tócala con el móvil desbloqueado.
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

- Si el Bluetooth se va sin haber llegado a conectar con el coche, HeadQLink se para. Si se va después de una sesión
  (apagas el coche), sigue esperando al coche hasta que vence «Esperar al coche».
- Si Android Auto seguía en pausa (vuelves antes de que venza «Esperar al coche», o se quedó aparcado con el móvil
  bloqueado), la imagen sale al instante, **sin desbloquear el móvil**.
- Si Android no la deja arrancar en segundo plano, verás la notificación «Coche detectado · Toca para conectar
  HeadQLink». Tócala.
- Si hay que arrancar Android Auto y el móvil está bloqueado, verás la notificación **«Desbloquea el móvil para iniciar
  Android Auto»**. Desbloquéalo (con el coche parado): HeadQLink lo arranca sola, sin abrir la app (verás un momento
  «Arrancando Auto…»).

### Widget y botón de ajustes rápidos

Para conectar sin abrir la app:

- **Widget «HeadQLink»** (pantalla de inicio): menú ⚙ › «Añadir widget a la pantalla de inicio», o mantén pulsado un
  hueco libre de la pantalla de inicio › Widgets › HeadQLink. Ocupa 4x2 y se puede reducir hasta 2x2.
  - El **botón grande** hace lo mismo que «Conectar» / «Desconectar» en la app: si falta algo obligatorio, abre la
    «Comprobación». Su color dice el estado: gris apagado, ámbar buscando o esperando al coche, verde con imagen en el
    coche (con los fps y los Mbit/s, al día cada 5 s) y rojo si hay un problema (por ejemplo, QDLink abierto).
  - Abajo, la **conexión**: «Zona Wi-Fi», «Wi-Fi Direct» o «Cable USB». Toca otra para cambiarla; con HeadQLink en
    marcha se cambia en el acto, sin reiniciar Android Auto. Si el cable del coche está puesto y en uso, sigue teniendo
    prioridad: la conexión elegida es a la que vuelve al quitarlo.
  - Arriba, el **modo** («Auto» / «Extendido»). Con HeadQLink en marcha, cambiarlo reconecta el vídeo y Android Auto,
    como al guardar otro perfil de imagen.
  - En 2x2 quedan el botón, el estado y el icono de la conexión: tócalo para pasar a la siguiente.
  - «HEADQLINK» abre la app sin conectar. El widget solo se actualiza cuando cambia algo: no gasta batería en segundo
    plano.
- **Botón «HeadQLink» de los ajustes rápidos** (Android 8+): con Android 13+, menú ⚙ › «Añadir botón a los ajustes
  rápidos»; si no, abre los ajustes rápidos, toca el lápiz (editar) y arrástralo. Al tocarlo conecta o desconecta (para
  conectar con el móvil bloqueado, primero pide desbloquearlo); manteniéndolo pulsado se abre la app. Debajo dice el
  estado («Buscando el coche…», «30 fps · 4,8 Mbit/s»…).

---

## 5. Ajustes útiles

Todo está en el menú ⚙ de la pantalla principal: «Comprobación», «Ajustes de imagen», «Lista de TV» y «Lista de radio»
(solo en «Auto extendido»), «Idioma», «Añadir widget a la pantalla de inicio», «Añadir botón a los ajustes rápidos»
(Android 13+) y «Diagnóstico».

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

**«Fluidez».** Solo cuenta con los perfiles «Coche» y «Automático»:

- «30 fps · menos calor (recomendado)»: lo que pide el coche (30 fps y unos 5 Mbit/s en el C10). Es lo que menos
  calienta el móvil.
- «60 fps · máxima fluidez»: 60 fps y entre 8 y 12 Mbit/s, como el HeadQLink original (el C10 los acepta). La imagen
  va más fluida, pero el móvil se calienta más: si llega al estado térmico «moderado», HeadQLink baja solo a 30 fps
  (sección 6). Si estás conectado, al guardar el coche y Android Auto se reconectan solos; si no, vale para la próxima
  sesión.

**Otras opciones de «Ajustes de imagen»:**

- «Ocultar el panel lateral tras unos segundos (Auto extendido)»: activada por defecto.
- «Mantener la pantalla del móvil encendida (más calor y batería; si no, se apaga como siempre y la proyección sigue)»:
  desactivada por defecto. **Déjala desactivada** salvo que la necesites.
- «Protección térmica»: qué hace HeadQLink cuando Android avisa de que el móvil se calienta (sección 6).
  - «Normal (recomendada)»: baja a 30 fps en «moderado» (solo si la sesión va a 60), a 24 fps en «grave» y a 20 fps en
    «crítico», siempre con menos bitrate.
  - «Suave»: solo baja el bitrate; los fps no bajan de 30 salvo en «crítico» (20 fps). Para quien prefiere la fluidez
    aunque el móvil vaya más caliente.
  - «Apagada»: no cambia nada, solo lo anota en el log.

  Se aplica en el acto, sin reconectar.
- «Avanzado ▾» › «Motor de protocolo»:
  - «QDAuto (recomendado)»: el motor por defecto, con reconexión sin reiniciar Android Auto y adaptación al calor.
  - «Original de headqlink»: el motor del HeadQLink original, como **plan B** si QDAuto te da problemas.

  El cambio «se aplica al volver a conectar»: pulsa «Desconectar» y luego «Conectar».
- «Avanzado ▾» › «Esperar al coche»: «1 min», «5 min (recomendado)» o «15 min». Es lo que HeadQLink sigue escuchando al
  coche, con Android Auto en pausa, después de perderlo (sección 4). Más tiempo = vuelve al instante tras paradas más
  largas; menos = el servidor de Android Auto se apaga antes. Vale ya para la próxima parada.
- «Avanzado ▾» › «Arranque del servidor de Android Auto»: «Automático (accesibilidad) · Recomendado» o «Manual (sin
  accesibilidad)» (sección 3, [Sin accesibilidad](#sin-accesibilidad-arranque-manual-del-servidor-de-android-auto)).
  Vale en el acto.
- El resto de «Avanzado» (fps, kbps, ancho, alto, perfil H.264, optimizaciones de latencia, freno) es para pruebas.
  Déjalo vacío o como viene.

### Ajustes desde el coche («Auto extendido»)

El botón «Ajustes» del panel del coche permite cambiar el perfil de imagen y la «Fluidez» («Aplicar · reconecta unos
segundos»), ocultar el panel solo y las optimizaciones de latencia, el «Precio de la electricidad» (€/kWh, para el coste
de los viajes; 0,20 de serie), y ver la conexión y el motor que se están usando. Úsalo solo con el coche parado.

### «Idioma»

«Idioma» (Android 13 o superior): «Idioma del sistema», «Español», «English», «Português (Portugal)», «Português
(Brasil)», «Italiano», «Français», «Deutsch» o «Nederlands». La interfaz del coche cambia al volver a conectar. En
Android 12 o anterior, la app usa el idioma del sistema.

### «Lista de TV» y «Lista de radio» («Auto extendido»)

Pega la URL de una lista M3U o pulsa «Elegir archivo». Si no pones lista de radio, en el coche salen las emisoras
populares.

### «Diagnóstico»

- «Exportar log»: sección 8.
- «Prueba sin Android Auto (patrón)»: muestra en el coche una imagen de prueba en lugar de Android Auto. Sirve para
  saber si falla la conexión o Android Auto. **Apágala al terminar.**
- «Opciones de prueba (QDAuto)»: para probar el motor. Lo normal es dejarlas como vienen. Entre ellas está «Espera a
  que vuelva el coche, en segundos (5-600; vacío = 30)».
- «Vista previa del modo extendido»: el panel del coche en el móvil (en horizontal), con un trayecto de demostración;
  se toca como en el coche. No se guarda nada y no está disponible con el coche conectado.

### Datos reales del coche (cuenta Leapmotor)

**Qué es.** Opcional. Con tu cuenta de Leapmotor, HeadQLink lee de la nube de Leapmotor el estado de tu coche (batería
y autonomía, carga, presiones de los neumáticos, puertas, maletero y cierre, temperaturas y cuentakilómetros) y lo usa
en «Auto extendido»:

- **«Estado»** (la sexta pestaña de «Coche»): la batería en un anillo con la autonomía y los kWh que quedan; la carga
  (corriente alterna o carga rápida, con la potencia y lo que falta) o, sin enchufar, la potencia que sale o entra; la
  temperatura de la batería (con «Batería fría: menos carga rápida y menos regeneración» por debajo de 10 °C); las
  cuatro presiones sobre **un C10 en 3D** que se gira con el dedo, en ámbar la rueda baja (por debajo de 2,1 bar,
  0,3 bar o más por debajo de las demás, o con el aviso del propio coche); las puertas y el maletero abiertos (abiertos
  en el dibujo), el cierre, el cuentakilómetros y de cuándo es el dato. Las ventanillas no las da la nube. El 3D solo se
  dibuja mientras lo mueves (no calienta el móvil); sin WebGL sale el coche visto desde arriba.
- **C10 REEV (autonomía extendida):** HeadQLink lo reconoce solo (el coche manda su depósito) y pasa la batería a
  28,4 kWh. En «Estado», el **depósito** (%, litros exactos, autonomía con gasolina y autonomía total); en «Viajes», los
  **litros** de cada viaje y los L/100 km (con el generador parado, «0 L: todo eléctrico»), y el coste con la gasolina;
  en «Ruta», la batería no baja del 20 % (ahí entra el generador) y dice cuántos litros pondrá para llegar.
- **«Ruta»**: el % de ahora es el real (desaparecen «−5»/«+5») y la batería al llegar sale de él con la capacidad de tu
  variante. La **previsión del consumo** se ajusta a tu coche: parte del consumo real de tus viajes (su historial) y la
  afinan las rutas que terminas (al llegar compara lo previsto con lo que bajó la batería). La parte de las cuestas es
  física y no se toca: si lo que queda sube, la previsión sube aunque tu media sea menor.
- **«Eficiencia»**: el consumo del viaje es el real (lo que ha bajado la batería por su capacidad, entre los km del
  cuentakilómetros) en cuanto la batería ha bajado un 2 %; antes dice «aún poco consumo para medir». Junto a la
  potencia estimada sale la real si el dato es reciente. El reparto de la energía sigue siendo estimado.
- **«Viajes»**: el consumo de cada viaje es **el que dice el coche** en su historial (marcado «COCHE»; el mismo que la
  app oficial de Leapmotor) o, si no está, el real de la bajada del % (marcado «REAL»); en «Récords», el total de los
  últimos 30 días según el coche.
- **Historial del coche:** la nube guarda tus viajes de las últimas semanas con los kWh (y en un REEV los litros) que
  mide el propio coche, y su consumo medio semanal. HeadQLink lo lee cada 30 minutos como mucho (a los 3 y 12 minutos de
  terminar un viaje) y la media semanal cada 12 horas.

Cada cifra dice de dónde sale: «real · hace 40 s» (la edad del dato: la nube no va en tiempo real y, con el coche
apagado, da lo último que supo) o «estimado».

**Cómo se configura** (⚙ › «Datos del coche (cuenta Leapmotor)», Android 6 o superior):

1. **Certificado de cliente.** Es el mismo que pide la app LMB10; HeadQLink no lo incluye ni lo proporciona. Pulsa
   «Importar certificado…» y elige en el selector de archivos app.crt y app.key: los dos a la vez (pulsación larga para
   marcar dos) o **uno detrás de otro** (dice cuál falta; «Empezar de nuevo» olvida lo elegido a medias), un .pem con
   los dos bloques o un .p12/.pfx (si tiene contraseña, la pide).
2. **Cuenta.** Tu correo y tu contraseña de Leapmotor, y «Entrar». La contraseña no se guarda.
3. **Coche.** Si la cuenta tiene uno, queda elegido; si tiene varios, elige el tuyo.
4. **Batería.** La nube no dice la variante: «C10 Life · 69,9 kWh», «C10 ProMax · 81,9 kWh», «C10 REEV · 28,4 kWh +
   gasolina» (se elige sola si el coche tiene depósito) u «Otra» (los kWh a mano). Sirve para pasar el % a kWh.
5. **«Leer estado ahora»** para comprobarlo: batería, autonomía, carga, presiones y la edad del dato.

Con «Auto extendido» en marcha (o la «Vista previa del modo extendido»), HeadQLink lee el coche cada 2 minutos (90 s con
la sección «Coche» en pantalla, como LMB10); si el coche no ha subido nada nuevo (aparcado o dormido), cada 5 y luego
cada 15 minutos; si falla, a los 2, 5 y 10 minutos; y como mucho 400 lecturas al día (contando el historial), para no
abusar de una API no oficial. Se para al desconectar. «Usar los datos reales
del coche» lo apaga sin borrar nada.

**Privacidad y seguridad.**

- Solo lectura: HeadQLink **nunca manda órdenes al coche** (ni cerrar, ni clima, ni carga, nada).
- Tus datos van **solo a los servidores de Leapmotor**. No se lee ni se guarda la posición del coche.
- El certificado, la sesión y el historial de viajes se guardan **cifrados** con una clave del Android Keystore, fuera
  de las copias de seguridad. La contraseña no se guarda: si la sesión caduca, «Estado» lo dice y se vuelve a entrar en el móvil.
- El servidor de Leapmotor usa un certificado de su propia autoridad. HeadQLink comprueba que su clave es la conocida;
  si algún día cambia, no se conecta hasta que la aceptes en el móvil (hazlo solo en una red de confianza).
- En el log: el correo enmascarado (c\*\*\*@e\*\*\*.com) y una línea por lectura (%, autonomía, carga, kW, km y
  latencia), sin tokens, VIN ni posición.
- «Cerrar sesión y borrar datos» borra de este móvil el certificado, la sesión y los ajustes de la cuenta.

> [!WARNING]
> Usa una **API no oficial** de Leapmotor: puede dejar de funcionar en cualquier momento, y HeadQLink no tiene relación
> con Leapmotor. Con el **C10 REEV**, el consumo real por la bajada del % no sirve cuando el generador carga en marcha:
> ahí manda el historial del coche y su contador de gasolina.

El protocolo viene de **[LMB10](https://github.com/txurtxil/LPB10)**, de **txurtxil** (GPL-3.0). Las señales del
depósito de los REEV y el historial de viajes, de **[leapmotor-mate](https://github.com/ProtossBlaster/leapmotor-mate)**
(ProtossBlaster), y el consumo semanal, de **[leapmotor-api](https://github.com/markoceri/leapmotor-api)** (markoceri),
los dos AGPL-3.0.

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

| Estado térmico de Android | «Protección térmica» Normal (recomendada) | «Suave» |
|---|---|---|
| Normal o ligero | Nada: los fps y el bitrate de la sesión. | Nada. |
| Moderado | A **30 fps** si la sesión va a 60 por «Fluidez» (a 30, los mismos fps) y al 80 % del bitrate. | Solo el bitrate al 80 %. |
| Grave | Baja a **24 fps** y como mucho **3,5 Mbit/s**. | **30 fps** como mucho y 3,5 Mbit/s. |
| Crítico o peor | Baja a **20 fps** y como mucho **3 Mbit/s**. | Lo mismo: 20 fps y 3 Mbit/s. |

Sube de nivel en el acto. Vuelve a lo normal cuando el móvil lleva **30 segundos seguidos** más fresco. Lo hace sin
cortar la sesión ni reiniciar Android Auto, y sin avisos: solo notarás la imagen algo menos fluida. Queda anotado en el
log. Con «Protección térmica» en «Apagada» no cambia nada (solo lo anota). Si además el enlace va justo, manda el tope
más bajo de los dos (calor o enlace).

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
| El coche no aparece («Buscando…» todo el rato) | El coche no está en la zona Wi-Fi del móvil, la zona Wi-Fi se apagó sola, o la app de espejo no está abierta en el coche. Con Wi-Fi Direct: la zona Wi-Fi encendida o el Wi-Fi apagado. | Mira la fila «Red»: debe decir «Zona Wi-Fi activa (…)». Une el coche a la zona Wi-Fi (Ajustes › Wi-Fi del coche) y abre la app de espejo. Quita el apagado automático de la zona Wi-Fi. A los 5 minutos sin anuncios del coche HeadQLink se para: pulsa «Conectar» otra vez. |
| «El puerto 18463 está ocupado (¿QDLink abierto?)», o «Puerto 18463 · Ocupado» en la «Comprobación» | La app QDLink (u otra) está abierta en el móvil y ocupa el puerto. | «Forzar detención» de QDLink. HeadQLink lo reintenta cada 5 s y el aviso desaparece solo. |
| Pantalla negra en el coche, o la fila «Auto» con un error | Android Auto no arrancó: el móvil estaba bloqueado («No arranca: desbloquea el móvil»), falta el modo desarrollador o la accesibilidad. | Desbloquea el móvil (con el aviso «Desbloquea el móvil para iniciar Android Auto», arranca solo al desbloquear). Si sigue, revisa la «Comprobación» y pulsa «Desconectar» y «Conectar». Para saber qué falla, activa Diagnóstico › «Prueba sin Android Auto (patrón)»: si ves la imagen de prueba, la conexión va bien y el problema es Android Auto (apágala después). Si nada funciona, prueba el motor «Original de headqlink». |
| La imagen va a tirones o con retraso | Zona Wi-Fi en 2,4 GHz, coche lejos del móvil, móvil caliente, perfil demasiado alto o pantalla del móvil encendida (con ella Android busca redes Wi-Fi a menudo). | Zona Wi-Fi en 5 GHz, perfil «Automático» o «Coche» (se adaptan solos al enlace: en el log, líneas `enlace: congestión…`), bloquea el móvil y enfríalo. Si sigue, prueba «Medio». |
| Muchos cortes de radio (la imagen se para medio segundo una y otra vez; en el log, líneas «Corte … RADIO» y «enlace muy congestionado») | La radio entre el móvil y el coche no da más: zona Wi-Fi en 2,4 GHz, el móvil en un bolsillo, en una bandeja metálica o lejos de la pantalla. HeadQLink ya baja la calidad todo lo que puede (hasta 1,2 Mbit/s y 20 fps), pero con la radio saturada no basta. | Pon la zona Wi-Fi en **5 GHz** (Ajustes › Zona Wi-Fi › Banda). Saca el móvil de bolsillos y bandejas metálicas y déjalo cerca de la pantalla del coche. Lo más estable es el **cable USB**. En el log, la línea «radio de la zona Wi-Fi» dice la banda y el canal cuando Android deja leerlos. |
| La imagen va menos fluida que con el HeadQLink original | El original enviaba 60 fps; HeadQLink envía 30 (lo que pide el coche) para que el móvil no se caliente. O el móvil ya está caliente y la adaptación térmica ha bajado los fps, o el enlace va justo y se han bajado el bitrate o los fps. | «Ajustes de imagen» › «Fluidez» › «60 fps · máxima fluidez» (con el perfil «Coche» o «Automático»). Si el calor baja los fps, «Protección térmica» › «Suave» (sección 6). En el log, las líneas «HQL/Térmico» («estado térmico 2» o más) y «enlace:» dicen cuál de las dos cosas pasa. |
| La imagen se congela unos 10 s y luego se reconecta | En versiones anteriores, un fotograma completo de más de ~512 KB colgaba el receptor del coche, que dejaba de leer hasta que se cortaba la conexión. **Corregido**: HeadQLink ya no envía ninguno tan grande y los mantiene en unos 300 KB como mucho. | Actualiza HeadQLink. Si te pasa con el perfil «Básico» (ahí el tamaño lo decide Android Auto), usa «Automático» o «Coche». Si sigue, exporta el log (sección 8). |
| Se desconecta a menudo | Apagado automático de la zona Wi-Fi, ahorro de batería, QDLink abierta o la app de espejo del coche cerrada. | Los cortes cortos se reconectan solos («Reconectando…»). Si son largos o frecuentes: «Batería sin restricciones», «Samsung: apps que nunca se suspenden», quita el apagado automático y cierra QDLink. Si sigue, exporta el log (sección 8). |
| Notificación «Arranca (o vuelve a arrancar) el servidor de Android Auto», o la fila «Auto» dice «Esperando al servidor de Android Auto» | Arranque manual y Android Auto no atiende: su servidor está apagado (tras reiniciar el móvil o actualizar Android Auto) o bloqueado: una conexión se cortó a medias (algo se conectó y se fue sin decir nada, o una sesión se cortó de golpe). | Toca la notificación o la fila: Android Auto › ⋮ › «Parar servidor» (si aparece) y ⋮ › «Iniciar servidor de la unidad principal». HeadQLink lo reintenta cada 5 s y sigue solo. |
| Notificación «HeadQLink no encuentra el botón del servidor» | Android Auto se ha actualizado y ha cambiado su menú ⋮ (la automatización lo busca por su texto). | «Abrir AA» y arráncalo a mano, o «Arranque manual». Mira [Versiones de Android Auto](#versiones-de-android-auto) y exporta el log (sección 8). |
| Notificación «Android Auto se conecta y se corta a los pocos segundos» | Síntoma conocido de algunas versiones nuevas de Android Auto (17.8). | Borra la caché de Android Auto (Ajustes › Aplicaciones › Android Auto › Almacenamiento › Borrar caché), para e inicia su servidor, o vuelve a una versión probada ([Versiones de Android Auto](#versiones-de-android-auto)). |
| La accesibilidad se desactiva sola | Android la desactiva al actualizar la app, o si la app se cerró de golpe. Algunos fabricantes también. | «Comprobación» › «Activar». Si dice «Activada pero sin funcionar», apágala y enciéndela. Si dice «Ajuste restringido», «Permitir ajustes restringidos» (sección 3). Quita las restricciones de batería. |
| Android Auto pide en el coche que mires el móvil | Es la primera vez que Android Auto ve esta «pantalla de coche», o tiene que pedirte un permiso o una confirmación. | Aparca, desbloquea el móvil y acepta lo que pida Android Auto. Normalmente solo pasa una vez. |
| El móvil se calienta mucho | Perfil «Muy alto» o «Alto», sol directo, pantalla encendida, carga inalámbrica. | Sección 6. |
| Notificación «Servidor de Android Auto abierto · Desbloquea el móvil para cerrarlo» | La sesión terminó con el móvil bloqueado y el servidor de Android Auto sigue abierto. | Desbloquea el móvil: HeadQLink lo cierra. |
| Tras una parada tarda mucho en volver, o hay que cerrar y abrir la app | La parada duró más que «Esperar al coche» y HeadQLink lo cerró todo; al desbloquear, primero cierra Android Auto («Cerrando Auto…») y después hay que arrancarlo otra vez. | Sube «Esperar al coche» a «15 min» (Ajustes de imagen › Avanzado) y activa la conexión automática por Bluetooth: al volver, Android Auto sale al instante sin desbloquear. Si ves «Desbloquea el móvil para iniciar Android Auto», desbloquéalo y espera: arranca solo, sin abrir la app. |
| «Cerrando Auto…» o «Arrancando Auto…» no se quita, o la notificación «El servidor de Android Auto sigue encendido · Tocar para apagarlo» | La automatización de los ajustes de Android Auto no terminó (por ejemplo, el móvil se bloqueó a mitad). | La capa se quita sola a los 15 s. Toca la notificación con el móvil desbloqueado para apagar el servidor. Si se repite, exporta el log (sección 8): las líneas «ciclo:» cuentan cada paso. |
| Los datos del coche se congelan al bloquear el móvil (velocidad, ruta, viaje, consumo; vuelven al desbloquear) | La ubicación de HeadQLink es solo «mientras se usa la app» y Android le corta el GPS con el móvil bloqueado. La pantalla del coche y los sensores de movimiento siguen; el GPS no. | «Comprobación» › «Ubicación todo el tiempo» › «Abrir» › «Permitir todo el tiempo». En el log, «GPS: ubicación todo el tiempo sí · con el móvil bloqueado llega» confirma que ya va. |
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
| Ubicación todo el tiempo | Que los paneles de «Coche» sigan con el móvil bloqueado. Solo se usa con el enlace en marcha o las pantallas «Coche» abiertas. | Recomendado, «Auto extendido» |
| Micrófono | «Hablar» en «Buscar destino»: lo entiende el reconocimiento de voz del móvil. | Opcional, «Auto extendido» |
| Internet | Servicios de «Auto extendido» (OpenStreetMap, OSRM, Open-Meteo, radio-browser.info), el buscador de destinos (Photon y el de direcciones de Android, de Google: reciben lo que escribes), el mapa de los viajes (teselas de OpenStreetMap), tus listas de TV y radio y, si la configuras, la nube de Leapmotor (datos reales del coche, solo lectura). | Solo esas funciones |

- HeadQLink **no envía telemetría** ni tiene anuncios.
- **No comparte el GPS** con Android Auto por defecto.
- Sus servicios internos no están abiertos a otras apps.
- El APK declara otros permisos heredados de Open Headunit. HeadQLink no los pide durante la configuración; el
  micrófono solo se usa para «Hablar» en el buscador (si no está permitido, el coche dice cómo darlo).
- Los **destinos recientes** del buscador y los **recorridos de los viajes** se guardan solo en el móvil (el log no
  lleva ni destinos ni posiciones).

> [!IMPORTANT]
> **El servidor de Android Auto.** Con Android Auto 17.4 o superior, HeadQLink enciende el «servidor de unidad
> principal» del modo desarrollador de Android Auto. Mientras está encendido, escucha en todas las redes del móvil, así
> que **HeadQLink lo apaga al terminar**: al pulsar «Desconectar» o cuando vence «Esperar al coche» (como mucho 15
> minutos sin coche, con Android Auto en pausa y ocupando el servidor). Con el móvil bloqueado no puede apagarlo, y lo
> deja en espera hasta que lo desbloquees («Auto en espera hasta que desbloquees el móvil…»). **Desbloquea el móvil
> después de cada viaje.**
>
> Con el arranque manual (sin accesibilidad), HeadQLink no puede apagarlo: al terminar cierra Android Auto, pero el
> servidor sigue encendido (es lo que deja empezar el próximo viaje sin tocar nada). **Páralo tú** (Android Auto › ⋮ ›
> «Parar servidor unidad principal») si no lo vas a usar, sobre todo en una Wi-Fi pública.

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
- Datos reales del coche (cuenta Leapmotor): cliente de solo lectura portado de **[LMB10](https://github.com/txurtxil/LPB10)**,
  de **txurtxil** (GPL-3.0); ver [NOTICE](NOTICE).
