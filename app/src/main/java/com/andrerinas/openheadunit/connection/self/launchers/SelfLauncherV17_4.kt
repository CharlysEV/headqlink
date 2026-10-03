package com.andrerinas.openheadunit.connection.self.launchers

import android.widget.Toast
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SelfLauncherV17_4(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.4+"

    override suspend fun run(): Boolean {
        // Call withContext directly, not on 'service'
        var success = withContext(Dispatchers.IO) {
            commManager.connect("127.0.0.1", 5277)
            commManager.isConnected
        }

        // c10link: si el servidor de head unit no está arrancado, lo arrancamos pulsando su menú
        // con el servicio de accesibilidad y reintentamos una vez.
        if (!success && !commManager.isConnected) {
            success = withContext(Dispatchers.IO) {
                if (com.c10link.link.AaServerStarter.startAndWait(services.aap)) {
                    Thread.sleep(1500)
                    commManager.connect("127.0.0.1", 5277)
                }
                commManager.isConnected
            }
        }

        if (!success && !commManager.isConnected && com.andrerinas.openheadunit.decoder.video.VideoTap.headless) {
            // c10link: nunca abrir los ajustes de AA a la vista; C10Link muestra el motivo.
            com.c10link.link.AaServerStarter.reportCannotStart(services.aap)
            return false
        }

        if (!success && !commManager.isConnected) {
            AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
            ToastUtils.showToast(
                services.aap,
                "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                Toast.LENGTH_LONG
            )
            manager.openAaSettings()
            return false
        }

        return true
    }
}
