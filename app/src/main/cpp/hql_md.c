/* HeadQLink: puente JNI propio con el núcleo de Mega Drive ClownMDEmu (clownmdemu/, AGPL v3 o posterior, como la app).
   Un juego a la vez, todo desde el hilo del emulador (MdEngine.java): cargar, un fotograma por llamada con el mando del
   jugador 1, reiniciar y descargar (devolviendo la partida guardada del cartucho, si la tiene). El sonido sale del
   mezclador de ClownMDEmu al ritmo del PSG (~224 kHz) y se pasa a 44,1 kHz con clownresampler. Sin Mega CD. */

#include <jni.h>
#include <android/log.h>

#include <stdlib.h>
#include <string.h>

#include "clownmdemu/core/source/clownmdemu.h"
#define MIXER_IMPLEMENTATION
#include "clownmdemu/mixer.h"
#include "clownmdemu/clownresampler/clownresampler.h"

#define TAG "hql-md"
#define MAX_W 320
#define MAX_H 480
#define OUT_RATE 44100

static ClownMDEmu emu;
static ClownMDEmu_Callbacks callbacks;
static Mixer_State mixer;
static cc_bool mixerReady;
static ClownResampler_Precomputed precomputed;
static ClownResampler_HighLevel_State resampler;
static cc_bool initDone;
static cc_bool loaded;
static cc_bool pal;

static cc_u16l *rom;
static uint32_t colours[16 * 4 * 3];
static uint32_t frame[MAX_W * MAX_H];
static unsigned frameW = 320, frameH = 224;
static int buttons;

/* Sonido del fotograma: lo que sale del mezclador (estéreo, a la frecuencia del PSG) y lo ya pasado a 44,1 kHz. */
static const cc_s16l *mixIn;
static size_t mixInFrames;
static int16_t outAudio[(OUT_RATE / 50 + 64) * 2];
static size_t outFrames;

static void Log(void *user_data, const char *format, va_list arg) {
    (void)user_data;
    __android_log_vprint(ANDROID_LOG_INFO, TAG, format, arg);
}

static void ColourUpdated(void *user_data, cc_u16f index, cc_u16f colour) {
    (void)user_data;
    const unsigned r = (colour >> 0) & 0xF, g = (colour >> 4) & 0xF, b = (colour >> 8) & 0xF;
    colours[index] = 0xFF000000u | (((r << 4) | r) << 16) | (((g << 4) | g) << 8) | ((b << 4) | b);
}

static void ScanlineRendered(void *user_data, cc_u16f scanline, const cc_u8l *pixels, cc_u16f left, cc_u16f right,
                             cc_u16f width, cc_u16f height) {
    (void)user_data;
    if (scanline == 0) {
        frameW = width > MAX_W ? MAX_W : width;
        frameH = height > MAX_H ? MAX_H : height;
    }
    if (scanline >= frameH || right > MAX_W) return;
    uint32_t *dst = frame + scanline * frameW;
    for (cc_u16f x = left; x < right; x++) dst[x] = colours[pixels[x]];
}

static cc_bool InputRequested(void *user_data, cc_u8f player, ClownMDEmu_Button b) {
    (void)user_data;
    if (player != 0) return cc_false;
    return (buttons >> b) & 1 ? cc_true : cc_false;
}

static void FmAudio(void *u, ClownMDEmu *e, size_t n, void (*gen)(ClownMDEmu *, cc_s16l *, size_t)) {
    (void)u;
    gen(e, Mixer_AllocateFMSamples(&mixer, n), n);
}

static void PsgAudio(void *u, ClownMDEmu *e, size_t n, void (*gen)(ClownMDEmu *, cc_s16l *, size_t)) {
    (void)u;
    gen(e, Mixer_AllocatePSGSamples(&mixer, n), n);
}

static void PcmAudio(void *u, ClownMDEmu *e, size_t n, void (*gen)(ClownMDEmu *, cc_s16l *, size_t)) {
    (void)u;
    gen(e, Mixer_AllocatePCMSamples(&mixer, n), n);
}

static void CddaAudio(void *u, ClownMDEmu *e, size_t n, void (*gen)(ClownMDEmu *, cc_s16l *, size_t)) {
    (void)u;
    gen(e, Mixer_AllocateCDDASamples(&mixer, n), n);
}

/* Sin Mega CD ni BuRAM: el resto de llamadas no hacen nada. */
static void CdSeeked(void *u, cc_u32f s) { (void)u; (void)s; }
static void CdSectorRead(void *u, cc_u16l *buffer) { (void)u; memset(buffer, 0, 2048); }
static cc_bool CdTrackSeeked(void *u, cc_u16f t, ClownMDEmu_CDDAMode m) { (void)u; (void)t; (void)m; return cc_false; }
static size_t CdAudioRead(void *u, cc_s16l *b, size_t n) { (void)u; (void)b; (void)n; return 0; }
static cc_bool SaveOpenRead(void *u, const char *f) { (void)u; (void)f; return cc_false; }
static cc_s16f SaveRead(void *u) { (void)u; return -1; }
static cc_bool SaveOpenWrite(void *u, const char *f) { (void)u; (void)f; return cc_false; }
static void SaveWritten(void *u, cc_u8f b) { (void)u; (void)b; }
static void SaveClosed(void *u) { (void)u; }
static cc_bool SaveRemoved(void *u, const char *f) { (void)u; (void)f; return cc_false; }
static cc_bool SaveSize(void *u, const char *f, size_t *s) { (void)u; (void)f; (void)s; return cc_false; }

static size_t ResamplerInput(void *user_data, cc_s16l *buffer, size_t total_frames) {
    (void)user_data;
    size_t n = total_frames < mixInFrames ? total_frames : mixInFrames;
    memcpy(buffer, mixIn, n * 2 * sizeof(cc_s16l));
    mixIn += n * 2;
    mixInFrames -= n;
    return n;
}

static cc_bool ResamplerOutput(void *user_data, const cc_s32f *f, cc_u8f total_samples) {
    (void)user_data;
    (void)total_samples;
    if (outFrames >= sizeof(outAudio) / sizeof(outAudio[0]) / 2) return cc_false;
    for (int i = 0; i < 2; i++) {
        cc_s32f v = f[i];
        outAudio[outFrames * 2 + i] = (int16_t)(v > 32767 ? 32767 : v < -32768 ? -32768 : v);
    }
    outFrames++;
    return cc_true;
}

static void MixerDone(void *user_data, const cc_s16l *samples, size_t total_frames) {
    (void)user_data;
    mixIn = samples;
    mixInFrames = total_frames;
    ClownResampler_HighLevel_Resample(&resampler, &precomputed, ResamplerInput, ResamplerOutput, NULL);
}

static void setupAudio(cc_bool palMode) {
    if (mixerReady) Mixer_Deinitialise(&mixer);
    mixerReady = Mixer_Initialise(&mixer, palMode);
    cc_u32f in = palMode ? MIXER_OUTPUT_SAMPLE_RATE_PAL : MIXER_OUTPUT_SAMPLE_RATE_NTSC;
    ClownResampler_HighLevel_Init(&resampler, 2, in, OUT_RATE, OUT_RATE);
}

/* PAL si la cabecera del cartucho solo dice Europa («E»); si no, NTSC (60 Hz), que es lo que mejor va a 30/60 fps. */
static cc_bool romIsPalOnly(const unsigned char *data, size_t size) {
    if (size < 0x200) return cc_false;
    cc_bool e = cc_false, other = cc_false;
    for (int i = 0x1F0; i < 0x1F3; i++) {
        unsigned char c = data[i];
        if (c == 'E') e = cc_true;
        else if (c == 'J' || c == 'U') other = cc_true;
        else if (c >= '0' && c <= '9') { if ((c - '0') & 0x5) other = cc_true; if ((c - '0') & 0x8) e = cc_true; }
        else if (c >= 'A' && c <= 'F') { int v = c - 'A' + 10; if (v & 0x5) other = cc_true; if (v & 0x8) e = cc_true; }
    }
    return e && !other;
}

JNIEXPORT jstring JNICALL
Java_com_headqlink_link_MdEngine_nLoad(JNIEnv *env, jclass cls, jbyteArray romData, jbyteArray sav) {
    (void)cls;
    if (!initDone) {
        callbacks.user_data = NULL;
        callbacks.colour_updated = ColourUpdated;
        callbacks.scanline_rendered = ScanlineRendered;
        callbacks.input_requested = InputRequested;
        callbacks.fm_audio_to_be_generated = FmAudio;
        callbacks.psg_audio_to_be_generated = PsgAudio;
        callbacks.pcm_audio_to_be_generated = PcmAudio;
        callbacks.cdda_audio_to_be_generated = CddaAudio;
        callbacks.cd_seeked = CdSeeked;
        callbacks.cd_sector_read = CdSectorRead;
        callbacks.cd_track_seeked = CdTrackSeeked;
        callbacks.cd_audio_read = CdAudioRead;
        callbacks.save_file_opened_for_reading = SaveOpenRead;
        callbacks.save_file_read = SaveRead;
        callbacks.save_file_opened_for_writing = SaveOpenWrite;
        callbacks.save_file_written = SaveWritten;
        callbacks.save_file_closed = SaveClosed;
        callbacks.save_file_removed = SaveRemoved;
        callbacks.save_file_size_obtained = SaveSize;
        ClownMDEmu_SetLogCallback(Log, NULL);
        ClownMDEmu_Constant_Initialise();
        ClownResampler_Precompute(&precomputed);
        initDone = cc_true;
    }
    loaded = cc_false;
    free(rom);
    rom = NULL;

    jsize n = (*env)->GetArrayLength(env, romData);
    if (n < 0x200) return (*env)->NewStringUTF(env, "ROM demasiado pequeña");
    unsigned char *data = (unsigned char *)malloc((size_t)n);
    if (!data) return (*env)->NewStringUTF(env, "sin memoria");
    (*env)->GetByteArrayRegion(env, romData, 0, n, (jbyte *)data);
    /* La ROM en palabras de 16 bits big-endian, como la lee el 68000. */
    size_t words = (size_t)n / 2;
    rom = (cc_u16l *)malloc(words * sizeof(cc_u16l));
    if (!rom) {
        free(data);
        return (*env)->NewStringUTF(env, "sin memoria");
    }
    for (size_t i = 0; i < words; i++) rom[i] = (cc_u16l)(data[i * 2] << 8 | data[i * 2 + 1]);
    pal = romIsPalOnly(data, (size_t)n);
    free(data);

    ClownMDEmu_InitialConfiguration config;
    memset(&config, 0, sizeof config);
    config.general.region = CLOWNMDEMU_REGION_OVERSEAS;
    config.general.tv_standard = pal ? CLOWNMDEMU_TV_STANDARD_PAL : CLOWNMDEMU_TV_STANDARD_NTSC;
    config.general.cd_add_on_enabled = cc_false;
    ClownMDEmu_Initialise(&emu, &config, &callbacks);
    setupAudio(pal);
    if (!mixerReady) return (*env)->NewStringUTF(env, "sin memoria para el sonido");
    ClownMDEmu_SetCartridge(&emu, rom, (cc_u32f)words);
    ClownMDEmu_HardReset(&emu, cc_true, cc_false);

    /* Partida guardada del cartucho (SRAM), si la había. */
    if (sav) {
        jsize s = (*env)->GetArrayLength(env, sav);
        if (s > (jsize)sizeof(emu.state.external_ram.buffer)) s = (jsize)sizeof(emu.state.external_ram.buffer);
        (*env)->GetByteArrayRegion(env, sav, 0, s, (jbyte *)emu.state.external_ram.buffer);
    }
    frameW = 320;
    frameH = pal ? 240 : 224;
    loaded = cc_true;
    return NULL;
}

/** Un fotograma: emula, copia la imagen (ARGB, ancho x alto del modo actual, ver nSize) y el sonido (estéreo,
    44,1 kHz). Devuelve las muestras escritas (dos por cada instante: izquierda y derecha). */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_MdEngine_nFrame(JNIEnv *env, jclass cls, jintArray argb, jshortArray audio, jint pad) {
    (void)cls;
    if (!loaded) return -1;
    buttons = pad;
    Mixer_Begin(&mixer);
    ClownMDEmu_Iterate(&emu);
    outFrames = 0;
    Mixer_End(&mixer, MixerDone, NULL);

    jsize cap = (*env)->GetArrayLength(env, argb);
    jsize px = (jsize)(frameW * frameH);
    if (px > cap) px = cap;
    (*env)->SetIntArrayRegion(env, argb, 0, px, (const jint *)frame);

    jsize acap = (*env)->GetArrayLength(env, audio);
    jsize samples = (jsize)(outFrames * 2);
    if (samples > acap) samples = acap;
    (*env)->SetShortArrayRegion(env, audio, 0, samples, outAudio);
    return samples;
}

/** Ancho y alto del último fotograma: (ancho << 16) | alto. */
JNIEXPORT jint JNICALL
Java_com_headqlink_link_MdEngine_nSize(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)((frameW << 16) | frameH);
}

JNIEXPORT jboolean JNICALL
Java_com_headqlink_link_MdEngine_nPal(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return pal ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_headqlink_link_MdEngine_nReset(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    if (loaded) ClownMDEmu_SoftReset(&emu, cc_true, cc_false);
}

/** Descarga el juego; devuelve la SRAM del cartucho (para guardarla en .sav) o null si no tiene. */
JNIEXPORT jbyteArray JNICALL
Java_com_headqlink_link_MdEngine_nUnload(JNIEnv *env, jclass cls) {
    (void)cls;
    if (!loaded) return NULL;
    loaded = cc_false;
    jbyteArray out = NULL;
    cc_u32l size = emu.state.external_ram.size;
    if (size > 0 && size <= sizeof(emu.state.external_ram.buffer) && emu.state.external_ram.non_volatile) {
        out = (*env)->NewByteArray(env, (jsize)size);
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)size, (const jbyte *)emu.state.external_ram.buffer);
    }
    free(rom);
    rom = NULL;
    return out;
}
