package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * c10link: hace sin vista lo que AapProjectionActivity hace al tener su superficie lista - arrancar
 * la lectura cuando el handshake termina - para que Android Auto avance aunque no se muestre nada
 * en el móvil (modo [VideoTap.headless]). El foco de vídeo lo da AapTransport.gainVideoFocus() cuando
 * AA configura el canal de vídeo; el handshake lo inicia AapService por su cuenta.
 */
object HeadlessDriver {
    private var job: Job? = null

    @JvmStatic
    fun start(context: Context) {
        val cm = App.provide(context).commManager
        job?.cancel()
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            cm.connectionState.collect { state ->
                when (state) {
                    is CommManager.ConnectionState.HandshakeComplete -> {
                        AppLog.i("HeadlessDriver: handshake completo, arranco la lectura")
                        launch { cm.startReading() }
                    }
                    else -> Unit
                }
            }
        }
    }

    @JvmStatic
    fun stop() {
        job?.cancel()
        job = null
    }
}
