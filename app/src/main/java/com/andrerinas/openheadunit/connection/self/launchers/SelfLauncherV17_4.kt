package com.andrerinas.openheadunit.connection.self.launchers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager.Companion.AA_PACKAGE
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherManual
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class SelfLauncherV17_4(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "v17.4+"

    override suspend fun run(): Boolean {
        AppLog.i("SelfMode: === v17.4+ launcher starting ===")
        diagnostics.clear()

        if (tryWirelessStartupReceiver()) return true
        if (tryWirelessProjectionBroadcast()) return true
        return tryDevServer()
    }

    // ── Path 1: WirelessStartupReceiver broadcast ──────────────────────────

    private suspend fun tryWirelessStartupReceiver(): Boolean {
        val enabled = isComponentEnabled(RECEIVER_CLASS)
        diag("WirelessStartupReceiver", if (enabled) "ENABLED" else "DISABLED")

        if (!enabled) return false

        AppLog.i("SelfMode: [Path 1] WirelessStartupReceiver enabled; trying broadcast on 5288...")
        startWirelessServer()
        waitForActiveNetwork()

        val intent = Intent().apply {
            setClassName(AA_PACKAGE, RECEIVER_CLASS)
            action = ACTION_WIRELESS_STARTUP
            putExtra("ip_address", "127.0.0.1")
            putExtra("projection_port", 5288)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        services.aap.sendBroadcast(intent)
        diag("Path1:broadcast", "SENT to WirelessStartupReceiver (127.0.0.1:5288)")

        val connected = waitForConnection(BRIDGE_TIMEOUT_MS)
        diag("Path1:result", if (connected) "CONNECTED" else "TIMEOUT ${BRIDGE_TIMEOUT_MS}ms")
        return connected
    }

    // ── Path 2: START_WIRELESS_PROJECTION → WifiBluetoothReceiver ──────────

    private suspend fun tryWirelessProjectionBroadcast(): Boolean {
        val enabled = isComponentEnabled(WIFI_BT_RECEIVER_CLASS)
        diag("WifiBluetoothReceiver", if (enabled) "ENABLED" else "DISABLED")

        if (!enabled) return false

        AppLog.i("SelfMode: [Path 2] WifiBluetoothReceiver enabled; sending START_WIRELESS_PROJECTION...")
        startWirelessServer()
        waitForActiveNetwork()

        val intent = Intent().apply {
            setClassName(AA_PACKAGE, WIFI_BT_RECEIVER_CLASS)
            action = ACTION_START_WIRELESS_PROJECTION
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        services.aap.sendBroadcast(intent)
        diag("Path2:broadcast", "SENT START_WIRELESS_PROJECTION")

        val connected = waitForConnection(PROJECTION_TIMEOUT_MS)
        diag("Path2:result", if (connected) "CONNECTED" else "TIMEOUT ${PROJECTION_TIMEOUT_MS}ms")
        return connected
    }

    // ── Path 3: Developer head-unit server (fallback) ──────────────────────

    private suspend fun tryDevServer(): Boolean {
        AppLog.i("SelfMode: [Path 3] Trying dev server on 127.0.0.1:5277...")
        diag("Path3", "dev server 5277")

        var success = withContext(Dispatchers.IO) {
            commManager.connect("127.0.0.1", 5277)
            commManager.isConnected
        }
        diag("Path3:connect1", if (success) "OK" else "REFUSED")

        if (!success && !commManager.isConnected) {
            diag("Path3", "starting AA server via accessibility...")
            success = withContext(Dispatchers.IO) {
                if (com.headqlink.link.AaServerStarter.startAndWait(services.aap)) {
                    Thread.sleep(1500)
                    commManager.connect("127.0.0.1", 5277)
                }
                commManager.isConnected
            }
            diag("Path3:connect2", if (success) "OK" else "FAILED")
        }

        if (!success && !commManager.isConnected && com.andrerinas.openheadunit.decoder.video.VideoTap.headless) {
            diag("Path3:result", "CANNOT_START (headless)")
            dumpDiagnostics()
            com.headqlink.link.AaServerStarter.reportCannotStart(services.aap)
            return false
        }

        if (!success && !commManager.isConnected) {
            diag("Path3:result", "FAILED — prompting user")
            dumpDiagnostics()
            AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
            ToastUtils.showToast(
                services.aap,
                "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                Toast.LENGTH_LONG
            )
            manager.openAaSettings()
            return false
        }

        diag("Path3:result", "CONNECTED via dev server")
        dumpDiagnostics()
        return true
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun isComponentEnabled(className: String): Boolean {
        return try {
            val component = ComponentName(AA_PACKAGE, className)
            val state = services.aap.packageManager.getComponentEnabledSetting(component)
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } catch (e: Exception) {
            AppLog.w("SelfMode: Failed to check $className state: ${e.message}")
            false
        }
    }

    private fun startWirelessServer() {
        services.wifiLauncherManager.sharedServices.startWirelessServer(
            services.wifiLauncherManager.active ?: WifiLauncherManual(services.wifiLauncherManager)
        )
    }

    private suspend fun waitForActiveNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val cm = services.aap.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val had = cm.activeNetwork != null
        if (had) {
            diag("activeNetwork", "already present")
            return
        }
        for (i in 1..10) {
            if (cm.activeNetwork != null) {
                diag("activeNetwork", "appeared after ${i * 100}ms")
                return
            }
            delay(100)
        }
        diag("activeNetwork", "ABSENT after 1000ms (dummy VPN may not be up)")
    }

    private suspend fun waitForConnection(timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (commManager.isConnected) {
                AppLog.i("SelfMode: AA connected via wireless bridge!")
                return true
            }
            delay(250)
        }
        return false
    }

    // ── Diagnostics ────────────────────────────────────────────────────────

    private val diagnostics = mutableListOf<Pair<String, String>>()

    private fun diag(key: String, value: String) {
        diagnostics.add(key to value)
        AppLog.i("SelfMode: diag [$key] $value")
    }

    private fun dumpDiagnostics() {
        val sb = StringBuilder("SelfMode v17.4+ diagnostics:\n")
        for ((k, v) in diagnostics) sb.append("  $k = $v\n")
        AppLog.i(sb.toString())
    }

    companion object {
        private const val RECEIVER_CLASS =
            "com.google.android.apps.auto.wireless.setup.receiver.WirelessStartupReceiver"
        private const val WIFI_BT_RECEIVER_CLASS =
            "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver"

        private const val ACTION_WIRELESS_STARTUP =
            "com.google.android.apps.auto.wireless.setup.receiver.wirelessstartup.START"
        private const val ACTION_START_WIRELESS_PROJECTION =
            "com.google.android.projection.gearhead.START_WIRELESS_PROJECTION"

        private const val BRIDGE_TIMEOUT_MS = 8000L
        private const val PROJECTION_TIMEOUT_MS = 12000L
    }
}
