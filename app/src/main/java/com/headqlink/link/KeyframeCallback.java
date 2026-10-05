package com.headqlink.link;

/**
 * Resultado de pedir un IDR a una fuente sin encoder propio (ciclo de foco de vídeo de Android Auto). Se llama en el
 * hilo principal, una vez por petición.
 */
interface KeyframeCallback {
    /** started: el ciclo empezó; false: la palanca estaba ocupada (otro ciclo en curso) o AA no está conectado. */
    void onResult(boolean started);
}
