# ClownMDEmu (núcleo del emulador de Mega Drive)

- Origen: https://github.com/Clownacy/clownmdemu-libretro (commit d43c270), submódulo
  https://github.com/Clownacy/clownmdemu-frontend-common (commit 347ea31), con su núcleo
  https://github.com/Clownacy/clownmdemu-core (commit 88ef45a).
- Copiado sin cambios:
  - `core/`: el núcleo, con clown68000 y clownz80. AGPL v3 o posterior (`core/LICENCE.txt`); clowncommon, 0BSD.
  - `mixer.h`: el mezclador de clownmdemu-frontend-common, AGPL v3 o posterior (`LICENCE.txt`).
  - `clownresampler/`: 0BSD (`clownresampler/LICENCE.txt`).
- No se usa el frontend de libretro: el puente con la app es `../hql_md.c`, propio de Qonecta+.
- Sin Mega CD.
- Se compila como `libhqlmd` (ver `../CMakeLists.txt`).
