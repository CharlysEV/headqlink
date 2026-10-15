# Planificador desde Google Maps: escenarios y pruebas

Con el interruptor **Ajustes › Viaje › «Planificador desde Google Maps»** (apagado de fábrica), cuando Google Maps
navega en Android Auto sin destino en el planificador:

1. La app lee el destino de la tarjeta de Maps: toca el icono de la ruta, lo lee con el OCR del móvil y la cierra con
   un «atrás». Antes de tocar nada pone Maps a pantalla completa: si AA tiene la pantalla partida (Maps y la tarjeta
   de música), pulsa una vez el icono de Maps de su barra, que alterna entre partida y completa, y lo comprueba. Así
   ningún toque cae en otra app. Antes espera a que el conductor lleve 4 s sin tocar la pantalla del coche, y solo lo hace por debajo de
   20 km/h.
2. Pregunta «¿Activo el planificador? Google Maps va a ‹sitio› (411 km)», con 15 s de cuenta atrás. Si no se ha podido
   leer, pregunta sin el sitio y lee tras el «Sí».
3. Con «Sí» calcula el plan de carga y, si hacen falta paradas, ofrece añadirlas a Maps.
4. Con «Añadir a Maps» quita la ruta de Maps, le manda la del plan con las paradas y pulsa su «Iniciar». Todo se maneja con
tarjetas flotantes encima de Android Auto: el panel no se despliega ni se recoge (AA no se recoloca).

Las tarjetas van en la columna de la derecha de la zona de AA (donde suele estar la tarjeta de música o el mapa sin
botones de Maps) y no se pisan. Se pueden ver varias a la vez:

- la de la próxima parada, donde la deje el conductor (arriba a la derecha de fábrica);
- la del planificador, en el centro de la columna o, si choca con la anterior, justo debajo o encima;
- el cartel de la imagen, arriba en el centro.

Al soltar la de la próxima parada encima de otra, la del planificador se aparta. Por eso el relay pinta varios trozos de
nuestra capa encima de AA (`GlFrameRelay.setFloatRects`); antes era uno solo.

Código:

- `RoutePlanner.guessFromMaps`: decide cuándo preguntar o leer.
- `MapsReader`: lee la tarjeta, quita la navegación y pulsa «Iniciar».
- `MapsCard`: lectura pura de las líneas del OCR. Tests en `MapsCardTest`.
- `CarUi.showPlannerAsk` y demás `show*`: las tarjetas.
- `DestGuess`: reglas de distancia.

El log de la app apunta cada paso sin datos personales: «ruta: …», «Maps: …» y «CarUi: tarjeta «…» en x,y AxB». Nunca
apunta direcciones ni el texto de las tarjetas.

## Escenarios

Pruebas en el coche virtual (versión 0.2.63 de pruebas, 15-10-2026), salvo donde se indica. Se probaron dos variantes y
se eligió la A: «A», leer Maps antes de preguntar; «B», preguntar primero y leer tras el «Sí». También se eligió pulsar
«Iniciar» solo.

| # | Escenario | Lo que debe pasar | Resultado |
|---|-----------|-------------------|-----------|
| 1 | Interruptor apagado | Maps navega y no aparece ninguna tarjeta | ✅ |
| 2 | Navegación nueva, a menos de 20 km/h | La pregunta sale a los 3-4 s de empezar Maps | ✅ |
| 3 | «No» | La tarjeta se va; no vuelve a preguntar en esa navegación | ✅ |
| 4 | Sin respuesta en 15 s (cuenta atrás) | Igual que «No» | ✅ |
| 5 | Maps ya navegaba al entrar | A los 10 s: «Google Maps ya tenía una ruta», con «Planificar esta ruta» / «No»; nunca se quita | ✅ |
| 6 | A más de 20 km/h | No pregunta hasta ir por debajo de 20 | ⏳ en coche real (el coche virtual va parado) |
| 7 | Destino a menos de 0,8 km | No pregunta | ⏳ (regla en `DestGuess.MIN_ROAD_KM`) |
| 8 | Con una pantalla nuestra delante (Juegos, Ajustes) | La pregunta sale encima de esa pantalla, a la derecha; tras el «Sí», lee Maps cuando vuelve AA | ✅ (ver nota 2) |
| 9 | Otro destino en Maps (navegación nueva) | Vuelve a preguntar | ✅ |
| 10 | Maps deja de navegar | El planificador quita el destino que había leído | ✅ |
| 11 | Destino sin paradas | Tarjeta verde «Ruta a ‹sitio›» con «Vale» / «No es» | ✅ (40 km) |
| 12 | Destino con paradas | Tarjeta naranja «¿Añado la parada de carga a Google Maps?» con «Añadir a Maps» / «No» | ✅ en A y en B |
| 12-A | Variante A | La pregunta dice a dónde va: «Google Maps va a ‹sitio› (411 km)» | ✅ |
| 13 | No se puede leer la tarjeta de Maps | «¿A dónde vas?» con «Buscar» / «Cerrar» | ⏳ (no se ha dado; `MapsCardTest`) |
| 14 | Lo leído no cuadra con los km de Maps | Igual que el 13 | ✅ (ruta de Maps con paradas: su tarjeta enseña la primera parada y no cuadra con el total) |
| 15 | Nombre del destino | El del sitio en la tarjeta de Maps («Almacenes Sol Gran Vía», inventado), no el número del portal («7-9») | ✅ (fallo arreglado; test `theDestinationIsCalledByThePlaceNameNotTheHouseNumber`) |
| 16 | «Añadir a Maps» | Quita la ruta de Maps (su X) y la pone con las paradas | ✅ |
| 16-a | …sin «pulsar Iniciar» | Maps enseña «Detalles de la ruta» y espera a que el conductor pulse «Iniciar» | ✅ |
| 16-b | …con «pulsar Iniciar» | La app pulsa «Iniciar» y Maps navega hacia la primera parada | ✅ al primer intento |
| 17 | «No» a las paradas | Maps sigue con su ruta | ✅ |
| 18 | No encuentra la X de Maps | Tarjeta «No he podido cambiar la ruta» con «Reintentar» | ⏳ (no se ha dado) |
| 18-b | La ruta de Maps ya lleva paradas | Su X abre un menú («Quitar parada» / «Salir de la navegación»): la app pulsa «Salir de la navegación» | ✅ test `theExitButtonOfTheMenuWhenTheRouteHasStops`; menú visto en el coche virtual |
| 19 | Cambio de día a noche (o al revés) con una tarjeta en pantalla | La tarjeta se vuelve a pintar con su texto, sus botones y los colores nuevos; sus botones siguen funcionando | ✅ (antes se quedaba en blanco) |
| 20 | Llamada con una tarjeta en pantalla | La llamada pasa por delante; la tarjeta sigue o caduca sola | ⏳ |
| 21 | App en otro idioma | Las tarjetas caben y se leen | ✅ chino; ⏳ inglés |
| 22 | Varias navegaciones seguidas sin cerrar el coche virtual | Cada una se pregunta una vez | ✅ |
| 23 | El planificador ya tiene destino | No pregunta | ✅ |
| 24 | Maps empezó con destino aquí y luego se quita ese destino | Pregunta por esa navegación | ✅ (fallo arreglado: el «ya preguntado» va con la navegación de Maps, por su inicio) |
| 25 | Tarjeta del planificador y de la próxima parada a la vez | Las dos en la columna derecha, sin pisarse | ✅ |
| 26 | Arrastrar la de la próxima parada encima de la del planificador | Al soltarla, la del planificador se aparta (debajo o encima) | ✅ |
| 27 | Pantalla partida (Maps + música) al ir a leer o a quitar la ruta | Un toque al icono de Maps; comprobado a pantalla completa, lee. Si sigue partida o no ve el icono, no toca nada | ✅ (tests `splitScreenIsSeenByTheGapBetweenTheTwoApps`, `theMapsIconIsTheOneWithTheFourGoogleColours`) |
| 28 | Maps a pantalla completa ya | No toca su icono (lo volvería a partir) | ✅ |
| 29 | «Detalles de la ruta» tras «Añadir a Maps», con pantalla partida | Ahí el icono de Maps no la pone completa; «Iniciar» se pulsa solo donde la copia de ese momento enseña su texto | ✅ (Maps navega con la parada) |

## Notas

1. **La tarjeta verde «Ruta a…»** se queda mientras se calcula el plan (unos 15 s si los cargadores de OpenStreetMap
   tardan) y, si hay paradas, la sustituye la naranja en el mismo sitio. En ese cambio se puede ver un instante la verde,
   más baja, «debajo» de la naranja. El log confirma que nunca hay dos a la vez.
2. **Encima de nuestras pantallas**, la tarjeta tapa lo que haya a la derecha (en Ajustes, los interruptores de esa zona)
   hasta que se contesta o caduca.
5. **Con paradas en la ruta de Maps** no se puede leer su destino: la tarjeta de Maps enseña la primera parada. La app
   no se inventa nada y pregunta «¿A dónde vas?».
6. **Un destino nuevo mientras Maps navega** suele quedar como parada de la ruta que ya tenía Maps: para la app es la
   misma navegación y no vuelve a preguntar.
3. **Coche parado y Maps**: con el coche quieto, Maps casi no actualiza su notificación. Tras reinstalar la app con
   una navegación ya en marcha, la app no la ve hasta que Maps la actualiza o empieza otra.
4. Al quitar la navegación de Maps con «atrás» nunca se pulsa dos veces: un segundo «atrás» llegó a quitar la
   navegación en el coche.

## Decidido (15-10-2026)

- Variante **A**: la pregunta dice el sitio. La lectura de Maps espera 4 s sin toques en la pantalla del coche.
- La app pulsa sola el **«Iniciar»** de Maps tras «Añadir a Maps». Sin él, Maps se queda en «Detalles de la ruta» y no
  guía.
