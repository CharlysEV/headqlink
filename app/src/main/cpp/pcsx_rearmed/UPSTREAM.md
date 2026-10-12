# PCSX ReARMed (núcleo libretro) en Qonecta+

- **Origen:** https://github.com/libretro/pcsx_rearmed, commit `c8816799b50388e61cfe237fe2cdbb7d8175f20a` (2026-10-02).
- **Licencia:** GPL versión 2 o posterior (`COPYING`; cada archivo lo dice en su cabecera). Se usa bajo la GPL v3, que el apartado 13 de la AGPL v3 de Qonecta+ permite combinar. Dependencias incluidas: libchdr (BSD de 3 cláusulas), LZMA SDK (dominio público), zstd (BSD) y libretro-common (MIT).
- **Qué se copia:** `include/`, `libpcsxcore/` (de `lightrec/`, solo `plugin.h` y `mem.h`, que se incluyen aunque no se use), `frontend/` (sin las carpetas de otras plataformas ni `libpicofe`), `plugins/` (`dfsound`, `gpulib`, `gpu_neon`, `cdrcimg`), `deps/libchdr` (sin pruebas) y de `deps/libretro-common` solo lo que usa el núcleo.
- **Cambios:** ninguno en el código. `include/revision.h` se escribe a mano (lo genera su `jni/Android.mk` con git).
- **Compilación:** `app/src/main/cpp/CMakeLists.txt`, objetivo `hqlpsx`, con las fuentes y opciones de su `jni/Android.mk`: en arm64, el recompilador `new_dynarec` (con su hilo) y la GPU `gpu_neon`; en las demás arquitecturas, el intérprete y la misma GPU con SSE o NEON en C. CD, GPU y sonido en sus hilos (`USE_ASYNC_*`).
- **Uso:** lo carga `hql_psx.cpp`, un frontend libretro mínimo (imagen, sonido, mando, opciones con sus valores por defecto y la tarjeta de memoria) que usa `PsxEngine`.
