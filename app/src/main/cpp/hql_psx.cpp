// headqlink: emulador de PlayStation con PCSX ReARMed (pcsx_rearmed/, GPL v2 o posterior, versión libretro) y un
// frontend libretro mínimo, como el de la Super Nintendo (hql_snes.cpp):
// - imagen: RGB565 (lo que pide el núcleo), 0RGB1555 o XRGB8888, a ARGB de 32 bits; hasta 1024x512;
// - sonido: estéreo de 16 bits a 44,1 kHz, el ritmo de NativeCore;
// - mando 1: el de la PlayStation (cruceta, ✕ ○ □ △, L1 R1 L2 R2, Start y Select), del entero que manda Java;
// - opciones: los valores por defecto de cada una (el primero de su lista), como RetroArch sin tocar nada, salvo las
//   que diga files/psx/opciones.txt (para probar un juego que falla);
// - BIOS: la de verdad si está en la carpeta del sistema (scph1001.bin…); si no, la HLE que trae el núcleo;
// - tarjeta de memoria 1: la de libretro («nombre.srm»), que se lee al abrir el juego y se escribe al cerrarlo (y cada
//   cierto tiempo si cambió, por si la app se cierra de golpe).

#include <jni.h>
#include <android/log.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <vector>

#include "pcsx_rearmed/deps/libretro-common/include/libretro.h"

// El código del disco que lee el núcleo al cargarlo («SCUS94194»), de libpcsxcore/misc.c.
extern "C" char CdromId[10];

#define TAG "HQL-PSX"

namespace {

constexpr int MAX_W = 1024;
constexpr int MAX_H = 512;
// Cola del sonido (muestras sueltas, L y R por separado): lo que no cabe en lo que pide Java se queda para el siguiente.
constexpr int AUDIO_CAP = 16384;
// Cada cuántos fotogramas se mira si la tarjeta de memoria cambió (unos 10 s).
constexpr int SRM_CHECK_FRAMES = 600;

bool initDone = false;
bool loaded = false;
retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
std::string saveDir;
std::string systemDir;
std::string srmPath;
std::vector<uint8_t> srmLast;
int srmCountdown = SRM_CHECK_FRAMES;
std::map<std::string, std::string> vars;

uint32_t frame[MAX_W * MAX_H];
int frameW = 320;
int frameH = 240;
int16_t audio[AUDIO_CAP];
int audioN = 0;  // muestras sueltas (L y R cuentan por separado)
// Los dos mandos: el jugador 1 en los 16 bits de abajo (puerto 0) y el 2 en los de arriba (puerto 1); NativeCore.twoPads.
int buttons = 0;
double fps = 60.0;

// Juegos que fallan con el recompilador y van con el intérprete (que en un móvil actual también da 60 fps). Gran
// Turismo (USA, Europa, Japón) se queda en negro al empezar la carrera: salta a código que no es (una instrucción
// reservada). Además de estos, los que se aprenden solos (files/psx/interprete.txt, ver nFrame).
const char *const INTERPRETER_DB[] = {"SCUS94194", "SCES00984", "SCPS10045"};
std::string learnPath;
std::string discId;
bool interpreter = false;
bool varsChanged = false;
bool resetAfterRun = false;
volatile bool sawReserved = false;

void logCb(enum retro_log_level level, const char *fmt, ...) {
    if (level == RETRO_LOG_DEBUG) return;
    int prio = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    // El procesador fue a parar a una instrucción reservada: con el recompilador, señal de que este juego no va con él.
    if (strstr(buf, "reserved instruction")) sawReserved = true;
    __android_log_write(prio, TAG, buf);
}

bool environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto f = *static_cast<const retro_pixel_format *>(data);
            if (f != RETRO_PIXEL_FORMAT_0RGB1555 && f != RETRO_PIXEL_FORMAT_RGB565 && f != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            pixelFormat = f;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char **>(data) = systemDir.empty() ? nullptr : systemDir.c_str();
            return !systemDir.empty();
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char **>(data) = saveDir.empty() ? nullptr : saveDir.c_str();
            return !saveDir.empty();
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback *>(data)->log = logCb;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            // «Descripción; valor1|valor2|…»: el primero es el de por defecto.
            for (auto *v = static_cast<const retro_variable *>(data); v && v->key; v++) {
                const char *semi = v->value ? strstr(v->value, "; ") : nullptr;
                if (!semi) continue;
                std::string opts(semi + 2);
                size_t bar = opts.find('|');
                vars[v->key] = bar == std::string::npos ? opts : opts.substr(0, bar);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto *v = static_cast<retro_variable *>(data);
            auto it = vars.find(v->key ? v->key : "");
            if (it == vars.end()) return false;
            v->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool *>(data) = varsChanged;
            varsChanged = false;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            if (data) *static_cast<bool *>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            auto *m = static_cast<const retro_message *>(data);
            if (m && m->msg) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto *av = static_cast<const retro_system_av_info *>(data);
            if (av && av->timing.fps > 1) fps = av->timing.fps;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE:
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
            return true;
        default:
            return false;
    }
}

void videoRefresh(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (!data) return;  // fotograma repetido: se queda el anterior
    if (width > (unsigned)MAX_W) width = MAX_W;
    if (height > (unsigned)MAX_H) height = MAX_H;
    frameW = (int)width;
    frameH = (int)height;
    for (unsigned y = 0; y < height; y++) {
        const uint8_t *row = static_cast<const uint8_t *>(data) + y * pitch;
        uint32_t *out = frame + y * width;
        if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *p = reinterpret_cast<const uint32_t *>(row);
            for (unsigned x = 0; x < width; x++) out[x] = 0xFF000000u | p[x];
        } else if (pixelFormat == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *p = reinterpret_cast<const uint16_t *>(row);
            for (unsigned x = 0; x < width; x++) {
                uint32_t c = p[x];
                uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
                out[x] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
            }
        } else {
            const uint16_t *p = reinterpret_cast<const uint16_t *>(row);
            for (unsigned x = 0; x < width; x++) {
                uint32_t c = p[x];
                uint32_t r = (c >> 10) & 31, g = (c >> 5) & 31, b = c & 31;
                out[x] = 0xFF000000u | ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
            }
        }
    }
}

void audioSample(int16_t left, int16_t right) {
    if (audioN + 2 > AUDIO_CAP) return;
    audio[audioN++] = left;
    audio[audioN++] = right;
}

size_t audioBatch(const int16_t *data, size_t frames) {
    size_t room = (size_t)(AUDIO_CAP - audioN) / 2;
    size_t n = frames < room ? frames : room;
    memcpy(audio + audioN, data, n * 2 * sizeof(int16_t));
    audioN += (int)(n * 2);
    return frames;
}

void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port > 1 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    unsigned pad = (static_cast<unsigned>(buttons) >> (port * 16)) & 0xFFFF;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(pad & 0xFFFF);
    return id < 16 ? (int16_t)((pad >> id) & 1) : 0;
}

/** La tarjeta de memoria 1 a «nombre.srm», si cambió desde la última vez. */
void saveSrm() {
    if (srmPath.empty()) return;
    auto *mem = static_cast<const uint8_t *>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || size == 0) return;
    if (srmLast.size() == size && memcmp(srmLast.data(), mem, size) == 0) return;
    std::string tmp = srmPath + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "no se pudo guardar la tarjeta de memoria");
        return;
    }
    bool ok = fwrite(mem, 1, size, f) == size;
    ok = fclose(f) == 0 && ok;
    if (ok && rename(tmp.c_str(), srmPath.c_str()) == 0) {
        srmLast.assign(mem, mem + size);
        __android_log_print(ANDROID_LOG_INFO, TAG, "tarjeta de memoria guardada");
    } else {
        remove(tmp.c_str());
    }
}

void loadSrm() {
    srmLast.clear();
    auto *mem = static_cast<uint8_t *>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || size == 0 || srmPath.empty()) return;
    FILE *f = fopen(srmPath.c_str(), "rb");
    if (f) {
        size_t n = fread(mem, 1, size, f);
        fclose(f);
        __android_log_print(ANDROID_LOG_INFO, TAG, "tarjeta de memoria leída (%zu bytes)", n);
    }
    srmLast.assign(mem, mem + size);
}

void unloadGame() {
    if (!loaded) return;
    saveSrm();
    retro_unload_game();
    // Cierra también los hilos del núcleo (CD, GPU, sonido y el del recompilador): el siguiente juego empieza de cero.
    retro_deinit();
    initDone = false;
    loaded = false;
}

/**
 * Opciones del núcleo cambiadas a mano para probar un juego que falla: files/psx/opciones.txt, una por línea
 * («pcsx_rearmed_drc=disabled»). Sin el archivo, todas con su valor por defecto.
 */
void applyOverrides(const std::string &path) {
    FILE *f = fopen(path.c_str(), "r");
    if (!f) return;
    char line[256];
    while (fgets(line, sizeof(line), f)) {
        std::string l(line);
        while (!l.empty() && (l.back() == '\n' || l.back() == '\r' || l.back() == ' ')) l.pop_back();
        size_t eq = l.find('=');
        if (l.empty() || l[0] == '#' || eq == std::string::npos) continue;
        std::string key = l.substr(0, eq), value = l.substr(eq + 1);
        if (vars.find(key) == vars.end()) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "opción desconocida: %s", key.c_str());
            continue;
        }
        vars[key] = value;
        __android_log_print(ANDROID_LOG_INFO, TAG, "opción: %s = %s", key.c_str(), value.c_str());
    }
    fclose(f);
}

/** ¿Este disco va con el intérprete (de la lista o aprendido)? */
bool needsInterpreter(const std::string &id) {
    if (id.empty()) return false;
    for (const char *g : INTERPRETER_DB) {
        if (id == g) return true;
    }
    FILE *f = fopen(learnPath.c_str(), "r");
    if (!f) return false;
    char line[64];
    bool found = false;
    while (!found && fgets(line, sizeof(line), f)) {
        std::string l(line);
        while (!l.empty() && (l.back() == '\n' || l.back() == '\r' || l.back() == ' ')) l.pop_back();
        found = l == id;
    }
    fclose(f);
    return found;
}

/** Pasa al intérprete: el núcleo lo aplica al empezar el siguiente fotograma (GET_VARIABLE_UPDATE). */
void useInterpreter(const char *why) {
    if (interpreter || vars.find("pcsx_rearmed_drc") == vars.end()) return;
    vars["pcsx_rearmed_drc"] = "disabled";
    varsChanged = true;
    interpreter = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s %s: con el intérprete en vez del recompilador", discId.c_str(), why);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return "";
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return r;
}

}  // namespace

extern "C" {

/**
 * Abre el disco (ruta real: .cue, .chd, .pbp, .iso o .m3u; el núcleo lo lee él) con la carpeta de guardado (tarjeta de
 * memoria) y la del sistema (BIOS, si se ha puesto). null si arranca; si no, el error.
 */
JNIEXPORT jstring JNICALL
Java_com_headqlink_link_PsxEngine_nLoad(JNIEnv *env, jclass, jstring jpath, jstring jsaveDir, jstring jsystemDir, jstring jsrm) {
    std::string romPath = jstr(env, jpath);
    saveDir = jstr(env, jsaveDir);
    systemDir = jstr(env, jsystemDir);
    srmPath = jstr(env, jsrm);
    unloadGame();
    if (!initDone) {
        vars.clear();
        retro_set_environment(environment);
        retro_set_video_refresh(videoRefresh);
        retro_set_audio_sample(audioSample);
        retro_set_audio_sample_batch(audioBatch);
        retro_set_input_poll(inputPoll);
        retro_set_input_state(inputState);
        retro_init();
        initDone = true;
    }
    applyOverrides(systemDir + "/../opciones.txt");
    retro_game_info game{};
    game.path = romPath.c_str();
    if (!retro_load_game(&game)) return env->NewStringUTF("PCSX ReARMed no abre el disco");
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
    retro_system_av_info av{};
    retro_get_system_av_info(&av);
    fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    frameW = av.geometry.base_width > 0 ? (int)av.geometry.base_width : 320;
    frameH = av.geometry.base_height > 0 ? (int)av.geometry.base_height : 240;
    memset(frame, 0, sizeof(frame));
    audioN = 0;
    loaded = true;
    learnPath = systemDir + "/../interprete.txt";
    discId.assign(CdromId, strnlen(CdromId, sizeof(CdromId)));
    interpreter = vars.count("pcsx_rearmed_drc") && vars["pcsx_rearmed_drc"] == "disabled";
    varsChanged = false;
    resetAfterRun = false;
    sawReserved = false;
    if (needsInterpreter(discId)) useInterpreter("está en la lista");
    loadSrm();
    srmCountdown = SRM_CHECK_FRAMES;
    __android_log_print(ANDROID_LOG_INFO, TAG, "disco abierto: %dx%d a %.2f fps, %.0f Hz", frameW, frameH, fps, av.timing.sample_rate);
    return nullptr;
}

/** Un fotograma: la imagen en argb (ARGB), el sonido en audio; devuelve las muestras escritas, o -1 sin juego. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_PsxEngine_nFrame(JNIEnv *env, jclass, jintArray argb, jshortArray jaudio, jint pad) {
    if (!loaded) return -1;
    buttons = pad;
    retro_run();
    if (resetAfterRun) {
        // Ya corre con el intérprete (se cambió al empezar este fotograma): el juego vuelve a empezar desde cero.
        resetAfterRun = false;
        retro_reset();
    }
    if (sawReserved) {
        sawReserved = false;
        if (!interpreter && !discId.empty()) {
            // Se aprende para la próxima vez y se sigue ya con el intérprete, empezando de nuevo.
            FILE *f = fopen(learnPath.c_str(), "a");
            if (f) {
                fprintf(f, "%s\n", discId.c_str());
                fclose(f);
            }
            useInterpreter("falla con el recompilador");
            resetAfterRun = true;
        }
    }
    if (--srmCountdown <= 0) {
        srmCountdown = SRM_CHECK_FRAMES;
        saveSrm();
    }
    jsize cap = env->GetArrayLength(argb);
    jsize px = frameW * frameH;
    if (px <= cap) env->SetIntArrayRegion(argb, 0, px, reinterpret_cast<const jint *>(frame));
    jsize acap = env->GetArrayLength(jaudio);
    // Lo que toca a un fotograma (44100 / fps pares), sin pasarse de lo que cabe; el resto sigue en la cola.
    int want = 2 * (int)(44100.0 / (fps > 1 ? fps : 60.0) + 0.5);
    if (audioN > 4 * want) want = audioN - 2 * want;  // muy atrasada (tras una pausa): se pone al día
    jsize n = audioN < want ? audioN : want;
    if (n > acap) n = acap;
    n &= ~1;
    env->SetShortArrayRegion(jaudio, 0, n, audio);
    if (n > 0 && n < audioN) memmove(audio, audio + n, (size_t)(audioN - n) * sizeof(int16_t));
    audioN -= n;
    return n;
}

JNIEXPORT jint JNICALL
Java_com_headqlink_link_PsxEngine_nSize(JNIEnv *, jclass) {
    return (frameW << 16) | frameH;
}

/** Fotogramas por segundo de la consola (NTSC 59,94; PAL 50), por 100. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_PsxEngine_nFps100(JNIEnv *, jclass) {
    return (jint)(fps * 100.0 + 0.5);
}

JNIEXPORT void JNICALL
Java_com_headqlink_link_PsxEngine_nReset(JNIEnv *, jclass) {
    if (loaded) retro_reset();
}

/** Cierra el juego: guarda la tarjeta de memoria y para el núcleo. */
JNIEXPORT void JNICALL
Java_com_headqlink_link_PsxEngine_nUnload(JNIEnv *, jclass) {
    unloadGame();
}

/** Estado completo del juego (para «Guardar» y seguir donde se dejó), o null si no se puede. */
JNIEXPORT jbyteArray JNICALL
Java_com_headqlink_link_PsxEngine_nSaveState(JNIEnv *env, jclass) {
    if (!loaded) return nullptr;
    size_t n = retro_serialize_size();
    if (n == 0) return nullptr;
    std::vector<uint8_t> buf(n);
    if (!retro_serialize(buf.data(), n)) return nullptr;
    jbyteArray out = env->NewByteArray((jsize)n);
    if (out) env->SetByteArrayRegion(out, 0, (jsize)n, reinterpret_cast<const jbyte *>(buf.data()));
    return out;
}

/** Vuelve a un estado guardado con nSaveState. */
JNIEXPORT jboolean JNICALL
Java_com_headqlink_link_PsxEngine_nLoadState(JNIEnv *env, jclass, jbyteArray data) {
    if (!loaded || !data) return JNI_FALSE;
    jsize n = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t)n);
    env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte *>(buf.data()));
    bool ok = retro_unserialize(buf.data(), (size_t)n);
    audioN = 0;
    return ok ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
