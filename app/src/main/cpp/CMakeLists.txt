cmake_minimum_required(VERSION 3.22.1)

project("headunit")

# Loaded only when AAudio is available. Resolve libaaudio dynamically to retain minSdk 16.
add_library(hur_aaudio SHARED aaudio_output.cpp)
target_compile_features(hur_aaudio PRIVATE cxx_std_17)
target_compile_options(hur_aaudio PRIVATE -Wall -Wextra -Werror)
target_link_libraries(hur_aaudio PRIVATE log dl)

# Force 16 KB memory page size alignment for Android 15+ compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# =========================================================================
# Target 1: usbhelper (Libusb Support)
# =========================================================================

# Define imported precompiled libusb1.0.so library (which is now 16 KB page-aligned)
add_library(usb1.0 SHARED IMPORTED)

set_target_properties(usb1.0 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb1.0.so"
)

# Define our JNI wrapper library
add_library(usbhelper SHARED usbhelper.c)

# Link wrapper with libusb and Android logging framework
target_link_libraries(usbhelper
    usb1.0
    log
)

# =========================================================================
# Target 2: hur_soft_hevc (FFmpeg HEVC Software Decoder Support)
# =========================================================================

add_library(hur_soft_hevc SHARED ffmpeg_hevc_decoder.cpp)

target_compile_features(hur_soft_hevc PRIVATE cxx_std_17)
target_compile_options(hur_soft_hevc PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)
find_library(android-lib android)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(FFMPEG_INCLUDE_DIR "${FFMPEG_ROOT}/include")
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(FFMPEG_LIBS
    "${FFMPEG_LIB_DIR}/libavcodec.so"
    "${FFMPEG_LIB_DIR}/libavutil.so"
    "${FFMPEG_LIB_DIR}/libswscale.so"
)

set(HUR_FFMPEG_REQUIRED_FILES
    "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h"
    "${FFMPEG_INCLUDE_DIR}/libavutil/imgutils.h"
    "${FFMPEG_INCLUDE_DIR}/libswscale/swscale.h"
    ${FFMPEG_LIBS}
)

set(HUR_FFMPEG_READY TRUE)
foreach(required_file IN LISTS HUR_FFMPEG_REQUIRED_FILES)
    if(NOT EXISTS "${required_file}")
        set(HUR_FFMPEG_READY FALSE)
        break()
    endif()
endforeach()

message(STATUS "HUR FFmpeg for ${ANDROID_ABI}: ${HUR_FFMPEG_READY} (${FFMPEG_LIB_DIR})")

if(HUR_FFMPEG_READY)
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=1)
    target_include_directories(hur_soft_hevc PRIVATE "${FFMPEG_INCLUDE_DIR}")
    target_link_libraries(hur_soft_hevc PRIVATE ${FFMPEG_LIBS})
else()
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=0)
endif()

target_link_libraries(hur_soft_hevc PRIVATE ${log-lib} ${android-lib})

# =========================================================================
# headqlink: estado del socket con el coche (SIOCOUTQ + TCP_INFO) para la traza
# =========================================================================
add_library(hqlnet SHARED hql_netstat.c)
target_link_libraries(hqlnet log)

# =========================================================================
# headqlink: emulador NES con el núcleo de Nestopia (nestopia/, GPL v2 o posterior) y un puente JNI propio (hql_nes.cpp)
# =========================================================================
file(GLOB_RECURSE HQL_NESTOPIA_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/nestopia/source/core/*.cpp")
add_library(hqlnes SHARED hql_nes.cpp ${HQL_NESTOPIA_SOURCES})
target_include_directories(hqlnes PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(hqlnes PRIVATE -O2 -fexceptions -frtti -Wno-c++11-narrowing -w)
target_compile_definitions(hqlnes PRIVATE NDEBUG)
target_link_libraries(hqlnes PRIVATE log z)

# =========================================================================
# headqlink: emulador de Mega Drive con el núcleo ClownMDEmu (clownmdemu/, AGPL v3 o posterior) y un puente JNI propio
# (hql_md.c). core/unity.c compila todo el núcleo (68000, Z80, VDP, FM, PSG) en una sola unidad.
# =========================================================================
add_library(hqlmd SHARED hql_md.c clownmdemu/core/unity.c clownmdemu/clownresampler/clownresampler.c)
target_include_directories(hqlmd PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_options(hqlmd PRIVATE -O2 -w)
target_compile_definitions(hqlmd PRIVATE NDEBUG)
target_link_libraries(hqlmd PRIVATE log m)

# =========================================================================
# headqlink: emulador de Super Nintendo con bsnes (bsnes/, GPL v3; versión libretro, ver bsnes/UPSTREAM.md) y un
# frontend libretro mínimo (hql_snes.cpp). Fuentes y opciones como su jni/Android.mk.
# =========================================================================
set(HQL_BSNES "${CMAKE_CURRENT_SOURCE_DIR}/bsnes")
add_library(hqlsnes SHARED
    hql_snes.cpp
    ${HQL_BSNES}/libco/libco.c
    ${HQL_BSNES}/bsnes/target-libretro/libretro.cpp
    ${HQL_BSNES}/bsnes/emulator/emulator.cpp
    ${HQL_BSNES}/bsnes/filter/filter.cpp
    ${HQL_BSNES}/bsnes/lzma/lzma.cpp
    ${HQL_BSNES}/bsnes/sfc/interface/interface.cpp
    ${HQL_BSNES}/bsnes/sfc/system/system.cpp
    ${HQL_BSNES}/bsnes/sfc/controller/controller.cpp
    ${HQL_BSNES}/bsnes/sfc/cartridge/cartridge.cpp
    ${HQL_BSNES}/bsnes/sfc/memory/memory.cpp
    ${HQL_BSNES}/bsnes/sfc/cpu/cpu.cpp
    ${HQL_BSNES}/bsnes/sfc/smp/smp.cpp
    ${HQL_BSNES}/bsnes/sfc/dsp/dsp.cpp
    ${HQL_BSNES}/bsnes/sfc/ppu/ppu.cpp
    ${HQL_BSNES}/bsnes/sfc/ppu-fast/ppu.cpp
    ${HQL_BSNES}/bsnes/sfc/expansion/expansion.cpp
    ${HQL_BSNES}/bsnes/sfc/coprocessor/coprocessor.cpp
    ${HQL_BSNES}/bsnes/sfc/slot/slot.cpp
    ${HQL_BSNES}/bsnes/gb/Core/apu.c
    ${HQL_BSNES}/bsnes/gb/Core/camera.c
    ${HQL_BSNES}/bsnes/gb/Core/display.c
    ${HQL_BSNES}/bsnes/gb/Core/gb.c
    ${HQL_BSNES}/bsnes/gb/Core/joypad.c
    ${HQL_BSNES}/bsnes/gb/Core/mbc.c
    ${HQL_BSNES}/bsnes/gb/Core/memory.c
    ${HQL_BSNES}/bsnes/gb/Core/printer.c
    ${HQL_BSNES}/bsnes/gb/Core/random.c
    ${HQL_BSNES}/bsnes/gb/Core/rewind.c
    ${HQL_BSNES}/bsnes/gb/Core/save_state.c
    ${HQL_BSNES}/bsnes/gb/Core/sgb.c
    ${HQL_BSNES}/bsnes/gb/Core/sm83_cpu.c
    ${HQL_BSNES}/bsnes/gb/Core/symbol_hash.c
    ${HQL_BSNES}/bsnes/gb/Core/timing.c
    ${HQL_BSNES}/bsnes/processor/arm7tdmi/arm7tdmi.cpp
    ${HQL_BSNES}/bsnes/processor/spc700/spc700.cpp
    ${HQL_BSNES}/bsnes/processor/wdc65816/wdc65816.cpp)
target_include_directories(hqlsnes PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${HQL_BSNES}" "${HQL_BSNES}/bsnes"
    "${HQL_BSNES}/bsnes/gb" "${HQL_BSNES}/bsnes/sfc")
target_compile_options(hqlsnes PRIVATE -O2 -fomit-frame-pointer -ffast-math -w -Wno-invalid-specialization
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17 -fexceptions -frtti>)
target_compile_definitions(hqlsnes PRIVATE __LIBRETRO__ GB_INTERNAL DISABLE_DEBUGGER PLATFORM_ANDROID NDEBUG)
target_link_libraries(hqlsnes PRIVATE log)

# =========================================================================
# headqlink: emulador de PlayStation con PCSX ReARMed (pcsx_rearmed/, GPL v2 o posterior; versión libretro, ver
# pcsx_rearmed/UPSTREAM.md) y un frontend libretro mínimo (hql_psx.cpp). Fuentes y opciones como su jni/Android.mk: en
# arm64, el recompilador (new_dynarec) y la GPU NEON; en el resto, el intérprete y la misma GPU con SSE o NEON en C.
# =========================================================================
set(HQL_PSX "${CMAKE_CURRENT_SOURCE_DIR}/pcsx_rearmed")
set(HQL_PSX_LCHDR "${HQL_PSX}/deps/libchdr")
set(HQL_PSX_LRC "${HQL_PSX}/deps/libretro-common")
set(HQL_PSX_SRC
    hql_psx.cpp
    ${HQL_PSX}/libpcsxcore/cdriso.c ${HQL_PSX}/libpcsxcore/cdrom.c ${HQL_PSX}/libpcsxcore/cdrom-async.c
    ${HQL_PSX}/libpcsxcore/cheat.c ${HQL_PSX}/libpcsxcore/database.c ${HQL_PSX}/libpcsxcore/decode_xa.c
    ${HQL_PSX}/libpcsxcore/mdec.c ${HQL_PSX}/libpcsxcore/misc.c ${HQL_PSX}/libpcsxcore/pad.c
    ${HQL_PSX}/libpcsxcore/plugins.c ${HQL_PSX}/libpcsxcore/ppf.c ${HQL_PSX}/libpcsxcore/psxbios.c
    ${HQL_PSX}/libpcsxcore/psxcommon.c ${HQL_PSX}/libpcsxcore/psxcounters.c ${HQL_PSX}/libpcsxcore/psxdma.c
    ${HQL_PSX}/libpcsxcore/psxevents.c ${HQL_PSX}/libpcsxcore/psxhw.c ${HQL_PSX}/libpcsxcore/psxinterpreter.c
    ${HQL_PSX}/libpcsxcore/psxmem.c ${HQL_PSX}/libpcsxcore/r3000a.c ${HQL_PSX}/libpcsxcore/sio.c
    ${HQL_PSX}/libpcsxcore/spu.c ${HQL_PSX}/libpcsxcore/gpu.c ${HQL_PSX}/libpcsxcore/gte.c
    ${HQL_PSX}/libpcsxcore/gte_nf.c ${HQL_PSX}/libpcsxcore/gte_divider.c
    ${HQL_PSX}/libpcsxcore/new_dynarec/emu_if.c
    ${HQL_PSX}/plugins/dfsound/dma.c ${HQL_PSX}/plugins/dfsound/freeze.c ${HQL_PSX}/plugins/dfsound/registers.c
    ${HQL_PSX}/plugins/dfsound/spu.c ${HQL_PSX}/plugins/dfsound/out.c ${HQL_PSX}/plugins/dfsound/nullsnd.c
    ${HQL_PSX}/plugins/gpulib/gpu.c ${HQL_PSX}/plugins/gpulib/prim.c ${HQL_PSX}/plugins/gpulib/vout_pl.c
    ${HQL_PSX}/plugins/gpulib/gpu_async.c
    ${HQL_PSX}/plugins/cdrcimg/cdrcimg.c
    ${HQL_PSX}/plugins/gpu_neon/psx_gpu_if.c ${HQL_PSX}/plugins/gpu_neon/psx_gpu/psx_gpu_simd.c
    ${HQL_PSX}/frontend/main.c ${HQL_PSX}/frontend/plugin.c ${HQL_PSX}/frontend/cspace.c
    ${HQL_PSX}/frontend/libretro.c ${HQL_PSX}/frontend/pcsxr-threads.c
    ${HQL_PSX_LCHDR}/src/libchdr_bitstream.c ${HQL_PSX_LCHDR}/src/libchdr_cdrom.c ${HQL_PSX_LCHDR}/src/libchdr_chd.c
    ${HQL_PSX_LCHDR}/src/libchdr_codec_cdfl.c ${HQL_PSX_LCHDR}/src/libchdr_codec_cdlz.c
    ${HQL_PSX_LCHDR}/src/libchdr_codec_cdzl.c ${HQL_PSX_LCHDR}/src/libchdr_codec_cdzs.c
    ${HQL_PSX_LCHDR}/src/libchdr_codec_flac.c ${HQL_PSX_LCHDR}/src/libchdr_codec_huff.c
    ${HQL_PSX_LCHDR}/src/libchdr_codec_lzma.c ${HQL_PSX_LCHDR}/src/libchdr_codec_zlib.c
    ${HQL_PSX_LCHDR}/src/libchdr_codec_zstd.c ${HQL_PSX_LCHDR}/src/libchdr_flac.c
    ${HQL_PSX_LCHDR}/src/libchdr_huffman.c
    ${HQL_PSX_LCHDR}/deps/lzma-26.02/src/LzmaDec.c ${HQL_PSX_LCHDR}/deps/zstd-1.5.7/zstddeclib.c
    ${HQL_PSX_LRC}/compat/compat_posix_string.c ${HQL_PSX_LRC}/compat/fopen_utf8.c ${HQL_PSX_LRC}/compat/compat_strl.c
    ${HQL_PSX_LRC}/encodings/encoding_utf.c ${HQL_PSX_LRC}/file/file_path.c ${HQL_PSX_LRC}/file/file_path_io.c
    ${HQL_PSX_LRC}/file/retro_dirent.c ${HQL_PSX_LRC}/streams/file_stream.c
    ${HQL_PSX_LRC}/streams/file_stream_transforms.c ${HQL_PSX_LRC}/string/stdstring.c ${HQL_PSX_LRC}/string/rstrtod.c
    ${HQL_PSX_LRC}/time/rtime.c ${HQL_PSX_LRC}/vfs/vfs_implementation.c ${HQL_PSX_LRC}/features/features_cpu.c)
set(HQL_PSX_DEFS HAVE_CHD CHDR_SYSTEM_ZLIB HAVE_LIBRETRO NO_FRONTEND ANDROID REARMED P_HAVE_MMAP=1 P_HAVE_PTHREAD=1
    P_HAVE_POSIX_MEMALIGN=1 USE_LIBRETRO_VFS NEON_BUILD TEXTURE_CACHE_4BPP TEXTURE_CACHE_8BPP GPU_NEON SIMD_BUILD
    USE_ASYNC_CDROM USE_ASYNC_GPU USE_ASYNC_SPU HAVE_RTHREADS NDEBUG)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    enable_language(ASM)
    list(APPEND HQL_PSX_SRC
        ${HQL_PSX}/libpcsxcore/new_dynarec/new_dynarec.c ${HQL_PSX}/libpcsxcore/new_dynarec/pcsxmem.c
        ${HQL_PSX}/libpcsxcore/gte_arm64.S ${HQL_PSX}/libpcsxcore/gte_nf_arm64.S
        ${HQL_PSX}/libpcsxcore/new_dynarec/linkage_arm64.S
        ${HQL_PSX_LCHDR}/deps/lzma-26.02/Asm/arm64/LzmaDecOpt.S)
    list(APPEND HQL_PSX_DEFS NDRC_THREAD Z7_LZMA_DEC_OPT)
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Sin recompilador, pero con el ensamblador ARM que el núcleo espera en 32 bits (GTE y mezcla del sonido).
    enable_language(ASM)
    list(APPEND HQL_PSX_SRC
        ${HQL_PSX}/libpcsxcore/gte_arm.S ${HQL_PSX}/libpcsxcore/gte_nf_arm.S ${HQL_PSX}/plugins/dfsound/arm_utils.S)
    list(APPEND HQL_PSX_DEFS DRC_DISABLE)
else()
    list(APPEND HQL_PSX_DEFS DRC_DISABLE)
endif()
add_library(hqlpsx SHARED ${HQL_PSX_SRC})
target_include_directories(hqlpsx PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${HQL_PSX}/include" "${HQL_PSX}/frontend"
    "${HQL_PSX_LCHDR}/include" "${HQL_PSX_LCHDR}/deps/lzma-26.02/include" "${HQL_PSX_LCHDR}/deps/zstd-1.5.7"
    "${HQL_PSX_LRC}/include")
target_compile_options(hqlpsx PRIVATE -O2 -ffast-math -w $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Como LOCAL_ARM_MODE := arm de su Android.mk: el ensamblador en línea de gte.c es ARM, no Thumb.
    target_compile_options(hqlpsx PRIVATE -marm)
endif()
target_compile_definitions(hqlpsx PRIVATE ${HQL_PSX_DEFS})
target_link_libraries(hqlpsx PRIVATE log z m)
