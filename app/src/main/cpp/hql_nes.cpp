// HeadQLink: puente JNI propio con el núcleo de Nestopia (source/core, GPL v2 o posterior), sin el frontend de libretro.
// Un juego a la vez, todo desde el hilo del emulador (NestopiaEngine.java): cargar, un fotograma por llamada con el
// mando de los jugadores 1 y 2, reiniciar y descargar (devolviendo la partida guardada de la pila del cartucho, si la tiene).

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include "nestopia/source/core/api/NstApiEmulator.hpp"
#include "nestopia/source/core/api/NstApiMachine.hpp"
#include <sstream>
#include "nestopia/source/core/api/NstApiCartridge.hpp"
#include "nestopia/source/core/api/NstApiVideo.hpp"
#include "nestopia/source/core/api/NstApiSound.hpp"
#include "nestopia/source/core/api/NstApiInput.hpp"
#include "nestopia/source/core/api/NstApiUser.hpp"

using namespace Nes;

#define TAG "hql-nes"

namespace {

constexpr int W = Api::Video::Output::WIDTH;    // 256
constexpr int H = Api::Video::Output::HEIGHT;   // 240
constexpr int CLIP = 8;                         // como halfNES: 256x224 sin las 8 líneas de arriba y abajo
constexpr int OUT_H = H - 2 * CLIP;

Api::Emulator* emulator = nullptr;
bool dbLoaded = false;
bool loaded = false;
uint32_t pixels[W * H];
std::vector<int16_t> samples;
unsigned samplesPerFrame = 0;
Api::Video::Output* video = nullptr;
Api::Sound::Output* sound = nullptr;
Api::Input::Controllers* input = nullptr;
// Partida de la pila: la que da Java al cargar y la que el núcleo entrega al descargar.
std::string savIn;
std::string savOut;

void NST_CALLBACK onLog(void*, const char* text, unsigned long length) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%.*s", static_cast<int>(length), text);
}

void NST_CALLBACK onFile(void*, Api::User::File& file) {
    switch (file.GetAction()) {
        case Api::User::File::LOAD_BATTERY:
        case Api::User::File::LOAD_EEPROM:
            if (!savIn.empty()) file.SetContent(savIn.data(), static_cast<unsigned long>(savIn.size()));
            break;
        case Api::User::File::SAVE_BATTERY:
        case Api::User::File::SAVE_EEPROM: {
            const void* mem = nullptr;
            unsigned long size = 0;
            if (file.GetContent(mem, size) == RESULT_OK && mem && size) savOut.assign(static_cast<const char*>(mem), size);
            break;
        }
        default:
            break;
    }
}

void freeOutputs() {
    delete video;
    delete sound;
    delete input;
    video = nullptr;
    sound = nullptr;
    input = nullptr;
}

std::string narrow(const std::wstring& w) {
    std::string s;
    for (wchar_t c : w) s += (c > 0 && c < 128) ? static_cast<char>(c) : '?';
    return s;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_headqlink_link_NestopiaEngine_nInit(JNIEnv* env, jclass, jbyteArray db) {
    if (!emulator) {
        emulator = new Api::Emulator;
        Api::User::logCallback.Set(onLog, nullptr);
        Api::User::fileIoCallback.Set(onFile, nullptr);
    }
    // La base de datos de cartuchos de Nestopia: corrige cabeceras iNES mal puestas (mapper, mirroring, RAM).
    if (!dbLoaded && db) {
        jsize n = env->GetArrayLength(db);
        std::string xml(static_cast<size_t>(n), '\0');
        env->GetByteArrayRegion(db, 0, n, reinterpret_cast<jbyte*>(&xml[0]));
        std::istringstream in(xml, std::ios::in | std::ios::binary);
        Api::Cartridge::Database database(*emulator);
        if (NES_SUCCEEDED(database.Load(in))) {
            database.Enable(true);
            dbLoaded = true;
        }
    }
    return dbLoaded ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_headqlink_link_NestopiaEngine_nLoad(JNIEnv* env, jclass, jbyteArray rom, jbyteArray sav, jint rate) {
    if (!emulator) return env->NewStringUTF("sin iniciar");
    Api::Machine machine(*emulator);
    if (loaded) {
        machine.Unload();
        loaded = false;
    }
    freeOutputs();
    savIn.clear();
    savOut.clear();
    if (sav) {
        jsize n = env->GetArrayLength(sav);
        savIn.resize(static_cast<size_t>(n));
        env->GetByteArrayRegion(sav, 0, n, reinterpret_cast<jbyte*>(&savIn[0]));
    }
    jsize n = env->GetArrayLength(rom);
    std::string data(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(rom, 0, n, reinterpret_cast<jbyte*>(&data[0]));
    std::istringstream in(data, std::ios::in | std::ios::binary);

    Result r = machine.Load(in, Api::Machine::FAVORED_NES_NTSC);
    if (NES_FAILED(r)) {
        char msg[64];
        snprintf(msg, sizeof msg, "Nestopia no lo carga (error %d)", static_cast<int>(r));
        return env->NewStringUTF(msg);
    }
    loaded = true;
    machine.SetMode(machine.GetDesiredMode());

    Api::Video videoApi(*emulator);
    Api::Video::RenderState state;
    state.filter = Api::Video::RenderState::FILTER_NONE;
    state.width = W;
    state.height = H;
    state.bits.count = 32;
    state.bits.mask.r = 0x00ff0000;
    state.bits.mask.g = 0x0000ff00;
    state.bits.mask.b = 0x000000ff;
    videoApi.SetRenderState(state);

    Api::Sound soundApi(*emulator);
    soundApi.SetSampleRate(static_cast<unsigned long>(rate));

    Api::Input(*emulator).AutoSelectControllers();
    machine.Power(true);

    // Muestras por fotograma al ritmo de la consola (60 NTSC, 50 PAL), que es al que el núcleo genera el sonido.
    bool pal = machine.GetMode() == Api::Machine::PAL;
    samplesPerFrame = static_cast<unsigned>(rate) / (pal ? 50 : 60);
    samples.assign(samplesPerFrame + 64, 0);
    video = new Api::Video::Output(pixels, W * static_cast<long>(sizeof(uint32_t)));
    sound = new Api::Sound::Output(samples.data(), samplesPerFrame);
    input = new Api::Input::Controllers;
    return nullptr;
}

/** Un fotograma: emula, copia la imagen (256x224 ARGB) y el sonido (mono) y devuelve las muestras escritas. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_NestopiaEngine_nFrame(JNIEnv* env, jclass, jintArray argb, jshortArray audio, jint buttons) {
    if (!loaded || !video) return -1;
    // Jugador 1 en los 16 bits de abajo y el 2 en los de arriba (NativeCore.twoPads); el puerto 2 es el mando 2 que
    // pone AutoSelectControllers en casi todos los juegos.
    input->pad[0].buttons = static_cast<unsigned>(buttons) & 0xFF;
    input->pad[1].buttons = (static_cast<unsigned>(buttons) >> 16) & 0xFF;
    // Se repone cada fotograma: el núcleo deja en length lo que escribió y, si no, la petición iría menguando.
    sound->length[0] = samplesPerFrame;
    sound->samples[0] = samples.data();
    video->pixels = pixels;
    emulator->Execute(video, sound, input);

    jint* out = static_cast<jint*>(env->GetPrimitiveArrayCritical(argb, nullptr));
    if (out) {
        const uint32_t* src = pixels + W * CLIP;
        for (int i = 0; i < W * OUT_H; i++) out[i] = static_cast<jint>(0xFF000000u | src[i]);
        env->ReleasePrimitiveArrayCritical(argb, out, 0);
    }
    unsigned got = sound->length[0];
    jsize cap = env->GetArrayLength(audio);
    if (got > static_cast<unsigned>(cap)) got = static_cast<unsigned>(cap);
    env->SetShortArrayRegion(audio, 0, static_cast<jsize>(got), samples.data());
    return static_cast<jint>(got);
}

JNIEXPORT void JNICALL
Java_com_headqlink_link_NestopiaEngine_nReset(JNIEnv*, jclass) {
    if (loaded) Api::Machine(*emulator).Reset(false);
}

/** Descarga el juego; devuelve la partida de la pila (para guardarla en .sav) o null si no tiene. */
JNIEXPORT jbyteArray JNICALL
Java_com_headqlink_link_NestopiaEngine_nUnload(JNIEnv* env, jclass) {
    if (!loaded) return nullptr;
    savOut.clear();
    Api::Machine(*emulator).Unload();
    loaded = false;
    freeOutputs();
    if (savOut.empty()) return nullptr;
    jbyteArray a = env->NewByteArray(static_cast<jsize>(savOut.size()));
    env->SetByteArrayRegion(a, 0, static_cast<jsize>(savOut.size()), reinterpret_cast<const jbyte*>(savOut.data()));
    return a;
}

/** Para el log: placa y mapper del cartucho cargado. */
JNIEXPORT jstring JNICALL
Java_com_headqlink_link_NestopiaEngine_nInfo(JNIEnv* env, jclass) {
    if (!loaded) return env->NewStringUTF("");
    const Api::Cartridge::Profile* p = Api::Cartridge(*emulator).GetProfile();
    std::string s = "Nestopia";
    if (p) {
        char buf[48];
        snprintf(buf, sizeof buf, " · mapper %u", p->board.mapper);
        s += buf;
        if (!p->board.type.empty()) s += " · " + narrow(p->board.type);
        if (p->board.HasBattery()) s += " · con pila";
    }
    s += Api::Machine(*emulator).GetMode() == Api::Machine::PAL ? " · PAL" : " · NTSC";
    return env->NewStringUTF(s.c_str());
}

/** Estado completo del juego (para «Guardar» y seguir donde se dejó), o null si no se puede. */
JNIEXPORT jbyteArray JNICALL
Java_com_headqlink_link_NestopiaEngine_nSaveState(JNIEnv* env, jclass) {
    if (!loaded) return nullptr;
    std::stringstream ss(std::ios::in | std::ios::out | std::ios::binary);
    if (NES_FAILED(Api::Machine(*emulator).SaveState(ss, Api::Machine::NO_COMPRESSION))) return nullptr;
    std::string data = ss.str();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(data.size()));
    if (out) env->SetByteArrayRegion(out, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte*>(data.data()));
    return out;
}

/** Vuelve a un estado guardado con nSaveState. */
JNIEXPORT jboolean JNICALL
Java_com_headqlink_link_NestopiaEngine_nLoadState(JNIEnv* env, jclass, jbyteArray data) {
    if (!loaded || !data) return JNI_FALSE;
    jsize n = env->GetArrayLength(data);
    std::string buf(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(&buf[0]));
    std::stringstream ss(buf, std::ios::in | std::ios::binary);
    return NES_SUCCEEDED(Api::Machine(*emulator).LoadState(ss)) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
