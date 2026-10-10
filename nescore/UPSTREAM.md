# nescore: halfNES

Copiado de https://github.com/andrew-hoffman/halfnes (rama principal, 2026-10-10), licencia GPL-3.0 (LICENSE).

Se quitan las partes de escritorio (halfNES.java, JavaFXNES, HeadlessNES, Twiddler, JInputHelper, ui/* salvo las
interfaces y PuppetController, video/* salvo NesColors, audio/SwingAudioImpl, cheats/ActionReplayGui) y se cambian:

- `PrefsSingleton`: mapa en memoria con la API de `java.util.prefs` (no existe en Android).
- `NES`: sin JavaFX; `NES.audioFactory` para que la app ponga su salida de audio (`AudioOutInterface`).
- `APU`: usa esa fábrica (o `SilentAudio`); sin osciloscopio.
- `PPU`: sin el visor de nametables (AWT).
- `FileUtils`: el guardado asíncrono de la SRAM en un hilo, no en la cola de AWT.
- `ProjectInfo`: constantes fijas (en halfNES las rellenaba Maven).

El dibujo: `GUIInterface.setFrame(int[] frame, …)` da 256x240 números de color NES (0-3F) con los bits de énfasis en
7-9; `NesColors.col[emph][color]` los pasa a ARGB. La app (:app, `NesScreen`) hace el resto.
