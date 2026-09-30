package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.ssl.ConscryptInitializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preReleaseStage: Int, // 0 = alpha, 1 = beta, 2 = rc, 3 = stable release
    val preReleaseNumber: Int
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preReleaseStage != other.preReleaseStage) return preReleaseStage.compareTo(other.preReleaseStage)
        return preReleaseNumber.compareTo(other.preReleaseNumber)
    }

    companion object {
        fun parse(versionStr: String): AppVersion? {
            val clean = versionStr.trim()
                .removePrefix("v.")
                .removePrefix("V.")
                .removePrefix("v")
                .removePrefix("V")
                .trim()

            val parts = clean.split("-")
            val numParts = parts[0].split(".")
            val major = numParts.getOrNull(0)?.toIntOrNull() ?: return null
            val minor = numParts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = numParts.getOrNull(2)?.toIntOrNull() ?: 0

            var stage = 3 // default: stable
            var preNum = 0

            if (parts.size > 1) {
                val suffix = parts[1].lowercase()
                when {
                    suffix.startsWith("alpha") -> {
                        stage = 0
                        preNum = suffix.removePrefix("alpha").toIntOrNull() ?: 0
                    }
                    suffix.startsWith("beta") -> {
                        stage = 1
                        preNum = suffix.removePrefix("beta").toIntOrNull() ?: 0
                    }
                    suffix.startsWith("rc") -> {
                        stage = 2
                        preNum = suffix.removePrefix("rc").toIntOrNull() ?: 0
                    }
                }
            }

            return AppVersion(major, minor, patch, stage, preNum)
        }
    }
}

data class UpdateInfo(
    val isUpdateAvailable: Boolean,
    val latestVersionName: String,
    val currentVersionName: String,
    val releaseUrl: String,
    val isPlayStore: Boolean
)

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/andreknieriem/open-headunit/releases?per_page=5"
    const val GITHUB_RELEASES_WEB = "https://github.com/andreknieriem/open-headunit/releases"

    fun isPlayStoreInstallation(context: Context): Boolean {
        if (BuildConfig.FLAVOR == "playstore") return true
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            installer == "com.android.vending"
        } catch (_: Exception) {
            false
        }
    }

    suspend fun check(context: Context): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_RELEASES_API)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "OpenHeadunit-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            if (conn is HttpsURLConnection) {
                ConscryptInitializer.httpsSocketFactory(context)?.let { conn.sslSocketFactory = it }
            }

            val code = conn.responseCode
            if (code != 200) {
                return@withContext Result.failure(Exception("HTTP error $code from GitHub API"))
            }

            val responseText = conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }

            val releasesJson = JSONArray(responseText)
            if (releasesJson.length() == 0) {
                return@withContext Result.failure(Exception("No releases found"))
            }

            var newestVersion: AppVersion? = null
            var newestRawTag = ""
            var newestUrl = GITHUB_RELEASES_WEB

            for (i in 0 until releasesJson.length()) {
                val rel = releasesJson.getJSONObject(i)
                val isDraft = rel.optBoolean("draft", false)
                if (isDraft) continue

                val tagName = rel.optString("tag_name", "")
                val parsed = AppVersion.parse(tagName) ?: continue

                if (newestVersion == null || parsed > newestVersion) {
                    newestVersion = parsed
                    newestRawTag = tagName.removePrefix("v.").removePrefix("v")
                    newestUrl = rel.optString("html_url", GITHUB_RELEASES_WEB)
                }
            }

            if (newestVersion == null) {
                return@withContext Result.failure(Exception("Could not parse any release versions"))
            }

            val currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME)
            val isUpdateAvailable = if (currentVersion != null) {
                newestVersion > currentVersion
            } else {
                false
            }

            val isPlayStore = isPlayStoreInstallation(context)

            Result.success(
                UpdateInfo(
                    isUpdateAvailable = isUpdateAvailable,
                    latestVersionName = newestRawTag,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    releaseUrl = newestUrl,
                    isPlayStore = isPlayStore
                )
            )
        } catch (e: Exception) {
            AppLog.e("$TAG: Update check failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    fun openPlayStore(context: Context) {
        val pkg = context.packageName
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e: Exception) {
                AppLog.e("$TAG: Failed to open Play Store", e)
            }
        }
    }

    fun openGitHubReleases(context: Context, url: String = GITHUB_RELEASES_WEB) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            AppLog.e("$TAG: Failed to open GitHub releases URL: $url", e)
        }
    }
}
