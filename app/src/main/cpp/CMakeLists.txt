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
