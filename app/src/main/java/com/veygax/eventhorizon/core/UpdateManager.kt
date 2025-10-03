package com.veygax.eventhorizon.core

import android.content.Context
import android.util.Log
import com.veygax.eventhorizon.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val TIMEOUT_MS = 15000 // 15 seconds

    data class ReleaseInfo(val version: String, val changelog: String, val downloadUrl: String)

    /**
     * Checks GitHub for the latest release and compares it with the current app version.
     * @return ReleaseInfo if a newer version is found, otherwise null.
     */
    suspend fun checkForUpdate(context: Context, owner: String, repo: String): ReleaseInfo? {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"

                val url = URL(apiUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                val jsonText = connection.inputStream.bufferedReader().readText()

                val json = JSONObject(jsonText)

                val latestVersion = json.getString("tag_name")
                val changelog = json.getString("body")
                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null

                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url")
                        break
                    }
                }

                if (apkUrl == null) {
                    Log.e("UpdateManager", "No APK found in the latest release.")
                    return@withContext null
                }

                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val currentVersion = packageInfo.versionName
                if (currentVersion == null) {
                    Log.e("UpdateManager", "Could not get current app version.")
                    return@withContext null
                }


                if (isNewerVersion(latestVersion, currentVersion)) {
                    return@withContext ReleaseInfo(latestVersion, changelog, apkUrl)
                } else {
                    return@withContext null
                }

            } catch (e: Exception) {
                Log.e("UpdateManager", "Failed to check for updates: ${e.message}")
                e.printStackTrace()
                return@withContext null
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Compares version strings.
     */
    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        val cleanLatest = latestVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val cleanCurrent = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }

        if (cleanLatest.isEmpty() || cleanCurrent.isEmpty()) return false

        val maxLen = maxOf(cleanLatest.size, cleanCurrent.size)
        for (i in 0 until maxLen) {
            val latestPart = cleanLatest.getOrElse(i) { 0 }
            val currentPart = cleanCurrent.getOrElse(i) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false // Versions are identical
    }


    /**
     * Downloads an APK from a URL and installs it using root.
     */
    suspend fun downloadAndInstallUpdate(
        context: Context,
        url: String,
        onProgress: (Float) -> Unit,
        onStatusUpdate: (String) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            // **FIX:** Declare apkFile here, in the outer scope.
            val apkFile = File(context.cacheDir, "update.apk")
            try {
                onStatusUpdate("Starting download...")
                onProgress(0f)

                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.connect()

                val fileLength = connection.contentLength

                connection.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val data = ByteArray(4096)
                        var total: Long = 0
                        var count: Int
                        while (input.read(data).also { count = it } != -1) {
                            total += count
                            if (fileLength > 0) {
                                val progress = total.toFloat() / fileLength
                                withContext(Dispatchers.Main) { onProgress(progress) }
                            }
                            output.write(data, 0, count)
                        }
                    }
                }

                onStatusUpdate("Download complete. Installing...")
                onProgress(1f)

                val result = RootUtils.runAsRoot("pm install -r \"${apkFile.absolutePath}\"")
                apkFile.delete()

                if (result.trim() != "Success") {
                    onStatusUpdate("Installation failed. See logs for details.")
                    Log.e("UpdateManager", "Install failed: $result")
                }
            } catch (e: Exception) {
                onStatusUpdate("An error occurred: ${e.message}")
                Log.e("UpdateManager", "Download/Install error", e)
            } finally {
                connection?.disconnect()
            }
        }
    }
}