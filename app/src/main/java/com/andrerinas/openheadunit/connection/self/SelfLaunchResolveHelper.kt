package com.andrerinas.openheadunit.connection.self

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils

class SelfLaunchResolveHelper(private val service: AapService) {

    fun run() {
        // headqlink: con el coche conectado (vídeo de AA sin vista), nada de abrir la pantalla de permisos de Android Auto
        // en el móvil: tapaba el móvil ~11 s (viaje del 2026-10-09, 08:54) y en marcha no se puede atender.
        if (com.andrerinas.openheadunit.decoder.video.VideoTap.headless) {
            AppLog.w("SelfMode: AA no conectó; con el coche conectado no abro su pantalla de permisos en el móvil")
            return
        }
        openPermissionsCheck()
    }

    private fun openPermissionsCheck() {
        PermissionTrampolineActivity.launch(service) { hadMissingPermissions ->
            if (!hadMissingPermissions) {
                // Duration was below threshold (permissions already granted) or activity could not be started.
                // Since permissions are not the issue, check for other failure causes (e.g., system app).
                AppLog.w("SelfMode: AA permissions were already granted or activity failed to start; checking other causes.")
                onPermissionsCheckFailed()
            } else {
                // AA's permission screen ran to request missing permissions.
                AppLog.i("SelfMode: AA's permission screen was shown for missing permissions.")
                ToastUtils.showToast(
                    service,
                    service.getString(R.string.failed_start_android_auto),
                    Toast.LENGTH_LONG
                )
            }
        }
    }

    /** Called when permissions were already granted or activity failed to start, to check other causes. */
    private fun onPermissionsCheckFailed() {
        // is AA even installed?
        if (!isAAInstalled()) {
            ToastUtils.showToast(
                service,
                service.getString(R.string.failed_aa_not_installed),
                Toast.LENGTH_LONG
            )
            return
        }

        // AA isn't a system app
        if (!isAASystemApp()) {
            ToastUtils.showToast(
                service,
                service.getString(R.string.failed_aa_not_system),
                Toast.LENGTH_LONG
            )
            return
        }

        // idk why it failed :(
        ToastUtils.showToast(
            service,
            service.getString(R.string.failed_start_android_auto),
            Toast.LENGTH_LONG
        )
    }

    private fun isAAInstalled(): Boolean {
        return try {
            val pm = service.packageManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(SelfLauncherManager.AA_PACKAGE, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(SelfLauncherManager.AA_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun isAASystemApp(): Boolean {
        return try {
            val pm = service.packageManager

            // Get ApplicationInfo based on Android version
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(SelfLauncherManager.AA_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(SelfLauncherManager.AA_PACKAGE, 0)
            }

            // Check if it's a factory system app OR an updated system app
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystem = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

            isSystem || isUpdatedSystem

        } catch (e: PackageManager.NameNotFoundException) {
            // App is not installed at all
            false
        }
    }
}
