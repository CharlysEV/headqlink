package com.grapeshot.halfnes.audio;

/** Salida de audio que no suena (sin fábrica de audio, o en las pruebas). */
public final class SilentAudio implements AudioOutInterface {
    @Override
    public void outputSample(int sample) {
    }

    @Override
    public void flushFrame(boolean waitIfBufferFull) {
    }

    @Override
    public void pause() {
    }

    @Override
    public void resume() {
    }

    @Override
    public void destroy() {
    }

    @Override
    public boolean bufferHasLessThan(int samples) {
        return true;
    }
}
