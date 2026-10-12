# bsnes (núcleo libretro) en Qonecta+

- **Origen:** https://github.com/libretro/bsnes, commit `63ee22f7af8f5d1ada295c2c98fd7bf261fa62a7` (2026-09-04).
- **Licencia:** bsnes, GPL versión 3 (`LICENSE.txt`, `GPLv3.txt`). nall y libco, ISC. El núcleo de Game Boy (`bsnes/gb`, SameBoy, para el Super Game Boy), MIT. Todo compatible con la AGPL v3 de Qonecta+: el apartado 13 de la AGPL permite combinarla con obras GPLv3.
- **Qué se copia:** `nall/`, `libco/` y, de `bsnes/`, `sfc`, `gb`, `processor`, `emulator`, `filter`, `lzma`, `heuristics` y `target-libretro` (sin `jni/` ni `GNUmakefile`).
- **No se copia:** la interfaz de escritorio (`hiro`, `ruby`, `target-bsnes`), `shaders`, `sourcery`, `Database` (la base de placas va embebida en `target-libretro/resources.hpp`) ni `Locale`.
- **Cambios:**
  - En `target-libretro/libretro.cpp`:
  - `SAMPLERATE` 44100 en lugar de 48000 (el sonido de Qonecta+ va a 44,1 kHz). bsnes remuestrea internamente.
  - `Emulator::audio.setFrequency(SAMPLERATE)` tras `configure("Audio/Frequency", …)`, que no llega al mezclador: sin ella el sonido seguía a 48 kHz.
  - `AUDIOBUFSIZE` 128 (64 pares) en lugar de un bloque de 1/50 s: así el sonido llega repartido en cada fotograma y no a trompicones. `retro_get_system_av_info` ya no sube el bloque a 1/60 s en NTSC (no cabría en el búfer) y `audio_queue` corta con `>=`.
- **Uso:** lo carga `hql_snes.cpp`, un frontend libretro mínimo (imagen, sonido, mando y opciones con sus valores por defecto) que usa `SnesEngine`.
