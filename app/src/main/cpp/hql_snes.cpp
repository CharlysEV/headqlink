// headqlink: emulador de Super Nintendo con bsnes (bsnes/, GPL v3, versión libretro) y un frontend libretro mínimo.
//
// bsnes trae su «target-libretro»: la interfaz estándar de los núcleos de RetroArch (retro_init, retro_load_game,
// retro_run y unas llamadas de vuelta para la imagen, el sonido y el mando). Aquí está la otra mitad, la del frontend, lo
// justo para SnesEngine (NativeCore hace el resto: hilo, ritmo, Bitmap y AudioTrack):
// - imagen: 0RGB1555 (lo que da bsnes si no se pide otra cosa), RGB565 o XRGB8888, a ARGB de 32 bits;
// - sonido: estéreo de 16 bits a 44,1 kHz (SAMPLERATE en libretro.cpp);
// - mando 1: los 12 botones de la SNES (RETRO_DEVICE_ID_JOYPAD_*), del entero que manda Java;
// - opciones: los valores por defecto de cada una (el primero de su lista), como RetroArch sin tocar nada; PPU y DSP
//   rápidos ya vienen así.
// La partida del cartucho la guarda el propio bsnes («nombre.srm») en la carpeta de guardado (la de la ROM copiada).

#include <jni.h>
#include <android/log.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>

#include "bsnes/bsnes/target-libretro/libretro.h"

#define TAG "HQL-SNES"

namespace {

constexpr int MAX_W = 512;
constexpr int MAX_H = 480;
// Cola del sonido (muestras sueltas, L y R por separado). bsnes lo entrega en bloques de 882 pares, no fotograma a
// fotograma: un fotograma puede traer 0, 1 o 2 bloques. Lo que no cabe en lo que pide Java se queda para el siguiente.
constexpr int AUDIO_CAP = 16384;

bool initDone = false;
bool loaded = false;
retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
std::string saveDir;
std::map<std::string, std::string> vars;

uint32_t frame[MAX_W * MAX_H];
int frameW = 256;
int frameH = 224;
int16_t audio[AUDIO_CAP];
int audioN = 0;  // muestras sueltas (L y R cuentan por separado)
int buttons = 0;
double fps = 60.0;

void logCb(enum retro_log_level level, const char *fmt, ...) {
    int prio = level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    if (level == RETRO_LOG_DEBUG) return;
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(prio, TAG, fmt, ap);
    va_end(ap);
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
            *static_cast<bool *>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            *static_cast<bool *>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
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
    if (port != 0 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)(buttons & 0xFFF);
    return id < 16 ? (int16_t)((buttons >> id) & 1) : 0;
}

}  // namespace

extern "C" {

/** Abre la ROM (ruta real; bsnes la lee él) con la carpeta de guardado. null si arranca; si no, el error. */
JNIEXPORT jstring JNICALL
Java_com_headqlink_link_SnesEngine_nLoad(JNIEnv *env, jclass, jstring jpath, jstring jsaveDir) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    const char *dir = env->GetStringUTFChars(jsaveDir, nullptr);
    saveDir = dir ? dir : "";
    std::string romPath = path ? path : "";
    env->ReleaseStringUTFChars(jpath, path);
    env->ReleaseStringUTFChars(jsaveDir, dir);
    if (loaded) {
        retro_unload_game();
        loaded = false;
    }
    if (!initDone) {
        retro_set_environment(environment);
        retro_set_video_refresh(videoRefresh);
        retro_set_audio_sample(audioSample);
        retro_set_audio_sample_batch(audioBatch);
        retro_set_input_poll(inputPoll);
        retro_set_input_state(inputState);
        retro_init();
        initDone = true;
    }
    retro_game_info game{};
    game.path = romPath.c_str();
    if (!retro_load_game(&game)) return env->NewStringUTF("bsnes no abre la ROM");
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_system_av_info av{};
    retro_get_system_av_info(&av);
    fps = av.timing.fps > 1 ? av.timing.fps : 60.0;
    frameW = av.geometry.base_width > 0 ? (int)av.geometry.base_width : 256;
    frameH = av.geometry.base_height > 0 ? (int)av.geometry.base_height : 224;
    memset(frame, 0, sizeof(frame));
    audioN = 0;
    loaded = true;
    __android_log_print(ANDROID_LOG_INFO, TAG, "ROM abierta: %dx%d a %.2f fps, %.0f Hz", frameW, frameH, fps, av.timing.sample_rate);
    return nullptr;
}

/** Un fotograma: la imagen en argb (ARGB), el sonido en audio; devuelve las muestras escritas, o -1 sin juego. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_SnesEngine_nFrame(JNIEnv *env, jclass, jintArray argb, jshortArray jaudio, jint pad) {
    if (!loaded) return -1;
    buttons = pad;
    retro_run();
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
Java_com_headqlink_link_SnesEngine_nSize(JNIEnv *, jclass) {
    return (frameW << 16) | frameH;
}

/** Fotogramas por segundo de la consola (NTSC 60,1; PAL 50,0), por 100. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_SnesEngine_nFps100(JNIEnv *, jclass) {
    return (jint)(fps * 100.0 + 0.5);
}

JNIEXPORT void JNICALL
Java_com_headqlink_link_SnesEngine_nReset(JNIEnv *, jclass) {
    if (loaded) retro_reset();
}

/** Descarga el juego: bsnes guarda la partida del cartucho («nombre.srm») en la carpeta de guardado. */
JNIEXPORT void JNICALL
Java_com_headqlink_link_SnesEngine_nUnload(JNIEnv *, jclass) {
    if (!loaded) return;
    retro_unload_game();
    loaded = false;
}

}  // extern "C"
