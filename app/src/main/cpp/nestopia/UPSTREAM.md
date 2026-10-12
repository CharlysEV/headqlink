# Nestopia (núcleo del emulador NES)

- Origen: https://github.com/libretro/nestopia, commit b9fdc9c (basado en Nestopia JG, https://gitlab.com/jgemu/nestopia).
- Copiado sin cambios: `source/core` (GPL v2 o posterior, ver `COPYING`) y los ficheros de cabecera de
  `source/nes_ntsc` (LGPL v2.1 o posterior, ver `source/nes_ntsc/license.txt`). `NstDatabase.xml` va en
  `app/src/main/assets/nes/`.
- No se usa el frontend de libretro (`libretro/`): el puente con la app es `../hql_nes.cpp`, propio de Qonecta+.
- Se compila como `libhqlnes` (ver `../CMakeLists.txt`).
