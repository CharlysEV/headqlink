# bsnes (núcleo libretro) en HeadQLink

- **Origen:** https://github.com/libretro/bsnes, commit `63ee22f7af8f5d1ada295c2c98fd7bf261fa62a7` (2026-09-04).
- **Licencia:** bsnes, GPL versión 3 (`LICENSE.txt`, `GPLv3.txt`). nall y libco, ISC. El núcleo de Game Boy (`bsnes/gb`, SameBoy, para el Super Game Boy), MIT. Todo compatible con la AGPL v3 de HeadQLink: el apartado 13 de la AGPL permite combinarla con obras GPLv3.
- **Qué se copia:** `nall/`, `libco/` y, de `bsnes/`, `sfc`, `gb`, `processor`, `emulator`, `filter`, `lzma`, `heuristics` y `target-libretro` (sin `jni/` ni `GNUmakefile`).
- **No se copia:** la interfaz de escritorio (`hiro`, `ruby`, `target-bsnes`), `shaders`, `sourcery`, `Database` (la base de placas va embebida en `target-libretro/resources.hpp`) ni `Locale`.
- **Único cambio:** `target-libretro/libretro.cpp`, `SAMPLERATE` 44100 en lugar de 48000 (el sonido de HeadQLink va a 44,1 kHz). bsnes remuestrea internamente.
- **Uso:** lo carga `hql_snes.cpp`, un frontend libretro mínimo (imagen, sonido, mando y opciones con sus valores por defecto) que usa `SnesEngine`.
